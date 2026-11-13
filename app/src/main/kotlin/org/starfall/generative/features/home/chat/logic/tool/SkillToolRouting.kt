package org.starfall.generative.features.home.chat.logic.tool

import org.starfall.generative.shared.model.AgentSkill
import org.starfall.generative.shared.model.ChatRole
import org.starfall.generative.shared.model.DEFAULT_AGENT_SKILL_ID
import org.starfall.generative.shared.model.StoredMessage
import org.starfall.generative.shared.helpers.networking.text

/**
 * Gate Agent Skills per user turn. A global skill catalog is not evidence that the user
 * wants any skill: attaching it unconditionally encourages spurious tool calls.
 */
internal suspend fun skillsForCurrentTurn(
    available: List<AgentSkill>,
    messages: List<StoredMessage>,
    createSkillRequested: Boolean
): List<AgentSkill> {
    val candidates = available.filter { it.name.isNotBlank() && it.description.isNotBlank() &&
        it.name != DEFAULT_AGENT_SKILL_ID }.distinctBy { it.name }
    val userMessages = messages.filter { it.role == ChatRole.USER }
    val latest = userMessages.lastOrNull()?.activeVersion ?: return emptyList()

    // The picker records provenance separately from message text. No amount of manually
    // typed text is allowed to give the model create_skill access.
    latest.selectedSkillName?.let { selected ->
        return candidates.filter { it.name == selected || it.id == selected }
    }
    if (createSkillRequested || latest.createSkillRequested) return emptyList()

    val input = latest.content.trim()
    if (input.isEmpty()) return emptyList()

    // Explicit activation requires an invocation verb or slash command. Merely asking
    // about a skill is not permission to attach its tools.
    val explicit = candidates.filter { skill ->
        val escaped = Regex.escape(skill.name)
        Regex("(?i)(?:^|\\s)/(?:$escaped)(?=\\s|$|[.,;!?])").containsMatchIn(input) ||
            Regex("(?i)\\b(?:use|run|apply|load|activate|invoke|dùng|chạy|áp dụng|sử dụng|kích hoạt)\\s+" +
                "(?:(?:the|a)\\s+)?(?:(?:agent)\\s+)?(?:skill|kỹ năng|kĩ năng)\\s*[:=]?\\s*" +
                "`?$escaped(?=\\s|$|[.,;!?`])").containsMatchIn(input)
    }
    if (explicit.size == 1) return explicit
    if (explicit.size > 1) return emptyList() // Ambiguous: don't guess a skill.
    // Explicit continuation is allowed only when the immediately preceding user
    // turn actually selected or activated this skill; questions never inherit it.
    if (!isSkillContinuation(input)) return emptyList()
    val latestUserIndex = messages.indexOfLast { it.role == ChatRole.USER }
    val previousUserIndex = messages.subList(0, latestUserIndex)
        .indexOfLast { it.role == ChatRole.USER }
    if (previousUserIndex < 0) return emptyList()
    val previousTurn = messages.subList(previousUserIndex, latestUserIndex)
    val prior = previousTurn.first().activeVersion.selectedSkillName
    val lastActivation = previousTurn.asReversed().firstNotNullOfOrNull { message ->
        if (message.role != ChatRole.MODEL) null else
            message.activeVersion.toolActivity.lastOrNull {
                it.wireName == ACTIVATE_SKILL_TOOL_NAME && it.status == "success"
            }?.nameArgument()
    }
    val continuedName = lastActivation ?: prior
    return candidates.filter { it.name == continuedName }
}

private fun isSkillContinuation(text: String): Boolean =
    Regex("(?i)^\\s*(?:continue|keep going|go on|same skill|again with|tiếp tục|làm tiếp|dùng skill vừa rồi|tiếp theo)\\b")
        .containsMatchIn(text)
