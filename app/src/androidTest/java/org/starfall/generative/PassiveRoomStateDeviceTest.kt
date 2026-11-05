package org.starfall.generative

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.starfall.generative.database.room.AppDatabase
import org.starfall.generative.database.repositories.ConversationRepository
import org.starfall.generative.database.repositories.ModelRepository
import org.starfall.generative.database.room.entities.ConversationEntity
import org.starfall.generative.database.room.entities.ProviderGroupEntity
import org.starfall.generative.integrations.modelapis.AiService
import org.starfall.generative.shared.model.Conversation
import org.starfall.generative.shared.model.provider.ProviderGroup

/** Tests real Room persistence without subscribing to its table invalidations. */
@RunWith(AndroidJUnit4::class)
class PassiveRoomStateDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun externalRoomSortChangesCannotRefreshAnActiveProviderCatalog() = runBlocking {
        val databaseName = "passive-providers-${UUID.randomUUID()}.db"
        val db = Room.databaseBuilder(context, AppDatabase::class.java, databaseName).build()
        try {
            val repository = ModelRepository(db, AiService(context))
            assertTrue(repository.allGroups.first().isEmpty())
            // This SQL write is intentionally NOT an application command.
            db.providerGroupDao().insertOrUpdate(ProviderGroupEntity("external", "Outside", 0, null))
            assertTrue("Room invalidations must never refresh a running catalog",
                repository.allGroups.first().isEmpty())

            repository.saveGroup(ProviderGroup("local", "Local action", 1))
            assertEquals(listOf("local"), repository.allGroups.first().map { it.id })

            // A newly constructed repository can still bootstrap from durable storage.
            val fresh = ModelRepository(db, AiService(context))
            assertEquals(setOf("external", "local"), fresh.allGroups.first().map { it.id }.toSet())
        } finally {
            db.close()
            context.deleteDatabase(databaseName)
        }
    }

    @Test fun conversationHistoryChangesOnlyAfterExplicitRepositoryActions() = runBlocking {
        val databaseName = "passive-conversations-${UUID.randomUUID()}.db"
        val db = Room.databaseBuilder(context, AppDatabase::class.java, databaseName).build()
        try {
            val repository = ConversationRepository(db)
            assertTrue(repository.conversationList.first().isEmpty())
            db.conversationDao().insertOrUpdate(ConversationEntity(
                id = "external", title = "Stored externally", createdAt = 1, updatedAt = 2,
                tokenCount = 0, providerId = "", modelId = "", profileId = null, reasoningEffort = null
            ))
            assertTrue("Room changes cannot publish to a running history list",
                repository.conversationList.first().isEmpty())

            repository.saveConversation(Conversation("local", "Created from UI", 3, 4))
            assertEquals(listOf("local"), repository.conversationList.first().map { it.id })
            repository.renameConversation("local", "Renamed")
            assertEquals("Renamed", repository.conversationList.first().single().title)
            repository.deleteConversation("local")
            assertTrue(repository.conversationList.first().isEmpty())

            val fresh = ConversationRepository(db)
            assertEquals(listOf("external"), fresh.conversationList.first().map { it.id })
        } finally {
            db.close()
            context.deleteDatabase(databaseName)
        }
    }
}
