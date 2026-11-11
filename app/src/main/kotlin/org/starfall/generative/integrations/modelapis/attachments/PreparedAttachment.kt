package org.starfall.generative.integrations.modelapis.attachments

import org.starfall.generative.shared.subfeatures.media.logic.ResolvedAttachment

/** Request-local only: conversation history always retains the original local reference. */
internal data class PreparedAttachment(
    val metadata: ResolvedAttachment,
    val remote: RemoteAttachmentRecord? = null
)
