package org.starfall.generative

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.starfall.generative.shared.subfeatures.explorer.logic.ExplorerItem
import org.starfall.generative.shared.subfeatures.explorer.logic.ExplorerLocation
import org.starfall.generative.shared.subfeatures.explorer.logic.ExplorerRepository
import org.starfall.generative.shared.subfeatures.explorer.ui.ExplorerScreen

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = LibraryTestApplication::class)
class ExplorerUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun frameworkNavigatesAndFiltersWithoutKnowingStorageBackend() {
        val media = ExplorerLocation(sourceId = "media", label = "MediaStorage")
        val repository = object : ExplorerRepository {
            override suspend fun list(location: ExplorerLocation): List<ExplorerItem> = when (location.sourceId) {
                "" -> listOf(ExplorerItem("MediaStorage", media, true))
                "media" -> listOf(
                    ExplorerItem("report.txt", media.copy(path = "report.txt"), false),
                    ExplorerItem("picture.png", media.copy(path = "picture.png"), false)
                )
                else -> emptyList()
            }
            override suspend fun createFolder(parent: ExplorerLocation, name: String) = Unit
            override suspend fun rename(item: ExplorerItem, newName: String) = Unit
            override suspend fun delete(item: ExplorerItem) = Unit
        }
        compose.setContent {
            MaterialTheme { ExplorerScreen(repository, ExplorerLocation(), onClose = {}, onOpenFile = {}) }
        }
        compose.onNodeWithText("MediaStorage").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("report.txt").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("report.txt").assertExists()
        compose.onNodeWithText("picture.png").assertExists()
        compose.onNodeWithTag("explorer-search").assertDoesNotExist()
        compose.onNodeWithTag("explorer-open-search").performClick()
        compose.onNodeWithTag("explorer-search").assertExists()
    }

    @Test fun locationTracksParentWithoutAnyHostData() {
        val root = ExplorerLocation()
        val machine = ExplorerLocation("machine:id-123", "documents/notes", "SFTP_Dev")
        assertEquals(ExplorerLocation("machine:id-123", "documents", "SFTP_Dev"), machine.parent())
        assertEquals(root, machine.copy(path = "").parent())
    }
}
