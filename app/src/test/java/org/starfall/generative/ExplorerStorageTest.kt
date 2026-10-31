package org.starfall.generative

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.starfall.generative.shared.subfeatures.explorer.logic.ExplorerLocation
import org.starfall.generative.features.storage.logic.MediaStorageExplorerRepository
import org.starfall.generative.shared.files.ToolFiles

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = LibraryTestApplication::class)
class ExplorerStorageTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val root: File get() = ToolFiles.appStorageDirectory(context)
    private val media = ExplorerLocation(sourceId = "media", label = "MediaStorage")

    @After fun cleanUp() {
        File(root, "explorer-test-folder").deleteRecursively()
    }

    @Test fun localMediaFoldersAreBrowsableRenamableAndDeletable() = runBlocking<Unit> {
        val adapter = MediaStorageExplorerRepository(context)
        adapter.createFolder(media, "explorer-test-folder")
        val folder = adapter.list(media).single { it.name == "explorer-test-folder" }
        assertTrue(folder.directory)
        assertEquals(media, folder.location.parent())
        File(root, "explorer-test-folder/example.txt").writeText("example")
        assertEquals("example.txt", adapter.list(folder.location).single().name)
        val child = adapter.list(folder.location).single()
        adapter.rename(child, "renamed.txt")
        assertEquals("renamed.txt", adapter.list(folder.location).single().name)
        adapter.delete(adapter.list(folder.location).single())
        assertTrue(adapter.list(folder.location).isEmpty())
        adapter.delete(folder)
        assertFalse(File(root, "explorer-test-folder").exists())
    }

    @Test fun uploadAndDownloadWorkInNestedFolderWithoutOverwritingFiles() = runBlocking<Unit> {
        val adapter = MediaStorageExplorerRepository(context)
        adapter.createFolder(media, "explorer-test-folder")
        val folder = adapter.list(media).single { it.name == "explorer-test-folder" }
        val bytes = byteArrayOf(0, 1, 2, -1, 12, 34)
        adapter.upload(folder.location, "payload.bin", bytes.inputStream())
        val item = adapter.list(folder.location).single()
        val output = ByteArrayOutputStream()
        adapter.download(item, output)
        assertArrayEquals(bytes, output.toByteArray())

        assertThrows(IOException::class.java) {
            runBlocking { adapter.upload(folder.location, "payload.bin", "replacement".byteInputStream()) }
        }
        assertArrayEquals(bytes, File(root, "explorer-test-folder/payload.bin").readBytes())
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { adapter.upload(folder.location, "../escape", bytes.inputStream()) }
        }
    }

    @Test fun failedUploadDoesNotLeavePartialFilesAndFoldersCannotBeDownloaded() = runBlocking<Unit> {
        val adapter = MediaStorageExplorerRepository(context)
        adapter.createFolder(media, "explorer-test-folder")
        val folder = adapter.list(media).single { it.name == "explorer-test-folder" }
        val brokenStream = object : InputStream() {
            override fun read(): Int = throw IOException("Source disconnected")
        }
        assertThrows(IOException::class.java) {
            runBlocking { adapter.upload(folder.location, "incomplete.bin", brokenStream) }
        }
        assertFalse(File(root, "explorer-test-folder/incomplete.bin").exists())
        assertThrows(IOException::class.java) {
            runBlocking { adapter.download(folder, ByteArrayOutputStream()) }
        }
    }

    @Test fun theStorageRootAndInvalidNamesAreProtected() = runBlocking<Unit> {
        val adapter = MediaStorageExplorerRepository(context)
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { adapter.createFolder(media, "../escape") }
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { adapter.createFolder(ExplorerLocation(), "anything") }
        }
    }
}
