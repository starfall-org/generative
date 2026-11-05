package org.starfall.generative

import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.starfall.generative.shared.model.*
import org.starfall.generative.features.providers.shared.ui.*
import org.starfall.generative.shared.subfeatures.modelpicker.logic.*
import org.starfall.generative.shared.subfeatures.modelpicker.ui.*
import org.starfall.generative.shared.model.language.ModelConfiguration
import org.starfall.generative.features.home.shared.ui.SelectedModelOverviewSheet

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w360dp-h800dp-xhdpi")
class ReasoningEffectsLifecycleTest {
    @get:Rule val compose = createComposeRule()

    private val multiLevelConfig = ModelConfiguration(reasoningEfforts = listOf("low", "medium", "high", "xhigh", "max"))

    @Test fun maxStarsAreHiddenOnDismissalAndRestoredWhenTheSheetReopens() {
        var clicks = 0
        lateinit var burst: ThinkingColorBurst
        lateinit var reopenSheet: () -> Unit
        compose.setContent {
            MaterialTheme {
                val screenBurst = rememberThinkingColorBurst()
                burst = screenBurst
                var sheetVisible by remember { mutableStateOf(true) }
                var effort by remember { mutableStateOf<String?>(null) }
                reopenSheet = { sheetVisible = true }
                CompositionLocalProvider(LocalThinkingScreenBurst provides screenBurst) {
                    Button(onClick = { clicks++ }) { Text("Underlying action") }
                    if (sheetVisible) {
                        ThinkingScreenBurstLayer(screenBurst)
                        SelectedModelOverviewSheet(
                                                                                    modelId = "Effect model",
                            config = multiLevelConfig,
                            conversationReasoningEffort = effort,
                            onSetReasoningEffort = { effort = it },
                            onOpenModelPicker = { sheetVisible = false },
                            onDismiss = { sheetVisible = false }
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        // Five configured levels mean Max is the sixth index (None/Default precede them).
        compose.onNodeWithTag("reasoning-slider").performSemanticsAction(SemanticsActions.SetProgress) {
            it(6f)
        }
        compose.mainClock.advanceTimeBy(800)
        compose.runOnIdle { assertTrue(burst.progress.value < 1f) }
        compose.onNodeWithTag("open-model-picker-button").performClick()
        compose.mainClock.advanceTimeBy(800)
        compose.onNodeWithTag("reasoning-slider").assertDoesNotExist()
         compose.runOnIdle {
             assertEquals(1f, burst.progress.value, 0.001f)
             assertFalse("Dismissing the sheet must hide its settled stars", burst.keepAfterCompletion)
         }
         compose.onNodeWithText("Underlying action").performClick()
         compose.runOnIdle { assertEquals(1, clicks) }
         compose.runOnIdle { reopenSheet() }
         compose.waitForIdle()
         compose.mainClock.advanceTimeBy(1000)
         compose.runOnIdle {
            assertTrue("Max effort should restore the settled stars", burst.keepAfterCompletion)
            assertEquals("Restored stars must occupy their full spread", 1f, burst.starSpreadProgress.value, 0.001f)
        }
         compose.mainClock.advanceTimeBy(7000)
        compose.runOnIdle { assertEquals(1f, burst.progress.value, 0.001f) }
    }
    private fun setUpSheet(onBurst: (ThinkingColorBurst) -> Unit) {
        compose.setContent {
            MaterialTheme {
                val screenBurst = rememberThinkingColorBurst()
                onBurst(screenBurst)
                var effort by remember { mutableStateOf<String?>(null) }
                CompositionLocalProvider(LocalThinkingScreenBurst provides screenBurst) {
                    ThinkingScreenBurstLayer(screenBurst)
                    SelectedModelOverviewSheet(
                                                                        modelId = "Effect model",
                        config = multiLevelConfig,
                        conversationReasoningEffort = effort,
                        onSetReasoningEffort = { effort = it },
                        onOpenModelPicker = {},
                        onDismiss = {}
                    )
                }
            }
        }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
    }

    @Test fun draggingToMaxSpreadsTheColourFromTheThumbNotFromTheScreenCorner() {
        lateinit var burst: ThinkingColorBurst
        setUpSheet { burst = it }
        compose.onNodeWithTag("reasoning-slider").performSemanticsAction(SemanticsActions.SetProgress) { it(6f) }
        compose.mainClock.advanceTimeBy(200)
        compose.runOnIdle {
            assertNotEquals("The bloom origin must be the slider thumb", androidx.compose.ui.geometry.Offset.Zero, burst.origin)
            assertTrue(burst.keepAfterCompletion)
            assertTrue("The bloom must still be spreading", burst.progress.value < 1f)
        }
    }

    @Test fun enteringMaximumSpreadsStarsOutwardAndLeavingReversesTheirTravel() {
        lateinit var burst: ThinkingColorBurst
        setUpSheet { burst = it }
        compose.onNodeWithTag("reasoning-slider").performSemanticsAction(SemanticsActions.SetProgress) { it(6f) }
        compose.mainClock.advanceTimeBy(100)
        val startingSpread = burst.starSpreadProgress.value
        assertTrue("Stars must launch close to the slider thumb", startingSpread < 0.15f)

        compose.mainClock.advanceTimeBy(700)
        val expandingSpread = burst.starSpreadProgress.value
        assertTrue("Stars should be visibly travelling away from the thumb", expandingSpread > startingSpread)
        assertTrue("Expansion must be animated, not an instant jump", expandingSpread < 1f)

        compose.mainClock.advanceTimeBy(2000)
        assertEquals("Stars must remain spread out while max effort is active", 1f,
            burst.starSpreadProgress.value, 0.001f)
        compose.onNodeWithTag("reasoning-slider").performSemanticsAction(SemanticsActions.SetProgress) { it(5f) }
        compose.mainClock.advanceTimeBy(250)
        val returningSpread = burst.starSpreadProgress.value
        assertTrue("Stars must travel back toward the same thumb", returningSpread in 0.01f..0.99f)
        compose.mainClock.advanceTimeBy(1100)
        assertEquals("All stars should reach the original point before disappearing", 0f,
            burst.starSpreadProgress.value, 0.001f)
    }

    @Test fun secondHighestLevelKeepsTheNormalColourWithoutAStarBurst() {
        lateinit var burst: ThinkingColorBurst
        setUpSheet { burst = it }
        compose.onNodeWithTag("reasoning-slider").performSemanticsAction(SemanticsActions.SetProgress) { it(5f) }
        compose.mainClock.advanceTimeBy(300)
        compose.runOnIdle {
            assertFalse("The penultimate level must not have a purple bloom", burst.keepAfterCompletion)
            assertFalse(burst.isRetracting)
            assertEquals(1f, burst.progress.value, 0.001f)
        }
    }

    @Test fun leavingMaximumRetractsItsEffectInsteadOfHidingImmediately() {
        lateinit var burst: ThinkingColorBurst
        setUpSheet { burst = it }
        compose.onNodeWithTag("reasoning-slider").performSemanticsAction(SemanticsActions.SetProgress) { it(6f) }
        compose.mainClock.advanceTimeBy(1500)
        compose.onNodeWithTag("reasoning-slider").performSemanticsAction(SemanticsActions.SetProgress) { it(5f) }
        compose.mainClock.advanceTimeBy(180)
        compose.runOnIdle {
            assertTrue("The outgoing maximum effect should contract", burst.isRetracting)
            assertTrue(burst.retractionProgress.value in 0f..1f)
            assertTrue(burst.retractionProgress.value < 1f)
            assertTrue(burst.retractionProgress.value > 0f)
            assertFalse(burst.keepAfterCompletion)
        }
        compose.mainClock.advanceTimeBy(1200)
        compose.runOnIdle {
            assertFalse(burst.isRetracting)
            assertEquals(0f, burst.retractionProgress.value, 0.001f)
            assertEquals(0, burst.starCount)
        }
    }

    @Test fun returningToMaximumInterruptsTheRetraction() {
        lateinit var burst: ThinkingColorBurst
        setUpSheet { burst = it }
        compose.onNodeWithTag("reasoning-slider").performSemanticsAction(SemanticsActions.SetProgress) { it(6f) }
        compose.mainClock.advanceTimeBy(250)
        compose.onNodeWithTag("reasoning-slider").performSemanticsAction(SemanticsActions.SetProgress) { it(5f) }
        compose.mainClock.advanceTimeBy(100)
        compose.onNodeWithTag("reasoning-slider").performSemanticsAction(SemanticsActions.SetProgress) { it(6f) }
        compose.mainClock.advanceTimeBy(100)
        compose.runOnIdle {
            assertFalse(burst.isRetracting)
            assertTrue(burst.keepAfterCompletion)
            assertTrue("The returning maximum effect should bloom again", burst.progress.value < 1f)
            assertTrue("Re-entering max must restart the outward star journey", burst.starSpreadProgress.value < 1f)
        }
    }
}
