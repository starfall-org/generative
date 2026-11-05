package org.starfall.generative

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.starfall.generative.features.home.drawer.ui.ConversationSideDrawer
import org.starfall.generative.shared.model.ConversationListItem
import org.starfall.generative.shared.model.SidebarOrganization
import org.starfall.generative.shared.ui.theme.GenerativeTheme

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w420dp-h900dp-xhdpi", application = LibraryTestApplication::class)
class ConversationDrawerGeneratingIndicatorTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun generatingChat_replacesCircularProgressIndicatorWithSpinningLeadingIconBorder() {
        val modelChat = ConversationListItem(
            id = "c1",
            title = "Model Chat",
            createdAt = 1L,
            updatedAt = 20L,
            tokenCount = null,
            providerId = "p1",
            modelId = "gpt-4o",
            profileId = null,
            reasoningEffort = null,
            hasMessages = true,
            hasSummary = false
        )
        val fallbackChat = ConversationListItem(
            id = "c2",
            title = "Fallback Chat",
            createdAt = 1L,
            updatedAt = 10L,
            tokenCount = null,
            providerId = "",
            modelId = "",
            profileId = null,
            reasoningEffort = null,
            hasMessages = false,
            hasSummary = false
        )

        var generatingIds by mutableStateOf(setOf("c1"))
        var selectedChatId: String? = null

        compose.setContent {
            GenerativeTheme(darkTheme = false, amoledMode = false) {
                ConversationSideDrawer(
                    conversations = listOf(modelChat, fallbackChat),
                    currentConversationId = "c1",
                    isOpen = true,
                    generatingConversationIds = generatingIds,
                    organization = SidebarOrganization(),
                    onUpdateOrganization = {},
                    onDeleteConversations = {},
                    onSelectConversation = { selectedChatId = it.id },
                    onNewChat = {},
                    onRenameConversation = { _, _ -> },
                    onDeleteConversation = {},
                    onNavigateToSettings = {},
                    onCloseDrawer = {}
                )
            }
        }

        // No trailing CircularProgressIndicator is rendered anywhere in the drawer.
        compose.onAllNodes(hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate))
            .assertCountEquals(0)

        // Generating chat shows the spinning border on its 36.dp leading icon; idle chat does not.
        compose.onNodeWithTag("conversation_generating_icon_c1")
            .assertIsDisplayed()
            .assertWidthIsEqualTo(36.dp)
            .assertHeightIsEqualTo(36.dp)
        compose.onNodeWithTag("conversation_icon_c2")
            .assertIsDisplayed()
            .assertWidthIsEqualTo(36.dp)
            .assertHeightIsEqualTo(36.dp)

        // Advancing animation time keeps the icon container bounds steady.
        compose.mainClock.advanceTimeBy(500L)
        compose.waitForIdle()
        compose.onNodeWithTag("conversation_generating_icon_c1")
            .assertWidthIsEqualTo(36.dp)
            .assertHeightIsEqualTo(36.dp)

        // Touching outside the 18.dp CircleShape arc of the leading icon does not open context menu.
        compose.onNodeWithTag("conversation_generating_icon_c1").performTouchInput {
            click(Offset(1.dp.toPx(), 1.dp.toPx()))
        }
        compose.waitForIdle()
        compose.onNodeWithText("Rename").assertDoesNotExist()
        assertEquals("c1", selectedChatId)

        // Touching inside the circular leading icon opens the conversation context menu.
        compose.onNodeWithTag("conversation_generating_icon_c1").performTouchInput {
            click(center)
        }
        compose.waitForIdle()
        compose.onNodeWithText("Rename").assertIsDisplayed()

        // Switching generation to the fallback-icon chat moves the spinning border there cleanly.
        generatingIds = setOf("c2")
        compose.waitForIdle()
        compose.onNodeWithTag("conversation_icon_c1").assertIsDisplayed()
        compose.onNodeWithTag("conversation_generating_icon_c2")
            .assertIsDisplayed()
            .assertWidthIsEqualTo(36.dp)
            .assertHeightIsEqualTo(36.dp)
        compose.onAllNodes(hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate))
            .assertCountEquals(0)
    }
}
