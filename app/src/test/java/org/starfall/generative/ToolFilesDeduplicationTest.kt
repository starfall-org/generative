package org.starfall.generative

import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.starfall.generative.shared.subfeatures.media.logic.*
import org.starfall.generative.shared.helpers.networking.*
import org.starfall.generative.database.preferences.*
import java.io.File
import java.io.StringReader
import java.nio.file.Files
import java.util.Base64

class ToolFilesDeduplicationTest {
    @Test fun identicalContentReusesFileAcrossNamesMimeHintsAndStoreInstances() = runBlocking {
        val root = Files.createTempDirectory("dedup").toFile()
        try {
            val firstStore = ToolFiles(root)
            val first = firstStore.save(referencePng.inputStream(), "image/png")
            val revision = ToolFiles.revision.value
            val second = ToolFiles(File(root, ".")).save(referencePng.inputStream(), "application/octet-stream")
            assertEquals(first, second)
            assertEquals(revision, ToolFiles.revision.value)
            assertEquals(1, firstStore.list().size)
            assertEquals(1, root.listFiles()!!.size)
            assertArrayEquals(referencePng, firstStore.resolve(first)!!.readBytes())
        } finally { root.deleteRecursively() }
    }

    @Test fun concurrentSavesAcrossInstancesCommitOnlyOnePhysicalFile() = runBlocking {
        val root = Files.createTempDirectory("concurrent-dedup").toFile()
        try {
            val stores = List(4) { ToolFiles(root) }
            val bytes = referencePng + ByteArray(128000) { 42 }
            val names = (0 until 24).map { i -> async(Dispatchers.Default) {
                stores[i % stores.size].save(bytes.inputStream(), "image/png")
            } }.awaitAll()
            assertEquals(1, names.toSet().size)
            assertEquals(1, stores.first().list().size)
            assertEquals(1, root.listFiles()!!.size)
            assertArrayEquals(bytes, stores.first().resolve(names.first())!!.readBytes())
        } finally { root.deleteRecursively() }
    }

    @Test fun openingSameDirectoryThroughAliasDoesNotDeleteAnActiveUpload() = runBlocking {
        val root = Files.createTempDirectory("dedup-alias").toFile()
        try {
            val store = ToolFiles(root)
            var openedAlias = false
            val source = object : java.io.ByteArrayInputStream(referencePng) {
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    if (!openedAlias) {
                        openedAlias = true
                        ToolFiles(File(root, "."))
                    }
                    return super.read(buffer, offset, length)
                }
            }
            val name = store.save(source)
            assertArrayEquals(referencePng, store.resolve(name)!!.readBytes())
            assertEquals(1, root.listFiles()!!.size)
        } finally { root.deleteRecursively() }
    }

    @Test fun legacyFilesAreReusedWithoutRenamingOrBreakingReferences() = runBlocking {
        val root = Files.createTempDirectory("legacy-dedup").toFile()
        try {
            val legacy = File(root, "old-uuid.png").apply { writeBytes(referencePng) }
            val store = ToolFiles(root)
            assertEquals(legacy.name, store.save(referencePng.inputStream(), "image/png"))
            assertEquals(legacy, store.resolve(legacy.name))
            assertEquals(1, root.listFiles()!!.size)
            assertArrayEquals(referencePng, legacy.readBytes())
        } finally { root.deleteRecursively() }
    }

    @Test fun differentContentWithSameSizeIsNotDeduplicated() = runBlocking {
        val root = Files.createTempDirectory("distinct-files").toFile()
        try {
            val store = ToolFiles(root)
            val first = store.save("first".byteInputStream(), "text/plain")
            val second = store.save("other".byteInputStream(), "text/plain")
            assertNotEquals(first, second)
            assertEquals(2, store.list().size)
            assertEquals("first", store.resolve(first)!!.readText())
            assertEquals("other", store.resolve(second)!!.readText())
        } finally { root.deleteRecursively() }
    }

    @Test fun deletedOrChangedFilesDoNotLeaveStaleDeduplicationEntries() = runBlocking {
        val root = Files.createTempDirectory("dedup-invalidation").toFile()
        try {
            val store = ToolFiles(root)
            val name = store.save("first".byteInputStream(), "text/plain")
            store.delete(listOf(name))
            assertNull(store.resolve(name))
            val recreated = store.save("first".byteInputStream(), "text/plain")
            assertEquals("first", store.resolve(recreated)!!.readText())
            val changed = store.resolve(recreated)!!
            changed.writeText("other")
            changed.setLastModified(changed.lastModified() + 2000)
            val original = store.save("first".byteInputStream(), "text/plain")
            assertNotEquals(recreated, original)
            assertEquals("other", changed.readText())
            assertEquals("first", store.resolve(original)!!.readText())
            assertEquals(original, store.save("first".byteInputStream(), "text/plain"))
        } finally { root.deleteRecursively() }
    }

    @Test fun base64AndDirectImportsShareTheSameSavedFileAndStillEnforceLimits() = runBlocking {
        val root = Files.createTempDirectory("dedup-base64").toFile()
        try {
            val store = ToolFiles(root)
            val original = store.save(referencePng.inputStream())
            val encoded = Base64.getEncoder().encodeToString(referencePng)
            val result = Json.parseToJsonElement(store.sanitize(StringReader(
                """{"data":[{"b64_json":"$encoded"},{"url":"data:image/png;base64,$encoded"}]}"""
            ))).jsonObject["data"]!!.jsonArray
            assertEquals("tool-file:$original", result[0].jsonObject.text("b64_json"))
            assertEquals("tool-file:$original", result[1].jsonObject.text("url"))
            assertTrue(runCatching { store.save(referencePng.inputStream(), maxBytes = 8) }.isFailure)
            assertEquals(1, root.listFiles()!!.size)
            assertArrayEquals(referencePng, store.resolve(original)!!.readBytes())
        } finally { root.deleteRecursively() }
    }
}
