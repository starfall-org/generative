package org.starfall.generative

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.starfall.generative.database.room.AppDatabase
import org.starfall.generative.shared.model.*
import org.starfall.generative.database.repositories.ModelRepository
import org.starfall.generative.integrations.modelapis.AiService
import org.starfall.generative.features.appdata.logic.buildJsonExport
import org.starfall.generative.features.appdata.logic.parseFullAppDataExport
import org.starfall.generative.shared.model.api.ModelApiType
import org.starfall.generative.shared.model.auth.CredentialLoadState
import org.starfall.generative.shared.model.provider.ModelProvider
import org.starfall.generative.shared.model.provider.ProviderGroup

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = LibraryTestApplication::class)
class ProviderImportTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var db: AppDatabase
    private lateinit var repo: ModelRepository

    private val groups = listOf(
        ProviderGroup("work", "Work", 1, "folder.png"),
        ProviderGroup("empty", "Empty folder", 3)
    )
    private val providers = listOf(
        provider("root", null, 0),
        provider("z-first", "work", 0),
        provider("a-second", "work", 1),
        provider("tail", null, 2)
    )

    private fun provider(id: String, group: String?, order: Int) = ModelProvider(
        id, id, ModelApiType.OPENAI, baseUrl = "https://example.test/v1", groupId = group, sortOrder = order,
        credentialState = CredentialLoadState.NOT_CONFIGURED
    )

    @Before fun setUp() {
        installTestAndroidKeyStore()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        repo = ModelRepository(db, AiService(context))
    }

    @After fun tearDown() { db.close() }

    private suspend fun importBackup() {
        val backup = parseFullAppDataExport(buildJsonExport(
            providers, emptyList(), emptyList(), emptyList(), groups
        ))
        repo.importProviders(backup.providers.orEmpty(), backup.providerGroups)
    }

    @Test fun roundTripRestoresGroupsMembershipAndExactOrderInAFreshDatabase() = runBlocking {
        importBackup()

        val reopened = ModelRepository(db, AiService(context))
        assertEquals(groups, reopened.allGroups.first())
        assertEquals(providers.associateBy { it.id }, reopened.loadProvidersForExport().associateBy { it.id })
        assertEquals(listOf("z-first", "a-second"), db.modelProviderDao().getProvidersByGroup("work").map { it.id })
        assertTrue(db.modelProviderDao().getProvidersByGroup("empty").isEmpty())
    }

    @Test fun reimportRestoresMovedProvidersWithoutReorderingTheBackupOrDuplicatingGroups() = runBlocking {
        importBackup()
        val localGroup = ProviderGroup("local", "Local only", 10)
        val localProvider = provider("local-provider", "local", 5)
        repo.saveGroup(localGroup)
        repo.saveProvider(localProvider)
        repo.saveGroup(groups.first().copy(name = "Renamed", sortOrder = 8, icon = null))
        repo.moveProviderToGroup("z-first", null)
        repo.moveProviderToGroup("tail", "work")

        repeat(2) { importBackup() }

        val reopened = ModelRepository(db, AiService(context))
        val restoredGroups = reopened.allGroups.first()
        assertEquals(listOf("work", "empty", "local"), restoredGroups.map { it.id })
        assertEquals(groups, restoredGroups.filter { it.id in groups.mapTo(hashSetOf()) { group -> group.id } })
        assertEquals((providers + localProvider).associateBy { it.id }, reopened.loadProvidersForExport().associateBy { it.id })
    }

    @Test fun legacyImportPreservesLocalLayoutAndDoesNotCreateDanglingGroupReferences() = runBlocking {
        val localGroup = ProviderGroup("local", "Local folder", 4)
        val existing = provider("existing", "local", 3)
        repo.saveGroup(localGroup)
        repo.saveProvider(existing)
        val legacy = parseFullAppDataExport("""{
            "formatVersion":1,"type":"providers","items":[
                {"id":"existing","name":"Updated","type":"openai","baseUrl":"https://new.example/v1"},
                {"id":"known-group","name":"Known","type":"openai","baseUrl":"","groupId":"local","sortOrder":5},
                {"id":"missing-group","name":"Missing","type":"openai","baseUrl":"","groupId":"missing"}
            ]
        }""")
        repo.importProviders(legacy.providers.orEmpty(), legacy.providerGroups)
        // A stale group ID in another legacy backup must not replace the local folder either.
        repo.importProviders(legacy.providers.orEmpty().take(1).map { it.copy(groupId = "missing") }, legacy.providerGroups)

        val reopened = ModelRepository(db, AiService(context))
        assertEquals(listOf(localGroup), reopened.allGroups.first())
        assertEquals(existing.copy(name = "Updated", baseUrl = "https://new.example/v1"), reopened.getProviderById("existing"))
        assertEquals("local", reopened.getProviderById("known-group")?.groupId)
        assertNull(reopened.getProviderById("missing-group")?.groupId)
    }

    @Test fun failedImportRollsBackGroupAndProviderChangesTogether() = runBlocking {
        val existingGroup = groups.first().copy(name = "Original", sortOrder = 8)
        val existingProvider = provider("root", null, 7)
        repo.saveGroup(existingGroup)
        repo.saveProvider(existingProvider)
        db.openHelper.writableDatabase.execSQL("""
            CREATE TRIGGER fail_import BEFORE INSERT ON model_providers
            WHEN NEW.id = 'tail' BEGIN SELECT RAISE(ABORT, 'test import failure'); END
        """.trimIndent())

        assertTrue(runCatching { importBackup() }.isFailure)

        val reopened = ModelRepository(db, AiService(context))
        assertEquals(listOf(existingGroup), reopened.allGroups.first())
        assertEquals(listOf(existingProvider), reopened.allProviders.first())
        assertEquals(listOf(existingGroup), repo.allGroups.first())
        assertEquals(listOf(existingProvider), repo.allProviders.first())
    }
}
