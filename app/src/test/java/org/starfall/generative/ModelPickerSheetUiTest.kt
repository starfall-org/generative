package org.starfall.generative

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.starfall.generative.shared.model.api.ModelApiType
import org.starfall.generative.shared.model.provider.ModelProvider
import org.starfall.generative.shared.model.provider.ProviderConfiguration
import org.starfall.generative.shared.subfeatures.modelpicker.ui.ModelPickerSheet

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w400dp-h800dp-xhdpi", application = LibraryTestApplication::class)
class ModelPickerSheetUiTest {
    @get:Rule val compose = createComposeRule()
    private val provider = ModelProvider(
        "p", "Primary", ModelApiType.OPENAI, baseUrl = "",
        config = ProviderConfiguration(modelIds = listOf("alpha", "beta"))
    )

    @Test fun collapsedProviderExpandsAndAllowsSelectingModel() {
        var selection: Pair<String, String>? = null
        compose.setContent { MaterialTheme {
            ModelPickerSheet(
                providers = listOf(provider), collapsedProviderIdsState = setOf("p"),
                selectedProviderId = "", selectedModelId = "", conversationReasoningEffort = null,
                onSelectModel = { id, model -> selection = id to model },
                onSetReasoningEffort = {}, onDismiss = {}
            )
        } }
        compose.onNodeWithTag("model-picker-toolbar").assertIsDisplayed()
        compose.onNodeWithTag("model-picker-provider_p").assertIsDisplayed()
        compose.onNodeWithTag("model-picker-model_p_alpha").assertDoesNotExist()
        compose.onNodeWithTag("model-picker-provider_p").performClick()
        compose.onNodeWithTag("model-picker-model_p_alpha").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals("p" to "alpha", selection) }
    }

    @Test fun searchFiltersModelsAndClearingRestoresResults() {
        compose.setContent { MaterialTheme {
            ModelPickerSheet(
                providers = listOf(provider), selectedProviderId = "", selectedModelId = "",
                conversationReasoningEffort = null, onSelectModel = { _, _ -> },
                onSetReasoningEffort = {}, onDismiss = {}
            )
        } }
        compose.onNodeWithTag("model-picker-search-button").performClick()
        compose.onNodeWithTag("model-picker-search").performTextInput("alpha")
        compose.onNodeWithTag("model-picker-model_p_alpha").assertIsDisplayed()
        compose.onNodeWithTag("model-picker-model_p_beta").assertDoesNotExist()
        compose.onNodeWithTag("model-picker-search").performTextClearance()
        compose.onNodeWithTag("model-picker-model_p_beta").assertIsDisplayed()
    }
    @Test fun longPressModelOpensEditorWithoutSelectingIt() {
        var edited: Pair<String, String>? = null
        var selected: Pair<String, String>? = null
        var dismissals = 0
        compose.setContent { MaterialTheme {
            ModelPickerSheet(
                providers = listOf(provider), selectedProviderId = "", selectedModelId = "",
                conversationReasoningEffort = null,
                onSelectModel = { id, model -> selected = id to model },
                onEditModel = { id, model -> edited = id to model },
                onSetReasoningEffort = {}, onDismiss = { dismissals++ }
            )
        } }
        compose.onNodeWithTag("model-picker-model_p_alpha").assertIsDisplayed()
            .performTouchInput { longClick() }
        compose.runOnIdle {
            assertEquals("p" to "alpha", edited)
            assertEquals(null, selected)
            assertEquals(1, dismissals)
        }
    }

}
