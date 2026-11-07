package org.starfall.generative.navigation

import android.widget.Toast
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CancellationException
import org.starfall.generative.R
import org.starfall.generative.features.home.chat.logic.ChatViewModel
import org.starfall.generative.features.home.chat.ui.RetainedChatPages
import org.starfall.generative.features.providers.shared.logic.ConfigurationViewModel
import org.starfall.generative.shared.subfeatures.media.logic.ToolFiles

/** Handle each external request once, preserving the current chat draft and attachment selection. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HandleExternalNavigationRequests(
    pendingAppDeepLink: AppDeepLinkRequest?,
    onAppDeepLinkHandled: (Long) -> Unit,
    pendingExternalShare: ExternalShareRequest?,
    onExternalShareHandled: (Long) -> Unit,
    viewModel: ChatViewModel,
    configurationViewModel: ConfigurationViewModel,
    toolFiles: ToolFiles,
    retainedChats: RetainedChatPages,
    drawerState: DrawerState,
    navigate: (AppDestination) -> Unit,
    clearMcpEditor: () -> Unit
) {
    val context = LocalContext.current
    LaunchedEffect(pendingAppDeepLink?.id) {
        val request = pendingAppDeepLink ?: return@LaunchedEffect
        when (val action = request.action) {
            AppDeepLink.Providers -> navigate(AppDestination.PROVIDERS)
            is AppDeepLink.Mcp -> {
                clearMcpEditor()
                navigate(AppDestination.MCP)
                if (action.name != null && action.url != null) {
                    try {
                        configurationViewModel.importMcpLink(action.name, action.url)
                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        Toast.makeText(context, R.string.save_change_failed, Toast.LENGTH_LONG).show()
                    }
                }
            }
            is AppDeepLink.Chat -> {
                if (action.temporary) viewModel.startTemporaryChat() else viewModel.startNewChat()
                navigate(AppDestination.CHAT)
                if (!viewModel.sendMessage(action.text)) {
                    Toast.makeText(context, R.string.save_change_failed, Toast.LENGTH_LONG).show()
                }
            }
        }
        onAppDeepLinkHandled(request.id)
    }

    LaunchedEffect(pendingExternalShare?.id) {
        val request = pendingExternalShare ?: return@LaunchedEffect
        // Share to the current chat, without replacing the current draft or sending it.
        drawerState.close()
        navigate(AppDestination.CHAT)
        // If there is no active chat (e.g. first launch), create a stable blank draft
        // before the asynchronous last-conversation restore can replace the selection.
        if (viewModel.currentConversation.value == null) viewModel.startNewChat()
        val destination = viewModel.currentConversation.value
        val selection = retainedChats.selection(destination?.id)
        selection.requestChatMode()
        request.content.text?.let(selection::queueExternalText)
        val temporaryId = destination?.takeIf { it.isTemporary }?.id
        var failed = false
        try {
            request.content.streams.forEach { uri ->
                try {
                    val file = stageSharedAttachment(
                        context, toolFiles, uri, request.content.fallbackMime, temporaryId
                    )
                    selection.addAttachments(listOf(file))
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    failed = true
                }
            }
            if (failed) Toast.makeText(context, R.string.shared_attachment_failed, Toast.LENGTH_LONG).show()
        } finally {
            onExternalShareHandled(request.id)
        }
    }
}
