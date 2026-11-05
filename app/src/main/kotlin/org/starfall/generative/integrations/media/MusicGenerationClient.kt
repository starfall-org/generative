package org.starfall.generative.integrations.media

import kotlinx.coroutines.*
import org.starfall.generative.shared.subfeatures.media.logic.ToolFileBatch
import kotlinx.serialization.json.JsonObject
import org.starfall.generative.integrations.security.SecretCipher
import org.starfall.generative.shared.model.media.MediaRequestProfile
import org.starfall.generative.shared.model.media.validMusicProfile
import org.starfall.generative.shared.helpers.networking.ToolHttp

/** Executes a profile snapshot without resolving a Provider or a chat model. */
internal class MusicGenerationClient(http: ToolHttp) {
    private val privateHttp = ToolHttp(http.files, http.client.newBuilder().apply { interceptors().removeAll { it is org.starfall.generative.shared.subfeatures.logging.logic.DebugLogInterceptor } }.build())

    private suspend fun <T> run(profile: MediaRequestProfile, block: suspend (MediaRequestChainExecutor) -> T): T {
        require(profile.validMusicProfile()) { "Select a valid Custom music profile." }
        val credential = if (profile.encryptedCredential.isBlank()) "" else SecretCipher.decryptStrict(profile.encryptedCredential)
        val batch = ToolFileBatch()
        return try {
            withContext(batch) {
                val completion = withTimeoutOrNull(600_000) {
                    val result = block(MediaRequestChainExecutor(privateHttp, music = true, credential = credential))
                    currentCoroutineContext().ensureActive()
                    Result.success(result)
                }
                completion?.getOrThrow() ?: error("Music generation exceeded its time limit.")
            }
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable + Dispatchers.IO) { batch.rollback() }
            throw cancelled
        } catch (failure: Exception) {
            withContext(NonCancellable + Dispatchers.IO) { batch.rollback() }
            // Never include a vendor's response or a resolved request template in a tool message.
            val status = Regex("HTTP ([0-9]{3})").find(failure.message.orEmpty())?.groupValues?.get(1)
            throw IllegalStateException("Music request failed${status?.let { " (HTTP $it)" }.orEmpty()}. Check the profile endpoint, credentials and response mapping.")
        }
    }
    suspend fun generate(profile: MediaRequestProfile, prompt: String): JsonObject {
        require(prompt.isNotBlank() && prompt.length <= 20_000) { "Music prompt must contain 1 to 20000 characters." }
        return run(profile) { it.execute(profile.chain, null, "", prompt, emptyList()) }
    }
    suspend fun test(profile: MediaRequestProfile, prompt: String, step: Int): MediaStepTestResult =
        run(profile.copy(chain = profile.chain.copy(finalResponse =
            org.starfall.generative.shared.model.media.MediaFinalResponse(mediaPath = "$")))) {
            it.testThroughStep(profile.chain, null, "", prompt, step)
        }
}
