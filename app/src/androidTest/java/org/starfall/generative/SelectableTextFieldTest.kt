package org.starfall.generative

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Rule
import org.junit.Test
import org.starfall.generative.shared.ui.inputs.OutlinedTextField

class SelectableTextFieldTest {
    @get:Rule val compose = createComposeRule()

    @Test fun thirdClickSelectsAllText() {
        var value by mutableStateOf("replace me")
        compose.setContent {
            MaterialTheme {
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    modifier = Modifier.testTag("field")
                )
            }
        }

        compose.onNodeWithTag("field").performTouchInput {
            click(center)
            advanceEventTime(100)
            click(center)
            advanceEventTime(100)
            click(center)
        }
        compose.onNodeWithTag("field").performTextInput("new")

        compose.onNodeWithTag("field").assertTextEquals("new")
    }

    @Test fun thirdClickSelectsAllCallerManagedText() {
        var value by mutableStateOf(TextFieldValue("replace me"))
        compose.setContent {
            MaterialTheme {
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    modifier = Modifier.testTag("field")
                )
            }
        }

        compose.onNodeWithTag("field").performTouchInput {
            click(center)
            advanceEventTime(100)
            click(center)
            advanceEventTime(100)
            click(center)
        }
        compose.onNodeWithTag("field").performTextInput("new")

        compose.onNodeWithTag("field").assertTextEquals("new")
    }
}
