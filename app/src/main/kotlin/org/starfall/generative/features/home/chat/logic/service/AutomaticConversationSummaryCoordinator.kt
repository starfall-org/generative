package org.starfall.generative.features.home.chat.logic.service

import kotlinx.coroutines.flow.StateFlow
import org.starfall.generative.features.home.chat.logic.estimateMessageTokens
import org.starfall.generative.shared.model.Conversation
import org.starfall.generative.shared.model.ConversationSummaryProgress
import org.starfall.generative.shared.model.ConversationSummaryRequest
import org.starfall.generative.shared.model.StoredMessage
import org.starfall.generative.shared.model.language.ModelType
import org.starfall.generative.shared.model.provider.ModelProvider
import org.starfall.generative.shared.model.preferences.AppPreferences

/** Applies the token-threshold and duplicate-attempt policy for automatic conversation summaries. */
internal class AutomaticConversationSummaryCoordinator(
    private val isGenerating: StateFlow<Boolean>,
    private val isGenerationReplacementActive: () -> Boolean,
    private val isSummaryActive: () -> Boolean,
    private val pendingWrites: () -> Int,
    private val isRestoreInProgress: () -> Boolean = { false },
    private val currentConversation: StateFlow<Conversation?>,
    private val appPreferences: StateFlow<AppPreferences>,
    private val providers: StateFlow<List<ModelProvider>>,
    private val modelContext: ChatModelContextCoordinator,
    private val startSummary: (ConversationSummaryRequest) -> Boolean,
) {
    private var lastAttempt: String? = null

    fun maybeStart(incoming: StoredMessage? = null): Boolean {
        if (isRestoreInProgress() || isGenerating.value || isGenerationReplacementActive() ||
            isSummaryActive() || pendingWrites() > 0
        ) {
            return false
        }
        val current = currentConversation.value ?: return false
        val preferences = appPreferences.value
        if (!preferences.autoSummaryEnabled) return false
        val model = providers.value.find { it.id == preferences.selectedModel.providerId }
            ?.config?.modelConfigs?.get(preferences.selectedModel.modelId) ?: return false
        val status = modelContext.statusFor(current, preferences, model, incoming) ?: return false
        val additional = incoming?.let { estimateMessageTokens(it, false) } ?: 0
        val projected = status.copy(
            estimatedTokens = status.usedTokens + additional.coerceAtMost(Long.MAX_VALUE - status.usedTokens),
            estimatedAdditionalTokens = status.estimatedAdditionalTokens +
                additional.coerceAtMost(Long.MAX_VALUE - status.estimatedAdditionalTokens)
        )
        if (model.modelType != ModelType.TEXT_GENERATION ||
            !projected.reachedPercent(preferences.autoSummaryPercent)
        ) return false
        val boundary = current.messages.lastOrNull()?.id ?: return false
        if (current.summary?.throughMessageId == boundary) return false
        val attempt = "${current.id}/$boundary/${current.summary?.id}/" +
            "${preferences.selectedModel.providerId}/${preferences.selectedModel.modelId}"
        if (lastAttempt == attempt) return false

        val helper = modelContext.summaryTextModel() ?: return false
        val helperWindow = modelContext.helperContextWindowTokens(helper)
        lastAttempt = attempt
        return startSummary(ConversationSummaryRequest(
            targetTokens = minOf(1200, (helperWindow / 8).coerceAtLeast(1)),
            chunked = true,
            tokensPerChunk = minOf(20_000, (helperWindow * 0.6).toInt().coerceAtLeast(1))
        ))
    }

    fun recordCancellation(
        progress: ConversationSummaryProgress?,
        current: Conversation?,
        preferences: AppPreferences,
    ) {
        if (progress == null || current?.id != progress.conversationId) return
        // A manual cancellation must not immediately restart at the same boundary.
        lastAttempt = "${progress.conversationId}/${progress.throughMessageId}/${current.summary?.id}/" +
            "${preferences.selectedModel.providerId}/${preferences.selectedModel.modelId}"
    }
}
