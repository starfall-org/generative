package org.starfall.generative.integrations.opencode

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.starfall.generative.shared.model.MessageVersion
import org.starfall.generative.shared.model.ToolActivity
import org.starfall.generative.shared.model.toToolPayload

/** One ordered transcript representation for streamed events and stored history. */
internal class OpenCodeAssistantContent {
    private sealed interface Part {
        data class Text(val reasoning: Boolean, val value: StringBuilder = StringBuilder()) : Part
        data class Tool(var activity: ToolActivity) : Part
    }

    private val parts = linkedMapOf<String, Part>()

    fun text(reasoning: Boolean, ordinal: Int, value: String, replace: Boolean) {
        val part = parts.getOrPut("text:$reasoning:$ordinal") { Part.Text(reasoning) } as Part.Text
        if (replace) part.value.clear()
        part.value.append(value)
    }

    fun tool(id: String, name: String? = null, transform: (ToolActivity) -> ToolActivity) {
        val existing = parts["tool:$id"] as? Part.Tool
        if (existing != null) existing.activity = transform(existing.activity)
        else if (!name.isNullOrBlank()) {
            parts["tool:$id"] = Part.Tool(transform(ToolActivity(id = id, name = name, wireName = name)))
        }
    }

    fun version(providerId: String, modelId: String): MessageVersion {
        val answer = StringBuilder()
        val reasoning = StringBuilder()
        var reasoningContentOffset: Int? = null
        val tools = buildList {
            parts.values.forEach { part ->
                when (part) {
                    is Part.Text -> if (part.reasoning) {
                        if (part.value.isNotEmpty()) {
                            if (reasoningContentOffset == null) reasoningContentOffset = answer.length
                            if (reasoning.isNotEmpty()) reasoning.append('\n')
                            reasoning.append(part.value)
                        }
                    } else if (part.value.isNotEmpty()) {
                        if (answer.isNotEmpty()) answer.append('\n')
                        answer.append(part.value)
                    }
                    is Part.Tool -> add(part.activity.copy(contentOffset = answer.length, reasoningOffset = reasoning.length))
                }
            }
        }
        return MessageVersion(
            content = answer.toString(),
            reasoningContent = reasoning.toString().ifBlank { null },
            reasoningContentOffset = reasoningContentOffset ?: 0,
            toolActivity = tools,
            providerId = providerId,
            modelId = modelId
        )
    }
}

internal fun JsonObject.openCodeText(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

internal fun JsonElement?.openCodePayload(): String = when (this) {
    null -> ""
    is JsonPrimitive -> contentOrNull.orEmpty()
    else -> toString()
}

internal fun JsonObject.openCodeToolResult(): String {
    val content = this["content"] as? JsonArray
    return content?.joinToString("\n") { item ->
        (item as? JsonObject)?.openCodeText("text") ?: item.toString()
    }?.takeIf { it.isNotBlank() } ?: this["error"].openCodePayload()
}

internal fun OpenCodeAssistantContent.storedTool(part: JsonObject) {
    val id = part.openCodeText("id") ?: return
    val status = part["state"] as? JsonObject ?: return
    tool(id, part.openCodeText("name")) {
        it.copy(
            status = when (status.openCodeText("status")) {
                "completed" -> "success"
                "error" -> "error"
                else -> "running"
            },
            arguments = status["input"].openCodePayload().toToolPayload(),
            response = status.openCodeToolResult().toToolPayload()
        )
    }
}
