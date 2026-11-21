package org.starfall.generative

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast
import org.starfall.generative.database.repositories.ServerConnectionRepository
import org.starfall.generative.database.room.AppDatabase
import org.starfall.generative.features.serverconnection.logic.ServerConnections
import org.starfall.generative.integrations.opencode.OpenCodeCredentials
import org.starfall.generative.navigation.ServerCatalogDestination
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w360dp-h800dp-xhdpi", application = LibraryTestApplication::class)
class ServerCatalogScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun openCodeIntegrationUsesProviderEditorForCredentialsAndDelete() {
        installTestAndroidKeyStore()
        val db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java
        ).build()
        try {
            MockWebServer().use { server ->
                val requests = ConcurrentLinkedQueue<RecordedRequest>()
                val providerConfigured = AtomicBoolean(false)
                server.dispatcher = object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse {
                        val configured = providerConfigured.get()
                        requests.add(request)
                        return when (request.requestUrl?.encodedPath) {
                            "/api/provider" -> MockResponse().setBody(
                                """{"data":[{"id":"provider-id","name":"OpenAI","package":"@opencode/ai/providers/openai-compatible","integrationID":"integration-id"}]}"""
                            )
                            "/api/integration/integration-id" -> MockResponse().setBody(
                                """{"data":{"name":"OpenAI","methods":[{"type":"key","form":{"project":"Project"}}]}}"""
                            )
                            "/api/integration/integration-id/connect/key", "/api/location/reload" ->
                                MockResponse().setResponseCode(204)
                            "/api/config" -> MockResponse().setBody(
                                if (configured)
                                    """[{"type":"directory","path":"/config"},{"type":"document","path":"/config/opencode.jsonc","info":{"providers":{"provider-id":{}}}}]"""
                                else """[{"type":"directory","path":"/config"}]"""
                            )
                            "/api/fs/read/opencode.jsonc" -> MockResponse().setBody(
                                """{"providers":{"provider-id":{},"keep":{}}}"""
                            )
                            "/api/experimental/fs/write" -> MockResponse().setBody(
                                """{"data":{"path":"/config/opencode.jsonc"}}"""
                            )
                            else -> MockResponse().setResponseCode(404)
                        }
                    }
                }
                val repository = ServerConnectionRepository(db)
                runBlocking {
                    repository.saveOpenCode("remote", "Remote", server.url("/").toString(),
                        OpenCodeCredentials("opencode", "secret"))
                }
                val service = ServerConnections(repository, clientFactory = { error("Unexpected CLI request") })
                compose.setScreenContent {
                    MaterialTheme { ServerCatalogDestination(service, "remote", providers = true, onBack = {}) }
                }
                compose.waitUntil(10_000) { compose.onAllNodesWithText("OpenAI").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithText("OpenAI").performClick()
                compose.waitUntil(10_000) { compose.onAllNodesWithTag("provider_api_key").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithTag("provider-configuration-form").assertExists()
                compose.onNodeWithText("Models").assertExists()
                compose.onNodeWithContentDescription("Delete").assertExists()
                compose.onNodeWithText("This server connection uses unencrypted HTTP.").assertExists()
                compose.onNodeWithTag("provider_api_key").performScrollTo().performTextInput("test-key")
                compose.onNode(hasText("Form answers (JSON)") and hasSetTextAction())
                    .performScrollTo().performTextInput("""{"project":"demo"}""")
                compose.onNodeWithText("Save").performClick()
                compose.waitUntil(10_000) {
                    requests.any { it.requestUrl?.encodedPath == "/api/integration/integration-id/connect/key" } &&
                        requests.count { it.requestUrl?.encodedPath == "/api/provider" } >= 2
                }
                val save = requests.single { it.requestUrl?.encodedPath == "/api/integration/integration-id/connect/key" }
                assertEquals("""{"key":"test-key","answer":{"project":"demo"}}""", save.body.readUtf8())

                val toastsBefore = ShadowToast.shownToastCount()
                compose.onNodeWithContentDescription("Delete").performClick()
                compose.onNodeWithText("Delete").performClick()
                compose.waitUntil(10_000) { requests.any { it.requestUrl?.encodedPath == "/api/config" } }
                compose.waitForIdle()
                compose.onNodeWithTag("provider-configuration-form").assertExists()
                compose.onNodeWithContentDescription("Delete").assertExists()
                assertEquals(toastsBefore, ShadowToast.shownToastCount())
                assertTrue(requests.none { it.requestUrl?.encodedPath == "/api/experimental/fs/write" })

                providerConfigured.set(true)
                compose.onNodeWithContentDescription("Delete").performClick()
                compose.onNodeWithText("Delete").performClick()
                compose.waitUntil(10_000) { requests.any { it.requestUrl?.encodedPath == "/api/location/reload" } }
                val write = requests.single { it.requestUrl?.encodedPath == "/api/experimental/fs/write" }
                val providers = Json.parseToJsonElement(write.body.readUtf8()).jsonObject.getValue("providers").jsonObject
                assertEquals(setOf("keep"), providers.keys)
                assertTrue(write.requestUrl?.queryParameter("path") == "/config/opencode.jsonc")
                assertEquals(toastsBefore, ShadowToast.shownToastCount())
            }
        } finally { db.close() }
    }

    @Test fun openCodeOAuthCompletesAFormAndPastedCodeInTheProviderEditor() {
        installTestAndroidKeyStore()
        val db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java,
        ).build()
        try {
            MockWebServer().use { server ->
                val requests = ConcurrentLinkedQueue<RecordedRequest>()
                val oauthPath = "/api/integration/integration-id/connect/oauth"
                server.dispatcher = object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse {
                        requests.add(request)
                        return when (request.requestUrl?.encodedPath) {
                            "/api/provider" -> MockResponse().setBody(
                                """{"data":[{"id":"provider-id","name":"OpenAI","package":"@opencode/ai/providers/openai-compatible","integrationID":"integration-id"}]}"""
                            )
                            "/api/integration/integration-id" -> MockResponse().setBody(
                                """{"data":{"methods":[{"type":"oauth","id":"manual","form":{"project":"Project"}}]}}"""
                            )
                            oauthPath -> MockResponse().setBody(
                                """{"data":{"attemptID":"attempt","mode":"code","url":"https://example.com/auth","instructions":"Paste the code"}}"""
                            )
                            "$oauthPath/attempt/complete" -> MockResponse().setResponseCode(204)
                            "$oauthPath/attempt" -> MockResponse().setBody("""{"data":{"status":"complete"}}""")
                            else -> MockResponse().setResponseCode(404)
                        }
                    }
                }
                val repository = ServerConnectionRepository(db)
                runBlocking {
                    repository.saveOpenCode("remote", "Remote", server.url("/").toString(), OpenCodeCredentials("opencode", "secret"))
                }
                val service = ServerConnections(repository, clientFactory = { error("Unexpected CLI request") })
                compose.setScreenContent {
                    MaterialTheme { ServerCatalogDestination(service, "remote", providers = true, onBack = {}) }
                }
                compose.waitUntil(10_000) { compose.onAllNodesWithText("OpenAI").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithText("OpenAI").performClick()
                compose.waitUntil(10_000) {
                    compose.onAllNodesWithText("Form answers (JSON)").fetchSemanticsNodes().isNotEmpty()
                }
                compose.onNode(hasText("Form answers (JSON)") and hasSetTextAction())
                    .performScrollTo().performTextInput("""{"project":"demo"}""")
                compose.onNodeWithText("Sign in").performScrollTo().performClick()
                compose.waitUntil(10_000) {
                    compose.onAllNodesWithText("Paste the code").fetchSemanticsNodes().isNotEmpty()
                }
                compose.onNode(hasText("Authorization code or redirect URL") and hasSetTextAction())
                    .performScrollTo().performTextInput("one-time-code")
                compose.onNodeWithText("Submit").performScrollTo().performClick()
                compose.waitUntil(10_000) {
                    compose.onAllNodesWithText("Signed in").fetchSemanticsNodes().isNotEmpty()
                }
                assertEquals("""{"methodID":"manual","answer":{"project":"demo"}}""",
                    requests.single { it.requestUrl?.encodedPath == oauthPath }.body.readUtf8())
                assertEquals("""{"code":"one-time-code"}""",
                    requests.single { it.requestUrl?.encodedPath == "$oauthPath/attempt/complete" }.body.readUtf8())
                assertTrue(requests.none { it.method == "DELETE" })
            }
        } finally { db.close() }
    }
}
