package org.starfall.generative

import android.content.Context
import android.graphics.Bitmap
import android.os.Looper
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.starfall.generative.database.repositories.*
import org.starfall.generative.database.room.AppDatabase
import org.starfall.generative.features.providers.shared.logic.ConfigurationViewModel
import org.starfall.generative.integrations.mcp.McpService
import org.starfall.generative.integrations.modelapis.AiService
import org.starfall.generative.shared.subfeatures.icons.logic.FaviconResult
import org.starfall.generative.shared.subfeatures.icons.logic.IconStore
import org.starfall.generative.shared.model.*
import org.starfall.generative.shared.model.api.ModelApiType
import org.starfall.generative.shared.model.auth.Authorization
import org.starfall.generative.shared.model.language.ModelConfiguration
import org.starfall.generative.shared.model.provider.*
import org.starfall.generative.database.preferences.AppPreferencesRepository
import org.starfall.generative.shared.subfeatures.icons.ui.FaviconFetchState

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = LibraryTestApplication::class)
@LooperMode(LooperMode.Mode.PAUSED)
class LogoPersistenceTest {
    @Before fun prepareSecrets() = installTestAndroidKeyStore()

    private val provider = ModelProvider("provider", "Original provider", ModelApiType.OPENAI,
        baseUrl = "https://example.test/v1", icon = "old.png", auth = Authorization(value = "saved-provider-key"),
        config = ProviderConfiguration(modelConfigs = mapOf("model" to ModelConfiguration(icon = "old.png", temperature = 0.4))))
    private val server = McpInfo("server", "Original MCP", url = "https://example.test/mcp", icon = "old.png",
        auth = McpAuthorization(McpAuthMethod.BEARER_TOKEN, value = "saved-token"))
    private val speech = SpeechService("speech", "Original speech", icon = "old.png", apiKey = "saved-key")

    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Test fun choosingWebLogoPersistsItBeforeClosingTheDialog() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val state = FaviconFetchState(context, scope)
        val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        val choice = FaviconResult(bitmap, "https://example.test/logo.png".toHttpUrl())
        var image: String? = null
        try {
            run {
                state.dialogOpen = true
                state.results = listOf(choice)
                state.select(choice) { image = it }
            }
            awaitCondition { image != null && state.selected == choice && !state.saving }
            run {
                assertTrue(state.dialogOpen)
                assertEquals(choice, state.selected)
                assertNotNull(IconStore(context).fileName(image))
            }
        } finally {
            scope.cancel()
            bitmap.recycle()
        }
    }

    @Test fun logoWritesPersistWithoutSavingOtherDraftSettings() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val store = ViewModelStore()
        try {
            val models = ModelRepository(db, AiService(context))
            val mcp = McpRepository(db, McpService())
            val services = SpeechRepository(db)
            val group = ProviderGroup("group", "Original group", icon = "old.png")
            models.saveProvider(provider)
            models.saveGroup(group)
            mcp.saveServer(server)
            services.saveService(speech)
            val viewModel = ConfigurationViewModel(ProfileRepository(db), models, mcp, services, AppPreferencesRepository(context))
            store.put("configuration", viewModel)
            run {
                viewModel.saveProviderIcon(provider.copy(name = "Unsaved name", baseUrl = "https://draft.test", icon = "new.png",
                    auth = Authorization(value = "unsaved-key")))
                viewModel.saveModelIcon(provider.id, "model", "new.png")
                viewModel.saveProviderGroupIcon(group.copy(name = "Unsaved group", icon = "new.png"))
                viewModel.saveMcpServerIcon(server.copy(name = "Unsaved MCP", url = "https://draft.test", icon = "new.png",
                    auth = McpAuthorization(McpAuthMethod.BEARER_TOKEN, value = "unsaved-token")))
                viewModel.saveSpeechServiceIcon(speech.copy(name = "Unsaved speech", speed = 2f, apiKey = "unsaved-key", icon = "new.png"))
            }
            awaitCondition {
                runBlocking {
                    models.getProviderById(provider.id)?.let { it.icon == "new.png" && it.config.modelConfigs["model"]?.icon == "new.png" } == true &&
                        models.allGroups.first().single().icon == "new.png" &&
                        mcp.getById(server.id)?.icon == "new.png" && services.getById(speech.id)?.icon == "new.png"
                }
            }
            val reloaded = ModelRepository(db, AiService(context)).getProviderById(provider.id)!!
            assertEquals(provider.name, reloaded.name)
            assertEquals(provider.baseUrl, reloaded.baseUrl)
            assertEquals(provider.auth, reloaded.auth)
            assertEquals(0.4, reloaded.config.modelConfigs.getValue("model").temperature)
            assertEquals(group.copy(icon = "new.png"), models.allGroups.first().single())
            assertEquals(server.copy(icon = "new.png"), McpRepository(db, McpService()).getById(server.id))
            assertEquals(speech.copy(icon = "new.png"), SpeechRepository(db).getById(speech.id))
        } finally {
            run { store.clear() }
            db.close()
        }
    }
    private fun awaitCondition(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(10)
        }
        assertTrue(condition())
    }
}
