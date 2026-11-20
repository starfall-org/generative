package org.starfall.generative

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import okhttp3.Credentials
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.starfall.generative.database.repositories.ServerConnectionRepository
import org.starfall.generative.database.room.AppDatabase
import org.starfall.generative.database.room.entities.ServerConnectionEntity
import org.starfall.generative.features.serverconnection.logic.ServerConnections
import org.starfall.generative.integrations.opencode.OpenCodeCredentials
import org.starfall.generative.integrations.generativeserver.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = LibraryTestApplication::class)
class ServerInventoryIsolationTest {
    @Test fun generativeServerMcpIsExplicitlyUnsupportedWithoutUsingLocalData() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java).build()
        try {
            db.serverConnectionDao().put(ServerConnectionEntity("server", "Server", "https://example.com", "pin", "client", "server", "secret", false))
            val wire = object : GenerativeServerWire {
                override fun authenticate(identity: PairedIdentity) = AccessToken("bearer", 900)
                override fun events(token: AccessToken, sessionId: String, after: Long) = emptyList<SessionEvent>()
                override fun rpc(token: AccessToken, request: RpcRequest): RpcReply {
                    assertEquals("mcp.catalog", request.op)
                    return RpcReply(request.id, false, error = RpcError("unsupported_feature", "Old server"))
                }
            }
            val service = ServerConnections(ServerConnectionRepository(db), clientFactory = {
                GenerativeServerClient(wire, PairedIdentity("c", "secret", "s"))
            })
            assertNull(service.projects("server"))
            assertNull(service.mcpServers("server"))
            try { service.setMcpConnected("server", "tool", true, null); fail("Expected unsupported operation") }
            catch (_: IllegalArgumentException) { }
        } finally { db.close() }
    }

    @Test fun inventoryAndMutationUseRequestedConnectionAndProject() = runBlocking {
        installTestAndroidKeyStore()
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java).build()
        try {
            MockWebServer().use { first -> MockWebServer().use { second ->
                val repository = ServerConnectionRepository(db)
                repository.saveOpenCode("first", "First", first.url("/").toString(), OpenCodeCredentials("first", "first-secret"))
                repository.saveOpenCode("second", "Second", second.url("/").toString(), OpenCodeCredentials("second", "second-secret"))
                repository.select("first")
                val service = ServerConnections(repository, clientFactory = { error("Generative Server must not be used") })
                second.enqueue(MockResponse().setBody("""{"data":[{"id":"p","name":"Remote","package":"pkg"}]}"""))
                second.enqueue(MockResponse().setBody("""{"data":[{"name":"tool","status":{"status":"disabled"}}]}"""))
                second.enqueue(MockResponse().setResponseCode(204))
                second.enqueue(MockResponse().setResponseCode(204))
                second.enqueue(MockResponse().setResponseCode(204))
                assertEquals("Remote", service.providers("second", "/project").single().name)
                assertEquals("disabled", service.mcpServers("second", "/project")!!.single().detail)
                service.setMcpConnected("second", "tool", true, "/project")
                service.addMcp("second", "new-tool", Json.parseToJsonElement("""{"type":"remote","url":"https://mcp.test"}""").jsonObject, "/project")
                service.removeMcp("second", "new-tool", "/project")
                second.enqueue(MockResponse().setResponseCode(204))
                service.connectIntegrationKey("second", "provider", "provider-secret", null, "/project")
                repeat(6) {
                    val request = second.takeRequest()
                    assertEquals(Credentials.basic("second", "second-secret", Charsets.UTF_8), request.getHeader("Authorization"))
                    assertEquals("/project", request.requestUrl?.queryParameter("location[directory]"))
                }
                assertEquals(0, first.requestCount)
                assertTrue(requireNotNull(repository.profile("first")).selected)
            } }
        } finally { db.close() }
    }
}
