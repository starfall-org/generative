package org.starfall.generative

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.starfall.generative.shared.ui.inputs.SelectionDropdown

class SelectionDropdownTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun selectingOptionUpdatesControlledValueOnce() {
        var selected by mutableStateOf(1)
        var changes = 0
        compose.setContent {
            MaterialTheme {
                SelectionDropdown(
                    label = "Mode",
                    options = listOf(1 to "First", 2 to "Second"),
                    selected = selected,
                    onSelect = { selected = it; changes++ },
                    fieldModifier = Modifier.testTag("mode-selection")
                )
            }
        }

        compose.onNodeWithTag("mode-selection").assertIsDisplayed().performClick()
        compose.onNodeWithText("Second").performClick()
        compose.onNodeWithTag("mode-selection").assertTextContains("Second")
        compose.runOnIdle {
            assertEquals(2, selected)
            assertEquals(1, changes)
        }
    }

    @Test
    fun disabledFieldCannotOpenMenuAndKeepsCustomDisplayValue() {
        compose.setContent {
            MaterialTheme {
                SelectionDropdown(
                    label = "Profile",
                    options = listOf("default" to "Default · Standard", "custom" to "Custom · Custom"),
                    selected = "default",
                    onSelect = { error("Disabled dropdown must not invoke onSelect") },
                    enabled = false,
                    displayValue = "Default",
                    fieldModifier = Modifier.testTag("profile-selection")
                )
            }
        }

        compose.onNodeWithTag("profile-selection").assertIsNotEnabled().assertTextContains("Default")
        compose.onNodeWithText("Custom · Custom").assertDoesNotExist()
    }
}
