package org.starfall.generative.database.repositories

import org.starfall.generative.shared.model.McpAuthorization
import org.starfall.generative.shared.model.McpInfo
import org.starfall.generative.shared.model.auth.Authorization
import org.starfall.generative.shared.model.auth.CredentialLoadState
import org.starfall.generative.shared.model.provider.ModelProvider

/** Strip conservatively without parsing/re-encoding: even malformed or escaped URLs retain their exact private form. */
internal data class EndpointSecrets(val publicUrl: String?, val privateUrl: String?) {
    companion object {
        fun split(raw: String?): EndpointSecrets {
            if (raw == null) return EndpointSecrets(null, null)
            val end = raw.indexOfAny(charArrayOf('?', '#')).takeIf { it >= 0 } ?: raw.length
            var public = raw.substring(0, end)
            val authorityStart = when {
                public.contains("://") -> public.indexOf("://") + 3
                public.startsWith("//") -> 2
                else -> 0
            }
            val authorityEnd = public.indexOf('/', authorityStart).takeIf { it >= 0 } ?: public.length
            val at = public.lastIndexOf('@', authorityEnd - 1)
            val hasUserInfo = at >= authorityStart
            if (hasUserInfo) public = public.substring(0, authorityStart) + public.substring(at + 1)
            return EndpointSecrets(public, raw.takeIf { hasUserInfo || end != raw.length })
        }
    }
}

internal fun restoreEndpoint(public: String?, private: String?): String? =
    private?.takeIf { EndpointSecrets.split(it).publicUrl == public } ?: public

internal fun ModelProvider.authPayload() = ProviderAuthPayload(
    auth, config.headers, config.httpProxy, config.socksProxy, config.apiKeys, config.oauthAccounts,
    EndpointSecrets.split(baseUrl).privateUrl, EndpointSecrets.split(config.customListModelsUrl).privateUrl
)

internal fun ProviderAuthPayload.hasSecrets(): Boolean =
    !authorization.key.isNullOrEmpty() || !authorization.value.isNullOrEmpty() || !authorization.oauthAccountId.isNullOrEmpty() ||
        headers.isNotEmpty() || httpProxy.isNotEmpty() || socksProxy.isNotEmpty() || apiKeys.isNotEmpty() || oauthAccounts.isNotEmpty() ||
        privateBaseUrl != null || privateCustomListModelsUrl != null

internal fun McpInfo.authPayload() = McpAuthPayload(headers.orEmpty(), auth, EndpointSecrets.split(url).privateUrl)

internal fun McpAuthPayload.hasSecrets(): Boolean =
    !authorization.key.isNullOrEmpty() || !authorization.value.isNullOrEmpty() ||
        !authorization.oauthClientId.isNullOrEmpty() || !authorization.oauthClientSecret.isNullOrEmpty() ||
        !authorization.oauthRedirectUri.isNullOrEmpty() || authorization.oauthAuthorized || headers.isNotEmpty() || privateUrl != null

internal fun ModelProvider.metadata(): ModelProvider {
    val configured = credentialState == CredentialLoadState.NOT_REQUESTED || authPayload().hasSecrets()
    return copy(
        auth = if (configured) Authorization() else Authorization(method = auth.method),
        baseUrl = EndpointSecrets.split(baseUrl).publicUrl.orEmpty(),
        config = config.copy(headers = emptyMap(), httpProxy = emptyMap(), socksProxy = emptyMap(), apiKeys = emptyList(),
            oauthAccounts = emptyList(), customListModelsUrl = EndpointSecrets.split(config.customListModelsUrl).publicUrl),
        credentialState = if (configured) CredentialLoadState.NOT_REQUESTED else CredentialLoadState.NOT_CONFIGURED
    )
}

internal fun McpInfo.metadata(): McpInfo {
    val configured = credentialState == CredentialLoadState.NOT_REQUESTED || authPayload().hasSecrets()
    return copy(url = EndpointSecrets.split(url).publicUrl, headers = null,
        auth = if (configured) McpAuthorization() else McpAuthorization(method = auth.method),
        credentialState = if (configured) CredentialLoadState.NOT_REQUESTED else CredentialLoadState.NOT_CONFIGURED)
}

internal fun requireSavableCredentials(state: CredentialLoadState) {
    check(state != CredentialLoadState.ERROR && state != CredentialLoadState.LOADING) {
        "Credentials are $state. Reload the selected record before saving."
    }
}
