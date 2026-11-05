package org.starfall.generative.integrations.mcp

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.starfall.generative.shared.subfeatures.auth.logic.AppAuthTransactions
import org.starfall.generative.shared.subfeatures.auth.logic.OAuthBrowser
import org.starfall.generative.shared.subfeatures.auth.logic.OAuthCallbackService
import org.starfall.generative.shared.subfeatures.auth.logic.awaitLoopbackAuthorizationCode
import org.starfall.generative.shared.model.McpAuthMethod
import org.starfall.generative.shared.model.McpInfo
import org.starfall.generative.shared.model.auth.Authorization
import org.starfall.generative.shared.model.resolvedUrl
import org.starfall.generative.shared.helpers.networking.ToolHttp
import org.starfall.generative.shared.helpers.networking.text



class McpOAuthService(
    context: Context,
    private val http: ToolHttp = ToolHttp(),
    private val openBrowser: suspend (String) -> Unit = { url ->
        withContext(Dispatchers.Main) {
            OAuthBrowser.open(context.applicationContext, url)
        }
    },
    private val contentApiMcpUrl: String? = null
) {
    private val appContext = context.applicationContext
    private val store = McpOAuthTokenStore(appContext)

    suspend fun authorize(info: McpInfo): McpInfo {
        require(info.auth.method == McpAuthMethod.OAUTH2) { "MCP server is not configured for OAuth." }
        val endpoint = info.resolvedUrl(contentApiMcpUrl) ?: error("MCP URL is missing")
        val mcpEndpoint = canonicalResource(endpoint)
        val discovery = discover(endpoint, mcpEndpoint)
        val resource = discovery.resource
        require(discovery.metadata.codeChallengeMethods.any { it.equals("S256", true) }) {
            "Authorization server does not advertise PKCE S256 support."
        }
        val redirectUri = redirectUri(info.auth.oauthRedirectUri)

        val previous = store.load(info.id)
        val configuredClientId = info.auth.oauthClientId?.trim().orEmpty()
        val configuredClientSecret = info.auth.oauthClientSecret?.takeIf { it.isNotBlank() }
        val registration = when {
            configuredClientId.isNotEmpty() -> ClientRegistration(
                clientId = configuredClientId,
                clientSecret = configuredClientSecret,
                tokenEndpointAuthMethod = when {
                    configuredClientSecret == null -> "none"
                    "client_secret_basic" in discovery.metadata.tokenEndpointAuthMethods -> "client_secret_basic"
                    "client_secret_post" in discovery.metadata.tokenEndpointAuthMethods -> "client_secret_post"
                    discovery.metadata.tokenEndpointAuthMethods.isEmpty() -> "client_secret_basic"
                    else -> error("Authorization server does not support a compatible client-secret authentication method.")
                }
            )
            previous != null &&
                sameUri(previous.authorizationServer, discovery.authorizationServer) &&
                sameUri(previous.resource, resource) &&
                sameUri(previous.redirectUri, redirectUri) -> ClientRegistration(
                    previous.clientId,
                    previous.clientSecret,
                    previous.tokenEndpointAuthMethod
                )
            discovery.metadata.registrationEndpoint != null ->
                registerClient(discovery.metadata.registrationEndpoint, redirectUri)
            else -> error(
                "This authorization server does not support dynamic client registration. " +
                    "Register Generative as a public OAuth client and enter its Client ID."
            )
        }

        if (discovery.metadata.tokenEndpointAuthMethods.isNotEmpty()) {
            require(registration.tokenEndpointAuthMethod in discovery.metadata.tokenEndpointAuthMethods) {
                "Authorization server does not support token endpoint authentication method ${registration.tokenEndpointAuthMethod}."
            }
        }

        val request = AppAuthTransactions.authorization(
            discovery.metadata.authorizationEndpoint, discovery.metadata.tokenEndpoint,
            registration.clientId, redirectUri, discovery.scope, mapOf("resource" to resource))
        val code = OAuthCallbackService.keepAlive(appContext) {
            awaitLoopbackAuthorizationCode(redirectUri, request.toUri().toString(), request.state!!, openBrowser)
        }

        val token = exchangeAuthorizationCode(
            discovery = discovery,
            registration = registration,
            resource = resource,
            scope = discovery.scope,
            code = code,
            verifier = request.codeVerifier!!,
            mcpEndpoint = mcpEndpoint,
            redirectUri = redirectUri
        )
        store.save(info.id, token)
        return info.copy(
            auth = info.auth.copy(
                value = null,
                oauthClientId = configuredClientId.ifEmpty { null },
                oauthClientSecret = configuredClientSecret,
                oauthRedirectUri = info.auth.oauthRedirectUri?.trim()?.takeIf { it.isNotEmpty() },
                oauthAuthorized = true
            )
        )
    }

    suspend fun accessToken(info: McpInfo): String {
        if (info.auth.method != McpAuthMethod.OAUTH2) return info.auth.token
        val current = store.load(info.id)
        if (current == null) {
            return info.auth.value?.takeIf { it.isNotBlank() }
                ?: error("MCP OAuth credentials are missing. Authorize this server again.")
        }
        val endpoint = canonicalResource(info.resolvedUrl(contentApiMcpUrl) ?: error("MCP URL is missing"))
        require(sameUri(current.mcpEndpoint ?: current.resource, endpoint)) {
            "MCP server URL changed after OAuth authorization. Authorize this server again."
        }
        info.auth.oauthClientId?.trim()?.takeIf { it.isNotEmpty() }?.let { configured ->
            require(configured == current.clientId) {
                "MCP OAuth Client ID changed. Authorize this server again."
            }
        }
        info.auth.oauthClientSecret?.takeIf { it.isNotBlank() }?.let { configured ->
            require(configured == current.clientSecret) {
                "MCP OAuth client secret changed. Authorize this server again."
            }
        }
        if (current.expiresAt == null || current.expiresAt > System.currentTimeMillis() + 60_000) {
            return current.accessToken
        }
        require(current.refreshToken.isNotBlank()) {
            "MCP OAuth authorization expired. Authorize this server again."
        }
        val refreshed = refresh(current)
        store.save(info.id, refreshed)
        return refreshed.accessToken
    }

    fun clear(serverId: String) = store.delete(serverId)

    internal fun exportTokens(): Map<String, JsonObject> = store.exportAll()

    internal fun importTokens(tokens: Map<String, JsonObject>) = store.importAll(tokens)

    private suspend fun discover(endpointValue: String, requestedResource: String): OAuthDiscovery {
        val endpoint = endpointValue.toHttpUrl()
        var challengedMetadataUrl: String? = null
        var challengedScope: String? = null

        val probeBody = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", "oauth-discovery")
            put("method", "initialize")
            put("params", buildJsonObject {
                put("protocolVersion", "2025-11-25")
                put("capabilities", buildJsonObject {})
                put("clientInfo", buildJsonObject {
                    put("name", "Generative")
                    put("version", "1.0")
                })
            })
        }
        runCatching {
            http.withResponse(
                Request.Builder()
                    .url(endpoint)
                    .header("Accept", "application/json, text/event-stream")
                    .post(probeBody.toString().toRequestBody("application/json".toMediaType()))
                    .build()
            ) { response ->
                if (response.code == 401 || response.code == 403) {
                    response.headers("WWW-Authenticate").forEach { value ->
                        if (!value.trimStart().startsWith("Bearer", true)) return@forEach
                        challengedMetadataUrl = bearerParameter(value, "resource_metadata") ?: challengedMetadataUrl
                        challengedScope = bearerParameter(value, "scope") ?: challengedScope
                    }
                }
            }
        }

        val metadataCandidates = buildList {
            challengedMetadataUrl?.let(::add)
            add(protectedResourceMetadataUrl(endpoint, includePath = true))
            add(protectedResourceMetadataUrl(endpoint, includePath = false))
        }.distinct()

        var protected: JsonObject? = null
        for (candidate in metadataCandidates) {
            protected = fetchJsonOrNull(candidate)
            if (protected != null) break
        }

        val resource = protected?.text("resource")?.takeIf { it.isNotBlank() }?.let { advertised ->
            require(resourceCoversEndpoint(advertised, requestedResource)) {
                "Protected Resource Metadata describes a different MCP resource."
            }
            canonicalResource(advertised)
        } ?: requestedResource

        val authorizationServers = protected?.arrayStrings("authorization_servers").orEmpty()
        val servers = if (authorizationServers.isNotEmpty()) {
            authorizationServers
        } else {
            // Compatibility with the 2025-03-26 discovery model.
            listOf(endpoint.newBuilder().encodedPath("/").query(null).fragment(null).build().toString().trimEnd('/'))
        }

        var lastError: Throwable? = null
        for (authorizationServer in servers) {
            try {
                val metadata = discoverAuthorizationServer(authorizationServer)
                val scope = challengedScope
                    ?: protected?.arrayStrings("scopes_supported")?.joinToString(" ")?.takeIf { it.isNotBlank() }
                return OAuthDiscovery(authorizationServer, metadata, scope, resource)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                lastError = e
            }
        }
        throw lastError ?: IllegalStateException("Unable to discover the MCP authorization server.")
    }

    private suspend fun discoverAuthorizationServer(issuer: String): AuthorizationServerMetadata {
        val issuerUrl = issuer.toHttpUrl()
        requireSecureOAuthUrl(issuerUrl, "authorization server")
        var lastError: Throwable? = null
        for (candidate in authorizationMetadataUrls(issuerUrl)) {
            val json = try {
                fetchJsonOrNull(candidate)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                lastError = e
                null
            } ?: continue
            val metadataIssuer = json.text("issuer")
            if (metadataIssuer.isNotBlank()) {
                require(sameUri(metadataIssuer, issuer)) {
                    "Authorization server metadata issuer does not match the discovered issuer."
                }
            }
            val authorizationEndpoint = json.text("authorization_endpoint")
                .takeIf { it.isNotBlank() } ?: continue
            val tokenEndpoint = json.text("token_endpoint")
                .takeIf { it.isNotBlank() } ?: continue
            requireSecureOAuthUrl(authorizationEndpoint.toHttpUrl(), "authorization endpoint")
            requireSecureOAuthUrl(tokenEndpoint.toHttpUrl(), "token endpoint")
            return AuthorizationServerMetadata(
                authorizationEndpoint = authorizationEndpoint,
                tokenEndpoint = tokenEndpoint,
                registrationEndpoint = json.text("registration_endpoint").takeIf { it.isNotBlank() },
                codeChallengeMethods = json.arrayStrings("code_challenge_methods_supported"),
                tokenEndpointAuthMethods = json.arrayStrings("token_endpoint_auth_methods_supported")
            )
        }
        throw lastError ?: IllegalStateException("Unable to discover OAuth authorization server metadata.")
    }

    private suspend fun registerClient(endpoint: String, redirectUri: String): ClientRegistration {
        val url = endpoint.toHttpUrl()
        requireSecureOAuthUrl(url, "registration endpoint")
        val response = http.post(
            endpoint,
            buildJsonObject {
                put("client_name", "Generative")
                put("application_type", "native")
                put("redirect_uris", JsonArray(listOf(JsonPrimitive(redirectUri))))
                put("grant_types", JsonArray(listOf(
                    JsonPrimitive("authorization_code"),
                    JsonPrimitive("refresh_token")
                )))
                put("response_types", JsonArray(listOf(JsonPrimitive("code"))))
                put("token_endpoint_auth_method", "none")
            }
        )
        val clientId = response.text("client_id")
        require(clientId.isNotBlank()) { "Dynamic client registration returned no client_id." }
        return ClientRegistration(
            clientId = clientId,
            clientSecret = response.text("client_secret").takeIf { it.isNotBlank() },
            tokenEndpointAuthMethod = response.text("token_endpoint_auth_method").ifBlank { "none" }
        )
    }

    private suspend fun exchangeAuthorizationCode(
        discovery: OAuthDiscovery,
        registration: ClientRegistration,
        resource: String,
        scope: String?,
        code: String,
        verifier: String,
        mcpEndpoint: String,
        redirectUri: String
    ): McpOAuthTokenState {
        val fields = linkedMapOf(
            "grant_type" to "authorization_code",
            "code" to code,
            "redirect_uri" to redirectUri,
            "code_verifier" to verifier,
            "resource" to resource
        )
        val response = tokenRequest(
            discovery.metadata.tokenEndpoint,
            fields,
            registration
        )
        return tokenState(
            response = response,
            previous = null,
            registration = registration,
            discovery = discovery,
            resource = resource,
            mcpEndpoint = mcpEndpoint,
            scope = scope,
            redirectUri = redirectUri
        )
    }

    private suspend fun refresh(previous: McpOAuthTokenState): McpOAuthTokenState {
        val registration = ClientRegistration(
            previous.clientId,
            previous.clientSecret,
            previous.tokenEndpointAuthMethod
        )
        val response = tokenRequest(
            previous.tokenEndpoint,
            linkedMapOf(
                "grant_type" to "refresh_token",
                "refresh_token" to previous.refreshToken,
                "resource" to previous.resource
            ),
            registration
        )
        return tokenState(
            response = response,
            previous = previous,
            registration = registration,
            discovery = OAuthDiscovery(
                previous.authorizationServer,
                AuthorizationServerMetadata(
                    previous.authorizationEndpoint,
                    previous.tokenEndpoint,
                    null,
                    listOf("S256"),
                    emptyList()
                ),
                previous.scope,
                previous.resource
            ),
            resource = previous.resource,
            mcpEndpoint = previous.mcpEndpoint ?: previous.resource,
            scope = response.text("scope").takeIf { it.isNotBlank() } ?: previous.scope,
            redirectUri = previous.redirectUri
        )
    }

    private suspend fun tokenRequest(
        endpoint: String,
        fields: Map<String, String>,
        registration: ClientRegistration
    ): JsonObject {
        return AppAuthTransactions.exchange(appContext, http,
            AppAuthTransactions.token(endpoint, registration.clientId, fields),
            AppAuthTransactions.authentication(registration.tokenEndpointAuthMethod, registration.clientSecret))
    }

    private fun tokenState(
        response: JsonObject,
        previous: McpOAuthTokenState?,
        registration: ClientRegistration,
        discovery: OAuthDiscovery,
        resource: String,
        mcpEndpoint: String,
        scope: String?,
        redirectUri: String
    ): McpOAuthTokenState {
        val accessToken = response.text("access_token")
        require(accessToken.isNotBlank()) { "OAuth token response has no access_token." }
        val tokenType = response.text("token_type").ifBlank { "Bearer" }
        require(tokenType.equals("Bearer", true)) { "Unsupported OAuth token type: $tokenType" }
        val expiresIn = response["expires_in"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
        return McpOAuthTokenState(
            accessToken = accessToken,
            refreshToken = response.text("refresh_token").ifBlank { previous?.refreshToken.orEmpty() },
            tokenType = tokenType,
            expiresAt = expiresIn?.let { System.currentTimeMillis() + it.coerceAtLeast(0) * 1000 },
            clientId = registration.clientId,
            clientSecret = registration.clientSecret,
            tokenEndpointAuthMethod = registration.tokenEndpointAuthMethod,
            authorizationServer = discovery.authorizationServer,
            authorizationEndpoint = discovery.metadata.authorizationEndpoint,
            tokenEndpoint = discovery.metadata.tokenEndpoint,
            resource = resource,
            mcpEndpoint = mcpEndpoint,
            scope = response.text("scope").takeIf { it.isNotBlank() } ?: scope,
            redirectUri = redirectUri
        )
    }

    private suspend fun fetchJsonOrNull(url: String): JsonObject? {
        val target = runCatching { url.toHttpUrl() }.getOrNull() ?: return null
        requireSecureOAuthUrl(target, "metadata endpoint")
        return http.withResponse(http.request(url).get().build()) { response ->
            if (!response.isSuccessful) return@withResponse null
            val contentType = response.header("Content-Type").orEmpty().lowercase()
            if (contentType.isNotEmpty() && !contentType.contains("json")) return@withResponse null
            runCatching {
                Json.parseToJsonElement(http.readJson(response)) as? JsonObject
            }.getOrNull()
        }
    }

    private data class OAuthDiscovery(
        val authorizationServer: String,
        val metadata: AuthorizationServerMetadata,
        val scope: String?,
        val resource: String
    )

    private data class AuthorizationServerMetadata(
        val authorizationEndpoint: String,
        val tokenEndpoint: String,
        val registrationEndpoint: String?,
        val codeChallengeMethods: List<String>,
        val tokenEndpointAuthMethods: List<String>
    )

    private data class ClientRegistration(
        val clientId: String,
        val clientSecret: String? = null,
        val tokenEndpointAuthMethod: String = "none"
    )

    companion object {
        const val REDIRECT_URI = "http://localhost:53682/oauth-callback"
    }
}
