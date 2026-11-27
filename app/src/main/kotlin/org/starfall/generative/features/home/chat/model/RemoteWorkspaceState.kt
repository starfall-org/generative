package org.starfall.generative.features.home.chat.model

import org.starfall.generative.integrations.generativeserver.ServerChatCapabilities
import org.starfall.generative.integrations.generativeserver.ServerProviderKey
import org.starfall.generative.integrations.generativeserver.ServerSnapshot
import org.starfall.generative.shared.model.Conversation
import org.starfall.generative.shared.model.ConversationListItem
import org.starfall.generative.shared.model.provider.ModelProvider

/** A recoverable remote mutation error must not disable the entire remote workspace. */
enum class RemoteActionFailure {
    REVISION_CONFLICT, OPERATION_FAILED, GENERATION_FAILED, CONVERSATION_LOAD_FAILED, CONVERSATION_CREATE_FAILED,
    LOCAL_PROVIDER_OAUTH, LOCAL_PROVIDER_CONNECTION_OPTIONS, LOCAL_PROVIDER_MODEL_OPTIONS, LOCAL_PROVIDER_CREDENTIALS,
}
enum class RemoteConnectionFailure { UNAUTHORIZED, CERTIFICATE, INCOMPATIBLE, UNAVAILABLE, CHAT_LOAD_FAILED }

/** Remote state never writes to the local conversation repository. */
data class RemoteWorkspaceState(
    val enabled: Boolean = false, val restoring: Boolean = false, val connectionId: String? = null,
    val items: List<ConversationListItem> = emptyList(), val revisions: Map<String, Long> = emptyMap(), val snapshot: ServerSnapshot? = null,
    val conversation: Conversation? = null, val providers: List<ModelProvider> = emptyList(),
    val providerId: String = "", val modelId: String = "", val keyId: String? = null,
    val pendingKeys: List<ServerProviderKey> = emptyList(), val busy: Boolean = false,
    val connecting: Boolean = false, val loadingConversationId: String? = null,
    val running: Boolean = false, val error: Boolean = false,
    val actionFailure: RemoteActionFailure? = null,
    val connectionFailure: RemoteConnectionFailure? = null,
    val capabilities: ServerChatCapabilities = ServerChatCapabilities(),
    val localProviderIds: Set<String> = emptySet(),
    val openCode: Boolean = false,
    val openCodeDirectory: String? = null,
    val catalogDiagnostics: Map<Pair<String, String>, String> = emptyMap(),
    val providerDiagnostic: String? = null,
) {
    val selectedCatalogDiagnostic: String? get() = catalogDiagnostics[providerId to modelId]
    val canSubmit: Boolean get() = enabled && !busy && !running && !error && modelId.isNotBlank() && selectedCatalogDiagnostic == null &&
        (conversation != null || actionFailure != RemoteActionFailure.CONVERSATION_LOAD_FAILED)
}
