package org.starfall.generative.shared.helpers.media

import org.starfall.generative.shared.model.media.MediaInputImage

import kotlinx.serialization.json.*
import okhttp3.MultipartBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import java.util.Base64
import org.starfall.generative.shared.model.api.ModelApiType
import org.starfall.generative.shared.helpers.networking.obj
import org.starfall.generative.shared.helpers.networking.str
import org.starfall.generative.shared.helpers.networking.text

// https://developers.openai.com/api/reference/resources/images/methods/edit
internal fun openAiImageEditRequest(model: String, prompt: String, options: JsonObject, images: List<MediaInputImage>): MultipartBody {
    validateImageOptions(ModelApiType.OPENAI, model, options)
    require(images.size in 1..16) { "Image editing requires 1–16 reference images." }
    require(model != "dall-e-3") { "DALL·E 3 does not support image editing. Select a GPT image model." }
    if (model == "dall-e-2") {
        require(images.size == 1 && images.single().mimeType == "image/png" && images.single().file.length() < 4L * 1024 * 1024) {
            "DALL·E 2 editing accepts one PNG image smaller than 4 MB."
        }
        val header = ByteArray(24)
        val count = images.single().file.inputStream().use { it.read(header) }
        require(count == 24 && java.nio.ByteBuffer.wrap(header, 16, 8).let { it.int == it.int }) {
            "DALL·E 2 editing requires a square PNG image."
        }
    }
    val fields = imageGenerationRequest(ModelApiType.OPENAI, model, prompt, options)
    return MultipartBody.Builder().setType(MultipartBody.FORM).apply {
        fields.forEach { (key, value) ->
            if (value != JsonNull) addFormDataPart(key, if (value is JsonPrimitive) value.content else value.toString())
        }
        images.forEach {
            addFormDataPart(if (model == "dall-e-2") "image" else "image[]", it.file.name, it.file.asRequestBody(it.mimeType.toMediaType()))
        }
    }.build()
}

// https://ai.google.dev/gemini-api/docs/image-generation
internal fun googleImageEditRequest(model: String, prompt: String, options: JsonObject, images: List<MediaInputImage>): JsonObject {
    require(!model.contains("imagen", true)) { "This Imagen generation endpoint does not support input images. Select a Gemini image model." }
    val estimatedBytes = images.sumOf { ((it.file.length() + 2) / 3) * 4 + 1024 } +
        prompt.toByteArray(Charsets.UTF_8).size + options.toString().toByteArray(Charsets.UTF_8).size
    require(estimatedBytes <= 20L * 1024 * 1024) {
        "The inline image request exceeds 20 MB. Use fewer or smaller reference images."
    }
    val request = imageGenerationRequest(ModelApiType.GOOGLE, model, prompt, options)
    val parts = listOf(obj("text" to str(prompt))) + images.map {
        obj("inlineData" to obj("mimeType" to str(it.mimeType), "data" to str(Base64.getEncoder().encodeToString(it.file.readBytes()))))
    }
    val edited = JsonObject(request + ("contents" to JsonArray(listOf(obj("role" to str("user"), "parts" to JsonArray(parts))))))
    require(edited.toString().toByteArray(Charsets.UTF_8).size <= 20 * 1024 * 1024) {
        "The inline image request exceeds 20 MB. Use fewer or smaller reference images."
    }
    return edited
}
