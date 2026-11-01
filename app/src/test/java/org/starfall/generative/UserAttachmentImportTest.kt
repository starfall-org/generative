package org.starfall.generative

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.starfall.generative.shared.files.ToolFiles
import org.starfall.generative.shared.files.importUserAttachment

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = LibraryTestApplication::class)
class UserAttachmentImportTest {
    @Test fun externalFileIsDurablyCopiedAndSurvivesOriginalDeletion() = runBlocking {
        val root = Files.createTempDirectory("chat-attachment-copy").toFile()
        val original = File(root, "source.jpg").apply { writeBytes(referencePng) }
        val store = ToolFiles(File(root, "tool_media"), File(root, "UploadCaches"))
        try {
            val saved = importUserAttachment(
                ApplicationProvider.getApplicationContext(), store, Uri.fromFile(original), "image/png"
            )
            assertTrue(saved.startsWith(store.uploadDirectory!!.absolutePath))
            assertNotEquals(original.absolutePath, saved)
            assertArrayEquals(referencePng, File(saved).readBytes())
            assertEquals(1, store.listUploads().size)
            assertTrue(store.list().isEmpty()) // Media Management must not list user uploads.
            assertTrue(original.delete())
            assertArrayEquals(referencePng, File(saved).readBytes())
            assertEquals(saved, importUserAttachment(
                ApplicationProvider.getApplicationContext(), store, Uri.fromFile(File(saved))
            ))
        } finally { root.deleteRecursively() }
    }

    @Test fun contentUriIsSnapshottedRatherThanStoredAsRevocableReference() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val root = Files.createTempDirectory("chat-content-copy").toFile()
        val store = ToolFiles(File(root, "tool_media"), File(root, "UploadCaches"))
        val uri = Uri.parse("content://external-provider/image/128")
        try {
            shadowOf(context.contentResolver).registerInputStream(uri, ByteArrayInputStream(referencePng))
            val stored = importUserAttachment(context, store, uri, fallbackMime = "image/png")
            assertTrue(stored.startsWith(store.uploadDirectory!!.absolutePath))
            assertFalse(stored.startsWith("content://"))
            assertArrayEquals(referencePng, File(stored).readBytes())
        } finally { root.deleteRecursively() }
    }

    @Test fun copiedTemporaryChatAttachmentIsNotPromotedToPermanentStorage() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val root = Files.createTempDirectory("chat-temporary-copy").toFile()
        val original = File(root, "original.png").apply { writeBytes(referencePng) }
        val store = ToolFiles(File(root, "tool_media"), File(root, "UploadCaches"))
        val cache = File(root, "cache")
        try {
            store.openTemporarySession("temporary", cache)
            val stored = importUserAttachment(context, store, Uri.fromFile(original), "image/png", "temporary")
            assertTrue(stored.startsWith(File(cache, "temporary").absolutePath))
            assertArrayEquals(referencePng, File(stored).readBytes())
            assertTrue(store.list().isEmpty())
            assertTrue(store.listUploads().isEmpty())
            store.closeTemporarySession("temporary")
            assertFalse(File(stored).exists())
            assertTrue(original.exists())
        } finally { root.deleteRecursively() }
    }

    @Test fun generatedOutputAndUserUploadUseIndependentDirectories() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val root = Files.createTempDirectory("separated-media-ownership").toFile()
        val original = File(root, "user-input.png").apply { writeBytes(referencePng) }
        val store = ToolFiles(File(root, "tool_media"), File(root, "UploadCaches"))
        try {
            val generated = store.save(referencePng.inputStream(), "image/png")
            store.markGeneratedMedia(listOf("tool-file:$generated"))
            val userCopy = importUserAttachment(context, store, Uri.fromFile(original), "image/png")
            assertTrue(userCopy.startsWith(store.uploadDirectory!!.absolutePath))
            assertEquals(1, store.list().size)
            assertEquals(1, store.listUploads().size)
            assertTrue(store.isGeneratedMedia(generated))
            val uploadedName = File(userCopy).name
            assertFalse(store.isGeneratedMedia(uploadedName))
            store.deletePermanentUploads(listOf(uploadedName, generated))
            assertFalse(File(userCopy).exists())
            assertTrue(store.resolve(generated)?.isFile == true)
        } finally { root.deleteRecursively() }
    }

    @Test fun twoImportsOfSameOriginalReuseOnePrivateCopy() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val root = Files.createTempDirectory("chat-shared-copy").toFile()
        val original = File(root, "shared.jpg").apply { writeBytes(referencePng) }
        val store = ToolFiles(File(root, "tool_media"), File(root, "UploadCaches"))
        try {
            val first = importUserAttachment(context, store, Uri.fromFile(original), "image/png")
            val second = importUserAttachment(context, store, Uri.fromFile(original), "image/png")
            assertEquals(first, second)
            assertEquals(1, store.listUploads().size)
        } finally { root.deleteRecursively() }
    }
}
