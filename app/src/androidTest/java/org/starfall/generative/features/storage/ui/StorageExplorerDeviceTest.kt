package org.starfall.generative.features.storage.ui

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.starfall.generative.R
import org.starfall.generative.shared.ui.theme.GenerativeTheme
import org.starfall.generative.shared.subfeatures.media.logic.ToolFiles

/** End-to-end UI checks against real app-private files and the existing viewer. */
@RunWith(AndroidJUnit4::class)
class StorageExplorerDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val root: File get() = ToolFiles.appStorageDirectory(context)
    private val uniqueId = UUID.randomUUID().toString().take(12)
    private val folderName = "explorer-device-$uniqueId"
    private val fileName = "preview-note-$uniqueId.txt"
    private val createdName = "created-$uniqueId"
    private val renamedName = "renamed-$uniqueId"
    private val folder: File get() = File(root, folderName)
    private val child: File get() = File(folder, fileName)
    private val sameNameAtRoot: File get() = File(root, fileName)

    @Before fun setUp() {
        folder.mkdirs()
        child.writeText("Nested explorer preview content")
        sameNameAtRoot.writeText("Root file must not be deleted")
        compose.setContent {
            GenerativeTheme { StorageExplorerScreen(onClose = {}, onOpenExternal = { _, _ -> }) }
        }
    }

    @After fun tearDown() {
        folder.deleteRecursively()
        sameNameAtRoot.delete()
        File(root, createdName).deleteRecursively()
        File(root, renamedName).deleteRecursively()
    }

    private fun waitFor(text: String) {
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun openNestedFile() {
        waitFor("MediaStorage")
        compose.onNodeWithText("MediaStorage").performClick()
        waitFor(folderName)
        compose.onNodeWithTag("explorer-list").performScrollToNode(hasText(folderName))
        compose.onNodeWithText(folderName).performClick()
        waitFor(fileName)
        compose.onNodeWithText(fileName).performClick()
        compose.onNodeWithContentDescription(context.getString(R.string.media_close_viewer))
            .assertIsDisplayed()
    }

    @Test fun nestedLocalFileUsesTheSharedViewerAndReturnsToExplorer() {
        openNestedFile()
        compose.onNodeWithContentDescription(context.getString(R.string.media_close_viewer))
            .performClick()
        compose.onNodeWithText(fileName).assertIsDisplayed()
        assertTrue(child.exists())
    }

    @Test fun deleteFromViewerTargetsNestedFileNotSameNamedRootFile() {
        openNestedFile()
        compose.onNodeWithContentDescription(context.getString(R.string.common_more_options))
            .performClick()
        compose.onNodeWithText(context.getString(R.string.common_delete)).performClick()
        compose.onNodeWithText(context.getString(R.string.common_delete)).performClick()
        compose.waitUntil(10_000) { !child.exists() }
        compose.onNodeWithContentDescription(context.getString(R.string.media_close_viewer))
            .assertDoesNotExist()
        waitFor("No files")
        assertFalse(child.exists())
        assertTrue("Deleting a nested file must not delete a same-named root file", sameNameAtRoot.exists())
    }

    @Test fun uiFolderOperationsPersistThroughTheRealStorageAdapter() {
        waitFor("MediaStorage")
        compose.onNodeWithText("MediaStorage").performClick()
        waitFor(folderName)
        compose.onNodeWithTag("explorer-new-folder").performClick()
        compose.onNodeWithTag("explorer-name").performTextInput(createdName)
        compose.onNodeWithTag("explorer-confirm").performClick()
        compose.waitUntil(10_000) { File(root, createdName).isDirectory }
        compose.onNodeWithTag("explorer-list").performScrollToNode(hasText(createdName))
        compose.onNodeWithTag("explorer-menu:media:$createdName").performClick()
        compose.onNodeWithText("Rename").performClick()
        compose.onNodeWithTag("explorer-name").performTextClearance()
        compose.onNodeWithTag("explorer-name").performTextInput(renamedName)
        compose.onNodeWithTag("explorer-confirm").performClick()
        compose.waitUntil(10_000) { File(root, renamedName).isDirectory }
        compose.onNodeWithTag("explorer-list").performScrollToNode(hasText(renamedName))
        compose.onNodeWithTag("explorer-menu:media:$renamedName").performClick()
        compose.onNodeWithText("Delete").performClick()
        compose.onNodeWithTag("explorer-confirm").performClick()
        compose.waitUntil(10_000) { !File(root, renamedName).exists() }
        assertFalse(File(root, renamedName).exists())
    }

}
