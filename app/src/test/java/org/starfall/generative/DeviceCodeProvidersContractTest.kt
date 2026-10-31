package org.starfall.generative

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.starfall.generative.integrations.modelapis.account.AccountTokenState
import org.starfall.generative.integrations.modelapis.account.accountTokenFromResponse
import org.starfall.generative.shared.model.api.ModelApiType
import org.starfall.generative.shared.model.auth.AuthMethod
import org.starfall.generative.shared.model.auth.defaultAuthorization

class DeviceCodeProvidersContractTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test fun deviceCodeProvidersAreOAuthAccountProviders() {
        listOf(ModelApiType.GROK_BUILD, ModelApiType.META_MUSE).forEach { type ->
            assertTrue(type.isAccountProvider)
            assertEquals(AuthMethod.OAUTH, type.defaultAuthorization().method)
        }
        assertEquals("https://cli-chat-proxy.grok.com/v1", ModelApiType.GROK_BUILD.defaultBaseUrl)
        assertEquals("https://api.meta.ai/v1", ModelApiType.META_MUSE.defaultBaseUrl)
    }

    @Test fun oldCredentialRecordsLoadWithNewOptionalFields() {
        val legacy = """{"accessToken":"old-token","refreshToken":"old-refresh"}"""
        val decoded = json.decodeFromString<AccountTokenState>(legacy)
        assertEquals("old-token", decoded.accessToken)
        assertNull(decoded.deviceToken)
        assertNull(decoded.apiBaseUrl)
        assertNull(decoded.tokenEndpoint)
    }

    @Test fun mintedDeviceCredentialSurvivesSerialization() {
        val token = AccountTokenState(accessToken = "minted-key", refreshToken = "",
            deviceToken = "dca:example", deviceTokenExpiresAt = 111_000L,
            apiBaseUrl = "https://api.meta.ai/v1", email = "account@example.test")
        assertEquals(token, json.decodeFromString<AccountTokenState>(json.encodeToString(token)))
        assertNull(token.expiresAt) // DCA expiry must not expire the minted API key.
    }

    @Test fun xaiRefreshRetainsRotatedIdentityAndEndpoint() {
        val prior = AccountTokenState("old", "refresh-old", expiresAt = 100L,
            accountId = "account-id", email = "account@example.test",
            tokenEndpoint = "https://auth.x.ai/oauth/token")
        val response = buildJsonObject {
            put("access_token", "new")
            put("expires_in", 3600)
        }
        val updated = accountTokenFromResponse(response, prior, now = 1_000L)
        assertEquals("new", updated.accessToken)
        assertEquals("refresh-old", updated.refreshToken)
        assertEquals(3_601_000L, updated.expiresAt)
        assertEquals(prior.tokenEndpoint, updated.tokenEndpoint)
        assertEquals(prior.email, updated.email)
    }
}
