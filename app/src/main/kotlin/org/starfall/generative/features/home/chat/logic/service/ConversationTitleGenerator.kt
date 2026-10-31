package org.starfall.generative.features.home.chat.logic.service

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import org.starfall.generative.features.home.chat.logic.ChatGeneration
import org.starfall.generative.features.home.chat.logic.tool.ToolChatViewModel
import org.starfall.generative.shared.model.ChatRole
import org.starfall.generative.shared.model.Conversation
import org.starfall.generative.shared.model.DEFAULT_TITLE_GENERATION_PROMPT
import org.starfall.generative.shared.model.MessageVersion
import org.starfall.generative.shared.model.StoredMessage
import org.starfall.generative.shared.model.SystemToolConfig
import org.starfall.generative.shared.model.provider.ModelProvider
import org.starfall.generative.database.ConversationRepository

/** Generates titles independently of the message-send transaction while respecting restore boundaries. */
internal class ConversationTitleGenerator(
    private val scope: CoroutineScope,
    private val conversationRepo: ConversationRepository,
    private val toolChat: ToolChatViewModel,
    private val currentConversation: MutableStateFlow<Conversation?>,
    private val configuredTextModel: (String) -> Pair<ModelProvider, SystemToolConfig>?,
    private val existingGeneration: (String) -> ChatGeneration?,
    private val generationFor: (String) -> ChatGeneration,
    private val isRestoreInProgress: () -> Boolean = { false },
    private val withRestoreGuard: (() -> Unit) -> Unit = { it() },
    private val writeConversation: (suspend () -> Unit) -> Boolean = { block ->
        scope.launch { block() }
        true
    },
) {
    private val jobsLock = Any()
    private val activeJobs = linkedMapOf<Job, String>()

    fun generate(conversation: Conversation, expectedFallbackTitle: String) {
        if (isRestoreInProgress() || conversation.isTemporary) return
        val configured = configuredTextModel("title_generation") ?: return
        val (provider, config) = configured
        val conversationId = conversation.id
        val job = scope.launch(start = CoroutineStart.LAZY) {
            val titleMessages = conversation.messages
                .filter { it.role == ChatRole.USER || it.role == ChatRole.MODEL }
                .map { message -> StoredMessage(
                    id = message.id,
                    role = message.role,
                    versions = listOf(MessageVersion(content = message.content))
                ) }
                .filter { it.content.isNotBlank() }
            val raw = runCatching {
                toolChat.completeText(
                    provider,
                    config.modelId,
                    titleMessages,
                    config.prompt.ifBlank { DEFAULT_TITLE_GENERATION_PROMPT }
                )
            }.onFailure { error ->
                if (error is CancellationException) throw error
            }.getOrNull().orEmpty()
            val title = raw.lineSequence().firstOrNull().orEmpty()
                .trim().trim('"', '\'', '`')
            if (title.isBlank() || isRestoreInProgress()) return@launch

            if (existingGeneration(conversationId)?.snapshot?.id == conversationId) {
                withRestoreGuard {
                    if (existingGeneration(conversationId)?.snapshot?.id == conversationId) {
                        generationFor(conversationId).updateConversation { current ->
                            if (current.id == conversationId && current.title == expectedFallbackTitle) {
                                current.copy(title = title, updatedAt = System.currentTimeMillis())
                            } else current
                        }
                    }
                }
            } else {
                writeConversation {
                    val current = currentConversation.value?.takeIf { it.id == conversationId }
                        ?: conversationRepo.loadById(conversationId)
                        ?: return@writeConversation
                    if (current.title != expectedFallbackTitle) return@writeConversation
                    val updated = current.copy(title = title, updatedAt = System.currentTimeMillis())
                    conversationRepo.saveConversation(updated)
                    if (currentConversation.value?.id == conversationId) currentConversation.value = updated
                }
            }
        }
        val accepted = synchronized(jobsLock) {
            if (isRestoreInProgress()) {
                false
            } else {
                activeJobs[job] = conversationId
                true
            }
        }
        if (!accepted) {
            job.cancel()
            return
        }
        job.invokeOnCompletion {
            synchronized(jobsLock) {
                activeJobs.remove(job)
            }
        }
        job.start()
    }

    suspend fun cancelFor(conversationIds: Set<String>) {
        if (conversationIds.isEmpty()) return
        while (true) {
            val jobs = synchronized(jobsLock) {
                activeJobs.filterValues { it in conversationIds }.keys.toList()
            }
            if (jobs.isEmpty()) break
            jobs.forEach { it.cancel() }
            jobs.joinAll()
        }
    }

    suspend fun cancelAll() {
        while (true) {
            val jobs = synchronized(jobsLock) { activeJobs.keys.toList() }
            if (jobs.isEmpty()) break
            jobs.forEach { it.cancel() }
            jobs.joinAll()
        }
    }
}
