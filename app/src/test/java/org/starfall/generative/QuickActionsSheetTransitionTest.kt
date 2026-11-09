package org.starfall.generative

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.core.app.ActivityOptionsCompat
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.starfall.generative.features.home.BottomSheetsLink
import org.starfall.generative.features.home.BottomSheetsLinkState
import org.starfall.generative.shared.model.McpInfo
import org.starfall.generative.shared.model.ToolSettings
import org.starfall.generative.features.home.shared.ui.LocalToolControls
import org.starfall.generative.features.home.shared.ui.ToolControls

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w360dp-h800dp-xhdpi")
class QuickActionsSheetTransitionTest {
    @get:Rule val compose = createComposeRule()

    @Before
    fun refreshFileProviderRoots() {
        // Robolectric changes the app directory per test; FileProvider caches roots statically.
        val context = ApplicationProvider.getApplicationContext<Context>()
        val info = requireNotNull(context.packageManager.resolveContentProvider(
            "${context.packageName}.tool-files", android.content.pm.PackageManager.GET_META_DATA
        ))
        FileProvider().attachInfo(context, info)
    }

    @Composable
    private fun LinkedQuickActionsHost(
        controls: ToolControls,
        attachments: List<String> = emptyList(),
        temporaryChatId: String? = null,
        onAttachmentsChange: (List<String>) -> Unit = {}
    ) {
        MaterialTheme {
            val sheets = remember { BottomSheetsLinkState().apply { openAddMenu() } }
            CompositionLocalProvider(LocalToolControls provides controls) {
                BottomSheetsLink(
                    sheets = sheets,
                    temporaryChatId = temporaryChatId,
                    mediaKind = null,
                    onMediaKindChange = {},
                    onCancelEdit = {},
                    onShowSkillsMenu = {},
                    providers = emptyList(),
                    providerGroups = emptyList(),
                    selectedProviderId = "",
                    selectedModelName = "",
                    conversationReasoningEffort = null,
                    onSetReasoningEffort = {},
                    onSelectModel = { _, _ -> },
                    onSetThinkingPassback = { _, _, _ -> },
                    onSetModelReasoningEffort = { _, _, _ -> },
                    onSetModelTools = { _, _, _ -> },
                    onEditModel = { _, _ -> },
                    onEditProvider = {},
                    modelPickerCollapsedGroups = emptySet(),
                    modelPickerCollapsedProviders = emptySet(),
                    onModelPickerCollapsedGroupsChange = {},
                    onModelPickerCollapsedProvidersChange = {},
                    dynamicModelsMap = emptyMap(),
                    mediaProviders = emptyList(),
                    mediaProviderId = "",
                    mediaModelId = "",
                    onMediaModelChange = { _, _ -> },
                    attachments = attachments,
                    onAttachmentsChange = onAttachmentsChange
                )
            }
        }
    }

    private class CameraRegistry : ActivityResultRegistry(), ActivityResultRegistryOwner {
        override val activityResultRegistry: ActivityResultRegistry get() = this
        var intent: Intent? = null
        var requestCode = 0
        var failLaunch = false

        override fun <I, O> onLaunch(
            requestCode: Int,
            contract: ActivityResultContract<I, O>,
            input: I,
            options: ActivityOptionsCompat?
        ) {
            if (failLaunch) throw android.content.ActivityNotFoundException()
            this.requestCode = requestCode
            intent = contract.createIntent(ApplicationProvider.getApplicationContext(), input)
        }
    }

    private fun capturePhoto(success: Boolean, temporary: Boolean = false) {
        val registry = CameraRegistry()
        var attachments = listOf("content://existing/image")
        val context = ApplicationProvider.getApplicationContext<Context>()
        val temporaryChatId = "camera-test".takeIf { temporary }
        val photoDirectory = if (temporary) {
            java.io.File(context.cacheDir, "temporary_chat_media/$temporaryChatId").apply { mkdirs() }
        } else org.starfall.generative.shared.subfeatures.media.logic.ToolFiles.appStorageDirectory(context)
        compose.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides registry) {
                LinkedQuickActionsHost(ToolControls(settings = ToolSettings()), attachments, temporaryChatId) { attachments = it }
            }
        }
        compose.onNodeWithText("Camera").performClick()
        compose.waitForIdle()
        val intent = requireNotNull(registry.intent) {
            org.robolectric.shadows.ShadowLog.getLogs().joinToString { "${it.msg}: ${it.throwable}" }
        }
        assertEquals(MediaStore.ACTION_IMAGE_CAPTURE, intent.action)
        val uri = androidx.core.content.IntentCompat.getParcelableExtra(intent, MediaStore.EXTRA_OUTPUT, Uri::class.java)!!
        val resolver = ApplicationProvider.getApplicationContext<Context>().contentResolver
        resolver.openOutputStream(uri)!!.use { it.write(byteArrayOf(1, 2, 3)) }
        compose.runOnIdle {
            registry.dispatchResult(registry.requestCode, if (success) Activity.RESULT_OK else Activity.RESULT_CANCELED, null)
        }
        compose.waitForIdle()
        if (success) {
            assertEquals("content://existing/image", attachments.first())
            assertEquals(2, attachments.size)
            val storedPhoto = java.io.File(requireNotNull(Uri.parse(attachments.last()).path))
            assertEquals(photoDirectory, storedPhoto.parentFile)
            assertEquals(3L, storedPhoto.length())
            resolver.openInputStream(uri)!!.use { assertEquals(3, it.readBytes().size) }
            resolver.delete(uri, null, null)
        } else {
            assertEquals(listOf("content://existing/image"), attachments)
            assertEquals(0, resolver.delete(uri, null, null))
        }
    }

    @Test
    fun cameraAttachesCapturedPhotoAlongsideExistingAttachments() = capturePhoto(success = true)

    @Test
    fun canceledCameraDeletesCaptureWithoutChangingAttachments() = capturePhoto(success = false)

    @Test
    fun temporaryChatCaptureStaysInTheSessionCache() = capturePhoto(success = true, temporary = true)

    @Test
    fun missingCameraShowsUnavailableDialog() {
        val registry = CameraRegistry().apply { failLaunch = true }
        compose.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides registry) {
                LinkedQuickActionsHost(ToolControls(settings = ToolSettings()))
            }
        }
        compose.onNodeWithText("Camera").performClick()
        compose.onNodeWithText("Camera unavailable").assertIsDisplayed()
    }

    @Test
    fun switchingBetweenQuickActionsAndLinkedSheetsAnimatesSlideAndHeightInOneSheet() {
        compose.setContent {
            LinkedQuickActionsHost(ToolControls(settings = ToolSettings()))
        }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false

        val initialWindowNode = compose.onNodeWithTag("bottom-sheet-window").fetchSemanticsNode()
        val sheetNodeId = initialWindowNode.id
        assertTrue(initialWindowNode.boundsInRoot.height > 0f)

        // Navigate forward to MCP Connectors (shorter empty state): same sheet shrinks and slides left.
        compose.onNodeWithText("MCP Connectors").performScrollTo().performClick()
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(100)

        val midShrinkNode = compose.onNodeWithTag("bottom-sheet-window").fetchSemanticsNode()
        assertEquals("Sheet must stay open instead of closing and reopening", sheetNodeId, midShrinkNode.id)
        val enteringLeftPx = with(compose.density) {
            compose.onNodeWithText("No MCP Servers").getUnclippedBoundsInRoot().left.toPx()
        }
        assertTrue("Incoming sub-sheet content should slide in from the right", enteringLeftPx > 40f)

        compose.mainClock.advanceTimeBy(350)
        compose.onNodeWithText("No MCP Servers").assertIsDisplayed()

        // Navigate back to QuickActions: same sheet expands and slides right.
        compose.onNodeWithContentDescription("Back").performClick()
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(100)

        val midExpandNode = compose.onNodeWithTag("bottom-sheet-window").fetchSemanticsNode()
        assertEquals(sheetNodeId, midExpandNode.id)
        val returningLeftPx = with(compose.density) {
            compose.onNodeWithText("Photos").getUnclippedBoundsInRoot().left.toPx()
        }
        assertTrue("Returning QuickActions content should slide in from the left", returningLeftPx < 0f)

        compose.mainClock.advanceTimeBy(350)
        assertEquals(sheetNodeId, compose.onNodeWithTag("bottom-sheet-window").fetchSemanticsNode().id)

        // Feature Tools also transitions within the same sheet instance.
        compose.onNodeWithText("Feature Tools").performScrollTo().performClick()
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(450)
        assertEquals(sheetNodeId, compose.onNodeWithTag("bottom-sheet-window").fetchSemanticsNode().id)
        compose.onNodeWithText("Image Generation").assertIsDisplayed()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(450)
        assertEquals(sheetNodeId, compose.onNodeWithTag("bottom-sheet-window").fetchSemanticsNode().id)
        compose.onNodeWithText("Photos").assertIsDisplayed()
    }

    @Test
    fun nestedSheetNavigationRemainsInTheSameMaterialSheet() {
        val servers = (1..6).map { index ->
            McpInfo(id = "mcp-$index", name = "Connector", url = "https://example.test/$index")
        }
        compose.setContent {
            LinkedQuickActionsHost(ToolControls(servers = servers, settings = ToolSettings()))
        }
        compose.waitForIdle()
        val nodeId = compose.onNodeWithTag("bottom-sheet-window").fetchSemanticsNode().id

        compose.onNodeWithText("MCP Connectors").performScrollTo().performClick()
        compose.mainClock.advanceTimeBy(450)
        assertEquals(nodeId, compose.onNodeWithTag("bottom-sheet-window").fetchSemanticsNode().id)
        compose.onNodeWithContentDescription("Back").performClick()
        compose.mainClock.advanceTimeBy(450)
        assertEquals(nodeId, compose.onNodeWithTag("bottom-sheet-window").fetchSemanticsNode().id)
        compose.onNodeWithText("Photos").assertIsDisplayed()
    }
    @Test
    fun customHttpToolsFollowMcpAndToggleInsideTheSameSheet() {
        val tool = org.starfall.generative.shared.model.CustomHttpTool(name = "weather", url = "https://example.test")
        var toggled: Pair<String, Boolean>? = null
        var settingsOpened = false
        compose.setContent {
            LinkedQuickActionsHost(ToolControls(customHttpTools = listOf(tool),
                setCustomHttpTool = { id, enabled -> toggled = id to enabled },
                openCustomHttpTools = { settingsOpened = true }))
        }
        compose.waitForIdle()
        val nodeId = compose.onNodeWithTag("bottom-sheet-window").fetchSemanticsNode().id
        compose.onNodeWithText("Custom HTTP Tools").performScrollTo().performClick()
        compose.mainClock.advanceTimeBy(450)
        assertEquals(nodeId, compose.onNodeWithTag("bottom-sheet-window").fetchSemanticsNode().id)
        compose.onNodeWithText("weather").performClick()
        assertEquals(tool.id to true, toggled)
        compose.onNodeWithContentDescription("Open Custom HTTP Tools settings").performClick()
        compose.waitForIdle()
        assertTrue(settingsOpened)
    }

}
