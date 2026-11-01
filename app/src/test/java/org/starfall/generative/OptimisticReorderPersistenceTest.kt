package org.starfall.generative

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.starfall.generative.database.AppDatabase
import org.starfall.generative.database.ModelRepository
import org.starfall.generative.integrations.modelapis.AiService
import org.starfall.generative.shared.model.api.ModelApiType
import org.starfall.generative.shared.model.language.ModelConfiguration
import org.starfall.generative.shared.model.provider.ModelProvider
import org.starfall.generative.shared.model.provider.ProviderConfiguration
import org.starfall.generative.shared.model.provider.ProviderGroup
import org.starfall.generative.shared.model.provider.ProviderPlacement
import org.starfall.generative.shared.model.provider.ProviderRootOrderItem

/** Verifies only drop publication/persistence, not the gesture or Launcher hit testing. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = LibraryTestApplication::class)
class OptimisticReorderPersistenceTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test fun providerPlacementIsVisibleWhileRoomIsBusyAndEventuallyCommits() = runBlocking {
        installTestAndroidKeyStore()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val repo = ModelRepository(db, AiService(context))
            repo.saveGroup(ProviderGroup("folder", "Folder", 0))
            repo.saveProvider(ModelProvider("a", "A", ModelApiType.OPENAI, baseUrl = "", sortOrder = 1))
            repo.saveProvider(ModelProvider("b", "B", ModelApiType.OPENAI, baseUrl = "", sortOrder = 2))
            repo.allProviders.first()

            val enteredTransaction = CompletableDeferred<Unit>()
            val releaseTransaction = CompletableDeferred<Unit>()
            val holder = launch(Dispatchers.IO) {
                db.withTransaction {
                    enteredTransaction.complete(Unit)
                    releaseTransaction.await()
                }
            }
            try {
                withTimeout(5_000) { enteredTransaction.await() }
                repo.queueProviderPlacement(ProviderPlacement(
                    providerId = "a",
                    groupId = "folder",
                    rootOrder = listOf(ProviderRootOrderItem("folder", true), ProviderRootOrderItem("b", false)),
                    groupOrders = mapOf("folder" to listOf("a"))
                ))
                // No Room transaction can finish yet: this must read the RAM projection.
                val visible = repo.allProviders.first()
                assertEquals("folder", visible.first { it.id == "a" }.groupId)
                assertEquals(listOf("a"), visible.filter { it.groupId == "folder" }.map { it.id })
            } finally {
                releaseTransaction.complete(Unit)
                withTimeout(5_000) { holder.join() }
            }
            withTimeout(8_000) {
                while (repo.getProviderById("a")?.groupId != "folder") delay(20)
            }
        } finally {
            db.close()
        }
    }

    @Test fun rootOrderPublishesRealRanksBeforeRoomCanWrite() = runBlocking {
        installTestAndroidKeyStore()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val repo = ModelRepository(db, AiService(context))
            repo.saveGroup(ProviderGroup("g1", "First", 0))
            repo.saveGroup(ProviderGroup("g2", "Second", 1))
            repo.saveProvider(ModelProvider("p", "Provider", ModelApiType.OPENAI,
                baseUrl = "", sortOrder = 2))
            repo.allProviders.first()
            val enteredTransaction = CompletableDeferred<Unit>()
            val releaseTransaction = CompletableDeferred<Unit>()
            val holder = launch(Dispatchers.IO) {
                db.withTransaction {
                    enteredTransaction.complete(Unit)
                    releaseTransaction.await()
                }
            }
            try {
                withTimeout(5_000) { enteredTransaction.await() }
                repo.queueReorderRootItems(listOf(
                    ProviderRootOrderItem("p", false),
                    ProviderRootOrderItem("g2", true),
                    ProviderRootOrderItem("g1", true)
                ))
                assertEquals(0, repo.allProviders.first().first { it.id == "p" }.sortOrder)
                assertEquals(listOf("g2", "g1"), repo.allGroups.first().map { it.id })
                assertEquals(listOf(1, 2), repo.allGroups.first().map { it.sortOrder })
            } finally {
                releaseTransaction.complete(Unit)
                withTimeout(5_000) { holder.join() }
            }
            withTimeout(8_000) {
                while (repo.getProviderById("p")?.sortOrder != 0 ||
                    repo.allGroups.first().associate { it.id to it.sortOrder } !=
                    mapOf("g2" to 1, "g1" to 2)) delay(20)
            }
        } finally {
            db.close()
        }
    }

    @Test fun editorModelOrderIsVisibleWhileRoomIsBusyAndEventuallyCommits() = runBlocking {
        installTestAndroidKeyStore()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val repo = ModelRepository(db, AiService(context))
            val initialIds = listOf("one", "two", "three")
            val reordered = listOf("three", "one", "two")
            repo.saveProvider(ModelProvider(
                "editor", "Editor", ModelApiType.OPENAI, baseUrl = "",
                config = ProviderConfiguration(
                    modelIds = initialIds,
                    modelConfigs = initialIds.associateWith { ModelConfiguration() }
                )
            ))
            repo.allProviders.first()
            val enteredTransaction = CompletableDeferred<Unit>()
            val releaseTransaction = CompletableDeferred<Unit>()
            val holder = launch(Dispatchers.IO) {
                db.withTransaction {
                    enteredTransaction.complete(Unit)
                    releaseTransaction.await()
                }
            }
            try {
                withTimeout(5_000) { enteredTransaction.await() }
                repo.queueReorderProviderModels("editor", reordered)
                val visible = repo.allProviders.first().first { it.id == "editor" }
                assertEquals(reordered, visible.config.modelIds)
                assertEquals(reordered, visible.config.modelConfigs.keys.toList())
            } finally {
                releaseTransaction.complete(Unit)
                withTimeout(5_000) { holder.join() }
            }
            withTimeout(8_000) {
                while (repo.getProviderById("editor")?.config?.modelIds != reordered) delay(20)
            }
            assertEquals(initialIds.toSet(),
                repo.getProviderById("editor")!!.config.modelConfigs.keys.toSet())
        } finally {
            db.close()
        }
    }
}
