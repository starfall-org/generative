package org.starfall.generative

import org.starfall.generative.features.home.BottomSheetsLinkState


import android.graphics.Bitmap
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.starfall.generative.shared.model.*
import org.starfall.generative.shared.subfeatures.media.logic.ToolFiles
import org.starfall.generative.features.providers.shared.ui.*
import org.starfall.generative.shared.subfeatures.media.ui.MediaPreviewCover
import org.starfall.generative.shared.subfeatures.media.ui.MediaFileCard
import org.starfall.generative.shared.subfeatures.media.ui.ChatFilePreview
import org.starfall.generative.shared.subfeatures.media.ui.InlineVideoPreview
import androidx.compose.ui.graphics.asImageBitmap
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.starfall.generative.shared.model.api.ModelApiType
import org.starfall.generative.shared.model.language.ModelConfiguration
import org.starfall.generative.shared.model.provider.ModelProvider
import org.starfall.generative.shared.model.language.ModelType
import org.starfall.generative.shared.model.provider.ProviderConfiguration
import org.starfall.generative.features.home.chat.logic.input.DirectMediaRequest
import org.starfall.generative.features.home.chat.ui.AssistantMessageCard
import org.starfall.generative.features.home.chat.ui.AttachmentStrip
import org.starfall.generative.features.home.chat.ui.UserMessageCard
import org.starfall.generative.features.home.chat.ui.input.UserInputArea
import org.starfall.generative.features.home.chat.ui.tool.ToolActivityCards

class ChatMediaUiTest {
    @Test fun assistantFilesKeepTheirLargePreview() {
        val image = File.createTempFile("assistant-photo", ".png", context.cacheDir)
        val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        image.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        try {
            val message = StoredMessage("assistant", ChatRole.MODEL,
                listOf(MessageVersion(content = "Image", files = listOf(image.path))))
            compose.setContent { MaterialTheme { AssistantMessageCard(message, false, {}, {}, {}, {}, {}, {}, onRead = {}, onSwitchVersion = {}) } }
            compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription(context.getString(R.string.media_generated_image)).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription(context.getString(R.string.media_generated_image)).assertHeightIsAtLeast(120.dp).performClick()
            compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription(context.getString(R.string.media_image_preview)).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription(context.getString(R.string.media_close_viewer)).performClick()
        } finally { image.delete() }
    }

    @Test fun userAttachmentsStayInOneRowAndMatchInputTiles() {
        val image = File.createTempFile("user-photo", ".png", context.cacheDir)
        val document = File.createTempFile("user-document", ".txt", context.cacheDir)
        val other = File.createTempFile("user-document-2", ".txt", context.cacheDir)
        val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        image.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        document.writeText("A document"); other.writeText("Another document")
        var input by mutableStateOf(false)
        try {
            val references = listOf(image.path, document.path, other.path)
            val message = StoredMessage("user", ChatRole.USER, listOf(MessageVersion(content = "Files", files = references)))
            compose.setContent { MaterialTheme {
                if (input) AttachmentStrip(references, removable = true)
                else UserMessageCard(message, {}, {}, {})
            } }
            val first = compose.onNodeWithTag("attachment-tile_${image.path}").fetchSemanticsNode().boundsInRoot
            val second = compose.onNodeWithTag("attachment-tile_${document.path}").fetchSemanticsNode().boundsInRoot
            assertEquals(first.top, second.top, 0.5f)
            assertEquals(first.bottom, second.bottom, 0.5f)
            compose.onNodeWithContentDescription(context.getString(R.string.media_generated_image)).assertDoesNotExist()
            compose.onNodeWithContentDescription(context.getString(R.string.media_image_preview)).assertDoesNotExist()
            compose.waitUntil(5_000) {
                compose.onAllNodesWithContentDescription(image.name).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag("attachment-tile_${image.path}").performClick()
            compose.waitUntil(5_000) {
                compose.onAllNodesWithContentDescription(context.getString(R.string.media_image_preview)).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithContentDescription(context.getString(R.string.media_close_viewer)).performClick()
            compose.runOnIdle { input = true }
            val inputTile = compose.onNodeWithTag("attachment-tile_${image.path}").fetchSemanticsNode().boundsInRoot
            assertEquals(inputTile.width, first.width, 0.5f)
            assertEquals(inputTile.height, first.height, 0.5f)
        } finally { image.delete(); document.delete(); other.delete() }
    }

    @Test fun sendFileActivityIsHiddenWhileRunningAndAfterCompletion() {
        compose.setContent { MaterialTheme {
            ToolActivityCards(listOf(ToolActivity("running", "send_file"), ToolActivity("done", "send_file", status = "success")))
        } }
        compose.onNodeWithText("send_file").assertDoesNotExist()
        compose.onNodeWithText("send file").assertDoesNotExist()
    }

    @Test fun genericFilesCanBeDownloadedAndSelectedForNextMessage() {
        var selected = false
        compose.setContent { MaterialTheme {
            var checked by remember { mutableStateOf(false) }
            ChatFilePreview("/document.pdf", "document.pdf", "application/pdf", null, false,
                selectedForChat = checked, onToggleAttachment = { checked = !checked; selected = checked }, onOpen = {})
        } }
        compose.onNodeWithContentDescription(context.getString(R.string.file_download)).assertIsEnabled()
        compose.onNodeWithContentDescription(context.getString(R.string.file_attach_next_message)).performClick()
        compose.runOnIdle { assertTrue(selected) }
        compose.onNodeWithContentDescription(context.getString(R.string.file_remove_next_message)).performClick()
        compose.runOnIdle { assertFalse(selected) }
    }

    @Test fun portraitMediaIsBoundedAndVideoFullscreenCanBeOpenedAndClosed() {
        val bitmap = Bitmap.createBitmap(16, 1600, Bitmap.Config.ARGB_8888)
        compose.setContent { MaterialTheme { InlineVideoPreview("${context.cacheDir}/missing.mp4", bitmap.asImageBitmap()) } }
        val bounds = compose.onNodeWithContentDescription(context.getString(R.string.media_video_preview)).fetchSemanticsNode().boundsInRoot
        val maximum = context.resources.configuration.screenHeightDp * context.resources.displayMetrics.density / 2
        assertTrue(bounds.height <= maximum + 1)
        compose.onNodeWithContentDescription(context.getString(R.string.video_fullscreen)).performClick()
        compose.onNodeWithContentDescription(context.getString(R.string.video_exit_fullscreen)).assertExists().performClick()
        compose.onNodeWithContentDescription(context.getString(R.string.video_exit_fullscreen)).assertDoesNotExist()
    }
    @Test fun returnedVideoHasNoFileLabelsAndPlaybackErrorsStayInline() {
        val store = ToolFiles(context)
        val invalidVideo = ByteArray(32).also { "ftyp".toByteArray().copyInto(it, 4) }
        val name = runBlocking { store.save(invalidVideo.inputStream(), "video/mp4") }
        try {
            compose.setContent { MaterialTheme { MediaFileCard(store, name, imageOnly = true) } }
            compose.onNodeWithText(name).assertDoesNotExist()
            compose.onNodeWithText("▶ View video").assertDoesNotExist()
            compose.onNodeWithContentDescription(context.getString(R.string.media_play_video)).performClick()
            compose.waitUntil(5_000) {
                compose.onAllNodesWithText(context.getString(R.string.media_video_play_failed), substring = true).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithContentDescription(context.getString(R.string.media_close_viewer)).assertDoesNotExist()
        } finally { store.delete(listOf(name)) }
    }

    @Test fun videosInAMediaGridOpenTheViewerInsteadOfPlayingInline() {
        val store = ToolFiles(context)
        val invalidVideo = ByteArray(32).also { "ftyp".toByteArray().copyInto(it, 4) }
        val names = runBlocking {
            listOf(
                store.save(invalidVideo.inputStream(), "video/mp4"),
                store.save(invalidVideo.copyOf().apply { this[lastIndex] = 1 }.inputStream(), "video/mp4")
            )
        }
        try {
            val message = StoredMessage("assistant-grid", ChatRole.MODEL, listOf(MessageVersion(
                toolActivity = listOf(ToolActivity(
                    "videos", "Content API / extract", status = "success", files = names, inlineMedia = true
                ))
            )))
            compose.setContent { MaterialTheme { AssistantMessageCard(message, false, {}, {}, {}, {}, {}, {}, onRead = {}, onSwitchVersion = {}) } }
            compose.waitUntil(5_000) {
                compose.onAllNodesWithContentDescription(context.getString(R.string.media_open_video)).fetchSemanticsNodes().size == 2
            }
            compose.onAllNodesWithContentDescription(context.getString(R.string.media_play_video)).assertCountEquals(0)
            compose.onAllNodesWithContentDescription(context.getString(R.string.media_open_video)).onFirst().performClick()
            // The renderer may take longer to reject deliberately corrupt video data on
            // individual devices. This regression verifies opening the viewer, not codec timing.
            compose.waitUntil(5_000) {
                compose.onAllNodesWithContentDescription(context.getString(R.string.media_close_viewer))
                    .fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithContentDescription(context.getString(R.string.media_close_viewer)).performClick()
        } finally { store.delete(names) }
    }
    @Test fun readButtonTogglesToStopAndShowsModelForActiveVersion() {
        val message = StoredMessage("a", ChatRole.MODEL, listOf(MessageVersion(
            content = "Read this answer.", modelId = "model-id", modelDisplayName = "Actual model")))
        compose.setContent {
            var reading by remember { mutableStateOf(false) }
            MaterialTheme {
                AssistantMessageCard(message, false, {}, {}, {}, {}, {}, {}, isReading = reading,
                    onRead = { reading = !reading }, onSwitchVersion = {})
            }
        }
        compose.onNodeWithText("Actual model").assertExists()
        compose.onNodeWithContentDescription(context.getString(R.string.message_read_aloud)).performClick()
        compose.onNodeWithContentDescription(context.getString(R.string.message_stop_reading)).performClick()
        compose.onNodeWithContentDescription(context.getString(R.string.message_read_aloud)).assertExists()
    }
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun mediaModesKeepConsistentSizeAndSendDirectly() {
        var submitted: DirectMediaRequest? = null
        var textSubmissions = 0
        val provider = ModelProvider("media", "Media", ModelApiType.OPENAI, baseUrl = "https://example.com/v1",
            config = ProviderConfiguration(modelIds = listOf("image", "video"), modelConfigs = mapOf(
                "image" to ModelConfiguration(modelType = ModelType.IMAGE_GENERATION),
                "video" to ModelConfiguration(modelType = ModelType.VIDEO_GENERATION))))
        compose.setContent {
            MaterialTheme {
                UserInputArea(
                    isGenerating = false,
                    onSendMessage = { _, _ -> textSubmissions++; true },
                    onSendMedia = { submitted = it; true },
                    bottomSheets = BottomSheetsLinkState(),
                    onEditMessage = { _, _, _ -> false },
                    editDraft = null,
                    onCancelEdit = {},
                    onStopGenerating = {},
                    selectedModelName = "text",
                    providers = listOf(provider),
                    selectedProviderId = "text-provider",
                    onSelectModel = { _, _ -> },
                    conversationReasoningEffort = null,
                    onSetReasoningEffort = {}
                )
            }
        }
        val original = compose.onNodeWithTag("chat-input").fetchSemanticsNode().boundsInRoot.size
        var firstMediaModeHeight: Float? = null
        listOf(
            context.getString(R.string.common_image) to ModelType.IMAGE_GENERATION,
            context.getString(R.string.common_video) to ModelType.VIDEO_GENERATION
        ).forEach { (label, kind) ->
            compose.onNodeWithContentDescription(context.getString(R.string.chat_attachments_and_tools)).performClick()
            compose.onNodeWithText(label, useUnmergedTree = true).performScrollTo().performClick()
            compose.waitForIdle()
            val size = compose.onNodeWithTag("chat-input").fetchSemanticsNode().boundsInRoot.size
            assertEquals(original.width, size.width, 1f)
            // A dedicated generation-mode chip adds one header row. Both image and video
            // modes must use the same compact layout, rather than grow on mode switching.
            assertTrue(size.height >= original.height)
            firstMediaModeHeight?.let { assertEquals(it, size.height, 1f) }
                ?: run { firstMediaModeHeight = size.height }
            compose.onNode(hasSetTextAction()).performTextInput("A quiet lake")
            compose.onNodeWithContentDescription(context.getString(R.string.chat_generate_media, label)).performClick()
            compose.runOnIdle {
                assertEquals(kind, submitted?.kind)
                assertEquals(if (kind == ModelType.IMAGE_GENERATION) "image" else "video", submitted?.modelId)
                assertEquals("A quiet lake", submitted?.prompt)
                assertEquals(0, textSubmissions)
            }
        }
        compose.onNodeWithContentDescription(context.getString(R.string.chat_exit_generation_mode,
            context.getString(R.string.common_video))).performClick()
        compose.onNodeWithContentDescription(context.getString(R.string.chat_send_message)).assertDoesNotExist()
    }

    @Test fun generatedImageIsVisibleInChatAndOpensViewerWithoutTextResponse() {
        val store = ToolFiles(context)
        val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        val bytes = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        bitmap.recycle()
        val name = runBlocking { store.save(bytes.inputStream(), "image/png") }
        try {
            val message = StoredMessage("assistant", ChatRole.MODEL, listOf(MessageVersion(toolActivity = listOf(
                ToolActivity("image", "generate_image", status = "success", files = listOf(name)),
                ToolActivity("duplicate", "other", status = "success", files = listOf(name))
            ))))
            compose.setContent {
                MaterialTheme { AssistantMessageCard(message, false, {}, {}, {}, {}, {}, {}, onRead = {}, onSwitchVersion = {}) }
            }
            compose.waitUntil(5_000) {
                compose.onAllNodesWithContentDescription(context.getString(R.string.media_generated_image)).fetchSemanticsNodes().size == 1
            }
            compose.onNodeWithContentDescription(context.getString(R.string.media_generated_image)).performClick()
            compose.waitUntil(5_000) {
                compose.onAllNodesWithContentDescription(context.getString(R.string.media_image_preview)).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithContentDescription(context.getString(R.string.media_close_viewer)).performClick()
            compose.onNodeWithContentDescription(context.getString(R.string.media_image_preview)).assertDoesNotExist()
        } finally { store.delete(listOf(name)) }
    }

    @Test fun userAudioAttachmentOpensPlayerOnlyAfterClick() {
        val file = File.createTempFile("audio-preview", ".wav", context.cacheDir)
        // Ten seconds of silent PCM: exercise a real decoder without audible test output.
        val dataSize = 8000 * 2 * 10
        file.writeBytes(ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + dataSize); put("WAVEfmt ".toByteArray())
            putInt(16); putShort(1); putShort(1); putInt(8000); putInt(16000)
            putShort(2); putShort(16); put("data".toByteArray()); putInt(dataSize)
        }.array())
        try {
            compose.setContent { MaterialTheme { AttachmentStrip(listOf(file.path), removable = false) } }
            compose.onNodeWithContentDescription(context.getString(R.string.media_play_audio)).assertDoesNotExist()
            compose.waitUntil(5_000) {
                compose.onAllNodesWithContentDescription(context.getString(R.string.attachment_play_media)).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag("attachment-tile_${file.path}").performClick()
            compose.waitUntil(5_000) {
                compose.onAllNodesWithContentDescription(context.getString(R.string.media_play_audio)).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithContentDescription(context.getString(R.string.media_play_audio)).performClick()
            compose.onNodeWithContentDescription(context.getString(R.string.media_pause_audio)).assertExists().performClick()
            compose.onNodeWithContentDescription(context.getString(R.string.media_play_audio)).assertExists()
            compose.onNode(SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.ProgressBarRangeInfo)).assertExists()
            compose.onNodeWithContentDescription(context.getString(R.string.media_close_viewer)).assertExists().performClick()
            compose.onNodeWithContentDescription(context.getString(R.string.media_play_audio)).assertDoesNotExist()
        } finally { file.delete() }
    }

    @Test fun unavailableVideoShowsErrorAndCanBeClosed() {
        compose.setContent {
            MaterialTheme { MediaPreviewCover("${context.cacheDir}/missing-video.mp4", "Video", "video/mp4", {}) }
        }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText(context.getString(R.string.media_video_play_failed), substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription(context.getString(R.string.media_close_viewer)).assertIsEnabled()
    }

    @Test fun selectedGeneratedImageIsAttachedToNextMessageAndClearedAfterSend() {
        val store = ToolFiles(context)
        val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        val bytes = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        bitmap.recycle()
        val name = runBlocking { store.save(bytes.inputStream(), "image/png") }
        var sentFiles: List<String>? = null
        try {
            val message = StoredMessage("assistant", ChatRole.MODEL, listOf(MessageVersion(toolActivity = listOf(
                ToolActivity("image", "generate_image", status = "success", files = listOf(name))
            ))))
            compose.setContent {
                var attachments by remember { mutableStateOf<List<String>>(emptyList()) }
                MaterialTheme {
                    Box(Modifier.fillMaxSize()) {
                        AssistantMessageCard(message, false, {}, {}, {}, {}, {}, {},
                            onRead = {}, onSwitchVersion = {},
                            selectedImageAttachments = attachments,
                            onToggleChatImage = { ref -> attachments = if (ref in attachments) attachments - ref else attachments + ref })
                        UserInputArea(
                            isGenerating = false,
                            onSendMessage = { _, files -> sentFiles = files; true },
                            onSendMedia = { false },
                            bottomSheets = BottomSheetsLinkState(),
                            onEditMessage = { _, _, _ -> false },
                            editDraft = null,
                            onCancelEdit = {},
                            onStopGenerating = {},
                            selectedModelName = "text",
                            providers = emptyList(),
                            selectedProviderId = "p",
                            onSelectModel = { _, _ -> },
                            conversationReasoningEffort = null,
                            onSetReasoningEffort = {},
                            attachments = attachments,
                            onAttachmentsChange = { attachments = it },
                            modifier = Modifier.align(Alignment.BottomCenter)
                        )
                    }
                }
            }
            compose.waitUntil(5_000) {
                compose.onAllNodesWithContentDescription(context.getString(R.string.attachment_attach_to_next)).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithContentDescription(context.getString(R.string.attachment_attach_to_next)).performClick()
            compose.onNodeWithContentDescription(context.getString(R.string.attachment_remove_from_next)).assertIsOn().performClick()
            compose.onNodeWithContentDescription(context.getString(R.string.attachment_attach_to_next)).performClick()
            compose.onNode(hasSetTextAction()).performTextInput("Describe this image")
            compose.onNodeWithContentDescription(context.getString(R.string.chat_send_message)).performClick()
            compose.runOnIdle { assertEquals(listOf(store.resolve(name)!!.path), sentFiles) }
            compose.onNodeWithContentDescription(context.getString(R.string.attachment_attach_to_next)).assertIsOff()
        } finally { store.delete(listOf(name)) }
    }

    @Test fun runningMediaToolsRetainFailedPlaceholdersAndRemoveCancelledOnes() {
        var version by mutableStateOf(MessageVersion(toolActivity = listOf(
            ToolActivity("image-pending", "generate_image"),
            ToolActivity("video-pending", "generate_video")
        )))
        var streaming by mutableStateOf(true)
        compose.setContent { MaterialTheme {
            AssistantMessageCard(
                StoredMessage("assistant-pending", ChatRole.MODEL, listOf(version)),
                streaming, {}, {}, {}, {}, {}, {}, onRead = {}, onSwitchVersion = {}
            )
        } }

        val originalImageBounds = compose.onNodeWithTag("media-generation-placeholder_image-pending")
            .fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("media-generation-placeholder_image-pending")
            .assertExists().assertHeightIsAtLeast(100.dp)
        compose.onNodeWithTag("media-generation-placeholder_video-pending")
            .assertExists().assertHeightIsAtLeast(100.dp)
        compose.onNodeWithContentDescription(context.getString(R.string.tool_generating_image)).assertExists()
        compose.onNodeWithContentDescription(context.getString(R.string.tool_generating_video)).assertExists()

        compose.runOnIdle {
            version = version.copy(toolActivity = listOf(
                ToolActivity("image-pending", "generate_image", status = "error", summary = "Failed"),
                ToolActivity("video-pending", "generate_video")
            ))
        }
        val failedImageBounds = compose.onNodeWithTag("media-generation-placeholder_image-pending")
            .assertExists().fetchSemanticsNode().boundsInRoot
        assertEquals(originalImageBounds.width, failedImageBounds.width, 0.5f)
        assertEquals(originalImageBounds.height, failedImageBounds.height, 0.5f)
        compose.onNodeWithTag("media-generation-failed_image-pending", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithContentDescription(context.getString(R.string.media_image_generation_failed)).assertExists()
        compose.onNodeWithContentDescription(context.getString(R.string.tool_generating_image)).assertDoesNotExist()
        compose.onNodeWithTag("media-generation-placeholder_video-pending").assertExists()

        compose.runOnIdle {
            version = version.copy(toolActivity = version.toolActivity.map {
                if (it.id == "video-pending") it.copy(status = "cancelled") else it
            })
        }
        compose.onNodeWithTag("media-generation-placeholder_video-pending").assertDoesNotExist()

        compose.runOnIdle { streaming = false }
        compose.onNodeWithTag("media-generation-placeholder_image-pending").assertExists()
        compose.onNodeWithContentDescription(context.getString(R.string.media_image_generation_failed)).assertExists()

        // A restored or stopped message must not resurrect placeholders for stale running calls.
        compose.runOnIdle {
            version = MessageVersion(toolActivity = listOf(ToolActivity("stale", "generate_image")))
            streaming = false
        }
        compose.onNodeWithTag("media-generation-placeholder_stale").assertDoesNotExist()
    }

    @Test fun failedVideoKeepsItsFrameInSavedMessages() {
        val version = MessageVersion(toolActivity = listOf(
            ToolActivity("video-failed", "generate_video", status = "error", summary = "Video request failed")
        ))
        compose.setContent { MaterialTheme {
            AssistantMessageCard(
                StoredMessage("assistant-video-failed", ChatRole.MODEL, listOf(version)),
                false, {}, {}, {}, {}, {}, {}, onRead = {}, onSwitchVersion = {}
            )
        } }
        compose.onNodeWithTag("media-generation-placeholder_video-failed").assertExists().assertHeightIsAtLeast(100.dp)
        compose.onNodeWithTag("media-generation-failed_video-failed", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithContentDescription(context.getString(R.string.media_video_generation_failed)).assertExists()
        compose.onNodeWithContentDescription(context.getString(R.string.tool_generating_video)).assertDoesNotExist()
    }

    @Test fun successfulMediaReplacesItsPendingPreviewWithoutMovingEarlierFailure() {
        val store = ToolFiles(context)
        val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        val bytes = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        bitmap.recycle()
        val name = runBlocking { store.save(bytes.inputStream(), "image/png") }
        try {
            val failed = ToolActivity("earlier-failure", "generate_image", status = "error")
            var version by mutableStateOf(MessageVersion(toolActivity = listOf(
                failed,
                ToolActivity("rendering", "generate_image")
            )))
            compose.setContent { MaterialTheme {
                AssistantMessageCard(
                    StoredMessage("assistant-rendering", ChatRole.MODEL, listOf(version)),
                    true, {}, {}, {}, {}, {}, {}, onRead = {}, onSwitchVersion = {}
                )
            } }
            compose.onNodeWithTag("media-generation-placeholder_rendering").assertExists()
            val failedLeft = compose.onNodeWithTag("media-generation-placeholder_earlier-failure")
                .fetchSemanticsNode().boundsInRoot.left
            compose.runOnIdle {
                version = version.copy(toolActivity = listOf(
                    failed,
                    ToolActivity("rendering", "generate_image", status = "success", files = listOf(name))
                ))
            }
            compose.onNodeWithTag("media-generation-placeholder_rendering").assertDoesNotExist()
            compose.waitUntil(5_000) {
                compose.onAllNodesWithContentDescription(context.getString(R.string.media_generated_image)).fetchSemanticsNodes().isNotEmpty()
            }
            val failureBounds = compose.onNodeWithTag("media-generation-placeholder_earlier-failure")
                .assertExists().fetchSemanticsNode().boundsInRoot
            val imageBounds = compose.onNodeWithContentDescription(context.getString(R.string.media_generated_image)).fetchSemanticsNode().boundsInRoot
            assertEquals(failedLeft, failureBounds.left, 0.5f)
            assertTrue(failureBounds.left < imageBounds.left)
        } finally { store.delete(listOf(name)) }
    }

    @Test fun generatedMediaStaysBetweenTextBeforeAndAfterToolCall() {
        val store = ToolFiles(context)
        val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        val bytes = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        bitmap.recycle()
        val name = runBlocking { store.save(bytes.inputStream(), "image/png") }
        try {
            val before = "Before the tool call.\n\n"
            val after = "After the tool result."
            val version = MessageVersion(content = before + after, toolActivity = listOf(
                ToolActivity("image", "generate_image", status = "success", files = listOf(name), contentOffset = before.length)
            ))
            compose.setContent { MaterialTheme {
                AssistantMessageCard(StoredMessage("assistant", ChatRole.MODEL, listOf(version)), false, {}, {}, {}, {}, {}, {}, onRead = {}, onSwitchVersion = {})
            } }
            compose.waitUntil(5_000) {
                compose.onAllNodesWithContentDescription(context.getString(R.string.media_generated_image)).fetchSemanticsNodes().isNotEmpty()
            }
            val beforeBounds = compose.onNodeWithText("Before the tool call.").fetchSemanticsNode().boundsInRoot
            val imageBounds = compose.onNodeWithContentDescription(context.getString(R.string.media_generated_image)).fetchSemanticsNode().boundsInRoot
            val afterBounds = compose.onNodeWithText(after).fetchSemanticsNode().boundsInRoot
            assertTrue(beforeBounds.bottom <= imageBounds.top)
            assertTrue(imageBounds.bottom <= afterBounds.top)
        } finally { store.delete(listOf(name)) }
    }
}
