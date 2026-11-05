package org.starfall.generative.integrations.modelapis.sdk

import com.openai.models.chat.completions.ChatCompletionContentPart
import com.openai.models.chat.completions.ChatCompletionContentPartImage
import com.openai.models.chat.completions.ChatCompletionContentPartText
import com.openai.models.responses.*
import com.anthropic.models.messages.*
import com.google.genai.Client
import com.google.genai.types.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.runInterruptible
import org.starfall.generative.shared.model.StoredMessage
import org.starfall.generative.shared.subfeatures.media.logic.AttachmentResolver
import org.starfall.generative.shared.subfeatures.media.logic.ResolvedAttachment
import java.util.Base64

internal class SdkAttachmentContent(private val attachments: AttachmentResolver) {
    private data class InlineAttachment(val meta: ResolvedAttachment, val base64: String, val dataUrl: String)

    private fun inlineAttachment(reference: String): InlineAttachment? {
        val meta = attachments.metadata(reference) ?: return null
        val bytes = attachments.readBytes(reference) ?: return null
        val encoded = Base64.getEncoder().encodeToString(bytes)
        return InlineAttachment(meta, encoded, "data:${meta.mimeType};base64,$encoded")
    }

    fun openAiChatContent(message: StoredMessage): List<ChatCompletionContentPart> {
        val parts = mutableListOf<ChatCompletionContentPart>()
        if (message.content.isNotBlank()) parts += ChatCompletionContentPart.ofText(ChatCompletionContentPartText.builder().text(message.content).build())
        message.files.forEach { reference ->
            val data = inlineAttachment(reference) ?: return@forEach
            if (data.meta.mimeType.startsWith("image/")) {
                val imageUrl = ChatCompletionContentPartImage.ImageUrl.builder().url(data.dataUrl).build()
                parts += ChatCompletionContentPart.ofImageUrl(ChatCompletionContentPartImage.builder().imageUrl(imageUrl).build())
            } else {
                val fileObject = ChatCompletionContentPart.File.FileObject.builder().fileData(data.dataUrl).filename(data.meta.name).build()
                parts += ChatCompletionContentPart.ofFile(ChatCompletionContentPart.File.builder().file(fileObject).build())
            }
        }
        if (parts.isEmpty()) parts += ChatCompletionContentPart.ofText(ChatCompletionContentPartText.builder().text("").build())
        return parts
    }

    fun openAiResponseContent(message: StoredMessage): List<ResponseInputContent> {
        val parts = mutableListOf<ResponseInputContent>()
        if (message.content.isNotBlank()) parts += ResponseInputContent.ofInputText(ResponseInputText.builder().text(message.content).build())
        message.files.forEach { reference ->
            val data = inlineAttachment(reference) ?: return@forEach
            if (data.meta.mimeType.startsWith("image/")) {
                parts += ResponseInputContent.ofInputImage(ResponseInputImage.builder().imageUrl(data.dataUrl).build())
            } else {
                parts += ResponseInputContent.ofInputFile(ResponseInputFile.builder().fileData(data.dataUrl).filename(data.meta.name).build())
            }
        }
        if (parts.isEmpty()) parts += ResponseInputContent.ofInputText(ResponseInputText.builder().text("").build())
        return parts
    }

    fun anthropicContent(message: StoredMessage): List<ContentBlockParam> {
        val blocks = mutableListOf<ContentBlockParam>()
        if (message.content.isNotBlank()) blocks += ContentBlockParam.ofText(message.content)
        message.files.forEach { reference ->
            val meta = attachments.metadata(reference) ?: return@forEach
            when {
                meta.mimeType in setOf("image/jpeg", "image/png", "image/gif", "image/webp") -> {
                    val data = inlineAttachment(reference) ?: return@forEach
                    val source = Base64ImageSource.builder().data(data.base64).mediaType(Base64ImageSource.MediaType.of(meta.mimeType)).build()
                    blocks += ContentBlockParam.ofImage(ImageBlockParam.builder().source(source).build())
                }
                meta.mimeType == "application/pdf" -> {
                    val data = inlineAttachment(reference) ?: return@forEach
                    blocks += ContentBlockParam.ofDocument(DocumentBlockParam.builder().source(Base64PdfSource.builder().data(data.base64).build()).title(meta.name).build())
                }
                meta.mimeType.startsWith("text/") || meta.mimeType in setOf("application/json", "application/xml", "application/javascript") -> {
                    val bytes = attachments.readBytes(reference) ?: return@forEach
                    blocks += ContentBlockParam.ofDocument(DocumentBlockParam.builder().textSource(bytes.toString(Charsets.UTF_8)).title(meta.name).build())
                }
                else -> Unit
            }
        }
        if (blocks.isEmpty()) blocks += ContentBlockParam.ofText("")
        return blocks
    }

    suspend fun googleParts(client: Client, message: StoredMessage): List<Part> {
        val parts = mutableListOf<Part>()
        if (message.content.isNotBlank()) parts += Part.fromText(message.content)
        for (reference in message.files) {
            val meta = attachments.metadata(reference) ?: continue
            if (meta.mimeType.startsWith("video/")) {
                val config = UploadFileConfig.builder().mimeType(meta.mimeType).displayName(meta.name).build()
                var uploaded = if (meta.sizeBytes > 0) {
                    val input = attachments.open(reference) ?: continue
                    input.use { sdkCall { client.files.upload(it, meta.sizeBytes, config) } }
                } else {
                    val bytes = attachments.readBytes(reference, 100L * 1024L * 1024L) ?: continue
                    sdkCall { client.files.upload(bytes, config) }
                }
                val fileName = uploaded.name().orElse(null)
                if (fileName != null) {
                    var attempts = 0
                    while (uploaded.state().orElse(null)?.knownEnum() != FileState.Known.ACTIVE && attempts < 120) {
                        if (uploaded.state().orElse(null)?.knownEnum() == FileState.Known.FAILED) error("Google failed to process ${meta.name}")
                        delay(1000)
                        uploaded = sdkCall { client.files.get(fileName, GetFileConfig.builder().build()) }
                        attempts++
                    }
                    check(uploaded.state().orElse(null)?.knownEnum() == FileState.Known.ACTIVE) { "Google timed out processing ${meta.name}" }
                }
                val uri = uploaded.uri().orElseThrow { IllegalStateException("Google did not return a file URI for ${meta.name}") }
                parts += Part.fromUri(uri, meta.mimeType)
            } else {
                val bytes = attachments.readBytes(reference) ?: continue
                parts += Part.fromBytes(bytes, meta.mimeType)
            }
        }
        if (parts.isEmpty()) parts += Part.fromText("")
        return parts
    }

    private suspend fun <T> sdkCall(block: () -> T): T = try { runInterruptible(block = block)
    } catch (e: Exception) { currentCoroutineContext().ensureActive(); throw e }
}
