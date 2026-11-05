package org.starfall.generative.integrations.modelapis.antigravity

import android.content.Context
import okhttp3.FormBody
import org.starfall.generative.shared.subfeatures.logging.logic.DebugLogInterceptor
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import org.starfall.generative.integrations.modelapis.account.*
import org.starfall.generative.shared.model.*
import org.starfall.generative.shared.model.api.*
import org.starfall.generative.shared.model.auth.*
import org.starfall.generative.shared.model.generation.*
import org.starfall.generative.shared.model.language.*
import org.starfall.generative.shared.model.provider.*
import org.starfall.generative.shared.subfeatures.media.logic.AttachmentResolver
import org.starfall.generative.integrations.modelapis.discoveredModel
import java.util.UUID
import org.starfall.generative.shared.helpers.networking.ToolHttp
import org.starfall.generative.shared.helpers.networking.obj
import org.starfall.generative.shared.helpers.networking.str
import org.starfall.generative.shared.helpers.networking.text

internal class AntigravityAdapter(context: Context, attachments: AttachmentResolver) : OAuthAccountAdapter(
    context, attachments, ModelApiType.ANTIGRAVITY,
    "1071006060591-tmhssin2h21lcre235vtolojh4g403ep.apps.googleusercontent.com",
    "https://accounts.google.com/o/oauth2/v2/auth", "https://oauth2.googleapis.com/token",
    "https://www.googleapis.com/auth/cloud-platform https://www.googleapis.com/auth/userinfo.email https://www.googleapis.com/auth/userinfo.profile https://www.googleapis.com/auth/cclog https://www.googleapis.com/auth/experimentsandconfigs",
    51121, "/oauth-callback", "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",
    callbackHost = "127.0.0.1"
) {
    // This endpoint wraps Gemini payloads in Antigravity's private project/request envelope.
    override val useOfficialSdkForGeneration: Boolean = false

    // Antigravity's Client-Metadata header follows the reference client's IDE fingerprint.
    private val metadata =
        obj("ideType" to str("ANTIGRAVITY"), "platform" to str("MACOS"), "pluginType" to str("GEMINI"))

    // Code Assist uses different metadata contracts for project loading and onboarding.
    private val loadCodeAssistMetadata = obj("ideType" to str("ANTIGRAVITY"))

    // Match the reference provider's raw OAuth token exchange. AppAuth's OIDC token parsing
    // rejects Google's Antigravity response with "Invalid ID Token".
    private val oauthHttp by lazy {
        ToolHttp(client = http.client.newBuilder().apply {
            interceptors().removeAll { it is DebugLogInterceptor }
        }.build())
    }

    override fun authorizationAdditionalParameters(): Map<String, String> = mapOf(
        "access_type" to "offline",
        "prompt" to "consent",
        "include_granted_scopes" to "true"
    )

    override suspend fun exchange(
        fields: Map<String, String>,
        previous: AccountTokenState?
    ): AccountTokenState {
        val form = FormBody.Builder().apply {
            add("client_id", clientId)
            clientSecret?.let { add("client_secret", it) }
            fields.filterKeys { it != "state" }.forEach { (key, value) -> add(key, value) }
        }.build()
        val request = oauthHttp.request(
            tokenUrl,
            extraHeaders = mapOf(
                "Accept" to "*/*",
                "User-Agent" to GEMINI_CLI_USER_AGENT,
                "X-Goog-Api-Client" to GEMINI_CLI_API_CLIENT
            )
        ).post(form).build()
        return accountTokenFromResponse(oauthHttp.json(request), previous)
    }

    override suspend fun enrichToken(token: AccountTokenState): AccountTokenState {
        require(token.refreshToken.isNotBlank()) {
            "Google did not return an Antigravity refresh token. Sign in again and grant offline access."
        }
        val userInfo = try {
            oauthHttp.json(oauthHttp.request(
                GOOGLE_USERINFO_URL,
                extraHeaders = mapOf(
                    "Authorization" to "Bearer ${token.accessToken}",
                    "User-Agent" to GEMINI_CLI_USER_AGENT,
                    "X-Goog-Api-Client" to GEMINI_CLI_API_CLIENT
                )
            ).get().build())
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        return token.copy(email = userInfo?.text("email")?.takeIf(String::isNotBlank) ?: token.email)
    }

    private fun wire(provider: ModelProvider, token: AccountTokenState) = provider.copy(
        type = ModelApiType.GOOGLE,
        auth = Authorization(AuthMethod.CUSTOM_HEADER, "Authorization", "Bearer ${token.accessToken}"),
        config = provider.config.copy(
            headers = provider.config.headers + mapOf(
                "User-Agent" to antigravityUserAgent(provider.config),
                "X-Goog-Api-Client" to "google-cloud-sdk vscode_cloudshelleditor/0.1",
                "Client-Metadata" to metadata.toString()
            )
        )
    )

    private val projectLock = Mutex()

    /** Resolves the project right after sign-in so the editor can show the discovered ID; failures never block sign-in. */
    override suspend fun authorize(provider: ModelProvider): Result<ModelProvider> {
        val result = super.authorize(provider)
        val signedIn = result.getOrNull() ?: return result
        val credentialId = signedIn.oauthCredentialId
        if (signedIn.config.antigravityProjectId(credentialId) != null) return result
        val projectId = try {
            ensureProject(signedIn).projectId?.trim()?.takeIf(String::isNotEmpty)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        } ?: return result
        return Result.success(signedIn.copy(config = signedIn.config.copy(
            antigravityProjectIds = signedIn.config.antigravityProjectIds + (credentialId to projectId))))
    }

    private companion object {
        // Cloud Code requires >= 2.23.0 for newer models. Custom versions must not borrow another release's CL.
        const val ANTIGRAVITY_ONBOARD_NODE_USER_AGENT = "google-api-nodejs-client/10.3.0"
        const val ANTIGRAVITY_ONBOARD_API_CLIENT = "gl-node/22.21.1"
        const val LEGACY_DEFAULT_BASE_URL = "https://daily-cloudcode-pa.sandbox.googleapis.com"
        const val GEMINI_CLI_USER_AGENT = "GeminiCLI/0.1.5 (Windows; AMD64)"
        const val GEMINI_CLI_API_CLIENT = "gl-node/22.18.0"
        const val GOOGLE_USERINFO_URL = "https://www.googleapis.com/oauth2/v1/userinfo?alt=json"
    }

    override suspend fun prepareAuthenticatedProvider(provider: ModelProvider): ModelProvider =
        wire(provider, ensureToken(provider))

    private fun isBuiltInRoute(provider: ModelProvider): Boolean =
        provider.baseUrl.trimEnd('/') in setOf(ModelApiType.ANTIGRAVITY.defaultBaseUrl, LEGACY_DEFAULT_BASE_URL)

    private fun bases(provider: ModelProvider): List<String> =
        if (isBuiltInRoute(provider)) listOf(ModelApiType.ANTIGRAVITY.defaultBaseUrl)
        else listOf(provider.baseUrl.trimEnd('/'))

    private fun projectValue(value: JsonElement?): String? = when (value) {
        is JsonPrimitive -> value.contentOrNull?.trim()?.takeIf(String::isNotBlank)
        is JsonObject -> value.text("id").trim().takeIf(String::isNotBlank)
        else -> null
    }

    private fun extractProject(data: JsonObject?): String? {
        if (data == null) return null
        return listOf("cloudaicompanionProject", "projectId", "project")
            .firstNotNullOfOrNull { key -> projectValue(data[key]) }
    }

    private suspend fun ensureProject(provider: ModelProvider): AccountTokenState = projectLock.withLock {
        var token = ensureToken(provider)
        // Legacy builds could provision/cache a project against prod or sandbox. Consumer OAuth
        // now has one authoritative route, so an automatic project cached against another endpoint
        // cannot bypass discovery on daily.
        if (isBuiltInRoute(provider) && token.codeAssistBaseUrl != ModelApiType.ANTIGRAVITY.defaultBaseUrl) {
            token = token.copy(projectId = null, codeAssistBaseUrl = null)
        }
        val configuredProject = provider.config.antigravityProjectId(provider.oauthCredentialId)
        if (configuredProject != null) return@withLock token.copy(projectId = configuredProject)
        if (!token.projectId.isNullOrBlank()) return@withLock token
        val authenticated = wire(provider, token)
        val onboarding = authenticated.copy(config = authenticated.config.copy(
            headers = authenticated.config.headers + mapOf(
                "Accept" to "*/*",
                "User-Agent" to "${antigravityUserAgent(provider.config)} $ANTIGRAVITY_ONBOARD_NODE_USER_AGENT",
                "X-Goog-Api-Client" to ANTIGRAVITY_ONBOARD_API_CLIENT
            )
        ))
        var lastFailure: Exception? = null
        for (base in bases(provider)) {
            try {
                val loaded = http.post(
                    "$base/v1internal:loadCodeAssist",
                    obj("metadata" to loadCodeAssistMetadata),
                    authenticated
                )
                var projectId = extractProject(loaded)
                if (projectId == null) {
                    val body = obj("tier_id" to str(antigravityTierId(loaded)), "metadata" to obj(
                        "ide_type" to str("ANTIGRAVITY"),
                        "ide_version" to str(provider.config.effectiveAntigravityVersion()),
                        "ide_name" to str("antigravity")
                    ))
                    var operation = http.post("$base/v1internal:onboardUser", body, onboarding)
                    for (attempt in 0 until 5) {
                        if ((operation["done"] as? JsonPrimitive)?.booleanOrNull == true) {
                            projectId = extractProject(operation["response"] as? JsonObject)
                            break
                        }
                        if (attempt == 4) break
                        delay(2000)
                        // The reference contract polls by repeating onboardUser; do not infer
                        // a publicly routable GET resource from the returned operation name.
                        operation = http.post("$base/v1internal:onboardUser", body, onboarding)
                    }
                }
                if (!projectId.isNullOrBlank()) {
                    token = token.copy(projectId = projectId, codeAssistBaseUrl = base)
                    withContext(Dispatchers.IO) { store.save(provider.oauthCredentialId, token) }
                    return@withLock token
                }
                lastFailure = IllegalStateException("Code Assist did not return a provisioned project")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastFailure = e
            }
        }
        error("Could not resolve the Antigravity project for this account: ${lastFailure?.message.orEmpty()}")
    }

    override suspend fun prepareModelProvider(provider: ModelProvider, modelName: String): ModelProvider =
        wire(provider, ensureProject(provider))

    override suspend fun fetchModels(provider: ModelProvider): List<String> = fetchModelCatalog(provider).map { it.id }

    override suspend fun fetchModelCatalog(provider: ModelProvider): List<DiscoveredModel> {
        val token = ensureProject(provider)
        val authenticated = wire(provider, token)
        val candidates =
            (listOfNotNull(token.codeAssistBaseUrl?.takeIf { it in bases(provider) }) + bases(provider)).distinct()
        var lastFailure: Exception? = null
        for (base in candidates) {
            try {
                val response = http.post(
                    "$base/v1internal:fetchAvailableModels",
                    obj("project" to str(token.projectId!!)),
                    authenticated
                )
                val models =
                    response["models"] as? JsonObject ?: error("Antigravity returned an unsupported model catalog")
                val catalog = models.mapNotNull { (id, value) ->
                    val item = value as? JsonObject ?: return@mapNotNull null
                    if (id.isBlank()) null else discoveredModel(id, item)
                }
                // Keep the generation route on the endpoint that returned this account's catalog.
                if (token.codeAssistBaseUrl != base) projectLock.withLock {
                    val current = withContext(Dispatchers.IO) { store.load(provider.oauthCredentialId) }
                    val hasConfiguredProject = provider.config.antigravityProjectId(provider.oauthCredentialId) != null
                    if (current != null && (hasConfiguredProject || current.projectId == token.projectId)) {
                        withContext(Dispatchers.IO) {
                            store.save(provider.oauthCredentialId, current.copy(codeAssistBaseUrl = base))
                        }
                    }
                }
                return catalog
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastFailure = e
            }
        }
        error("Could not load the Antigravity model catalog for this account/project: ${lastFailure?.message.orEmpty()}")
    }

    override suspend fun testConnection(provider: ModelProvider): Result<String> = result {
        ensureToken(provider)
        "Antigravity authorization is ready."
    }

    override fun shouldStreamUpstream(
        sourceProvider: ModelProvider,
        wireProvider: ModelProvider,
        modelName: String,
        callerStream: Boolean
    ): Boolean = callerStream || modelName.contains("claude", ignoreCase = true) ||
        modelName.contains("gemini-3-pro") || modelName.contains("gemini-3.1-flash-image")

    override fun normalizeToolRequest(
        sourceProvider: ModelProvider, wireProvider: ModelProvider,
        body: JsonObject, systemPrompt: String
    ): JsonObject {
        val project = sourceProvider.config.antigravityProjectId(sourceProvider.oauthCredentialId)
            ?: store.load(sourceProvider.oauthCredentialId)?.projectId ?: error("Antigravity project is missing")
        val model = body.text("model")
        require(model.isNotBlank()) { "Antigravity request needs a model" }
        val request = body.toMutableMap().apply { remove("model") }
        val contents = request["contents"] as? JsonArray
        if (contents != null) request["contents"] = JsonArray(contents.map { content ->
            val message = content.jsonObject
            val parts = (message["parts"] as? JsonArray).orEmpty().map { part ->
                val value = part.jsonObject
                if (value["functionCall"] != null && value["thoughtSignature"] == null)
                    JsonObject(value + ("thoughtSignature" to str("skip_thought_signature_validator"))) else value
            }
            JsonObject(message + ("parts" to JsonArray(parts)))
        })
        val config = (request["generationConfig"] as? JsonObject) ?: obj()
        val modelConfig = sourceProvider.config.modelConfigs[model]
        if (model.contains("claude") && modelConfig?.supportsThinking != false &&
            modelConfig?.requestReasoningEffort != null
        ) {
            val maxTokens = (config["maxOutputTokens"] as? JsonPrimitive)?.intOrNull ?: sourceProvider.config.maxTokens
            if (maxTokens > 1024) {
                val thinkingConfig = if (modelConfig.reasoningEffortDisabled) obj(
                    "includeThoughts" to JsonPrimitive(false), "thinkingBudget" to JsonPrimitive(0)
                ) else obj(
                    "includeThoughts" to JsonPrimitive(true),
                    "thinkingBudget" to JsonPrimitive(modelConfig.reasoningBudget(maxTokens))
                )
                request["generationConfig"] = JsonObject(config + ("thinkingConfig" to thinkingConfig))
            }
        }
        // The catalog's model field may be an internal protobuf enum, not a generation route.
        val resolvedModel = (modelConfig?.modelJson?.get("model") as? JsonPrimitive)?.contentOrNull
            ?.takeIf { it.isNotBlank() && !it.startsWith("MODEL_PLACEHOLDER_") }
            ?: if (modelConfig?.modelJson?.isNotEmpty() == true) model
        else AntigravityModels.resolve(model, modelConfig?.wireReasoningEffort)
        val normalizedRequest = normalizeAntigravityRequest(
            JsonObject(request), resolvedModel, sourceProvider.oauthCredentialId)
        val imageModel = resolvedModel.contains("image")
        return obj(
            "project" to str(project), "model" to str(resolvedModel),
            "requestId" to str(if (imageModel) "image_gen/${System.currentTimeMillis()}/${UUID.randomUUID()}/12"
            else "agent-${UUID.randomUUID()}"),
            "userAgent" to str("antigravity"), "requestType" to str(if (imageModel) "image_gen" else "agent"),
            "request" to normalizedRequest
        )
    }

    override fun restoreToolResponse(sourceProvider: ModelProvider, wireProvider: ModelProvider,
        requestBody: JsonObject, responseBody: JsonObject): JsonObject =
        restoreAntigravityToolArguments(requestBody, responseBody)

    override fun requestUrl(sourceProvider: ModelProvider, wireProvider: ModelProvider, defaultUrl: String) =
        (store.load(sourceProvider.oauthCredentialId)?.codeAssistBaseUrl?.takeIf { it in bases(sourceProvider) }
            ?: bases(sourceProvider).first()) + "/v1internal:generateContent"

    override fun unwrapResponse(response: JsonObject): JsonObject = (response["response"] as? JsonObject) ?: response
}

internal fun antigravityUserAgent(config: ProviderConfiguration): String {
    val version = config.effectiveAntigravityVersion()
    val changelist = config.effectiveAntigravityChangelist()
    val metadata = buildString {
        append("aidev_client; os_type=darwin; arch=arm64")
        changelist?.let { append("; cl=").append(it) }
    }
    return "antigravity/hub/$version ($metadata)"
}

internal fun antigravityTierId(load: JsonObject): String {
    val defaultTier = (load["allowedTiers"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        .firstNotNullOfOrNull { tier ->
            if ((tier["isDefault"] as? JsonPrimitive)?.booleanOrNull == true)
                tier.text("id").trim().takeIf(String::isNotBlank) else null
        }
    if (defaultTier != null) return defaultTier
    return (load["currentTier"] as? JsonObject)?.text("id")?.trim()?.takeIf(String::isNotBlank)
        ?: "free-tier"
}
