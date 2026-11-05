package org.starfall.generative.features.home.chat.logic.tool

import kotlinx.serialization.json.*
import org.starfall.generative.shared.model.*
import org.starfall.generative.shared.helpers.networking.obj
import org.starfall.generative.shared.helpers.networking.str

internal const val CREATE_SKILL_TOOL_NAME = "create_skill"
internal const val ACTIVATE_SKILL_TOOL_NAME = "activate_skill"
internal const val AGENT_SKILLS_SEARCH_TOOL_NAME = "agent_skills_search"
internal const val READ_SKILL_RESOURCE_TOOL_NAME = "read_skill_resource"
internal const val SEARCH_TOOLS_TOOL_NAME = "search_tools"

internal val createSkillDefinition = ToolDefinition(
    CREATE_SKILL_TOOL_NAME,
    "Save a reusable Agent Skill in the user's agent-skills directory. Call this when the user asks to create a skill. Provide a lowercase hyphenated name, a description of what it does and when to use it, and reusable instruction content.",
    obj("type" to str("object"), "properties" to obj(
        "name" to obj("type" to str("string"), "description" to str("Lowercase hyphenated skill name")),
        "description" to obj("type" to str("string"), "description" to str("What the skill does and when to use it")),
        "content" to obj("type" to str("string"), "description" to str("Complete reusable skill instructions"))
    ), "required" to JsonArray(listOf(str("name"), str("description"), str("content"))))
)

/** A separately opt-in discovery tool; only its results unlock skill activation. */
internal val agentSkillsSearchDefinition = ToolDefinition(
    AGENT_SKILLS_SEARCH_TOOL_NAME,
    "Search installed Agent Skills by their names and descriptions. Call this when a skill could help " +
        "with the user's task. Only returned skills become available to activate; search results " +
        "contain metadata, not full instructions.",
    obj("type" to str("object"), "properties" to obj(
        "query" to obj("type" to str("string"),
            "description" to str("What skill or capability would help with the current task?"))
    ), "required" to JsonArray(listOf(str("query"))))
)

/** Metadata-only results; no arbitrary instructions enter the context before activation. */
internal fun searchAgentSkills(query: String, skills: List<AgentSkill>): List<AgentSkill> {
    val commonWords = setOf("the", "and", "for", "with", "from", "that", "this", "how",
        "can", "you", "please", "help", "use", "skill", "skills", "find", "need", "want")
    val terms = Regex("[\\p{L}\\p{N}]+")
        .findAll(query.lowercase()).map { it.value }
        .filter { it.length >= 3 && it !in commonWords }.distinct().toList()
    if (terms.isEmpty()) return emptyList()
    return skills.asSequence()
        .map { skill ->
            val name = skill.name.replace('-', ' ').lowercase()
            val description = skill.description.lowercase()
            val score = terms.sumOf { term ->
                (if (name.contains(term)) 3 else 0) + (if (description.contains(term)) 1 else 0)
            }
            skill to score
        }
        .filter { (_, score) -> score > 0 }
        .sortedWith(compareByDescending<Pair<AgentSkill, Int>> { it.second }.thenBy { it.first.name })
        .take(5)
        .map { it.first }
        .toList()
}

/** Agent Skills: discover by metadata, load instructions on demand, then load referenced files. */
internal fun activateSkillDefinition(names: List<String>) = ToolDefinition(
    ACTIVATE_SKILL_TOOL_NAME,
    "Load the instructions of an available Agent Skill when its description matches the task. " +
        "Use the exact skill name from the available skills catalog before following its instructions.",
    obj("type" to str("object"), "properties" to obj(
        "name" to obj("type" to str("string"),
            "enum" to JsonArray(names.map(::str)),
            "description" to str("Name of an available skill to activate"))
    ), "required" to JsonArray(listOf(str("name"))))
)

internal val readSkillResourceDefinition = ToolDefinition(
    READ_SKILL_RESOURCE_TOOL_NAME,
    "Read a text resource referenced by an activated Agent Skill, relative to its root folder. " +
        "Can read references, templates and script source but cannot execute scripts or read binary files.",
    obj("type" to str("object"), "properties" to obj(
        "name" to obj("type" to str("string"), "description" to str("Activated skill name")),
        "path" to obj("type" to str("string"), "description" to str("Relative resource path, e.g. references/guide.md"))
    ), "required" to JsonArray(listOf(str("name"), str("path"))))
)

/** Keep the discovery tier small: no skill bodies or file contents in the base prompt. */
internal fun skillCatalogPrompt(skills: List<AgentSkill>): String {
    if (skills.isEmpty()) return ""
    return buildString {
        appendLine("<available_skills>")
        skills.forEach { skill ->
            appendLine("- ${skill.name}: ${skill.description.replace('\n', ' ')}")
        }
        appendLine("</available_skills>")
        append("When the current task matches a skill description, call activate_skill with its name before following ")
        append("that skill's instructions. Only use read_skill_resource for files referenced by an activated skill. ")
        append("Skills are instructions, not automatic permission to use other tools.")
    }
}

internal val searchToolsDefinition = ToolDefinition(
    SEARCH_TOOLS_TOOL_NAME,
    "Search available feature and enabled-MCP tool names, including disabled tools. Returns only matching names and enabled states. Search by any partial word or name fragment.",
    obj("type" to str("object"), "properties" to obj(
        "query" to obj("type" to str("string"), "description" to str("Tool name or words to search for"))
    ), "required" to JsonArray(listOf(str("query"))))
)

internal data class SearchableTool(val name: String, val enabled: Boolean)

internal fun searchToolCatalog(query: String, catalog: List<SearchableTool>): JsonArray {
    val words = Regex("[\\p{L}\\p{N}]+").findAll(query.lowercase()).map { it.value }.toList()
    return JsonArray(catalog.filter { tool ->
        val name = tool.name.lowercase()
        val parts = Regex("[\\p{L}\\p{N}]+").findAll(name).map { it.value }.toList()
        words.isEmpty() || words.any { word ->
            name.contains(word) || word.contains(name) || parts.any { it.contains(word) || word.contains(it) }
        }
    }.distinctBy { it.name }.map { tool ->
        obj("name" to str(tool.name), "enabled" to JsonPrimitive(tool.enabled))
    })
}
