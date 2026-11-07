package org.starfall.generative.database.preferences

import org.starfall.generative.integrations.files.AgentSkillsStore
import org.starfall.generative.shared.model.AgentSkill
import org.starfall.generative.shared.model.DEFAULT_AGENT_SKILL_ID
import org.starfall.generative.shared.model.normalizeAgentSkillName

/** Apply the same normalization to editor saves and full-backup restores. */
internal fun normalizeStoredAgentSkills(skills: List<AgentSkill>): List<AgentSkill> =
    skills
        .filterNot { it.id == DEFAULT_AGENT_SKILL_ID ||
            normalizeAgentSkillName(it.name) == DEFAULT_AGENT_SKILL_ID }
        .mapNotNull { skill ->
            val name = normalizeAgentSkillName(skill.name)
            val content = skill.content.trim()
            if (content.isBlank()) return@mapNotNull null
            skill.copy(
                id = name,
                name = name,
                content = content,
                description = skill.description.trim().ifBlank {
                    "Reusable skill for $name."
                }.take(AgentSkillsStore.MAX_DESCRIPTION_LENGTH)
            )
        }
        .distinctBy { it.name }
