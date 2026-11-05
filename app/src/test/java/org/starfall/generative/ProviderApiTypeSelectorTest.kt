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
import org.starfall.generative.features.providers.editor.ui.ProviderApiTypeSelector
import org.starfall.generative.shared.model.api.ModelApiType
import org.starfall.generative.shared.theme.GenerativeTheme

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "en-w360dp-h800dp-xhdpi", application = LibraryTestApplication::class)
class ProviderApiTypeSelectorTest {
    @get:Rule val compose = createComposeRule()

    @Test fun apiTypeIsASelectionDropdownAndUpdatesImmediately() {
        var selected by mutableStateOf(ModelApiType.OPENAI_RESPONSES)
        val selections = mutableListOf<ModelApiType>()
        compose.setContent {
            GenerativeTheme(themeMode = "LIGHT", amoledMode = false, dynamicColor = false) {
                ProviderApiTypeSelector(selected) {
                    selected = it
                    selections += it
                }
            }
        }

        compose.onNodeWithText("API Type").assertIsDisplayed()
        compose.onNodeWithTag("provider-type-selector").assertTextContains("OpenAI Responses")
        compose.onNodeWithTag("provider-type-selector").assertHasClickAction().performClick()
        compose.onNodeWithText("Anthropic Messages").performClick()
        compose.onNodeWithTag("provider-type-selector").assertTextContains("Anthropic Messages")
        compose.runOnIdle { assertEquals(listOf(ModelApiType.ANTHROPIC), selections) }
    }

    @Test fun choosingCurrentApiTypeClosesDropdownWithoutChangingTheType() {
        var selected by mutableStateOf(ModelApiType.OPENAI)
        compose.setContent {
            GenerativeTheme(themeMode = "LIGHT", amoledMode = false, dynamicColor = false) {
                ProviderApiTypeSelector(selected) { selected = it }
            }
        }
        compose.onNodeWithTag("provider-type-selector").performClick()
        compose.onAllNodesWithText(ModelApiType.OPENAI.displayName).onLast().performClick()
        compose.onNodeWithTag("app-dialog").assertDoesNotExist()
        compose.runOnIdle { assertEquals(ModelApiType.OPENAI, selected) }
    }
}
