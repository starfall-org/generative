package org.starfall.generative.integrations.serverside

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** Wire models only: identity and transport policy belong to the connection layer. */
@Serializable
data class RpcRequest(
    val id: String,
    val op: String,
    @SerialName("session_id") val sessionId: String? = null,
    val data: JsonObject? = null,
)

@Serializable
data class RpcError(val code: String, val message: String, val retryable: Boolean = false)

@Serializable
data class RpcReply(val id: String, val ok: Boolean, val data: JsonElement? = null, val error: RpcError? = null)

@Serializable
data class SessionListItem(
    val id: String,
    val title: String = "",
    @SerialName("created_at") val createdAt: Long = 0,
    @SerialName("updated_at") val updatedAt: Long = 0,
    @SerialName("deleted_at") val deletedAt: Long? = null,
    @SerialName("provider_id") val providerId: String = "",
    @SerialName("model_id") val modelId: String = "",
    @SerialName("profile_id") val profileId: String? = null,
    @SerialName("reasoning_effort") val reasoningEffort: String? = null,
    @SerialName("token_count") val tokenCount: Long? = null,
    @SerialName("has_messages") val hasMessages: Boolean = false,
    @SerialName("has_summary") val hasSummary: Boolean = false,
    val revision: Long,
)

@Serializable
data class SessionListPage(val version: Int, val items: List<SessionListItem>, @SerialName("next_cursor") val nextCursor: String)

@Serializable
data class ProviderCatalog(val version: Int, val revision: Long, val providers: List<ServerProvider>)
@Serializable
data class ServerProvider(
    val id: String, val name: String, val type: String,
    @SerialName("custom_model_id") val customModelId: Boolean,
    @SerialName("key_selection_required") val keySelectionRequired: Boolean,
    val keys: List<ServerProviderKey>, val models: List<ServerModel>,
)
@Serializable
data class ServerProviderKey(val id: String, val label: String, val availability: String = "unknown")
@Serializable
data class ServerModel(
    val id: String, val name: String, val availability: String,
    @SerialName("text_generation") val textGeneration: Boolean,
    @SerialName("context_limit") val contextLimit: Int? = null,
    @SerialName("output_limit") val outputLimit: Int? = null,
)

@Serializable
data class SessionEvent(
    @SerialName("session_id") val sessionId: String,
    val seq: Long,
    @SerialName("request_id") val requestId: String = "",
    val kind: String,
    val data: JsonElement? = null,
)

class ServerSideRpcException(val rpcError: RpcError) : Exception(rpcError.message)
class ServerSideProtocolException(message: String) : Exception(message)

object ServerSideCodec {
    val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    fun encode(request: RpcRequest): String {
        require(request.id.isNotBlank() && request.op.isNotBlank())
        return json.encodeToString(request)
    }
    fun decodeReply(payload: String, expectedId: String): RpcReply {
        val reply = try { json.decodeFromString<RpcReply>(payload) } catch (e: Exception) {
            throw ServerSideProtocolException("Invalid RPC reply")
        }
        if (reply.id != expectedId || (reply.ok && reply.error != null) || (!reply.ok && reply.error == null)) {
            throw ServerSideProtocolException("Inconsistent RPC reply")
        }
        return reply
    }
    fun requireSuccess(reply: RpcReply): JsonElement? {
        if (!reply.ok) throw ServerSideRpcException(reply.error ?: throw ServerSideProtocolException("Missing RPC error"))
        return reply.data
    }
}
