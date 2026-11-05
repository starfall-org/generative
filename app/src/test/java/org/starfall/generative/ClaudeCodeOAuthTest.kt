package org.starfall.generative

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.starfall.generative.integrations.modelapis.account.AccountTokenState
import org.starfall.generative.integrations.modelapis.account.AccountTokenStore
import org.starfall.generative.integrations.modelapis.claudecode.ClaudeCodeAdapter
import org.starfall.generative.integrations.modelapis.claudecode.CLAUDE_CODE_OAUTH_SCOPE
import org.starfall.generative.shared.subfeatures.auth.logic.AppAuthTransactions
import org.starfall.generative.shared.subfeatures.media.logic.AttachmentResolver
import org.starfall.generative.shared.model.api.ModelApiType
import org.starfall.generative.shared.model.auth.AuthMethod
import org.starfall.generative.shared.model.auth.Authorization
import org.starfall.generative.shared.model.provider.ModelProvider
import org.starfall.generative.shared.helpers.networking.text

@Config(sdk = [28])
@RunWith(RobolectricTestRunner::class)
class ClaudeCodeOAuthTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before fun prepareKeystore() = installTestAndroidKeyStore()

    @Test fun authorizationUrlUsesOnlyRequiredClaudeScopes() {
        val request = AppAuthTransactions.authorization(
            "https://claude.ai/oauth/authorize",
            "https://platform.claude.com/v1/oauth/token",
            "9d1c250a-e61b-44d9-88ed-5944d1962f5e",
            "http://localhost:54545/callback",
            CLAUDE_CODE_OAUTH_SCOPE,
            mapOf("code" to "true")
        ).toUri()
        val scopes = request.getQueryParameter("scope").orEmpty().split(' ').filterTo(mutableSetOf()) { it.isNotEmpty() }
        assertEquals(setOf("user:profile", "user:inference", "user:sessions:claude_code",
            "user:mcp_servers", "user:file_upload"), scopes)
        assertFalse("org:create_api_key" in scopes)
        assertFalse(CLAUDE_CODE_OAUTH_SCOPE.contains("org:create_api_key"))
    }

    @Test fun existingClaudeCredentialRefreshesWithoutRequestingScopes() = runBlocking {
        MockWebServer().use { server ->
            val store = AccountTokenStore(context, "claude_code")
            store.save("existing", AccountTokenState("expired", "old-refresh", expiresAt = 1,
                accountId = "account", email = "user@example.com"))
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(
                """{"access_token":"fresh","refresh_token":"rotated","expires_in":3600}"""))
            val provider = ModelProvider("existing", "Claude", ModelApiType.CLAUDE_CODE,
                auth = Authorization(AuthMethod.OAUTH, value = "account:claude_code:v1"),
                baseUrl = ModelApiType.CLAUDE_CODE.defaultBaseUrl)
            val prepared = ClaudeCodeAdapter(context, AttachmentResolver(context),
                tokenEndpoint = server.url("/token").toString()).prepareAuthenticatedProvider(provider)
            assertEquals("Bearer fresh", prepared.auth.value)
            assertEquals("rotated", store.load("existing")!!.refreshToken)
            assertEquals("account", store.load("existing")!!.accountId)
            val fields = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
            assertEquals("refresh_token", fields.text("grant_type"))
            assertEquals("old-refresh", fields.text("refresh_token"))
            assertFalse(fields.containsKey("scope"))
        }
    }
}
