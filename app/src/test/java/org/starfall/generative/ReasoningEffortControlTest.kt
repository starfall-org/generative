package org.starfall.generative

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import androidx.compose.ui.graphics.Color
import org.junit.Test
import org.starfall.generative.shared.model.language.DEFAULT_REASONING_EFFORTS
import org.starfall.generative.shared.model.language.ModelConfiguration
import org.starfall.generative.shared.model.language.REASONING_EFFORT_ACTIVE
import org.starfall.generative.shared.model.language.SELECTABLE_REASONING_EFFORTS
import org.starfall.generative.shared.model.language.supportedReasoningEfforts
import org.starfall.generative.shared.subfeatures.modelpicker.ui.reasoningEffortBurstDuration
import org.starfall.generative.shared.subfeatures.modelpicker.ui.reasoningEffortEnabled
import org.starfall.generative.shared.subfeatures.modelpicker.ui.reasoningEffortAccent
import org.starfall.generative.shared.subfeatures.modelpicker.ui.reasoningEffortIndex
import org.starfall.generative.shared.subfeatures.modelpicker.ui.reasoningEffortOptions
import org.starfall.generative.shared.subfeatures.modelpicker.ui.ReasoningEffortOption
import org.starfall.generative.shared.subfeatures.modelpicker.ui.reasoningEffortTier
import org.starfall.generative.shared.subfeatures.modelpicker.ui.nearestReasoningEffortIndex

class ReasoningEffortControlTest {
    private fun efforts(options: List<org.starfall.generative.shared.subfeatures.modelpicker.ui.ReasoningEffortOption>) = options.map { it.effort }
    private fun tiers(options: List<org.starfall.generative.shared.subfeatures.modelpicker.ui.ReasoningEffortOption>) =
        options.indices.map { reasoningEffortTier(it, options) }

    @Test fun disabledValuesAreRecognized() {
        assertFalse(reasoningEffortEnabled("none"))
        assertFalse(reasoningEffortEnabled(" OFF "))
        assertTrue(reasoningEffortEnabled(null))
        assertTrue(reasoningEffortEnabled("medium"))
    }

    @Test fun everyModelStartsWithLowMediumAndHigh() {
        assertEquals(listOf("low", "medium", "high"), DEFAULT_REASONING_EFFORTS)
        assertEquals(DEFAULT_REASONING_EFFORTS, ModelConfiguration().supportedReasoningEfforts)
        assertEquals(
            listOf("none", null, "low", "medium", "high"),
            efforts(reasoningEffortOptions(DEFAULT_REASONING_EFFORTS))
        )
    }

    @Test fun sliderOnlyShowsTheLevelsTheModelMarks() {
        val marked = listOf("minimal", "medium", "max")
        val options = reasoningEffortOptions(marked)
        assertEquals(listOf("none", null, "minimal", "medium", "max"), efforts(options))
        assertEquals(listOf("None", "Default", "Minimal", "Medium", "Max"), options.map { it.label })
        assertEquals(listOf("minimal", "low", "medium", "high", "xhigh", "max"),
            ModelConfiguration(reasoningEfforts = SELECTABLE_REASONING_EFFORTS.reversed()).supportedReasoningEfforts)
    }

    @Test fun labelsFollowTheCanonicalLevels() {
        val all = SELECTABLE_REASONING_EFFORTS
        val levels = reasoningEffortOptions(all).filter { it.effort in all }
        assertEquals(listOf("Minimal", "Low", "Medium", "High", "X-High", "Max"), levels.map { it.label })
    }

    @Test fun unsupportedSavedValuesShowAsDefault() {
        val options = reasoningEffortOptions(DEFAULT_REASONING_EFFORTS)
        assertEquals(1, reasoningEffortIndex(null, options))
        assertEquals(0, reasoningEffortIndex("none", options))
        assertEquals(0, reasoningEffortIndex("off", options))
        assertEquals(2, reasoningEffortIndex("LOW", options))
        assertEquals(4, reasoningEffortIndex(" HIGH ", options))
        for (stale in listOf("xhigh", "max", "minimal", "unknown", REASONING_EFFORT_ACTIVE)) {
            assertEquals(1, reasoningEffortIndex(stale, options))
        }
    }

    @Test fun onlyTheLastSelectableLevelGetsTheMaximumColour() {
        assertEquals(listOf(0, 1, 2, 3, 6), tiers(reasoningEffortOptions(DEFAULT_REASONING_EFFORTS)))
        assertEquals(listOf(0, 1, 2, 6), tiers(reasoningEffortOptions(supportedEfforts = listOf("high", "max"))))
        assertEquals(listOf(0, 1, 2, 3, 4, 4, 4, 6),
            tiers(reasoningEffortOptions(supportedEfforts = SELECTABLE_REASONING_EFFORTS)))
        assertEquals(listOf(0, 1, 2, 6),
            tiers(reasoningEffortOptions(supportedEfforts = listOf("minimal", "low"))))
        assertTrue(tiers(reasoningEffortOptions(SELECTABLE_REASONING_EFFORTS)).none { it == 5 })
        assertEquals(Color.Green, reasoningEffortAccent(5, Color.Green))
        assertEquals(Color(0xFFB91C1C), reasoningEffortAccent(6, Color.Green))
    }

    @Test fun singleLevelGetsTheMaximumColourAndDefaultStaysUncoloured() {
        val options = reasoningEffortOptions(supportedEfforts = listOf("medium"))
        assertEquals(listOf(null, "medium"), efforts(options).filter { it != "none" })
        assertEquals(listOf(0, 1, 6), tiers(options))
    }

    @Test fun aThinkingModelWithoutLevelsGetsAnActiveStepAfterDefault() {
        val options = reasoningEffortOptions(supportedEfforts = emptyList())
        assertEquals(listOf("none", null, REASONING_EFFORT_ACTIVE), efforts(options))
        assertEquals(listOf("None", "Default", "Active"), options.map { it.label })
        assertEquals(listOf(0, 1, 6), tiers(options))
        assertEquals(2, reasoningEffortIndex(REASONING_EFFORT_ACTIVE, options))
        assertEquals(1, reasoningEffortIndex(null, options))
    }

    @Test fun activeIsOnlyOfferedWhenThereAreNoLevels() {
        assertFalse(efforts(reasoningEffortOptions(DEFAULT_REASONING_EFFORTS)).contains(REASONING_EFFORT_ACTIVE))
        assertFalse(efforts(reasoningEffortOptions(supportedEfforts = listOf("low"))).contains(REASONING_EFFORT_ACTIVE))
    }

    @Test fun maximumEffectsLastLongerThanRegularLevels() {
        assertTrue(reasoningEffortBurstDuration(6) > reasoningEffortBurstDuration(4))
    }
    @Test fun smoothSliderSnapsToNearestValidDetentOnRelease() {
        assertEquals(0, nearestReasoningEffortIndex(-0.4f, 5))
        assertEquals(2, nearestReasoningEffortIndex(2.49f, 5))
        assertEquals(3, nearestReasoningEffortIndex(2.51f, 5))
        assertEquals(4, nearestReasoningEffortIndex(4.6f, 5))
        assertEquals(0, nearestReasoningEffortIndex(0f, 1))
    }

}
