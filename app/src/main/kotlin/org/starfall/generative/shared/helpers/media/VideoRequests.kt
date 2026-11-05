package org.starfall.generative.shared.helpers.media

import org.starfall.generative.shared.model.media.MediaInputImage
import org.starfall.generative.shared.model.media.MediaInputVideo

import kotlinx.serialization.json.*
import okhttp3.MultipartBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import org.starfall.generative.shared.helpers.networking.obj
import org.starfall.generative.shared.helpers.networking.str
import java.util.Base64

/** OpenAI-compatible video APIs use multipart requests. */
internal fun openAiVideoRequest(model: String, prompt: String, image: MediaInputImage? = null,
    video: MediaInputVideo? = null, options: JsonObject = obj()): MultipartBody {
    require(image == null || video == null) { "OpenAI-compatible video APIs accept one input_reference: an image or a video." }
    val reference = image?.let { it.file to it.mimeType } ?: video?.let { it.file to it.mimeType }
    return MultipartBody.Builder().setType(MultipartBody.FORM)
        .addFormDataPart("model", model)
        .addFormDataPart("prompt", prompt)
        .apply {
            options.forEach { (name, value) -> addFormDataPart(name, value.jsonPrimitive.content) }
            reference?.let { (file, mime) ->
                addFormDataPart("input_reference", file.name, file.asRequestBody(mime.toMediaType()))
            }
        }.build()
}

/** Gemini Veo REST uses an inline image, and an inline video or a hosted video URI, in the generation instance. */
internal fun googleVideoRequest(prompt: String, image: MediaInputImage? = null, video: MediaInputVideo? = null,
    videoUrl: String? = null, options: JsonObject = obj()): JsonObject =
    obj("instances" to JsonArray(listOf(buildJsonObject {
        put("prompt", prompt)
        image?.let {
            put("image", obj("inlineData" to obj(
                "mimeType" to str(it.mimeType),
                "data" to str(Base64.getEncoder().encodeToString(it.file.readBytes()))
            )))
        }
        video?.let {
            put("video", obj("inlineData" to obj(
                "mimeType" to str(it.mimeType),
                "data" to str(Base64.getEncoder().encodeToString(it.file.readBytes()))
            )))
        }
        videoUrl?.let { put("video", obj("uri" to str(it))) }
    }))).let { request ->
        options["parameters"]?.let { params -> JsonObject(request + ("parameters" to params)) } ?: request
    }
