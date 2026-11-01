package org.starfall.generative

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.starfall.generative.shared.ui.dialogs.RadioSelectionDialog
import org.starfall.generative.shared.ui.dialogs.RadioSelectionOption

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w360dp-h800dp-xhdpi", application = LibraryTestApplication::class)
class RadioSelectionDialogTest {
    @get:Rule val compose = createComposeRule()

    @Test fun aChoiceIsAppliedOnceAndDismissesWithCloseFooter() {
        var open by mutableStateOf(true)
        var selected by mutableStateOf("first")
        var selectionCount = 0
        var dismissCount = 0
        compose.setContent {
            MaterialTheme {
                if (open) RadioSelectionDialog(
                    title = "Choose type",
                    selected = selected,
                    options = listOf(
                        RadioSelectionOption("first", "First", "Initial option", "radio-first"),
                        RadioSelectionOption("second", "Second", "Other option", "radio-second")
                    ),
                    onDismissRequest = { open = false; dismissCount++ },
                    onSelected = { selected = it; selectionCount++ }
                )
            }
        }
        compose.onNodeWithTag("app-dialog-buttons").assertExists()
        compose.onNodeWithTag("radio-selection-close").assertHasClickAction()
        compose.onNodeWithTag("radio-first").assertIsSelected()
        compose.onNodeWithText("Other option").assertExists()
        compose.onNodeWithTag("radio-second").performClick()
        compose.onNodeWithTag("app-dialog").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals("second", selected)
            assertEquals(1, selectionCount)
            assertEquals(1, dismissCount)
        }
    }

    @Test fun closeFooterDismissesWithoutChangingSelection() {
        var open by mutableStateOf(true)
        var selected by mutableStateOf("first")
        var dismissCount = 0
        var selectionCount = 0
        compose.setContent {
            MaterialTheme {
                if (open) RadioSelectionDialog(
                    title = "Choose type",
                    selected = selected,
                    options = listOf(RadioSelectionOption("first", "First"), RadioSelectionOption("second", "Second")),
                    onDismissRequest = { open = false; dismissCount++ },
                    onSelected = { selected = it; selectionCount++ }
                )
            }
        }
        compose.onNodeWithTag("radio-selection-close").assertHasClickAction().performClick()
        compose.onNodeWithTag("app-dialog").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals("first", selected)
            assertEquals(0, selectionCount)
            assertEquals(1, dismissCount)
        }
    }

    @Test fun manyChoicesRemainScrollableWithinTheSameDialog() {
        var selected = -1
        compose.setContent {
            MaterialTheme {
                RadioSelectionDialog(
                    title = "Many options",
                    selected = 0,
                    options = (0..35).map { i ->
                        RadioSelectionOption(i, "Choice $i", testTag = "radio-$i")
                    },
                    onDismissRequest = {},
                    onSelected = { selected = it }
                )
            }
        }
        compose.onNodeWithTag("radio-35").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(35, selected) }
        compose.onNodeWithTag("app-dialog-buttons").assertExists()
    }
}
