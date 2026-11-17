package org.starfall.generative

import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.starfall.generative.database.repositories.RemoteCacheScope
import org.starfall.generative.database.repositories.RemoteConversationCache
import org.starfall.generative.features.home.chat.logic.remote.RemoteWorkspace
import org.starfall.generative.integrations.serverside.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = LibraryTestApplication::class)
class RemoteConversationCacheTest {
    @get:Rule val temp = TemporaryFolder()
    private fun cache(): RemoteConversationCache = RemoteConversationCache(temp.newFolder("remote"),
        encrypt = { "sealed:$it" }, decrypt = { require(it.startsWith("sealed:")); it.removePrefix("sealed:") })
    private val owner = RemoteCacheScope("profile", "device-a", "server", "sha256/pin-a")
    private fun snapshot(id: String, revision: Long = 1) = ServerSnapshot(id, revision,
        Json.parseToJsonElement("""{"id":"$id","title":"secret chat","created_at":1,"updated_at":2,"messages":[],"future_field":"kept"}""").jsonObject)
    private fun item(id: String, revision: Long = 1) = SessionListItem(id, "secret chat", revision = revision)

    @Test fun encryptedDiskSnapshotsAndIndexesAreScopedToPairingAndNeverEnterLocalChatDatabase() = runBlocking {
        val c = cache(); val ownerB = owner.copy(clientId = "device-b")
        c.save(owner, snapshot("chat"))
        c.saveList(owner, listOf(item("chat")))
        assertEquals(snapshot("chat").conversation, c.snapshot(owner, "chat")?.conversation)
        assertEquals(listOf(item("chat")), c.list(owner))
        assertNull(c.snapshot(ownerB, "chat"))
        assertNull(c.snapshot(owner.copy(pin = "sha256/rotated"), "chat"))
        assertNull(c.snapshot(owner.copy(profileId = "another"), "chat"))
        assertNull(c.list(ownerB))
        val files = temp.root.walkTopDown().filter { it.isFile }.toList()
        assertEquals(2, files.size)
        files.forEach { file ->
            assertTrue(file.readText().startsWith("sealed:"))
            assertFalse(file.name.contains("chat"))
            assertFalse(file.name.contains("device"))
        }
        // Async older checkpoints cannot overwrite a newer server revision.
        c.save(owner, snapshot("chat", 3))
        c.save(owner, snapshot("chat", 2))
        assertEquals(3L, c.snapshot(owner, "chat")?.revision)
        c.removeProfile("profile")
        assertNull(c.snapshot(owner, "chat"))
        assertNull(c.list(owner))
    }

    @Test fun invalidCacheEntriesAreDiscardedAndDeletedChatsArePruned() = runBlocking {
        val c = cache()
        c.save(owner, snapshot("keep")); c.save(owner, snapshot("delete"))
        c.saveList(owner, listOf(item("keep")))
        c.prune(owner, setOf("keep"))
        assertNotNull(c.snapshot(owner, "keep"))
        assertNull(c.snapshot(owner, "delete"))
        val file = temp.root.walkTopDown().first { it.isFile && it.name.endsWith(".cache") && it.readText().contains("future_field") }
        file.writeText("malformed")
        assertNull(c.snapshot(owner, "keep"))
        assertFalse(file.exists())
        assertEquals(listOf(item("keep")), c.list(owner))
    }

    private class Wire : ServerSideWire {
        var revision = 1L
        var offline = false
        val fullLoads = AtomicInteger(0)
        val conditionalLoads = AtomicInteger(0)
        override fun authenticate(identity: PairedIdentity): AccessToken {
            if (offline) throw ServerSideHttpException(401)
            return AccessToken("bearer", 900)
        }
        override fun events(token: AccessToken, sessionId: String, after: Long) = emptyList<SessionEvent>()
        override fun rpc(token: AccessToken, request: RpcRequest): RpcReply {
            val data = when (request.op) {
                "capabilities.get" -> """{"protocol":5,"operations":["session.list","session.open","session.get","session.configure","session.run","session.cancel","session.events","session.save","session.delete","provider.catalog"],"chat_features":{"version":1,"text_generation":true,"streaming":true}}"""
                "provider.catalog" -> """{"version":1,"revision":1,"providers":[{"id":"p","name":"Provider","type":"openai","custom_model_id":false,"key_selection_required":false,"keys":[],"models":[{"id":"m","name":"Model","availability":"available","text_generation":true}]}]}"""
                "session.list" -> """{"version":1,"items":[{"id":"chat","title":"Remote","revision":$revision,"created_at":1,"updated_at":2}],"next_cursor":""}"""
                "session.open" -> {
                    val hint = request.data?.get("if_revision")?.toString()?.toLongOrNull()
                    if (hint == revision) {
                        conditionalLoads.incrementAndGet()
                        """{"session_id":"chat","revision":$revision,"event_seq":0,"unchanged":true}"""
                    } else {
                        fullLoads.incrementAndGet()
                        """{"session_id":"chat","revision":$revision,"event_seq":0,"conversation":{"id":"chat","title":"Remote","created_at":1,"updated_at":2,"messages":[]}}"""
                    }
                }
                else -> error("Unexpected RPC ${request.op}")
            }
            return RpcReply(request.id, true, Json.parseToJsonElement(data))
        }
    }

    @Test fun restartUsesConditionalOpenAndOfflineShowsReadOnlyCachedHistory() = runBlocking {
        val c = cache()
        val wire = Wire()
        fun workspace() = RemoteWorkspace(
            { ServerSideSessions(ServerSideClient(wire, PairedIdentity("device-a", "secret", "server"))) }, this,
            loadLastSession = { "chat" }, cache = c, cacheScope = { owner })
        suspend fun ready(workspace: RemoteWorkspace) = withTimeout(5000) { while (workspace.state.value.busy) yield() }
        val first = workspace(); first.enable("profile"); ready(first)
        assertEquals(1, wire.fullLoads.get())
        assertNotNull(first.state.value.conversation)
        first.disable()

        val second = workspace(); second.enable("profile"); ready(second)
        assertEquals(1, wire.fullLoads.get())
        assertEquals(1, wire.conditionalLoads.get())
        assertEquals("chat", second.state.value.snapshot?.sessionId)
        second.disable()

        wire.revision = 2
        val changed = workspace(); changed.enable("profile"); ready(changed)
        assertEquals(2, wire.fullLoads.get())
        assertEquals(2L, changed.state.value.snapshot?.revision)
        changed.disable()

        wire.offline = true
        val offline = workspace(); offline.enable("profile"); ready(offline)
        assertTrue(offline.state.value.enabled)
        assertTrue(offline.state.value.error)
        assertEquals("chat", offline.state.value.conversation?.id)
        assertFalse(offline.send("cannot send while offline", emptyList()))
        offline.disable()
    }
}
