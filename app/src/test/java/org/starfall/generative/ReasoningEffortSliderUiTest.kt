package org.starfall.generative

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.starfall.generative.shared.subfeatures.modelpicker.ui.ReasoningEffortControl

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w400dp-h800dp-xhdpi", application = LibraryTestApplication::class)
class ReasoningEffortSliderUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun thumbFollowsFractionalProgressThenSettlesToNearestLevel() {
        var lastPreview = Float.NaN
        var saved: String? = null
        var saveCount = 0
        compose.setContent {
            var selected by remember { mutableStateOf<String?>(null) }
            MaterialTheme {
                ReasoningEffortControl(
                    effort = selected,
                    onEffortChange = { selected = it; saved = it; saveCount++ },
                    onPreviewValueChange = { lastPreview = it }
                )
            }
        }
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("reasoning-slider").performSemanticsAction(SemanticsActions.SetProgress) {
            it(2.65f)
        }
        compose.runOnIdle {
            assertTrue("Slider must accept fractional positions during interaction", lastPreview in 2.60f..2.70f)
            assertEquals("medium", saved)
            assertEquals(1, saveCount)
        }
        compose.mainClock.advanceTimeBy(1300)
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals("medium", saved)
            assertEquals(1, saveCount)
            assertEquals(3f, lastPreview, 0.02f)
        }
    }
}
