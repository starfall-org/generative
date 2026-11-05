package org.starfall.generative.shared.subfeatures.auth.logic

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellableContinuation
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationRequest
import net.openid.appauth.AuthorizationResponse
import net.openid.appauth.AuthorizationService
import org.starfall.generative.shared.subfeatures.auth.ui.AppAuthResultActivity
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Registered app-scheme redirects use AppAuth's complete browser/redirect transaction. */
internal object StandardAppAuthAuthorization {
    private data class Pending(val request: AuthorizationRequest, val continuation: CancellableContinuation<AuthorizationResponse>)
    private val pending = ConcurrentHashMap<String, Pending>()
    const val SESSION = "appauth_transaction"

    suspend fun authorize(context: Context, request: AuthorizationRequest): AuthorizationResponse = withContext(Dispatchers.Main.immediate) {
        require(request.redirectUri.scheme == "generative-oauth") { "Redirect scheme is not registered with AppAuth" }
        val service = AuthorizationService(context.applicationContext)
        val id = UUID.randomUUID().toString()
        val resultIntent = Intent(context, AppAuthResultActivity::class.java).setAction(id).putExtra(SESSION, id)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
        val result = PendingIntent.getActivity(context, 0, resultIntent, flags)
        try {
            suspendCancellableCoroutine { continuation ->
                pending[id] = Pending(request, continuation)
                continuation.invokeOnCancellation { pending.remove(id) }
                try { service.performAuthorizationRequest(request, result, result) }
                catch (exception: Exception) {
                    pending.remove(id)
                    if (continuation.isActive) continuation.resumeWithException(exception)
                }
            }
        } finally {
            pending.remove(id)
            result.cancel()
            withContext(NonCancellable + Dispatchers.Main.immediate) { service.dispose() }
        }
    }

    fun complete(intent: Intent) {
        val id = intent.getStringExtra(SESSION) ?: return
        val transaction = pending.remove(id) ?: return
        if (!transaction.continuation.isActive) return
        val response = AuthorizationResponse.fromIntent(intent)
        val exception = AuthorizationException.fromIntent(intent)
        when {
            exception != null -> transaction.continuation.resumeWithException(exception)
            response == null -> transaction.continuation.resumeWithException(IllegalStateException("OAuth authorization was cancelled"))
            response.state != transaction.request.state -> transaction.continuation.resumeWithException(IllegalStateException("OAuth state mismatch"))
            else -> transaction.continuation.resume(response)
        }
    }
}
