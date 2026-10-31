package org.starfall.generative

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.starfall.generative.features.providers.shared.ui.GeminiCliProjectIdField
import org.starfall.generative.features.providers.shared.ui.OAuthAccountCard
import org.starfall.generative.shared.model.api.ModelApiType

/** Verify provider sign-in affordances on device without opening an external browser. */
@RunWith(AndroidJUnit4::class)
class GeminiCliDevinOAuthUiDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun geminiCliProjectIdEditorPersistsTypedInput() {
        var project by mutableStateOf("")
        compose.setContent { MaterialTheme { GeminiCliProjectIdField(project) { project = it } } }
        compose.onNodeWithTag("gemini-cli-project-id").assertIsDisplayed().performTextInput("my-project-123")
        compose.onNodeWithTag("gemini-cli-project-id").assertTextContains("my-project-123")
        compose.runOnIdle { assertEquals("my-project-123", project) }
    }

    @Test fun bothProvidersExposeExplicitSignInAndCancelControls() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        var type by mutableStateOf(ModelApiType.GEMINI_CLI)
        var pressed by mutableStateOf(false)
        compose.setContent { MaterialTheme {
            OAuthAccountCard(
                providerType = type, signedIn = false, identity = null, signingIn = pressed,
                signingOut = false, canSignIn = true, canSignOut = false,
                canCancelSignIn = true, error = null,
                onSignIn = { pressed = true }, onCancelSignIn = { pressed = false }, onSignOut = {}
            )
        } }
        repeat(2) { round ->
            compose.onNodeWithText(context.getString(R.string.oauth_sign_in)).assertIsDisplayed().performClick()
            compose.runOnIdle { assertTrue(pressed) }
            compose.onNodeWithText(context.getString(R.string.common_cancel)).assertIsDisplayed().performClick()
            compose.runOnIdle {
                assertTrue(!pressed)
                if (round == 0) type = ModelApiType.DEVIN
            }
        }
    }
}
