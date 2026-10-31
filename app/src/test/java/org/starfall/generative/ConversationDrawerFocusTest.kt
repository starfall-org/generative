package org.starfall.generative

import androidx.compose.runtime.getValue
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.starfall.generative.features.home.drawer.ui.ConversationSideDrawer
import org.starfall.generative.shared.model.SidebarOrganization
import org.starfall.generative.shared.model.ConversationListItem
import org.starfall.generative.shared.theme.GenerativeTheme

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w420dp-h900dp-xhdpi", application = LibraryTestApplication::class)
class ConversationDrawerFocusTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun losingSearchFocusKeepsQueryAndConversationFilterUntilExplicitClear() {
        val conversations = listOf("Alpha chat", "Beta chat").mapIndexed { index, name ->
            ConversationListItem(
                id = "chat-$index", title = name, createdAt = 1L, updatedAt = 1L,
                tokenCount = null, providerId = "", modelId = "", profileId = null,
                reasoningEffort = null, hasMessages = true, hasSummary = false
            )
        }
        var isOpen by mutableStateOf(true)
        lateinit var focusManager: FocusManager
        compose.setContent {
            focusManager = LocalFocusManager.current
            GenerativeTheme(themeMode = "LIGHT", amoledMode = false, dynamicColor = false) {
                ConversationSideDrawer(
                    conversations = conversations,
                    currentConversationId = null,
                    isOpen = isOpen,
                    organization = SidebarOrganization(),
                    onUpdateOrganization = {},
                    onDeleteConversations = {},
                    onSelectConversation = {},
                    onNewChat = {},
                    onRenameConversation = { _, _ -> },
                    onDeleteConversation = {},
                    onNavigateToSettings = {},
                    onCloseDrawer = {}
                )
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("conversation_drawer_search_button").performClick()
        compose.onNodeWithTag("conversation_drawer_search").assertIsFocused().performTextInput("Alpha")
        compose.onNodeWithText("Alpha chat").assertIsDisplayed()
        compose.onNodeWithText("Beta chat").assertDoesNotExist()

        compose.runOnIdle { focusManager.clearFocus(force = true) }
        compose.waitForIdle()
        compose.onNodeWithTag("conversation_drawer_search_button").assertIsDisplayed()
        compose.onNodeWithText("Alpha chat").assertIsDisplayed()
        compose.onNodeWithText("Beta chat").assertDoesNotExist()

        // Drawer focus restoration also must not clear a previous search.
        compose.runOnIdle { isOpen = false }
        compose.waitForIdle()
        compose.runOnIdle { isOpen = true }
        compose.waitForIdle()
        compose.onNodeWithText("Beta chat").assertDoesNotExist()
        compose.onNodeWithTag("conversation_drawer_search_button").performClick()
        compose.onNodeWithTag("conversation_drawer_search").assertTextContains("Alpha")
        compose.onNodeWithTag("conversation_drawer_search").performTextClearance()
        compose.onNodeWithText("Beta chat").assertIsDisplayed()
    }

    @Test
    fun selectionModeKeepsQuickShortcutsVisibleAboveSelectionToolbar() {
        val chat = ConversationListItem(
            id = "selected-chat", title = "A conversation whose full title is retained",
            createdAt = 1L, updatedAt = 1L, tokenCount = null,
            providerId = "", modelId = "", profileId = null,
            reasoningEffort = null, hasMessages = true, hasSummary = false
        )

        compose.setContent {
            GenerativeTheme(themeMode = "LIGHT", amoledMode = false, dynamicColor = false) {
                ConversationSideDrawer(
                    conversations = listOf(chat),
                    currentConversationId = null,
                    isOpen = true,
                    organization = SidebarOrganization(),
                    onUpdateOrganization = {},
                    onDeleteConversations = {},
                    onSelectConversation = {},
                    onNewChat = {},
                    onRenameConversation = { _, _ -> },
                    onDeleteConversation = {},
                    onNavigateToSettings = {},
                    onCloseDrawer = {}
                )
            }
        }

        compose.onNodeWithTag("conversation_drawer_quick_shortcuts").assertIsDisplayed()
        compose.onNodeWithTag("conversation_drawer_selection_bar").assertDoesNotExist()

        // Tapping a chat's leading icon opens the same selection menu as long-press.
        compose.onNodeWithTag("conversation_icon_selected-chat").performClick()
        compose.onNodeWithText("Select").performClick()
        compose.waitForIdle()

        compose.onNodeWithTag("conversation_drawer_quick_shortcuts").assertIsDisplayed()
        compose.onNodeWithTag("conversation_drawer_selection_bar").assertIsDisplayed()
        compose.onNodeWithText("A conversation whose full title is retained").assertIsDisplayed()

        compose.onNodeWithContentDescription("Exit selection").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("conversation_drawer_selection_bar").assertDoesNotExist()
        compose.onNodeWithTag("conversation_drawer_quick_shortcuts").assertIsDisplayed()
    }

    @Test
    fun openingDrawerShowsSearchButtonAndOnlyOpensSearchAfterTap() {
        var isOpen by mutableStateOf(false)

        compose.setContent {
            GenerativeTheme(themeMode = "LIGHT", amoledMode = false, dynamicColor = false) {
                ConversationSideDrawer(
                    conversations = emptyList(),
                    currentConversationId = null,
                    isOpen = isOpen,
                    organization = SidebarOrganization(),
                    onUpdateOrganization = {},
                    onDeleteConversations = {},
                    onSelectConversation = {},
                    onNewChat = {},
                    onRenameConversation = { _, _ -> },
                    onDeleteConversation = {},
                    onNavigateToSettings = {},
                    onCloseDrawer = {}
                )
            }
        }

        isOpen = true
        compose.waitForIdle()
        compose.onNodeWithTag("conversation_side_drawer").assertWidthIsEqualTo(420.dp)
        // At rest, Search, Menu and New chat share the same 52dp height; Search and New chat are 52dp squares.
        compose.onNodeWithTag("conversation_drawer_search_button")
            .assertIsDisplayed()
            .assertWidthIsEqualTo(52.dp)
            .assertHeightIsEqualTo(52.dp)
        compose.onNodeWithTag("conversation_drawer_menu_button")
            .assertIsDisplayed()
            .assertHeightIsEqualTo(52.dp)
        compose.onNodeWithTag("conversation_drawer_new_chat")
            .assertIsDisplayed()
            .assertWidthIsEqualTo(52.dp)
            .assertHeightIsEqualTo(52.dp)
        compose.onNodeWithTag("conversation_drawer_search").assertDoesNotExist()

        compose.onNodeWithTag("conversation_drawer_search_button").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("conversation_drawer_search").assertIsFocused()
        compose.onNodeWithTag("conversation_drawer_search_button").assertDoesNotExist()
        compose.onNodeWithTag("conversation_drawer_menu_button").assertDoesNotExist()
        compose.onNodeWithTag("conversation_drawer_new_chat")
            .assertIsDisplayed()
            .assertWidthIsEqualTo(42.dp)
    }
}
