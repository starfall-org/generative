package org.starfall.generative.features.home.chat.logic.remote

import org.starfall.generative.features.home.chat.model.RemoteActionFailure
import org.starfall.generative.integrations.generativeserver.GenerativeServerRpcException
import org.starfall.generative.integrations.generativeserver.MobileProviderConfigurationException
import org.starfall.generative.integrations.generativeserver.MobileProviderFailure
import org.starfall.generative.integrations.generativeserver.ProviderCatalog
import org.starfall.generative.integrations.generativeserver.RpcError
import org.starfall.generative.integrations.generativeserver.ServerProviderOptions
import org.starfall.generative.integrations.generativeserver.remoteTextCatalog
import org.starfall.generative.shared.model.api.ModelApiType
import org.starfall.generative.shared.model.language.ModelConfiguration
import org.starfall.generative.shared.model.provider.ModelProvider
import org.starfall.generative.shared.model.provider.ProviderConfiguration

internal fun ProviderCatalog.textProviders(): List<ModelProvider> = providers.mapNotNull { provider ->
    val type = when (provider.type) {
        "openai" -> ModelApiType.OPENAI
        "openai_responses" -> ModelApiType.OPENAI_RESPONSES
        "anthropic" -> ModelApiType.ANTHROPIC
        "google" -> ModelApiType.GOOGLE
        else -> return@mapNotNull null
    }
    val models = provider.models.filter {
        it.textGeneration && (it.availability == "available" || it.availability == "key_selection_required" || it.errorCode != null)
    }
    if (models.isEmpty()) return@mapNotNull null
    ModelProvider(
        id = provider.id, name = provider.name, type = type, baseUrl = "",
        config = ProviderConfiguration(
            modelIds = models.map { it.id },
            modelConfigs = models.associate { model ->
                model.id to ModelConfiguration(
                    displayName = model.name, supportsVision = false, supportsThinking = false, supportsToolCalls = false,
                )
            },
        ),
    )
}

/** Keep rejected text models visible so the server's precise diagnostic is not lost. */
internal fun ProviderCatalog.modelDiagnostics(): Map<Pair<String, String>, String> = buildMap {
    for (provider in providers) for (model in provider.models) {
        val code = model.errorCode ?: continue
        if (model.availability !in setOf("available", "key_selection_required")) {
            put(provider.id to model.id, RpcError(code, "", field = model.errorField).diagnostic
                .ifBlank { "unsupported_provider_configuration" })
        }
    }
}

internal fun combinedProviders(
    serverProviders: List<ModelProvider>,
    localProviders: List<ModelProvider>,
    mobileProviders: Boolean,
    options: ServerProviderOptions = ServerProviderOptions(),
): Pair<List<ModelProvider>, Set<String>> {
    val serverIds = serverProviders.mapTo(hashSetOf()) { it.id }
    val local = if (mobileProviders) localProviders.mapNotNull { provider ->
        provider.remoteTextCatalog(options)?.let { item ->
            val id = "local:" + item.id
            item.copy(id = id, groupId = null).takeIf { id !in serverIds }
        }
    } else emptyList()
    val ordered = if (local.isEmpty()) serverProviders else
        (serverProviders + local).mapIndexed { index, provider -> provider.copy(sortOrder = index) }
    return ordered to local.mapTo(hashSetOf()) { it.id }
}

internal fun localProviderFailure(failure: Exception): RemoteActionFailure =
    when ((failure as? MobileProviderConfigurationException)?.reason) {
        MobileProviderFailure.OAUTH -> RemoteActionFailure.LOCAL_PROVIDER_OAUTH
        MobileProviderFailure.CONNECTION_OPTIONS -> RemoteActionFailure.LOCAL_PROVIDER_CONNECTION_OPTIONS
        MobileProviderFailure.MODEL_OPTIONS -> RemoteActionFailure.LOCAL_PROVIDER_MODEL_OPTIONS
        MobileProviderFailure.CREDENTIALS, null -> RemoteActionFailure.LOCAL_PROVIDER_CREDENTIALS
    }


internal fun remoteProviderFailure(failure: Exception): RemoteActionFailure? {
    val error = (failure as? GenerativeServerRpcException)?.rpcError ?: return null
    if (error.code !in setOf("invalid_provider_configuration", "unsupported_provider_configuration", "unsupported_provider_adapter", "auth_required")) return null
    val field = error.field.orEmpty()
    return when {
        field == "provider.auth.oauthAccountId" -> RemoteActionFailure.LOCAL_PROVIDER_OAUTH
        field.startsWith("provider.auth.") || field == "provider.keyId" -> RemoteActionFailure.LOCAL_PROVIDER_CREDENTIALS
        field in setOf("provider.type", "provider.baseUrl", "provider.config.headers", "provider.config.customEndpoints",
            "provider.config.httpProxy", "provider.config.socksProxy", "provider.config.responsesApi") ||
            field.startsWith("provider.config.anthropicPromptCache") ->
            RemoteActionFailure.LOCAL_PROVIDER_CONNECTION_OPTIONS
        field.startsWith("provider.modelConfig") || field == "provider.config.maxTokens" || field == "provider.modelId" ->
            RemoteActionFailure.LOCAL_PROVIDER_MODEL_OPTIONS
        else -> RemoteActionFailure.LOCAL_PROVIDER_MODEL_OPTIONS
    }
}
