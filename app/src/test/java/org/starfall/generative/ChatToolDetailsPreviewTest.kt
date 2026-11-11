package org.starfall.generative

import android.content.Context
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.util.UUID
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.starfall.generative.features.home.chat.ui.RetainedChatConversations
import org.starfall.generative.features.home.chat.ui.RetainedChatPages
import org.starfall.generative.features.home.chat.ui.rememberChatToolDetails
import org.starfall.generative.navigation.BrowserRoutes
import org.starfall.generative.navigation.RetainedNavHost
import org.starfall.generative.shared.model.Conversation
import org.starfall.generative.shared.model.ToolActivity
import org.starfall.generative.shared.subfeatures.media.logic.ToolFiles
import org.starfall.generative.shared.ui.code.LocalOpenCodePreview

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w360dp-h800dp-xhdpi", application = LibraryTestApplication::class)
class ChatToolDetailsPreviewTest {
    @get:Rule val compose = createComposeRule()
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private var file: File? = null

    @After fun cleanUp() { file?.delete() }

    @Test fun previewReturnsToTheSameToolFileAndOtherNavigationStillClosesTheSheet() {
        val source = File(ToolFiles.appStorageDirectory(context), "preview-${UUID.randomUUID()}.html")
        source.writeText("<p>Tool file preview</p>")
        file = source
        val activity = ToolActivity("tool", "inspect_source", status = "success", files = listOf(source.name))
        val conversation = Conversation("chat", "Chat", 1L, 1L)
        lateinit var nav: NavHostController
        compose.setContent {
            nav = rememberNavController()
            val entry by nav.currentBackStackEntryAsState()
            val route = entry?.destination?.route
            val retained = remember { RetainedChatPages() }
            MaterialTheme {
                CompositionLocalProvider(LocalOpenCodePreview provides { _, _ ->
                    nav.navigate(BrowserRoutes.codePreview("test"))
                }) {
                    RetainedNavHost(nav, startDestination = "chat") {
                        composable("chat") {
                            RetainedChatConversations(
                                retained, conversation, setOf(conversation.id),
                                foreground = route == "chat",
                                keepOverlayState = route == BrowserRoutes.CODE_PREVIEW
                            ) { _, _ ->
                                val state = rememberChatToolDetails("message", listOf(activity))
                                Button(onClick = { state.selectedId = activity.id }) { Text("Open tool") }
                            }
                        }
                        composable(BrowserRoutes.CODE_PREVIEW) { Text("Preview screen") }
                        composable("settings") { Text("Settings screen") }
                    }
                }
            }
        }
        compose.onNodeWithText("Open tool").performClick()
        compose.onNodeWithText(source.name).performScrollTo().performClick()
        val previewLabel = context.getString(R.string.preview_code)
        compose.waitUntil(10_000) {
            compose.onAllNodesWithContentDescription(previewLabel).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription(previewLabel).performClick()
        compose.onNodeWithText("Preview screen").assertIsDisplayed()
        compose.onNodeWithText("inspect_source").assertDoesNotExist()
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { nav.popBackStack() }
        compose.mainClock.advanceTimeBy(100)
        compose.onNodeWithContentDescription(previewLabel).assertDoesNotExist()
        compose.onNodeWithText("inspect_source").assertDoesNotExist()
        compose.mainClock.advanceTimeBy(500)
        compose.mainClock.autoAdvance = true
        compose.waitUntil(10_000) {
            compose.onAllNodesWithContentDescription(previewLabel).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription(previewLabel).assertIsDisplayed()
        compose.onNodeWithContentDescription(context.getString(R.string.media_close_viewer)).performClick()
        // The sheet's scroll position is retained as well as the selected tool and file.
        compose.onNodeWithText(source.name).assertIsDisplayed()
        compose.runOnIdle { nav.navigate("settings") }
        compose.onNodeWithText("Settings screen").assertIsDisplayed()
        compose.runOnIdle { nav.popBackStack() }
        compose.onNodeWithText("Open tool").assertIsDisplayed()
        compose.onNodeWithText("inspect_source").assertDoesNotExist()
    }

    @Test fun aDifferentChatDoesNotInheritThePreviewedToolSheet() {
        val a = Conversation("a", "A", 1L, 1L)
        val b = Conversation("b", "B", 1L, 1L)
        val activity = ToolActivity("tool", "inspect_source", status = "success")
        var current by mutableStateOf(a)
        lateinit var nav: NavHostController
        compose.setContent {
            nav = rememberNavController()
            val entry by nav.currentBackStackEntryAsState()
            val route = entry?.destination?.route
            val retained = remember { RetainedChatPages() }
            MaterialTheme {
                RetainedNavHost(nav, startDestination = "chat") {
                    composable("chat") {
                        RetainedChatConversations(
                            retained, current, setOf(a.id, b.id), foreground = route == "chat",
                            keepOverlayState = route == BrowserRoutes.CODE_PREVIEW
                        ) { page, _ ->
                            val state = rememberChatToolDetails("message", listOf(activity))
                            Button(onClick = { state.selectedId = activity.id }) {
                                Text("Open ${page?.id}")
                            }
                        }
                    }
                    composable(BrowserRoutes.CODE_PREVIEW) { Text("Preview screen") }
                }
            }
        }
        compose.onNodeWithText("Open a").performClick()
        compose.onNodeWithText("inspect_source").assertIsDisplayed()
        compose.runOnIdle { nav.navigate(BrowserRoutes.codePreview("test")) }
        compose.onNodeWithText("Preview screen").assertIsDisplayed()
        compose.runOnIdle { current = b }
        compose.runOnIdle { nav.popBackStack() }
        compose.onNodeWithText("Open b").assertIsDisplayed()
        compose.onNodeWithText("inspect_source").assertDoesNotExist()
        compose.runOnIdle { current = a }
        compose.onNodeWithText("Open a").assertIsDisplayed()
        compose.onNodeWithText("inspect_source").assertDoesNotExist()
    }
}
