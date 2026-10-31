package org.starfall.generative.integrations.modelapis.geminicli

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import org.starfall.generative.integrations.modelapis.account.*
import org.starfall.generative.shared.files.AttachmentResolver
import org.starfall.generative.shared.model.api.ModelApiType
import org.starfall.generative.shared.model.auth.AuthMethod
import org.starfall.generative.shared.model.auth.Authorization
import org.starfall.generative.shared.model.language.DiscoveredModel
import org.starfall.generative.shared.model.provider.ModelProvider
import org.starfall.generative.shared.model.provider.oauthCredentialId
import org.starfall.generative.shared.networking.obj
import org.starfall.generative.shared.networking.str
import org.starfall.generative.shared.networking.text

/** Independent Google Gemini CLI account: Google OAuth -> Cloud Code Assist -> native Gemini JSON. */
internal class GeminiCliAdapter(context: Context, attachments: AttachmentResolver) : OAuthAccountAdapter(
    context, attachments, ModelApiType.GEMINI_CLI,
    GeminiCliOAuthProtocol.CLIENT_ID, GeminiCliOAuthProtocol.AUTH_URL, GeminiCliOAuthProtocol.TOKEN_URL,
    GeminiCliOAuthProtocol.SCOPE, GeminiCliOAuthProtocol.CALLBACK_PORT,
    GeminiCliOAuthProtocol.CALLBACK_PATH, GeminiCliOAuthProtocol.CLIENT_SECRET, callbackHost = "127.0.0.1"
) {
    override val useOfficialSdkForGeneration: Boolean = false
    private val projectLock = Mutex()
    private val metadata = obj(
        "ideType" to str("IDE_UNSPECIFIED"),
        "platform" to str("PLATFORM_UNSPECIFIED"),
        "pluginType" to str("GEMINI")
    )

    override fun authorizationAdditionalParameters(): Map<String, String> = GeminiCliOAuthProtocol.ADDITIONAL_PARAMETERS

    override suspend fun authorize(provider: ModelProvider): Result<ModelProvider> {
        val result = super.authorize(provider)
        val signedIn = result.getOrNull() ?: return result
        val existingChoice = signedIn.config.geminiCliProjectIds[signedIn.oauthCredentialId]
            ?.takeIf(String::isNotBlank)
        val discovered = store.load(signedIn.oauthCredentialId)?.projectId?.takeIf(String::isNotBlank)
        val selected = existingChoice ?: discovered ?: return result
        return Result.success(signedIn.copy(config = signedIn.config.copy(
            geminiCliProjectIds = signedIn.config.geminiCliProjectIds +
                (signedIn.oauthCredentialId to selected)
        )))
    }

    override suspend fun enrichToken(token: AccountTokenState): AccountTokenState {
        require(token.refreshToken.isNotBlank()) {
            "Google did not issue a Gemini CLI refresh token. Sign in again with offline access."
        }
        val email = try {
            http.json(http.request("https://www.googleapis.com/oauth2/v1/userinfo?alt=json",
                extraHeaders = mapOf("Authorization" to "Bearer ${token.accessToken}"))
                .get().build()).text("email")
        } catch (e: CancellationException) { throw e } catch (_: Exception) { "" }
        val resolvedEmail = email.takeIf(String::isNotBlank) ?: token.email
        return token.copy(email = resolvedEmail).let { current ->
            // Google project discovery can be unavailable for consumer accounts; the UI permits
            // manual selection and the first authenticated model request will retry onboarding.
            try { current.copy(projectId = discoverProject(current.accessToken, null)) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { current }
        }
    }

    private suspend fun cloudPost(path: String, token: String, body: JsonObject): JsonObject =
        http.post("$CLOUD_ROOT/v1internal:$path", body,
            ModelProvider("temporary", "Gemini CLI", ModelApiType.GOOGLE,
                Authorization(AuthMethod.BEARER_TOKEN, value = token), baseUrl = CLOUD_ROOT,
                config = org.starfall.generative.shared.model.provider.ProviderConfiguration(headers = mapOf(
                    "User-Agent" to fingerprint("unknown"), "X-Goog-Api-Client" to API_CLIENT
                ))))

    private fun projectValue(value: JsonElement?): String? = when (value) {
        is JsonPrimitive -> value.contentOrNull?.trim()?.takeIf(String::isNotBlank)
        is JsonObject -> value.text("id").trim().takeIf(String::isNotBlank)
        else -> null
    }

    private suspend fun discoverCloudProjects(accessToken: String): List<String> {
        val response = http.json(http.request("https://cloudresourcemanager.googleapis.com/v1/projects",
            extraHeaders = mapOf("Authorization" to "Bearer $accessToken")).get().build())
        return (response["projects"] as? JsonArray).orEmpty()
            .mapNotNull { (it as? JsonObject)?.text("projectId")?.takeIf(String::isNotBlank) }
            .distinct()
    }

    private suspend fun discoverProject(accessToken: String, preferred: String?): String {
        val requestedProject = preferred?.takeIf(String::isNotBlank) ?: try {
            discoverCloudProjects(accessToken).firstOrNull()
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { null }
        val load = cloudPost("loadCodeAssist", accessToken, buildJsonObject {
            put("metadata", metadata)
            requestedProject?.let { put("cloudaicompanionProject", it) }
        })
        var project = requestedProject ?: projectValue(load["cloudaicompanionProject"])
        val tier = (load["allowedTiers"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
            .firstOrNull { (it["isDefault"] as? JsonPrimitive)?.booleanOrNull == true }
            ?.text("id")?.takeIf(String::isNotBlank) ?: "legacy-tier"
        if (project == null) {
            for (attempt in 0 until 15) {
                val onboarding = cloudPost("onboardUser", accessToken,
                    buildJsonObject { put("tierId", tier); put("metadata", metadata) })
                if ((onboarding["done"] as? JsonPrimitive)?.booleanOrNull == true) {
                    project = projectValue((onboarding["response"] as? JsonObject)?.get("cloudaicompanionProject"))
                    break
                }
                delay(2_000)
            }
        }
        val projectId = project?.takeIf(String::isNotBlank)
            ?: error("Gemini CLI needs a Google Cloud project. Enter Project ID in Provider settings.")
        for (attempt in 0 until 15) {
            val onboarding = cloudPost("onboardUser", accessToken, buildJsonObject {
                put("tierId", tier); put("metadata", metadata); put("cloudaicompanionProject", projectId)
            })
            if ((onboarding["done"] as? JsonPrimitive)?.booleanOrNull == true) {
                return projectValue((onboarding["response"] as? JsonObject)?.get("cloudaicompanionProject")) ?: projectId
            }
            delay(2_000)
        }
        error("Gemini CLI Code Assist onboarding timed out.")
    }

    private suspend fun project(provider: ModelProvider): Pair<AccountTokenState, String> = projectLock.withLock {
        val token = ensureToken(provider)
        val override = provider.config.geminiCliProjectIds[provider.oauthCredentialId]?.trim()?.takeIf(String::isNotEmpty)
        if (override != null) return@withLock token to override
        token.projectId?.takeIf(String::isNotBlank)?.let { return@withLock token to it }
        val identified = discoverProject(token.accessToken, null)
        val updated = token.copy(projectId = identified)
        store.save(provider.oauthCredentialId, updated)
        updated to identified
    }

    private fun wire(provider: ModelProvider, token: AccountTokenState) = provider.copy(
        type = ModelApiType.GOOGLE,
        baseUrl = ModelApiType.GEMINI_CLI.defaultBaseUrl,
        auth = Authorization(AuthMethod.CUSTOM_HEADER, "Authorization", "Bearer ${token.accessToken}"),
        config = provider.config.copy(headers = provider.config.headers + mapOf(
            "User-Agent" to fingerprint("unknown"),
            "X-Goog-Api-Client" to API_CLIENT
        ))
    )

    override suspend fun prepareAuthenticatedProvider(provider: ModelProvider): ModelProvider = wire(provider, ensureToken(provider))
    override suspend fun prepareModelProvider(provider: ModelProvider, modelName: String): ModelProvider {
        val (token, projectId) = project(provider)
        return wire(provider, token).copy(config = provider.config.copy(
            headers = provider.config.headers + mapOf("User-Agent" to fingerprint(modelName),
                "X-Goog-Api-Client" to API_CLIENT),
            geminiCliProjectIds = provider.config.geminiCliProjectIds + (provider.oauthCredentialId to projectId)
        ))
    }

    override fun requestUrl(sourceProvider: ModelProvider, wireProvider: ModelProvider, defaultUrl: String): String =
        "$CLOUD_ROOT/v1internal:generateContent"

    override fun normalizeToolRequest(sourceProvider: ModelProvider, wireProvider: ModelProvider,
        body: JsonObject, systemPrompt: String): JsonObject {
        // Project resolution has already completed in prepareModelProvider. Callers build the
        // request synchronously after that suspension point, so the project comes from the saved
        // token or selected account's explicit override.
        val project = wireProvider.config.geminiCliProjectIds[sourceProvider.oauthCredentialId]
            ?: error("Gemini CLI project is not resolved.")
        return geminiCliEnvelope(project, body)
    }

    override fun unwrapResponse(response: JsonObject): JsonObject = (response["response"] as? JsonObject) ?: response

    override suspend fun fetchModels(provider: ModelProvider): List<String> = fetchModelCatalog(provider).map { it.id }
    override suspend fun fetchModelCatalog(provider: ModelProvider): List<DiscoveredModel> {
        // CLIProxyAPI ModelsForAuth is static, independent of Code Assist project onboarding.
        // Validate that this is an authenticated account without forcing network onboarding.
        ensureToken(provider)
        return GeminiCliModelCatalog.models()
    }

    override suspend fun testConnection(provider: ModelProvider): Result<String> = result {
        val (_, projectId) = project(provider)
        "Gemini CLI OAuth ready (project: $projectId). Model access depends on Google account entitlements."
    }

    override fun prepareRequestProvider(sourceProvider: ModelProvider, wireProvider: ModelProvider, body: JsonObject): ModelProvider = wireProvider

    private fun fingerprint(model: String) = "GeminiCLI/0.34.0/$model (linux; x64; terminal)"

    companion object {
        private const val CLOUD_ROOT = "https://cloudcode-pa.googleapis.com"
        private const val API_CLIENT = "google-genai-sdk/1.41.0 gl-node/v22.19.0"
        val MODEL_IDS = GeminiCliModelCatalog.modelIds
    }
}

/** Cloud Code requires project/model at the outer level; the model request lives under request. */
internal fun geminiCliEnvelope(project: String, body: JsonObject): JsonObject {
    require(project.isNotBlank()) { "Gemini CLI Project ID is missing" }
    require(body.text("model").isNotBlank()) { "Gemini CLI Model ID is missing" }
    val request = JsonObject(body - setOf("model", "stream", "project"))
    return obj("project" to str(project), "model" to str(body.text("model")), "request" to request)
}
