package org.starfall.generative

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.starfall.generative.features.storage.ui.StorageScreen
import org.starfall.generative.shared.subfeatures.media.logic.ToolFiles
import org.starfall.generative.shared.ui.theme.GenerativeTheme
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w360dp-h800dp-xhdpi", application = LibraryTestApplication::class)
class StorageListUiTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val temporary = TemporaryFolder()

    private fun openStorage() {
        compose.setContent {
            GenerativeTheme(darkTheme = false, amoledMode = false) {
                StorageScreen(ToolFiles(temporary.root), onBack = {}, onBrowseFiles = {})
            }
        }
    }

    private fun tile(file: File) = compose.onNodeWithTag("storage-preview:${file.canonicalPath}")

    @Test fun previewsHaveNoVisibleNamesAndListTilesAreWideWhileGridTilesAreSquare() {
        val nested = temporary.newFolder("nested")
        val photo = File(nested, "photo.png").apply { writeText("image") }
        openStorage()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("storage-preview:${photo.canonicalPath}")
            .fetchSemanticsNodes().isNotEmpty() }
        tile(photo).assertIsDisplayed()
        compose.onNodeWithText("photo.png").assertDoesNotExist()
        compose.onNodeWithText("nested").assertDoesNotExist()
        val listBounds = tile(photo).fetchSemanticsNode().boundsInRoot
        assertTrue("List preview should fill a wide rectangular tile", listBounds.width > listBounds.height * 1.8f)

        compose.onNodeWithContentDescription("Switch to Grid View").performClick()
        compose.waitForIdle()
        val gridBounds = tile(photo).fetchSemanticsNode().boundsInRoot
        assertTrue("Grid preview should be square", kotlin.math.abs(gridBounds.width - gridBounds.height) < 4f)
        compose.onNodeWithText("photo.png").assertDoesNotExist()
    }

    @Test fun multiSelectionTracksExactFilesNotDuplicateNames() {
        val first = File(temporary.root, "same.txt").apply { writeText("first") }
        val second = File(temporary.newFolder("nested"), "same.txt").apply { writeText("second") }
        openStorage()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("storage-preview:${first.canonicalPath}")
            .fetchSemanticsNodes().isNotEmpty() }
        tile(first).performTouchInput { longClick() }
        compose.onNodeWithText("1 Selected").assertIsDisplayed()
        tile(second).performClick()
        compose.onNodeWithText("2 Selected").assertIsDisplayed()
        compose.onNodeWithContentDescription("Clear selection").performClick()
        compose.onNodeWithText("Select").assertIsDisplayed()
        compose.onNodeWithText("same.txt").assertDoesNotExist()
    }

    @Test fun deletingSelectedNestedFileDoesNotDeleteSameNamedRootFile() {
        val root = File(temporary.root, "same.txt").apply { writeText("root") }
        val nested = File(temporary.newFolder("nested"), "same.txt").apply { writeText("nested") }
        openStorage()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("storage-preview:${nested.canonicalPath}")
            .fetchSemanticsNodes().isNotEmpty() }
        tile(nested).performTouchInput { longClick() }
        compose.onNodeWithText("1 Selected").assertIsDisplayed()
        compose.onNodeWithContentDescription("Delete").performClick()
        compose.onNodeWithText("Delete").performClick()
        compose.waitUntil(10_000) { !nested.exists() }
        assertFalse(nested.exists())
        assertTrue(root.exists())
    }

}
