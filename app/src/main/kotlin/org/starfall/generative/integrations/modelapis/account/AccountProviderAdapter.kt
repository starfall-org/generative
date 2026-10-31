package org.starfall.generative.integrations.modelapis.account

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonObject
import org.starfall.generative.shared.model.language.DiscoveredModel
import org.starfall.generative.shared.model.generation.GenerationEvent
import org.starfall.generative.shared.model.provider.ModelProvider
import org.starfall.generative.shared.model.api.ModelApiType
import org.starfall.generative.shared.model.StoredMessage
import org.starfall.generative.shared.model.ToolDefinition
import org.starfall.generative.shared.model.generation.TokenUsage

/**
 * Boundary for account-backed providers that do not use a normal API key.
 *
 * Keep provider-specific OAuth, token storage and wire behavior behind this
 * interface so removing an adapter only requires removing its registry entry
 * and ModelApiType dispatch.
 */
internal interface AccountProviderAdapter {
    val providerType: ModelApiType

    suspend fun authorize(provider: ModelProvider): Result<ModelProvider>
    suspend fun fetchModels(provider: ModelProvider): List<String>
    suspend fun fetchModelCatalog(provider: ModelProvider): List<DiscoveredModel> =
        fetchModels(provider).map { DiscoveredModel(it) }
    suspend fun testConnection(provider: ModelProvider): Result<String>
    suspend fun prepareAuthenticatedProvider(provider: ModelProvider): ModelProvider

    suspend fun prepareModelProvider(provider: ModelProvider, modelName: String): ModelProvider =
        prepareAuthenticatedProvider(provider)

    fun normalizeToolRequest(
        sourceProvider: ModelProvider,
        wireProvider: ModelProvider,
        body: JsonObject,
        systemPrompt: String
    ): JsonObject = body

    /** Restore provider-private wire names before the shared response parser sees them. */
    fun restoreToolResponse(
        sourceProvider: ModelProvider,
        wireProvider: ModelProvider,
        requestBody: JsonObject,
        responseBody: JsonObject
    ): JsonObject = responseBody

    /** Upstream transport can differ from the caller-visible streaming mode. */
    fun shouldStreamUpstream(
        sourceProvider: ModelProvider,
        wireProvider: ModelProvider,
        modelName: String,
        callerStream: Boolean
    ): Boolean = callerStream

    fun streamEvents(
        provider: ModelProvider,
        modelName: String,
        messages: List<StoredMessage>,
        systemPrompt: String,
        maxOutputTokens: Int
    ): Flow<GenerationEvent>

    fun prepareRequestProvider(sourceProvider: ModelProvider, wireProvider: ModelProvider, body: JsonObject): ModelProvider = wireProvider

    fun requestUrl(sourceProvider: ModelProvider, wireProvider: ModelProvider, defaultUrl: String): String = defaultUrl

    fun unwrapResponse(response: JsonObject): JsonObject = response

    /** Non-HTTP/JSON transports (e.g. Devin Connect-RPC) own a complete tool turn. */
    suspend fun requestToolTurn(
        provider: ModelProvider, modelName: String, history: List<JsonObject>,
        tools: List<ToolDefinition>, systemPrompt: String, sendThinking: Boolean,
        onText: suspend (String) -> Unit, onReasoning: suspend (String) -> Unit,
        onUsage: suspend (TokenUsage) -> Unit, sessionKey: String?
    ): JsonObject? = null

    fun clearCredentials(providerId: String)

    fun exportCredentials(): Map<String, JsonObject>

    fun importCredentials(credentials: Map<String, JsonObject>)
}
