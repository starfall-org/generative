package org.starfall.generative.integrations.opencode

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import org.starfall.generative.shared.model.ChatRole
import org.starfall.generative.shared.model.StoredMessage
import org.starfall.generative.shared.model.toToolPayload

/** Applies volatile output only after this session has delivered the submitted prompt. */
internal class OpenCodeReplyEvents(
    private val sessionId: String,
    private val promptId: String,
    private val providerId: String,
    private val modelId: String
) {
    private val replies = mutableMapOf<String, OpenCodeAssistantContent>()
    private var delivered = false
    private var ended = false

    fun message(event: JsonObject): StoredMessage? {
        val data = event["data"] as? JsonObject ?: return null
        if (ended || data.text("sessionID") != sessionId) return null
        val type = event.text("type") ?: return null
        if (type == "session.inbox.delivered") {
            if (data.text("inboxID") == promptId) delivered = true
            else if (delivered) ended = true
            return null
        }
        if (!delivered) return null
        if (type in completionEvents) {
            ended = true
            return null
        }
        val messageId = data.text("assistantMessageID")?.takeIf { it.isNotBlank() } ?: return null
        val content = replies.getOrPut(messageId) { OpenCodeAssistantContent() }
        when {
            type.startsWith("session.tool.") -> {
                val id = data.text("id") ?: return null
                content.tool(id, data.text("name")) { tool ->
                    when (type) {
                        "session.tool.input.delta" -> tool.copy(
                            arguments = (tool.arguments.peek().orEmpty() + data.text("delta").orEmpty()).toToolPayload()
                        )
                        "session.tool.input.ended" -> tool.copy(arguments = data["text"].openCodePayload().toToolPayload())
                        "session.tool.called" -> tool.copy(arguments = data["input"].openCodePayload().toToolPayload())
                        "session.tool.success" -> tool.copy(status = "success", response = data.openCodeToolResult().toToolPayload())
                        "session.tool.failed" -> tool.copy(status = "error", response = data.openCodeToolResult().toToolPayload())
                        else -> tool
                    }
                }
            }
            type.startsWith("session.text.") || type.startsWith("session.reasoning.") -> {
                val ordinal = (data["ordinal"] as? JsonPrimitive)?.intOrNull?.takeIf { it >= 0 } ?: return null
                val complete = type.endsWith(".ended")
                val value = data.text(if (complete) "text" else "delta").orEmpty()
                content.text(type.startsWith("session.reasoning."), ordinal, value, replace = complete)
            }
            else -> return null
        }
        return StoredMessage(messageId, ChatRole.MODEL, listOf(content.version(providerId, modelId)))
    }
}

private val completionEvents = setOf(
    "session.execution.succeeded", "session.execution.failed", "session.execution.interrupted"
)

internal fun JsonObject.eventType(): String? = text("type")

private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
