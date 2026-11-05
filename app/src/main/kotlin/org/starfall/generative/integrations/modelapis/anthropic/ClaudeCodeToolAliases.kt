package org.starfall.generative.integrations.modelapis.anthropic

import java.security.MessageDigest
import kotlinx.serialization.json.*
import org.starfall.generative.shared.helpers.networking.str
import org.starfall.generative.shared.helpers.networking.text

private val mcpName = Regex("^mcp__[A-Za-z0-9_-]+__[A-Za-z0-9_-]+$")
private val serverToolPrefixes = listOf(
    "advisor_", "agent_toolset_", "bash_", "code_execution_", "computer_", "memory_",
    "text_editor_", "tool_search_tool_", "web_fetch_", "web_search_"
)
private val aliasWords = listOf(
    "amber", "apple", "aspen", "birch", "brook", "cedar", "cloud", "coral",
    "crane", "delta", "dove", "elm", "ember", "fern", "field", "finch",
    "forest", "glen", "grove", "harbor", "hazel", "heron", "hill", "iris",
    "lake", "lark", "leaf", "lotus", "maple", "meadow", "mist", "oak",
    "otter", "pine", "pond", "rain", "reed", "river", "robin", "rose",
    "sage", "shore", "sky", "sparrow", "stone", "stream", "sunny", "thorn",
    "tiger", "vale", "violet", "willow", "wind", "wren"
)

/** Alias declarations first, then every historical reference with the same request-local resolver. */
internal fun aliasClaudeCodeTools(body: JsonObject): JsonObject = remapTools(body).first

internal fun restoreClaudeCodeToolNames(request: JsonObject, response: JsonObject): JsonObject {
    val reverse = remapTools(request).second
    if (reverse.isEmpty()) return response
    val content = response["content"] as? JsonArray ?: return response
    return JsonObject(response + ("content" to restoreBlocks(content, reverse)))
}

private fun remapTools(body: JsonObject): Pair<JsonObject, Map<String, String>> {
    val tools = body["tools"] as? JsonArray
    val declarations = tools?.mapNotNull { it as? JsonObject }.orEmpty()
    val protectedNames = declarations.filter { isServerTool(it.text("type")) }.mapTo(mutableSetOf()) { it.text("name") }
    val reserved = declarations.mapTo(mutableSetOf()) { it.text("name") }.filterTo(mutableSetOf()) { it.isNotEmpty() }
    val forward = linkedMapOf<String, String>()

    fun rewriteName(name: String): String {
        if (name.isEmpty() || name in protectedNames || isMcpName(name)) return name
        forward[name]?.let { return it }
        val alias = allocateAlias(name, reserved) ?: return name
        forward[name] = alias
        reserved += alias
        return alias
    }

    // Allocation order is part of the wire contract: response restoration repeats it exactly.
    declarations.forEach { tool ->
        if (!isServerTool(tool.text("type"))) rewriteName(tool.text("name"))
    }
    val rewrittenTools = tools?.let { value ->
        JsonArray(value.map { element ->
            val tool = element as? JsonObject ?: return@map element
            if (isServerTool(tool.text("type"))) return@map tool
            val renamed = rewriteName(tool.text("name"))
            JsonObject(tool - "type" + ("name" to str(renamed)))
        })
    }
    val messages = body["messages"] as? JsonArray
    val rewrittenMessages = messages?.let { value ->
        JsonArray(value.map { element ->
            val message = element as? JsonObject ?: return@map element
            val content = message["content"] ?: return@map message
            JsonObject(message + ("content" to rewriteContent(content, ::rewriteName)))
        })
    }
    val toolChoice = body["tool_choice"] as? JsonObject
    val rewrittenChoice = toolChoice?.takeIf { it.text("type") == "tool" }?.let { choice ->
        JsonObject(choice + ("name" to str(rewriteName(choice.text("name")))))
    } ?: toolChoice

    val result = JsonObject(body.toMutableMap().apply {
        rewrittenTools?.let { this["tools"] = it }
        rewrittenMessages?.let { this["messages"] = it }
        rewrittenChoice?.let { this["tool_choice"] = it }
    })
    val reverse = linkedMapOf<String, String>()
    forward.forEach { (original, alias) -> if (alias !in reverse) reverse[alias] = original }
    return result to reverse
}

private fun rewriteContent(content: JsonElement, rename: (String) -> String): JsonElement = when (content) {
    is JsonArray -> JsonArray(content.map { rewriteContent(it, rename) })
    is JsonObject -> {
        var result = when (content.text("type")) {
            "tool_use" -> JsonObject(content + ("name" to str(rename(content.text("name")))))
            "tool_reference" -> JsonObject(content + ("tool_name" to str(rename(content.text("tool_name")))))
            "tool_addition", "tool_removal" -> rewriteToolChange(content, rename)
            else -> content
        }
        when (result.text("type")) {
            "tool_result" -> result["content"]?.let { nested ->
                result = JsonObject(result + ("content" to rewriteContent(nested, rename)))
            }
            "tool_search_tool_result" -> (result["content"] as? JsonObject)?.let { nested ->
                val references = nested["tool_references"] ?: return@let
                result = JsonObject(result + ("content" to JsonObject(nested +
                    ("tool_references" to rewriteContent(references, rename)))))
            }
        }
        result
    }
    else -> content
}

private fun rewriteToolChange(block: JsonObject, rename: (String) -> String): JsonObject {
    val tool = block["tool"] as? JsonObject ?: return block
    return when (tool.text("type")) {
        "tool_reference" -> JsonObject(block + ("tool" to JsonObject(tool + ("name" to str(rename(tool.text("name")))))))
        "tool_definition" -> {
            val definition = tool["definition"] as? JsonObject ?: return block
            if (block.text("type") != "tool_addition" || isServerTool(definition.text("type"))) return block
            val renamed = JsonObject(definition - "type" + ("name" to str(rename(definition.text("name")))))
            JsonObject(block + ("tool" to JsonObject(tool + ("definition" to renamed))))
        }
        else -> block
    }
}

private fun restoreBlocks(content: JsonArray, reverse: Map<String, String>): JsonArray = JsonArray(content.map { element ->
    val block = element as? JsonObject ?: return@map element
    fun restored(name: String) = reverse[name] ?: name
    var result = when (block.text("type")) {
        "tool_use" -> JsonObject(block + ("name" to str(restored(block.text("name")))))
        "tool_reference" -> JsonObject(block + ("tool_name" to str(restored(block.text("tool_name")))))
        else -> block
    }
    when (result.text("type")) {
        "tool_result" -> (result["content"] as? JsonArray)?.let { nested ->
            result = JsonObject(result + ("content" to restoreBlocks(nested, reverse)))
        }
        "tool_search_tool_result" -> (result["content"] as? JsonObject)?.let { nested ->
            val references = nested["tool_references"] as? JsonArray ?: return@let
            result = JsonObject(result + ("content" to JsonObject(nested +
                ("tool_references" to restoreBlocks(references, reverse)))))
        }
    }
    result
})

private fun isServerTool(type: String): Boolean {
    val normalized = type.trim().lowercase()
    return serverToolPrefixes.any(normalized::startsWith)
}

private fun isMcpName(name: String): Boolean = name.length in 1..64 && mcpName.matches(name)

private fun allocateAlias(original: String, reserved: Set<String>): String? {
    if (original.isEmpty()) return null
    val digest = digest("tool\n$original")
    val serverDigest = digest("server")
    val server = "${aliasWords[serverDigest[0].unsigned() % aliasWords.size]}_${aliasWords[serverDigest[1].unsigned() % aliasWords.size]}"
    val base = (digest[0].unsigned() shl 8) or digest[1].unsigned()
    repeat(aliasWords.size) { attempt ->
        val word = aliasWords[(base + attempt) % aliasWords.size]
        val prefix = "mcp__${server}__${word}_"
        val alias = prefix + semanticSuffix(original, 64 - prefix.length)
        if (alias !in reserved) return alias
    }
    return null
}

private fun semanticSuffix(original: String, maxLength: Int): String {
    val result = StringBuilder()
    var separator = false
    for (char in original) {
        val valid = char in 'a'..'z' || char in 'A'..'Z' || char in '0'..'9' || char == '_' || char == '-'
        if (!valid) {
            separator = result.isNotEmpty()
            continue
        }
        if (separator && result.length + 1 < maxLength) result.append('_')
        separator = false
        if (result.length >= maxLength) break
        result.append(char)
    }
    return result.toString().trim('_', '-').ifEmpty { "tool" }
}

private fun digest(value: String): ByteArray = MessageDigest.getInstance("SHA-256")
    .digest("generative-claude-code-mcp-v1\n$value".toByteArray(Charsets.UTF_8))

private fun Byte.unsigned(): Int = toInt() and 0xff
