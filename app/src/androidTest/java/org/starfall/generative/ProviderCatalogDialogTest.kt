package org.starfall.generative

import android.content.ClipboardManager
import android.content.Context

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import kotlinx.coroutines.CompletableDeferred
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.starfall.generative.features.providers.editor.ui.ModelCatalogDialog
import org.starfall.generative.shared.model.api.ModelApiType
import org.starfall.generative.shared.model.language.DiscoveredModel
import org.starfall.generative.shared.model.provider.ModelProvider

@RunWith(AndroidJUnit4::class)
class ProviderCatalogDialogTest {
    @get:Rule val compose = createComposeRule()

    @Test fun catalogFetchFailureCopiesDetailsAndRetryRecovers() {
        val failure = "Catalog fetch failed"
        var fail by mutableStateOf(true)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.setContent { MaterialTheme {
            ModelCatalogDialog(
                provider = ModelProvider("catalog", "Catalog", ModelApiType.OPENAI, baseUrl = "https://example.test"),
                selectedModels = emptySet(),
                onFetchModels = {
                    if (fail) throw IllegalStateException(failure)
                    listOf(DiscoveredModel("recovered", displayName = "Recovered model"))
                },
                onCatalogLoaded = {}, onToggle = {}, onSetSelection = { _, _ -> }, onDismiss = {}
            )
        } }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("provider-model-catalog-error").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(failure).assertIsDisplayed()
        compose.onNodeWithTag("provider-model-catalog-copy-error").assertIsDisplayed().performClick()
        compose.runOnIdle {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            assertEquals(failure, clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString())
            fail = false
        }
        compose.onNodeWithText(context.getString(R.string.common_retry)).assertIsDisplayed().performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Recovered model").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("provider-model-catalog-error").assertDoesNotExist()
    }

    @Test fun smallCatalogFitsInDialogWithoutLargeEmptyArea() {
        val fetched = CompletableDeferred<List<DiscoveredModel>>()
        compose.setContent { MaterialTheme {
            ModelCatalogDialog(
                provider = ModelProvider("catalog", "Catalog", ModelApiType.OPENAI, baseUrl = "https://example.test"),
                selectedModels = emptySet(),
                onFetchModels = { fetched.await() },
                onCatalogLoaded = {}, onToggle = {}, onSetSelection = { _, _ -> }, onDismiss = {}
            )
        } }
        compose.onNodeWithTag("app-dialog").assertIsDisplayed()
        fetched.complete(listOf(DiscoveredModel("one", displayName = "Only model")))
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Only model").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Only model").assertIsDisplayed()
        compose.onNodeWithTag("provider_model_catalog_search_button").assertIsDisplayed()
        compose.onNodeWithTag("app-dialog-buttons").assertIsDisplayed()
        val dialog = compose.onNodeWithTag("app-dialog").fetchSemanticsNode().boundsInRoot
        val list = compose.onNodeWithTag("provider-model-catalog-list").fetchSemanticsNode().boundsInRoot
        assertTrue("Short catalog should leave space for dialog header and footer", list.height < dialog.height / 2)
    }

    @Test fun largeCatalogScrollsBetweenPinnedToolbarAndFooter() {
        val fetched = CompletableDeferred<List<DiscoveredModel>>()
        compose.setContent { MaterialTheme {
            ModelCatalogDialog(
                provider = ModelProvider("catalog", "Catalog", ModelApiType.OPENAI, baseUrl = "https://example.test"),
                selectedModels = emptySet(),
                onFetchModels = { fetched.await() },
                onCatalogLoaded = {}, onToggle = {}, onSetSelection = { _, _ -> }, onDismiss = {}
            )
        } }
        fetched.complete((0 until 80).map { DiscoveredModel("model-$it", displayName = "Model $it") })
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Model 0").fetchSemanticsNodes().isNotEmpty() }
        val toolbar = compose.onNodeWithTag("provider-model-catalog-toolbar").assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot
        val list = compose.onNodeWithTag("provider-model-catalog-list").assertIsDisplayed()
        list.performScrollToIndex(79)
        list.performTouchInput { swipeUp(durationMillis = 400) }
        compose.waitForIdle()
        val footer = compose.onNodeWithTag("app-dialog-buttons").assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot
        val listBounds = list.fetchSemanticsNode().boundsInRoot
        val afterToolbar = compose.onNodeWithTag("provider-model-catalog-toolbar")
            .fetchSemanticsNode().boundsInRoot
        assertEquals("Toolbar must stay pinned", toolbar.top, afterToolbar.top, 1f)
        assertTrue("List must stay between toolbar and dialog footer", toolbar.bottom <= listBounds.top && listBounds.bottom < footer.top)
        val lastModel = compose.onNodeWithText("Model 79").assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot
        val bottomGap = with(compose.density) { 16.dp.toPx() }
        assertTrue("Last model must keep bottom spacing", lastModel.bottom <= listBounds.bottom - bottomGap + 2f)
    }

    @Test fun catalogOpensInDialogWithTopToolbarAndOnDemandSearch() {
        var selected by mutableStateOf(emptySet<String>())
        var refreshes = 0
        compose.setContent {
            MaterialTheme {
                ModelCatalogDialog(
                    provider = ModelProvider("catalog", "Catalog", ModelApiType.OPENAI, baseUrl = "https://example.test"),
                    selectedModels = selected,
                    onFetchModels = {
                        refreshes++
                        listOf(DiscoveredModel("alpha", displayName = "Alpha model"),
                            DiscoveredModel("beta", displayName = "Beta model"))
                    },
                    onCatalogLoaded = {},
                    onToggle = { id -> selected = if (id in selected) selected - id else selected + id },
                    onSetSelection = { ids, enabled ->
                        selected = if (enabled) selected + ids else selected - ids.toSet()
                    },
                    onDismiss = {}
                )
            }
        }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Alpha model").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("app-dialog").assertIsDisplayed()
        compose.onNodeWithTag("app-dialog-title").assertIsDisplayed()
        compose.onNodeWithTag("app-dialog-buttons").assertIsDisplayed()
        compose.onNodeWithTag("app-bottom-sheet").assertDoesNotExist()
        val toolbar = compose.onNodeWithTag("provider-model-catalog-toolbar")
            .assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val list = compose.onNodeWithTag("provider-model-catalog-list")
            .assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue("Catalog toolbar must be above the model list", toolbar.bottom <= list.top)
        compose.onNodeWithTag("provider_model_catalog_search_button").assertIsDisplayed()
        compose.onNodeWithTag("provider_model_catalog_search").assertDoesNotExist()
        compose.onNodeWithContentDescription(InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.select_all_visible_models)).performClick()
        compose.runOnIdle { assertEquals(setOf("alpha", "beta"), selected) }
        compose.onNodeWithContentDescription(InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.provider_deselect_visible_models)).performClick()
        compose.runOnIdle { assertEquals(emptySet<String>(), selected) }

        compose.onNodeWithTag("provider_model_catalog_search_button").performClick()
        compose.onNodeWithTag("provider_model_catalog_search").assertIsDisplayed()
        compose.onNodeWithTag("provider_model_catalog_search").performTextInput("alpha")
        compose.onNodeWithText("Alpha model").assertIsDisplayed()
        compose.onNodeWithText("Beta model").assertDoesNotExist()
        // Dismissing the IME must collapse the editor, not reset the search filter.
        compose.onNodeWithTag("provider_model_catalog_search").performImeAction()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("provider_model_catalog_search_button").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("provider_model_catalog_search_button").assertIsDisplayed()
        compose.onNodeWithText("Alpha model").assertIsDisplayed()
        compose.onNodeWithText("Beta model").assertDoesNotExist()
        compose.onNodeWithTag("provider_model_catalog_search_button").performClick()
        compose.onNodeWithTag("provider_model_catalog_search").assertTextContains("alpha")
        compose.onNodeWithText("Beta model").assertDoesNotExist()
        compose.onNodeWithTag("provider_model_catalog_search").performTextClearance()
        compose.onNodeWithText("Beta model").assertIsDisplayed()
        compose.onNodeWithContentDescription(InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.refresh_models)).performClick()
        compose.waitUntil(5_000) { refreshes >= 2 }
    }
    @Test fun catalogDialogScrollsToBothEndsWithoutReverseSwipe() {
        val models = (0 until 150).map { DiscoveredModel(id = "scroll-model-$it", displayName = "Catalog model $it") }
        compose.setContent { MaterialTheme {
            ModelCatalogDialog(
                provider = ModelProvider("test", "Test", ModelApiType.OPENAI, baseUrl = "https://example.test"),
                selectedModels = emptySet(), onFetchModels = { models }, onCatalogLoaded = {},
                onToggle = {}, onSetSelection = { _, _ -> }, onDismiss = {}
            )
        } }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Catalog model 0").fetchSemanticsNodes().isNotEmpty() }
        val list = compose.onNodeWithTag("provider-model-catalog-list")
        repeat(40) {
            val before = list.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
            val position = before.value()
            val couldScroll = position < before.maxValue()
            list.performTouchInput { swipeUp(durationMillis = 180) }
            compose.waitForIdle()
            val after = list.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
            if (couldScroll) assertTrue("Forward swipes must keep advancing until the real bottom",
                after.value() > position || after.value() >= after.maxValue())
        }
        compose.onNodeWithText("Catalog model 149").assertIsDisplayed()
        repeat(3) { list.performTouchInput { swipeUp(durationMillis = 180) }; compose.waitForIdle() }
        repeat(40) { list.performTouchInput { swipeDown(durationMillis = 180) }; compose.waitForIdle() }
        compose.onNodeWithText("Catalog model 0").assertIsDisplayed()
    }
}
