package org.starfall.generative

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import android.content.Context
import java.io.ByteArrayInputStream
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.starfall.generative.navigation.parseExternalShare
import org.starfall.generative.navigation.stageSharedAttachment
import org.starfall.generative.shared.files.ToolFiles
import org.starfall.generative.shared.ui.media.ChatAttachmentSelection

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = LibraryTestApplication::class)
class ExternalShareTest {

    @Test fun sendWithCaptionAndStreamProducesDraftAndOneAttachment() {
        val photo = Uri.parse("content://sender/photos/123")
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_TEXT, "Caption")
            putExtra(Intent.EXTRA_STREAM, photo)
            clipData = ClipData.newRawUri("duplicate", photo)
        }
        val content = parseExternalShare(intent)!!
        assertEquals("Caption", content.text)
        assertEquals(listOf(photo), content.streams)
        assertEquals("image/png", content.fallbackMime)
    }

    @Test fun clipOnlySendAndMultipleSendWorkWithoutExtraStream() {
        val one = Uri.parse("content://provider/one")
        val two = Uri.parse("content://provider/two")
        val clip = ClipData.newRawUri("attachment", one).apply { addItem(ClipData.Item(two)) }
        val single = Intent(Intent.ACTION_SEND).apply { type = "*/*"; clipData = clip }
        assertEquals(listOf(one, two), parseExternalShare(single)?.streams)

        val multiple = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "application/pdf"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(one, two, one))
        }
        assertEquals(listOf(one, two), parseExternalShare(multiple)?.streams)
    }

    @Test fun processTextCopiesOnlySelectedTextAndNeverStreams() {
        val intent = Intent(Intent.ACTION_PROCESS_TEXT).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_PROCESS_TEXT, "Selected words")
            putExtra(Intent.EXTRA_STREAM, Uri.parse("content://provider/ignored"))
        }
        assertEquals("Selected words", parseExternalShare(intent)?.text)
        assertTrue(parseExternalShare(intent)!!.streams.isEmpty())
        assertNull(parseExternalShare(Intent(Intent.ACTION_SEND)))
        assertNull(parseExternalShare(Intent(Intent.ACTION_VIEW).apply { data = Uri.parse("https://example.org") }))
    }

    @Test fun externalTextAndFilesAccumulateWithoutOverwritingComposerSelection() {
        val selection = ChatAttachmentSelection()
        selection.addAttachments(listOf("/files/old.png"))
        selection.addAttachments(listOf("/files/new.pdf", "/files/old.png"))
        assertEquals(listOf("/files/old.png", "/files/new.pdf"), selection.attachments.value)
        selection.queueExternalText("First")
        selection.queueExternalText("Second")
        assertEquals("First\n\nSecond", selection.takeExternalText())
        assertNull(selection.takeExternalText())
        selection.requestChatMode()
        assertEquals(1, selection.requestId)
    }

    @Test fun sharedContentUriIsStagedAsLocalFile() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val uri = Uri.parse("content://external-share-test/sample")
        val data = "%PDF-1.7\nExample shared attachment".toByteArray()
        shadowOf(context.contentResolver).registerInputStream(uri, ByteArrayInputStream(data))
        val location = File(context.cacheDir, "external-share-unit-test")
        val files = ToolFiles(location, File(context.cacheDir, "external-share-uploads-test"))
        try {
            val reference = stageSharedAttachment(context, files, uri, "application/pdf", null)
            assertTrue(reference.endsWith(".pdf"))
            assertArrayEquals(data, File(reference).readBytes())
        } finally {
            location.deleteRecursively()
            files.uploadDirectory?.deleteRecursively()
        }
    }
}
