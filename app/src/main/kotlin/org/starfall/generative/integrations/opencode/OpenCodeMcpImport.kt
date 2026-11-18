package org.starfall.generative.integrations.opencode

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.starfall.generative.shared.model.McpAuthMethod
import org.starfall.generative.shared.model.McpInfo
import org.starfall.generative.shared.model.auth.bearerHeaderValue

/** OpenCode accepts remote URL and headers, but not Android's OAuth token store. */
internal fun openCodeMcpImportConfig(server: McpInfo): JsonObject {
    val auth = server.auth
    require(auth.method != McpAuthMethod.OAUTH2 && !auth.oauthAuthorized &&
        auth.oauthClientId == null && auth.oauthClientSecret == null && auth.oauthRedirectUri == null)
    val url = requireNotNull(server.url).toHttpUrl().newBuilder()
    val headers = server.headers.orEmpty().toMutableMap()
    when (auth.method) {
        McpAuthMethod.NONE -> Unit
        McpAuthMethod.BEARER_TOKEN, McpAuthMethod.CUSTOM_HEADER -> {
            val name = auth.key?.takeIf { it.isNotBlank() } ?: "Authorization"
            require(auth.token.isNotBlank())
            headers.keys.removeAll { it.equals(name, ignoreCase = true) }
            headers[name] = bearerHeaderValue(name, auth.token)
        }
        McpAuthMethod.QUERY_PARAM -> {
            require(auth.token.isNotBlank())
            url.addQueryParameter(auth.key?.takeIf { it.isNotBlank() } ?: "key", auth.token)
        }
        McpAuthMethod.OAUTH2 -> error("OAuth credential migration is not supported")
    }
    return buildJsonObject {
        put("type", "remote")
        put("url", url.build().toString())
        put("oauth", false)
        if (headers.isNotEmpty()) put("headers", buildJsonObject {
            headers.forEach { (name, value) -> put(name, value) }
        })
    }
}
