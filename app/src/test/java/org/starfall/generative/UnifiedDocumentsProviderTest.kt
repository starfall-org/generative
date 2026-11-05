package org.starfall.generative

import android.content.Context
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.io.FileNotFoundException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.starfall.generative.integrations.remote.AppDocumentsProvider
import org.starfall.generative.shared.subfeatures.media.logic.AppDocumentsLocation
import org.starfall.generative.shared.subfeatures.media.logic.MediaStorageDocuments
import org.starfall.generative.shared.subfeatures.media.logic.ToolFiles

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = LibraryTestApplication::class)
class UnifiedDocumentsProviderTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val folder: File get() = ToolFiles.appStorageDirectory(context)
    private val uploads: File get() = ToolFiles.uploadStorageDirectory(context)

    @After fun cleanUp() {
        File(folder, "documents-test.txt").delete()
        File(folder, "documents-test-folder").deleteRecursively()
        File(uploads, "documents-test-upload.jpg").delete()
    }

    @Test fun oneSystemRootContainsMediaStorageAndUploadCaches() {
        val provider = Robolectric.buildContentProvider(AppDocumentsProvider::class.java).create().get()
        try {
            provider.queryRoots(arrayOf(DocumentsContract.Root.COLUMN_ROOT_ID)).use { roots ->
                assertEquals(1, roots.count)
                assertTrue(roots.moveToFirst())
                assertEquals(AppDocumentsLocation.ROOT_ID, roots.getString(0))
            }
            provider.queryChildDocuments(AppDocumentsLocation.ROOT_ID, null, sortOrder = null).use { children ->
                assertTrue(children.moveToFirst())
                assertEquals(MediaStorageDocuments.ROOT_ID,
                    children.getString(children.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)))
                assertEquals("MediaStorage",
                    children.getString(children.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)))
                assertTrue(children.moveToNext())
                assertEquals(MediaStorageDocuments.UPLOAD_ROOT_ID,
                    children.getString(children.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)))
                assertEquals("UploadCaches",
                    children.getString(children.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)))
                while (children.moveToNext()) {
                    assertTrue(children.getString(children.getColumnIndexOrThrow(
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME)).startsWith("SFTP_"))
                }
            }
            assertTrue(provider.isChildDocument(AppDocumentsLocation.ROOT_ID, MediaStorageDocuments.ROOT_ID))
            assertTrue(provider.isChildDocument(AppDocumentsLocation.ROOT_ID, MediaStorageDocuments.UPLOAD_ROOT_ID))
            assertFalse(provider.isChildDocument(MediaStorageDocuments.ROOT_ID, AppDocumentsLocation.ROOT_ID))
            assertThrows(FileNotFoundException::class.java) {
                provider.createDocument(AppDocumentsLocation.ROOT_ID, "text/plain", "bad.txt")
            }
            assertThrows(FileNotFoundException::class.java) {
                provider.deleteDocument(MediaStorageDocuments.ROOT_ID)
            }
            assertThrows(FileNotFoundException::class.java) {
                provider.deleteDocument(MediaStorageDocuments.UPLOAD_ROOT_ID)
            }
        } finally {
            provider.shutdown()
        }
    }

    @Test fun mediaStorageSupportsFilesAndNestedFoldersAndLegacyIds() {
        val media = MediaStorageDocuments(context)
        File(folder, "documents-test.txt").writeText("hello")
        media.queryDocument("documents-test.txt", null).use { document ->
            assertTrue(document.moveToFirst())
            assertEquals("media_storage/documents-test.txt",
                document.getString(document.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)))
        }
        assertTrue(media.isChildDocument(MediaStorageDocuments.ROOT_ID, "documents-test.txt"))
        media.openDocument("media_storage/documents-test.txt", "r", null).use { descriptor ->
            ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { reader ->
                assertEquals("hello", reader.readText())
            }
        }
        val directory = media.createDocument(MediaStorageDocuments.ROOT_ID,
            DocumentsContract.Document.MIME_TYPE_DIR, "documents-test-folder")
        assertEquals("media_storage/documents-test-folder", directory)
        val child = media.createDocument(directory, "text/plain", "nested.txt")
        assertEquals("media_storage/documents-test-folder/nested.txt", child)
        media.queryChildDocuments(directory, null, null).use { children ->
            assertEquals(1, children.count)
        }
        assertTrue(media.isChildDocument(directory, child))
        media.deleteDocument(child)
        media.deleteDocument(directory)
        assertFalse(File(folder, "documents-test-folder").exists())
    }

    @Test fun uploadsRemainInTheirOwnProviderFolderAndCannotEscapeToMediaStorage() {
        val provider = Robolectric.buildContentProvider(AppDocumentsProvider::class.java).create().get()
        try {
            File(uploads, "documents-test-upload.jpg").writeText("upload content")
            val id = "${MediaStorageDocuments.UPLOAD_ROOT_ID}/documents-test-upload.jpg"
            provider.queryChildDocuments(MediaStorageDocuments.UPLOAD_ROOT_ID, null, sortOrder = null).use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val children = buildSet {
                    while (cursor.moveToNext()) add(cursor.getString(idColumn))
                }
                assertTrue(id in children)
            }
            assertTrue(provider.isChildDocument(MediaStorageDocuments.UPLOAD_ROOT_ID, id))
            assertFalse(provider.isChildDocument(MediaStorageDocuments.ROOT_ID, id))
            provider.openDocument(id, "r", null).use { descriptor ->
                ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use {
                    assertEquals("upload content", it.readText())
                }
            }
            assertThrows(FileNotFoundException::class.java) {
                provider.queryDocument("${MediaStorageDocuments.UPLOAD_ROOT_ID}/../media_storage/secret", null)
            }
        } finally { provider.shutdown() }
    }

    @Test fun documentPathsCannotEscapeOrExposeTemporaryFiles() {
        val media = MediaStorageDocuments(context)
        for (id in listOf("media_storage/../secret", "media_storage/a/../../secret",
            "media_storage/part.spill", "media_storage/fake.part", "unknown/subdir")) {
            assertThrows(FileNotFoundException::class.java) { media.queryDocument(id, null) }
        }
        assertThrows(FileNotFoundException::class.java) {
            media.createDocument(MediaStorageDocuments.ROOT_ID, "text/plain", "bad.part")
        }
        assertThrows(FileNotFoundException::class.java) {
            media.deleteDocument(MediaStorageDocuments.ROOT_ID)
        }
    }
}
