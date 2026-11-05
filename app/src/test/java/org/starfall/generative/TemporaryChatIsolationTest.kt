package org.starfall.generative

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import org.starfall.generative.shared.subfeatures.media.logic.TemporaryChatFiles
import org.starfall.generative.shared.subfeatures.media.logic.ToolFiles
import org.starfall.generative.shared.model.Conversation

class TemporaryChatIsolationTest {
    @Test fun temporarySessionIsNeverSerialized() {
        val conversation = Conversation("temporary", "Temporary Chat", 1, 1, isTemporary = true)
        val json = Json.encodeToString(Conversation.serializer(), conversation)
        assertFalse(json.contains("isTemporary"))
        assertFalse(Json.decodeFromString(Conversation.serializer(), json).isTemporary)
        assertTrue(conversation.copy(title = "Changed").isTemporary)
    }

    @Test fun temporaryMediaStaysInCacheAndIsDestroyedOnExit() = runBlocking {
        val root = Files.createTempDirectory("temporary-chat-test").toFile()
        try {
            val permanent = ToolFiles(File(root, "tool_media"))
            val cache = File(root, "cache")
            permanent.openTemporarySession("session-1", cache)
            val name = withContext(Dispatchers.IO + TemporaryChatFiles("session-1")) {
                permanent.save("private photo".byteInputStream(), "text/plain")
            }
            val temporaryFile = permanent.resolve(name)!!
            assertEquals(File(cache, "session-1").canonicalFile, requireNotNull(temporaryFile.parentFile).canonicalFile)
            assertEquals(name, permanent.nameOf(temporaryFile.absolutePath))
            assertTrue(permanent.list().isEmpty())
            val permanentName = permanent.save("regular chat".byteInputStream(), "text/plain")
            assertEquals(1, permanent.list().size)
            permanent.closeTemporarySession("session-1")
            assertFalse(temporaryFile.exists())
            assertNull(permanent.resolve(name))
            assertNotNull(permanent.resolve(permanentName))
            try {
                withContext(TemporaryChatFiles("session-1")) {
                    permanent.save("late reply".byteInputStream(), "text/plain")
                }
                fail("A cancelled temporary chat must not write into permanent storage")
            } catch (_: CancellationException) {
                // Late tool replies are rejected, not redirected into Media Storage.
            }
            assertEquals(1, permanent.list().size)
        } finally {
            root.deleteRecursively()
        }
    }
}
