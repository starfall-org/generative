package org.starfall.generative

import android.content.Context
import kotlinx.serialization.json.*
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.starfall.generative.database.room.AppDatabase
import org.starfall.generative.shared.model.*
import org.starfall.generative.database.repositories.ModelRepository
import org.starfall.generative.integrations.modelapis.AiService
import org.starfall.generative.features.providers.list.ui.newProviderId
import org.starfall.generative.shared.model.api.ModelApiType
import org.starfall.generative.shared.model.language.ModelConfiguration
import org.starfall.generative.shared.model.provider.ModelProvider
import org.starfall.generative.shared.model.provider.ProviderConfiguration
import org.starfall.generative.shared.model.provider.ProviderGroup
import org.starfall.generative.shared.model.provider.ProviderPlacement
import org.starfall.generative.shared.model.provider.ProviderRootOrderItem
import org.starfall.generative.shared.subfeatures.modelpicker.logic.ModelPickerItem
import org.starfall.generative.shared.subfeatures.modelpicker.logic.computeModelPickerItems

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ProviderGroupTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test fun repositoryPersistsSmallContextOverridesAndRawModelJson() = runBlocking {
        installTestAndroidKeyStore()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val repo = ModelRepository(db, AiService(context))
            val raw = Json.parseToJsonElement("""{"id":"small","context_window":32000,"vendor":{"extra":true}}""").jsonObject
            repo.saveProvider(ModelProvider("json-test", "Provider", ModelApiType.OPENAI, baseUrl = "",
                config = ProviderConfiguration(modelConfigs = mapOf(
                    "small" to ModelConfiguration(contextWindowTokens = 32000, modelJson = raw),
                    "default" to ModelConfiguration(contextWindowTokens = 128000),
                    "large" to ModelConfiguration(contextWindowTokens = 128001)
                ))))
            val reopened = ModelRepository(db, AiService(context)).getProviderById("json-test")!!
            assertEquals(raw, reopened.config.modelConfigs.getValue("small").modelJson)
            assertEquals(32000, reopened.config.modelConfigs.getValue("small").contextWindowTokens)
        } finally { db.close() }
    }

    @Test fun dragPlacementPersistsMembershipAndExactOrdersTogether() = runBlocking {
        installTestAndroidKeyStore()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val repo = ModelRepository(db, AiService(context))
            repo.saveGroup(ProviderGroup("g", "Folder", 1))
            for ((id, group) in listOf("outside" to null, "a" to "g", "b" to "g", "tail" to null))
                repo.saveProvider(ModelProvider(id, id, ModelApiType.OPENAI, baseUrl = "", groupId = group))
            val root = listOf(ProviderRootOrderItem("g", true), ProviderRootOrderItem("tail", false))
            repo.placeProvider(ProviderPlacement("outside", "g", root, mapOf("g" to listOf("a", "outside", "b"))))
            assertEquals("g", repo.getProviderById("outside")!!.groupId)
            val visible = repo.allProviders.first()
            assertEquals(listOf("a", "outside", "b"), visible.filter { it.groupId == "g" }.map { it.id })
            val persisted = ModelRepository(db, AiService(context))
            assertEquals(listOf("a", "outside", "b"), persisted.allProviders.first().filter { it.groupId == "g" }.map { it.id })
            repo.placeProvider(ProviderPlacement("a", null,
                listOf(ProviderRootOrderItem("a", false)) + root, mapOf("g" to listOf("outside", "b"))))
            assertNull(repo.getProviderById("a")!!.groupId)
            assertEquals(0, repo.getProviderById("a")!!.sortOrder)
            assertEquals(1, repo.allGroups.first().first().sortOrder)
            assertEquals(2, repo.getProviderById("tail")!!.sortOrder)
            assertEquals(listOf("outside", "b"), repo.allProviders.first().filter { it.groupId == "g" }.map { it.id })
            val before = repo.allProviders.first()
            repo.placeProvider(ProviderPlacement("outside", "missing", emptyList(), emptyMap()))
            assertEquals(before, repo.allProviders.first())
        } finally { db.close() }
    }

    @Test
    fun repositoryPersistsMembershipAndDeletingGroupUngroupsProviders() = runBlocking {
        installTestAndroidKeyStore()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val repo = ModelRepository(db, AiService(context))
            val group = ProviderGroup("g1", "Work", 0, "icon-11111111-1111-1111-1111-111111111111.png")
            repo.saveGroup(group)
            repo.saveProvider(
                ModelProvider(
                    id = "p1",
                    name = "Provider",
                    type = ModelApiType.OPENAI,
                    baseUrl = "https://example.test/v1",
                    groupId = group.id
                )
            )

            assertEquals(listOf(group), repo.allGroups.first())
            assertEquals(group.id, repo.getProviderById("p1")?.groupId)

            repo.deleteGroup(group.id)

            assertTrue(repo.allGroups.first().isEmpty())
            assertNull(repo.getProviderById("p1")?.groupId)
        } finally {
            db.close()
        }
    }

    @Test
    fun newAndMovedProvidersAppendInCreationOrderAndStayThereAfterReload() = runBlocking {
        installTestAndroidKeyStore()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val repo = ModelRepository(db, AiService(context))
            repo.saveGroup(ProviderGroup("g", "Work", 0))
            fun provider(id: String, group: String? = "g") = ModelProvider(id, id, ModelApiType.OPENAI,
                baseUrl = "https://example.test/v1", groupId = group)
            repo.saveProvider(provider("z-first"))
            repo.saveProvider(provider("a-second"))
            repo.saveProvider(provider("0-moved", null))
            val expected = listOf("z-first", "a-second", "0-moved")
            val emissions = java.util.Collections.synchronizedList(mutableListOf<List<String>>())
            val collector = launch(Dispatchers.Unconfined) {
                repo.allProviders.collect { providers ->
                    val members = providers.filter { it.groupId == "g" }.map { it.id }
                    if ("0-moved" in members) emissions.add(members)
                }
            }
            repo.moveProviderToGroup("0-moved", "g")
            collector.cancelAndJoin()
            assertTrue(emissions.isNotEmpty())
            emissions.forEach { assertEquals(expected, it) }
            repo.saveProvider(provider("0-new"))
            val finalOrder = expected + "0-new"
            assertEquals(finalOrder, repo.allProviders.first().filter { it.groupId == "g" }.map { it.id })
            repo.moveProviderToGroup("a-second", "g")
            assertEquals(finalOrder, repo.allProviders.first().filter { it.groupId == "g" }.map { it.id })
            val reloaded = ModelRepository(db, AiService(context)).allProviders.first()
            assertEquals(finalOrder, reloaded.filter { it.groupId == "g" }.map { it.id })
            assertEquals(listOf(0, 1, 2, 3), reloaded.filter { it.groupId == "g" }.map { it.sortOrder })
        } finally { db.close() }
    }

    @Test
    fun legacyMaximumRanksAreCompactedWithoutOverflowWhenMovingIntoFolder() = runBlocking {
        installTestAndroidKeyStore()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val repo = ModelRepository(db, AiService(context))
            repo.saveGroup(ProviderGroup("g", "Work", 0))
            for (id in listOf("z-legacy", "a-legacy")) {
                db.modelProviderDao().insertOrUpdate(org.starfall.generative.database.room.entities.ModelProviderEntity(
                    id = id,
                    name = id,
                    type = ModelApiType.OPENAI.name,
                    baseUrl = "https://example.test/v1",
                    icon = null,
                    sortOrder = Int.MAX_VALUE,
                    groupId = "g",
                    authId = null,
                    supportStream = true,
                    responsesApi = false,
                    customListModelsUrl = null,
                    maxTokens = 4000,
                    multipleApiKeys = false,
                    modelSelectionMode = "DISCOVERY"
                ))
            }
            repo.saveProvider(ModelProvider("moving", "Moving", ModelApiType.OPENAI, baseUrl = "https://example.test/v1"))
            repo.moveProviderToGroup("moving", "g")
            val members = ModelRepository(db, AiService(context)).allProviders.first().filter { it.groupId == "g" }
            assertEquals(listOf("a-legacy", "z-legacy", "moving"), members.map { it.id })
            assertEquals(listOf(0, 1, 2), members.map { it.sortOrder })
            repo.moveProviderToGroup("moving", null)
            assertTrue(repo.getProviderById("moving")!!.sortOrder > repo.allGroups.first().single().sortOrder)
        } finally { db.close() }
    }

    @Test
    fun defaultPickerOrdersProvidersByFolderThenProviderSortOrder() {
        val first = ProviderGroup("first", "First", sortOrder = 0)
        val second = ProviderGroup("second", "Second", sortOrder = 1)
        fun provider(id: String, groupId: String?, sortOrder: Int) = ModelProvider(
            id = id,
            name = id,
            type = ModelApiType.OPENAI,
            baseUrl = "",
            groupId = groupId,
            sortOrder = sortOrder,
            config = ProviderConfiguration(modelIds = listOf("model-$id"))
        )
        val rows = computeModelPickerItems(
            providers = listOf(
                provider("second-0", second.id, 0),
                provider("first-1", first.id, 1),
                provider("root-0", null, 0),
                provider("first-0", first.id, 0),
                provider("second-1", second.id, 1)
            ),
            providerGroups = listOf(second, first),
            dynamicModelsMap = emptyMap(),
            selectedProviderId = "",
            selectedModelId = ""
        )

        assertEquals(
            listOf("first-0", "first-1", "second-0", "second-1", "root-0"),
            rows.filterIsInstance<ModelPickerItem.Provider>().map { it.provider.id }
        )
    }

    @Test
    fun pickerTabsFilterFoldersAndProviderCollapseButSearchExpandsMatches() {
        val group = ProviderGroup("g1", "Work", 0)
        val grouped = ModelProvider(
            id = "p1",
            name = "Primary",
            type = ModelApiType.OPENAI,
            baseUrl = "https://example.test/v1",
            groupId = group.id,
            config = ProviderConfiguration(
                modelIds = listOf("alpha", "beta"),
                modelConfigs = mapOf("alpha" to ModelConfiguration(), "beta" to ModelConfiguration())
            )
        )
        val root = ModelProvider(
            id = "p2",
            name = "Root",
            type = ModelApiType.ANTHROPIC,
            baseUrl = "https://example.test/v1",
            config = ProviderConfiguration(
                modelIds = listOf("gamma"),
                modelConfigs = mapOf("gamma" to ModelConfiguration())
            )
        )

        val groupCollapsed = computeModelPickerItems(
            providers = listOf(grouped, root),
            providerGroups = listOf(group),
            dynamicModelsMap = emptyMap(),
            selectedProviderId = "",
            selectedModelId = "",
            collapsedGroupIds = setOf(group.id),
            selectedFolderId = group.id
        )
        assertTrue(groupCollapsed.any { it is ModelPickerItem.Group && it.group.id == group.id })
        assertTrue(groupCollapsed.any { it is ModelPickerItem.Provider && it.provider.id == grouped.id })
        assertEquals(listOf("alpha", "beta"), groupCollapsed.filterIsInstance<ModelPickerItem.Model>().map { it.modelId })
        assertFalse(groupCollapsed.any { it is ModelPickerItem.Provider && it.provider.id == root.id })

        val providerCollapsed = computeModelPickerItems(
            providers = listOf(grouped),
            providerGroups = listOf(group),
            dynamicModelsMap = emptyMap(),
            selectedProviderId = "",
            selectedModelId = "",
            collapsedProviderIds = setOf(grouped.id)
        )
        assertTrue(providerCollapsed.any { it is ModelPickerItem.Provider && it.provider.id == grouped.id })
        assertFalse(providerCollapsed.any { it is ModelPickerItem.Model })

        val searched = computeModelPickerItems(
            providers = listOf(grouped),
            providerGroups = listOf(group),
            dynamicModelsMap = emptyMap(),
            selectedProviderId = "",
            selectedModelId = "",
            query = "beta",
            collapsedGroupIds = setOf(group.id),
            collapsedProviderIds = setOf(grouped.id)
        )
        assertTrue(searched.any { it is ModelPickerItem.Group && it.group.id == group.id })
        assertTrue(searched.any { it is ModelPickerItem.Provider && it.provider.id == grouped.id })
        assertEquals(listOf("beta"), searched.filterIsInstance<ModelPickerItem.Model>().map { it.modelId })
    }

    @Test
    fun rootOrderInterleavesGroupsAndUngroupedProvidersAndMovePersistsMembership() = runBlocking {
        installTestAndroidKeyStore()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val repo = ModelRepository(db, AiService(context))
            val g1 = ProviderGroup("g1", "One", 0)
            val g2 = ProviderGroup("g2", "Two", 1)
            repo.saveGroup(g1)
            repo.saveGroup(g2)
            repo.saveProvider(
                ModelProvider(
                    id = "root",
                    name = "Root",
                    type = ModelApiType.OPENAI,
                    baseUrl = "https://example.test/v1",
                    sortOrder = 2
                )
            )
            repo.saveProvider(
                ModelProvider(
                    id = "child",
                    name = "Child",
                    type = ModelApiType.OPENAI,
                    baseUrl = "https://example.test/v1",
                    sortOrder = 0,
                    groupId = g1.id
                )
            )

            repo.reorderRootItems(
                listOf(
                    ProviderRootOrderItem("root", isGroup = false),
                    ProviderRootOrderItem("g2", isGroup = true),
                    ProviderRootOrderItem("g1", isGroup = true)
                )
            )

            assertEquals(0, repo.getProviderById("root")?.sortOrder)
            assertEquals(
                mapOf("g1" to 2, "g2" to 1),
                repo.allGroups.first().associate { it.id to it.sortOrder }
            )

            repo.moveProviderToGroup("root", "g1")
            val moved = repo.getProviderById("root")
            assertEquals("g1", moved?.groupId)
            assertTrue((moved?.sortOrder ?: -1) > (repo.getProviderById("child")?.sortOrder ?: Int.MAX_VALUE))

            repo.moveProviderToGroup("root", null)
            assertNull(repo.getProviderById("root")?.groupId)
            assertTrue((repo.getProviderById("root")?.sortOrder ?: -1) > repo.allGroups.first().maxOf { it.sortOrder })
        } finally {
            db.close()
        }
    }


    @Test
    fun creatingMultipleProvidersUsesDistinctPersistentIds() = runBlocking {
        installTestAndroidKeyStore()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val repo = ModelRepository(db, AiService(context))
            val firstId = newProviderId()
            val secondId = newProviderId()
            assertNotEquals(firstId, secondId)
            assertTrue(firstId.startsWith("custom_"))
            assertTrue(secondId.startsWith("custom_"))

            repo.saveProvider(
                ModelProvider(
                    id = firstId,
                    name = "First",
                    type = ModelApiType.OPENAI,
                    baseUrl = "https://first.example/v1"
                )
            )
            repo.saveProvider(
                ModelProvider(
                    id = secondId,
                    name = "Second",
                    type = ModelApiType.OPENAI,
                    baseUrl = "https://second.example/v1"
                )
            )

            assertEquals(
                setOf(firstId, secondId),
                repo.allProviders.first().map { it.id }.toSet()
            )
        } finally {
            db.close()
        }
    }

}
