package org.starfall.generative

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.starfall.generative.shared.ui.screens.ListScreenTemplate

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w400dp-h800dp-xhdpi", application = LibraryTestApplication::class)
class ProviderSearchBarTest {
    @get:Rule val compose = createComposeRule()

    @Test fun searchExpandsOnTapAndCollapsesOnUnfocusWithoutLosingQuery() {
        var searching by mutableStateOf(false)
        var query by mutableStateOf("")
        lateinit var focus: FocusManager
        compose.setContent {
            val manager = LocalFocusManager.current
            SideEffect { focus = manager }
            MaterialTheme {
                ListScreenTemplate(
                    title = "Providers", onBack = {},
                    searchQuery = query,
                    onSearchQueryChange = { query = it },
                    searchExpanded = searching,
                    onSearchExpandedChange = { searching = it },
                    searchTag = "provider_search",
                    searchButtonTag = "provider_search_expand",
                    searchClearButtonTag = "provider_search_clear"
                ) { _ -> }
            }
        }
        compose.onNodeWithTag("provider_search").assertDoesNotExist()
        compose.onNodeWithTag("provider_search_expand").performClick()
        compose.onNodeWithTag("provider_search").performTextInput("Alpha")
        compose.runOnIdle { focus.clearFocus(force = true) }
        compose.onNodeWithTag("provider_search").assertDoesNotExist()
        compose.onNodeWithTag("provider_search_expand").performClick()
        compose.onNodeWithTag("provider_search").assertTextContains("Alpha")
        compose.onNodeWithTag("provider_search_clear").performClick()
        compose.runOnIdle { assertEquals("", query) }
    }
}
