package org.starfall.generative.integrations.media

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.starfall.generative.shared.subfeatures.media.logic.ToolFiles
import org.starfall.generative.shared.helpers.media.MEDIA_FILE_TOKEN_PREFIX
import org.starfall.generative.shared.helpers.media.MediaTemplateContext
import org.starfall.generative.shared.helpers.media.MediaTemplateInput
import org.starfall.generative.shared.helpers.media.MediaStepTemplateContext
import org.starfall.generative.shared.subfeatures.media.logic.MAX_MEDIA_INPUT_VIDEO_BYTES
import org.starfall.generative.shared.helpers.media.decodeMediaBase64
import org.starfall.generative.shared.helpers.media.isAbsoluteMediaEndpoint
import org.starfall.generative.shared.helpers.media.isValidMediaEndpointTemplate
import org.starfall.generative.shared.helpers.media.resolveMediaString
import org.starfall.generative.shared.helpers.media.resolveMediaTemplate
import org.starfall.generative.shared.helpers.media.selectMediaPath
import org.starfall.generative.shared.model.media.MediaFileTransport
import org.starfall.generative.shared.model.media.MediaRequestChain
import org.starfall.generative.shared.model.media.MediaRequestMode
import org.starfall.generative.shared.model.media.MediaRequestStep
import org.starfall.generative.shared.model.media.MediaResponseType
import org.starfall.generative.shared.model.media.MediaResponseObservationMode
import org.starfall.generative.shared.model.media.MediaValueKind
import org.starfall.generative.shared.model.provider.ModelProvider
import org.starfall.generative.shared.helpers.networking.ToolHttp
import org.starfall.generative.shared.helpers.networking.obj
import org.starfall.generative.shared.helpers.networking.providerBase
import org.starfall.generative.shared.helpers.networking.str
import java.io.File
import java.util.Locale

internal class MediaRequestChainExecutor(private val http: ToolHttp, private val music: Boolean = false, private val credential: String = "") {
    private val json = Json { isLenient = true; ignoreUnknownKeys = true }

    private data class InputFile(
        val file: File,
        val template: MediaTemplateInput
    )

    private data class StepResult(
        val value: JsonElement,
        val rawFile: String? = null,
        val responseHeaders: Map<String, String> = emptyMap(),
        val step: MediaStepTemplateContext? = null
    )

    suspend fun execute(
        chain: MediaRequestChain,
        provider: ModelProvider?,
        model: String,
        prompt: String,
        inputReferences: List<String>
    ): JsonObject {
        require(chain.steps.isNotEmpty()) { "At least one media request step is required." }
        val inputs = resolveInputs(http.requireFiles(), inputReferences)
        val responses = mutableListOf<JsonElement>()
        val responseHeaders = mutableListOf<Map<String, String>>()
        val steps = mutableListOf<MediaStepTemplateContext>()
        val results = mutableListOf<StepResult>()
        var last = StepResult(JsonObject(emptyMap()))
        chain.steps.forEachIndexed { index, step ->
            try {
                var attempts = 0
                val startedAt = System.currentTimeMillis()
                while (true) {
                    attempts++
                    val context = MediaTemplateContext(
                        prompt = prompt,
                        model = model,
                        apiToken = (provider?.auth?.token ?: credential),
                        inputFiles = inputs.map { it.template },
                        responses = responses.toList(),
                        responseHeaders = responseHeaders.toList(),
                        steps = steps.toList()
                    )
                    last = executeStep(
                        provider,
                        step,
                        context,
                        inputs,
                        isFinal = index == chain.steps.lastIndex,
                        finalResponseType = chain.finalResponse.type
                    )
                    if (!shouldRetry(step, last.value)) break
                    val policy = step.observation.retry
                    val elapsed = System.currentTimeMillis() - startedAt
                    val canRetry = attempts < policy.maxAttempts.coerceAtLeast(1) &&
                        elapsed + policy.delayMillis.coerceAtLeast(0) <= policy.maxWaitMillis.coerceAtLeast(0)
                    if (!canRetry) {
                        error("Media API did not become ready within the configured retry limit: ${safeResponse(last.value, provider)}")
                    }
                    delay(policy.delayMillis.coerceAtLeast(0))
                }
                responses += last.value
                responseHeaders += last.responseHeaders
                steps += last.step ?: error("Media request step did not produce request/response metadata.")
                results += last
                if (index == chain.steps.lastIndex) return finish(chain, results, provider)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (failure.message?.startsWith("Final response extraction") == true) throw failure
                val detail = failure.message?.takeIf { it.isNotBlank() } ?: failure::class.simpleName.orEmpty()
                throw IllegalStateException("Media request step ${index + 1} failed: $detail", failure)
            }
        }
        error("Media request chain did not produce a final response.")
    }

    /** Run the same real HTTP requests as generation up through the selected step, without
     * media extraction or creating an output file. Preview is intentionally file-free.
     * Every request here is initiated explicitly by the user from the step editor.
     */
    suspend fun testThroughStep(
        chain: MediaRequestChain,
        provider: ModelProvider?,
        model: String,
        prompt: String,
        stepIndex: Int,
        requiresModel: Boolean = true
    ): MediaStepTestResult {
        require(stepIndex in chain.steps.indices) { "Invalid step number." }
        require(!requiresModel || music || model.isNotBlank()) { "Select a model before testing the request." }
        val responses = mutableListOf<JsonElement>()
        val responseHeaders = mutableListOf<Map<String, String>>()
        val steps = mutableListOf<MediaStepTemplateContext>()
        for (index in 0..stepIndex) {
            val step = chain.steps[index]
            var attempt = 0
            val start = System.currentTimeMillis()
            var result: StepResult
            do {
                attempt++
                result = executeStep(
                    provider = provider,
                    step = step,
                    context = MediaTemplateContext(
                        prompt = prompt,
                        model = model,
                        apiToken = (provider?.auth?.token ?: credential),
                        inputFiles = emptyList(),
                        responses = responses.toList(),
                        responseHeaders = responseHeaders.toList(),
                        steps = steps.toList()
                    ),
                    inputs = emptyList(),
                    isFinal = index == chain.steps.lastIndex,
                    finalResponseType = chain.finalResponse.type,
                    preview = true
                )
                if (!shouldRetry(step, result.value)) break
                val retry = step.observation.retry
                val waitMillis = retry.delayMillis.coerceAtLeast(0)
                check(attempt < retry.maxAttempts.coerceAtLeast(1) &&
                    System.currentTimeMillis() - start + waitMillis <= retry.maxWaitMillis.coerceAtLeast(0)) {
                    "Step ${index + 1} did not finish within the retry limit."
                }
                delay(waitMillis)
            } while (true)
            responses += result.value
            responseHeaders += result.responseHeaders
            steps += requireNotNull(result.step)
        }
        val response = steps.last()
        val raw = responses.last().toString()
        val token = (provider?.auth?.token ?: credential)
        return MediaStepTestResult(
            statusCode = response.responseStatusCode,
            body = (if (token.isBlank()) raw else raw.replace(token, "[redacted]")).take(12000)
        )
    }

    private fun resolveInputs(files: ToolFiles, references: List<String>): List<InputFile> = references.map { reference ->
        val name = files.nameOf(reference) ?: reference.removePrefix("tool-file:")
        val file = files.resolve(name) ?: error("Input file is no longer available.")
        require(file.length() in 1..MAX_MEDIA_INPUT_VIDEO_BYTES) {
            "Input files must be non-empty and no larger than ${MAX_MEDIA_INPUT_VIDEO_BYTES / (1024 * 1024)} MB."
        }
        InputFile(file, MediaTemplateInput(
            name = file.name,
            mimeType = detectMime(file),
            base64 = java.util.Base64.getEncoder().encodeToString(file.readBytes())
        ))
    }

    private suspend fun executeStep(
        provider: ModelProvider?,
        step: MediaRequestStep,
        context: MediaTemplateContext,
        inputs: List<InputFile>,
        isFinal: Boolean,
        finalResponseType: MediaResponseType,
        preview: Boolean = false
    ): StepResult {
        val endpoint = resolveEndpoint(provider, resolveText(step.endpoint, context))
        val headers = step.headers.entries.associate { (key, value) ->
            resolveText(key, context) to resolveText(value, context)
        }
        val body = step.body.trim().ifBlank { if (step.mode == MediaRequestMode.RAW) "" else "{}" }
        val resolved = if (step.mode == MediaRequestMode.RAW) {
            JsonPrimitive(body)
        } else {
            val template = runCatching { json.parseToJsonElement(body) }
                .getOrElse { error("Request step JSON is invalid: ${it.message.orEmpty()}") }
            resolveMediaTemplate(template, context, step.fileTransport)
        }
        val requestBody = when (step.mode) {
            MediaRequestMode.JSON -> {
                require(!containsFileToken(resolved)) { "JSON request steps must use base64 for input files." }
                resolved.toString().toRequestBody(
                    step.contentType.trim().ifBlank { "application/json" }.toMediaType()
                )
            }
            MediaRequestMode.FORM -> formBody(resolved, inputs, step.contentType)
            MediaRequestMode.RAW -> rawRequestBody(step, context, inputs)
        }
        val builder = http.request(
            endpoint,
            provider,
            headers,
            provider?.auth?.token?.takeIf { it.isNotBlank() }
        )
        val request = applyMethod(builder, step.method, requestBody).build()
        val responseType = if (isFinal) finalResponseType else MediaResponseType.JSON
        return when (responseType) {
            MediaResponseType.JSON -> http.withResponse(request) { response ->
                http.requireSuccess(response)
                val text = http.readJson(response, sanitizeMedia = false, maxChars = if (music) 64 * 1024 * 1024 else 8 * 1024 * 1024)
                require(text.length <= (if (music) 64 else 8) * 1024 * 1024) { "Media API JSON response exceeds 8 MB." }
                val responseBody = json.parseToJsonElement(text)
                val headers = responseHeaders(response)
                StepResult(
                    value = responseBody,
                    responseHeaders = headers,
                    step = stepContext(request, resolved, response, responseBody, headers)
                )
            }
            MediaResponseType.IMAGE, MediaResponseType.VIDEO, MediaResponseType.AUDIO, MediaResponseType.OCTET_STREAM ->
                http.withResponse(request) { response ->
                    http.requireSuccess(response)
                    val mime = response.header("Content-Type").orEmpty().substringBefore(';').ifBlank {
                        when (responseType) {
                            MediaResponseType.IMAGE -> "image/*"
                            MediaResponseType.VIDEO -> "video/*"
                            MediaResponseType.AUDIO -> "audio/*"
                            else -> "application/octet-stream"
                        }
                    }
                    val source = response.body.byteStream().buffered()
                    val verifiedMime = if (music || responseType == MediaResponseType.AUDIO) {
                        source.mark(32)
                        val header = ByteArray(16)
                        var offset = 0
                        while (offset < header.size) {
                            val count = source.read(header, offset, header.size - offset)
                            if (count < 0) break
                            offset += count
                        }
                        source.reset()
                        org.starfall.generative.shared.helpers.audio.audioMime(header) ?: error("Response is not supported audio.")
                    } else mime
                    val name = if (preview) null else http.requireFiles().save(source, verifiedMime, audioOnly = music)
                    val responseBody = if (preview) obj(
                        "mediaType" to str(mime),
                        "contentLength" to str(response.body.contentLength().takeIf { it >= 0 }?.toString() ?: "unknown")
                    ) else obj("file" to str("tool-file:$name"))
                    val headers = responseHeaders(response)
                    StepResult(
                        value = responseBody,
                        rawFile = name,
                        responseHeaders = headers,
                        step = stepContext(request, resolved, response, responseBody, headers)
                    )
                }
        }
    }

    private fun stepContext(
        request: Request,
        requestBody: JsonElement,
        response: Response,
        responseBody: JsonElement,
        responseHeaders: Map<String, String>
    ) = MediaStepTemplateContext(
        url = request.url.toString(),
        requestMethod = request.method,
        requestHeaders = request.headers.names().associateWith { name -> request.header(name).orEmpty() },
        requestBody = requestBody,
        responseBody = responseBody,
        responseHeaders = responseHeaders,
        responseStatusCode = response.code
    )

    private fun responseHeaders(response: Response): Map<String, String> =
        response.headers.names().associateWith { name -> response.header(name).orEmpty() }

    private fun applyMethod(builder: Request.Builder, method: String, body: RequestBody): Request.Builder = when (method.trim().uppercase(Locale.ROOT)) {
        "GET" -> builder.get()
        "POST" -> builder.post(body)
        "PUT" -> builder.put(body)
        "PATCH" -> builder.patch(body)
        "DELETE" -> builder.delete(body)
        else -> error("Unsupported media request method '$method'.")
    }

    private fun formBody(value: JsonElement, inputs: List<InputFile>, contentType: String): RequestBody {
        val objectValue = value as? JsonObject ?: error("Form request mappings must be a JSON object.")
        val form = MultipartBody.Builder().setType(
            contentType.trim().ifBlank { MultipartBody.FORM.toString() }.toMediaType()
        )
        objectValue.forEach { (key, child) ->
            val fileTokens = fileTokens(child)
            if (fileTokens.isNotEmpty()) {
                fileTokens.forEach { index ->
                    val input = inputs.getOrNull(index) ?: error("Input file $index is not available.")
                    form.addFormDataPart(key, input.file.name, input.file.asRequestBody(input.template.mimeType.toMediaType()))
                }
            } else {
                form.addFormDataPart(key, formValue(child))
            }
        }
        return form.build()
    }

    private fun rawRequestBody(
        step: MediaRequestStep,
        context: MediaTemplateContext,
        inputs: List<InputFile>
    ): RequestBody {
        val source = Regex("""^\{\{\s*input_file\[(\d+)\]\s*\}\}$""").matchEntire(step.body.trim())
        val input = source?.groupValues?.get(1)?.toIntOrNull()?.let { inputs.getOrNull(it) }
        val contentType = step.contentType.trim().ifBlank {
            input?.template?.mimeType?.takeIf { it.isNotBlank() } ?: "application/octet-stream"
        }.toMediaType()
        if (source != null && step.fileTransport == MediaFileTransport.RAW) {
            return input?.file?.asRequestBody(contentType) ?: ByteArray(0).toRequestBody(contentType)
        }
        val text = resolveText(step.body, context)
        return text.toRequestBody(contentType)
    }

    private fun formValue(value: JsonElement): String = when (value) {
        is JsonPrimitive -> value.content
        else -> value.toString()
    }

    private fun fileTokens(value: JsonElement): List<Int> = when (value) {
        is JsonPrimitive -> fileTokenIndex(value.content)?.let(::listOf).orEmpty()
        is JsonArray -> value.flatMap(::fileTokens)
        is JsonObject -> value.values.flatMap(::fileTokens)
    }

    private fun containsFileToken(value: JsonElement): Boolean = fileTokens(value).isNotEmpty() || when (value) {
        is JsonObject -> value.values.any(::containsFileToken)
        is JsonArray -> value.any(::containsFileToken)
        else -> false
    }

    private fun fileTokenIndex(value: String): Int? = value.removePrefix(MEDIA_FILE_TOKEN_PREFIX)
        .takeIf { it != value }?.toIntOrNull()

    /**
     * The observer only controls whether the current step is retried. A non-pending response
     * always ends the retry loop and lets the chain continue to its next step, including an
     * API response whose status is configured as an error.
     */
    private fun shouldRetry(step: MediaRequestStep, response: JsonElement): Boolean {
        if (!step.observation.isEnabled) return false
        val path = step.observation.statusPath.trim()
        if (path.isBlank()) return false
        val selected = runCatching { selectMediaPath(response, path) }.getOrNull()
        if (selected.isNullOrEmpty()) return true
        if (step.observation.mode == MediaResponseObservationMode.PATH_EXISTS) return false
        val values = selected.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.lowercase() }
        if (values.isEmpty()) return true
        if (music) check(values.none { it in step.observation.errorValues.map(String::lowercase) }) { "Media API reported a failed generation." }
        val pending = step.observation.pendingValues.map(String::lowercase)
        return values.any { it in pending }
    }

    private suspend fun finish(
        chain: MediaRequestChain,
        results: List<StepResult>,
        provider: ModelProvider?
    ): JsonObject {
        val sourceStepNumber = chain.finalResponse.sourceStep ?: results.size
        return try {
            val sourceIndex = sourceStepNumber - 1
            require(sourceIndex in results.indices) {
                "Final response source step must refer to a completed request step."
            }
            finishFromResult(chain, results[sourceIndex], provider)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            val detail = failure.message?.takeIf { it.isNotBlank() } ?: failure::class.simpleName.orEmpty()
            throw IllegalStateException("Final response extraction for step $sourceStepNumber failed: $detail", failure)
        }
    }

    private suspend fun finishFromResult(
        chain: MediaRequestChain,
        result: StepResult,
        provider: ModelProvider?
    ): JsonObject {
        val files = http.requireFiles()
        val names = when (chain.finalResponse.type) {
            MediaResponseType.IMAGE, MediaResponseType.VIDEO, MediaResponseType.AUDIO, MediaResponseType.OCTET_STREAM ->
                result.rawFile?.let(::listOf) ?: error("Final response is configured as media but the last response was not raw media.")
            MediaResponseType.JSON -> {
                val path = chain.finalResponse.mediaPath.trim()
                require(path.isNotBlank()) { "A JSON final response requires a configurable media path." }
                val values = selectMediaPath(result.value, path)
                require(!music || values.size == 1) { "A music response must contain exactly one audio value." }
                require(values.size <= 10) { "A response may contain at most 10 media values." }
                values.map { value ->
                    val text = (value as? JsonPrimitive)?.contentOrNull.orEmpty()
                    require(text.isNotBlank()) { "The configured JSON media path must contain string values." }
                    when (chain.finalResponse.valueKind) {
                        MediaValueKind.LINK -> {
                            require(text.startsWith("https://") || (!music && text.startsWith("http://"))) {
                                "The configured JSON media value is not an HTTP(S) link."
                            }
                            http.download(text, if (provider != null && sameOrigin(text, providerBase(provider))) provider else null, audioOnly = music)
                        }
                        MediaValueKind.BASE64 -> files.save(
                            decodeMediaBase64(text).inputStream(),
                            dataUrlMime(text), audioOnly = music
                        )
                    }
                }
            }
        }
        require(names.isNotEmpty()) { "Media API returned no generated media." }
        return obj(
            "files" to JsonArray(names.distinct().map { str("tool-file:$it") }),
            "message" to str("Media saved and displayed to the user.")
        )
    }

    private fun resolveText(value: String, context: MediaTemplateContext): String {
        val element = resolveMediaString(value, context, MediaFileTransport.BASE64)
        require(element is JsonPrimitive && element.isString) { "Endpoint and header values must resolve to strings." }
        return element.content
    }

    private fun resolveEndpoint(provider: ModelProvider?, endpoint: String): String {
        val trimmed = endpoint.trim()
        require(isValidMediaEndpointTemplate(trimmed)) {
            "Media request endpoints must be provider-relative paths or complete HTTP(S) URLs without credentials or fragments."
        }
        if (isAbsoluteMediaEndpoint(trimmed)) {
            return trimmed.toHttpUrlOrNull()?.toString()
                ?: error("Media request endpoint is not a valid HTTP(S) URL.")
        }
        require(provider != null) { "Music steps require complete HTTPS endpoints." }
        return providerBase(provider).trimEnd('/') + "/" + trimmed.trimStart('/')
    }

    private fun sameOrigin(value: String, base: String): Boolean = runCatching {
        val left = java.net.URI(value)
        val right = java.net.URI(base)
        left.scheme == right.scheme && left.host == right.host && left.port == right.port
    }.getOrDefault(false)

    private fun safeResponse(value: JsonElement, provider: ModelProvider?): String {
        val raw = value.toString()
        val redacted = (provider?.auth?.token ?: credential).takeIf { it.isNotBlank() }
            ?.let { raw.replace(it, "[redacted]") } ?: raw
        return redacted.filter { it >= ' ' }.take(2_000)
    }

    private fun dataUrlMime(value: String): String? = value.takeIf { it.startsWith("data:") }
        ?.substringBefore(';')?.removePrefix("data:")

    private fun detectMime(file: File): String = when (file.extension.lowercase(Locale.ROOT)) {
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        "mp4" -> "video/mp4"
        "webm" -> "video/webm"
        "mov" -> "video/quicktime"
        else -> "application/octet-stream"
    }
}

/** Sanitized, bounded real HTTP response for the step editor. */
internal data class MediaStepTestResult(val statusCode: Int, val body: String)
