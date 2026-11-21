package org.starfall.generative.features.home.chat.ui

import kotlinx.coroutines.flow.StateFlow
import org.starfall.generative.features.home.chat.logic.input.DirectMediaRequest
import org.starfall.generative.features.home.chat.model.ActiveChatStreamState
import org.starfall.generative.features.home.chat.model.ContextWindowStatus
import org.starfall.generative.shared.model.ConversationSummaryProgress
import org.starfall.generative.shared.model.ConversationSummaryRequest
import org.starfall.generative.shared.model.StoredMessage
import org.starfall.generative.shared.model.SummaryRole
import org.starfall.generative.shared.model.language.ModelType
import org.starfall.generative.shared.model.language.ThinkingPassback
import org.starfall.generative.shared.model.provider.ModelProvider
import org.starfall.generative.shared.model.provider.ProviderGroup

/** The current backend supplies its chat data and operations through this contract. */
data class ChatBackend(
    val isGenerating: Boolean = false,
    val generatingConversationId: String? = null,
    val chatError: String? = null,
    val providers: List<ModelProvider> = emptyList(),
    val mediaProviders: List<ModelProvider>? = null,
    val providerGroups: List<ProviderGroup> = emptyList(),
    val modelPickerCollapsedGroups: Set<String> = emptySet(),
    val modelPickerCollapsedProviders: Set<String> = emptySet(),
    val onModelPickerCollapsedGroupsChange: (Set<String>) -> Unit = {},
    val onModelPickerCollapsedProvidersChange: (Set<String>) -> Unit = {},
    val selectedProviderId: String = "",
    val selectedModelName: String = "",
    val onSendMessage: (String, List<String>) -> Boolean = { _, _ -> false },
    val onSendMedia: (DirectMediaRequest) -> Boolean = { false },
    val onMediaModeChange: (ModelType?, String, String) -> Unit = { _, _, _ -> },
    val onStopGenerating: () -> Unit = {},
    val workspaceLabel: String? = null,
    val onRegenerate: (String) -> Unit = {},
    val onRetryFromError: (String) -> Unit = {},
    val onEditMessage: (messageId: String, newContent: String, files: List<String>) -> Boolean = { _, _, _ -> false },
    val onDeleteAssistantBlock: (String, Int) -> Unit = { _, _ -> },
    val onDeleteMessage: (messageId: String) -> Unit = {},
    val onDeleteMessageVersion: (messageId: String) -> Unit = {},
    val onSwitchVersion: (messageId: String, versionIndex: Int) -> Unit = { _, _ -> },
    val onSelectModel: (providerId: String, modelId: String) -> Unit = { _, _ -> },
    val summaryProgress: ConversationSummaryProgress? = null,
    val onSetReasoningEffort: (String?) -> Unit = {},
    val onSetThinkingPassback: (providerId: String, modelId: String, mode: ThinkingPassback) -> Unit = { _, _, _ -> },
    val onSetModelReasoningEffort: (providerId: String, modelId: String, effort: String?) -> Unit = { _, _, _ -> },
    val onSetModelTools: (providerId: String, modelId: String, tools: List<String>) -> Unit = { _, _, _ -> },
    val onEditModel: (providerId: String, modelId: String) -> Unit = { _, _ -> },
    val onEditProvider: (providerId: String) -> Unit = {},
    val onStartConversationSummary: (ConversationSummaryRequest) -> Boolean = { false },
    val onCancelConversationSummary: () -> Unit = {},
    val onSummaryRoleChange: (SummaryRole) -> Unit = {},
    val onDeleteSummary: () -> Unit = {},
    val onReadMessage: (String, String) -> Unit = { _, _ -> },
    val dynamicModels: Map<String, List<String>> = emptyMap(),
    val queuedMessages: List<StoredMessage> = emptyList(),
    val onEditQueuedMessage: (String, String, List<String>) -> Boolean = { _, _, _ -> false },
    val onDeleteQueuedMessage: (String) -> Unit = {},
    val loadingConversationId: String? = null,
    val contextWindowStatus: ContextWindowStatus? = null,
    val speakingMessageId: String? = null,
    val onResendUserMessage: (String) -> Boolean = { false },
    val activeStream: StateFlow<ActiveChatStreamState?>? = null,
    val onSendMessageWithSkill: (suspend (String, List<String>, Boolean, String?) -> Boolean)? = null,
    val restoreRemoteViewport: Boolean = false,
)
