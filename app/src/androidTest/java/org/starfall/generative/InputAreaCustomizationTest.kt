package org.starfall.generative

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.starfall.generative.features.home.BottomSheetsLinkState
import org.starfall.generative.features.home.chat.ui.input.UserInputArea
import org.starfall.generative.features.customization.ui.CustomizationScreen
import org.starfall.generative.database.preferences.AppPreferences
import org.starfall.generative.database.preferences.UserProfile
import org.starfall.generative.shared.ui.layout.*

@RunWith(AndroidJUnit4::class)
class InputAreaCustomizationTest {
    @get:Rule val compose = createComposeRule()

    @Test fun slidersCommitIndependentlyAndResetToOriginalAppearance() {
        var preferences by mutableStateOf(AppPreferences(itemTransparency = 0.4f))
        compose.setContent { MaterialTheme {
            CustomizationScreen(
                profile = UserProfile(), onChange = {}, onBack = {}, appearance = preferences,
                onInputAreaTransparencyChange = { preferences = preferences.copy(inputAreaTransparency = it) },
                onInputAreaBlurChange = { preferences = preferences.copy(inputAreaBlur = it) }
            )
        } }
        compose.onNodeWithTag("customization-list").performScrollToKey("input-area")
        compose.onNodeWithTag("input-area-transparency-slider").assertDoesNotExist()
        compose.onNodeWithTag("customization-input-setting").performClick()
        compose.onNodeWithTag("input-area-transparency-slider").performScrollTo().performTouchInput {
            swipe(Offset(width * 0.1f, center.y), Offset(width * 0.8f, center.y), 350)
        }
        compose.runOnIdle {
            assertTrue(preferences.inputAreaTransparency > 0.7f)
            assertEquals(2f, preferences.inputAreaBlur, 0f)
        }
        compose.onNodeWithTag("input-area-blur-slider").performScrollTo().performTouchInput {
            swipe(Offset(width * 0.1f, center.y), Offset(width * 0.8f, center.y), 350)
        }
        compose.runOnIdle {
            assertTrue(preferences.inputAreaBlur > 20f)
            assertEquals(0.4f, preferences.itemTransparency, 0f)
        }
        compose.onNodeWithTag("input-area-appearance-reset").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(0.1f, preferences.inputAreaTransparency, 0f)
            assertEquals(2f, preferences.inputAreaBlur, 0f)
            assertEquals(0.4f, preferences.itemTransparency, 0f)
        }
    }

    @Test fun composerTransparencyUpdatesWithoutAffectingTypingOrSending() {
        var appearance by mutableStateOf(InputAreaAppearance(0f, 32f))
        var sent: String? = null
        compose.setContent { MaterialTheme {
            val backdrop = rememberInputAreaBackdrop()
            CompositionLocalProvider(LocalInputAreaAppearance provides appearance) {
                Box(Modifier.fillMaxSize()) {
                    Box(Modifier.matchParentSize().inputAreaBackdropSource(backdrop, enabled = true)
                        .background(Color.Magenta))
                    UserInputArea(
                        backdrop = backdrop, modifier = Modifier.align(Alignment.BottomCenter),
                        isGenerating = false, onSendMessage = { text, _ -> sent = text; true },
                        onSendMedia = { false }, bottomSheets = remember { BottomSheetsLinkState() },
                        onEditMessage = { _, _, _ -> false }, editDraft = null,
                        onCancelEdit = {}, onStopGenerating = {}, selectedModelName = "",
                        providers = emptyList(), selectedProviderId = "", onSelectModel = { _, _ -> },
                        conversationReasoningEffort = null, onSetReasoningEffort = {}
                    )
                }
            }
        } }
        val opaque = compose.onNodeWithTag("chat-input").captureToImage().toPixelMap()
        compose.runOnIdle { appearance = InputAreaAppearance(1f, 32f) }
        val transparent = compose.onNodeWithTag("chat-input").captureToImage().toPixelMap()
        val x = transparent.width / 2
        val y = 4
        assertTrue(transparent[x, y].red > 0.95f && transparent[x, y].blue > 0.95f)
        assertTrue(transparent[x, y].green < 0.05f)
        assertTrue(opaque[x, y].green > transparent[x, y].green + 0.1f)
        compose.onNode(hasSetTextAction()).performTextInput("Clear input")
        compose.runOnIdle { appearance = InputAreaAppearance(0.5f, 0f) }
        compose.onNode(hasSetTextAction()).assertTextEquals("Clear input")
        compose.onNodeWithContentDescription(InstrumentationRegistry.getInstrumentation().targetContext
            .getString(R.string.chat_send_message)).performClick()
        compose.runOnIdle { assertEquals("Clear input", sent) }
    }

    @SdkSuppress(minSdkVersion = 31)
    @Test fun backdropBlurSoftensTheBackgroundAndKeepsForegroundInteractive() {
        var radius by mutableFloatStateOf(0f)
        var reversed by mutableStateOf(false)
        var clicks = 0
        compose.setContent { MaterialTheme {
            val backdrop = rememberInputAreaBackdrop()
            Box(Modifier.size(240.dp, 160.dp)) {
                Row(Modifier.fillMaxSize().inputAreaBackdropSource(backdrop, enabled = true)) {
                    Box(Modifier.weight(1f).fillMaxHeight().background(if (reversed) Color.Blue else Color.Red))
                    Box(Modifier.weight(1f).fillMaxHeight().background(if (reversed) Color.Red else Color.Blue))
                }
                Box(Modifier.align(Alignment.Center).size(160.dp, 100.dp).testTag("blur-target")) {
                    InputAreaBackdropLayer(backdrop, Modifier.matchParentSize(), InputAreaAppearance(1f, radius))
                    Text("Sharp input", Modifier.align(Alignment.BottomCenter).clickable { clicks++ })
                }
            }
        } }
        val original = compose.onNodeWithTag("blur-target").captureToImage().toPixelMap()
        compose.runOnIdle { radius = 16f }
        val blurred = compose.onNodeWithTag("blur-target").captureToImage().toPixelMap()
        val x = blurred.width / 2 - 4
        val y = blurred.height / 4
        assertTrue("Blur should mix blue into the red side", blurred[x, y].blue > original[x, y].blue + 0.1f)
        compose.onNodeWithText("Sharp input").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, clicks); reversed = true }
        val updated = compose.onNodeWithTag("blur-target").captureToImage().toPixelMap()
        assertTrue("Backdrop must update when its source changes", updated[10, y].blue > 0.8f)
        compose.runOnIdle { radius = 0f }
        compose.onNodeWithText("Sharp input").performClick()
        compose.runOnIdle { assertEquals(2, clicks) }
    }
}
