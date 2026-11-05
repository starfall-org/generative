package org.starfall.generative.features.home.chat.logic.tool

import kotlinx.serialization.json.*
import org.starfall.generative.shared.helpers.networking.obj
import org.starfall.generative.shared.helpers.networking.str
import org.starfall.generative.features.home.shared.logic.ToolSummary

/**
 * Keep the complete result for the model. `preview` is app-only activity metadata and is never
 * substituted for the result placed in the model conversation history.
 */
internal suspend fun prepareGenericToolResult(result: JsonObject): ToolSummary {
    val media = Regex("tool-file:([a-zA-Z0-9._-]+)").findAll(result.toString())
        .map { it.groupValues[1] }.distinct().take(32).toList()
    val preview = when {
        (result["isError"] as? JsonPrimitive)?.booleanOrNull == true -> "Tool reported an error."
        media.isNotEmpty() -> "Tool completed with ${media.size} file(s). Open details to view."
        else -> "Tool completed."
    }
    return ToolSummary(content = result, preview = preview, files = media)
}
