package org.starfall.generative

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.starfall.generative.navigation.BrowserRoutes
import org.starfall.generative.navigation.RetainedNavHost
import org.starfall.generative.navigation.browserScreens
import org.starfall.generative.navigation.openCodePreview
import org.starfall.generative.shared.ui.code.CodeBlockSurface
import org.starfall.generative.shared.ui.code.LocalOpenCodePreview

/** Exercises the real WebView and navigation from the code-block action on Android. */
class BrowserScreensDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun codePreviewOpensInTheSameHostAndRemovesItsTemporaryDocument() {
        lateinit var nav: NavHostController
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.setContent {
            val hostContext = LocalContext.current
            nav = rememberNavController()
            MaterialTheme {
                CompositionLocalProvider(LocalOpenCodePreview provides { language, code ->
                    openCodePreview(hostContext, nav, language, code)
                }) {
                    RetainedNavHost(nav, startDestination = "parent") {
                        composable("parent") { CodeBlockSurface("html", "<p>Hello preview</p>") }
                        browserScreens(nav)
                    }
                }
            }
        }
        compose.onNodeWithContentDescription(context.getString(R.string.preview_code)).performClick()
        compose.onNodeWithText(context.getString(R.string.code_preview_title)).assertIsDisplayed()
        lateinit var file: File
        compose.runOnIdle {
            assertEquals(BrowserRoutes.CODE_PREVIEW, nav.currentDestination?.route)
            assertEquals("parent", nav.previousBackStackEntry?.destination?.route)
            file = File(context.cacheDir, "code-preview/${nav.currentBackStackEntry?.arguments?.getString("document")}")
            assertTrue(file.isFile)
        }
        compose.onNodeWithContentDescription(context.getString(R.string.common_back)).performClick()
        compose.onNodeWithContentDescription(context.getString(R.string.preview_code)).assertIsDisplayed()
        compose.waitForIdle()
        assertFalse(file.exists())
    }
}
