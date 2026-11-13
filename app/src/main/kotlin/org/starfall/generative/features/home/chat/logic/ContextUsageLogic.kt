package org.starfall.generative.features.home.chat.logic

import java.security.MessageDigest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.starfall.generative.features.home.chat.model.ContextWindowStatus
import org.starfall.generative.shared.model.ChatRole
import org.starfall.generative.shared.model.Conversation
import org.starfall.generative.shared.model.StoredMessage
import org.starfall.generative.shared.model.ToolActivity
import org.starfall.generative.shared.model.ToolPayload
import org.starfall.generative.shared.model.ToolSettings
import org.starfall.generative.shared.model.generation.ContextUsageSnapshot
import org.starfall.generative.shared.model.generation.TokenUsage
import org.starfall.generative.shared.model.language.ModelConfiguration

/** Hash request-affecting settings; never persist the prompt or credentials in usage metadata. */
internal fun contextUsageKey(
    conversation: Conversation,
    prompt: String,
    config: ModelConfiguration,
    prefixMessages: List<StoredMessage> = emptyList(),
    toolSettings: ToolSettings? = null
): String = digestParts(
    "context-usage-v1", prompt, Json.encodeToString(config),
    Json.encodeToString(conversation.summary), conversation.reasoningEffort.orEmpty(),
    contextMessagesFingerprint(prefixMessages), Json.encodeToString(toolSettings)
)

internal fun contextMessagesFingerprint(messages: List<StoredMessage>): String =
    messages.fold("") { previous, message -> advanceContextFingerprint(previous, message) }

private fun advanceContextFingerprint(previous: String, message: StoredMessage): String {
    val version = message.activeVersion
    // Exclude timestamps, usage itself, inactive versions and generation/UI state.
    return digestParts(previous, message.id, message.role.name, version.content,
        version.reasoningContent.orEmpty(), version.reasoningSignature.orEmpty(),
        Json.encodeToString(version.files), toolActivityFingerprint(version.toolActivity))
}

/**
 * Tool payloads may exist only in storage, so they are represented by their sizes instead of being
 * read. An empty list keeps its former "[]" form, so histories without tools remain comparable.
 */
private fun toolActivityFingerprint(activities: List<ToolActivity>): String {
    val shape = Json.encodeToString(activities.map { it.copy(arguments = ToolPayload.EMPTY, response = ToolPayload.EMPTY) })
    if (activities.isEmpty()) return shape
    return shape + activities.joinToString(",", "#") { "${it.arguments.size}/${it.response.size}" }
}

internal fun recordContextUsage(
    conversation: Conversation,
    messageId: String,
    usage: TokenUsage,
    contextKey: String
): Conversation {
    if (!usage.isValid || contextKey.isBlank()) return conversation
    val index = conversation.messages.indexOfFirst { it.id == messageId && it.role == ChatRole.MODEL }
    if (index < 0) return conversation
    val snapshot = ContextUsageSnapshot(usage, contextKey,
        contextMessagesFingerprint(conversation.messages.take(index + 1)))
    return conversation.copy(messages = conversation.messages.mapIndexed { messageIndex, message ->
        if (messageIndex != index) message else message.copy(versions = message.versions.mapIndexed { versionIndex, version ->
            if (versionIndex == message.activeVersionIndex) version.copy(contextUsage = snapshot) else version
        })
    })
}

internal fun apiContextWindowStatus(
    conversation: Conversation,
    contextKey: String,
    providerId: String,
    modelId: String,
    limit: Int,
    includeReasoning: Boolean
): ContextWindowStatus? {
    var fingerprint = ""
    var anchor: Pair<Int, ContextUsageSnapshot>? = null
    conversation.messages.forEachIndexed { index, message ->
        fingerprint = advanceContextFingerprint(fingerprint, message)
        val version = message.activeVersion
        val usage = version.contextUsage
        if (message.role == ChatRole.MODEL && version.providerId == providerId && version.modelId == modelId &&
            usage != null && usage.usage.isValid && usage.contextKey == contextKey &&
            usage.messagesFingerprint == fingerprint) {
            anchor = index to usage
        }
    }
    val (index, snapshot) = anchor ?: return null
    val additional = conversation.messages.drop(index + 1).sumOf { estimateMessageTokens(it, includeReasoning) }
    val used = snapshot.usage.contextTokens + additional.coerceAtMost(Long.MAX_VALUE - snapshot.usage.contextTokens)
    return ContextWindowStatus(used, limit, snapshot.usage, additional)
}

private fun digestParts(vararg values: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
    values.forEach { value ->
        val bytes = value.toByteArray(Charsets.UTF_8)
        for (shift in 24 downTo 0 step 8) digest.update((bytes.size ushr shift).toByte())
        digest.update(bytes)
    }
    val hex = "0123456789abcdef"
    return buildString(64) {
        digest.digest().forEach { byte ->
            val value = byte.toInt() and 0xff
            append(hex[value ushr 4])
            append(hex[value and 15])
        }
    }
}
