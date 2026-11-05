package org.starfall.generative

import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.starfall.generative.database.room.AppDatabase
import org.starfall.generative.database.repositories.McpRepository
import org.starfall.generative.database.repositories.ModelRepository
import org.starfall.generative.database.repositories.ProfileRepository
import org.starfall.generative.database.repositories.SpeechRepository
import org.starfall.generative.integrations.mcp.McpService
import org.starfall.generative.integrations.modelapis.AiService
import org.starfall.generative.shared.model.ChatProfile
import org.starfall.generative.shared.model.McpInfo
import org.starfall.generative.shared.model.SpeechService
import org.starfall.generative.shared.model.api.ModelApiType
import org.starfall.generative.shared.model.language.ModelConfiguration
import org.starfall.generative.shared.model.provider.ModelProvider
import org.starfall.generative.shared.model.provider.ProviderConfiguration
import org.starfall.generative.shared.model.provider.ProviderGroup
import org.starfall.generative.shared.model.provider.ProviderPlacement
import org.starfall.generative.shared.model.provider.ProviderRootOrderItem

/**
 * Real-device persistence tests for drop callbacks. This uses an isolated, file-backed Room
 * database (not the user's database), so writes exercise real device storage and can be reopened.
 * Gesture hit-testing and the Launcher test screen are deliberately outside this test's scope.
 */
@RunWith(AndroidJUnit4::class)
class OptimisticReorderPersistenceDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var databaseName: String
    private lateinit var db: AppDatabase
    private lateinit var repository: ModelRepository

    @Before fun setUp() {
        databaseName = "reorder-device-test-${UUID.randomUUID()}.db"
        db = Room.databaseBuilder(context, AppDatabase::class.java, databaseName).build()
        repository = ModelRepository(db, AiService(context))
    }

    @After fun tearDown() {
        db.close()
        context.deleteDatabase(databaseName)
    }

    private suspend fun <T> reopened(block: suspend (AppDatabase) -> T): T {
        val otherConnection = Room.databaseBuilder(context, AppDatabase::class.java, databaseName).build()
        return try { block(otherConnection) } finally { otherConnection.close() }
    }

    private suspend fun observedRoot(repo: ModelRepository): List<ProviderRootOrderItem> {
        val folders = repo.allGroups.first()
        val providers = repo.allProviders.first()
        val groupIds = folders.mapTo(hashSetOf()) { it.id }
        return (folders.map { Triple(it.sortOrder, 0, ProviderRootOrderItem(it.id, true)) } +
            providers.filter { it.groupId == null || it.groupId !in groupIds }
                .map { Triple(it.sortOrder, 1, ProviderRootOrderItem(it.id, false)) })
            .sortedWith(compareBy<Triple<Int, Int, ProviderRootOrderItem>> { it.first }
                .thenBy { it.second }.thenBy { it.third.id })
            .map { it.third }
    }

    private fun provider(id: String, order: Int, group: String? = null) =
        ModelProvider(id, "Test Provider $id", ModelApiType.OPENAI,
            baseUrl = "https://example.invalid/v1", sortOrder = order, groupId = group)

    @Test fun providerDropUpdatesMembershipAndFolderPositionsBeforeRoomIsFree() = runBlocking {
        repository.saveGroup(ProviderGroup("g1", "First", 0))
        repository.saveGroup(ProviderGroup("g2", "Second", 1))
        repository.saveProvider(provider("inside", 0, "g1"))
        repository.saveProvider(provider("moving", 2))
        repository.saveProvider(provider("remaining", 3))
        repository.allProviders.first()

        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val holdingRoom = async(Dispatchers.IO) {
            db.withTransaction { entered.complete(Unit); release.await() }
        }
        val placement = ProviderPlacement(
            providerId = "moving",
            groupId = "g1",
            rootOrder = listOf(ProviderRootOrderItem("g2", true),
                ProviderRootOrderItem("g1", true), ProviderRootOrderItem("remaining", false)),
            groupOrders = mapOf("g1" to listOf("moving", "inside"))
        )
        val write = try {
            withTimeout(5_000) { entered.await() }
            val pending = repository.queueProviderPlacement(placement)
            assertFalse("Room is blocked, but the drop must not wait for it", pending.isCompleted)
            val providersInRam = withTimeout(2_000) { repository.allProviders.first() }
            assertEquals("g1", providersInRam.first { it.id == "moving" }.groupId)
            assertEquals(listOf("moving", "inside"),
                providersInRam.filter { it.groupId == "g1" }.map { it.id })
            assertEquals(placement.rootOrder, observedRoot(repository))
            pending
        } finally {
            release.complete(Unit)
            withTimeout(15_000) { holdingRoom.await() }
        }
        withTimeout(15_000) { write.await() }
        reopened { fresh ->
            val persisted = ModelRepository(fresh, AiService(context))
            assertEquals(placement.rootOrder, observedRoot(persisted))
            assertEquals(listOf("moving", "inside"),
                persisted.allProviders.first().filter { it.groupId == "g1" }.map { it.id })
        }
    }

    @Test fun rapidRootReordersPublishLatestCoordinatesBeforeDatabaseCatchesUp() = runBlocking {
        repository.saveGroup(ProviderGroup("g", "Folder", 0))
        val ids = (0 until 32).map { "p$it" }
        ids.forEachIndexed { index, id -> repository.saveProvider(provider(id, index + 1)) }
        val original = listOf(ProviderRootOrderItem("g", true)) +
            ids.map { ProviderRootOrderItem(it, false) }
        assertEquals(original, observedRoot(repository))

        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val holdingRoom = async(Dispatchers.IO) {
            db.withTransaction { entered.complete(Unit); release.await() }
        }
        val firstOrder = original.drop(1) + original.first()
        val finalOrder = original.reversed()
        val writes = try {
            withTimeout(5_000) { entered.await() }
            val first = repository.queueReorderRootItems(firstOrder)
            assertEquals(firstOrder, observedRoot(repository))
            val second = repository.queueReorderRootItems(finalOrder)
            assertEquals(finalOrder, observedRoot(repository))
            assertFalse(first.isCompleted)
            assertFalse(second.isCompleted)
            first to second
        } finally {
            release.complete(Unit)
            withTimeout(15_000) { holdingRoom.await() }
        }
        withTimeout(20_000) { writes.first.await(); writes.second.await() }
        assertEquals(finalOrder, observedRoot(repository))
        reopened { fresh ->
            assertEquals(finalOrder, observedRoot(ModelRepository(fresh, AiService(context))))
        }
    }

    @Test fun modelsTabReorderKeepsConfigurationsAndPublishesWhileRoomIsLocked() = runBlocking {
        val ids = (0 until 24).map { "model-$it" }
        val configurations = ids.withIndex().associate { (index, id) ->
            id to ModelConfiguration(displayName = "Model $index",
                contextWindowTokens = 8_192 + index)
        }
        repository.saveProvider(provider("models", 0).copy(config = ProviderConfiguration(
            modelIds = ids, modelConfigs = configurations
        )))
        repository.allProviders.first()
        val nextOrder = ids.reversed()

        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val holdingRoom = async(Dispatchers.IO) {
            db.withTransaction { entered.complete(Unit); release.await() }
        }
        val write = try {
            withTimeout(5_000) { entered.await() }
            val pending = repository.queueReorderProviderModels("models", nextOrder)
            val displayed = withTimeout(2_000) {
                repository.allProviders.first().single().config
            }
            assertEquals(nextOrder, displayed.modelIds)
            assertEquals(nextOrder, displayed.modelConfigs.keys.toList())
            assertEquals(configurations, displayed.modelConfigs)
            assertFalse(pending.isCompleted)
            pending
        } finally {
            release.complete(Unit)
            withTimeout(15_000) { holdingRoom.await() }
        }
        withTimeout(15_000) { write.await() }
        reopened { fresh ->
            val saved = ModelRepository(fresh, AiService(context)).getProviderById("models")!!.config
            assertEquals(nextOrder, saved.modelIds)
            assertEquals(nextOrder, saved.modelConfigs.keys.toList())
            assertEquals(configurations, saved.modelConfigs)
        }
    }

    @Test fun addingModelsThenImmediatelyReorderingCannotOvertakePendingSave() = runBlocking {
        val original = listOf("one", "two")
        repository.saveProvider(provider("editor", 0).copy(config = ProviderConfiguration(
            modelIds = original,
            modelConfigs = original.associateWith { ModelConfiguration(displayName = it) }
        )))
        repository.allProviders.first()
        val expanded = linkedMapOf(
            "one" to ModelConfiguration(displayName = "One"),
            "two" to ModelConfiguration(displayName = "Two"),
            "new" to ModelConfiguration(displayName = "New", contextWindowTokens = 64_000)
        )
        val finalOrder = listOf("new", "one", "two")
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val holdingRoom = async(Dispatchers.IO) {
            db.withTransaction { entered.complete(Unit); release.await() }
        }
        try {
            withTimeout(5_000) { entered.await() }
            // UNDISPATCHED models the ViewModel's immediate dispatch of the model-save callback.
            val selectionSave = async(start = CoroutineStart.UNDISPATCHED) {
                repository.saveProviderModels("editor", expanded)
            }
            val reorder = repository.queueReorderProviderModels("editor", finalOrder)
            val inRam = repository.allProviders.first().single().config
            assertEquals(finalOrder, inRam.modelIds)
            assertEquals(finalOrder, inRam.modelConfigs.keys.toList())
            assertEquals(expanded, inRam.modelConfigs)
            assertFalse(selectionSave.isCompleted)
            assertFalse(reorder.isCompleted)
            release.complete(Unit)
            withTimeout(15_000) { selectionSave.await(); reorder.await(); holdingRoom.await() }
            reopened { fresh ->
                val saved = ModelRepository(fresh, AiService(context)).getProviderById("editor")!!.config
                assertEquals(finalOrder, saved.modelIds)
                assertEquals(finalOrder, saved.modelConfigs.keys.toList())
                assertEquals(expanded, saved.modelConfigs)
            }
        } finally {
            release.complete(Unit)
            withTimeout(15_000) { holdingRoom.await() }
        }
    }

    @Test fun mcpSpeechAndProfileOrdersPublishBeforeTheirWrites() = runBlocking {
        val mcp = McpRepository(db, McpService())
        val speech = SpeechRepository(db)
        val profiles = ProfileRepository(db)
        for (index in 0 until 3) {
            mcp.saveServer(McpInfo("m$index", "MCP $index", sortOrder = index))
            speech.saveService(SpeechService("s$index", "Speech $index", sortOrder = index))
            profiles.saveProfile(ChatProfile("c$index", "Profile $index", sortOrder = index))
        }
        withTimeout(8_000) {
            mcp.allServers.first { it.size == 3 }
            speech.allServices.first { it.size == 3 }
            profiles.allProfiles.first { it.size == 3 }
        }
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val holdingRoom = async(Dispatchers.IO) {
            db.withTransaction { entered.complete(Unit); release.await() }
        }
        val reversed = (2 downTo 0).toList()
        try {
            withTimeout(5_000) { entered.await() }
            mcp.queueReorderServers(reversed.map { "m$it" })
            speech.queueReorderServices(reversed.map { "s$it" })
            profiles.queueReorderProfiles(reversed.map { "c$it" })
            assertEquals(reversed.map { "m$it" }, mcp.allServers.first().map { it.id })
            assertEquals(reversed.map { "s$it" }, speech.allServices.first().map { it.id })
            assertEquals(reversed.map { "c$it" }, profiles.allProfiles.first().map { it.id })
        } finally {
            release.complete(Unit)
            withTimeout(15_000) { holdingRoom.await() }
        }
        withTimeout(15_000) {
            while (mcp.getById("m2")?.sortOrder != 0 ||
                speech.getById("s2")?.sortOrder != 0 ||
                profiles.getById("c2")?.sortOrder != 0) delay(25)
        }
        reopened { fresh ->
            assertEquals(listOf("m2", "m1", "m0"), McpRepository(fresh, McpService()).allServers.first().map { it.id })
            assertEquals(listOf("s2", "s1", "s0"), SpeechRepository(fresh).allServices.first().map { it.id })
            assertEquals(listOf("c2", "c1", "c0"), ProfileRepository(fresh).allProfiles.first().map { it.id })
        }
    }
}
