package org.starfall.generative

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.starfall.generative.features.home.chat.ui.LiveThinkingBlock
import org.starfall.generative.database.preferences.AppPreferences
import org.starfall.generative.shared.ui.code.LocalCodeRenderingPreferences

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w360dp-h800dp-xhdpi", application = LibraryTestApplication::class)
class LiveThinkingBlockHeightTest {
    @get:Rule val compose = createComposeRule()

    @Test fun collapsedPreviewKeepsTheHalfLineAcrossFontSizesAndScales() {
        val cases = listOf(12 to 1f, 20 to 1f, 16 to 1.5f)
        var scenario by mutableIntStateOf(0)
        var expectedHeight = 0f
        compose.setContent { MaterialTheme {
            val (fontSize, fontScale) = cases[scenario]
            val density = Density(LocalDensity.current.density, fontScale)
            expectedHeight = with(density) { (fontSize.sp * 1.375f * 3.5f).toPx() }
            CompositionLocalProvider(LocalDensity provides density,
                LocalCodeRenderingPreferences provides AppPreferences(
                    messageFontSize = fontSize, messageFontSizeCustom = true)) {
                key(scenario) {
                    LiveThinkingBlock((1..20).joinToString("\n\n") { "Thinking paragraph $it." },
                        active = false, showHeader = false)
                }
            }
        } }
        cases.indices.forEach { index ->
            compose.runOnIdle { scenario = index }
            val bounds = compose.onNodeWithTag("live-thinking-preview").fetchSemanticsNode().boundsInRoot
            assertEquals("Keep 3.5 lines without rounding to four", expectedHeight, bounds.height, 1f)
        }
    }

    @Test fun shortContentKeepsNaturalHeightAndExpandedContentIsNotCapped() {
        var reasoning by mutableStateOf("Brief thought.")
        compose.setContent { MaterialTheme {
            Column { LiveThinkingBlock(reasoning, active = false) }
        } }
        val preview = compose.onNodeWithTag("live-thinking-preview")
        val shortHeight = preview.fetchSemanticsNode().boundsInRoot.height
        compose.runOnIdle { reasoning = (1..12).joinToString("\n\n") { "Thinking paragraph $it." } }
        val collapsedHeight = preview.fetchSemanticsNode().boundsInRoot.height
        assertTrue("Short reasoning must not reserve 3.5 lines", shortHeight < collapsedHeight)
        compose.onNodeWithContentDescription("Expand").performClick()
        compose.waitForIdle()
        assertTrue("Expanded reasoning must exceed the collapsed cap",
            preview.getUnclippedBoundsInRoot().let { it.bottom - it.top }.value >
                with(compose.density) { collapsedHeight.toDp() }.value)
        compose.onNodeWithText("Thinking paragraph 12.", substring = true, useUnmergedTree = true).assertExists()
    }
}
