package org.starfall.generative

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.text.TextRange
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.starfall.generative.features.httptools.ui.HttpRequestTemplateField

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w420dp-h1000dp-xhdpi", application = LibraryTestApplication::class)
class HttpParameterCompletionUiTest {
    @get:Rule val compose = createComposeRule()

    private fun showField(json: Boolean = true, parameters: List<String> = listOf("city", "city_code"), insertable: Boolean = true) {
        compose.setContent {
            var value by remember { mutableStateOf("") }
            MaterialTheme {
                HttpRequestTemplateField("Body", value, { value = it }, parameters, "completion",
                    json = json, insertable = insertable, allowEmpty = true)
            }
        }
    }

    @Test fun tapCompletesParameterInsideJsonString() {
        showField()
        compose.onNodeWithTag("completion").performTextInput("""{"q":"{{ci"}""")
        compose.onNodeWithTag("completion").performTextInputSelection(TextRange(10))
        compose.onNodeWithTag("completion_parameter_city").performClick()
        compose.onNodeWithTag("completion").assertTextEquals("""{"q":"{{city}}"}""")
        compose.onNodeWithTag("completion_parameter_city").assertDoesNotExist()
    }

    @Test fun keyboardSelectsWithArrowsAndTabAndEscapeDismisses() {
        showField(json = false)
        compose.onNodeWithTag("completion").performTextInput("{{ci")
        compose.onNodeWithTag("completion").performKeyInput { pressKey(Key.DirectionDown); pressKey(Key.Tab) }
        compose.onNodeWithTag("completion").assertTextEquals("{{city_code}}")
        compose.onNodeWithTag("completion").performTextReplacement("{{ci")
        compose.onNodeWithTag("completion_parameter_city").assertExists()
        compose.onNodeWithTag("completion").performKeyInput { pressKey(Key.Escape) }
        compose.onNodeWithTag("completion_parameter_city").assertDoesNotExist()
        compose.onNodeWithTag("completion").assertTextEquals("{{ci")
    }

    @Test fun sampleFieldDoesNotOfferParameterCompletion() {
        showField(insertable = false)
        compose.onNodeWithTag("completion").performTextInput("{{ci")
        compose.onNodeWithTag("completion_parameter_city").assertDoesNotExist()
    }

    @Test fun arrowUpAndEnterAcceptHighlightedParameter() {
        showField(json = false)
        compose.onNodeWithTag("completion").performTextInput("{{ci")
        compose.onNodeWithTag("completion").performKeyInput {
            pressKey(Key.DirectionDown)
            pressKey(Key.DirectionUp)
            pressKey(Key.Enter)
        }
        compose.onNodeWithTag("completion").assertTextEquals("{{city}}")
    }
}
