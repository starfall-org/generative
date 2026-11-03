package org.starfall.generative

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.starfall.generative.appwiring.DefaultDataInitializer
import org.starfall.generative.integrations.mcp.McpService
import org.starfall.generative.integrations.modelapis.AiService
import org.starfall.generative.shared.icons.IconStore
import org.starfall.generative.shared.model.McpInfo
import org.starfall.generative.shared.networking.ToolHttp
import org.starfall.generative.database.McpRepository
import org.starfall.generative.database.ModelRepository
import org.starfall.generative.database.SpeechRepository
import org.starfall.generative.database.AppDatabase
import org.starfall.generative.shared.storage.preferences.AppPreferencesRepository
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = LibraryTestApplication::class)
class DefaultDataInitializerContentApiTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var database: AppDatabase

    @Before
    fun setUp() {
        installTestAndroidKeyStore()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun buildEndpointIsUsedForTransportButHiddenFromUiAndBackup() = runBlocking {
        val configuredUrl = "https://hidden.example.test/mcp"
        val repository = McpRepository(database, McpService(ToolHttp()),
            contentApiMcpUrl = configuredUrl)
        val preset = McpInfo(id = "content-api", name = "Content API")
        repository.saveServer(preset)

        assertNull(repository.allServers.first().single().url)
        assertNull(repository.getById(preset.id)?.url)
        assertNull(repository.loadServersForExport().single().url)
        assertNull(database.mcpServerDao().getServerById(preset.id)?.url)
        assertEquals(configuredUrl, repository.resolveServer(preset).url)

        // An old record containing the configured endpoint is scrubbed on save.
        repository.saveServer(preset.copy(url = configuredUrl))
        assertNull(repository.allServers.first().single().url)
        assertNull(repository.getById(preset.id)?.url)
        assertNull(repository.loadServersForExport().single().url)
        assertNull(database.mcpServerDao().getServerById(preset.id)?.url)

        // Old backups may contain another embedded URL from an earlier build.
        // Never persist or re-export such URLs for the bundled preset.
        val oldEmbeddedUrl = "https://old-build.example.test/mcp"
        repository.saveServer(preset.copy(url = oldEmbeddedUrl))
        assertNull(repository.allServers.first().single().url)
        assertNull(repository.getById(preset.id)?.url)
        assertNull(repository.loadServersForExport().single().url)
        assertNull(database.mcpServerDao().getServerById(preset.id)?.url)

        // Ordinary MCP servers still retain their user-supplied URLs.
        val custom = McpInfo(id = "custom", name = "Custom", url = oldEmbeddedUrl)
        repository.saveServer(custom)
        assertEquals(oldEmbeddedUrl, repository.allServers.first().single { it.id == custom.id }.url)
        assertEquals(oldEmbeddedUrl, repository.loadServersForExport().single { it.id == custom.id }.url)
    }

    @Test(timeout = 20_000)
    fun startupLoadsAndPersistsBundledContentApiToolsOnlyWhenCacheIsMissing() = runBlocking {
        val toolListCalls = AtomicInteger()
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.method == "DELETE") return MockResponse().setResponseCode(204)
                if (request.method == "GET") return MockResponse().setResponseCode(405)

                val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
                val id = body["id"]
                val result: JsonObject = when (body["method"]?.jsonPrimitive?.content) {
                    "initialize" -> buildJsonObject {
                        put("protocolVersion", "2025-06-18")
                        put("capabilities", buildJsonObject { put("tools", buildJsonObject {}) })
                        put("serverInfo", buildJsonObject { put("name", "content-api-test"); put("version", "1") })
                    }
                    "notifications/initialized" -> return MockResponse().setResponseCode(202)
                    "tools/list" -> {
                        toolListCalls.incrementAndGet()
                        buildJsonObject {
                            put("tools", buildJsonArray {
                                add(buildJsonObject {
                                    put("name", "search_content")
                                    put("description", "Search content")
                                    put("inputSchema", buildJsonObject {
                                        put("type", "object")
                                        put("properties", buildJsonObject {})
                                    })
                                })
                            })
                        }
                    }
                    else -> return MockResponse().setResponseCode(400)
                }
                return MockResponse().setHeader("Content-Type", "application/json").setBody(
                    buildJsonObject {
                        put("jsonrpc", "2.0")
                        put("id", id!!)
                        put("result", result)
                    }.toString()
                )
            }
        }
        server.start()
        try {
            val preferences = AppPreferencesRepository(context)
            preferences.setMcpPresetsInitialized(true)
            preferences.setContentApiPresetInitialized(true)

            val mcpRepository = McpRepository(database, McpService(ToolHttp()))
            mcpRepository.saveServer(McpInfo(
                id = "content-api",
                name = "Content API",
                url = server.url("/mcp").toString()
            ))
            val initializer = DefaultDataInitializer(
                ModelRepository(database, AiService(context)),
                mcpRepository,
                SpeechRepository(database),
                preferences,
                IconStore(context)
            )

            initializer.initialize()

            val cachedTools = mcpRepository.getById("content-api")?.cachedTools
            assertNotNull("Startup should persist the bundled MCP tool list", cachedTools)
            assertEquals(listOf("search_content"), cachedTools!!.map { it.originalName })
            assertEquals(1, toolListCalls.get())

            // Subsequent startup passes reuse the persisted list instead of making a network call.
            initializer.initialize()
            assertEquals(1, toolListCalls.get())
        } finally {
            server.shutdown()
        }
    }
}
