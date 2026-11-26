package org.starfall.generative

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.starfall.generative.integrations.modelapis.account.AccountTokenState
import org.starfall.generative.integrations.modelapis.account.AccountTokenStore
import org.starfall.generative.integrations.modelapis.devin.DevinAdapter
import org.starfall.generative.integrations.modelapis.devin.DevinWire
import org.starfall.generative.shared.model.ChatRole
import org.starfall.generative.shared.model.MessageVersion
import org.starfall.generative.shared.model.StoredMessage
import org.starfall.generative.shared.model.api.ModelApiType
import org.starfall.generative.shared.model.auth.AuthMethod
import org.starfall.generative.shared.model.auth.Authorization
import org.starfall.generative.shared.model.generation.GenerationEvent
import org.starfall.generative.shared.model.provider.ModelProvider
import org.starfall.generative.shared.subfeatures.media.logic.AttachmentResolver

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = LibraryTestApplication::class)
class DevinAdapterTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val token = "devin-session-token\$test"
    private val provider = ModelProvider("devin-test", "Devin", ModelApiType.DEVIN,
        baseUrl = ModelApiType.DEVIN.defaultBaseUrl,
        auth = Authorization(AuthMethod.OAUTH, value = "account:devin:v1"))

    @Before fun saveCredentials() {
        installTestAndroidKeyStore()
        AccountTokenStore(context, "devin").save(provider.id, AccountTokenState(token, ""))
    }

    @Test fun discoversEnabledModelsAndSendsTheExactSelectedUid() = runBlocking {
        MockWebServer().use { server ->
            val modelUid = "swe-2-flash-medium"
            val models = DevinWire.ProtoWriter().apply {
                nested(1) { string(1, "SWE-2 Flash (Medium)"); number(5, 0); string(22, modelUid) }
                nested(1) { string(1, "SWE-2 Flash (High)"); number(4, 1); string(22, "swe-2-flash-high") }
            }.build()
            server.enqueue(MockResponse().setHeader("Content-Type", "application/proto").setBody(Buffer().write(models)))
            val reply = DevinWire.ProtoWriter().apply { string(3, "Hello") }.build()
            server.enqueue(MockResponse().setHeader("Content-Type", "application/connect+proto").setBody(
                Buffer().write(DevinWire.envelope(reply)).writeByte(2).writeInt(2).writeUtf8("{}")
            ))
            val adapter = adapter(server)
            val model = adapter.fetchModelCatalog(provider).single()
            assertEquals(modelUid, model.id)
            assertFalse(model.configuration().supportsVision)
            val discovery = server.takeRequest()
            assertEquals(DevinWire.MODELS_PATH, discovery.path)
            assertEquals("Basic $token-$token", discovery.getHeader("Authorization"))
            val metadata = DevinWire.fields(DevinWire.fields(discovery.body.readByteArray()).single().data)
            assertEquals(token, metadata.first { it.number == 3 }.text())

            val configured = provider.copy(config = provider.config.copy(modelConfigs = mapOf(
                model.id to model.configuration().copy(temperature = 0.0, reasoningEffort = "high")
            )))
            val message = StoredMessage("user-turn", ChatRole.USER, listOf(MessageVersion(content = "Hi")))
            val events = adapter.streamEvents(configured, model.id, listOf(message), "", 4000).toList()
            assertEquals(listOf(GenerationEvent.Text("Hello")), events)

            val chat = server.takeRequest()
            assertEquals(DevinWire.CHAT_PATH, chat.path)
            val frame = requireNotNull(DevinWire.nextFrame(chat.body.inputStream()))
            val fields = DevinWire.fields(frame.payload)
            assertEquals(modelUid, fields.first { it.number == 21 }.text())
            val sampling = DevinWire.fields(fields.first { it.number == 8 }.data)
            val temperature = ByteBuffer.wrap(sampling.first { it.number == 5 }.data).order(ByteOrder.LITTLE_ENDIAN).double
            assertEquals(0.0001, temperature, 0.0)
        }
    }

    @Test fun reportsUnavailabilityInsteadOfReturningStaleModels() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(503))
            val error = runCatching { adapter(server).fetchModels(provider) }.exceptionOrNull()
            assertEquals("Devin model catalog HTTP 503", error?.message)
        }
    }

    private fun adapter(server: MockWebServer) = DevinAdapter(context, AttachmentResolver(context),
        serverBaseUrl = server.url("/").toString())
}
