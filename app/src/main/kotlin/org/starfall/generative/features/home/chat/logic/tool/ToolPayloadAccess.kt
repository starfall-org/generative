package org.starfall.generative.features.home.chat.logic.tool

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonPrimitive
import org.starfall.generative.shared.helpers.networking.text
import org.starfall.generative.shared.helpers.networking.toolArguments
import org.starfall.generative.shared.model.ToolActivity
import org.starfall.generative.shared.model.ToolPayload

/** For small lookups (e.g. a skill name) where an unreadable payload simply means "unknown". */
internal suspend fun ToolPayload.textOrNull(): String? = try {
    text()
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    null
}

/** The `name` argument of a call, or null when the payload is missing or not a JSON object. */
internal suspend fun ToolActivity.nameArgument(): String? =
    arguments.textOrNull()?.let { raw -> runCatching { toolArguments(JsonPrimitive(raw)).text("name") }.getOrNull() }
