package org.starfall.generative.features.storage.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.compose.ui.graphics.toPixelMap
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
import org.starfall.generative.features.storage.logic.listMediaStorageFiles
import org.starfall.generative.shared.subfeatures.media.logic.ToolFiles
import org.starfall.generative.shared.ui.theme.GenerativeTheme

/** Device UI regression tests using disposable app-private files; never touches the user's MediaStorage. */
@RunWith(AndroidJUnit4::class)
class MediaStorageSurfacesDeviceTest {
    @get:Rule val compose = createComposeRule()
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val root by lazy { File(context.cacheDir, "media-storage-device-${UUID.randomUUID()}") }
    private val store by lazy { ToolFiles(root) }
    private val primary by lazy { File(root, "image.png") }
    private val nested by lazy { File(root, "album/2026/image.png") }
    private val audio by lazy { File(root, "album/2026/audio.mp3") }
    private val video by lazy { File(root, "album/clip.mp4") }
    private val ignored by lazy { File(root, "album/2026/notes.txt") }

    @Before fun prepareFiles() {
        createPng(primary, AndroidColor.RED)
        createPng(nested, AndroidColor.GREEN)
        audio.apply { parentFile!!.mkdirs(); writeText("audio") }
        video.apply { parentFile!!.mkdirs(); writeText("video") }
        ignored.writeText("text")
        File(root, "album/2026/unready.part").writeText("partial")
    }

    @After fun removeFiles() { root.deleteRecursively() }

    private fun createPng(target: File, color: Int) {
        target.parentFile!!.mkdirs()
        val bitmap = Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(color)
        target.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
    }

    private fun openScreen() {
        compose.setContent { GenerativeTheme(darkTheme = false, amoledMode = false) {
            StorageScreen(store, onBack = {}, onBrowseFiles = {})
        } }
        waitForTile(primary)
    }

    private fun openSheet() {
        val initializedStore = store
        assertTrue(listMediaStorageFiles(initializedStore).any { it == audio.canonicalFile })
        compose.setContent { GenerativeTheme(darkTheme = false, amoledMode = false) {
            var visible by remember { mutableStateOf(false) }
            Button(onClick = { visible = true }, modifier = Modifier.testTag("open-media-sheet")) {
                Text("Open media sheet")
            }
            if (visible) MediaStorageSheet(initializedStore, onDismiss = { visible = false })
        } }
        compose.onNodeWithTag("open-media-sheet").performClick()
        compose.waitUntil(15_000) {
            compose.onAllNodesWithTag("media-storage-file_audio.mp3").fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun screenTile(file: File) = compose.onNodeWithTag("storage-preview:${file.canonicalPath}")
    private fun scrollTo(file: File) {
        compose.onNodeWithTag("storage-preview-list")
            .performScrollToNode(hasTestTag("storage-preview:${file.canonicalPath}"))
    }

    private fun waitForTile(file: File) {
        compose.waitUntil(12_000) {
            compose.onAllNodesWithTag("storage-preview:${file.canonicalPath}").fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test fun storageScreenShowsNestedFilesAsPreviewOnlyAndCropsImagesIntoWideListTiles() {
        openScreen()
        screenTile(primary).assertIsDisplayed()
        waitForTile(nested)
        scrollTo(nested)
        screenTile(nested).assertExists()
        scrollTo(audio)
        screenTile(audio).assertExists()
        scrollTo(video)
        screenTile(video).assertExists()
        scrollTo(ignored)
        screenTile(ignored).assertExists()
        scrollTo(primary)
        compose.onNodeWithText("image.png").assertDoesNotExist()
        compose.onNodeWithText("album").assertDoesNotExist()
        compose.onNodeWithText("2026").assertDoesNotExist()
        compose.onNodeWithText(nested.parentFile!!.absolutePath).assertDoesNotExist()

        val bounds = screenTile(primary).fetchSemanticsNode().boundsInRoot
        assertTrue("List tiles should be landscape rectangles", bounds.width > bounds.height * 1.8f)
        // The full-bleed image should be visible, not just a file icon or a 42dp thumbnail.
        compose.waitUntil(12_000) {
            val pixels = screenTile(primary).captureToImage().toPixelMap()
            val centre = pixels[pixels.width / 2, pixels.height / 2]
            centre.red > 0.75f && centre.green < 0.25f
        }
    }

    @Test fun gridToggleProducesSquarePreviewsAndPreservesNoNameLayout() {
        openScreen()
        compose.onNodeWithContentDescription(context.getString(R.string.switch_to_grid_view)).performClick()
        compose.waitUntil(12_000) {
            val bounds = screenTile(primary).fetchSemanticsNode().boundsInRoot
            kotlin.math.abs(bounds.width - bounds.height) < 5f
        }
        scrollTo(nested)
        screenTile(nested).assertExists()
        compose.onNodeWithText("image.png").assertDoesNotExist()
    }

    @Test fun selectingAndDeletingNestedDuplicateDoesNotDeleteRootFile() {
        openScreen()
        scrollTo(nested)
        screenTile(nested).performTouchInput { longClick() }
        compose.onNodeWithText("1 ${context.getString(R.string.common_selected)}").assertIsDisplayed()
        compose.onNodeWithContentDescription(context.getString(R.string.common_delete)).performClick()
        compose.onNodeWithText(context.getString(R.string.common_delete)).performClick()
        compose.waitUntil(12_000) { !nested.exists() }
        assertFalse(nested.exists())
        assertTrue("Same-named root photo must be preserved", primary.exists())
        screenTile(primary).assertExists()
    }

    @Test fun sheetFlattensNestedMediaButDoesNotRenderFolderNamesOrTextFiles() {
        openSheet()
        compose.onAllNodesWithTag("media-storage-file_image.png").assertCountEquals(2)
        compose.onNodeWithTag("media-storage-file_audio.mp3").assertExists()
        compose.onNodeWithTag("media-storage-file_clip.mp4").assertExists()
        compose.onNodeWithTag("media-storage-file_notes.txt").assertDoesNotExist()
        compose.onNodeWithText("album").assertDoesNotExist()
        compose.onNodeWithText("2026").assertDoesNotExist()
        compose.onNodeWithText(nested.parentFile!!.absolutePath).assertDoesNotExist()
        assertTrue(nested.exists())
        // Also verify normal open/close through the shared viewer on the same mounted sheet.
        compose.onNodeWithTag("media-storage-file_audio.mp3").performClick()
        compose.onNodeWithContentDescription(context.getString(R.string.media_close_viewer)).assertIsDisplayed()
        compose.onNodeWithContentDescription(context.getString(R.string.media_close_viewer)).performClick()
        compose.onNodeWithTag("media-storage-file_audio.mp3").assertExists()
        assertTrue(audio.exists())
    }

    @Test fun viewerDeletionFromScreenTargetsExactNestedFile() {
        openScreen()
        scrollTo(nested)
        screenTile(nested).performClick()
        compose.onNodeWithContentDescription(context.getString(R.string.media_close_viewer)).assertIsDisplayed()
        compose.onNodeWithContentDescription(context.getString(R.string.common_more_options)).performClick()
        compose.onNodeWithText(context.getString(R.string.common_delete)).performClick()
        compose.onNodeWithText(context.getString(R.string.common_delete)).performClick()
        compose.waitUntil(12_000) { !nested.exists() }
        assertTrue(primary.exists())
        assertFalse(nested.exists())
    }

    @Test fun deletingNestedMediaFromSheetPreservesSameNamedRootMedia() {
        val rootAudio = File(root, "audio.mp3").apply { writeText("root audio") }
        rootAudio.setLastModified(System.currentTimeMillis() - 60_000L)
        audio.setLastModified(System.currentTimeMillis() + 60_000L)
        openSheet()
        compose.onAllNodesWithTag("media-storage-file_audio.mp3").assertCountEquals(2)
        // Sorted newest first: choose the nested file, not the same-named root file.
        compose.onAllNodesWithTag("media-storage-file_audio.mp3")[0].performClick()
        compose.onNodeWithContentDescription(context.getString(R.string.media_close_viewer)).assertIsDisplayed()
        compose.onNodeWithContentDescription(context.getString(R.string.common_more_options)).performClick()
        compose.onNodeWithText(context.getString(R.string.common_delete)).performClick()
        compose.onNodeWithText(context.getString(R.string.common_delete)).performClick()
        compose.waitUntil(12_000) { !audio.exists() }
        assertTrue("Root media with the same filename must survive", rootAudio.exists())
        assertFalse(audio.exists())
    }

}
