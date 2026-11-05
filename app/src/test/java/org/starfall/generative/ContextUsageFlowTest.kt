package org.starfall.generative

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.starfall.generative.features.home.chat.logic.ChatGeneration
import org.starfall.generative.features.home.chat.logic.contextUsageKey
import org.starfall.generative.features.home.chat.logic.contextWindowStatus
import org.starfall.generative.integrations.modelapis.AiService
import org.starfall.generative.shared.model.*
import org.starfall.generative.shared.model.api.ModelApiType
import org.starfall.generative.shared.model.generation.TokenUsage
import org.starfall.generative.shared.model.language.ModelConfiguration
import org.starfall.generative.shared.model.provider.ModelProvider
import org.starfall.generative.shared.model.provider.ProviderConfiguration
import org.starfall.generative.database.repositories.ConversationRepository
import org.starfall.generative.database.room.AppDatabase

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = LibraryTestApplication::class)
class ContextUsageFlowTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test fun realResponseUsageReachesContextIndicatorAndSurvivesDatabaseReload() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            MockWebServer().use { server ->
                val model = ModelConfiguration(contextWindowTokens = 1000)
                val provider = ModelProvider("p", "Fixture", ModelApiType.OPENAI, baseUrl = server.url("/v1").toString(),
                    config = ProviderConfiguration(supportStream = false, modelConfigs = mapOf("m" to model)))
                val prompt = "System"
                val user = StoredMessage("u", ChatRole.USER, listOf(MessageVersion(content = "Question")))
                val response = StoredMessage("a", ChatRole.MODEL, listOf(MessageVersion(providerId = "p", modelId = "m")))
                val initial = Conversation("c", "Fixture", 1, 1, listOf(user, response), providerId = "p", modelId = "m")
                val repository = ConversationRepository(db)
                val generation = ChatGeneration(CoroutineScope(coroutineContext), repository::saveConversation, {})
                for (hasUsage in listOf(true, false)) {
                    val usage = if (hasUsage) ",\"usage\":{\"prompt_tokens\":700,\"completion_tokens\":200,\"total_tokens\":900}" else ""
                    server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(
                        """{"id":"reply","object":"chat.completion","created":1,"model":"m","choices":[{"index":0,"message":{"role":"assistant","content":"Answer"},"finish_reason":"stop"}]$usage}"""))
                    generation.startEvents(initial, "a", AiService(context).streamEvents(provider, "m", listOf(user), prompt),
                        usageContextKey = contextUsageKey(initial, prompt, model))
                    withTimeout(15_000) { generation.busy.first { !it } }
                    assertNull(generation.error.value)
                    val restored = repository.loadById("c")!!
                    assertEquals("Answer", restored.messages.last().content)
                    val status = contextWindowStatus(restored, prompt, model)
                    if (hasUsage) {
                        assertEquals(TokenUsage(700, 200), status.apiUsage)
                        assertEquals(900L, status.usedTokens)
                        assertEquals(90, status.percentage)
                        assertFalse(status.isEstimated)
                    } else {
                        assertNull(status.apiUsage)
                        assertTrue(status.isEstimated)
                        assertTrue(status.usedTokens < 900)
                    }
                }
            }
        } finally { db.close() }
    }
}
