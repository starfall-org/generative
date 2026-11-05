package org.starfall.generative.features.tools.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.starfall.generative.LibraryTestApplication
import org.starfall.generative.shared.ui.theme.GenerativeTheme

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w400dp-h800dp-xhdpi", application = LibraryTestApplication::class)
class MediaVariableCompletionUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun selectingSuggestionCompletesVariableWithoutDuplicateBraces() {
        compose.setContent {
            var value by remember { mutableStateOf("") }
            GenerativeTheme(themeMode = "LIGHT", amoledMode = false, dynamicColor = false) {
                MediaTemplateAutocompleteField(
                    value = value,
                    onValueChange = { value = it },
                    stepNumber = 2,
                    modifier = Modifier.testTag("media-autocomplete-field")
                )
            }
        }
        compose.onNodeWithTag("media-autocomplete-field").performTextInput("{{mo")
        compose.onNodeWithTag("media-variable-option-model").performClick()
        compose.onNodeWithTag("media-autocomplete-field").assertTextEquals("{{model}}")
    }
}
