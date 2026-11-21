package org.starfall.generative.features.home.chat.logic.remote

import javax.net.ssl.SSLException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.starfall.generative.features.home.chat.model.RemoteConnectionFailure
import org.starfall.generative.features.home.chat.model.RemoteWorkspaceState
import org.starfall.generative.integrations.generativeserver.GenerativeServerCodec
import org.starfall.generative.integrations.generativeserver.GenerativeServerHttpException
import org.starfall.generative.integrations.generativeserver.GenerativeServerProtocolException
import org.starfall.generative.integrations.generativeserver.GenerativeServerRpcException
import org.starfall.generative.integrations.generativeserver.ServerSnapshot
import org.starfall.generative.integrations.generativeserver.SessionListItem
import org.starfall.generative.integrations.opencode.OpenCodeHttpException
import org.starfall.generative.integrations.opencode.OpenCodeProtocolException
import org.starfall.generative.shared.model.ChatRole
import org.starfall.generative.shared.model.Conversation
import org.starfall.generative.shared.model.ConversationListItem
import org.starfall.generative.shared.model.MessageVersion
import org.starfall.generative.shared.model.ToolActivity
import org.starfall.generative.shared.model.provider.ModelProvider

internal fun remoteConnectionFailure(failure: Exception): RemoteConnectionFailure = when (failure) {
    is SSLException -> RemoteConnectionFailure.CERTIFICATE
    is OpenCodeHttpException -> if (failure.status == 401 || failure.status == 403) RemoteConnectionFailure.UNAUTHORIZED
        else RemoteConnectionFailure.UNAVAILABLE
    is OpenCodeProtocolException -> RemoteConnectionFailure.INCOMPATIBLE
    is GenerativeServerHttpException -> if (failure.status == 401 || failure.status == 403) RemoteConnectionFailure.UNAUTHORIZED
        else RemoteConnectionFailure.UNAVAILABLE
    is GenerativeServerRpcException -> when (failure.rpcError.code) {
        "unauthorized", "auth_required" -> RemoteConnectionFailure.UNAUTHORIZED
        "unsupported_feature" -> RemoteConnectionFailure.INCOMPATIBLE
        else -> RemoteConnectionFailure.UNAVAILABLE
    }
    is GenerativeServerProtocolException -> RemoteConnectionFailure.INCOMPATIBLE
    else -> RemoteConnectionFailure.UNAVAILABLE
}

internal fun isConversationLoadFailure(failure: Exception): Boolean = when (failure) {
    is GenerativeServerRpcException -> remoteConnectionFailure(failure) == RemoteConnectionFailure.UNAVAILABLE
    is GenerativeServerProtocolException, is IllegalArgumentException -> true
    else -> false
}

internal fun selectionFor(snapshot: ServerSnapshot, available: List<ModelProvider>): Pair<String, String> {
    val conversation = snapshot.ui()
    val lastModel = conversation.messages.lastOrNull { it.role == ChatRole.MODEL }?.activeVersion
    val provider = conversation.providerId.ifBlank { lastModel?.providerId.orEmpty() }
    val model = conversation.modelId.ifBlank { lastModel?.modelId.orEmpty() }
    return if (available.any { it.id == provider && it.config.modelIds?.contains(model) == true }) provider to model
        else "" to "" // Never silently replace an unavailable saved model with a different model.
}
internal fun publishRunSnapshot(state: RemoteWorkspaceState, snapshot: ServerSnapshot): RemoteWorkspaceState {
    val conversation = snapshot.ui()
    val row = ConversationListItem(
        conversation.id, conversation.title, conversation.createdAt, conversation.updatedAt,
        conversation.tokenCount, conversation.providerId, conversation.modelId, conversation.profileId,
        conversation.reasoningEffort, conversation.messages.isNotEmpty(), conversation.summary != null
    )
    return state.copy(
        snapshot = snapshot, conversation = conversation,
        items = (listOf(row) + state.items.filterNot { it.id == row.id }).sortedByDescending { it.updatedAt },
        revisions = state.revisions + (row.id to snapshot.revision)
    )
}

internal fun setSnapshot(state: RemoteWorkspaceState, snapshot: ServerSnapshot): RemoteWorkspaceState {
    val (provider, model) = selectionFor(snapshot, state.providers)
    // A rotated/expired key cannot be inferred from the transcript.
    val key = state.keyId.takeIf { state.providerId == provider && state.modelId == model }
    return state.copy(snapshot = snapshot, conversation = snapshot.ui(), providerId = provider, modelId = model, keyId = key)
}

internal fun SessionListItem.ui() = ConversationListItem(id, title, createdAt, updatedAt, tokenCount?.toInt(), providerId, modelId, profileId, reasoningEffort, hasMessages, hasSummary)
internal fun ServerSnapshot.ui(): Conversation =
    GenerativeServerCodec.json.decodeFromString<Conversation>(conversation.toString())
        .copy(structuralRevision = revision)

internal fun Conversation.applyRunEvent(kind: String, data: JsonObject): Conversation {
    val message = messages.lastOrNull()?.takeIf { it.role == ChatRole.MODEL } ?: return this
    val index = message.activeVersionIndex
    val version = message.versions.getOrNull(index) ?: return this
    val updated = version.withRunEvent(kind, data)
    val versions = message.versions.toMutableList().apply { this[index] = updated }
    return copy(messages = messages.dropLast(1) + message.copy(versions = versions))
}

private fun MessageVersion.withRunEvent(kind: String, data: JsonObject): MessageVersion = when (kind) {
    "delta" -> copy(content = content + data.getValue("text").jsonPrimitive.content)
    "reasoning_delta" -> copy(
        reasoningContent = reasoningContent.orEmpty() + data.getValue("text").jsonPrimitive.content,
        reasoningContentOffset = data["content_offset"]?.jsonPrimitive?.intOrNull ?: reasoningContentOffset
    )
    "tool_activity" -> {
        val activity = GenerativeServerCodec.json.decodeFromString<ToolActivity>(data.toString())
        val index = toolActivity.indexOfFirst { it.id == activity.id }
        copy(toolActivity = if (index < 0) toolActivity + activity else toolActivity.toMutableList().apply {
            this[index] = activity
        })
    }
    else -> this
}
