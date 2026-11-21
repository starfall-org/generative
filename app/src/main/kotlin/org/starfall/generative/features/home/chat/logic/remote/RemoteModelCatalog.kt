package org.starfall.generative.features.home.chat.logic.remote

import org.starfall.generative.features.home.chat.model.RemoteActionFailure
import org.starfall.generative.integrations.generativeserver.MobileProviderConfigurationException
import org.starfall.generative.integrations.generativeserver.MobileProviderFailure
import org.starfall.generative.integrations.generativeserver.ProviderCatalog
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
        it.textGeneration && (it.availability == "available" || it.availability == "key_selection_required")
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

internal fun combinedProviders(
    serverProviders: List<ModelProvider>,
    localProviders: List<ModelProvider>,
    mobileProviders: Boolean,
): Pair<List<ModelProvider>, Set<String>> {
    val serverIds = serverProviders.mapTo(hashSetOf()) { it.id }
    val local = if (mobileProviders) localProviders.mapNotNull { provider ->
        provider.remoteTextCatalog()?.let { item ->
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
