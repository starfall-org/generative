package org.starfall.generative.integrations.modelapis.attachments

import com.google.genai.types.FileState
import com.google.genai.types.GetFileConfig
import com.google.genai.types.UploadFileConfig
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import org.starfall.generative.integrations.modelapis.sdk.OfficialProviderSdk
import org.starfall.generative.integrations.modelapis.sdk.useClient
import org.starfall.generative.shared.model.api.ModelApiType
import org.starfall.generative.shared.model.provider.ModelProvider
import org.starfall.generative.shared.subfeatures.media.logic.AttachmentResolver
import org.starfall.generative.shared.subfeatures.media.logic.ResolvedAttachment

/** Only these public protocol adapters implement a Files API. Account adapters remain inline. */
@Suppress("DEPRECATION") // Processing status is optional today; older compatible gateways still report it.
internal class SdkAttachmentUploader(
    private val sdk: OfficialProviderSdk,
    private val source: AttachmentResolver,
    private val messages: AttachmentMessages = AttachmentMessages()
) : AttachmentUploader {
    override fun capability(provider: ModelProvider, file: ResolvedAttachment): UploadCapability {
        val inline = AttachmentPreflight.INLINE_LIMIT
        val customGeneration = provider.config.customEndpoints[when (provider.type) {
            ModelApiType.OPENAI -> "chat"
            ModelApiType.OPENAI_RESPONSES -> "responses"
            ModelApiType.ANTHROPIC -> "messages"
            else -> "generate"
        }].orEmpty().isNotBlank()
        // An unrelated custom generation route does not establish a Files API on the base URL.
        val remote = !customGeneration || provider.config.customEndpoints["files"].orEmpty().isNotBlank()
        return when (provider.type) {
            ModelApiType.OPENAI_RESPONSES -> UploadCapability(remote, true, if (remote) 50L * MB else inline,
                if (file.isImage) "vision" else "user_data")
            ModelApiType.OPENAI -> UploadCapability(remote && file.isPdf, file.isImage || file.isPdf,
                if (remote && file.isPdf) 50L * MB else inline, "user_data")
            ModelApiType.ANTHROPIC -> {
                val image = file.mimeType in setOf("image/jpeg", "image/png", "image/gif", "image/webp")
                val supported = image || file.isPdf || file.isText
                UploadCapability(remote && (image || file.isPdf), supported,
                    if (remote && (image || file.isPdf)) 500L * MB else inline, "messages")
            }
            ModelApiType.GOOGLE -> UploadCapability(!customGeneration && provider.config.customEndpoints["files"].isNullOrBlank(), true,
                if (!customGeneration && provider.config.customEndpoints["files"].isNullOrBlank()) (if (file.isPdf) 50L * MB else 2L * 1024 * MB) else inline, "generateContent")
            ModelApiType.OLLAMA -> UploadCapability(false, file.isImage, inline, "inline")
            // Preserve specialized account protocols, without probing a public Files endpoint.
            ModelApiType.CLAUDE_CODE -> UploadCapability(false, file.isImage || file.isPdf || file.isText, inline, "inline")
            ModelApiType.GEMINI_CLI, ModelApiType.ANTIGRAVITY -> UploadCapability(false, true, inline, "inline")
            else -> UploadCapability(false, file.isImage, inline, "inline")
        }
    }

    override fun scope(provider: ModelProvider): String = attachmentHash(buildList {
        add(provider.type.name)
        add(sdk.baseUrl(provider))
        add(provider.auth.method.name)
        add(provider.auth.key.orEmpty())
        add(provider.auth.token)
        add(provider.auth.oauthAccountId.orEmpty())
        provider.config.customEndpoints.toSortedMap().forEach { (key, value) -> add(key); add(value) }
        // Headers can select an organization/project or override authentication.
        sdk.headers(provider).entries.sortedBy { it.key.lowercase() }.forEach { (key, value) -> add(key.lowercase()); add(value) }
    })

    override suspend fun upload(provider: ModelProvider, file: ResolvedAttachment, key: String): RemoteAttachmentRecord =
        when (provider.type) {
            ModelApiType.OPENAI, ModelApiType.OPENAI_RESPONSES -> sdk.openAi(provider).useClient { client ->
                val result = source.open(file.reference)?.use { input ->
                    val field = com.openai.core.MultipartField.builder<java.io.InputStream>()
                        .value(input).filename(file.name).contentType(file.mimeType).build()
                    sdk.sdkCall { client.files().create(com.openai.models.files.FileCreateParams.builder()
                        .file(field).purpose(com.openai.models.files.FilePurpose.of(capability(provider, file).purpose)).build()) }
                } ?: error(messages.missing(file.name))
                var current = result
                var attempts = 0
                while (current._status().asKnown().orElse(null)?.toString() == "uploaded" && attempts++ < 120) {
                    delay(1000)
                    current = sdk.sdkCall { client.files().retrieve(result.id()) }
                }
                check(current._status().asKnown().orElse(null)?.toString() in setOf(null, "processed")) { messages.processing(file.name) }
                RemoteAttachmentRecord(key, file.reference, result.id(), expiresAt = result.expiresAt().orElse(null)?.times(1000))
            }
            ModelApiType.ANTHROPIC -> sdk.anthropic(provider.withFileReferenceHeaders()).useClient { client ->
                val result = source.open(file.reference)?.use { input ->
                    val field = com.anthropic.core.MultipartField.builder<java.io.InputStream>()
                        .value(input).filename(file.name).contentType(file.mimeType).build()
                    sdk.sdkCall { client.files().upload(com.anthropic.models.files.FileUploadParams.builder().file(field).build()) }
                } ?: error(messages.missing(file.name))
                RemoteAttachmentRecord(key, file.reference, result.id(), expiresAt = result.expiresAt().orElse(null)?.toInstant()?.toEpochMilli())
            }
            ModelApiType.GOOGLE -> sdk.google(provider).use { client ->
                // The fingerprint pass already bounded unknown-length streams; local snapshots have a known length.
                val size = if (file.sizeBytes > 0) file.sizeBytes else source.open(file.reference)?.use { input ->
                    var total = 0L
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        kotlinx.coroutines.currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= capability(provider, file).maxBytes) { messages.tooLarge(file.name) }
                    }
                    total
                } ?: error(messages.missing(file.name))
                var uploaded = source.open(file.reference)?.use { input ->
                    sdk.sdkCall { client.files.upload(input, size,
                        UploadFileConfig.builder().mimeType(file.mimeType).displayName(file.name).build()) }
                } ?: error(messages.missing(file.name))
                val name = uploaded.name().orElseThrow { IllegalStateException(messages.processing(file.name)) }
                var attempts = 0
                while (uploaded.state().orElse(null)?.knownEnum() != FileState.Known.ACTIVE && attempts++ < 120) {
                    check(uploaded.state().orElse(null)?.knownEnum() != FileState.Known.FAILED) { messages.processing(file.name) }
                    delay(1000)
                    uploaded = sdk.sdkCall { client.files.get(name, GetFileConfig.builder().build()) }
                }
                check(uploaded.state().orElse(null)?.knownEnum() == FileState.Known.ACTIVE) { messages.processing(file.name) }
                val uri = uploaded.uri().orElseThrow { IllegalStateException(messages.processing(file.name)) }
                RemoteAttachmentRecord(key, file.reference, uri, name,
                    uploaded.expirationTime().orElse(null)?.toEpochMilli() ?: (System.currentTimeMillis() + 47L * 60 * 60 * 1000))
            }
            else -> error("This adapter does not support file uploads")
        }

    override suspend fun exists(provider: ModelProvider, remote: RemoteAttachmentRecord): Boolean = try {
        when (provider.type) {
            ModelApiType.OPENAI, ModelApiType.OPENAI_RESPONSES -> sdk.openAi(provider).useClient { client ->
                sdk.sdkCall { client.files().retrieve(remote.remoteId) }._status().asKnown().orElse(null)?.toString() in setOf(null, "processed")
            }
            ModelApiType.ANTHROPIC -> sdk.anthropic(provider.withFileReferenceHeaders()).useClient { client ->
                sdk.sdkCall { client.files().retrieveMetadata(remote.remoteId) }; true
            }
            ModelApiType.GOOGLE -> sdk.google(provider).use { client ->
                sdk.sdkCall { client.files.get(remote.remoteName, GetFileConfig.builder().build()) }
                    .state().orElse(null)?.knownEnum() == FileState.Known.ACTIVE
            }
            else -> false
        }
    } catch (error: Exception) {
        if (attachmentHttpStatus(error) == 404) false else throw error
    }

    override fun isUnsupportedEndpoint(error: Exception): Boolean = when (attachmentHttpStatus(error)) {
        405, 501 -> true
        404 -> error.message.orEmpty().let { text ->
            listOf("unknown endpoint", "unsupported endpoint", "route not found", "cannot post", "endpoint not found").any { text.contains(it, true) }
        }
        else -> false
    }

    companion object { private const val MB = 1024L * 1024 }
}

internal fun attachmentHttpStatus(error: Exception): Int? = when (error) {
    is com.openai.errors.OpenAIServiceException -> error.statusCode()
    is com.anthropic.errors.AnthropicServiceException -> error.statusCode()
    is com.google.genai.errors.ApiException -> error.code()
    else -> Regex("^HTTP (400|404|410):").find(error.message.orEmpty())?.groupValues?.get(1)?.toIntOrNull()
}

internal fun rejectedRemoteAttachments(
    error: Exception, prepared: Map<String, PreparedAttachment>
): Map<String, PreparedAttachment> {
    if (attachmentHttpStatus(error) !in setOf(400, 404, 410)) return emptyMap()
    val message = error.message.orEmpty()
    if (listOf("not found", "expired", "does not exist", "invalid file", "deleted").none { message.contains(it, true) }) return emptyMap()
    return prepared.filterValues { attachment ->
        attachment.remote?.let { remote ->
            listOf(remote.remoteId, remote.remoteName).any { id ->
                Regex("(?<![\\w-])" + Regex.escape(id) + "(?![\\w-])").containsMatchIn(message)
            }
        } == true
    }
}

internal fun rejectedRemoteAttachment(error: Exception, prepared: Map<String, PreparedAttachment>): Boolean =
    rejectedRemoteAttachments(error, prepared).isNotEmpty()
