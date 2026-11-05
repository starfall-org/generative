package org.starfall.generative.integrations.modelapis.anthropic

import kotlinx.serialization.json.*
import org.starfall.generative.shared.helpers.networking.obj
import org.starfall.generative.shared.helpers.networking.str
import org.starfall.generative.shared.helpers.networking.text

internal const val CLAUDE_CODE_IDENTITY = "You are Claude Code, Anthropic's official CLI for Claude."
internal const val CLAUDE_CODE_USER_AGENT = "claude-cli/2.1.280 (external, cli)"

private val legacySystemReminderModels = setOf(
    "claude-3-5-haiku-20241022", "claude-3-5-haiku-latest",
    "claude-3-7-sonnet-20250219", "claude-3-7-sonnet-latest",
    "claude-haiku-4-5", "claude-haiku-4-5-20251001",
    "claude-opus-4", "claude-opus-4-20250514",
    "claude-opus-4-1", "claude-opus-4-1-20250805",
    "claude-opus-4-5", "claude-opus-4-5-20251101",
    "claude-opus-4-6", "claude-opus-4-7",
    "claude-sonnet-4", "claude-sonnet-4-20250514",
    "claude-sonnet-4-5", "claude-sonnet-4-5-20250929", "claude-sonnet-4-6"
)

/** Claude Code's beta list is request-dependent; a static profile contradicts legacy payloads. */
internal fun claudeCodeBetaHeader(body: JsonObject? = null): String {
    val model = canonicalModel(body?.text("model").orEmpty())
    val thinking = body?.get("thinking") as? JsonObject
    val betas = mutableListOf("claude-code-20250219", "oauth-2025-04-20", "interleaved-thinking-2025-05-14")
    if (thinking?.containsKey("display") != true) betas += "redact-thinking-2026-02-12"
    betas += listOf(
        "thinking-token-count-2026-05-13",
        "context-management-2025-06-27",
        "prompt-caching-scope-2026-01-05"
    )
    if (body != null && !usesLegacySystemReminder(model)) {
        betas += "mid-conversation-system-2026-04-07"
        if (!isSonnet5(model)) betas += "mid-conversation-tool-changes-2026-07-01"
    }
    val haiku = Regex("(?:^|-)haiku(?:-|\\[|$)").containsMatchIn(model)
    if (body != null && !haiku && thinking?.text("type") != "disabled") {
        betas += "effort-2025-11-24"
    }
    betas += "extended-cache-ttl-2025-04-11"
    return betas.joinToString(",")
}

internal fun claudeCodeSupportsMidConversationSystem(modelName: String): Boolean =
    !usesLegacySystemReminder(canonicalModel(modelName))

internal fun claudeCodeSystemReminder(text: String): String = buildString {
    append("<system-reminder>\n")
    append(text)
    if (!text.endsWith("\n")) append('\n')
    append("</system-reminder>")
}

/**
 * Keep the Claude Code identity alone at top level and move caller instructions into the
 * model-appropriate conversation slot. Unknown future models optimistically accept system turns.
 */
internal fun relocateClaudeCodeSystem(body: JsonObject, systemPrompt: String): JsonObject {
    val source = body["system"] ?: systemPrompt.takeIf { it.isNotEmpty() }?.let(::str)
    val callerBlocks = callerSystemBlocks(source)
    val identity = obj("type" to str("text"), "text" to str(CLAUDE_CODE_IDENTITY))
    var result = JsonObject(body + ("system" to JsonArray(listOf(identity))))
    if (callerBlocks.isEmpty()) return result
    val messageArray = body["messages"] as? JsonArray
    if (messageArray?.let(::hasAdvisorHistory) == true) {
        // Advisor results are bound to message indices; do not splice or prepend any content.
        return JsonObject(result + ("system" to JsonArray(listOf(identity) + callerBlocks)))
    }
    val messages = messageArray?.map { it.jsonObject } ?: return result
    val firstUser = messages.indexOfFirst { it.text("role") == "user" }
    // Match Claude Code: without a user turn there is no legal relocation slot.
    if (firstUser < 0) return result

    if (!claudeCodeSupportsMidConversationSystem(body.text("model"))) {
        val user = messages[firstUser]
        val content = user["content"]
        val updatedContent = when (content) {
            is JsonArray -> prependReminderBlocks(content, callerBlocks)
            is JsonPrimitive -> JsonArray(callerBlocks.map { reminderBlock(it.jsonObject.text("text")) } +
                listOf(obj("type" to str("text"), "text" to content)))
            else -> content
        }
        if (updatedContent != null) {
            val updated = messages.toMutableList()
            updated[firstUser] = JsonObject(user + ("content" to updatedContent))
            result = JsonObject(result + ("messages" to JsonArray(updated)))
        }
        return result
    }

    val existingBlocks = messages.filter { it.text("role") == "system" }
        .flatMap { (it["content"] as? JsonArray).orEmpty() }.toMutableList()
    val pending = callerBlocks.filterNot(existingBlocks::remove)
    if (pending.isEmpty()) return result
    var insertAt = firstUser + 1
    while (insertAt < messages.size && messages[insertAt].text("role") == "user") insertAt++
    val updated = messages.toMutableList()
    updated.addAll(insertAt, pending.map { block ->
        obj("role" to str("system"), "content" to JsonArray(listOf(block)))
    })
    return JsonObject(result + ("messages" to JsonArray(updated)))
}

private fun callerSystemBlocks(system: JsonElement?): List<JsonObject> = when (system) {
    JsonNull, null -> emptyList()
    is JsonPrimitive -> listOfNotNull(system.content.takeIf { it.isNotBlank() }?.let {
        obj("type" to str("text"), "text" to system)
    })
    is JsonArray -> system.mapIndexed { index, element ->
        val block = element as? JsonObject
        require(block != null && block.text("type") == "text") {
            "Claude Code system instructions support text only (system.$index)"
        }
        block
    }
    else -> emptyList()
}.filter { it.text("text").isNotBlank() && it.text("text").trim() != CLAUDE_CODE_IDENTITY }

private fun reminderBlock(text: String) = obj("type" to str("text"), "text" to str(claudeCodeSystemReminder(text)))

private fun prependReminderBlocks(content: JsonArray, callerBlocks: List<JsonObject>): JsonArray {
    val blocks = content.toList()
    val existingTexts = blocks.mapNotNull { (it as? JsonObject)?.text("text") }.toMutableList()
    val reminders = callerBlocks.map { reminderBlock(it.text("text")) }.filterNot {
        existingTexts.remove(it.jsonObject.text("text"))
    }
    var insertAt = 0
    while (insertAt < blocks.size && (blocks[insertAt] as? JsonObject)?.text("type") == "tool_result") insertAt++
    return JsonArray(blocks.subList(0, insertAt) + reminders + blocks.subList(insertAt, blocks.size))
}

private fun canonicalModel(model: String): String {
    val normalized = model.trim().lowercase()
    return normalized.substringAfterLast('/')
}

private fun usesLegacySystemReminder(model: String): Boolean = model in legacySystemReminderModels

private fun isSonnet5(model: String): Boolean {
    val normalized = model.replace('.', '-').replace('_', '-')
    val sonnet55 = normalized == "claude-sonnet-5-5" || normalized.startsWith("claude-sonnet-5-5-") ||
        normalized.startsWith("claude-sonnet-5-5[")
    return !sonnet55 && (normalized == "claude-sonnet-5" || normalized.startsWith("claude-sonnet-5-") ||
        normalized.startsWith("claude-sonnet-5["))
}

private fun hasAdvisorHistory(messages: JsonArray): Boolean = messages.any { message ->
    val content = (message as? JsonObject)?.get("content")
    fun advisorBlock(block: JsonObject): Boolean = when (block.text("type")) {
        "advisor_tool_result", "advisor_redacted_result" -> true
        "server_tool_use" -> block.text("name") == "advisor"
        "tool_result" -> when (val nested = block["content"]) {
            is JsonArray -> nested.any { (it as? JsonObject)?.text("type") == "advisor_redacted_result" }
            is JsonObject -> nested.text("type") == "advisor_redacted_result"
            else -> false
        }
        else -> false
    }
    when (content) {
        is JsonArray -> content.any { (it as? JsonObject)?.let(::advisorBlock) == true }
        is JsonObject -> advisorBlock(content)
        else -> false
    }
}
