package org.starfall.generative

import androidx.compose.material3.MaterialTheme
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
import org.starfall.generative.features.providers.editor.ui.ModelCatalogSheet
import org.starfall.generative.shared.model.api.ModelApiType
import org.starfall.generative.shared.model.language.DiscoveredModel
import org.starfall.generative.shared.model.provider.ModelProvider

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w400dp-h800dp-xhdpi", application = LibraryTestApplication::class)
class ModelCatalogSheetUiTest {
    @get:Rule val compose = createComposeRule()
    private val provider = ModelProvider("p", "Primary", ModelApiType.OPENAI, baseUrl = "")
    private val models = listOf(
        DiscoveredModel("alpha", displayName = "Alpha model"),
        DiscoveredModel("beta", displayName = "Beta model")
    )

    @Test fun fetchSearchAndSelectVisibleModels() {
        var selected by mutableStateOf(emptySet<String>())
        var loaded = emptyList<DiscoveredModel>()
        compose.setContent { MaterialTheme {
            ModelCatalogSheet(
                provider = provider, selectedModels = selected,
                onFetchModels = { models }, onCatalogLoaded = { loaded = it },
                onToggle = { id -> selected = if (id in selected) selected - id else selected + id },
                onSetSelection = { ids, add -> selected = if (add) selected + ids else selected - ids.toSet() },
                onDismiss = {}
            )
        } }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Alpha model").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("provider-model-catalog-toolbar").assertIsDisplayed()
        compose.onNodeWithTag("provider_model_catalog_search_button").performClick()
        compose.onNodeWithTag("provider_model_catalog_search").performTextInput("alpha")
        compose.onNodeWithText("Alpha model").assertIsDisplayed()
        compose.onNodeWithText("Beta model").assertDoesNotExist()
        compose.onNodeWithTag("provider-model-catalog-select-all").performClick()
        compose.runOnIdle {
            assertEquals(setOf("alpha"), selected)
            assertEquals(models, loaded)
        }
    }

    @Test fun failedFetchDisplaysRetryAndCanRecover() {
        var fail by mutableStateOf(true)
        compose.setContent { MaterialTheme {
            ModelCatalogSheet(
                provider = provider, selectedModels = emptySet(),
                onFetchModels = { if (fail) throw IllegalStateException("Catalog unavailable") else models },
                onCatalogLoaded = {}, onToggle = {}, onSetSelection = { _, _ -> }, onDismiss = {}
            )
        } }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("provider-model-catalog-error").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Catalog unavailable").assertIsDisplayed()
        compose.onNodeWithText("Unable to load models").assertDoesNotExist()
        compose.runOnIdle { fail = false }
        compose.onNodeWithText("Retry").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Alpha model").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("provider-model-catalog-error").assertDoesNotExist()
    }
}
