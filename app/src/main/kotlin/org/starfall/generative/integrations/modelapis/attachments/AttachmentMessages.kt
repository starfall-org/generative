package org.starfall.generative.integrations.modelapis.attachments

import android.content.Context
import org.starfall.generative.R

internal class AttachmentMessages(private val context: Context? = null) {
    fun missing(name: String) = text(R.string.attachment_prepare_missing, "Attachment is no longer readable: %s", name)
    fun unsupported(name: String) = text(R.string.attachment_prepare_unsupported, "The selected model or API does not support attachment: %s", name)
    fun tooLarge(name: String) = text(R.string.attachment_prepare_too_large, "Attachment exceeds the selected API's transfer limit: %s", name)
    fun processing(name: String) = text(R.string.attachment_prepare_processing_failed, "The server could not finish processing attachment: %s", name)
    private fun text(id: Int, fallback: String, name: String) = context?.getString(id, name) ?: fallback.format(name)
}
