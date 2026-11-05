package org.starfall.generative.features.home.chat.logic.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import org.starfall.generative.features.home.chat.model.ContextWindowStatus
import org.starfall.generative.features.home.chat.logic.contextWindowStatus as calculateContextWindowStatus
import org.starfall.generative.shared.model.Conversation
import org.starfall.generative.shared.model.StoredMessage
import org.starfall.generative.shared.model.SystemToolConfig
import org.starfall.generative.shared.model.ToolSettings
import org.starfall.generative.shared.model.language.DEFAULT_CONTEXT_WINDOW_TOKENS
import org.starfall.generative.shared.model.language.ModelConfiguration
import org.starfall.generative.shared.model.language.ModelType
import org.starfall.generative.shared.model.language.withConversationReasoning
import org.starfall.generative.shared.model.provider.ModelProvider
import org.starfall.generative.database.preferences.AppPreferences

internal data class ChatContextEstimate(
    val conversation: Conversation?,
    val preferences: AppPreferences,
    val model: ModelConfiguration?,
    val status: ContextWindowStatus?,
    val providerId: String,
    val modelId: String,
    val toolSettings: ToolSettings,
)

/** Resolves the active chat model and derives context-window state without mixing it into actions. */
internal class ChatModelContextCoordinator(
    scope: CoroutineScope,
    private val currentConversation: StateFlow<Conversation?>,
    private val appPreferences: StateFlow<AppPreferences>,
    private val providers: StateFlow<List<ModelProvider>>,
    private val toolSettings: StateFlow<ToolSettings>,
) {
    val contextEstimate: StateFlow<ChatContextEstimate?> = combine(
        currentConversation,
        appPreferences,
        providers,
        toolSettings
    ) { conversation, preferences, availableProviders, currentToolSettings ->
        val conversationProvider = conversation?.takeIf {
            preferences.persistSelectedModel && it.messages.isNotEmpty()
        }?.let { selected ->
            availableProviders.firstOrNull { provider ->
                provider.id == selected.providerId &&
                    provider.config.modelIds?.contains(selected.modelId) != false &&
                    (provider.config.modelConfigs[selected.modelId]?.modelType
                        ?: ModelType.TEXT_GENERATION) == ModelType.TEXT_GENERATION
            }
        }
        val provider = conversationProvider ?: availableProviders.find {
            it.id == preferences.selectedModel.providerId
        }
        val modelId = conversationProvider?.let { conversation.modelId }
            ?: preferences.selectedModel.modelId
        val config = provider?.config?.modelConfigs?.get(modelId)
            ?: provider?.takeIf { modelId.isNotBlank() }?.let {
                // Discovered/custom models may not have a persisted configuration yet.
                ModelConfiguration(displayName = modelId)
            }
        ChatContextEstimate(
            conversation = conversation,
            preferences = preferences,
            model = config,
            status = if (conversation == null || config == null || config.modelType != ModelType.TEXT_GENERATION) {
                null
            } else {
                calculateContextWindowStatus(
                    conversation,
                    preferences.systemPromptFor(conversation),
                    config,
                    preferences.instructionRoleMessagesFor(conversation),
                    provider?.id.orEmpty(),
                    modelId,
                    currentToolSettings
                )
            },
            providerId = provider?.id.orEmpty(),
            modelId = modelId,
            toolSettings = currentToolSettings
        )
    }.flowOn(Dispatchers.Default).stateIn(scope, SharingStarted.Eagerly, null)

    val contextWindowStatus: StateFlow<ContextWindowStatus?> = contextEstimate.map { it?.status }
        .stateIn(scope, SharingStarted.Eagerly, null)

    fun statusFor(
        conversation: Conversation,
        preferences: AppPreferences,
        model: ModelConfiguration,
        incoming: StoredMessage? = null,
    ): ContextWindowStatus? {
        return contextEstimate.value?.takeIf {
            it.conversation === conversation && it.preferences == preferences && it.model == model &&
                it.providerId == preferences.selectedModel.providerId && it.modelId == preferences.selectedModel.modelId &&
                it.toolSettings == toolSettings.value
        }?.status ?: if (incoming != null) {
            calculateContextWindowStatus(
                conversation,
                preferences.systemPromptFor(conversation),
                model,
                preferences.instructionRoleMessagesFor(conversation),
                preferences.selectedModel.providerId,
                preferences.selectedModel.modelId,
                toolSettings.value
            )
        } else {
            null
        }
    }

    fun providerWithReasoning(
        provider: ModelProvider,
        modelId: String,
        conversation: Conversation,
    ): ModelProvider {
        val effort = conversation.reasoningEffort?.takeIf { it.isNotBlank() } ?: return provider
        val config = provider.config.modelConfigs[modelId] ?: return provider
        if (!config.supportsThinking) return provider
        return provider.copy(config = provider.config.copy(
            modelConfigs = provider.config.modelConfigs +
                (modelId to config.withConversationReasoning(effort))
        ))
    }

    fun conversationModelProvider(conversation: Conversation): ModelProvider? {
        if (conversation.modelId.isBlank()) return null
        return providers.value.firstOrNull { provider ->
            provider.id == conversation.providerId &&
                provider.config.modelIds?.contains(conversation.modelId) != false &&
                (provider.config.modelConfigs[conversation.modelId]?.modelType
                    ?: ModelType.TEXT_GENERATION) == ModelType.TEXT_GENERATION
        }
    }

    /** Resolve the model presented by the composer for this conversation. */
    fun selectedChatModel(
        conversation: Conversation?,
        preferences: AppPreferences,
    ): Pair<ModelProvider?, String> {
        val conversationProvider = conversation?.takeIf {
            preferences.persistSelectedModel && it.messages.isNotEmpty()
        }?.let(::conversationModelProvider)
        val provider = conversationProvider
            ?: providers.value.find { it.id == preferences.selectedModel.providerId }
        val modelId = if (conversationProvider != null) conversation.modelId else preferences.selectedModel.modelId
        return provider to modelId
    }

    fun configuredTextModel(name: String): Pair<ModelProvider, SystemToolConfig>? {
        val config = toolSettings.value.system[name] ?: return null
        val provider = providers.value.find { it.id == config.providerId } ?: return null
        val model = provider.config.modelConfigs[config.modelId] ?: return null
        if (model.modelType != ModelType.TEXT_GENERATION) return null
        return provider to config
    }

    fun summaryTextModel(): Pair<ModelProvider, SystemToolConfig>? {
        configuredTextModel("chat_summary")?.let { return it }
        val preferences = appPreferences.value
        val provider = providers.value.find { it.id == preferences.selectedModel.providerId } ?: return null
        val model = provider.config.modelConfigs[preferences.selectedModel.modelId] ?: return null
        if (model.modelType != ModelType.TEXT_GENERATION) return null
        return provider to SystemToolConfig(providerId = provider.id, modelId = preferences.selectedModel.modelId)
    }

    fun helperContextWindowTokens(helper: Pair<ModelProvider, SystemToolConfig>): Int =
        helper.first.config.modelConfigs[helper.second.modelId]?.contextWindowTokens?.coerceAtLeast(1)
            ?: DEFAULT_CONTEXT_WINDOW_TOKENS
}
