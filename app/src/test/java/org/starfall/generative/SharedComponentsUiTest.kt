package org.starfall.generative

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.starfall.generative.shared.ui.inputs.ExpandableSearchField
import org.starfall.generative.shared.ui.inputs.KeyValueField
import org.starfall.generative.shared.ui.inputs.KeyValueSuggestion

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = LibraryTestApplication::class)
class SharedComponentsUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun expandableSearchRetainsControlledTextAcrossCollapse() {
        var expanded by mutableStateOf(false)
        var query by mutableStateOf("")
        compose.setContent {
            MaterialTheme {
                ExpandableSearchField(
                    value = query,
                    onValueChange = { query = it },
                    expanded = expanded,
                    onExpandedChange = { expanded = it },
                    placeholder = "Search",
                    testTag = "shared-search"
                )
            }
        }
        compose.onNodeWithTag("shared-search-button").performClick()
        compose.onNodeWithTag("shared-search").performTextInput("alpha")
        compose.runOnIdle { expanded = false }
        compose.runOnIdle {
            check(query == "alpha")
            check(!expanded)
        }
    }

    @Test fun keyValueFieldShowsEditableKeySuggestions() {
        var key by mutableStateOf("")
        var value by mutableStateOf("")
        compose.setContent {
            MaterialTheme {
                KeyValueField(
                    keyValue = key,
                    onKeyChange = { key = it },
                    value = value,
                    onValueChange = { value = it },
                    keySuggestions = listOf(KeyValueSuggestion("Authorization")),
                    testTag = "header"
                )
            }
        }
        compose.onNodeWithTag("header-key").performClick()
        compose.onNodeWithTag("header-key").performTextInput("Auth")
        compose.onNodeWithText("Authorization").assertIsDisplayed().performClick()
        compose.runOnIdle { check(key == "Authorization") }
    }
}
