package org.starfall.generative

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.starfall.generative.shared.model.*
import org.starfall.generative.features.providers.shared.logic.OAuthAuthorizations
import org.starfall.generative.shared.model.auth.AuthMethod
import org.starfall.generative.shared.model.auth.Authorization
import org.starfall.generative.shared.model.api.ModelApiType
import org.starfall.generative.shared.model.provider.ModelProvider

class OAuthAuthorizationsTest {
    private val provider = ModelProvider("first-login", "Account", ModelApiType.ANTIGRAVITY, baseUrl = ModelApiType.ANTIGRAVITY.defaultBaseUrl)

    @Test fun screenCancellationDoesNotLoseAuthorizationAndRetryJoinsTheSameRequest() = runBlocking {
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val release = CompletableDeferred<Unit>()
        var attempts = 0
        var saved = false
        val coordinator = OAuthAuthorizations(owner) {
            attempts++
            release.await()
            saved = true
            Result.success(it.copy(auth = Authorization(AuthMethod.OAUTH, value = "saved")))
        }
        try {
            val firstScreen = async { coordinator.authorize(provider) }
            yield()
            firstScreen.cancelAndJoin()
            val returnedScreen = async { coordinator.authorize(provider) }
            yield()
            assertEquals(1, attempts)
            release.complete(Unit)
            assertEquals("saved", returnedScreen.await().getOrThrow().auth.value)
            assertTrue(saved)
        } finally { owner.cancel() }
    }

    @Test fun explicitCancelStopsTheViewModelOwnedAuthorization() = runBlocking {
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val started = CompletableDeferred<Unit>()
        var cancelled = false
        val coordinator = OAuthAuthorizations(owner) {
            started.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                cancelled = true
            }
        }
        try {
            val waiter = async { coordinator.authorize(provider) }
            started.await()
            coordinator.cancel(provider)
            waiter.join()
            assertTrue(waiter.isCancelled)
            assertTrue(cancelled)
        } finally { owner.cancel() }
    }

    @Test fun unexpectedOAuthExceptionBecomesFailureAndDoesNotCrashItsOwnerScope() = runBlocking {
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val failure = IllegalStateException("Foreground service unavailable")
        var attempts = 0
        val coordinator = OAuthAuthorizations(owner) {
            if (++attempts == 1) throw failure
            Result.success(it)
        }
        try {
            val first = coordinator.authorize(provider)
            assertTrue(first.isFailure)
            assertSame(failure, first.exceptionOrNull())
            assertTrue(owner.isActive)
            assertTrue(coordinator.authorize(provider).isSuccess)
            assertEquals(2, attempts)
        } finally { owner.cancel() }
    }

    @Test fun completedOrFailedAuthorizationDoesNotBlockAnExplicitNewAttempt() = runBlocking {
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var attempts = 0
        val coordinator = OAuthAuthorizations(owner) {
            if (++attempts == 1) Result.failure(IllegalStateException("First attempt failed"))
            else Result.success(it)
        }
        try {
            assertTrue(coordinator.authorize(provider).isFailure)
            assertTrue(coordinator.authorize(provider).isSuccess)
            assertEquals(2, attempts)
        } finally { owner.cancel() }
    }
}
