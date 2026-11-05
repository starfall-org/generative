package org.starfall.generative.shared.helpers.media

import org.starfall.generative.R
import org.starfall.generative.shared.model.api.ModelApiType
import org.starfall.generative.shared.model.media.ImageOptionField
import org.starfall.generative.shared.helpers.networking.obj
import org.starfall.generative.shared.helpers.networking.str
import org.starfall.generative.shared.model.media.VideoApi

import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.starfall.generative.shared.model.provider.ModelProvider

internal fun videoApi(provider: ModelProvider): VideoApi = VideoApi.OPENAI

internal fun validateVideoImageUrl(value: String): String = validateReferenceUrl(value, "images")

internal fun validateReferenceUrl(value: String, kind: String): String {
    val url = value.toHttpUrlOrNull()
    require(url != null && url.scheme == "https" && url.username.isEmpty() && url.password.isEmpty()) {
        "Reference $kind require a publicly accessible HTTPS URL without embedded credentials."
    }
    return value
}

/** Only options implemented by the built-in Sora/Veo transports are exposed. */
fun videoOptionFields(provider: ModelProvider, model: String): List<ImageOptionField> = when (provider.type) {
    ModelApiType.OPENAI, ModelApiType.OPENAI_RESPONSES -> listOf(
        ImageOptionField("seconds", "Duration", choices = listOf("4", "8", "12"), labelRes = R.string.video_option_duration),
        ImageOptionField("size", "Video size", choices = listOf("720x1280", "1280x720") +
            (if (model.contains("pro", true)) listOf("1024x1792", "1792x1024") else emptyList()),
            labelRes = R.string.video_option_size)
    )
    ModelApiType.GOOGLE -> listOf(
        ImageOptionField("parameters.durationSeconds", "Duration", kind = "integer",
            choices = listOf("4", "6", "8"), labelRes = R.string.video_option_duration),
        ImageOptionField("parameters.aspectRatio", "Aspect ratio", choices = listOf("16:9", "9:16"),
            labelRes = R.string.image_option_aspect_ratio),
        ImageOptionField("parameters.resolution", "Video resolution", choices = listOf("720p", "1080p"),
            labelRes = R.string.video_option_resolution)
    )
    else -> emptyList()
}

fun suggestedVideoOptions(type: ModelApiType): JsonObject = when (type) {
    ModelApiType.OPENAI, ModelApiType.OPENAI_RESPONSES -> obj(
        "seconds" to str("4"), "size" to str("720x1280")
    )
    ModelApiType.GOOGLE -> obj("parameters" to obj(
        "durationSeconds" to JsonPrimitive(8), "aspectRatio" to str("16:9"), "resolution" to str("720p")
    ))
    else -> obj()
}

fun validateVideoOptions(provider: ModelProvider, model: String, options: JsonObject) {
    require(options.toString().length <= 65536) { "Video settings must be under 64 KB." }
    require(listOf("model", "prompt", "input_reference", "image", "instances").none { it in options }) {
        "Model, prompt, and attached files are supplied by chat."
    }
    val fields = videoOptionFields(provider, model)
    val validPaths = fields.map { it.path }.toSet()
    when (provider.type) {
        ModelApiType.GOOGLE -> {
            require(options.keys.all { it == "parameters" }) { "Unsupported video settings." }
            val params = options["parameters"]
            require(params == null || params is JsonObject) { "parameters must be a JSON object." }
            require(params?.keys.orEmpty().all { "parameters.$it" in validPaths }) {
                "Unsupported Veo parameters."
            }
        }
        else -> require(options.keys.all { it in validPaths }) {
            "Unsupported video settings: ${(options.keys - validPaths).joinToString()}."
        }
    }
    fields.forEach { field ->
        val value = options.optionAt(field.path) ?: return@forEach
        val primitive = value as? JsonPrimitive ?: error("${field.label} must be ${field.kind}.")
        val content = primitive.content
        if (field.kind == "integer") {
            require(!primitive.isString && primitive.intOrNull != null && content in field.choices) {
                "Invalid ${field.label.lowercase()}."
            }
        } else require(primitive.isString && (field.choices.isEmpty() || content in field.choices)) {
            "Invalid ${field.label.lowercase()}."
        }
    }
    if (provider.type == ModelApiType.GOOGLE && options.optionAt("parameters.resolution")?.jsonPrimitive?.content == "1080p") {
        require(options.optionAt("parameters.durationSeconds")?.jsonPrimitive?.intOrNull.let { it == null || it == 8 }) {
            "Veo requires an 8-second duration for 1080p output."
        }
    }
}
