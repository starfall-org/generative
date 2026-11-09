package org.starfall.generative.shared.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.starfall.generative.shared.model.generation.ContextUsageSnapshot

enum class ChatRole {
    @SerialName("user") USER,
    @SerialName("model") MODEL,
    @SerialName("system") SYSTEM
}

@Serializable
data class MessageVersion(
    val content: String = "",
    val timestamp: String = "",
    val files: List<String> = emptyList(),
    val toolActivity: List<ToolActivity> = emptyList(),
    @SerialName("reasoning_content") val reasoningContent: String? = null,
    @SerialName("reasoning_signature") val reasoningSignature: String? = null,
    @SerialName("processing_finished_at") val processingFinishedAt: Long? = null,
    @SerialName("provider_id") val providerId: String = "",
    @SerialName("model_id") val modelId: String = "",
    @SerialName("model_display_name") val modelDisplayName: String = "",
    @SerialName("generation_interrupted") val generationInterrupted: Boolean = false,
    @SerialName("generation_error") val generationError: String? = null,
    @SerialName("context_usage") val contextUsage: ContextUsageSnapshot? = null,
    /** Provenance set by the skill picker, never inferred from user-supplied text. */
    @SerialName("create_skill_requested") val createSkillRequested: Boolean = false,
    /** Picker-selected skill; never inferred by matching prompt text. */
    @SerialName("selected_skill_name") val selectedSkillName: String? = null,
    @SerialName("reasoning_content_offset") val reasoningContentOffset: Int = 0,
    @SerialName("files_content_offset") val filesContentOffset: Int? = null,
    @SerialName("media_content_offsets") val mediaContentOffsets: Map<String, Int> = emptyMap(),
    @SerialName("block_order") val blockOrder: Map<String, Int> = emptyMap()
)

@Serializable
data class StoredMessage(
    val id: String,
    val role: ChatRole,
    val versions: List<MessageVersion> = emptyList(),
    @SerialName("active_version_index") val activeVersionIndex: Int = 0,
    @kotlinx.serialization.Transient val isQueued: Boolean = false
) {
    val activeVersion: MessageVersion
        get() {
            if (versions.isEmpty()) return MessageVersion()
            val idx = activeVersionIndex.coerceIn(0, versions.size - 1)
            return versions[idx]
        }

    val content: String
        get() = activeVersion.content

    val files: List<String>
        get() = activeVersion.files
    val reasoningSignature: String?
        get() = activeVersion.reasoningSignature


    val reasoningContent: String?
        get() = activeVersion.reasoningContent

}

@Serializable
enum class SummaryRole { SYSTEM, ASSISTANT, USER }

@Serializable
data class ConversationSummary(
    val id: String,
    val content: String,
    @SerialName("through_message_id") val throughMessageId: String,
    val role: SummaryRole = SummaryRole.SYSTEM,
    @SerialName("created_at") val createdAt: Long = System.currentTimeMillis()
)

data class ConversationSummaryRequest(
    val targetTokens: Int,
    val chunked: Boolean = false,
    val tokensPerChunk: Int = 20_000
)

data class ConversationSummaryProgress(
    val conversationId: String,
    val throughMessageId: String,
    val label: String,
    val progress: Float
)

@Serializable
data class Conversation(
    val id: String,
    val title: String,
    @SerialName("created_at") val createdAt: Long,
    @SerialName("updated_at") val updatedAt: Long,
    val messages: List<StoredMessage> = emptyList(),
    @SerialName("token_count") val tokenCount: Int? = null,
    @SerialName("provider_id") val providerId: String = "",
    @SerialName("model_id") val modelId: String = "",
    @SerialName("profile_id") val profileId: String? = null,
    val summary: ConversationSummary? = null,
    @SerialName("reasoning_effort") val reasoningEffort: String? = null,
    /** This chat's own instruction selection; null delegates to the configured instruction scope. */
    @SerialName("selected_prompt_ids") val selectedInstructionIds: Set<String>? = null,
    /** Remote working directories keyed by machine ID, owned by this chat. */
    @SerialName("remote_workspaces") val remoteWorkspaces: Map<String, String> = emptyMap(),
    /** Process-local list-structure epoch; serialized conversations always restore at revision zero. */
    @kotlinx.serialization.Transient val structuralRevision: Long = 0L,
    /** Memory-only session: it must never be added to history, backups, or persistent storage. */
    @kotlinx.serialization.Transient val isTemporary: Boolean = false
)
