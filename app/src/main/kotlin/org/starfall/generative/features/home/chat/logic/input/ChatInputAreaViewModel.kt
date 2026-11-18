package org.starfall.generative.features.home.chat.logic.input

import org.starfall.generative.shared.model.language.ModelConfiguration
import org.starfall.generative.shared.model.language.ModelType
import org.starfall.generative.shared.model.provider.ModelProvider

data class DirectMediaRequest(
    val prompt: String,
    val kind: ModelType,
    val providerId: String,
    val modelId: String,
    val attachments: List<String> = emptyList()
)

data class ChatInputEditDraft(
    val messageId: String,
    val text: String,
    val attachments: List<String>,
    val isQueued: Boolean = false,
    val revision: Long
)

internal const val MAX_CLIPBOARD_IMAGE_BYTES = 20L * 1024L * 1024L

/** Builds the provider/model list eligible for direct image or video generation. */
internal fun filterMediaProvidersForKind(
    providers: List<ModelProvider>,
    mediaKind: ModelType?
): List<ModelProvider> = providers.mapNotNull { provider ->
    val models = provider.config.modelConfigs.filter { (id, config) ->
        config.modelType == mediaKind && provider.config.modelIds?.contains(id) != false
    }
    if (models.isEmpty()) null else provider.copy(config = provider.config.copy(
        modelIds = models.keys.toList(), modelConfigs = models
    ))
}

/** Keeps ordinary chat submission permissive while validating direct-media prompts. */
internal fun canSendChatInput(
    text: String,
    attachments: List<String>,
    mediaKind: ModelType?,
    isGenerating: Boolean,
    hasMediaModel: Boolean
): Boolean {
    val hasContent = text.isNotBlank() || attachments.isNotEmpty()
    return if (mediaKind == null) hasContent
    else text.isNotBlank() && text.length <= 32000 &&
        attachments.size <= (if (mediaKind == ModelType.VIDEO_GENERATION) 1 else 16) &&
        hasMediaModel && !isGenerating
}

/** Describes the first direct-media constraint that currently needs attention. */
internal fun directMediaHint(
    text: String,
    attachments: List<String>,
    mediaKind: ModelType?,
    mediaModel: ModelConfiguration?,
    mediaModelId: String,
    mediaLabel: String
): String = when {
    mediaKind == ModelType.VIDEO_GENERATION && attachments.size > 1 -> "Choose one reference image or video for the video"
    mediaKind == ModelType.IMAGE_GENERATION && attachments.size > 16 -> "Choose up to 16 reference images"
    text.length > 32000 -> "Prompt exceeds 32,000 characters"
    mediaModel == null -> "Choose a $mediaLabel model"
    else -> "Direct to ${mediaModel.displayName.ifBlank { mediaModelId }}"
}
