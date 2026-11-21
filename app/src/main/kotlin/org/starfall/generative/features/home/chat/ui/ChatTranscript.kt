package org.starfall.generative.features.home.chat.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.StateFlow
import org.starfall.generative.R
import org.starfall.generative.features.home.chat.logic.input.ChatInputEditDraft
import org.starfall.generative.features.home.chat.logic.unansweredUserMessageIds
import org.starfall.generative.features.home.chat.model.ActiveChatStreamState
import org.starfall.generative.shared.model.ChatRole
import org.starfall.generative.shared.model.ConversationSummary
import org.starfall.generative.shared.model.ConversationSummaryProgress
import org.starfall.generative.shared.model.StoredMessage
import org.starfall.generative.shared.model.SummaryRole
import org.starfall.generative.shared.ui.ErrorContainer
import org.starfall.generative.shared.ui.text.PlatformTextSelectionRegion

/** Renders conversation history and owns the actions attached to its messages. */
@Composable
internal fun ChatTranscript(
    conversationId: String?,
    conversationKey: String,
    foreground: Boolean,
    conversationMessages: List<StoredMessage>,
    messages: List<StoredMessage>,
    structuralRevision: Long?,
    isTemporary: Boolean,
    isGenerating: Boolean,
    streamingHere: Boolean,
    activeStream: StateFlow<ActiveChatStreamState?>?,
    autoScroll: Boolean,
    historyLoading: Boolean,
    viewport: ChatMessageListState,
    minimapState: ConversationMinimapSideBarState,
    appBarHeight: Dp,
    bottomClearance: Dp,
    minimapBottomObscured: Dp,
    fallbackModelName: String,
    speakingMessageId: String?,
    inputAttachments: List<String>,
    toggleChatImage: (String) -> Unit,
    resendEnabled: Boolean,
    saveConversationError: String?,
    onDismissSaveError: () -> Unit,
    summary: ConversationSummary?,
    summaryProgress: ConversationSummaryProgress?,
    onCancelConversationSummary: () -> Unit,
    onSummaryRoleChange: (SummaryRole) -> Unit,
    onDeleteSummary: () -> Unit,
    onEditDraft: (ChatInputEditDraft) -> Unit,
    onResendUserMessage: (String) -> Boolean,
    onDeleteQueuedMessage: (String) -> Unit,
    onSwitchVersion: (String, Int) -> Unit,
    onRegenerate: (String) -> Unit,
    onRetryFromError: (String) -> Unit,
    onDeleteAssistantBlock: (String, Int) -> Unit,
    onReadMessage: (String, String) -> Unit,
    onDeleteMessageVersion: (String) -> Unit,
    onDeleteMessage: (String) -> Unit,
) {
    val density = LocalDensity.current
    var deletingMessageId by remember(conversationKey, foreground) { mutableStateOf<String?>(null) }
    var regeneratingMessageId by remember(conversationKey, foreground) { mutableStateOf<String?>(null) }
    var showSummaryDialog by remember(conversationKey, foreground) { mutableStateOf(false) }
    val unversionedMessages = conversationMessages.takeIf { structuralRevision == null }
    fun resumeFollowing() = viewport.resume()
    val copyLabel = stringResource(R.string.common_copy)
    val copiedLabel = stringResource(R.string.common_copied_to_clipboard)
    val context = LocalContext.current
    val lastMessage = conversationMessages.lastOrNull()
    val streamingMessage = if (streamingHere) conversationMessages.lastOrNull { it.role == ChatRole.MODEL } else null
    val lastResponseState = conversationMessages.lastOrNull()?.activeVersion
    val unansweredMessages = remember(
        conversationKey,
        structuralRevision,
        unversionedMessages,
        lastResponseState?.generationError,
        lastResponseState?.generationInterrupted,
        lastResponseState?.processingFinishedAt
    ) { unansweredUserMessageIds(conversationMessages) }
    fun whenIdle(action: () -> Unit) {
        if (!isGenerating) action()
    }

    if (messages.isEmpty() && saveConversationError == null) {
        ChatOpeningVeil(
            active = historyLoading,
            modifier = Modifier.fillMaxSize().padding(top = appBarHeight + 8.dp, bottom = bottomClearance)
        ) {
            ChatWelcome(
                isTemporary = isTemporary,
                foreground = foreground,
                modifier = Modifier.fillMaxSize()
            )
            if (isGenerating && !streamingHere) {
                Text(
                    stringResource(R.string.chat_error_other_conversation),
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 8.dp, start = 12.dp, end = 12.dp),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    } else {
        PlatformTextSelectionRegion {
            ChatMessageList(
                state = viewport,
                messages = messages,
                streaming = streamingHere,
                autoScroll = autoScroll,
                historyLoading = historyLoading,
                activeStream = activeStream,
                appBarHeight = appBarHeight + 8.dp,
                bottomClearance = bottomClearance,
                modifier = Modifier.fillMaxSize(),
                footer = if ((isGenerating && !streamingHere) || saveConversationError != null) {
                    {
                        if (isGenerating && !streamingHere) {
                            Text(stringResource(R.string.chat_error_other_conversation),
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                style = MaterialTheme.typography.bodySmall)
                        }
                        saveConversationError?.let { error ->
                            ErrorContainer(
                                message = error,
                                onDismiss = onDismissSaveError,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)
                                    .testTag("chat-save-error")
                            )
                        }
                    }
                } else null
            ) { msg ->
                val isLast = msg.id == lastMessage?.id
                if (msg.role == ChatRole.USER) {
                    UserMessageCard(
                        message = msg,
                        conversationId = conversationId,
                        cacheEnabled = !isTemporary,
                        selectedImageAttachments = inputAttachments,
                        onToggleChatImage = toggleChatImage,
                        onResend = if (msg.id in unansweredMessages) rememberChatAction {
                            if (onResendUserMessage(msg.id)) resumeFollowing()
                        } else null,
                        onResendFromMenu = if (!msg.isQueued) rememberChatAction {
                            if (onResendUserMessage(msg.id)) resumeFollowing()
                        } else null,
                        resendEnabled = resendEnabled,
                        onEdit = rememberChatValueAction<String> {
                            onEditDraft(
                                ChatInputEditDraft(
                                    messageId = msg.id,
                                    text = msg.content,
                                    attachments = msg.files,
                                    isQueued = msg.isQueued,
                                    revision = System.nanoTime(),
                                )
                            )
                        },
                        onDelete = rememberChatValueAction<String> {
                            if (msg.isQueued) {
                                onDeleteQueuedMessage(msg.id)
                            } else {
                                deletingMessageId = msg.id
                            }
                        },
                        onSwitchVersion = rememberChatValueAction { newIdx: Int ->
                            if (!msg.isQueued) {
                                whenIdle { onSwitchVersion(msg.id, newIdx) }
                            }
                        },
                        modifier = if (msg.isQueued) Modifier.alpha(0.5f) else Modifier
                    )
                } else {
                    AssistantMessageCard(
                        message = msg,
                        isReading = speakingMessageId == msg.id,
                        fallbackModelName = fallbackModelName,
                        selectedImageAttachments = inputAttachments,
                        onToggleChatImage = toggleChatImage,
                        isStreaming = streamingHere && msg.id == streamingMessage?.id,
                        conversationId = conversationId,
                        cacheEnabled = !isTemporary,
                        streamStateFlow = activeStream,
                        onCopy = rememberChatAction {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText(copyLabel, assistantCopyText(msg)))
                            Toast.makeText(context, copiedLabel, Toast.LENGTH_SHORT).show()
                        },
                        onRegenerate = rememberChatAction {
                            whenIdle {
                                if (!isLast) regeneratingMessageId = msg.id
                                else onRegenerate(msg.id)
                            }
                        },
                        onRetryError = if (isLast) {
                            rememberChatAction { whenIdle { onRetryFromError(msg.id) } }
                        } else null,
                        onEdit = rememberChatAction {
                            onEditDraft(
                                ChatInputEditDraft(
                                    messageId = msg.id,
                                    text = assistantCopyText(msg),
                                    attachments = msg.files,
                                    revision = System.nanoTime(),
                                )
                            )
                        },
                        onDeleteBlock = rememberChatValueAction<Int> { block ->
                            whenIdle { onDeleteAssistantBlock(msg.id, block) }
                        },
                        onDelete = rememberChatAction {
                            deletingMessageId = msg.id
                        },
                        onRead = rememberChatAction {
                            onReadMessage(msg.id, msg.content.ifBlank { msg.reasoningContent.orEmpty() })
                        },
                        onSwitchVersion = rememberChatValueAction { newIdx: Int ->
                            whenIdle { onSwitchVersion(msg.id, newIdx) }
                        }
                    )
                }

                val progressHere = summaryProgress?.takeIf {
                    it.conversationId == conversationId && it.throughMessageId == msg.id
                }
                val summaryHere = summary?.takeIf {
                    it.throughMessageId == msg.id && progressHere == null
                }
                if (progressHere != null || summaryHere != null) {
                    ConversationSummaryDivider(
                        summary = summaryHere,
                        progress = progressHere,
                        onOpenSummary = { showSummaryDialog = true },
                        onCancelSummary = onCancelConversationSummary,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                }
            }
        }
    }

    ConversationMinimapSideBar(
        conversationKey = conversationKey,
        messages = messages,
        listState = viewport.listState,
        topObscuredPx = with(density) { appBarHeight.roundToPx() },
        bottomObscuredPx = with(density) { minimapBottomObscured.roundToPx() },
        // Dragging the thumb is manual scrolling: it stops following and resumes at the end.
        onScrub = viewport::manualScroll,
        state = minimapState
    )

    if (foreground && showSummaryDialog) {
        summary?.let { summary ->
            ConversationSummaryDialog(
                summary = summary,
                onRoleChange = onSummaryRoleChange,
                onDelete = onDeleteSummary,
                onDismiss = { showSummaryDialog = false }
            )
        } ?: run { showSummaryDialog = false }
    }

    if (foreground && regeneratingMessageId != null) {
        RegenerateResponseDialog(
            onConfirm = {
                regeneratingMessageId?.let(onRegenerate)
                regeneratingMessageId = null
            },
            onDismiss = { regeneratingMessageId = null }
        )
    }

    // Delete message/version confirmation dialog
    val deletingMessage = deletingMessageId?.let { id -> messages.firstOrNull { it.id == id } }
    if (foreground && deletingMessage != null) {
        DeleteMessageDialog(
            message = deletingMessage,
            onDeleteVersion = {
                onDeleteMessageVersion(deletingMessage.id)
                deletingMessageId = null
            },
            onDeleteAll = {
                onDeleteMessage(deletingMessage.id)
                deletingMessageId = null
            },
            onDismiss = { deletingMessageId = null }
        )
    }
}
