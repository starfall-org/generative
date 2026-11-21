package org.starfall.generative

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.starfall.generative.database.repositories.ServerConnectionRepository
import org.starfall.generative.database.room.AppDatabase
import org.starfall.generative.features.serverconnection.logic.ServerConnections
import org.starfall.generative.navigation.RuntimeMcpConnectors
import org.starfall.generative.navigation.ServerCatalogDestination
import org.starfall.generative.integrations.opencode.OpenCodeCredentials
import java.util.concurrent.ConcurrentLinkedQueue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w400dp-h800dp-xhdpi", application = LibraryTestApplication::class)
class ServerInventoryScopeUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun switchingConnectionDoesNotReuseCatalogOrSearchState() {
        installTestAndroidKeyStore()
        val db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java
        ).build()
        try {
            MockWebServer().use { first -> MockWebServer().use { second ->
                val firstLocations = ConcurrentLinkedQueue<String>()
                val secondLocations = ConcurrentLinkedQueue<String>()
                configure(first, "First", firstLocations)
                configure(second, "Second", secondLocations)
                val repository = ServerConnectionRepository(db)
                runBlocking {
                    repository.saveOpenCode("first", "First", first.url("/").toString(), OpenCodeCredentials("first", "secret"))
                    repository.saveOpenCode("second", "Second", second.url("/").toString(), OpenCodeCredentials("second", "secret"))
                    repository.select("first")
                }
                val service = ServerConnections(repository, clientFactory = { error("Unexpected Generative Server request") })
                var connectionId by mutableStateOf("first")
                compose.setScreenContent {
                    MaterialTheme {
                        ServerCatalogDestination(service, connectionId, providers = true, onBack = {})
                    }
                }
                awaitText("First default")
                compose.onNodeWithText("Server default").assertDoesNotExist()
                compose.onNodeWithTag("add_provider").assertExists()
                compose.onNodeWithTag("add_provider_group").assertExists()
                compose.onNodeWithContentDescription("Switch to Grid View").performClick()
                compose.onNodeWithContentDescription("Search providers").performClick()
                compose.onNodeWithTag("provider_search").performTextInput("First")
                compose.onNodeWithText("First default").assertExists()

                assertTrue(firstLocations.isNotEmpty() && firstLocations.all { it == "default" })
                compose.runOnIdle { connectionId = "second" }
                awaitText("Second default")
                compose.onNodeWithText("First default").assertDoesNotExist()
                compose.onNodeWithText("Server default").assertDoesNotExist()
                compose.onNodeWithContentDescription("Switch to Grid View").assertExists()
                assertTrue(secondLocations.isNotEmpty() && secondLocations.all { it == "default" })
                assertTrue(runBlocking { requireNotNull(repository.profile("first")).selected })
            } }
        } finally { db.close() }
    }

    @Test fun bothCatalogsUseDefaultScopeWithoutRequestingProjects() {
        installTestAndroidKeyStore()
        val db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java
        ).build()
        try {
            MockWebServer().use { server ->
                val requests = ConcurrentLinkedQueue<RecordedRequest>()
                server.dispatcher = object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse {
                        requests.add(request)
                        return when (request.requestUrl?.encodedPath) {
                            "/api/provider" -> MockResponse().setBody(
                                """{"data":[{"id":"p","name":"Remote provider","package":"pkg"}]}"""
                            )
                            "/api/mcp" -> MockResponse().setBody(
                                """{"data":[{"name":"Remote MCP","status":{"status":"connected"}}]}"""
                            )
                            "/api/experimental/mcp/new-runtime", "/api/location/reload" -> MockResponse().setResponseCode(204)
                            "/api/config" -> MockResponse().setBody(
                                """[{"type":"directory","path":"/config"},{"type":"document","path":"/config/opencode.jsonc","info":{"mcp":{"servers":{"Remote MCP":{}}}}}]"""
                            )
                            "/api/fs/read/opencode.jsonc" -> MockResponse().setBody(
                                """{"mcp":{"servers":{"Remote MCP":{"type":"remote","url":"https://old.example/mcp","pool":true}}}}"""
                            )
                            "/api/experimental/fs/write" -> MockResponse().setBody("""{"data":{"path":"/config/opencode.jsonc"}}""")
                            else -> MockResponse().setResponseCode(404)
                        }
                    }
                }
                val repository = ServerConnectionRepository(db)
                runBlocking {
                    repository.saveOpenCode("server", "Server", server.url("/").toString(),
                        OpenCodeCredentials("opencode", "secret"))
                }
                val service = ServerConnections(repository, clientFactory = { error("Unexpected Generative Server request") })
                var providers by mutableStateOf(true)
                compose.setScreenContent {
                    MaterialTheme {
                        ServerCatalogDestination(service, "server", providers = providers, onBack = {})
                    }
                }
                awaitText("Remote provider")
                compose.onNodeWithTag("add_provider").assertExists()
                compose.onNodeWithTag("add_provider_group").assertExists()
                compose.onNodeWithText("Server default").assertDoesNotExist()
                compose.runOnIdle { providers = false }
                awaitText("Remote MCP")
                compose.onNodeWithContentDescription("Add").performClick()
                compose.onNodeWithTag("mcp-connection-settings").assertExists()
                compose.onNodeWithTag("mcp_name").performTextInput("new-runtime")
                compose.onNodeWithTag("mcp_url").performTextInput("https://example.com/mcp")
                compose.onNodeWithText("Save").performClick()
                compose.waitUntil(10_000) {
                    requests.any { it.method == "PUT" && it.requestUrl?.encodedPath == "/api/experimental/mcp/new-runtime" }
                }
                val save = requests.single { it.method == "PUT" }
                val body = save.body.readUtf8()
                assertTrue(body.contains("https://example.com/mcp") && body.contains("\"type\":\"remote\""))
                compose.onNodeWithText("Server default").assertDoesNotExist()
                assertTrue(requests.none { it.requestUrl?.encodedPath == "/api/project" })
                assertTrue(requests.all { it.requestUrl?.queryParameter("location[directory]") == null })

                compose.waitUntil(10_000) {
                    compose.onAllNodesWithTag("mcp-connection-settings").fetchSemanticsNodes().isEmpty()
                }
                compose.onNodeWithText("Remote MCP").performClick()
                compose.waitUntil(10_000) {
                    compose.onAllNodesWithTag("mcp_url").fetchSemanticsNodes().isNotEmpty()
                }
                compose.onNodeWithTag("mcp_url").assertTextContains("https://old.example/mcp")
                    .performTextReplacement("https://new.example/mcp")
                compose.onNodeWithText("Save").performClick()
                compose.waitUntil(10_000) {
                    requests.any { it.requestUrl?.encodedPath == "/api/location/reload" }
                }
                val edit = requests.single { it.requestUrl?.encodedPath == "/api/experimental/fs/write" }
                assertTrue(edit.requestUrl?.queryParameter("path") == "/config/opencode.jsonc")
                val written = edit.body.readUtf8()
                assertTrue(written.contains("https://new.example/mcp") && written.contains("\"pool\":true"))
            }
        } finally { db.close() }
    }

    @Test fun runtimeMcpToggleAndRefreshUseTheChatDirectory() {
        installTestAndroidKeyStore()
        val db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java
        ).build()
        try {
            MockWebServer().use { server ->
                val requests = ConcurrentLinkedQueue<RecordedRequest>()
                server.dispatcher = object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse {
                        requests.add(request)
                        return when (request.requestUrl?.encodedPath) {
                            "/api/mcp" -> {
                                val status = if (requests.any { it.method == "POST" }) "connected" else "disabled"
                                MockResponse().setBody(
                                    """{"data":[{"name":"remote","title":"Remote MCP","status":{"status":"$status"}}]}"""
                                )
                            }
                            "/api/experimental/mcp/remote/connect" -> MockResponse().setResponseCode(204)
                            else -> MockResponse().setResponseCode(404)
                        }
                    }
                }
                val repository = ServerConnectionRepository(db)
                runBlocking {
                    repository.saveOpenCode("server", "Server", server.url("/").toString(),
                        OpenCodeCredentials("opencode", "secret"))
                }
                val service = ServerConnections(repository, clientFactory = { error("Unexpected Generative Server request") })
                compose.setScreenContent {
                    MaterialTheme {
                        RuntimeMcpConnectors(service, "server", "/workspace chat", onBack = {}, onOpenSettings = {})
                    }
                }
                awaitText("Disabled")
                compose.onNodeWithContentDescription("Remote MCP").assertIsOff().performClick()
                awaitText("Connected")
                compose.onNodeWithContentDescription("Remote MCP").assertIsOn()
                assertTrue(requests.any { it.method == "POST" && it.requestUrl?.encodedPath == "/api/experimental/mcp/remote/connect" })
                assertTrue(requests.all { it.requestUrl?.queryParameter("location[directory]") == "/workspace chat" })
            }
        } finally { db.close() }
    }

    private fun awaitText(name: String) {
        compose.waitUntil(10_000) { compose.onAllNodesWithText(name).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(name).assertExists()
    }

    private fun configure(server: MockWebServer, prefix: String, locations: ConcurrentLinkedQueue<String>) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return when (request.requestUrl?.encodedPath) {
                    "/api/project" -> MockResponse().setBody("""[{"id":"a","name":"Project A","canonical":"/a"}]""")
                    "/api/provider" -> {
                        val location = request.requestUrl?.queryParameter("location[directory]")
                        locations.add(location ?: "default")
                        val name = "$prefix " + if (location == "/a") "project" else "default"
                        MockResponse().setBody("""{"data":[{"id":"shared","name":"$name","package":"pkg"}]}""")
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
    }
}
