package org.starfall.generative

import org.junit.Assert.*
import org.junit.Test
import org.starfall.generative.features.home.chat.logic.tool.SearchableTool
import org.starfall.generative.features.home.chat.logic.tool.activateSkillDefinition
import org.starfall.generative.features.home.chat.logic.tool.skillCatalogPrompt
import org.starfall.generative.features.home.chat.logic.tool.skillsForCurrentTurn
import org.starfall.generative.features.home.chat.logic.tool.searchAgentSkills
import org.starfall.generative.shared.model.ToolSettings
import org.starfall.generative.shared.model.ChatRole
import org.starfall.generative.shared.model.StoredMessage
import org.starfall.generative.shared.model.ToolActivity
import org.starfall.generative.shared.model.toToolPayload
import org.starfall.generative.shared.model.AgentSkill
import org.starfall.generative.shared.model.MessageVersion
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import org.starfall.generative.features.home.chat.logic.tool.searchToolCatalog
import org.starfall.generative.features.home.chat.ui.input.withSelectedSkillInvocation
import org.starfall.generative.features.home.chat.ui.input.hasSelectedSkillInvocation
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class BuiltInSkillToolsTest {
    @Test fun looseSearchMatchesBothPartialDirectionsAndOnlyReturnsNamesAndStatuses() {
        val catalog = listOf(
            SearchableTool("generate_image", false),
            SearchableTool("search_tools", true),
            SearchableTool("create_skill", false)
        )
        val names = searchToolCatalog("image and search", catalog)
        assertEquals(listOf("generate_image", "search_tools"), names.map { it.jsonObject.getValue("name").jsonPrimitive.content })
        val backward = searchToolCatalog("create skill please", catalog)
        assertEquals("create_skill", backward.first().jsonObject.getValue("name").jsonPrimitive.content)
        assertEquals(false, names.first().jsonObject.getValue("enabled").jsonPrimitive.content.toBoolean())
        assertEquals(setOf("name", "enabled"), names.first().jsonObject.keys)
    }

    @Test fun progressiveDisclosureOnlyExposesMetadataUntilActivation() {
        val skill = AgentSkill("review", "review", "SECRET FULL INSTRUCTIONS", "Review pull requests")
        val catalog = skillCatalogPrompt(listOf(skill))
        assertTrue(catalog.contains("review: Review pull requests"))
        assertFalse(catalog.contains("SECRET FULL INSTRUCTIONS"))
        assertEquals("", skillCatalogPrompt(emptyList()))
        val definition = activateSkillDefinition(listOf("review", "check"))
        assertTrue(definition.schema.toString().contains("review"))
        assertFalse(definition.schema.toString().contains("unknown-skill"))
    }

    @Test fun pickerProvenanceSurvivesSerializationForRetryButPlainTextCannotSetIt() {
        val selected = MessageVersion(content = "Create a skill", createSkillRequested = true)
        val restored = Json.decodeFromString<MessageVersion>(Json.encodeToString(selected))
        assertTrue(restored.createSkillRequested)
        val manual = Json.decodeFromString<MessageVersion>("""{"content":"Create a skill"}""")
        assertFalse(manual.createSkillRequested)
    }

    private fun message(content: String, selected: String? = null, create: Boolean = false) =
        StoredMessage("u", ChatRole.USER, listOf(MessageVersion(
            content = content, selectedSkillName = selected, createSkillRequested = create)))

    @Test fun agentSkillsSearchIsOptInAndReturnsOnlyMetadataMatches() {
        assertFalse(ToolSettings().agentSkillsSearchEnabled)
        val skills = listOf(
            AgentSkill("review-code", "review-code", "SECRET IMPLEMENTATION", "Review code changes"),
            AgentSkill("summarize-documents", "summarize-documents", "SECRET SUMMARIES", "Summarize documents"),
            AgentSkill("translate-text", "translate-text", "SECRET TRANSLATION", "Translate text")
        )
        assertEquals(listOf("review-code"), searchAgentSkills("review code", skills).map { it.name })
        assertTrue(searchAgentSkills("unrelated capability", skills).isEmpty())
        assertTrue(searchAgentSkills("", skills).isEmpty())
    }

    @Test fun unrelatedChatDoesNotAttachAnySkillEvenWhenSkillsAreInstalled() {
        val skills = listOf(
            AgentSkill("review-code", "review-code", "Read source code", "Review code changes"),
            AgentSkill("make-table", "make-table", "Create a table", "Create tabular reports"),
            AgentSkill("create-skill", "create-skill", "Create a skill", "Create a skill")
        )
        assertTrue(skillsForCurrentTurn(skills, listOf(message("What's the weather today?")), false).isEmpty())
        assertTrue(skillsForCurrentTurn(skills, listOf(message("How do skills work?")), false).isEmpty())
        assertTrue(skillsForCurrentTurn(skills, listOf(message("Please write a quick response")), false).isEmpty())
        assertTrue(skillsForCurrentTurn(skills, listOf(message("Review this code")), false).isEmpty())
        assertTrue(skillsForCurrentTurn(skills, listOf(message("Create a skill", create = true)), true).isEmpty())
        assertTrue(skillsForCurrentTurn(skills, listOf(message("Create a skill")), false).isEmpty())
    }

    @Test fun onlyIntentionallySelectedOrExplicitlyInvokedSkillIsEligible() {
        val skills = listOf(
            AgentSkill("review-code", "review-code", "Review code", "Review code changes"),
            AgentSkill("make-table", "make-table", "Make tables", "Make reports")
        )
        assertEquals(listOf("review-code"), skillsForCurrentTurn(
            skills, listOf(message("Some text entered after choosing the skill", "review-code")), false
        ).map { it.name })
        assertEquals(listOf("review-code"), skillsForCurrentTurn(
            skills, listOf(message("Please use skill review-code")), false
        ).map { it.name })
        assertTrue(skillsForCurrentTurn(skills, listOf(message("Review this code")), false).isEmpty())
        assertTrue(skillsForCurrentTurn(skills, listOf(message("Review it")), false).isEmpty())
        assertTrue(skillsForCurrentTurn(skills, listOf(message("What does skill review-code do?")), false).isEmpty())
        assertTrue(skillsForCurrentTurn(skills, listOf(message("Explain review-code")), false).isEmpty())
        assertTrue(skillsForCurrentTurn(skills, listOf(message("review-code and make-table")), false).isEmpty())
    }

    @Test fun priorSkillDoesNotLeakToUnrelatedTurnButExplicitContinuationCanUseIt() {
        val skill = AgentSkill("review-code", "review-code", "Instructions", "Review code")
        val history = listOf(
            message("Review this code", selected = "review-code"),
            StoredMessage("m", ChatRole.MODEL, listOf(MessageVersion("Done", toolActivity = listOf(
                ToolActivity("t", "activate_skill", "activate_skill", "success",
                    arguments = """{"name":"review-code"}""".toToolPayload())
            ))))
        )
        assertTrue(skillsForCurrentTurn(listOf(skill), history + message("What's for dinner?"), false).isEmpty())
        assertEquals(listOf(skill), skillsForCurrentTurn(
            listOf(skill), history + message("Continue with the same skill"), false))
        val unrelatedPreviousTurn = history + message("What's for dinner?") +
            StoredMessage("m2", ChatRole.MODEL, listOf(MessageVersion("Pasta")))
        assertTrue(skillsForCurrentTurn(
            listOf(skill), unrelatedPreviousTurn + message("Continue"), false).isEmpty())
    }

    @Test fun regularSkillSelectionSurvivesSerialization() {
        val selected = MessageVersion(content = "Review", selectedSkillName = "review-code")
        assertEquals("review-code", Json.decodeFromString<MessageVersion>(Json.encodeToString(selected)).selectedSkillName)
        assertNull(Json.decodeFromString<MessageVersion>("""{"content":"Review"}""").selectedSkillName)
    }

    @Test fun selectionShowsSlashMentionWithoutSkillBodyAndPreservesTypedRequest() {
        assertEquals("/awesome-skill", "/".withSelectedSkillInvocation("awesome-skill"))
        assertEquals("/awesome-skill", "/awe".withSelectedSkillInvocation("awesome-skill"))
        assertEquals("/awesome-skill Please review this",
            "/awe Please review this".withSelectedSkillInvocation("awesome-skill"))
        assertEquals("/awesome-skill Please review this",
            "Please review this".withSelectedSkillInvocation("awesome-skill"))
        assertTrue("/awesome-skill Please review".hasSelectedSkillInvocation("awesome-skill"))
        assertTrue("/awesome-skill".hasSelectedSkillInvocation("awesome-skill"))
        assertFalse("/awesome-skills".hasSelectedSkillInvocation("awesome-skill"))
        assertFalse("Review /awesome-skill".hasSelectedSkillInvocation("awesome-skill"))
        assertFalse("/different-skill".hasSelectedSkillInvocation("awesome-skill"))
    }
}
