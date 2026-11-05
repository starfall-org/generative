package org.starfall.generative

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.starfall.generative.features.providers.shared.ui.OAuthAccountCard
import org.starfall.generative.shared.model.api.ModelApiType
import org.starfall.generative.shared.ui.theme.GenerativeTheme

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = LibraryTestApplication::class)
class OAuthAccountErrorUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun devinLoginFailureShowsCopyableDiagnosticInsteadOfCrashing() {
        val details = "java.lang.IllegalStateException: OAuth foreground service unavailable\\n" +
            "    at org.starfall.generative.shared.subfeatures.auth.logic.OAuthCallbackService.start(OAuthCallbackService.kt:1)"
        compose.setContent {
            GenerativeTheme(themeMode = "LIGHT", dynamicColor = false) {
                OAuthAccountCard(
                    providerType = ModelApiType.DEVIN,
                    signedIn = false,
                    identity = null,
                    signingIn = false,
                    signingOut = false,
                    canSignIn = true,
                    canSignOut = false,
                    canCancelSignIn = false,
                    error = details,
                    onSignIn = {},
                    onCancelSignIn = {},
                    onSignOut = {}
                )
            }
        }
        compose.onNodeWithText("OAuth foreground service unavailable", substring = true).assertExists()
        compose.onNodeWithTag("error-container-copy").assertExists().performClick()
        compose.runOnIdle {
            val context = RuntimeEnvironment.getApplication()
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            assertEquals(details, clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString())
        }
    }
}
