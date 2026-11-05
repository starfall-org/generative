package org.starfall.generative.shared.subfeatures.auth.logic

import android.net.Uri
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import net.openid.appauth.*
import org.json.JSONObject
import org.starfall.generative.shared.subfeatures.logging.logic.DebugLog
import org.starfall.generative.shared.model.logging.DebugLogCategory
import org.starfall.generative.shared.model.logging.DebugLogLevel
import okhttp3.FormBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import org.starfall.generative.shared.helpers.networking.ToolHttp

/** Builds AppAuth authorization requests and exchanges OAuth tokens without OIDC ID-token validation. */
internal object AppAuthTransactions {
    fun authorization(authorizationEndpoint: String, tokenEndpoint: String, clientId: String,
        redirectUri: String, scope: String?, additional: Map<String, String> = emptyMap()): AuthorizationRequest =
        AuthorizationRequest.Builder(AuthorizationServiceConfiguration(Uri.parse(authorizationEndpoint), Uri.parse(tokenEndpoint)),
            clientId, ResponseTypeValues.CODE, Uri.parse(redirectUri))
            .setScope(scope?.takeIf { it.isNotBlank() })
            .setPrompt(additional["prompt"])
            .setAdditionalParameters(additional - "prompt")
            .build()

    fun token(endpoint: String, clientId: String, fields: Map<String, String>): TokenRequest =
        TokenRequest.Builder(AuthorizationServiceConfiguration(Uri.parse(endpoint), Uri.parse(endpoint)), clientId)
            .setGrantType(fields.getValue("grant_type"))
            .setAuthorizationCode(fields["code"])
            .setRedirectUri(fields["redirect_uri"]?.let(Uri::parse))
            .setCodeVerifier(fields["code_verifier"])
            .setRefreshToken(fields["refresh_token"])
            .setScope(fields["scope"])
            .setAdditionalParameters(fields - setOf("grant_type", "code", "redirect_uri", "code_verifier", "refresh_token", "scope"))
            .build()

    fun authentication(method: String, secret: String?): ClientAuthentication = when (method) {
        "", "none" -> NoClientAuthentication.INSTANCE
        "client_secret_basic" -> ClientSecretBasic(requireNotNull(secret) { "OAuth client secret is missing." })
        "client_secret_post" -> ClientSecretPost(requireNotNull(secret) { "OAuth client secret is missing." })
        else -> error("Unsupported token endpoint authentication method: $method")
    }

    fun fields(request: TokenRequest, auth: ClientAuthentication = NoClientAuthentication.INSTANCE): Map<String, String> =
        request.requestParameters + auth.getRequestParameters(request.clientId).orEmpty()

    suspend fun exchange(context: Context, http: ToolHttp, request: TokenRequest,
        auth: ClientAuthentication = NoClientAuthentication.INSTANCE, jsonBody: Boolean = false): JsonObject =
        if (jsonBody || request.configuration.tokenEndpoint.scheme != "https") {
            CompatibilityOAuth.exchangeToken(http, request, auth, jsonBody)
        } else executeToken(request, auth)

    /**
     * Executes a standard form token request without AppAuth's OIDC ID-token validation.
     * Some OAuth providers return provider-specific ID tokens that AppAuth rejects as
     * "Invalid ID Token" even though the access/refresh-token response is usable.
     */
    internal suspend fun executeToken(request: TokenRequest,
        auth: ClientAuthentication = NoClientAuthentication.INSTANCE): JsonObject =
        withContext(Dispatchers.IO) { CompatibilityOAuth.exchangeToken(tokenHttp, request, auth) }

    private val tokenHttp by lazy {
        ToolHttp(client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(180, TimeUnit.SECONDS)
            .callTimeout(5, TimeUnit.MINUTES)
            .followRedirects(false)
            .build())
    }
}

/** Raw OAuth token transport for providers whose token payloads AppAuth cannot parse. */
internal object CompatibilityOAuth {
    /** Handles form/JSON token bodies and normalizes providers that omit token_type. */
    suspend fun exchangeToken(http: ToolHttp, request: TokenRequest, auth: ClientAuthentication = NoClientAuthentication.INSTANCE,
        jsonBody: Boolean = false): JsonObject {
        val values = AppAuthTransactions.fields(request, auth)
        val headers = auth.getRequestHeaders(request.clientId).orEmpty()
        val response = if (jsonBody) http.json(http.request(request.configuration.tokenEndpoint.toString()).apply {
            headers.forEach { (name, value) -> header(name, value) }
        }.post(JsonObject(values.mapValues { JsonPrimitive(it.value) }).toString()
            .toRequestBody("application/json".toMediaType())).build())
        else http.json(http.request(request.configuration.tokenEndpoint.toString()).apply {
            headers.forEach { (name, value) -> header(name, value) }
        }.post(FormBody.Builder().apply { values.forEach { (name, value) -> add(name, value) } }.build()).build())
        // Some compatible servers omit token_type; retain the app's Bearer fallback.
        val normalized = JsonObject(response + (if (response["token_type"] == null)
            mapOf("token_type" to JsonPrimitive("Bearer")) else emptyMap()))
        DebugLog.log(DebugLogCategory.NETWORK, "OAuth") {
            "Token exchange succeeded; grant=${values["grant_type"] ?: "unknown"}, " +
                "format=${if (jsonBody) "JSON" else "form"}, fallbackTokenType=${response["token_type"] == null}"
        }

        // AppAuth's parser also validates OIDC ID tokens. Keep that validation diagnostic-only:
        // providers may return non-standard ID tokens, while the OAuth access token is still usable.
        runCatching { TokenResponse.Builder(request).fromResponseJson(JSONObject(normalized.toString())).build() }
            .onFailure { error ->
                DebugLog.log(DebugLogCategory.NETWORK, "OAuth", DebugLogLevel.WARN) {
                    "Accepted token response despite AppAuth validation warning: ${error.message ?: error::class.java.simpleName}"
                }
            }
        return normalized
    }
}
