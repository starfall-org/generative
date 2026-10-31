package org.starfall.generative

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.starfall.generative.features.home.shared.ui.*
import org.starfall.generative.features.speech.ui.SpeechEnginePickerSheet
import org.starfall.generative.features.customization.ui.MediaStoragePhotoSheet
import org.starfall.generative.features.storage.ui.MediaStorageSheet
import org.starfall.generative.shared.files.ToolFiles
import org.starfall.generative.shared.model.SpeechEngine
import java.io.File

/** On-device surface regressions for sheets using the content-sized Material scaffold. */
class SheetMigrationVisualDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun setSheet(content: @Composable () -> Unit) {
        compose.setContent { MaterialTheme { content() } }
        compose.onNodeWithTag("app-bottom-sheet").assertExists()
        compose.onNodeWithTag("bottom-sheet-drag-handle").assertExists()
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val bitmap = compose.activityRule.scenario.let {
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        } ?: error("Could not capture $name")
        val file = File(compose.activity.getExternalFilesDir(null), "sheet_migration_$name.png")
        file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
    }

    @Test fun quickActionsGenerationCardsShareRowAndRemainVisible() {
        var imageClicks = 0
        var videoClicks = 0
        setSheet {
            QuickActionsSheet(
                onPickImage = {}, onPickDocument = {}, onTakePhoto = {},
                onCreateImage = { imageClicks++ }, onCreateVideo = { videoClicks++ },
                onOpenInstructions = {}, onOpenFeatureTools = {}, onOpenMcpConnectors = {},
                onDismiss = {}
            )
        }
        compose.onNodeWithText(compose.activity.getString(R.string.quick_action_photos)).assertIsDisplayed()
        compose.onNodeWithTag("quick-actions-generation-row").assertIsDisplayed()
        val image = compose.onNodeWithTag("quick-actions-create-image").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val video = compose.onNodeWithTag("quick-actions-create-video").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue("Generation cards must be on same row", kotlin.math.abs(image.top - video.top) < 2f)
        assertTrue("Generation cards overlap", image.right < video.left)
        compose.onNodeWithText(compose.activity.getString(R.string.quick_action_image_description)).assertDoesNotExist()
        compose.onNodeWithText(compose.activity.getString(R.string.quick_action_video_description)).assertDoesNotExist()
        screenshot("quick_actions")
        compose.onNodeWithTag("quick-actions-create-image").performClick()
        compose.onNodeWithTag("quick-actions-create-video").performClick()
        compose.runOnIdle { assertTrue(imageClicks == 1 && videoClicks == 1) }
        compose.onNodeWithText(compose.activity.getString(R.string.common_mcp_connectors)).performScrollTo().assertIsDisplayed()
    }

    @Test fun featureToolsSheetRendersHeaderAndLastAction() {
        setSheet { FeatureToolsSheet(onDismiss = {}) }
        compose.onNodeWithText(compose.activity.getString(R.string.common_feature_tools)).assertIsDisplayed()
        screenshot("feature_tools")
        compose.onNodeWithText(compose.activity.getString(R.string.tool_search_tools_title)).performScrollTo().assertIsDisplayed()
    }

    @Test fun mcpConnectorsSheetRendersEmptyAndToolbar() {
        setSheet { McpConnectorsSheet(onDismiss = {}) }
        compose.onNodeWithText(compose.activity.getString(R.string.common_mcp_connectors)).assertIsDisplayed()
        screenshot("mcp_connectors")
    }

    @Test fun instructionsSheetRendersHeader() {
        setSheet { InstructionsSheet(onDismiss = {}) }
        compose.onNodeWithText(compose.activity.getString(R.string.common_instructions)).assertIsDisplayed()
        screenshot("instructions")
    }

    @Test fun summarySheetRendersScrollableFields() {
        setSheet { ConversationSummarySheet(onStart = { true }, onDismiss = {}) }
        compose.onNodeWithTag("summary-sheet-content").assertIsDisplayed()
        screenshot("summary")
    }

    @Test fun reasoningEffortSheetRendersControls() {
        setSheet { ReasoningEffortSheet(currentEffort = "medium", onApply = {}, onDismiss = {}) }
        compose.onNodeWithText(compose.activity.getString(R.string.reasoning_effort_title)).assertIsDisplayed()
        screenshot("reasoning")
    }

    @Test fun speechEnginePickerRendersEngineChoices() {
        setSheet {
            SpeechEnginePickerSheet(engine = SpeechEngine.SYSTEM, providerId = "", providers = emptyList(),
                modelsByProvider = emptyMap(), onSelectSystem = {}, onSelectStandalone = {},
                onSelectProvider = {}, onDismiss = {})
        }
        compose.onNodeWithTag("speech-engine-option-SYSTEM").assertIsDisplayed()
        screenshot("speech_engines")
    }

    @Test fun mediaStorageSheetRendersWithoutPhotos() {
        val store = ToolFiles(File(compose.activity.cacheDir, "test_migration_empty_media"))
        setSheet { MediaStorageSheet(store = store, onDismiss = {}) }
        compose.onNodeWithTag("media-storage-sheet").assertIsDisplayed()
        screenshot("media_storage")
    }

    @Test fun profilePhotoPickerRendersWithoutPhotos() {
        setSheet { MediaStoragePhotoSheet(onDismiss = {}, onSelected = {}) }
        compose.onNodeWithTag("bottom-sheet-window").assertExists()
        screenshot("profile_photos")
    }
}
