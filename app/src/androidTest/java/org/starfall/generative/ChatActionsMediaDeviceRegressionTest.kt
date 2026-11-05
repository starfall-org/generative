package org.starfall.generative

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.starfall.generative.features.home.chat.ui.AssistantMessageCard
import org.starfall.generative.features.home.chat.ui.ChatRenderCaches
import org.starfall.generative.features.home.chat.ui.ProcessingDropdown
import org.starfall.generative.features.home.chat.ui.ProcessingDropdownItem
import org.starfall.generative.features.home.chat.ui.tool.ToolActivityCards
import org.starfall.generative.shared.subfeatures.media.logic.ToolFiles
import org.starfall.generative.shared.subfeatures.media.ui.mediaMimeType
import org.starfall.generative.shared.subfeatures.media.ui.mediaThumbnail
import org.starfall.generative.shared.model.ChatRole
import org.starfall.generative.shared.model.MessageVersion
import org.starfall.generative.shared.model.StoredMessage
import org.starfall.generative.shared.model.ToolActivity

/** Actions timeline, tool-only toolbar, pending media and real generated-media preview. */
@RunWith(AndroidJUnit4::class)
class ChatActionsMediaDeviceRegressionTest {
    @get:Rule val compose = createComposeRule()
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before fun clearCaches() = ChatRenderCaches.clearAll()

    private fun message(version: MessageVersion) = StoredMessage("assistant", ChatRole.MODEL, listOf(version))

    @Test fun toolOnlyReplyHasToolbarAndMoreMenuActions() {
        var copies = 0
        var edits = 0
        var deletions = 0
        compose.setContent { MaterialTheme {
            AssistantMessageCard(
                message = message(MessageVersion(toolActivity = listOf(
                    ToolActivity("tool", "search_documents", status = "success")
                ))),
                isStreaming = false, onCopy = { copies++ },
                onRegenerate = {}, onEdit = { edits++ }, onDelete = { deletions++ },
                onRead = {}, onSwitchVersion = {}
            )
        } }
        compose.onNodeWithContentDescription(context.getString(R.string.common_copy)).performClick()
        // Tool-only replies expose editing only through the existing More menu.
        compose.onNodeWithTag("assistant-empty-content").assertDoesNotExist()
        compose.onNodeWithContentDescription(context.getString(R.string.common_more)).performClick()
        compose.onAllNodesWithText(context.getString(R.string.common_edit)).assertCountEquals(1)
        compose.onNodeWithText(context.getString(R.string.common_edit)).performClick()
        compose.runOnIdle {
            assertEquals(1, copies)
            assertEquals(1, edits)
            assertEquals(0, deletions)
        }
        compose.onNodeWithContentDescription(context.getString(R.string.common_more)).performClick()
        compose.onNodeWithText(context.getString(R.string.common_delete)).performClick()
        compose.runOnIdle { assertEquals(1, deletions) }
    }

    @Test fun collapsedActionsShowOnlyRunningToolsUntilOpenedByTheUser() {
        var tools by mutableStateOf(listOf(
            ToolActivity("done", "finished_action", status = "success"),
            ToolActivity("live", "running_action", status = "running")
        ))
        compose.setContent { MaterialTheme {
            ProcessingDropdown(
                items = listOf(ProcessingDropdownItem.Thinking("Reasoning history")) +
                    tools.map { ProcessingDropdownItem.Tool(it) },
                durationMillis = null, startNumber = 1, isStreaming = true
            )
        } }
        compose.onNodeWithText("running action").assertIsDisplayed()
        compose.onNodeWithText("finished action").assertDoesNotExist()
        compose.onNodeWithTag("processing-dropdown-toggle").performClick()
        compose.waitUntil(8_000) {
            compose.onAllNodesWithText("finished action").fetchSemanticsNodes().isNotEmpty()
        }
        compose.runOnIdle { tools = tools.map { it.copy(status = "success") } }
        compose.onNodeWithText("finished action").assertExists()
        compose.onNodeWithTag("processing-dropdown-toggle").performClick()
        compose.waitUntil(8_000) {
            compose.onAllNodesWithText("running action").fetchSemanticsNodes().isEmpty()
        }
    }

    @Test fun toolCallBlockOpensArgumentsAndResponseInTheSharedDetailsSheet() {
        val activity = ToolActivity(
            id = "tool-arguments", name = "search_documents", status = "success",
            arguments = "{\"query\":\"design\"}", response = "Found three documents"
        )
        compose.setContent { MaterialTheme { ToolActivityCards(listOf(activity)) } }
        compose.onNodeWithText("search documents").performClick()
        compose.waitUntil(8_000) {
            compose.onAllNodesWithText(context.getString(R.string.tool_details_args)).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(context.getString(R.string.tool_details_response)).assertExists()
    }

    @Test fun runningImageToolReservesPreviewAndSuccessfulResultReplacesPlaceholder() {
        var activity by mutableStateOf(ToolActivity("image-generation", "generate_image", status = "running"))
        var streaming by mutableStateOf(true)
        compose.setContent { MaterialTheme {
            AssistantMessageCard(
                message = message(MessageVersion(toolActivity = listOf(activity))),
                isStreaming = streaming, onCopy = {}, onRegenerate = {}, onEdit = {},
                onDelete = {}, onRead = {}, onSwitchVersion = {}
            )
        } }
        compose.onNodeWithTag("media-generation-placeholder_image-generation").assertIsDisplayed()
        compose.runOnIdle {
            activity = activity.copy(status = "success")
            streaming = false
        }
        compose.waitUntil(8_000) {
            compose.onAllNodesWithTag("media-generation-placeholder_image-generation").fetchSemanticsNodes().isEmpty()
        }
        compose.onNodeWithTag("processing-dropdown-toggle").assertExists()
    }

    @Test fun videoGenerationAlsoUsesASquarePreviewAndRemovesItWhenFinished() {
        var tool by mutableStateOf(ToolActivity("video-generation", "generate_video", status = "running"))
        var streaming by mutableStateOf(true)
        compose.setContent { MaterialTheme {
            AssistantMessageCard(
                message = message(MessageVersion(toolActivity = listOf(tool))),
                isStreaming = streaming, onCopy = {}, onRegenerate = {}, onEdit = {},
                onDelete = {}, onRead = {}, onSwitchVersion = {}
            )
        } }
        compose.onNodeWithTag("media-generation-placeholder_video-generation").assertIsDisplayed()
        compose.runOnIdle { streaming = false; tool = tool.copy(status = "success") }
        compose.waitUntil(8_000) {
            compose.onAllNodesWithTag("media-generation-placeholder_video-generation").fetchSemanticsNodes().isEmpty()
        }
    }

    @Test fun generatedImagePreviewOpensAndClosesWithoutAnAnswerTextBlock() {
        val store = ToolFiles(context)
        val bitmap = Bitmap.createBitmap(24, 24, Bitmap.Config.ARGB_8888)
        val bytes = ByteArrayOutputStream().also {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }.toByteArray()
        bitmap.recycle()
        val file = runBlocking { store.save(bytes.inputStream(), "image/png") }
        try {
            val resolved = store.resolve(file)
            assertNotNull("Saved media must resolve on-device: $file", resolved)
            assertEquals("image/png", mediaMimeType(file))
            val thumbnail = runBlocking { mediaThumbnail(context, resolved!!.path, "image/png", 720) }
            assertNotNull("Coil must decode the saved PNG thumbnail: $file", thumbnail)
            val version = MessageVersion(toolActivity = listOf(
                ToolActivity("image", "generate_image", status = "success", files = listOf(file)),
                ToolActivity("duplicate", "other", status = "success", files = listOf(file))
            ))
            compose.setContent { MaterialTheme {
                AssistantMessageCard(
                    message = message(version), isStreaming = false,
                    onCopy = {}, onRegenerate = {}, onEdit = {}, onDelete = {},
                    onRead = {}, onSwitchVersion = {}
                )
            } }
            compose.waitUntil(10_000) {
                compose.onAllNodesWithContentDescription(context.getString(R.string.media_generated_image)).fetchSemanticsNodes().isNotEmpty()
            }
            val images = compose.onAllNodesWithContentDescription(context.getString(R.string.media_generated_image)).fetchSemanticsNodes()
            assertEquals("Unexpected duplicated generated image(s):\n" +
                compose.onRoot(useUnmergedTree = true).printToString(maxDepth = 25), 1, images.size)
            compose.onNodeWithContentDescription(context.getString(R.string.media_generated_image)).performClick()
            compose.waitUntil(10_000) {
                compose.onAllNodesWithContentDescription(context.getString(R.string.media_image_preview)).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithContentDescription(context.getString(R.string.media_close_viewer)).performClick()
            compose.onNodeWithContentDescription(context.getString(R.string.media_image_preview)).assertDoesNotExist()
        } finally {
            store.delete(listOf(file))
        }
    }
}
