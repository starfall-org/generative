package org.starfall.generative

import org.starfall.generative.integrations.modelapis.account.*

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.starfall.generative.integrations.modelapis.antigravity.AntigravityAdapter
import org.starfall.generative.integrations.modelapis.openaicodex.CodexTokenStore
import org.starfall.generative.integrations.modelapis.openaicodex.CodexTokenState
import org.starfall.generative.integrations.modelapis.openaicodex.OpenAICodexAdapter
import org.starfall.generative.shared.subfeatures.auth.logic.*
import org.starfall.generative.shared.model.*
import org.starfall.generative.shared.subfeatures.media.logic.AttachmentResolver
import org.starfall.generative.shared.model.auth.AuthMethod
import org.starfall.generative.shared.model.auth.Authorization
import org.starfall.generative.shared.model.api.ModelApiType
import org.starfall.generative.shared.model.provider.ModelProvider
import org.starfall.generative.shared.model.provider.oauthCredentialId

@Config(sdk = [28])
@RunWith(RobolectricTestRunner::class)
class OAuthPersistenceTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    @Before fun prepareKeystore() = installTestAndroidKeyStore()

    @Test fun antigravityAuthorizationAndConnectionDoNotRequireCodeAssistProject() = runBlocking {
        val id = "without-code-assist"
        AccountTokenStore(context, "antigravity").save(id, AccountTokenState("access", "refresh"))
        val provider = ModelProvider(id, "Antigravity", ModelApiType.ANTIGRAVITY,
            baseUrl = "http://127.0.0.1:1",
            auth = Authorization(AuthMethod.OAUTH, value = "account:antigravity:v1"))
        val adapter = AntigravityAdapter(context, AttachmentResolver(context))
        assertTrue(adapter.testConnection(provider).isSuccess)
        assertEquals("Bearer access", adapter.prepareAuthenticatedProvider(provider).auth.token)
        assertNull(AccountTokenStore(context, "antigravity").load(id)!!.projectId)
    }

    @Test fun firstCodexCredentialSaveIsReadableByANewStore() {
        val token = CodexTokenState("access", "refresh", email = "test@example.com")
        CodexTokenStore(context).save("first-codex-login", token)
        assertEquals(token, CodexTokenStore(context).load("first-codex-login"))
    }

    @Test fun selectedAccountUsesItsOwnTokenAndClearingItPreservesOtherAccounts() = runBlocking {
        val store = AccountTokenStore(context, "antigravity")
        store.save("multi", AccountTokenState("legacy-access", "legacy-refresh"))
        store.save("multi:second", AccountTokenState("second-access", "second-refresh"))
        val provider = ModelProvider("multi", "Antigravity", ModelApiType.ANTIGRAVITY,
            baseUrl = "http://127.0.0.1:1", auth = Authorization(AuthMethod.OAUTH, value = "account:antigravity:v1"))
        val adapter = AntigravityAdapter(context, AttachmentResolver(context))
        assertEquals("Bearer legacy-access", adapter.prepareAuthenticatedProvider(provider).auth.token)
        val second = provider.copy(auth = provider.auth.copy(oauthAccountId = "multi:second"))
        assertEquals("Bearer second-access", adapter.prepareAuthenticatedProvider(second).auth.token)
        adapter.clearCredentials(second.oauthCredentialId)
        assertNull(store.load("multi:second"))
        assertEquals("Bearer legacy-access", adapter.prepareAuthenticatedProvider(provider).auth.token)
    }

    @Test fun codexCatalogLoadsSelectedAccountWithoutTouchingLegacyCredentials() = runBlocking {
        val store = CodexTokenStore(context)
        store.save("codex:second", CodexTokenState("second-access", "second-refresh"))
        val provider = ModelProvider("codex", "Codex", ModelApiType.OPENAI_CODEX, baseUrl = "",
            auth = Authorization(AuthMethod.OAUTH, value = "openai_codex_oauth", oauthAccountId = "codex:second"))
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json")
                .setBody("""{"models":[{"slug":"codex-test-model"}]}"""))
            val adapter = OpenAICodexAdapter(
                context, AttachmentResolver(context),
                modelsEndpoint = server.url("/models").toString()
            )
            assertEquals(listOf("codex-test-model"), adapter.fetchModels(provider))
            val request = server.takeRequest(5, TimeUnit.SECONDS)!!
            assertEquals("Bearer second-access", request.getHeader("Authorization"))
            assertTrue(request.path!!.startsWith("/models?client_version="))
            assertNull(store.load("codex"))
            adapter.clearCredentials(provider.oauthCredentialId)
            assertNull(store.load("codex:second"))
        }
    }


}
