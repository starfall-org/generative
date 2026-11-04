package org.starfall.generative.shared.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

enum class McpProtocol {
    @SerialName("streamable_http") STREAMABLE_HTTP,
    @SerialName("sse") SSE
}

@Serializable
enum class McpAuthMethod {
    NONE,
    BEARER_TOKEN,
    QUERY_PARAM,
    CUSTOM_HEADER,
    OAUTH2
}

@Serializable
data class McpAuthorization(
    val method: McpAuthMethod = McpAuthMethod.NONE,
    val key: String? = null,
    val value: String? = null,
    val oauthClientId: String? = null,
    val oauthClientSecret: String? = null,
    val oauthRedirectUri: String? = null,
    val oauthAuthorized: Boolean = false
) {
    val token: String get() = value.orEmpty()
}

@Serializable
data class McpInfo(
    val id: String,
    val name: String,
    val protocol: McpProtocol = McpProtocol.STREAMABLE_HTTP,
    val url: String? = null,
    val headers: Map<String, String>? = null,
    val auth: McpAuthorization = McpAuthorization(),
    val cachedTools: List<ToolDefinition>? = null,
    val sortOrder: Int = Int.MAX_VALUE,
    val icon: String? = null,
    @kotlinx.serialization.Transient val credentialState: org.starfall.generative.shared.model.auth.CredentialLoadState = org.starfall.generative.shared.model.auth.CredentialLoadState.LOADED
)

fun String.isContentApiName(): Boolean =
    trim().lowercase().filter(Char::isLetterOrDigit).let {
        it == "contentapi" || it == "mcpcontentapi" || it == "contentapimcp"
    }

fun McpInfo.isContentApiPreset(): Boolean = id == "content-api" || name.isContentApiName()

/** Returns the explicit endpoint stored on this server, if one is present. */
fun McpInfo.resolvedUrl(): String? = url?.trim()?.takeIf(String::isNotEmpty)

/** Applies a host-provided endpoint for the bundled Content API preset without changing the model. */
fun McpInfo.resolvedUrl(contentApiEndpoint: String?): String? =
    resolvedUrl() ?: contentApiEndpoint?.trim()?.takeIf { isContentApiPreset() && it.isNotEmpty() }
