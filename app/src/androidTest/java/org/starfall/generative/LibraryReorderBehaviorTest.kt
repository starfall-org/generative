package org.starfall.generative

import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.starfall.generative.shared.model.language.ModelConfiguration
import org.starfall.generative.features.providers.shared.ui.ProviderModelsPane

class LibraryReorderBehaviorTest {
    @get:Rule val compose = createComposeRule()

    @Test fun modelOrderChangesDuringDragAndPersistsOnlyOnRelease() {
        val order = mutableStateOf(listOf("First", "Second", "Third"))
        val configurations = order.value.associateWith { ModelConfiguration() }
        val saved = mutableListOf<List<String>>()
        compose.setContent { MaterialTheme {
            ProviderModelsPane(order.value, configurations, rememberLazyListState(),
                onOrderChange = { order.value = it },
                onDrop = { _, reordered -> saved += reordered }, onEdit = {}, onDelete = {}, onOpenCatalog = {})
        } }
        val source = compose.onNodeWithText("First")
        val from = source.fetchSemanticsNode().boundsInRoot
        val to = compose.onNodeWithText("Third").fetchSemanticsNode().boundsInRoot
        val root = compose.onRoot()
        val origin = root.fetchSemanticsNode().boundsInRoot.topLeft
        root.performTouchInput {
            down(from.center - origin)
            advanceEventTime(650)
            moveBy(Offset(0f, to.center.y - from.center.y), delayMillis = 200)
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals("First", order.value.last())
            assertTrue(saved.isEmpty())
        }
        root.performTouchInput { up() }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(listOf(order.value), saved) }
    }
}
