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
import org.starfall.generative.features.serverconnection.ui.ServerInventoryScreen
import org.starfall.generative.integrations.opencode.OpenCodeCredentials
import java.util.concurrent.ConcurrentLinkedQueue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w400dp-h800dp-xhdpi", application = LibraryTestApplication::class)
class ServerInventoryScopeUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun switchingProjectAndConnectionDoesNotReuseCatalogOrSearchState() {
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
                val service = ServerConnections(repository, clientFactory = { error("Unexpected ServerSide request") })
                var connectionId by mutableStateOf("first")
                compose.setScreenContent {
                    MaterialTheme {
                        ServerInventoryScreen(service, connectionId, providers = true, onBack = {})
                    }
                }
                awaitProvider("First default")
                compose.onNodeWithContentDescription("Switch to Grid View").performClick()
                compose.onNodeWithContentDescription("Search providers").performClick()
                compose.onNodeWithTag("list-screen-search").performTextInput("First")
                compose.onNodeWithText("First default").assertExists()

                // The same provider ID exists in a different project. No first-project row is reused.
                compose.onNodeWithText("Server default").performClick()
                compose.onNodeWithText("Project A · /a").performClick()
                awaitProvider("First project")
                compose.onNodeWithText("First default").assertDoesNotExist()
                compose.onNodeWithContentDescription("Switch to Grid View").assertExists()
                assertTrue(firstLocations.contains("/a"))

                compose.onNodeWithContentDescription("Search providers").performClick()
                compose.onNodeWithTag("list-screen-search").performTextInput("First")
                compose.runOnIdle { connectionId = "second" }
                awaitProvider("Second default")
                compose.onNodeWithText("First project").assertDoesNotExist()
                compose.onNodeWithText("Server default").assertExists()
                assertTrue(secondLocations.contains("default"))
                assertTrue(runBlocking { requireNotNull(repository.profile("first")).selected })
            } }
        } finally { db.close() }
    }

    private fun awaitProvider(name: String) {
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
