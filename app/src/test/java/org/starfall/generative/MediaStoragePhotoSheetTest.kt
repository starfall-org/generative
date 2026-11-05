package org.starfall.generative

import android.content.Context
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.starfall.generative.features.customization.ui.MediaStoragePhotoSheet
import org.starfall.generative.shared.subfeatures.media.logic.ToolFiles
import org.starfall.generative.shared.ui.theme.GenerativeTheme

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w360dp-h800dp-xhdpi", application = LibraryTestApplication::class)
class MediaStoragePhotoSheetTest {
    @get:Rule val compose = createComposeRule()

    @Test fun sheetShowsAllImagesWithoutSearchAndKeepsSelection() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val directory = ToolFiles.appStorageDirectory(context)
        val prefix = "photo-sheet-${UUID.randomUUID()}"
        val photoA = File(directory, "$prefix-a.png")
        val photoB = File(directory, "$prefix-b.jpg")
        val audio = File(directory, "$prefix-audio.mp3")
        var selected: File? = null
        try {
            photoA.writeBytes(byteArrayOf(1))
            photoB.writeBytes(byteArrayOf(1))
            audio.writeBytes(byteArrayOf(1))
            compose.setContent {
                GenerativeTheme(darkTheme = false, amoledMode = false) {
                    MediaStoragePhotoSheet(onDismiss = {}, onSelected = { selected = it })
                }
            }
            compose.waitUntil(10_000) {
                compose.onAllNodesWithContentDescription(photoA.name).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithContentDescription(photoA.name).assertExists()
            compose.onNodeWithContentDescription(photoB.name).assertExists()
            compose.onNodeWithContentDescription(audio.name).assertDoesNotExist()
            assertTrue("Sheet must have no search input", compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isEmpty())
            compose.onNodeWithContentDescription(photoB.name).performClick()
            compose.runOnIdle { assertEquals(photoB, selected) }
        } finally {
            photoA.delete()
            photoB.delete()
            audio.delete()
        }
    }
}
