package org.starfall.generative

import android.net.Uri
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.starfall.generative.shared.model.*
import org.starfall.generative.shared.model.auth.AuthMethod
import org.starfall.generative.shared.model.auth.Authorization
import org.starfall.generative.shared.model.api.ModelApiType
import org.starfall.generative.shared.model.auth.requestHeaders

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ProviderLinkTest {
    @Test fun defaultsAndEncodedValuesPopulateProviderCredentials() {
        val uri = Uri.Builder().scheme("generative").authority("provider")
            .appendQueryParameter("name", "Gateway ảnh & video")
            .appendQueryParameter("url", "https://example.com/v1?route=a&mode=b")
            .appendQueryParameter("key", "test+a/b=c&d%value")
            .appendQueryParameter("icon_url", "https://example.com/icon.png?size=128&theme=dark").build()
        val provider = parseProviderLink(uri)
        assertEquals(ModelApiType.OPENAI, provider.type)
        assertEquals(AuthMethod.PLATFORM_DEFAULT, provider.auth.method)
        assertEquals("Gateway ảnh & video", provider.name)
        assertEquals("https://example.com/v1?route=a&mode=b", provider.baseUrl)
        assertEquals("test+a/b=c&d%value", provider.auth.token)
        assertEquals("https://example.com/icon.png?size=128&theme=dark", provider.icon)
        assertEquals("Bearer test+a/b=c&d%value", provider.auth.requestHeaders(provider.type)["Authorization"])
        // The same link always resolves to the same provider so re-delivery cannot duplicate it.
        assertEquals(provider.id, parseProviderLink(uri).id)
        assertNotEquals(provider.id, parseProviderLink(Uri.parse("generative://provider?name=Other&key=test")).id)
    }

    @Test fun explicitTypeAndAuthUseTheirWireSemantics() {
        val google = parseProviderLink(Uri.parse("generative://provider?provider_type=gemini&auth_type=platform&key=test"))
        assertEquals(ModelApiType.GOOGLE, google.type)
        assertEquals(ModelApiType.GOOGLE.defaultBaseUrl, google.baseUrl)
        assertEquals(mapOf("x-goog-api-key" to "test"), google.auth.requestHeaders(google.type))
        val bearer = parseProviderLink(Uri.parse("generative://provider?provider_type=openai_responses&auth_type=bearer-token&key=test"))
        assertEquals(ModelApiType.OPENAI_RESPONSES, bearer.type)
        assertEquals(AuthMethod.BEARER_TOKEN, bearer.auth.method)
        assertEquals("Authorization", bearer.auth.key)
        assertEquals("test", bearer.auth.token)
        val query = parseProviderLink(Uri.parse("generative://provider?auth_type=query&key=test"))
        assertEquals(AuthMethod.QUERY_PARAM, query.auth.method)
        assertEquals("key", query.auth.key)
        assertEquals("test", query.auth.value)
    }

    @Test fun legacyParameterNamesRemainCompatible() {
        val provider = parseProviderLink(Uri.parse(
            "generative://provider?type=gemini&auth=bearer&key=test"
        ))
        assertEquals(ModelApiType.GOOGLE, provider.type)
        assertEquals(AuthMethod.BEARER_TOKEN, provider.auth.method)
    }

    @Test fun legacyMgSchemeRemainsCompatible() {
        val provider = parseProviderLink(Uri.parse("mg://provider?provider_type=gemini&auth_type=platform&key=test"))
        assertEquals(ModelApiType.GOOGLE, provider.type)
        assertEquals(AuthMethod.PLATFORM_DEFAULT, provider.auth.method)
    }

    @Test fun blankOptionsUseDefaultsAndIncompleteProvidersCanBeEdited() {
        val provider = parseProviderLink(Uri.parse("generative://provider?provider_type=&name=&url=&auth_type=&key="))
        assertEquals(ModelApiType.OPENAI, provider.type)
        assertEquals(AuthMethod.PLATFORM_DEFAULT, provider.auth.method)
        assertEquals("New provider", provider.name)
        assertEquals("", provider.baseUrl)
        assertEquals("", provider.auth.token)
    }

    @Test fun invalidLinksAreRejectedWithoutLeakingCredentialValues() {
        listOf(
            "https://provider?key=secret", "generative://oauth?key=secret",
            "mg://oauth?key=secret", "generative://provider?provider_type=secret",
            "generative://provider?auth_type=secret", "generative://provider?url=file%3A%2F%2Fsecret&key=secret",
            "generative://provider?icon_url=content%3A%2F%2Fsecret&key=secret"
        ).forEach { link ->
            val error = runCatching { parseProviderLink(Uri.parse(link)) }.exceptionOrNull()
            assertTrue(error is IllegalArgumentException)
            assertFalse(error!!.message.orEmpty().contains("secret"))
        }
    }
}
