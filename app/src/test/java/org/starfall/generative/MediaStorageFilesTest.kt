package org.starfall.generative

import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.starfall.generative.features.storage.logic.deleteMediaStorageFiles
import org.starfall.generative.features.storage.logic.listMediaStorageFiles
import org.starfall.generative.shared.subfeatures.media.logic.ToolFiles

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = LibraryTestApplication::class)
class MediaStorageFilesTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun listsNestedFilesWithoutDirectoryEntriesOrTransientFiles() {
        val store = ToolFiles(temporary.root)
        val deep = File(temporary.root, "a/b").apply { mkdirs() }
        val rootMedia = File(temporary.root, "image.png").apply { writeText("root") }
        val nestedMedia = File(deep, "image.png").apply { writeText("nested") }
        File(deep, "download.part").writeText("incomplete")
        File(deep, "video.spill").writeText("incomplete")
        assertEquals(setOf(rootMedia.canonicalFile, nestedMedia.canonicalFile),
            listMediaStorageFiles(store).toSet())
        assertEquals(2, listMediaStorageFiles(store).size)
    }

    @Test fun deletesOnlySelectedNestedFileWhenNamesAreIdentical() = runBlocking {
        val store = ToolFiles(temporary.root)
        val nested = File(temporary.root, "nested").apply { mkdirs() }
        val rootFile = File(temporary.root, "same.png").apply { writeText("root") }
        val nestedFile = File(nested, "same.png").apply { writeText("nested") }
        deleteMediaStorageFiles(store, listOf(nestedFile))
        assertTrue(rootFile.exists())
        assertFalse(nestedFile.exists())
        assertEquals(listOf(rootFile.canonicalFile), listMediaStorageFiles(store))
    }

    @Test fun rejectsDeletionOutsideMediaStorageRoot() {
        val store = ToolFiles(temporary.newFolder("storage"))
        val outside = File(temporary.root, "outside.png").apply { writeText("keep") }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { deleteMediaStorageFiles(store, listOf(outside)) }
        }
        assertTrue(outside.exists())
    }
}
