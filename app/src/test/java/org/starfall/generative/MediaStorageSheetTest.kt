package org.starfall.generative

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.starfall.generative.features.storage.ui.MediaStorageSheet
import org.starfall.generative.shared.subfeatures.media.logic.ToolFiles
import org.starfall.generative.shared.ui.theme.GenerativeTheme

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w360dp-h800dp-xhdpi", application = LibraryTestApplication::class)
class MediaStorageSheetTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun drawerMediaSheetShowsMediaGridWithoutHeaderOrSearch() {
        temporary.newFile("portrait.png")
        temporary.newFile("clip.mp4")
        temporary.newFile("song.mp3")
        temporary.newFile("notes.txt")
        val nested = temporary.newFolder("subfolder")
        java.io.File(nested, "nested-photo.png").writeText("image")
        java.io.File(nested, "nested-audio.mp3").writeText("audio")

        compose.setContent {
            GenerativeTheme(darkTheme = false, amoledMode = false) {
                MediaStorageSheet(ToolFiles(temporary.root), onDismiss = {})
            }
        }

        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("media-storage-file_clip.mp4")
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("media-storage-grid").assertExists()
        compose.onNodeWithTag("media-storage-file_portrait.png").assertExists()
        compose.onNodeWithTag("media-storage-file_song.mp3").assertExists()
        compose.onNodeWithTag("media-storage-file_notes.txt").assertDoesNotExist()
        compose.onNodeWithTag("media-storage-file_nested-photo.png").assertExists()
        compose.onNodeWithTag("media-storage-file_nested-audio.mp3").assertExists()
        compose.onNodeWithText("subfolder").assertDoesNotExist()

        compose.onNodeWithTag("media-storage-search").assertDoesNotExist()
        compose.onNodeWithText("Media").assertDoesNotExist()
        compose.onNodeWithContentDescription("Close").assertDoesNotExist()
        compose.onNodeWithTag("media-storage-file_clip.mp4").assertExists()
        compose.onNodeWithTag("media-storage-file_portrait.png").assertExists()
        compose.onNodeWithTag("media-storage-file_song.mp3").assertExists()
    }
}
