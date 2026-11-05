package org.starfall.generative

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Base64
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.core.content.FileProvider
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.starfall.generative.features.home.BottomSheetsLinkState
import org.starfall.generative.features.home.chat.ui.input.UserInputArea
import org.starfall.generative.shared.subfeatures.media.logic.ToolFiles
import java.io.File

class ClipboardImagePasteDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun pasteImageIntoComposerAddsAttachmentAndTextPasteStillWorks() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val originalClip = clipboard.primaryClip
        val source = File(ToolFiles.appStorageDirectory(context), "clipboard-input-device-test.png")
        source.writeBytes(Base64.decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9Y9oXEgAAAAASUVORK5CYII=",
            Base64.DEFAULT
        ))
        val imageUri = FileProvider.getUriForFile(context, "${context.packageName}.tool-files", source)
        var attachments by mutableStateOf(emptyList<String>())
        var sentText: String? = null
        try {
            compose.setContent {
                MaterialTheme {
                    UserInputArea(
                        isGenerating = false,
                        onSendMessage = { text, _ -> sentText = text; true },
                        onSendMedia = { false },
                        bottomSheets = BottomSheetsLinkState(),
                        onEditMessage = { _, _, _ -> false },
                        editDraft = null,
                        onCancelEdit = {},
                        onStopGenerating = {},
                        selectedModelName = "",
                        providers = emptyList(),
                        selectedProviderId = "",
                        onSelectModel = { _, _ -> },
                        conversationReasoningEffort = null,
                        onSetReasoningEffort = {},
                        attachments = attachments,
                        onAttachmentsChange = { attachments = it },
                        onAddClipboardImages = { attachments = (attachments + it).distinct() }
                    )
                }
            }
            compose.runOnIdle {
                clipboard.setPrimaryClip(ClipData.newUri(context.contentResolver, "image", imageUri))
            }
            compose.onNode(hasSetTextAction()).performClick()
            compose.onNode(hasSetTextAction()).performSemanticsAction(SemanticsActions.PasteText) { it() }
            compose.waitUntil(10_000) { attachments.isNotEmpty() }
            compose.runOnIdle {
                assertEquals(1, attachments.size)
                assertTrue(File(attachments.single()).isFile)
            }
            compose.runOnIdle { clipboard.setPrimaryClip(ClipData.newPlainText("text", "hello")) }
            compose.onNode(hasSetTextAction()).performSemanticsAction(SemanticsActions.PasteText) { it() }
            compose.onNodeWithContentDescription("Send message").performClick()
            compose.runOnIdle { assertEquals("hello", sentText) }
        } finally {
            if (originalClip != null) clipboard.setPrimaryClip(originalClip)
            else clipboard.clearPrimaryClip()
            source.delete()
        }
    }
}
