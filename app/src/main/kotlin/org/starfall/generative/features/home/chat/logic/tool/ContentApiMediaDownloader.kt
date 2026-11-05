package org.starfall.generative.features.home.chat.logic.tool

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*
import org.starfall.generative.shared.helpers.media.prepareContentApiResult
import org.starfall.generative.shared.model.media.ContentApiMediaResult
import org.starfall.generative.shared.helpers.networking.ToolHttp
import org.starfall.generative.shared.helpers.networking.obj
import org.starfall.generative.shared.helpers.networking.str

internal suspend fun resolveContentApiMedia(
    result: JsonObject,
    http: ToolHttp,
    onMediaReady: suspend (List<String>) -> Unit = {}
): ContentApiMediaResult {
    val prepared = prepareContentApiResult(result)
    val files = mutableListOf<String>()
    val displayed = mutableListOf<JsonElement>()
    val isError = result["isError"]?.jsonPrimitive?.booleanOrNull == true
    for (media in prepared.media) {
        var downloaded = false
        if (!isError) {
            try {
                files += http.download(media.url)
                downloaded = true
                onMediaReady(files.toList())
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Keep the extraction metadata useful even if one CDN URL expired or failed.
            }
        }
        // Content-API media is displayed by the app from the ToolActivity. Keep local file names
        // out of the model-visible result so this special MCP can never route them through
        // send_file or expose a media URL to the model.
        displayed += obj("type" to str(media.type), "available" to JsonPrimitive(downloaded),
            "displayed_in_chat" to JsonPrimitive(downloaded))
    }
    val content = if (prepared.media.isEmpty()) prepared.content else JsonObject(prepared.content + mapOf(
        "app_media" to JsonArray(displayed),
        "app_media_note" to str("Media has been downloaded and displayed in the chat. Media URLs and local file references are hidden; do not output them.")
    ))
    return ContentApiMediaResult(content, files)
}
