package org.starfall.generative.navigation

import androidx.compose.material3.DrawerState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.starfall.generative.database.preferences.AppPreferences
import org.starfall.generative.features.home.chat.logic.ChatViewModel
import org.starfall.generative.features.home.chat.ui.ChatAttachmentSelection
import org.starfall.generative.features.home.chat.ui.RetainedChatPages
import org.starfall.generative.features.home.drawer.ui.ConversationSideDrawer
import org.starfall.generative.features.machines.shared.logic.RemoteMachinesViewModel
import org.starfall.generative.features.settings.logic.SettingsViewModel
import org.starfall.generative.shared.model.ConversationListItem
import org.starfall.generative.shared.model.RemoteMachine
import org.starfall.generative.shared.model.provider.ModelProvider

/** Longest a chat selection waits for the drawer to finish closing before it is applied anyway. */
private const val DRAWER_CLOSE_WAIT_MS = 700L

/** Navigation callbacks and their root coroutine scope outlive the drawer's close animation. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NavigationDrawerContent(
    viewModel: ChatViewModel,
    settingsViewModel: SettingsViewModel,
    remoteMachinesViewModel: RemoteMachinesViewModel,
    drawerState: DrawerState,
    coroutineScope: CoroutineScope,
    conversations: List<ConversationListItem>,
    providers: List<ModelProvider>,
    currentConvId: String?,
    generatingConversationIds: Set<String>,
    appPrefs: AppPreferences,
    chatAttachments: ChatAttachmentSelection,
    retainedChats: RetainedChatPages,
    navigate: (AppDestination) -> Unit,
    startNewChat: () -> Unit,
    openConfiguration: (AppDestination) -> Unit,
    openTerminal: (RemoteMachine) -> Unit,
    onOpenMediaStorage: () -> Unit,
    onPortForward: (RemoteMachine) -> Unit
) {
    ConversationSideDrawer(
        conversations = conversations,
        providers = providers,
        currentConversationId = currentConvId,
        isOpen = drawerState.isOpen,
        generatingConversationIds = generatingConversationIds,
        organization = appPrefs.chatRelations.sidebar,
        backgroundImage = appPrefs.userProfile.drawerBackground,
        backgroundTransparency = appPrefs.userProfile.drawerTransparency,
        onUpdateOrganization = settingsViewModel::updateSidebar,
        onDeleteConversations = { ids ->
            viewModel.deleteConversations(ids, chatAttachments.attachments.value)
            retainedChats.discard(ids)
        },
        onSelectConversation = { item ->
            navigate(AppDestination.CHAT)
            // Switching chats composes the whole history. Doing that while the drawer is still
            // sliding away makes the close animation drop frames, so the switch waits for the
            // drawer to settle (bounded, in case it is reopened or never reports closed).
            coroutineScope.launch {
                withTimeoutOrNull(DRAWER_CLOSE_WAIT_MS) {
                    snapshotFlow { drawerState.isClosed }.first { it }
                }
                viewModel.selectConversation(item.id)
            }
        },
        onNewChat = {
            startNewChat()
        },
        onRenameConversation = { id, newTitle ->
            viewModel.renameConversation(id, newTitle)
        },
        onSuggestConversationTitle = viewModel::suggestConversationTitle,
        onDeleteConversation = {
            viewModel.deleteConversations(setOf(it), chatAttachments.attachments.value)
            retainedChats.discard(setOf(it))
        },
        onNavigateToSettings = {
            navigate(AppDestination.FEATURES)
        },
        featuresLogoImage = appPrefs.featuresLogoImage,
        onCloseDrawer = {
            coroutineScope.launch { drawerState.close() }
        },
        onOpenMediaStorage = {
            coroutineScope.launch {
                drawerState.close()
                onOpenMediaStorage()
            }
        },
        onOpenRemoteMachines = {
            coroutineScope.launch { drawerState.close() }
            openConfiguration(AppDestination.REMOTE_MACHINES)
        },
        onPortForward = { machine ->
            onPortForward(machine)
            coroutineScope.launch { drawerState.close() }
        },
        onOpenTerminal = { machine ->
            coroutineScope.launch { drawerState.close() }
            openTerminal(machine)
        },
        onSaveMachineTools = remoteMachinesViewModel::saveMachineTools
    )
}
