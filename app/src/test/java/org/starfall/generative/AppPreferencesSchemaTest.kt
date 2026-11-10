package org.starfall.generative

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.starfall.generative.features.appdata.logic.readZipBackup
import org.starfall.generative.shared.model.AgentSkill
import org.starfall.generative.shared.model.AgentSkills
import org.starfall.generative.shared.model.InstructionLibrary
import org.starfall.generative.shared.model.SavedInstruction
import org.starfall.generative.integrations.files.AgentSkillsStore
import org.starfall.generative.shared.model.DEFAULT_AGENT_SKILL_ID
import org.starfall.generative.shared.model.preferences.AppPreferences
import org.starfall.generative.database.preferences.AppPreferencesRepository
import org.starfall.generative.shared.model.preferences.InstructionSelectionMode
import org.starfall.generative.shared.model.preferences.UNFILED_INSTRUCTION_SCOPE
import org.starfall.generative.shared.model.ChatFolder
import org.starfall.generative.shared.model.preferences.SelectedModel

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AppPreferencesSchemaTest {
    @Test
    fun selectedModelRelationsInstructionsAndSkillsPersistAsTheirOwnGroups() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repository = AppPreferencesRepository(context)
        val instruction = SavedInstruction("instruction-1", "System rules", "Be precise")
        val library = InstructionLibrary(listOf(instruction), setOf(instruction.id))
        val skill = AgentSkill("research", "research", "Gather evidence", "Research skill for gathering evidence.")
        try {
            repository.setSelectedModel("provider-1", "model-1")
            repository.updateSidebar { it.copy(pinnedChatIds = setOf("chat-1")) }
            repository.setLastActiveConversationId("chat-1")
            repository.setMcpPresetsInitialized(true)
            repository.setContentApiPresetInitialized(true)
            repository.setInstructionLibrary(library)
            repository.setAgentSkills(listOf(skill))
            assertTrue(context.filesDir.resolve("agent-skills/research/SKILL.md").isFile)

            val restored = AppPreferencesRepository(context).appPreferencesFlow.first()
            assertEquals(SelectedModel("provider-1", "model-1"), restored.selectedModel)
            assertEquals(setOf("chat-1"), restored.chatRelations.sidebar.pinnedChatIds)
            assertEquals("chat-1", restored.chatRelations.lastActiveConversationId)
            assertTrue(restored.chatRelations.mcpPresetsInitialized)
            assertTrue(restored.chatRelations.contentApiPresetInitialized)
            assertEquals(library, restored.instructionLibrary)
            val bundled = AgentSkillsStore(context).readBundledCreateSkill(context)
            assertEquals(listOf(bundled, skill), restored.agentSkills.skills)
            assertTrue(bundled.content.contains("Call `create_skill`"))
        } finally {
            repository.replace(AppPreferences())
        }
    }

    @Test
    fun instructionModeAndFolderSelectionsSurvivePersistenceAndFolderRemoval() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repository = AppPreferencesRepository(context)
        try {
            repository.updateSidebar {
                it.copy(folders = listOf(ChatFolder("folder", "Work"))).moveChats(setOf("chat-a"), "folder")
            }
            repository.setInstructionSelectionMode(InstructionSelectionMode.PER_FOLDER)
            repository.setFolderInstructionSelection("folder", setOf("instruction-1"))
            repository.setFolderInstructionSelection(UNFILED_INSTRUCTION_SCOPE, emptySet())
            val restored = AppPreferencesRepository(context).appPreferencesFlow.first()
            assertEquals(InstructionSelectionMode.PER_FOLDER, restored.instructionSelectionMode)
            assertEquals(setOf("instruction-1"), restored.chatRelations.folderInstructionSelections["folder"])
            assertEquals(emptySet<String>(), restored.chatRelations.folderInstructionSelections[UNFILED_INSTRUCTION_SCOPE])
            assertEquals("folder", restored.chatRelations.sidebar.chatFolders["chat-a"])
            repository.updateSidebar { it.removeFolder("folder") }
            val afterRemoval = AppPreferencesRepository(context).appPreferencesFlow.first()
            assertFalse("folder" in afterRemoval.chatRelations.folderInstructionSelections)
            assertTrue(UNFILED_INSTRUCTION_SCOPE in afterRemoval.chatRelations.folderInstructionSelections)
        } finally {
            repository.replace(AppPreferences())
        }
    }

    @Test
    fun bundledCreateSkillCannotBeDeletedOrOverwrittenByRuntimeChanges() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repository = AppPreferencesRepository(context)
        val bundled = AgentSkillsStore(context).readBundledCreateSkill(context)
        try {
            assertTrue(bundled.content.startsWith("# Create an Agent Skill"))
            assertTrue(bundled.description.contains("when the user"))
            repository.setAgentSkills(listOf(bundled.copy(content = "Overwritten")))
            assertEquals(bundled, AppPreferencesRepository(context).appPreferencesFlow.first()
                .agentSkills.skills.single { it.id == DEFAULT_AGENT_SKILL_ID })

            repository.replace(AppPreferences(agentSkills = AgentSkills(emptyList())))
            assertEquals(bundled, AppPreferencesRepository(context).appPreferencesFlow.first()
                .agentSkills.skills.single { it.id == DEFAULT_AGENT_SKILL_ID })
        } finally {
            repository.replace(AppPreferences())
        }
    }

    @Test
    fun restoredPreferenceSnapshotRecreatesSkillFilesInsideAppdata() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repository = AppPreferencesRepository(context)
        val skill = AgentSkill("backup-skill", "backup-skill", "Follow backup instructions", "Restorable skill")
        val skillFile = context.filesDir.resolve("agent-skills/backup-skill/SKILL.md")
        try {
            repository.setAgentSkills(listOf(skill))
            val snapshot = repository.appPreferencesFlow.first()
            assertTrue(skillFile.isFile)
            repository.setAgentSkills(emptyList())
            assertFalse(skillFile.exists())
            repository.replace(snapshot)
            assertTrue(skillFile.isFile)
            assertEquals(skill, AppPreferencesRepository(context).appPreferencesFlow.first()
                .agentSkills.skills.single { it.id == skill.id })
        } finally {
            repository.replace(AppPreferences())
        }
    }

    @Test
    fun versionTwoBackupMigratesFlatPreferencesAndLegacySystemPrompt() {
        val legacyManifest = """
            {
              "formatVersion": 2,
              "exportedAt": "2026-01-01T00:00:00Z",
              "type": "full_backup",
              "appState": {
                "sidebar": {"folders": [], "chatFolders": {}, "pinnedChatIds": []},
                "selectedProfileId": "removed-profile-feature",
                "selectedProviderId": "provider-legacy",
                "selectedModelId": "model-legacy",
                "defaultSystemPrompt": "Keep this legacy prompt",
                "instructionLibrary": null,
                "agentSkills": [{"id": "skill-legacy", "name": "Legacy skill", "content": "Keep this skill"}],
                "lastActiveConversationId": "chat-legacy",
                "mcpPresetsInitialized": true,
                "contentApiPresetInitialized": true
              }
            }
        """.trimIndent()
        val archive = ByteArrayOutputStream().also { bytes ->
            ZipOutputStream(bytes).use { zip ->
                zip.putNextEntry(ZipEntry("manifest.json"))
                zip.write(legacyManifest.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }.toByteArray()
        val directory = Files.createTempDirectory("app-preferences-v2-").toFile()

        try {
            val migrated = readZipBackup(ByteArrayInputStream(archive), directory).data
            assertEquals(3, migrated.formatVersion)
            assertEquals("provider-legacy", migrated.appPreferences.selectedModel.providerId)
            assertEquals("model-legacy", migrated.appPreferences.selectedModel.modelId)
            assertEquals("Keep this legacy prompt", migrated.appPreferences.instructionLibrary.systemPrompt())
            assertEquals("chat-legacy", migrated.appPreferences.chatRelations.lastActiveConversationId)
            assertTrue(migrated.appPreferences.chatRelations.mcpPresetsInitialized)
            assertTrue(migrated.appPreferences.chatRelations.contentApiPresetInitialized)
            assertEquals("Keep this skill", migrated.appPreferences.agentSkills.skills.single().content)
        } finally {
            directory.deleteRecursively()
        }
    }
}
