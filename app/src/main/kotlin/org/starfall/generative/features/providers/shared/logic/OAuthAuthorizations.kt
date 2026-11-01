package org.starfall.generative.features.providers.shared.logic

import kotlinx.coroutines.*
import org.starfall.generative.shared.model.provider.ModelProvider

/** An OAuth request belongs to the ViewModel, so browser/activity transitions cannot discard it. */
internal class OAuthAuthorizations(
    private val scope: CoroutineScope,
    private val authorizeAccount: suspend (ModelProvider) -> Result<ModelProvider>
) {
    private val active = mutableMapOf<String, Deferred<Result<ModelProvider>>>()

    private fun key(provider: ModelProvider) = "${provider.id}:${provider.type}"

    suspend fun authorize(provider: ModelProvider): Result<ModelProvider> {
        val key = key(provider)
        val pending = synchronized(active) {
            active[key] ?: scope.async(start = CoroutineStart.LAZY) {
                try { authorizeAccount(provider) }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { Result.failure(e) }
            }.also { request ->
                active[key] = request
                request.invokeOnCompletion {
                    synchronized(active) { active.remove(key, request) }
                }
            }
        }
        return pending.await()
    }
    fun cancel(provider: ModelProvider) {
        val request = synchronized(active) { active[key(provider)] }
        request?.cancel(CancellationException("OAuth authorization cancelled by user"))
    }

}
