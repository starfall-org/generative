package org.starfall.generative.integrations.modelapis.attachments

import org.starfall.generative.shared.model.provider.ModelProvider
import org.starfall.generative.shared.subfeatures.media.logic.ResolvedAttachment

internal data class UploadCapability(val remote: Boolean, val inline: Boolean, val maxBytes: Long, val purpose: String)

internal interface AttachmentUploader {
    fun capability(provider: ModelProvider, file: ResolvedAttachment): UploadCapability
    fun scope(provider: ModelProvider): String
    suspend fun upload(provider: ModelProvider, file: ResolvedAttachment, key: String): RemoteAttachmentRecord
    suspend fun exists(provider: ModelProvider, remote: RemoteAttachmentRecord): Boolean
    fun isUnsupportedEndpoint(error: Exception): Boolean
}
