package org.starfall.generative

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.starfall.generative.integrations.security.oauthRecordStorage
import org.junit.Test
import org.junit.runner.RunWith
import org.starfall.generative.integrations.modelapis.account.AccountTokenState
import org.starfall.generative.integrations.modelapis.account.AccountTokenStore
import org.starfall.generative.integrations.modelapis.devin.DevinAdapter
import org.starfall.generative.integrations.modelapis.geminicli.GeminiCliAdapter
import org.starfall.generative.shared.files.AttachmentResolver
import org.starfall.generative.shared.model.api.ModelApiType
import org.starfall.generative.shared.model.auth.defaultAuthorization
import org.starfall.generative.shared.model.provider.ModelProvider
import java.util.UUID

/** Device-only AndroidKeystore, account isolation and bundled Devin model catalog tests. */
@RunWith(AndroidJUnit4::class)
class GeminiCliDevinCredentialDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun bothProvidersStoreIndependentEncryptedTokensAndCleanup() {
        val id = "android-oauth-test-${UUID.randomUUID()}"
        val google = AccountTokenStore(context, ModelApiType.GEMINI_CLI.name.lowercase())
        val devin = AccountTokenStore(context, "devin")
        try {
            google.save(id, AccountTokenState("google-private-token", "google-refresh", projectId = "project-alpha"))
            devin.save(id, AccountTokenState("devin-private-token", ""))
            assertEquals("google-private-token", google.load(id)?.accessToken)
            assertEquals("google-refresh", google.load(id)?.refreshToken)
            assertEquals("project-alpha", google.load(id)?.projectId)
            assertEquals("devin-private-token", devin.load(id)?.accessToken)
            assertNotEquals(google.load(id)?.accessToken, devin.load(id)?.accessToken)
            assertEquals("google-private-token", google.exportAll()[id]?.get("accessToken")?.toString()?.trim('"'))
            val storage = oauthRecordStorage(context)
            val googleEncrypted = storage.get("generative.gemini_cli.oauth", id)
            val devinEncrypted = storage.get("generative.devin.oauth", id)
            assertNotNull(googleEncrypted)
            assertNotNull(devinEncrypted)
            assertTrue(googleEncrypted!!.startsWith("keystore:v1:"))
            assertTrue(devinEncrypted!!.startsWith("keystore:v1:"))
            assertFalse(googleEncrypted.contains("google-private-token"))
            assertFalse(devinEncrypted.contains("devin-private-token"))
        } finally {
            google.delete(id)
            devin.delete(id)
        }
        assertNull(google.load(id))
        assertNull(devin.load(id))
    }

    @Test fun unauthenticatedProvidersNeverTreatOAuthAsAnApiKey() {
        val resolver = AttachmentResolver(context)
        val google = GeminiCliAdapter(context, resolver)
        val devin = DevinAdapter(context, resolver)
        val providers = listOf(google to ModelApiType.GEMINI_CLI, devin to ModelApiType.DEVIN)
        providers.forEach { (adapter, type) ->
            val provider = ModelProvider("no-token-${UUID.randomUUID()}", type.displayName, type,
                auth = type.defaultAuthorization(), baseUrl = type.defaultBaseUrl)
            try {
                kotlinx.coroutines.runBlocking { adapter.prepareAuthenticatedProvider(provider) }
                fail("Provider $type must require OAuth before accessing model APIs")
            } catch (e: IllegalArgumentException) {
                assertTrue(e.message.orEmpty().contains("not authorized", ignoreCase = true))
            }
        }
    }

    @Test fun devinModelCatalogIsBundledAndAvailableWithoutExternalServicesAfterAuthentication() {
        val asset = context.assets.open("devin_models_catalog.json").bufferedReader().use { it.readText() }
        val parsed = kotlinx.serialization.json.Json.parseToJsonElement(asset).jsonObject
        val array = parsed["devin"] as? kotlinx.serialization.json.JsonArray
        assertNotNull(array)
        assertTrue("Bundled catalog should be populated", array!!.size >= 40)
        assertEquals("swe-2", array.first().jsonObject["id"]!!.jsonPrimitive.content)
    }
}
