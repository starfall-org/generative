package org.starfall.generative

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
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
import org.starfall.generative.shared.subfeatures.modelpicker.ui.ModelPickerSheetContent

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w400dp-h800dp-xhdpi", application = LibraryTestApplication::class)
class ModelPickerHeightTest {
    @get:Rule val compose = createComposeRule()

    private fun measuredOverflow(count: Int, collapsed: Boolean = false, heightDp: Int = 500): Boolean? {
        val provider = ModelProvider("p", "Provider", ModelApiType.OPENAI, baseUrl = "",
            config = ProviderConfiguration(modelIds = (0 until count).map { "model-$it" }))
        val overflow = mutableStateOf<Boolean?>(null)
        compose.setContent {
            MaterialTheme {
                Box(Modifier.fillMaxWidth().height(heightDp.dp)) {
                    ModelPickerSheetContent(
                        providers = listOf(provider),
                        selectedProviderId = "p", selectedModelId = "",
                        conversationReasoningEffort = null,
                        onSelectModel = { _, _ -> }, onSetReasoningEffort = {},
                        collapsedProviderIdsState = if (collapsed) setOf("p") else emptySet(),
                        includeNavigationBarPadding = false,
                        onOverflowChanged = { overflow.value = it },
                        modifier = Modifier.fillMaxSize(),
                        onDismiss = {}
                    )
                }
            }
        }
        compose.waitForIdle()
        return overflow.value
    }

    @Test fun shortListDoesNotRequestExpansion() {
        assertEquals(false, measuredOverflow(1))
    }

    @Test fun longListRequestsExpansionFromRealLazyListLayout() {
        assertEquals(true, measuredOverflow(20))
    }

    @Test fun fiveVisibleModelsRequestBrowsingHeightEvenIfTheyFit() {
        assertEquals(true, measuredOverflow(5, heightDp = 780))
    }

    @Test fun collapsedProviderDoesNotCountHiddenModels() {
        assertEquals(false, measuredOverflow(20, collapsed = true))
    }
}
