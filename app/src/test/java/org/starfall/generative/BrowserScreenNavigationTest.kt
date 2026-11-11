package org.starfall.generative

import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import android.content.Context
import java.io.File
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.starfall.generative.navigation.BrowserRoutes
import org.starfall.generative.navigation.RetainedNavHost
import org.starfall.generative.navigation.browserScreens
import org.starfall.generative.shared.ui.preview.codePreviewFile
import org.starfall.generative.shared.ui.preview.createCodePreviewFile

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = LibraryTestApplication::class)
class BrowserScreenNavigationTest {
    @get:Rule val compose = createComposeRule()
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private var temporary: File? = null

    @After fun cleanUp() { temporary?.deleteRecursively() }

    @Test fun localExplorerUsesTheSameBackStackAndReturnsToItsParent() {
        lateinit var nav: NavHostController
        compose.setContent {
            nav = rememberNavController()
            RetainedNavHost(nav, startDestination = "parent") {
                composable("parent") { Text("Parent screen") }
                browserScreens(nav)
            }
        }
        compose.runOnIdle { nav.navigate(BrowserRoutes.explorer()) }
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("MediaStorage").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("MediaStorage").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(BrowserRoutes.EXPLORER, nav.currentDestination?.route)
            assertEquals("parent", nav.previousBackStackEntry?.destination?.route)
            nav.popBackStack()
        }
        compose.onNodeWithText("Parent screen").assertIsDisplayed()
    }

    @Test fun previewKeepsItsFileAcrossRestorationAndRemovesItAfterBack() {
        lateinit var nav: NavHostController
        // A directory is deliberately unreadable as a document, exercising the real unavailable
        // screen and cleanup without requiring a WebView provider in the JVM test environment.
        val file = File(context.cacheDir, "code-preview/${UUID.randomUUID()}.html")
        assertTrue(file.mkdirs())
        temporary = file
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            nav = rememberNavController()
            RetainedNavHost(nav, startDestination = "parent") {
                composable("parent") { Text("Parent screen") }
                browserScreens(nav)
            }
        }
        compose.runOnIdle { nav.navigate(BrowserRoutes.codePreview(file.name)) }
        compose.onNodeWithText(context.getString(R.string.code_preview_unavailable)).assertIsDisplayed()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText(context.getString(R.string.code_preview_unavailable)).assertIsDisplayed()
        compose.runOnIdle {
            assertTrue(file.exists())
            assertEquals(BrowserRoutes.CODE_PREVIEW, nav.currentDestination?.route)
            assertEquals("parent", nav.previousBackStackEntry?.destination?.route)
            nav.popBackStack()
        }
        compose.onNodeWithText("Parent screen").assertIsDisplayed()
        compose.waitForIdle()
        assertFalse(file.exists())
    }

    @Test fun previewDocumentsStayInThePrivateCacheAndRejectOtherPaths() {
        val file = createCodePreviewFile(context, "html", "<p>Preview content</p>")
        temporary = file
        assertEquals(file.canonicalFile, codePreviewFile(context, file.name)?.canonicalFile)
        assertTrue(file.readText().contains("<p>Preview content</p>"))
        assertNull(codePreviewFile(context, "../${file.name}"))
    }
}
