package org.starfall.generative.features.home.chat.logic.service

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import org.starfall.generative.features.home.chat.logic.ChatGeneration
import org.starfall.generative.shared.subfeatures.logging.logic.DebugLog
import org.starfall.generative.shared.model.logging.DebugLogCategory
import org.starfall.generative.features.home.chat.logic.tool.ToolChatViewModel
import org.starfall.generative.shared.model.ChatRole
import org.starfall.generative.shared.model.Conversation
import org.starfall.generative.shared.model.DEFAULT_TITLE_GENERATION_PROMPT
import org.starfall.generative.shared.model.MessageVersion
import org.starfall.generative.shared.model.StoredMessage
import org.starfall.generative.shared.model.SystemToolConfig
import org.starfall.generative.shared.model.provider.ModelProvider
import org.starfall.generative.database.repositories.ConversationRepository

/** Generates titles independently of the message-send transaction while respecting restore boundaries. */
internal class ConversationTitleGenerator(
    private val scope: CoroutineScope,
    private val conversationRepo: ConversationRepository,
    private val toolChat: ToolChatViewModel,
    private val currentConversation: MutableStateFlow<Conversation?>,
    private val configuredTextModel: (String) -> Pair<ModelProvider, SystemToolConfig>?,
    private val existingGeneration: (String) -> ChatGeneration?,
    private val isRestoreInProgress: () -> Boolean = { false },
    private val withRestoreGuard: (() -> Unit) -> Unit = { it() },
    private val writeConversation: (suspend () -> Unit) -> Boolean = { block ->
        scope.launch { block() }
        true
    },
) {
    private val jobsLock = Any()
    private val activeJobs = linkedMapOf<Job, String>()

    /** Generate a suggestion for the rename dialog without modifying the conversation. */
    suspend fun suggest(conversationId: String): Result<String> {
        if (isRestoreInProgress()) return Result.failure(IllegalStateException("Restore in progress"))
        val configured = configuredTextModel("title_generation")
            ?: return Result.failure(IllegalStateException("Title Generation model not configured"))
        val conversation = existingGeneration(conversationId)?.snapshot?.takeIf { it.id == conversationId }
            ?: currentConversation.value?.takeIf { it.id == conversationId }
            ?: conversationRepo.loadById(conversationId)
            ?: return Result.failure(IllegalArgumentException("Conversation not found"))
        if (conversation.isTemporary || conversation.messages.none {
                (it.role == ChatRole.USER || it.role == ChatRole.MODEL) && it.content.isNotBlank()
            }) return Result.failure(IllegalArgumentException("Conversation has no messages"))
        return try {
            val title = requestTitle(conversation, configured.first, configured.second)
            if (title.isBlank() || isRestoreInProgress()) {
                Result.failure(IllegalStateException("No title generated"))
            } else Result.success(title)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    private suspend fun requestTitle(conversation: Conversation, provider: ModelProvider, config: SystemToolConfig): String {
        val messages = conversationTitleRequestMessages(conversation, config.prompt)
        check(messages.isNotEmpty()) { "Conversation has no messages" }
        val raw = toolChat.completeText(
            provider,
            config.modelId,
            messages,
            ""
        )
        return parseConversationTitle(raw)
    }

    fun generate(conversation: Conversation, expectedFallbackTitle: String) {
        if (isRestoreInProgress() || conversation.isTemporary ||
            conversation.title != expectedFallbackTitle) return
        val configured = configuredTextModel("title_generation") ?: return
        val (provider, config) = configured
        val conversationId = conversation.id
        val job = scope.launch(start = CoroutineStart.LAZY) {
            val title = try {
                requestTitle(conversation, provider, config)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                DebugLog.error(DebugLogCategory.SYSTEM, "Title Generation",
                    "Could not generate title for conversation $conversationId", error)
                return@launch
            }
            if (isRestoreInProgress()) return@launch

            // A running answer still owns its RAM snapshot; its final save includes the title.
            fun updateRunningGeneration(): Boolean {
                var applied = false
                withRestoreGuard {
                    val running = existingGeneration(conversationId)
                    if (running?.busy?.value == true && running.snapshot?.id == conversationId) {
                        running.updateConversation { current ->
                            if (current.title == expectedFallbackTitle) {
                                current.copy(title = title, updatedAt = System.currentTimeMillis())
                            } else current
                        }
                        applied = true
                    }
                }
                return applied
            }

            if (updateRunningGeneration()) return@launch
            writeConversation {
                // The final response may already be queued for persistence after busy becomes false.
                // Do not let that older save overwrite the generated title.
                existingGeneration(conversationId)?.awaitPersistence()
                if (isRestoreInProgress() || updateRunningGeneration()) return@writeConversation
                val current = conversationRepo.loadById(conversationId) ?: return@writeConversation
                if (current.title != expectedFallbackTitle) return@writeConversation
                // Update metadata only: saving an old full conversation can erase newer replies.
                conversationRepo.renameConversation(conversationId, title)
                // A new answer may have started while the database operation was suspended.
                if (!updateRunningGeneration()) {
                    val visible = currentConversation.value
                    if (visible?.id == conversationId && visible.title == expectedFallbackTitle) {
                        currentConversation.value = visible.copy(title = title, updatedAt = System.currentTimeMillis())
                    }
                    // If another response finished during the metadata write, let its old
                    // snapshot finish saving before restoring the title (without touching messages).
                    existingGeneration(conversationId)?.awaitPersistence()
                    if (conversationRepo.loadById(conversationId)?.title == expectedFallbackTitle) {
                        conversationRepo.renameConversation(conversationId, title)
                    }
                }
            }
        }
        val accepted = synchronized(jobsLock) {
            if (isRestoreInProgress() || conversationId in activeJobs.values) {
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

/** The last turn must request a title, not ask the API to continue an assistant answer. */
internal fun conversationTitleRequestMessages(conversation: Conversation, prompt: String = DEFAULT_TITLE_GENERATION_PROMPT): List<StoredMessage> {
    val messages = conversation.messages
        .filter { it.role == ChatRole.USER || it.role == ChatRole.MODEL }
        .map { message -> StoredMessage(
            id = message.id,
            role = message.role,
            versions = listOf(MessageVersion(content = message.content))
        ) }
        .filter { it.content.isNotBlank() }
    if (messages.isEmpty()) return emptyList()
    val instruction = prompt.ifBlank { DEFAULT_TITLE_GENERATION_PROMPT }
    val last = messages.last()
    return if (last.role == ChatRole.USER) {
        messages.dropLast(1) + last.copy(versions = listOf(MessageVersion(content = "${last.content}\n\n$instruction")))
    } else {
        messages + StoredMessage("title_generation_instruction", ChatRole.USER, listOf(MessageVersion(content = instruction)))
    }
}

/** Text-encoded transport failures must not become conversation titles. */
internal fun parseConversationTitle(raw: String): String {
    val response = raw.trim()
    val error = Regex("(?:^|\\s)\\[Error: (.*)]$", RegexOption.DOT_MATCHES_ALL).find(response)
    if (error != null) throw IllegalStateException(error.groupValues[1])
    val title = response.lineSequence().firstOrNull().orEmpty().trim().trim('"', '\'', '`').trim()
    check(title.isNotBlank()) { "No title generated" }
    return title
}
