package org.starfall.generative

import org.junit.Assert.assertEquals
import org.junit.Test
import org.starfall.generative.shared.model.ChatFolder
import org.starfall.generative.shared.model.InstructionLibrary
import org.starfall.generative.shared.model.SavedInstruction
import org.starfall.generative.shared.model.SidebarOrganization
import org.starfall.generative.shared.storage.preferences.AppPreferences
import org.starfall.generative.shared.storage.preferences.ChatRelations
import org.starfall.generative.shared.storage.preferences.InstructionSelectionMode
import org.starfall.generative.shared.storage.preferences.UNFILED_INSTRUCTION_SCOPE

class InstructionSelectionModeTest {
    private val library = InstructionLibrary(
        instructions = listOf(
            SavedInstruction("a", "A", "Instruction A"),
            SavedInstruction("b", "B", "Instruction B")
        ),
        selectedIds = setOf("b")
    )
    private val preferences = AppPreferences(
        instructionLibrary = library,
        chatRelations = ChatRelations(
            sidebar = SidebarOrganization(
                folders = listOf(ChatFolder("folder", "Folder")),
                chatFolders = mapOf("chat-a" to "folder", "chat-b" to "folder")
            ),
            folderInstructionSelections = mapOf(
                "folder" to setOf("a"),
                UNFILED_INSTRUCTION_SCOPE to setOf("b")
            )
        )
    )

    private fun selected(preferences: AppPreferences, chatId: String?, own: Set<String>? = null): Set<String> =
        preferences.instructionLibraryForSelection(own, chatId).selectedIds

    @Test fun defaultUsesFolderWhenPresentOtherwiseIndependentConversation() {
        assertEquals(InstructionSelectionMode.PER_CHAT_OR_FOLDER, preferences.instructionSelectionMode)
        assertEquals(setOf("a"), selected(preferences, "chat-a"))
        assertEquals(setOf("a"), selected(preferences, "chat-b", setOf("b")))
        assertEquals(setOf("b"), selected(preferences, "unfiled", setOf("b")))
        assertEquals(emptySet<String>(), selected(preferences, "new-chat"))
        assertEquals(emptySet<String>(), selected(preferences, null))
        assertEquals("Instruction A", preferences.instructionLibraryForSelection(null, "chat-a").systemPrompt())
    }

    @Test fun perChatDoesNotInheritGlobalSelection() {
        val mode = preferences.copy(instructionSelectionMode = InstructionSelectionMode.PER_CHAT)
        assertEquals(setOf("b"), selected(mode, "chat-a", setOf("b")))
        assertEquals(emptySet<String>(), selected(mode, "chat-b"))
        assertEquals(emptySet<String>(), selected(mode, "unfiled"))
    }

    @Test fun globalIgnoresConversationAndFolderSelections() {
        val mode = preferences.copy(instructionSelectionMode = InstructionSelectionMode.GLOBAL)
        assertEquals(setOf("b"), selected(mode, "chat-a", setOf("a")))
        assertEquals(setOf("b"), selected(mode, "unfiled"))
        assertEquals(setOf("b"), selected(mode, null))
    }

    @Test fun perFolderSharesSelectionAlsoAmongUnfiledChats() {
        val mode = preferences.copy(instructionSelectionMode = InstructionSelectionMode.PER_FOLDER)
        assertEquals(setOf("a"), selected(mode, "chat-a", setOf("b")))
        assertEquals(setOf("a"), selected(mode, "chat-b"))
        assertEquals(setOf("b"), selected(mode, "unfiled"))
        assertEquals(setOf("b"), selected(mode, "another-unfiled", setOf("a")))
    }

    @Test fun perChatRememberGlobalUsesLatestDefaultOnlyWhenChatHasNoSelection() {
        val mode = preferences.copy(instructionSelectionMode = InstructionSelectionMode.PER_CHAT_REMEMBER_GLOBAL)
        assertEquals(setOf("a"), selected(mode, "chat-a", setOf("a")))
        assertEquals(emptySet<String>(), selected(mode, "chat-b", emptySet()))
        assertEquals(setOf("b"), selected(mode, "new-chat"))
    }

    @Test fun movingChatInAndOutOfFolderChangesItsEffectiveScopeWithoutDestroyingOwnSelection() {
        val movedOutside = preferences.copy(chatRelations = preferences.chatRelations.copy(
            sidebar = preferences.chatRelations.sidebar.moveChats(setOf("chat-a"), null)
        ))
        assertEquals(setOf("b"), selected(movedOutside, "chat-a", setOf("b")))
        val movedBack = movedOutside.copy(chatRelations = movedOutside.chatRelations.copy(
            sidebar = movedOutside.chatRelations.sidebar.moveChats(setOf("chat-a"), "folder")
        ))
        assertEquals(setOf("a"), selected(movedBack, "chat-a", setOf("b")))
    }
}
