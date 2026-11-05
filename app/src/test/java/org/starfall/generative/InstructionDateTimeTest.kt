package org.starfall.generative

import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.starfall.generative.shared.model.ChatRole
import org.starfall.generative.shared.model.InstructionLibrary
import org.starfall.generative.shared.model.SavedInstruction
import org.starfall.generative.database.preferences.AppPreferences
import org.starfall.generative.database.preferences.InstructionSelectionMode

class InstructionDateTimeTest {
    private val first = ZonedDateTime.of(2026, 10, 31, 9, 14, 25, 0, ZoneId.of("Asia/Bangkok"))
    private val later = first.plusMinutes(4)

    @Test fun selectedSystemAndRoleInstructionsExpandDateTimeWithLocalOffset() {
        val library = InstructionLibrary(
            instructions = listOf(
                SavedInstruction("system", "System", "Now: {{datetime}}. Again: {{datetime}}"),
                SavedInstruction("user", "User", "At {{datetime}}", ChatRole.USER),
                SavedInstruction("assistant", "Assistant", "Time {{datetime}}", ChatRole.MODEL),
                SavedInstruction("off", "Disabled", "Ignore {{datetime}}")
            ),
            selectedIds = setOf("system", "user", "assistant")
        )
        val preferences = AppPreferences(instructionLibrary = library, instructionSelectionMode = InstructionSelectionMode.GLOBAL)
        val expected = "2026-10-31T09:14:25+07:00"
        assertEquals("Now: $expected. Again: $expected", preferences.systemPromptFor(null, first))
        val messages = preferences.instructionRoleMessagesFor(null, first)
        assertEquals(listOf(ChatRole.USER, ChatRole.MODEL), messages.map { it.role })
        assertEquals(listOf("At $expected", "Time $expected"), messages.map { it.content })
        assertEquals("2026-10-31T09:18:25+07:00", library.roleMessages(later).first().content.removePrefix("At "))
        assertFalse(library.systemPrompt(first).contains("Ignore"))
    }

    @Test fun savedInstructionRemainsUnchangedAndUnknownVariablesRemainLiteral() {
        val original = InstructionLibrary(
            listOf(SavedInstruction("a", "Instruction", "{{datetime}} {{unknown}} {{DATEtime}}")),
            setOf("a")
        )
        val restored = Json.decodeFromString<InstructionLibrary>(Json.encodeToString(original))
        assertEquals(original, restored)
        assertEquals("{{datetime}} {{unknown}} {{DATEtime}}", restored.instructions.single().content)
        assertEquals("2026-10-31T09:14:25+07:00 {{unknown}} {{DATEtime}}", restored.systemPrompt(first))
        assertEquals("2026-10-31T09:18:25+07:00 {{unknown}} {{DATEtime}}", restored.systemPrompt(later))
        assertTrue(restored.instructions.single().content.contains("{{datetime}}"))
    }

    @Test fun missingVariableDoesNotChangeExistingInstructionText() {
        val library = InstructionLibrary(
            listOf(SavedInstruction("a", "Plain", "Keep this exactly as it is")), setOf("a")
        )
        assertEquals("Keep this exactly as it is", library.systemPrompt(first))
        assertEquals("Keep this exactly as it is", library.systemPrompt(later))
    }
}
