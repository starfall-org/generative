package org.starfall.generative.shared.auth

import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * Opens the user's browser directly. The OAuth callback listener and foreground service own
 * the pending login; there is no intermediary Activity or browser task to close afterwards.
 */
internal object OAuthBrowser {
    private class Session : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<Session>
    }

    private val browserLock = Mutex()

    suspend fun <T> withSession(block: suspend () -> T): T {
        if (currentCoroutineContext()[Session] != null) return block()
        return browserLock.withLock { withContext(Session()) { block() } }
    }

    suspend fun open(context: Context, url: String) = withContext(Dispatchers.Main) {
        check(currentCoroutineContext()[Session] != null) { "OAuth browser requires an authorization session" }
        context.startActivity(externalIntent(url))
    }

    /** Only HTTPS OAuth URLs may be opened; callback endpoints are handled by their listeners. */
    internal fun externalIntent(url: String): Intent {
        val uri = Uri.parse(url)
        require(uri.scheme == "https" && !uri.host.isNullOrBlank()) { "OAuth URL must be HTTPS" }
        return Intent(Intent.ACTION_VIEW, uri)
            .addCategory(Intent.CATEGORY_BROWSABLE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
