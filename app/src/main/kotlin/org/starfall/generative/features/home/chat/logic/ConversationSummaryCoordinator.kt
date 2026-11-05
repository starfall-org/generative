package org.starfall.generative.features.home.chat.logic


import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID
import org.starfall.generative.shared.model.Conversation
import org.starfall.generative.shared.model.ConversationSummary
import org.starfall.generative.shared.model.ConversationSummaryProgress
import org.starfall.generative.shared.model.ConversationSummaryRequest
import org.starfall.generative.shared.model.SummaryRole
import org.starfall.generative.shared.model.SystemToolConfig
import org.starfall.generative.shared.model.language.ModelConfiguration
import org.starfall.generative.shared.model.provider.ModelProvider
import org.starfall.generative.database.repositories.ConversationRepository
import org.starfall.generative.features.home.chat.logic.tool.ToolChatViewModel

internal class ConversationSummaryCoordinator(
    private val repository: ConversationRepository,
    private val toolChat: ToolChatViewModel,
    private val currentConversation: () -> Conversation?,
    private val onSaved: (Conversation) -> Unit
) {
    private val progressState = MutableStateFlow<ConversationSummaryProgress?>(null)
    val progress = progressState.asStateFlow()

    suspend fun summarize(conversation: Conversation, provider: ModelProvider, config: SystemToolConfig,
        request: ConversationSummaryRequest) {
        val cutoff = conversation.messages.last().id
        val modelId = config.modelId
        fun report(label: String, fraction: Float) {
            progressState.value = ConversationSummaryProgress(conversation.id, cutoff, label, fraction)
        }
        report("Preparing summary…", 0f)
        try {
            val text = generateConversationSummary(
                conversation, provider.config.modelConfigs[modelId] ?: ModelConfiguration(), config.prompt, request,
                complete = { messages, prompt, outputTokens ->
                    toolChat.completeText(provider, modelId, messages, prompt, maxOutputTokens = outputTokens)
                },
                report = ::report
            )
            val latest = repository.loadById(conversation.id)
                ?: currentConversation()?.takeIf { it.id == conversation.id } ?: conversation
            val updated = latest.copy(summary = ConversationSummary(UUID.randomUUID().toString(), text, cutoff, SummaryRole.SYSTEM),
                updatedAt = System.currentTimeMillis())
            repository.saveConversation(updated)
            onSaved(updated)
        } catch (e: CancellationException) { throw e
        } catch (e: Exception) {
            report("Summary failed: ${e.localizedMessage ?: "Unknown error"}", 0f)
            delay(2500)
        } finally { progressState.value = null }
    }
}
