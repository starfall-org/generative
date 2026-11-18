package org.starfall.generative

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.starfall.generative.features.home.drawer.ui.ConversationSideDrawer
import org.starfall.generative.shared.model.SidebarOrganization
import org.starfall.generative.shared.ui.theme.GenerativeTheme

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w420dp-h900dp-xhdpi", application = LibraryTestApplication::class)
class ConversationDrawerProjectActionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun projectActionReplacesFolderCreationWhenProvidedByHost() {
        var opened = 0
        compose.setContent {
            GenerativeTheme(themeMode = "LIGHT", amoledMode = false, dynamicColor = false) {
                ConversationSideDrawer(
                    conversations = emptyList(), currentConversationId = null, isOpen = true,
                    organization = SidebarOrganization(), onUpdateOrganization = {}, onDeleteConversations = {},
                    onSelectConversation = {}, onNewChat = {}, onRenameConversation = { _, _ -> },
                    onDeleteConversation = {}, onNavigateToSettings = {}, onCloseDrawer = {},
                    onOpenProject = { opened++ },
                )
            }
        }
        compose.onNodeWithTag("conversation_drawer_list").performTouchInput {
            longClick(Offset(center.x, height * 0.85f))
        }
        compose.onNodeWithContentDescription("Open project").performClick()
        compose.runOnIdle { assertEquals(1, opened) }
    }
}
