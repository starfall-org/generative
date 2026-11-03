package org.starfall.generative

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.starfall.generative.features.providers.list.ui.ProvidersScreen
import org.starfall.generative.shared.model.api.ModelApiType
import org.starfall.generative.shared.model.provider.ModelProvider

/** An active ProvidersScreen owns its item positions even if upstream sort ranks change. */
@RunWith(AndroidJUnit4::class)
class ProviderPassiveOrderDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun verifyLocalOrderSurvivesUpstreamSortOnlyChange(grid: Boolean) {
        val catalog = mutableStateOf((0 until 48).map { index ->
            ModelProvider("p$index", "Provider $index", ModelApiType.OPENAI,
                baseUrl = "", sortOrder = index)
        })
        compose.setContent {
            MaterialTheme {
                ProvidersScreen(
                    providers = catalog.value, isGridView = grid,
                    onOpenProviderEditor = { _, _ -> },
                    onDeleteProvider = {}, onReorderProviders = {}, onBack = {}
                )
            }
        }
        val list = compose.onNodeWithTag("provider_list", useUnmergedTree = true)
        list.performScrollToIndex(27)
        compose.waitForIdle()
        val before = compose.onNodeWithTag("provider_p27", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        // Mimics a late storage ranking snapshot. Item membership and metadata
        // remain unchanged: only the presentation order is different.
        compose.runOnIdle {
            catalog.value = catalog.value.reversed().mapIndexed { index, provider ->
                provider.copy(sortOrder = index)
            }
        }
        compose.waitForIdle()
        val after = compose.onNodeWithTag("provider_p27", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        assertEquals("The viewport must not jump when ranks update upstream", before.top, after.top, 1f)
        assertEquals("The same item must remain in its local slot", before.left, after.left, 1f)
    }

    @Test fun openRootListIgnoresIncomingSortRankChanges() =
        verifyLocalOrderSurvivesUpstreamSortOnlyChange(grid = false)

    @Test fun openRootGridIgnoresIncomingSortRankChanges() =
        verifyLocalOrderSurvivesUpstreamSortOnlyChange(grid = true)
}
