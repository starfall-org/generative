package org.starfall.generative

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.starfall.generative.database.repositories.ServerConnectionRepository
import org.starfall.generative.database.room.AppDatabase
import org.starfall.generative.features.serverconnection.logic.ServerConnections
import org.starfall.generative.features.serverconnection.ui.ServerConnectionsScreen
import org.starfall.generative.integrations.opencode.OpenCodeCredentials

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w360dp-h800dp-xhdpi", application = LibraryTestApplication::class)
class ServerConnectionMenuTest {
    @get:Rule val compose = createComposeRule()

    @Test fun menuOpensTheConnectionWhoseCardWasClicked() = runBlocking {
        installTestAndroidKeyStore()
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java).build()
        try {
            val repository = ServerConnectionRepository(db)
            repository.saveOpenCode("one", "First", "https://first.example/", OpenCodeCredentials("first", "secret-one"))
            repository.saveOpenCode("two", "Second", "https://second.example/", OpenCodeCredentials("second", "secret-two"))
            repository.select("one")
            val service = ServerConnections(repository, clientFactory = { error("Unexpected server request") })
            val opened = mutableListOf<String>()
            compose.setScreenContent {
                MaterialTheme {
                    ServerConnectionsScreen(service, onProviders = { opened += "providers:$it" },
                        onMcp = { opened += "mcp:$it" }, onBack = {})
                }
            }
            compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("More options").fetchSemanticsNodes().size == 2 }
            compose.onAllNodesWithContentDescription("More options")[1].performClick()
            compose.onNodeWithText("Import from local").assertDoesNotExist()
            compose.onNodeWithText("Model Providers").performClick()
            assertEquals(listOf("providers:two"), opened)
            compose.onAllNodesWithContentDescription("More options")[1].performClick()
            compose.onNodeWithText("MCP").performClick()
            assertEquals(listOf("providers:two", "mcp:two"), opened)

            compose.onNodeWithText("Second").performClick()
            compose.waitUntil(10_000) {
                compose.onAllNodesWithText("Import from local").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("Model Providers").assertDoesNotExist()
            compose.onNodeWithText("Open Model Providers").performScrollTo().performClick()
            compose.onNodeWithText("Open MCP").performScrollTo().performClick()
            compose.onNodeWithText("MCP").performScrollTo().performClick()
            compose.waitUntil(10_000) {
                compose.onAllNodesWithText("No items").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("The selected connection and credentials will be copied to OpenCode until it restarts.")
                .assertExists()
            assertEquals(listOf("providers:two", "mcp:two", "providers:two", "mcp:two"), opened)
        } finally { db.close() }
    }
}
