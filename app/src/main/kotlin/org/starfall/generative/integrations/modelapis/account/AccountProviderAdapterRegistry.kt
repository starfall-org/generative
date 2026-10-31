package org.starfall.generative.integrations.modelapis.account

import android.content.Context
import org.starfall.generative.integrations.modelapis.openaicodex.OpenAICodexAdapter
import org.starfall.generative.integrations.modelapis.claudecode.ClaudeCodeAdapter
import org.starfall.generative.integrations.modelapis.antigravity.AntigravityAdapter
import org.starfall.generative.integrations.modelapis.githubcopilot.GitHubCopilotAdapter
import org.starfall.generative.integrations.modelapis.grokbuild.GrokBuildAdapter
import org.starfall.generative.integrations.modelapis.metamuse.MetaMuseAdapter
import org.starfall.generative.integrations.modelapis.geminicli.GeminiCliAdapter
import org.starfall.generative.integrations.modelapis.devin.DevinAdapter
import org.starfall.generative.shared.model.api.ModelApiType
import org.starfall.generative.shared.files.AttachmentResolver
import kotlinx.serialization.json.JsonObject

/** Single integration point between core services and removable account adapters. */
internal class AccountProviderAdapterRegistry(
    context: Context,
    attachments: AttachmentResolver
) {
    private val adapters: Map<ModelApiType, AccountProviderAdapter> = listOf(
        OpenAICodexAdapter(context, attachments),
        ClaudeCodeAdapter(context, attachments),
        AntigravityAdapter(context, attachments),
        GitHubCopilotAdapter(context, attachments),
        GrokBuildAdapter(context, attachments),
        MetaMuseAdapter(context, attachments),
        GeminiCliAdapter(context, attachments),
        DevinAdapter(context, attachments)
    ).associateBy { it.providerType }

    fun get(type: ModelApiType): AccountProviderAdapter? = adapters[type]

    fun clearCredentials(type: ModelApiType, providerId: String) {
        adapters[type]?.clearCredentials(providerId)
    }

    fun exportCredentials(): Map<String, Map<String, JsonObject>> =
        adapters.mapKeys { (type, _) -> type.name }.mapValues { (_, adapter) -> adapter.exportCredentials() }

    fun importCredentials(credentials: Map<String, Map<String, JsonObject>>) {
        adapters.forEach { (type, adapter) ->
            adapter.importCredentials(credentials[type.name].orEmpty())
        }
    }
}
