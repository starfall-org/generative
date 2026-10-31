package org.starfall.generative

import android.graphics.Bitmap
import android.view.accessibility.AccessibilityNodeInfo
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.espresso.Espresso
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.starfall.generative.features.home.BottomSheetsLink
import org.starfall.generative.features.home.BottomSheetsLinkState
import org.starfall.generative.features.home.ModelSheetPage
import org.starfall.generative.shared.model.api.ModelApiType
import org.starfall.generative.shared.model.language.ModelConfiguration
import org.starfall.generative.shared.model.language.ThinkingPassback
import org.starfall.generative.shared.model.language.passbackMode
import org.starfall.generative.shared.model.provider.ModelProvider
import org.starfall.generative.shared.model.provider.ProviderConfiguration

/** Runs on a device to verify actual sheet rendering, navigation and persistence callbacks. */
class SelectedModelOptionsDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val modelId = "alpha"

    @Composable
    private fun Host(
        sheets: BottomSheetsLinkState,
        configuration: ModelConfiguration,
        onSetPassback: (ThinkingPassback) -> Unit = {},
        onSetTools: (List<String>) -> Unit = {}
    ) {
        val provider = ModelProvider(
            "provider", "Demo Provider", ModelApiType.OPENAI, baseUrl = "",
            config = ProviderConfiguration(
                modelIds = listOf(modelId),
                modelConfigs = mapOf(modelId to configuration)
            )
        )
        MaterialTheme {
            BottomSheetsLink(
                sheets = sheets,
                mediaKind = null,
                onMediaKindChange = {}, onCancelEdit = {}, onShowSkillsMenu = {},
                providers = listOf(provider), providerGroups = emptyList(),
                selectedProviderId = provider.id, selectedModelName = modelId,
                conversationReasoningEffort = null,
                onSetReasoningEffort = {}, onSelectModel = { _, _ -> },
                onSetThinkingPassback = { _, id, mode ->
                    assertEquals(modelId, id)
                    onSetPassback(mode)
                },
                onSetModelReasoningEffort = { _, _, _ -> },
                onSetModelTools = { _, id, tools ->
                    assertEquals(modelId, id)
                    onSetTools(tools)
                },
                onEditModel = { _, _ -> }, onEditProvider = {},
                modelPickerCollapsedGroups = emptySet(), modelPickerCollapsedProviders = emptySet(),
                onModelPickerCollapsedGroupsChange = {}, onModelPickerCollapsedProvidersChange = {},
                dynamicModelsMap = emptyMap(),
                mediaProviders = emptyList(), mediaProviderId = "", mediaModelId = "",
                onMediaModelChange = { _, _ -> },
                attachments = emptyList(), onAttachmentsChange = {}
            )
        }
    }

    @Test fun nativeAccessibilityTreeAndScreenshotSmokeTest() {
        val sheets = BottomSheetsLinkState().apply { openSelectedModelOverview() }
        compose.setContent {
            Host(sheets, ModelConfiguration(displayName = "Demo Model", supportsThinking = true))
        }
        Thread.sleep(1500)
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val root = automation.rootInActiveWindow
        val found = mutableListOf<String>()
        fun collect(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || depth > 25 || found.size > 150) return
            val text = node.text?.toString().orEmpty()
            val description = node.contentDescription?.toString().orEmpty()
            if (text.isNotEmpty() || description.isNotEmpty() || node.isClickable)
                found += "${node.className}: text=$text desc=$description click=${node.isClickable}"
            for (i in 0 until node.childCount) collect(node.getChild(i), depth + 1)
        }
        collect(root, 0)
        found.forEach { Log.i("ModelOptionsAccess", it) }
        takeScreenshot("selected_model_accessibility_smoke")
        // Some OEM UIAutomation bridges return null for modal Dialog windows although
        // Compose semantics, touch injection and screenshots continue to work.
        if (root != null) {
            assertTrue("Model card missing from Android accessibility tree: $found",
                found.any { it.contains("Demo Model") })
        }
        compose.onNodeWithText("Demo Model").assertIsDisplayed()
    }

    @Test fun tappingModelCardOpensOptionsAndPersistsThinkingAndToolsAcrossBack() {
        var config by mutableStateOf(ModelConfiguration(
            displayName = "Demo Model", supportsThinking = true,
            modelTools = listOf("web_search", "file_search"),
            enabledModelTools = listOf("file_search")
        ))
        val sheets = BottomSheetsLinkState().apply { openSelectedModelOverview() }
        compose.setContent {
            Host(sheets, config,
                onSetPassback = { config = config.copy(thinkingPassback = it) },
                onSetTools = { config = config.copy(enabledModelTools = it) })
        }
        compose.onNodeWithTag("selected-model-card").assertIsDisplayed()
        compose.onNodeWithTag("reasoning-slider").assertExists()
        compose.onNodeWithTag("passback-thinking-mode").assertDoesNotExist()
        compose.onNodeWithTag("model-tool-switch-web_search").assertDoesNotExist()
        takeScreenshot("selected_model_overview")

        compose.onNodeWithText("Demo Model").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("selected-model-options-sheet").assertIsDisplayed()
        compose.onNodeWithTag("model-options-appbar").assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.model_options_title)).assertIsDisplayed()
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.common_back)).assertIsDisplayed()
        compose.onNodeWithTag("passback-thinking-mode").assertIsDisplayed()
        compose.onNodeWithTag("model-tool-switch-web_search").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { assertEquals(ModelSheetPage.MODEL_OPTIONS, sheets.modelSheetPage) }
        takeScreenshot("selected_model_options")

        compose.onNodeWithTag("passback-thinking-on").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(ThinkingPassback.ON, config.passbackMode) }
        compose.onNodeWithTag("model-tool-switch-web_search").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(setOf("web_search", "file_search"), config.enabledModelTools.toSet()) }
        compose.onNodeWithTag("model-tool-switch-file_search").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf("web_search"), config.enabledModelTools) }
        takeScreenshot("selected_model_options_configured")

        compose.onNodeWithContentDescription(compose.activity.getString(R.string.common_back)).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("selected-model-card").assertIsDisplayed()
        compose.onNodeWithTag("reasoning-slider").assertExists()
        compose.onNodeWithTag("passback-thinking-mode").assertDoesNotExist()
        compose.runOnIdle { assertEquals(ModelSheetPage.SELECTED_MODEL, sheets.modelSheetPage) }

        compose.onNodeWithText("Demo Model").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("passback-thinking-on").assertIsSelected()
        compose.onNodeWithTag("model-tool-switch-web_search").assertIsOn()
        compose.onNodeWithTag("model-tool-switch-file_search").assertIsOff()
    }

    @Test fun optionsWithoutSupportedFeaturesShowUsefulEmptyStateAndReturn() {
        val sheets = BottomSheetsLinkState().apply { openSelectedModelOverview() }
        compose.setContent {
            Host(sheets, ModelConfiguration(displayName = "Simple Model", supportsThinking = false))
        }
        compose.onNodeWithText("Simple Model").performClick()
        compose.onNodeWithTag("selected-model-options-sheet").assertIsDisplayed()
        compose.onNodeWithTag("model-options-empty").assertIsDisplayed()
        compose.onNodeWithTag("passback-thinking-mode").assertDoesNotExist()
        compose.onNodeWithTag("model-tool-switch-web_search").assertDoesNotExist()
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.common_back)).performClick()
        compose.onNodeWithTag("selected-model-card").assertIsDisplayed()
    }

    @Test fun systemBackReturnsToOverviewInsteadOfDismissingTheModal() {
        val sheets = BottomSheetsLinkState().apply { openSelectedModelOverview() }
        compose.setContent {
            Host(sheets, ModelConfiguration(displayName = "Demo Model", supportsThinking = true))
        }
        compose.onNodeWithText("Demo Model").performClick()
        compose.onNodeWithTag("selected-model-options-sheet").assertIsDisplayed()
        Espresso.pressBack()
        compose.waitForIdle()
        compose.onNodeWithTag("selected-model-card").assertIsDisplayed()
        compose.runOnIdle { assertEquals(ModelSheetPage.SELECTED_MODEL, sheets.modelSheetPage) }
    }

    private fun takeScreenshot(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val file = File(instrumentation.targetContext.getExternalFilesDir(null), "$name.png")
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
            ?: error("Failed to capture screenshot: $name")
        file.outputStream().use { stream ->
            assertTrue("Unable to encode screenshot $name", bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream))
        }
        bitmap.recycle()
        Log.i("ModelOptionsDeviceTest", "Screenshot saved: ${file.absolutePath}")
    }
}
