package org.starfall.generative.integrations.opencode

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import org.starfall.generative.shared.model.ChatRole
import org.starfall.generative.shared.model.Conversation
import org.starfall.generative.shared.model.ConversationListItem
import org.starfall.generative.shared.model.MessageVersion
import org.starfall.generative.shared.model.StoredMessage
import org.starfall.generative.shared.model.api.ModelApiType
import org.starfall.generative.shared.model.language.ModelConfiguration
import org.starfall.generative.shared.model.provider.ModelProvider
import org.starfall.generative.shared.model.provider.ProviderConfiguration

/** A chat connection scoped to the server's default location. */
internal class OpenCodeChat(val api: OpenCodeApi, private val directory: String) {
    suspend fun providers(): List<ModelProvider> {
        val names = api.providers(directory).associate { it.requiredText("id") to it.requiredText("name") }
        return api.models(directory)
            .filter { (it["enabled"] as? JsonPrimitive)?.booleanOrNull == true && it.text("type") in setOf(null, "chat") }
            .groupBy { it.requiredText("providerID") }
            .map { (provider, models) ->
                val configs = models.associate { model ->
                    model.requiredText("id") to ModelConfiguration(
                        displayName = model.requiredText("name"),
                        supportsVision = false, supportsThinking = false, supportsToolCalls = false
                    )
                }
                // The picker requires an API type. Requests always use OpenCode's own protocol.
                ModelProvider(id = provider, name = names[provider] ?: provider,
                    type = ModelApiType.OPENAI, baseUrl = "", config = ProviderConfiguration(
                        modelIds = configs.keys.toList(), modelConfigs = configs
                    ))
            }
    }

    suspend fun list(): List<ConversationListItem> = api.sessions(directory).map { it.conversation().listItem() }

    suspend fun create(): Conversation = api.createSession(directory).conversation()

    suspend fun open(id: String): Conversation {
        val session = api.session(id).conversation()
        require(session.id == id)
        return session.copy(messages = api.messages(id).mapNotNull(::message))
    }

    suspend fun readTurn(id: String, promptId: String): OpenCodeTurn {
        val rows = api.messages(id)
        val promptIndex = rows.indexOfFirst { it.text("type") == "user" && it.text("id") == promptId }
        val idle = if (promptIndex < 0) null else rows.asSequence().drop(promptIndex + 1)
            .firstOrNull { it.text("type") == "idle" }
        return OpenCodeTurn(rows.mapNotNull(::message), idle?.requiredText("outcome"))
    }
}

internal data class OpenCodeTurn(val messages: List<StoredMessage>, val outcome: String?)

internal fun Conversation.listItem() = ConversationListItem(
    id, title, createdAt, updatedAt, tokenCount, providerId, modelId, null, null,
    hasMessages = messages.isNotEmpty() || updatedAt > createdAt, hasSummary = false
)

private fun JsonObject.conversation(): Conversation {
    val time = this["time"] as? JsonObject ?: throw OpenCodeProtocolException("Missing session timestamps")
    val model = this["model"] as? JsonObject
    return Conversation(requiredText("id"), text("title").orEmpty(),
        time.requiredTime("created"), time.requiredTime("updated"),
        providerId = model?.text("providerID").orEmpty(), modelId = model?.text("id").orEmpty())
}

private fun message(row: JsonObject): StoredMessage? {
    val role = when (row.text("type")) {
        "user" -> ChatRole.USER
        "assistant" -> ChatRole.MODEL
        else -> return null
    }
    val parts = (row["content"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
    fun collect(type: String) = parts.filter { it.text("type") == type }.joinToString("\n") { it.requiredText("text") }
    val model = row["model"] as? JsonObject
    val content = if (role == ChatRole.USER) row.text("text").orEmpty() else collect("text")
    val reasoning = collect("reasoning").ifBlank { null }
    if (role == ChatRole.MODEL && content.isBlank() && reasoning == null) return null
    return StoredMessage(row.requiredText("id"), role, listOf(MessageVersion(
        content = content, reasoningContent = reasoning,
        providerId = model?.text("providerID").orEmpty(), modelId = model?.text("id").orEmpty()
    )))
}

private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
private fun JsonObject.requiredText(key: String): String =
    text(key)?.takeIf { it.isNotBlank() } ?: throw OpenCodeProtocolException("Missing OpenCode field: $key")
private fun JsonObject.requiredTime(key: String): Long =
    (this[key] as? JsonPrimitive)?.longOrNull ?: throw OpenCodeProtocolException("Invalid OpenCode timestamp: $key")
