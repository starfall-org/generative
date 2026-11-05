package org.starfall.generative.integrations.modelapis.openaicodex

import java.util.UUID
import kotlinx.serialization.json.JsonObject
import org.starfall.generative.shared.model.ChatRole
import org.starfall.generative.shared.model.StoredMessage
import org.starfall.generative.shared.model.provider.ModelProvider
import org.starfall.generative.shared.model.provider.oauthCredentialId
import org.starfall.generative.shared.helpers.networking.text

/** Request-only metadata used to carry conversation identity through the manual tool pipeline. */
internal const val CODEX_SESSION_SCOPE = "_generative_codex_session"

internal fun codexConversationScope(messages: List<StoredMessage>): String? =
    messages.firstOrNull { it.role == ChatRole.USER }?.id?.takeIf(String::isNotBlank)

/** Explicit caller identity wins; otherwise use a stable account + conversation scoped UUID. */
internal fun codexSessionId(provider: ModelProvider, request: JsonObject? = null): String {
    val configured = provider.config.headers["Session_id"]?.trim().orEmpty()
    if (configured.isNotEmpty()) return configured
    val explicit = request?.text("prompt_cache_key")?.trim().orEmpty()
    if (explicit.isNotEmpty()) return explicit
    val scope = request?.text(CODEX_SESSION_SCOPE)?.trim().orEmpty().ifEmpty { "account" }
    return UUID.nameUUIDFromBytes("generative:codex\n${provider.oauthCredentialId}\n$scope"
        .toByteArray(Charsets.UTF_8)).toString()
}
