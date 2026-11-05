package org.starfall.generative.features.home.chat.logic.tool


import kotlinx.serialization.json.*
import org.starfall.generative.shared.subfeatures.media.logic.ToolFiles
import org.starfall.generative.shared.model.ToolActivity
import org.starfall.generative.shared.helpers.networking.obj
import org.starfall.generative.shared.helpers.networking.str

/**
 * Past tool calls as real `assistant` + `tool_calls` and `tool` result messages.
 * Writing them into the assistant text (e.g. "Tool X: success.") made models imitate those lines.
 */
internal class ToolReplay(
    val messages: List<JsonObject>,
    /** Assistant text after the last tool round; empty when the reply ended with a tool result. */
    val trailingContent: String
)

internal suspend fun replayToolActivities(
    content: String,
    activities: List<ToolActivity>,
    files: ToolFiles
): ToolReplay {
    if (activities.isEmpty()) return ToolReplay(emptyList(), content)
    val out = mutableListOf<JsonObject>()
    var cursor = 0
    var index = 0
    while (index < activities.size) {
        val offset = activities[index].contentOffset.coerceIn(cursor, content.length)
        // Calls made in one model turn share the same text offset.
        val group = activities.drop(index).takeWhile { it.contentOffset.coerceIn(cursor, content.length) == offset }
        out += obj(
            "role" to str("assistant"),
            "content" to str(content.substring(cursor, offset)),
            "tool_calls" to JsonArray(group.map { activity ->
                obj(
                    "id" to str(activity.id),
                    "type" to str("function"),
                    "function" to obj(
                        "name" to str(activity.replayWireName()),
                        "arguments" to (runCatching { Json.parseToJsonElement(activity.arguments) as? JsonObject }.getOrNull()
                            ?: JsonObject(emptyMap()))
                    )
                )
            })
        )
        group.forEach { activity ->
            out += obj(
                "role" to str("tool"),
                "tool_call_id" to str(activity.id),
                "name" to str(activity.replayWireName()),
                "content" to str(replayResult(activity, files).toString())
            )
        }
        cursor = offset
        index += group.size
    }
    return ToolReplay(out, content.substring(cursor))
}

private fun ToolActivity.replayWireName(): String =
    wireName.ifBlank { name.replace(Regex("[^A-Za-z0-9_-]+"), "_").trim('_').take(64).ifBlank { "tool" } }

private suspend fun replayResult(activity: ToolActivity, files: ToolFiles): JsonObject {
    val parsed = runCatching { Json.parseToJsonElement(activity.response) as? JsonObject }.getOrNull()
    val body = when {
        activity.status == "running" || activity.status == "cancelled" -> obj(
            "isError" to JsonPrimitive(true),
            "status" to str("interrupted"),
            "error" to str("Tool call was stopped before it finished.")
        )
        parsed != null && (activity.status == "error" || activity.responsePreviewLimit > 0) ->
            if (activity.status == "error") parsed else prepareGenericToolResult(parsed).content
        else -> obj("status" to str(activity.status), "summary" to str(activity.summary.take(2000)))
    }
    // Generated media stays hidden from the model: it only needs to know the media was created.
    val generatedMedia = activity.wireName == "generate_image" || activity.wireName == "generate_video"
    val references = if (generatedMedia) emptyList()
        else activity.files.filter { files.resolve(it) != null }.map { str("tool-file:$it") }
    return if (references.isEmpty()) body else JsonObject(body + ("reusableFiles" to JsonArray(references)))
}
