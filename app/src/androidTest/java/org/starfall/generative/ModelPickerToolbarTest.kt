package org.starfall.generative

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.starfall.generative.shared.model.language.ModelConfiguration
import org.starfall.generative.shared.subfeatures.modelpicker.ui.ModelPickerToolbar

class ModelPickerToolbarTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun searchExpandsAndCollapsesOnFocusLossWithoutLosingTheQuery() {
        var query by mutableStateOf("")
        var opened = 0
        lateinit var focusManager: FocusManager
        val modelName = "A long selected model name that must stay on one line"
        compose.setContent {
            focusManager = LocalFocusManager.current
            MaterialTheme {
                ModelPickerToolbar(
                    query = query,
                    onQueryChange = { query = it },
                    toggleEnabled = true,
                    allCollapsed = false,
                    onToggleAllCollapsed = {},
                    selectedModelId = "model-id",
                    selectedModel = ModelConfiguration(displayName = modelName),
                    onOpenSelectedModel = { opened++ }
                )
            }
        }

        compose.onNodeWithText(modelName, useUnmergedTree = true).assertIsDisplayed()
        val toolbar = compose.onNodeWithTag("model-picker-toolbar")
        val collapsedHeight = toolbar.getUnclippedBoundsInRoot().let { (it.bottom - it.top).value }
        compose.onNodeWithTag("model-picker-search-button").performClick()
        compose.onNodeWithTag("model-picker-search").assertIsFocused().performTextInput("hello")
        compose.onNodeWithText(modelName, useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("selected-model-icon", useUnmergedTree = true).assertIsDisplayed()
        // Focus changes must not alter the toolbar's measured height while the IME resizes.
        assertEquals(collapsedHeight, toolbar.getUnclippedBoundsInRoot().let { (it.bottom - it.top).value }, 1f)

        compose.runOnIdle { focusManager.clearFocus() }
        compose.onNodeWithTag("model-picker-search-button").assertIsDisplayed()
        assertEquals(collapsedHeight, toolbar.getUnclippedBoundsInRoot().let { (it.bottom - it.top).value }, 1f)
        compose.onNodeWithText(modelName, useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("model-picker-search-button").performClick()
        compose.onNodeWithTag("model-picker-search").assertIsFocused()
        compose.runOnIdle { assertEquals("hello", query) }
        compose.onNodeWithTag("open-selected-model-button").performClick()
        compose.runOnIdle { assertEquals(1, opened) }
    }
}
