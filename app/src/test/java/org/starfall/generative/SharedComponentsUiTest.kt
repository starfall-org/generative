package org.starfall.generative

import org.starfall.generative.shared.ui.inputs.InputFocusHost
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.semantics.SemanticsProperties
import org.junit.Assert.assertEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
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
            MaterialTheme { InputFocusHost {
                ExpandableSearchField(
                    value = query,
                    onValueChange = { query = it },
                    expanded = expanded,
                    onExpandedChange = { expanded = it },
                    onSearch = {},
                    placeholder = "Search",
                    testTag = "shared-search"
                )
            } }
        }
        compose.onNodeWithTag("shared-search-button").performClick()
        compose.onNodeWithTag("shared-search").performTextInput("alpha")
        compose.onNodeWithTag("shared-search").performTextInputSelection(TextRange(2))
        compose.onNodeWithTag("shared-search").performImeAction()
        compose.runOnIdle {
            check(query == "alpha")
            check(!expanded)
        }
        compose.onNodeWithTag("shared-search-button").performClick()
        compose.onNodeWithTag("shared-search").assertTextContains("alpha")
        assertEquals(TextRange(2), compose.onNodeWithTag("shared-search")
            .fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange])
        compose.onNodeWithTag("shared-search").performTextInput("X")
        compose.runOnIdle { assertEquals("alXpha", query) }
    }

    @Test fun expandableSearchWithExistingQueryStartsAtEndAndClearsNormally() {
        var expanded by mutableStateOf(false)
        var query by mutableStateOf("alpha")
        compose.setContent {
            MaterialTheme { InputFocusHost {
                ExpandableSearchField(query, { query = it }, expanded, { expanded = it },
                    placeholder = "Search", testTag = "prefilled-search")
            } }
        }
        compose.onNodeWithTag("prefilled-search-button").performClick()
        compose.onNodeWithTag("prefilled-search").performTextInput("X")
        compose.runOnIdle { assertEquals("alphaX", query) }
        compose.runOnIdle { query = "" }
        compose.onNodeWithTag("prefilled-search").performTextInput("new")
        compose.runOnIdle { assertEquals("new", query) }
    }

    @Test fun keyValueFieldShowsEditableKeySuggestions() {
        var key by mutableStateOf("")
        var value by mutableStateOf("")
        compose.setContent {
            MaterialTheme { InputFocusHost {
                KeyValueField(
                    keyValue = key,
                    onKeyChange = { key = it },
                    value = value,
                    onValueChange = { value = it },
                    keySuggestions = listOf(KeyValueSuggestion("Authorization")),
                    testTag = "header"
                )
            } }
        }
        compose.onNodeWithTag("header-key").performClick()
        compose.onNodeWithTag("header-key").performTextInput("Auth")
        compose.onNodeWithText("Authorization").assertIsDisplayed().performClick()
        compose.runOnIdle { check(key == "Authorization") }
    }
}
