package org.starfall.generative

import androidx.activity.ComponentActivity
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.starfall.generative.features.home.shared.ui.SelectedModelOverviewSheet
import org.starfall.generative.shared.model.language.ModelConfiguration
import org.starfall.generative.shared.subfeatures.modelpicker.ui.LocalThinkingScreenBurst
import org.starfall.generative.shared.subfeatures.modelpicker.ui.ThinkingColorBurst
import org.starfall.generative.shared.subfeatures.modelpicker.ui.ThinkingScreenBurstLayer
import org.starfall.generative.shared.subfeatures.modelpicker.ui.rememberThinkingColorBurst

/** Real-device regression coverage for the sheet's screen-owned reasoning effect. */
@RunWith(AndroidJUnit4::class)
class ReasoningEffectsLifecycleDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val config = ModelConfiguration(
        supportsThinking = true,
        reasoningEfforts = listOf("low", "medium", "high", "xhigh", "max")
    )

    private lateinit var burst: ThinkingColorBurst
    private lateinit var reopenSheet: () -> Unit
    private var underlyingClicks = 0

    private fun showSheet() {
        compose.setContent {
            MaterialTheme {
                val screenBurst = rememberThinkingColorBurst()
                burst = screenBurst
                var visible by remember { mutableStateOf(true) }
                var effort by remember { mutableStateOf<String?>(null) }
                reopenSheet = { visible = true }
                CompositionLocalProvider(LocalThinkingScreenBurst provides screenBurst) {
                    Button(onClick = { underlyingClicks++ }) { Text("Underlying action") }
                    ThinkingScreenBurstLayer(screenBurst)
                    if (visible) {
                        SelectedModelOverviewSheet(
                            modelId = "Effect model",
                            config = config,
                            conversationReasoningEffort = effort,
                            onSetReasoningEffort = { effort = it },
                            onOpenModelPicker = { visible = false },
                            onDismiss = { visible = false }
                        )
                    }
                }
            }
        }
        compose.waitForIdle() // Mount the actual ModalBottomSheet window before freezing its clock.
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("reasoning-slider").assertIsDisplayed()
    }

    private fun selectEffort(index: Float) {
        compose.onNodeWithTag("reasoning-slider")
            .performSemanticsAction(SemanticsActions.SetProgress) { it(index) }
    }

    @Test fun maximumStartsAtSliderThumbAndSpreadsStarsAcrossTheScreen() {
        showSheet()
        selectEffort(6f) // None, Default, low, medium, high, xhigh, max.
        compose.mainClock.advanceTimeBy(100)
        val initial = burst.starSpreadProgress.value
        compose.runOnIdle {
            assertNotEquals("Effect must originate at the slider", Offset.Zero, burst.origin)
            assertTrue(burst.keepAfterCompletion)
            assertEquals(180, burst.starCount)
            assertTrue("Maximum bloom should be animated", burst.progress.value < 1f)
            assertTrue("Stars begin at the thumb", initial < 0.15f)
        }
        compose.mainClock.advanceTimeBy(700)
        val spreading = burst.starSpreadProgress.value
        assertTrue("Stars must move out from the thumb", spreading > initial)
        assertTrue("Spread should not jump to its end", spreading < 1f)
        compose.mainClock.advanceTimeBy(2000)
        compose.runOnIdle {
            assertEquals(1f, burst.starSpreadProgress.value, 0.001f)
            assertTrue(burst.keepAfterCompletion)
        }
    }

    @Test fun penultimateEffortDoesNotTriggerMaximumBurst() {
        showSheet()
        selectEffort(5f)
        compose.mainClock.advanceTimeBy(400)
        compose.runOnIdle {
            assertFalse(burst.keepAfterCompletion)
            assertFalse(burst.isRetracting)
            assertEquals(Offset.Zero, burst.origin)
            assertEquals(1f, burst.progress.value, 0.001f)
        }
    }

    @Test fun leavingMaximumRetractsStarsAndClearsTheEffect() {
        showSheet()
        selectEffort(6f)
        compose.mainClock.advanceTimeBy(1700)
        selectEffort(5f)
        compose.mainClock.advanceTimeBy(200)
        compose.runOnIdle {
            assertTrue("Leaving maximum must retract, not disappear instantly", burst.isRetracting)
            assertFalse(burst.keepAfterCompletion)
            assertTrue(burst.retractionProgress.value in 0.01f..0.99f)
            assertTrue(burst.starSpreadProgress.value in 0.01f..0.99f)
        }
        compose.mainClock.advanceTimeBy(1500)
        compose.runOnIdle {
            assertFalse(burst.isRetracting)
            assertEquals(0f, burst.retractionProgress.value, 0.001f)
            assertEquals(0f, burst.starSpreadProgress.value, 0.001f)
            assertEquals(0, burst.starCount)
        }
    }

    @Test fun maximumAgainInterruptsAnOngoingRetraction() {
        showSheet()
        selectEffort(6f)
        compose.mainClock.advanceTimeBy(250)
        selectEffort(5f)
        compose.mainClock.advanceTimeBy(100)
        selectEffort(6f)
        compose.mainClock.advanceTimeBy(120)
        compose.runOnIdle {
            assertFalse("The new burst must cancel the old retraction", burst.isRetracting)
            assertTrue(burst.keepAfterCompletion)
            assertEquals(180, burst.starCount)
            assertTrue("Re-entry starts a new outward animation", burst.progress.value < 1f)
            assertTrue(burst.starSpreadProgress.value < 1f)
        }
    }

    @Test fun closingSheetHidesStarsAndReopeningRestoresTheSettledMaximum() {
        showSheet()
        selectEffort(6f)
        compose.mainClock.advanceTimeBy(800)
        compose.onNodeWithTag("open-model-picker-button").performClick()
        compose.mainClock.advanceTimeBy(800)
        compose.onNodeWithTag("reasoning-slider").assertDoesNotExist()
        compose.runOnIdle {
            assertFalse("Closed sheet must not leave stars on the Activity", burst.keepAfterCompletion)
            assertEquals(0, burst.starCount)
            assertEquals(1f, burst.progress.value, 0.001f)
        }
        // An Activity overlay is drawable-only; it must not intercept underlying taps.
        compose.onNodeWithText("Underlying action").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, underlyingClicks) }
        // ModalBottomSheet is initially Hidden; allow the window/show transition to
        // finish before checking visibility (v2 rules use a queued dispatcher).
        compose.mainClock.autoAdvance = true
        compose.runOnIdle { reopenSheet() }
        compose.waitForIdle()
        compose.onNodeWithTag("reasoning-slider").assertIsDisplayed()
        compose.runOnIdle {
            assertTrue("Reopening max should restore the settled stars", burst.keepAfterCompletion)
            assertEquals(1f, burst.starSpreadProgress.value, 0.001f)
        }
    }
}
