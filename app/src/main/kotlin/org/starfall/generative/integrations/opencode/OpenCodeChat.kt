package org.starfall.generative.integrations.opencode

import kotlinx.coroutines.CancellationException
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
internal class OpenCodeChat(
    val api: OpenCodeApi,
    private val directory: String,
    private val restoreAttachment: suspend (String, String) -> String?,
) {
    private val restoredFiles = LinkedHashMap<String, List<String>>(16, 0.75f, true)
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

    suspend fun create(providerId: String, modelId: String): Conversation =
        api.createSession(directory, providerId, modelId).conversation()

    suspend fun open(id: String): Conversation =
        session(id).copy(messages = api.messages(id, ::message).filterNotNull())

    suspend fun session(id: String): Conversation = api.session(id).conversation().also { require(it.id == id) }

    suspend fun readTurn(id: String, promptId: String): OpenCodeTurn {
        var promptSeen = false
        var outcome: String? = null
        val messages = api.messages(id) { row ->
            val message = message(row)
            if (message?.role == ChatRole.USER && message.id == promptId) promptSeen = true
            if (promptSeen && outcome == null && row.text("type") == "idle") outcome = row.requiredText("outcome")
            message
        }
        return OpenCodeTurn(messages.filterNotNull(), outcome)
    }

    private suspend fun message(row: JsonObject): StoredMessage? {
        val message = decodeMessage(row) ?: return null
        if (message.role != ChatRole.USER) return message
        val files = (row["files"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
        if (files.isEmpty()) return message
        val references = restoredFiles[message.id] ?: files.mapNotNull { attachment ->
            val data = attachment.text("data") ?: return@mapNotNull null
            val mime = attachment.text("mime") ?: return@mapNotNull null
            if (data.length > (OPEN_CODE_MAX_ATTACHMENT_BYTES + 2) / 3 * 4) return@mapNotNull null
            try { restoreAttachment(data, mime) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { null }
        }.also { references ->
            if (references.size == files.size) {
                restoredFiles[message.id] = references
                if (restoredFiles.size > 128) restoredFiles.remove(restoredFiles.keys.first())
            }
        }
        return message.copy(versions = listOf(message.activeVersion.copy(files = references)))
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

private fun decodeMessage(row: JsonObject): StoredMessage? {
    val role = when (row.text("type")) {
        "user" -> ChatRole.USER
        "assistant" -> ChatRole.MODEL
        else -> return null
    }
    val model = row["model"] as? JsonObject
    val providerId = model?.text("providerID").orEmpty()
    val modelId = model?.text("id").orEmpty()
    val version = if (role == ChatRole.USER) {
        MessageVersion(content = row.text("text").orEmpty(), providerId = providerId, modelId = modelId)
    } else {
        val content = OpenCodeAssistantContent()
        (row["content"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>().forEachIndexed { index, part ->
            when (part.text("type")) {
                "text" -> content.text(false, index, part.text("text").orEmpty(), replace = true)
                "reasoning" -> content.text(true, index, part.text("text").orEmpty(), replace = true)
                "tool" -> content.storedTool(part)
            }
        }
        content.version(providerId, modelId).takeUnless {
            it.content.isBlank() && it.reasoningContent == null && it.toolActivity.isEmpty()
        } ?: return null
    }
    return StoredMessage(row.requiredText("id"), role, listOf(version))
}

private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
private fun JsonObject.requiredText(key: String): String =
    text(key)?.takeIf { it.isNotBlank() } ?: throw OpenCodeProtocolException("Missing OpenCode field: $key")
private fun JsonObject.requiredTime(key: String): Long =
    (this[key] as? JsonPrimitive)?.longOrNull ?: throw OpenCodeProtocolException("Invalid OpenCode timestamp: $key")
