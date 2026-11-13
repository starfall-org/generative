package org.starfall.generative.features.home.shared.logic

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID
import org.starfall.generative.shared.subfeatures.media.logic.MAX_MEDIA_INPUT_BYTES
import org.starfall.generative.shared.subfeatures.media.logic.MAX_MEDIA_INPUT_VIDEO_BYTES
import org.starfall.generative.shared.subfeatures.media.logic.detectInputMediaMime
import org.starfall.generative.shared.helpers.media.imagePreviewAspectRatio
import org.starfall.generative.integrations.media.SystemMediaTools
import org.starfall.generative.integrations.modelapis.AiService
import org.starfall.generative.shared.model.ToolActivity
import org.starfall.generative.shared.model.toToolPayload
import org.starfall.generative.shared.model.generation.GenerationEvent
import org.starfall.generative.shared.model.language.ModelType
import org.starfall.generative.shared.model.media.MediaRequestChain
import org.starfall.generative.shared.model.media.MediaGenerationMode
import org.starfall.generative.shared.model.provider.ModelProvider
import org.starfall.generative.shared.helpers.networking.ToolHttp
import org.starfall.generative.shared.helpers.networking.obj
import org.starfall.generative.shared.helpers.networking.str
import org.starfall.generative.features.home.shared.logic.prepareToolResult

/** Handles direct image/video generation and emits its progress as tool events. */
internal class ToolMediaGenerationService(
    private val http: ToolHttp,
    private val aiService: AiService
) {
    suspend fun executeMusicTool(profile: org.starfall.generative.shared.model.media.MediaRequestProfile, prompt: String): JsonObject =
        org.starfall.generative.integrations.media.MusicGenerationClient(http).generate(profile, prompt)

    suspend fun executeConfiguredTool(
        name: String,
        provider: ModelProvider?,
        model: String,
        prompt: String,
        imageOptions: JsonObject,
        inputImage: String?,
        inputImages: List<String>,
        videoOptions: JsonObject,
        inputImageUrl: String?,
        inputVideo: String? = null,
        inputVideoUrl: String? = null,
        generationMode: MediaGenerationMode = MediaGenerationMode.DEFAULT,
        requestChain: MediaRequestChain? = null
    ): JsonObject = SystemMediaTools(http).generate(
        name, provider, model, prompt, imageOptions,
        inputImage, inputImages, videoOptions = videoOptions, inputImageUrl = inputImageUrl,
        inputVideo = inputVideo, inputVideoUrl = inputVideoUrl,
        generationMode = generationMode, requestChain = requestChain
    )

    fun generateMedia(
        provider: ModelProvider,
        model: String,
        kind: ModelType,
        prompt: String,
        imageOptions: JsonObject = obj(),
        attachments: List<String> = emptyList(),
        videoOptions: JsonObject = obj(),
        generationMode: MediaGenerationMode = MediaGenerationMode.DEFAULT,
        requestChain: MediaRequestChain? = null
    ): Flow<GenerationEvent> = flow {
        require(kind == ModelType.IMAGE_GENERATION || kind == ModelType.VIDEO_GENERATION)
        val name = if (kind == ModelType.IMAGE_GENERATION) "generate_image" else "generate_video"
        var activity = ToolActivity(UUID.randomUUID().toString(), name,
            arguments = obj("prompt" to str(prompt)).toString().toToolPayload(),
            previewAspectRatio = if (kind == ModelType.IMAGE_GENERATION) imagePreviewAspectRatio(imageOptions) else null)
        emit(GenerationEvent.Tool(activity))
        try {
            require(kind != ModelType.VIDEO_GENERATION || attachments.size <= 1) {
                "Video generation accepts one reference image or video. Remove extra attachments."
            }
            require(attachments.size <= 16) { "Image generation accepts at most 16 reference images." }
            val maxBytes = when {
                kind == ModelType.VIDEO_GENERATION -> MAX_MEDIA_INPUT_VIDEO_BYTES
                provider.type.isOpenAi -> 50L * 1024 * 1024 - 1
                else -> MAX_MEDIA_INPUT_BYTES
            }
            val files = http.requireFiles()
            val inputs = aiService.importToolAttachments(attachments, files, maxBytes)
            // A video's single reference attachment can be an image or a video; sniff the saved file to tell which.
            val referenceIsVideo = kind == ModelType.VIDEO_GENERATION && inputs.singleOrNull()?.let { reference ->
                val mime = files.resolve(reference.removePrefix("tool-file:"))?.let(::detectInputMediaMime)
                require(mime != null) { "Video reference must be a PNG, JPEG, or WebP image, or an MP4, MOV, or WebM video." }
                mime.startsWith("video/")
            } == true
            if (inputs.isNotEmpty()) {
                activity = activity.copy(arguments = buildJsonObject {
                    put("prompt", prompt)
                    if (kind == ModelType.VIDEO_GENERATION) put(if (referenceIsVideo) "input_video" else "input_image", inputs.single())
                    else put("input_images", JsonArray(inputs.map(::str)))
                }.toString().toToolPayload())
                emit(GenerationEvent.Tool(activity))
            }
            val result = SystemMediaTools(http).generate(name, provider, model, prompt, imageOptions,
                inputImage = if (kind == ModelType.VIDEO_GENERATION && !referenceIsVideo) inputs.singleOrNull() else null,
                inputImages = if (kind == ModelType.IMAGE_GENERATION) inputs else emptyList(),
                videoOptions = videoOptions,
                inputVideo = if (referenceIsVideo) inputs.single() else null,
                generationMode = generationMode,
                requestChain = requestChain)
            val summary = prepareToolResult(result, http.requireFiles())
            emit(GenerationEvent.Tool(activity.copy(status = "success", summary = summary.preview,
                files = summary.files, response = summary.content.toString().toToolPayload())))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val error = obj(
                "isError" to JsonPrimitive(true),
                "status" to str("error"),
                "error" to str(e.message.orEmpty().take(500))
            )
            emit(GenerationEvent.Tool(activity.copy(
                status = "error",
                summary = e.message.orEmpty().take(500),
                response = error.toString().toToolPayload()
            )))
            throw e
        }
    }

}
