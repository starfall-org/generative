package org.starfall.generative

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
import org.starfall.generative.features.providers.editor.ui.ModelTypeSelector
import org.starfall.generative.shared.model.language.ModelType
import org.starfall.generative.shared.ui.theme.GenerativeTheme

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "en-w400dp-h800dp-xhdpi", application = LibraryTestApplication::class)
class ModelTypeSelectorTest {
    @get:Rule val compose = createComposeRule()

    @Test fun modelTypeOpensDropdownAndSelectsImmediately() {
        var selected by mutableStateOf(ModelType.TEXT_GENERATION)
        var changes = 0
        compose.setContent {
            GenerativeTheme(themeMode = "LIGHT", amoledMode = false, dynamicColor = false) {
                ModelTypeSelector(selected) { selected = it; changes++ }
            }
        }
        compose.onNodeWithTag("model-type-selector").assertHasClickAction()
        compose.onNodeWithText("Model Type").assertIsDisplayed()
        compose.onNodeWithTag("model-type-selector").assertTextContains("Text Generation")
        compose.onNodeWithTag("model-type-selector").performClick()
        compose.onNodeWithText("Video Generation").performClick()
        compose.onNodeWithTag("model-type-selector").assertTextContains("Video Generation")
        compose.runOnIdle {
            assertEquals(ModelType.VIDEO_GENERATION, selected)
            assertEquals(1, changes)
        }
    }
}
