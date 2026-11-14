package org.starfall.generative

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.geometry.Offset
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.starfall.generative.shared.model.provider.ModelProvider
import org.starfall.generative.shared.model.provider.ProviderGroup
import org.starfall.generative.shared.model.provider.ProviderPlacement
import org.starfall.generative.shared.model.api.ModelApiType
import org.starfall.generative.features.providers.list.ui.ProvidersScreen
import org.starfall.generative.navigation.RetainedNavHost

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w360dp-h1000dp-xhdpi")
class ProviderFolderContainerTest {
    @get:Rule val compose = createComposeRule()

    private val group = ProviderGroup("g", "Folder", sortOrder = 1)
    private val providers = listOf(
        ModelProvider("root", "Root", ModelApiType.OPENAI, baseUrl = "", sortOrder = 0),
        ModelProvider("member-a", "Member A", ModelApiType.OPENAI, baseUrl = "", groupId = "g", sortOrder = 0),
        ModelProvider("member-b", "Member B", ModelApiType.OPENAI, baseUrl = "", groupId = "g", sortOrder = 1),
        ModelProvider("tail", "Tail", ModelApiType.OPENAI, baseUrl = "", sortOrder = 2)
    )

    private fun showFolder(
        source: List<ModelProvider> = providers,
        writes: MutableList<ProviderPlacement> = mutableListOf(),
        groups: List<ProviderGroup> = listOf(group),
        retainScreenOnEditor: Boolean = false
    ) {
        compose.setContent {
            MaterialTheme {
                var editorOpened by remember { mutableStateOf(false) }
                var gridView by remember { mutableStateOf(true) }
                if (editorOpened) {
                    Text("Provider editor", modifier = androidx.compose.ui.Modifier.testTag("provider_editor"))
                } else {
                    ProvidersScreen(
                        providers = source,
                        providerGroups = groups,
                        isGridView = gridView,
                        onToggleGridView = { gridView = it },
                        onOpenProviderEditor = { _, _ -> editorOpened = !retainScreenOnEditor },
                        onDeleteProvider = {},
                        onReorderProviders = {},
                        onBack = {},
                        onPlaceProvider = { placement ->
                            writes += placement
                            Result.success(Unit)
                        }
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun folderIsAStandaloneRootTileAndDoesNotExpandIntoTheMainGrid() {
        showFolder()

        compose.onNodeWithTag("provider_group_g", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("provider_group_icon_g", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("provider_member-a", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("provider_member-b", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("provider_root", useUnmergedTree = true).assertExists()
    }

    @Test
    fun clickingFolderOpensFullScreenPageWithItsOwnHeaderAndToolbar() {
        showFolder()

        compose.onNodeWithTag("provider_group_g", useUnmergedTree = true).performClick()
        compose.onNodeWithTag("provider_folder_screen_g", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("provider_folder_provider_member-a", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("provider_folder_provider_member-b", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("provider_folder_header_g", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("provider_folder_header_icon_g", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("provider_folder_header_name_g", useUnmergedTree = true).assertTextEquals("Folder")
        compose.onNodeWithTag("provider_folder_toolbar_g", useUnmergedTree = true).assertExists()
        val menuLeft = compose.onNode(
            hasContentDescription("More options") and hasAnyAncestor(hasTestTag("provider_folder_toolbar_g")),
            useUnmergedTree = true
        )
            .fetchSemanticsNode().boundsInRoot.left
        val addLeft = compose.onNodeWithTag("add_folder_provider", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot.left
        assertTrue("Folder menu must be left of Add", menuLeft < addLeft)
        compose.onNodeWithContentDescription("Close").assertDoesNotExist()
        val headerBottom = compose.onNodeWithTag("provider_folder_header_g").fetchSemanticsNode().boundsInRoot.bottom
        val gridTop = compose.onNodeWithTag("provider_folder_grid_g").fetchSemanticsNode().boundsInRoot.top
        assertTrue("Provider list must start below the folder's own header", gridTop >= headerBottom)
    }

    @Test
    fun searchShowsMatchingMembersDirectlyWithoutFolders() {
        showFolder()
        compose.onNodeWithTag("provider_search_expand").performClick()
        compose.onNodeWithTag("provider_search").performTextInput("Member")
        compose.onNodeWithTag("provider_group_g").assertDoesNotExist()
        compose.onNodeWithTag("provider_member-a").assertExists()
        compose.onNodeWithTag("provider_member-b").assertExists()
        compose.onNodeWithTag("provider_root").assertDoesNotExist()
    }

    @Test
    fun commonAddButtonCreatesAProviderForTheOpenFolder() {
        showFolder()
        compose.onNodeWithTag("provider_group_g").performClick()
        compose.onNodeWithTag("add_folder_provider", useUnmergedTree = true).performClick()
        compose.onNodeWithTag("provider_folder_screen_g").assertDoesNotExist()
    }

    @Test
    fun reopeningFolderAfterRetainedEditorStillAnimates() {
        showFolder(retainScreenOnEditor = true)
        compose.onNodeWithTag("provider_group_g").performClick()
        compose.onNodeWithTag("add_folder_provider", useUnmergedTree = true).performClick()
        val rootBounds = compose.onRoot().fetchSemanticsNode().boundsInRoot
        compose.onRoot().performTouchInput {
            click(Offset(rootBounds.width / 2f, rootBounds.height - 48f))
        }
        compose.waitForIdle()

        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("provider_group_g").performClick()
        compose.mainClock.advanceTimeBy(50)
        val openingWidth = compose.onNodeWithTag("provider_folder_screen_g", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot.width
        compose.mainClock.advanceTimeBy(300)
        val openWidth = compose.onNodeWithTag("provider_folder_screen_g", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot.width
        assertTrue("A newly opened folder must expand from its tile", openingWidth < openWidth)
    }

    @Test
    fun editorNavigationRetainsTheOpenFolderAndProvidersComposition() {
        lateinit var nav: NavHostController
        var mounts = 0
        var disposals = 0
        compose.setContent {
            nav = rememberNavController()
            MaterialTheme {
                RetainedNavHost(nav, startDestination = "providers") {
                    composable("providers") {
                        DisposableEffect(Unit) {
                            mounts++
                            onDispose { disposals++ }
                        }
                        ProvidersScreen(
                            providers = providers,
                            providerGroups = listOf(group),
                            isGridView = true,
                            onOpenProviderEditor = { _, isNew ->
                                nav.navigate(if (isNew) "providers/new" else "providers/edit/member-a")
                            },
                            onDeleteProvider = {},
                            onReorderProviders = {},
                            onBack = {}
                        )
                    }
                    composable("providers/new") { Text("New editor", Modifier.testTag("provider_editor")) }
                    composable("providers/edit/{providerId}") { Text("Edit editor", Modifier.testTag("provider_editor")) }
                }
            }
        }
        compose.onNodeWithTag("provider_group_g").performClick()
        listOf("add_folder_provider", "provider_folder_provider_member-a").forEach { entryTag ->
            compose.onNodeWithTag(entryTag, useUnmergedTree = true).performClick()
            compose.onNodeWithTag("provider_editor").assertIsDisplayed()
            compose.runOnIdle {
                assertEquals(1, mounts)
                assertEquals(0, disposals)
                nav.popBackStack()
            }
            compose.onNodeWithTag("provider_folder_screen_g", useUnmergedTree = true).assertIsDisplayed()
            compose.onNodeWithTag("provider_folder_provider_member-a", useUnmergedTree = true).assertExists()
            compose.runOnIdle {
                assertEquals(1, mounts)
                assertEquals(0, disposals)
            }
        }
    }

    @Test
    fun folderToolbarSwitchesBetweenGridAndListWithoutClosingTheFolder() {
        showFolder()
        compose.onNodeWithTag("provider_group_g").performClick()
        compose.onNodeWithContentDescription("Switch to List View").performClick()
        compose.onNodeWithTag("provider_folder_screen_g").assertExists()
        compose.onNodeWithContentDescription("Switch to Grid View").assertExists()
        compose.onNodeWithContentDescription("Switch to Grid View").performClick()
        compose.onNodeWithContentDescription("Switch to List View").assertExists()
    }

    @Test
    fun droppingAProviderOnFolderMovesItInWithoutOpeningTheFolder() {
        val writes = mutableListOf<ProviderPlacement>()
        showFolder(
            source = listOf(
                ModelProvider("source", "Source", ModelApiType.OPENAI, baseUrl = "", sortOrder = 0),
                ModelProvider("member", "Member", ModelApiType.OPENAI, baseUrl = "", groupId = "g", sortOrder = 0)
            ),
            writes = writes
        )
        val sourceCenter = compose.onNodeWithTag("provider_source").fetchSemanticsNode().boundsInRoot.center
        val folderCenter = compose.onNodeWithTag("provider_group_g").fetchSemanticsNode().boundsInRoot.center

        compose.onRoot().performTouchInput { down(sourceCenter) }
        compose.mainClock.advanceTimeBy(600)
        compose.onRoot().performTouchInput { moveTo(folderCenter) }
        compose.mainClock.advanceTimeBy(200)
        compose.onRoot().performTouchInput { up() }
        compose.waitForIdle()

        assertEquals("g", writes.single().groupId)
        assertEquals(listOf("member", "source"), writes.single().groupOrders.getValue("g"))
        compose.onNodeWithTag("provider_folder_screen_g", useUnmergedTree = true).assertDoesNotExist()
    }
    @Test
    fun nextFolderOpensBeforePreviousFolderCloseAnimationFinishes() {
        val sibling = ProviderGroup("sibling", "Sibling", sortOrder = 3)
        showFolder(groups = listOf(group, sibling))
        val siblingCenter = compose.onNodeWithTag("provider_group_sibling")
            .fetchSemanticsNode().boundsInRoot.center
        compose.onNodeWithTag("provider_group_g").performClick()
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("provider_folder_header_gesture_g").performTouchInput { swipeRight() }
        compose.mainClock.advanceTimeByFrame()
        compose.onRoot().performTouchInput { click(siblingCenter) }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithTag("provider_folder_screen_sibling", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("provider_folder_screen_g", useUnmergedTree = true).assertExists()
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithTag("provider_folder_screen_g", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("provider_folder_screen_sibling", useUnmergedTree = true).assertExists()
    }

}
