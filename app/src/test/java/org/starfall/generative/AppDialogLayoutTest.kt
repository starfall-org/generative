package org.starfall.generative

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.starfall.generative.shared.ui.theme.GenerativeTheme
import org.starfall.generative.shared.ui.dialogs.AlertDialog
import org.starfall.generative.shared.ui.dialogs.Dialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w360dp-h640dp-xhdpi", application = LibraryTestApplication::class)
class AppDialogLayoutTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun alertScrollsLongContentWhileTitleAndButtonsStayVisible() {
        var confirmed = false
        compose.setContent {
            GenerativeTheme(darkTheme = false, amoledMode = false) {
                AlertDialog(
                    onDismissRequest = {},
                    title = { Text("Confirm change") },
                    text = { Column { repeat(40) { Text("Paragraph $it") } } },
                    dismissButton = { TextButton(onClick = {}) { Text("Cancel") } },
                    confirmButton = { Button(onClick = { confirmed = true }) { Text("Confirm") } }
                )
            }
        }

        assertSlotOrder()
        compose.onNodeWithText("Paragraph 39").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Confirm change").assertIsDisplayed()
        compose.onNodeWithText("Confirm").assertIsDisplayed().performClick()
        compose.runOnIdle { assertTrue(confirmed) }
    }

    @Test
    fun customDialogKeepsActionsVisibleWhenItsLazyListScrolls() {
        var saved = false
        compose.setContent {
            GenerativeTheme(darkTheme = false, amoledMode = false) {
                Dialog(
                    onDismissRequest = {},
                    title = { Text("Choose an item") },
                    content = {
                        LazyColumn(Modifier.testTag("dialog-list")) {
                            items(40) { Text("Item $it") }
                        }
                    },
                    buttons = {
                        TextButton(onClick = {}) { Text("Cancel") }
                        Button(onClick = { saved = true }) { Text("Save") }
                    }
                )
            }
        }

        assertSlotOrder()
        val buttonTop = compose.onNodeWithTag("app-dialog-buttons").fetchSemanticsNode().boundsInRoot.top
        compose.onNodeWithTag("dialog-list").performScrollToIndex(39)
        compose.onNodeWithText("Item 39").assertIsDisplayed()
        compose.onNodeWithText("Choose an item").assertIsDisplayed()
        assertEquals(buttonTop, compose.onNodeWithTag("app-dialog-buttons").fetchSemanticsNode().boundsInRoot.top, 1f)
        compose.onNodeWithText("Save").performClick()
        compose.runOnIdle { assertTrue(saved) }
    }

    private fun assertSlotOrder() {
        val title = compose.onNodeWithTag("app-dialog-title").fetchSemanticsNode().boundsInRoot
        val content = compose.onNodeWithTag("app-dialog-content").fetchSemanticsNode().boundsInRoot
        val buttons = compose.onNodeWithTag("app-dialog-buttons").fetchSemanticsNode().boundsInRoot
        assertTrue("Content must follow the title", content.top >= title.bottom)
        assertTrue("Actions must follow the content", buttons.top >= content.bottom)
    }
}
