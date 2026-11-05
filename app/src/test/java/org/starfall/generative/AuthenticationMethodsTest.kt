package org.starfall.generative

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.starfall.generative.shared.model.*
import org.starfall.generative.shared.subfeatures.media.logic.AttachmentResolver
import org.starfall.generative.shared.helpers.networking.ToolHttp
import java.util.concurrent.TimeUnit
import org.starfall.generative.shared.model.auth.AuthMethod
import org.starfall.generative.shared.model.auth.Authorization
import org.starfall.generative.shared.model.api.ModelApiType
import org.starfall.generative.shared.model.provider.ModelProvider
import org.starfall.generative.shared.model.auth.defaultAuthorization

@Config(sdk = [28])
@RunWith(RobolectricTestRunner::class)
class AuthenticationMethodsTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val types = listOf(ModelApiType.GOOGLE, ModelApiType.ANTHROPIC, ModelApiType.OPENAI_RESPONSES, ModelApiType.OPENAI)
    private val methods = listOf(AuthMethod.PLATFORM_DEFAULT, AuthMethod.BEARER_TOKEN, AuthMethod.QUERY_PARAM, AuthMethod.OAUTH, AuthMethod.NONE)

    private fun provider(server: MockWebServer, type: ModelApiType, auth: Authorization) =
        ModelProvider("test", "Test", type, auth, baseUrl = server.url(if (type == ModelApiType.GOOGLE) "/v1beta" else "/v1").toString())

    private fun modelsResponse(type: ModelApiType) = MockResponse().setHeader("Content-Type", "application/json")
        .setBody(if (type == ModelApiType.GOOGLE) """{"models":[]}""" else """{"object":"list","data":[],"has_more":false}""")

    @Test fun standardProvidersDefaultToNativeSdkAuthentication() {
        types.forEach { assertEquals(AuthMethod.PLATFORM_DEFAULT, it.defaultAuthorization().method) }
    }

    @Test fun emptyCredentialsReachAcceptingServersForEveryMethod() = runBlocking {
        val adapters = ProviderAdaptersTestSupport(context)
        for (type in types) for (method in methods) MockWebServer().use { server ->
            server.enqueue(modelsResponse(type))
            val auth = Authorization(method, if (method == AuthMethod.QUERY_PARAM) "token" else "Authorization", "")
            val result = adapters.testConnection(provider(server, type, auth))
            assertTrue("$type/$method: ${result.exceptionOrNull()}", result.isSuccess)
            val request = server.takeRequest(5, TimeUnit.SECONDS)!!
            assertNull("$type/$method", request.getHeader("Authorization"))
            assertNull(request.getHeader("x-api-key"))
            assertNull(request.getHeader("x-goog-api-key"))
            assertNull(request.requestUrl!!.query)
        }
    }

    @Test fun blankCredentialsReportRealServerRejection() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(401).setHeader("Content-Type", "application/json")
                .setBody("""{"error":{"message":"Server rejected request","type":"authentication_error"}}"""))
            val result = ProviderAdaptersTestSupport(context).testConnection(
                provider(server, ModelApiType.OPENAI, Authorization(AuthMethod.BEARER_TOKEN, "Authorization", "")))
            assertTrue(result.isFailure)
            assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
            assertTrue(result.exceptionOrNull().toString().contains("401"))
        }
    }

    @Test fun platformDefaultPassesNativeApiKeysToSdk() = runBlocking {
        for (type in types) MockWebServer().use { server ->
            server.enqueue(modelsResponse(type))
            val result = ProviderAdaptersTestSupport(context).testConnection(
                provider(server, type, Authorization(AuthMethod.PLATFORM_DEFAULT, value = "secret")))
            assertTrue("$type: ${result.exceptionOrNull()}", result.isSuccess)
            val request = server.takeRequest(5, TimeUnit.SECONDS)!!
            when (type) {
                ModelApiType.GOOGLE -> assertEquals("secret", request.getHeader("x-goog-api-key"))
                ModelApiType.ANTHROPIC -> assertEquals("secret", request.getHeader("x-api-key"))
                else -> assertEquals("Bearer secret", request.getHeader("Authorization"))
            }
        }
    }

    @Test fun sdkBearerSupportsCustomHeadersAndDoesNotRepeatPrefix() = runBlocking {
        for (type in types) for ((name, value, expected) in listOf(
            Triple("Authorization", "secret", "Bearer secret"),
            Triple("authorization", "Bearer secret", "Bearer secret"),
            Triple("AUTHORIZATION", "bearer secret", "bearer secret"),
            Triple("X-Gateway-Key", "secret", "secret")
        )) MockWebServer().use { server ->
            server.enqueue(modelsResponse(type))
            val result = ProviderAdaptersTestSupport(context).testConnection(
                provider(server, type, Authorization(AuthMethod.BEARER_TOKEN, name, value)))
            assertTrue("$type/$name: ${result.exceptionOrNull()}", result.isSuccess)
            assertEquals(expected, server.takeRequest(5, TimeUnit.SECONDS)!!.getHeader(name))
        }
    }

    @Test fun mediaTransportMatchesSdkAuthorizationAndLegacyHeaders() {
        MockWebServer().use { server ->
            val http = ToolHttp()
            for (method in listOf(AuthMethod.BEARER_TOKEN, AuthMethod.CUSTOM_HEADER)) {
                val auth = Authorization(method, "authorization", "Bearer secret")
                val request = http.request(server.url("/").toString(), provider(server, ModelApiType.GOOGLE, auth)).build()
                assertEquals("Bearer secret", request.header("Authorization"))
                assertNull(request.header("x-goog-api-key"))
            }
            val request = http.request(server.url("/").toString(),
                provider(server, ModelApiType.OPENAI, Authorization(AuthMethod.BEARER_TOKEN, "X-Key", ""))).build()
            assertNull(request.header("X-Key"))
            assertNull(request.header("Authorization"))
        }
    }
}
