package org.starfall.generative

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.starfall.generative.database.repositories.CustomHttpToolRepository
import org.starfall.generative.database.room.AppDatabase
import org.starfall.generative.shared.model.CustomHttpTool

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = LibraryTestApplication::class)
class CustomHttpToolRepositoryTest {
    @Before fun prepareKeyStore() = installTestAndroidKeyStore()

    @Test fun encryptsConfigurationAndPublishesExplicitEditsAndRestores() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val repository = CustomHttpToolRepository(db)
            val tool = CustomHttpTool(name = "fetch", url = "https://example.test/private?key=secret",
                headers = buildJsonObject { put("Authorization", "Bearer secret") })
            repository.save(tool)
            assertEquals(listOf(tool), repository.snapshot())
            val row = db.customHttpToolDao().all().single()
            assertFalse(row.encryptedPayload.contains("secret"))
            assertFalse(row.encryptedPayload.contains("example.test"))
            assertEquals(listOf(tool), CustomHttpToolRepository(db).snapshot())
            repository.setEnabled(tool.id, true)
            assertTrue(repository.snapshot().single().enabled)
            // A replacement is serialized after the toggle and must publish the restored snapshot.
            repository.replaceAll(listOf(tool.copy(name = "restored")))
            assertEquals("restored", repository.snapshot().single().name)
            assertEquals("restored", CustomHttpToolRepository(db).snapshot().single().name)
            repository.delete(tool.id)
            withTimeout(5000) { repository.tools.first { it.isEmpty() } }
            repository.replaceAll(emptyList()) // await the serial writer before closing Room
            assertTrue(db.customHttpToolDao().all().isEmpty())
        } finally { db.close() }
    }
}
