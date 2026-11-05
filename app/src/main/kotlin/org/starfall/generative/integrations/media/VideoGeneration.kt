package org.starfall.generative.integrations.media

import org.starfall.generative.shared.model.media.MediaInputImage
import org.starfall.generative.shared.model.media.MediaInputVideo
import org.starfall.generative.shared.helpers.media.*
import org.starfall.generative.shared.subfeatures.media.logic.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import org.starfall.generative.shared.model.provider.ModelProvider
import org.starfall.generative.shared.helpers.networking.ToolHttp
import org.starfall.generative.shared.helpers.networking.obj
import org.starfall.generative.shared.helpers.networking.providerBase
import org.starfall.generative.shared.helpers.networking.str
import org.starfall.generative.shared.helpers.networking.text
import kotlin.time.Duration.Companion.milliseconds

internal class VideoGeneration(
    private val http: ToolHttp,
    private val pollIntervalMillis: Long = 1500,
    private val pollingTimeoutMillis: Long = 15 * 60 * 1000L
) {
    suspend fun generate(
        provider: ModelProvider, model: String, prompt: String, image: MediaInputImage?, options: JsonObject, imageUrl: String?,
        video: MediaInputVideo? = null, videoUrl: String? = null
    ): JsonObject {
        require(image == null || imageUrl == null) { "Use one source image: an attachment or input_image_url." }
        require(video == null || videoUrl == null) { "Use one source video: an attachment or input_video_url." }
        imageUrl?.let(::validateVideoImageUrl)
        videoUrl?.let { validateReferenceUrl(it, "videos") }
        require(imageUrl == null) { "This video API needs an attached source image, not input_image_url." }
        require(videoUrl == null) { "This video API needs an attached source video, not input_video_url." }
        validateVideoOptions(provider, model, options)
        val configuredBase = providerBase(provider).removeSuffix("/videos")
        val videos = provider.config.customEndpoints["video"]?.takeIf { it.isNotBlank() }?.trimEnd('/') ?: "$configuredBase/videos"
        var job = http.json(http.request(videos, provider).post(openAiVideoRequest(model, prompt, image, video, options)).build())
        val id = videoJobId(job)
        validateVideoJobId(id)
        if (!isTerminalVideoJob(job)) {
            if (id.isBlank() && job.text("status").isBlank()) return job
            require(id.isNotBlank()) { "Video API returned no job ID to retrieve the result." }
            val pollUrl = provider.config.customEndpoints["videoStatus"]?.takeIf { it.isNotBlank() }?.replace("{id}", id) ?: "$videos/$id"
            job = withTimeout(pollingTimeoutMillis.milliseconds) {
                var state = job
                while (!isTerminalVideoJob(state)) {
                    delay(pollIntervalMillis.milliseconds)
                    state = http.json(http.request(pollUrl, provider).get().build())
                }
                state
            }
        }
        check(job.text("status").equals("completed", true)) { "Video generation failed or was cancelled." }
        // OpenAI-compatible gateways may provide a CDN URL for the common media collector.
        if (videoResultUrl(job).isNotBlank()) return obj("url" to str(videoResultUrl(job)))
        if (id.isBlank()) return job
        return obj("file" to str("tool-file:" + http.download(provider.config.customEndpoints["videoContent"]?.takeIf { it.isNotBlank() }?.replace("{id}", id) ?: "$videos/$id/content", provider)))
    }

}
