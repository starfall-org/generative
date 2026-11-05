package org.starfall.generative

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.starfall.generative.database.room.AppDatabase
import org.starfall.generative.database.room.entities.ConversationMigrationQuarantineEntity
import org.starfall.generative.database.room.entities.ConversationEntity
import org.starfall.generative.database.preferences.AppPreferencesRepository
import org.starfall.generative.shared.model.*
import org.starfall.generative.database.repositories.ConversationRepository

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SidebarStorageTest {
    @Test fun ttsCodeBlockPreferenceDefaultsOffAndPersists() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val repository = AppPreferencesRepository(context)
        assertFalse(repository.appPreferencesFlow.first().ttsReadCodeBlocks)
        try {
            repository.setTtsReadCodeBlocks(true)
            assertTrue(AppPreferencesRepository(context).appPreferencesFlow.first().ttsReadCodeBlocks)
        } finally { repository.setTtsReadCodeBlocks(false) }
    }

    @Test fun modelProvenanceSurvivesRepositoryReloadAndVersionSwitch() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java).build()
        try {
            val versions = listOf(
                MessageVersion(content = "First answer", providerId = "p1", modelId = "m1", modelDisplayName = "First model"),
                MessageVersion(content = "Second answer", providerId = "p2", modelId = "m2", modelDisplayName = "Second model")
            )
            val conversation = Conversation("c", "Chat", 1, 1,
                messages = listOf(StoredMessage("a", ChatRole.MODEL, versions, activeVersionIndex = 1)))
            ConversationRepository(db).saveConversation(conversation)
            val restored = ConversationRepository(db).getById("c")!!.messages.single()
            assertEquals("Second model", restored.activeVersion.modelDisplayName)
            assertEquals("p2", restored.activeVersion.providerId)
            assertEquals("First model", restored.copy(activeVersionIndex = 0).activeVersion.modelDisplayName)
        } finally { db.close() }
    }

    @Test fun conversationListReadsMetadataWithoutDecodingMessageOrSummaryJson() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java).build()
        try {
            db.conversationDao().insertOrUpdate(
                ConversationEntity(
                    id = "broken-history",
                    title = "Metadata only",
                    createdAt = 1,
                    updatedAt = 2,
                    tokenCount = 7,
                    providerId = "provider",
                    modelId = "model",
                    profileId = "profile",
                    reasoningEffort = "high"
                )
            )
            db.conversationDao().insertMigrationIssue(
                ConversationMigrationQuarantineEntity(
                    conversationId = "broken-history",
                    payloadKind = "messages_json",
                    rawJson = "not valid json",
                    errorCode = "invalid_json"
                )
            )
            db.conversationDao().insertMigrationIssue(
                ConversationMigrationQuarantineEntity(
                    conversationId = "broken-history",
                    payloadKind = "summary_json",
                    rawJson = "also not valid json",
                    errorCode = "invalid_json"
                )
            )

            val item = ConversationRepository(db).conversationList.first().single()

            assertEquals("broken-history", item.id)
            assertEquals("Metadata only", item.title)
            assertEquals(7, item.tokenCount)
            assertEquals("provider", item.providerId)
            assertEquals("model", item.modelId)
            assertEquals("profile", item.profileId)
            assertEquals("high", item.reasoningEffort)
            assertTrue(item.hasMessages)
            assertTrue(item.hasSummary)
        } finally { db.close() }
    }

    @Test fun loadByIdRemainsAnExplicitFullHistoryRead() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java).build()
        try {
            val conversation = Conversation(
                id = "selected",
                title = "Selected",
                createdAt = 1,
                updatedAt = 1,
                messages = listOf(
                    StoredMessage("message", ChatRole.USER, listOf(MessageVersion(content = "Loaded")))
                )
            )
            ConversationRepository(db).saveConversation(conversation)

            assertEquals(conversation, ConversationRepository(db).loadById("selected"))
        } finally { db.close() }
    }
    @Test fun bulkDeleteKeepsUnselectedConversationAndItsMessages() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java).build()
        try {
            val repository = ConversationRepository(db)
            val chats = (1..3).map { number -> Conversation(id = "$number", title = "Chat $number", createdAt = 1, updatedAt = 1,
                messages = listOf(StoredMessage("m$number", ChatRole.USER, listOf(MessageVersion(content = "Keep $number"))))) }
            chats.forEach { repository.saveConversation(it) }
            repository.deleteConversations(listOf("1", "3"))
            assertNull(repository.getById("1"))
            assertNull(repository.getById("3"))
            assertEquals(chats[1], repository.getById("2"))
            repository.deleteConversations(emptyList())
            assertEquals(chats[1], repository.getById("2"))
        } finally { db.close() }
    }
    @Test fun folderAndPinChangesPersistAcrossRepositoryInstances() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val repository = AppPreferencesRepository(context)
        val saved = SidebarOrganization(listOf(ChatFolder("f", "Folder", true, false)), mapOf("chat" to "f"), setOf("chat"))
        try {
            repository.updateSidebar { saved }
            assertEquals(saved, AppPreferencesRepository(context).appPreferencesFlow.first().chatRelations.sidebar)
            repository.updateSidebar { it.removeFolder("f") }
            val updated = repository.appPreferencesFlow.first().chatRelations.sidebar
            assertTrue(updated.folders.isEmpty())
            assertTrue(updated.chatFolders.isEmpty())
            assertEquals(setOf("chat"), updated.pinnedChatIds)
        } finally { repository.updateSidebar { SidebarOrganization() } }
    }

    @Test fun providerCollapseStatePersistsAcrossRepositoryInstances() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val repository = AppPreferencesRepository(context)
        try {
            repository.setProvidersCollapsedSections(setOf("group-a", "__ungrouped__"))
            repository.setModelPickerCollapsedGroups(setOf("group-b"))
            repository.setModelPickerCollapsedProviders(setOf("provider-a", "provider-b"))

            val restored = AppPreferencesRepository(context).appPreferencesFlow.first()
            assertEquals(setOf("group-a", "__ungrouped__"), restored.providersCollapsedSections)
            assertEquals(setOf("group-b"), restored.modelPickerCollapsedGroups)
            assertEquals(setOf("provider-a", "provider-b"), restored.modelPickerCollapsedProviders)
        } finally {
            repository.setProvidersCollapsedSections(emptySet())
            repository.setModelPickerCollapsedGroups(emptySet())
            repository.setModelPickerCollapsedProviders(emptySet())
        }
    }

}
