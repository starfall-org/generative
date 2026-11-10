package org.starfall.generative

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.starfall.generative.features.customization.ui.CustomizationScreen
import org.starfall.generative.shared.model.preferences.UserProfile

@RunWith(AndroidJUnit4::class)
class UserProfilePreviewDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun changingProfileBackgroundAndTransparencyUpdatesPreviewImmediately() {
        var profile by mutableStateOf(UserProfile(chatTransparency = 0.25f))
        var saves = 0
        compose.setContent { MaterialTheme {
            CustomizationScreen(
                profile = profile, onChange = { update ->
                    saves++
                    profile = update(profile)
                }, onBack = {}
            )
        } }

        compose.onNodeWithTag("customization-list").performScrollToKey("backgrounds")
        for (slot in listOf("chat", "drawer", "menu", "sheet", "dialog")) {
            compose.onNodeWithTag("background-setting-$slot").assertExists()
            compose.onNodeWithTag("profile-transparency-$slot").assertDoesNotExist()
        }
        compose.onNodeWithTag("background-setting-chat").performClick()
        compose.onNodeWithTag("background-dialog-chat").assertIsDisplayed()
        compose.onNodeWithTag("profile-preview-active").assertIsDisplayed()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "25%"))
        compose.onNodeWithTag("background-preview-options").assertIsDisplayed()
        compose.onNodeWithTag("profile-transparency-chat").assertIsDisplayed().performTouchInput {
            swipe(Offset(width * 0.70f, center.y), Offset(width * 0.22f, center.y), 350)
        }
        compose.runOnIdle {
            assertTrue("Slider should persist a new opacity on release", profile.chatTransparency < 0.60f)
            assertEquals("Other background transparency remains independent", 0.5f, profile.drawerTransparency)
            assertEquals(1, saves)
        }
        compose.onNodeWithTag("profile-preview-active")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,
                "${(profile.chatTransparency * 100).toInt()}%"))
        compose.onNodeWithTag("background-preview-options").performClick()
        compose.onNodeWithText(
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
                .getString(R.string.common_remove)
        ).assertExists()
        compose.onNodeWithTag("background-dialog-chat").assertExists()
    }
}
