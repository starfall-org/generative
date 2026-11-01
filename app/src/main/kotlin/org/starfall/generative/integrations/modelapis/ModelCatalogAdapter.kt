package org.starfall.generative.integrations.modelapis

import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.json.*
import org.starfall.generative.shared.model.*
import org.starfall.generative.shared.model.api.*
import org.starfall.generative.shared.model.auth.*
import org.starfall.generative.shared.model.generation.*
import org.starfall.generative.shared.model.language.*
import org.starfall.generative.shared.model.provider.*
import org.starfall.generative.integrations.modelapis.discoveredModel

internal class ModelCatalogAdapter(private val httpClient: HttpClient, private val json: Json) {
    suspend fun fetch(provider: ModelProvider): List<DiscoveredModel> {
        var base = provider.baseUrl.trim().trimEnd('/')
        for (suffix in listOf("/chat/completions", "/responses", "/messages", "/models", "/chat", "/tags", "/generate")) {
            base = base.removeSuffix(suffix)
        }
        val endpoint = provider.config.customEndpoints["catalog"]?.takeIf { it.isNotBlank() } ?: provider.config.customListModelsUrl?.takeIf { it.isNotBlank() } ?: when (provider.type) {
            ModelApiType.OLLAMA -> base.removeSuffix("/api").removeSuffix("/v1") + "/api/tags"
            ModelApiType.ANTHROPIC -> "$base/models"
            ModelApiType.GOOGLE -> "$base/models"
            ModelApiType.OPENAI, ModelApiType.OPENAI_RESPONSES -> "$base/models"
            else -> error("No model discovery endpoint is registered for ${provider.type.displayName}.")
        }
        val models = linkedMapOf<String, DiscoveredModel>()
        var cursor: String? = null
        val seenCursors = mutableSetOf<String>()
        do {
            val response = httpClient.get(endpoint) {
                val auth = provider.auth
                auth.requestHeaders(provider.type).forEach { (key, value) -> header(key, value) }
                if (auth.method == AuthMethod.QUERY_PARAM && auth.token.isNotBlank()) {
                    parameter(auth.key?.takeIf { it.isNotBlank() } ?: "key", auth.token)
                }
                if (provider.type == ModelApiType.ANTHROPIC) header("anthropic-version", "2023-06-01")
                provider.config.headers.forEach { (name, value) -> headers.remove(name); header(name, value) }
                cursor?.let { parameter(if (provider.type == ModelApiType.GOOGLE) "pageToken" else "after_id", it) }
            }
            val responseText = response.bodyAsText()
            check(response.status.isSuccess()) {
                modelCatalogHttpError(response.status.value, responseText, json)
            }
            val body = json.parseToJsonElement(responseText).jsonObject
            val entries = (body["data"] ?: body["models"]) as? JsonArray
                ?: error("The models endpoint returned an unsupported response.")
            entries.forEach { entry ->
                val item = entry as? JsonObject ?: return@forEach
                val id = (item["id"] ?: item["name"])?.jsonPrimitive?.contentOrNull
                id?.takeIf { it.isNotBlank() }?.let {
                    val modelId = if (provider.type == ModelApiType.GOOGLE) it.removePrefix("models/") else it
                    models[modelId] = discoveredModel(modelId, item)
                }
            }
            cursor = when {
                provider.type == ModelApiType.GOOGLE -> body["nextPageToken"]?.jsonPrimitive?.contentOrNull
                provider.type == ModelApiType.ANTHROPIC && body["has_more"]?.jsonPrimitive?.booleanOrNull == true ->
                    body["last_id"]?.jsonPrimitive?.contentOrNull
                else -> null
            }?.takeIf { it.isNotBlank() }
            cursor?.let { check(seenCursors.add(it)) { "The models endpoint repeated a page." } }
        } while (cursor != null)
        return models.values.toList()
    }
}

/** Show the HTTP code and the actual API error, without masking it with a generic prefix. */
internal fun modelCatalogHttpError(statusCode: Int, responseText: String, json: Json): String {
    val raw = responseText.trim()
    val payload = runCatching { json.parseToJsonElement(raw) }.getOrNull() as? JsonObject
    val error = payload?.get("error")
    val detail = when (error) {
        is JsonObject -> (error["message"] as? JsonPrimitive)?.contentOrNull
            ?: (error["detail"] as? JsonPrimitive)?.contentOrNull
        is JsonPrimitive -> error.contentOrNull
        else -> null
    }?.takeIf { it.isNotBlank() }
        ?: (payload?.get("message") as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
        ?: (payload?.get("detail") as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
        ?: raw.ifBlank { "Unable to load models" }
    return "HTTP $statusCode\n$detail"
}
