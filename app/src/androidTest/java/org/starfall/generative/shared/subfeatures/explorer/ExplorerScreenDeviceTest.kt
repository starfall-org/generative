package org.starfall.generative.shared.subfeatures.explorer

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import android.net.Uri
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.starfall.generative.shared.subfeatures.explorer.logic.ExplorerItem
import org.starfall.generative.shared.subfeatures.explorer.logic.ExplorerLocation
import org.starfall.generative.shared.subfeatures.explorer.logic.ExplorerRepository
import org.starfall.generative.shared.subfeatures.explorer.ui.ExplorerScreen

/**
 * Real-device Compose tests with a deterministic backend.
 * No SSH connection, application database or private MediaStorage data is needed.
 */
@RunWith(AndroidJUnit4::class)
class ExplorerScreenDeviceTest {
    @get:Rule val compose = createComposeRule()
    private var clearExplorerFocus: () -> Unit = {}

    private val home = ExplorerLocation()
    private val local = ExplorerLocation(sourceId = "local", label = "LocalFiles")
    private val remote = ExplorerLocation(sourceId = "remote:one", label = "RemoteFiles")

    private fun item(parent: ExplorerLocation, name: String, directory: Boolean = false) =
        ExplorerItem(name, parent.copy(path = if (parent.path.isEmpty()) name else "${parent.path}/$name"),
            directory, if (directory) 0 else 1536)

    private inner class MemoryRepository : ExplorerRepository {
        val calls = AtomicInteger(0)
        val contents = mutableMapOf(
            home to mutableListOf(
                ExplorerItem("LocalFiles", local, true),
                ExplorerItem("RemoteFiles", remote, true)
            ),
            local to mutableListOf(
                item(local, "Projects", directory = true),
                item(local, "report.txt"),
                item(local, "photo.PNG")
            ),
            local.copy(path = "Projects") to mutableListOf(item(local.copy(path = "Projects"), "inside.md")),
            remote to mutableListOf(
                item(remote, "remote-folder", directory = true),
                item(remote, "server.log")
            )
        )
        var blockedList: CompletableDeferred<Unit>? = null
        var failNextList = false
        var failNextCreate = false

        override suspend fun list(location: ExplorerLocation): List<ExplorerItem> {
            calls.incrementAndGet()
            blockedList?.await()
            if (failNextList) {
                failNextList = false
                throw IOException("Listing unavailable")
            }
            return contents[location].orEmpty().toList()
        }

        override suspend fun createFolder(parent: ExplorerLocation, name: String) {
            if (failNextCreate) {
                failNextCreate = false
                throw IOException("Cannot create folder")
            }
            val folder = item(parent, name, directory = true)
            contents.getOrPut(parent) { mutableListOf() }.add(folder)
            contents[folder.location] = mutableListOf()
        }

        override suspend fun rename(item: ExplorerItem, newName: String) {
            val parent = item.location.parent()
            contents.getValue(parent).remove(item)
            val renamed = item.copy(name = newName,
                location = item.location.copy(path = parent.path.let {
                    if (it.isEmpty()) newName else "$it/$newName"
                }))
            contents.getValue(parent).add(renamed)
            if (item.directory) {
                contents[renamed.location] = contents.remove(item.location) ?: mutableListOf()
            }
        }

        override suspend fun delete(item: ExplorerItem) {
            contents.getValue(item.location.parent()).remove(item)
            contents.remove(item.location)
        }

    }

    private fun start(
        repo: ExplorerRepository, initial: ExplorerLocation = home,
        onClose: () -> Unit = {}, onOpen: suspend (ExplorerItem) -> Unit = {},
        onUpload: (suspend (ExplorerLocation, Uri) -> Unit)? = null,
        onDownload: (suspend (ExplorerItem, Uri) -> Unit)? = null
    ) {
        compose.setContent {
            val focusManager = LocalFocusManager.current
            clearExplorerFocus = { focusManager.clearFocus() }
            MaterialTheme {
                ExplorerScreen(repo, initial, onClose = onClose, onOpenFile = onOpen,
                    onUploadFile = onUpload, onDownloadFile = onDownload)
            }
        }
    }

    private fun waitFor(text: String) {
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun menu(path: String) = "explorer-menu:local:$path"

    @Test fun homeShowsInjectedSourcesAndNavigatesNestedFoldersBeforeClosing() {
        val repo = MemoryRepository()
        var closed = 0
        start(repo, onClose = { closed++ })

        waitFor("LocalFiles")
        compose.onNodeWithText("RemoteFiles").performClick()
        waitFor("server.log")
        compose.onNodeWithText("remote-folder").assertIsDisplayed()
        compose.onNodeWithTag("explorer-back").performClick()
        compose.onNodeWithText("LocalFiles").performClick()
        waitFor("Projects")
        compose.onNodeWithText("Projects").performClick()
        waitFor("inside.md")
        compose.onNodeWithTag("explorer-back").performClick()
        waitFor("report.txt")
        compose.onNodeWithTag("explorer-back").performClick()
        waitFor("RemoteFiles")
        compose.runOnIdle { assertEquals(0, closed) }
        compose.onNodeWithTag("explorer-back").performClick()
        compose.runOnIdle { assertEquals(1, closed) }
    }

    @Test fun searchIsCaseInsensitiveGridTogglePreservesFilterAndOpeningFileUsesHostPort() {
        val repo = MemoryRepository()
        val opened = mutableListOf<ExplorerItem>()
        start(repo, local, onOpen = { opened.add(it) })
        waitFor("report.txt")
        compose.onNodeWithTag("explorer-search").assertDoesNotExist()
        compose.onNodeWithTag("explorer-open-search").performClick()
        compose.onNodeWithTag("explorer-search").performTextInput("REPort")
        compose.onNodeWithText("report.txt").assertIsDisplayed()
        compose.onNodeWithText("photo.PNG").assertDoesNotExist()
        compose.onNodeWithText("Projects").assertDoesNotExist()

        compose.onNodeWithTag("explorer-search").performImeAction()
        compose.onNodeWithTag("explorer-open-search").assertIsDisplayed()
        compose.onNodeWithTag("explorer-search").assertDoesNotExist()
        compose.onNodeWithText("report.txt").assertIsDisplayed()
        compose.onNodeWithText("photo.PNG").assertDoesNotExist()
        compose.onNodeWithTag("explorer-toggle-layout").performClick()
        compose.onNodeWithText("report.txt").assertIsDisplayed()
        compose.onNodeWithTag("explorer-open-search").performClick()
        compose.onNodeWithTag("explorer-search").assertTextContains("REPort")
        compose.onNodeWithTag("explorer-close-search").performClick()
        compose.onNodeWithTag("explorer-search").assertDoesNotExist()
        compose.onNodeWithText("report.txt").performClick()
        compose.waitUntil(5_000) { opened.isNotEmpty() }
        compose.runOnIdle {
            assertEquals("report.txt", opened.single().name)
            assertEquals("local", opened.single().location.sourceId)
        }

        compose.onNodeWithTag("explorer-open-search").performClick()
        compose.onNodeWithTag("explorer-search").performTextClearance()
        waitFor("photo.PNG")
        compose.onNodeWithTag("explorer-close-search").performClick()
        compose.onNodeWithTag("explorer-toggle-layout").performClick()
        compose.onNodeWithText("Projects").assertIsDisplayed()
    }

    @Test fun searchLosingFocusRetractsFieldWithoutResettingFilter() {
        val repo = MemoryRepository()
        start(repo, local)
        waitFor("report.txt")
        compose.onNodeWithTag("explorer-open-search").performClick()
        compose.onNodeWithTag("explorer-search").performTextInput("report")
        compose.runOnIdle { clearExplorerFocus() }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("explorer-search").fetchSemanticsNodes().isEmpty()
        }
        compose.onNodeWithText("report.txt").assertIsDisplayed()
        compose.onNodeWithText("photo.PNG").assertDoesNotExist()
        compose.onNodeWithTag("explorer-open-search").performClick()
        compose.onNodeWithTag("explorer-search").assertTextContains("report")
    }

    @Test fun createRenameAndDeleteRequireDialogConfirmationAndUpdateVisibleEntries() {
        val repo = MemoryRepository()
        start(repo, local)
        waitFor("report.txt")

        compose.onNodeWithTag("explorer-new-folder").performClick()
        compose.onNodeWithTag("explorer-name").performTextInput("Drafts")
        compose.onNodeWithTag("explorer-cancel").performClick()
        compose.onNodeWithText("Drafts").assertDoesNotExist()

        compose.onNodeWithTag("explorer-new-folder").performClick()
        compose.onNodeWithTag("explorer-name").performTextInput("Drafts")
        compose.onNodeWithTag("explorer-confirm").performClick()
        waitFor("Drafts")
        compose.runOnIdle {
            assertTrue(repo.contents.getValue(local).any { it.name == "Drafts" && it.directory })
        }

        compose.onNodeWithTag(menu("report.txt")).performClick()
        compose.onNodeWithText("Rename").performClick()
        compose.onNodeWithTag("explorer-name").assertTextContains("report.txt")
        compose.onNodeWithTag("explorer-name").performTextClearance()
        compose.onNodeWithTag("explorer-name").performTextInput("renamed.txt")
        compose.onNodeWithTag("explorer-confirm").performClick()
        waitFor("renamed.txt")
        compose.onNodeWithText("report.txt").assertDoesNotExist()

        compose.onNodeWithTag(menu("renamed.txt")).performClick()
        compose.onNodeWithText("Delete").performClick()
        compose.onNodeWithText("Delete renamed.txt?").assertIsDisplayed()
        compose.onNodeWithTag("explorer-cancel").performClick()
        compose.onNodeWithText("renamed.txt").assertIsDisplayed()

        compose.onNodeWithTag(menu("renamed.txt")).performClick()
        compose.onNodeWithText("Delete").performClick()
        compose.onNodeWithTag("explorer-confirm").performClick()
        compose.waitUntil(5_000) {
            repo.contents.getValue(local).none { it.name == "renamed.txt" }
        }
        compose.onNodeWithText("renamed.txt").assertDoesNotExist()
        compose.runOnIdle { assertFalse(repo.contents.getValue(local).any { it.name == "renamed.txt" }) }
    }

    @Test fun transferActionsAreOnlyOfferedWhenHostSupportsThem() {
        val repo = MemoryRepository()
        start(repo, local, onUpload = { _, _ -> }, onDownload = { _, _ -> })
        waitFor("report.txt")
        compose.onNodeWithTag("explorer-upload").assertIsDisplayed()

        compose.onNodeWithTag(menu("report.txt")).performClick()
        compose.onNodeWithText("Download").assertIsDisplayed()
        compose.onNodeWithText("Rename").assertIsDisplayed()
        compose.onNodeWithText("Delete").assertIsDisplayed()
    }

    @Test fun uploadDoesNotAppearAtSourceHomeAndFoldersCannotBeDownloaded() {
        val repo = MemoryRepository()
        start(repo, onUpload = { _, _ -> }, onDownload = { _, _ -> })
        waitFor("LocalFiles")
        compose.onNodeWithTag("explorer-upload").assertDoesNotExist()
        compose.onNodeWithText("LocalFiles").performClick()
        waitFor("Projects")
        compose.onNodeWithTag(menu("Projects")).performClick()
        compose.onNodeWithText("Download").assertDoesNotExist()
    }

    @Test fun transferActionsStayHiddenWhenHostDoesNotProvideCallbacks() {
        val repo = MemoryRepository()
        start(repo, local)
        waitFor("report.txt")
        compose.onNodeWithTag("explorer-upload").assertDoesNotExist()
        compose.onNodeWithTag(menu("report.txt")).performClick()
        compose.onNodeWithText("Download").assertDoesNotExist()
    }

    @Test fun refreshReadsRepositoryAgainAndEmptyDirectoryShowsPlaceholder() {
        val repo = MemoryRepository()
        start(repo, local.copy(path = "Projects"))
        waitFor("inside.md")
        val before = repo.calls.get()
        compose.onNodeWithTag("explorer-refresh").performClick()
        compose.waitUntil(5_000) { repo.calls.get() > before }
        compose.runOnIdle { repo.contents.getValue(local.copy(path = "Projects")).clear() }
        compose.onNodeWithTag("explorer-refresh").performClick()
        waitFor("No files")
        compose.onNodeWithText("inside.md").assertDoesNotExist()
    }

    @Test fun loadAndOperationFailuresAreVisibleAndRefreshCanRecover() {
        val repo = MemoryRepository()
        repo.failNextList = true
        start(repo, local)
        waitFor("Listing unavailable")
        compose.onNodeWithTag("explorer-refresh").performClick()
        waitFor("report.txt")

        repo.failNextCreate = true
        compose.onNodeWithTag("explorer-new-folder").performClick()
        compose.onNodeWithTag("explorer-name").performTextInput("CannotSave")
        compose.onNodeWithTag("explorer-confirm").performClick()
        waitFor("Cannot create folder")
        compose.onNodeWithText("CannotSave").assertDoesNotExist()
        compose.onNodeWithText("report.txt").assertIsDisplayed()
    }

    @Test fun aFeatureOwnedRootClosesDirectlyWithoutSwitchingToAnotherFeature() {
        val repo = MemoryRepository()
        var closed = 0
        start(repo, remote, onClose = { closed++ })
        waitFor("server.log")
        compose.onNodeWithTag("explorer-back").performClick()
        compose.runOnIdle { assertEquals(1, closed) }
    }

    @Test fun remoteFileIsPassedToHostViewerPortWithoutAnyNetworkDependency() {
        val repo = MemoryRepository()
        val opened = mutableListOf<ExplorerItem>()
        start(repo, remote, onOpen = { opened.add(it) })
        waitFor("server.log")
        compose.onNodeWithText("server.log").performClick()
        compose.waitUntil(5_000) { opened.size == 1 }
        compose.runOnIdle {
            assertEquals("remote:one", opened.single().location.sourceId)
            assertEquals("server.log", opened.single().location.path)
        }
    }

    @Test fun viewerPortFailureShowsSnackbarWithoutLeavingFolder() {
        val repo = MemoryRepository()
        start(repo, remote, onOpen = { throw IOException("Viewer unavailable") })
        waitFor("server.log")
        compose.onNodeWithText("server.log").performClick()
        waitFor("Viewer unavailable")
        compose.onNodeWithText("server.log").assertIsDisplayed()
    }

    @Test fun suspendedLoadingDisplaysProgressAndThenRendersItems() {
        val repo = MemoryRepository()
        val release = CompletableDeferred<Unit>()
        repo.blockedList = release
        start(repo, remote)
        compose.onNodeWithTag("explorer-loading").assertIsDisplayed()
        compose.onNodeWithText("server.log").assertDoesNotExist()
        release.complete(Unit)
        waitFor("server.log")
        compose.onNodeWithTag("explorer-loading").assertDoesNotExist()
    }
}
