package org.starfall.generative.shared.auth

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import org.starfall.generative.MainActivity
import org.starfall.generative.R

/** Keeps browser callbacks and device-code polling active for the sign-in session. */
class OAuthCallbackService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val expire = Runnable { stopSelf() }
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val requestId = intent?.getStringExtra(EXTRA_START_REQUEST)
        try {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(
                CHANNEL_ID, getString(R.string.notification_oauth_channel_name), NotificationManager.IMPORTANCE_HIGH).apply {
                description = getString(R.string.notification_oauth_channel_description)
            })
            val returnToApp = PendingIntent.getActivity(this, 0,
                Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            startForeground(NOTIFICATION_ID, NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_info_details)
                .setContentTitle(getString(R.string.notification_oauth_title))
                .setContentText(getString(R.string.oauth_browser_pending))
                .setContentIntent(returnToApp)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .build())
            if (wakeLock?.isHeld != true) {
                wakeLock = getSystemService(PowerManager::class.java)
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Generative:OAuth").apply {
                        setReferenceCounted(false)
                        acquire(SESSION_TIMEOUT_MS)
                    }
            }
            handler.removeCallbacks(expire)
            handler.postDelayed(expire, SESSION_TIMEOUT_MS)
            requestId?.let { pendingStarts.remove(it)?.complete(Unit) }
        } catch (e: Exception) {
            // Android may reject foreground service promotion after an activity/browser handoff.
            // This happens asynchronously on the main thread: without catching it here, the
            // whole app crashes before the adapter's Result.failure handler can run.
            Log.e(TAG, "OAuth foreground service failed to start", e)
            requestId?.let { pendingStarts.remove(it)?.completeExceptionally(e) }
            runCatching { stopSelfResult(startId) }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(expire)
        runCatching { wakeLock?.let { if (it.isHeld) it.release() } }
            .onFailure { Log.w(TAG, "OAuth wake lock cleanup failed", it) }
        wakeLock = null

        // Cleanup must never crash the main thread if the system already denied foreground mode.
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
            .onFailure { Log.w(TAG, "OAuth foreground cleanup failed", it) }
        runCatching { getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID) }
            .onFailure { Log.w(TAG, "OAuth notification cleanup failed", it) }

        pendingStarts.values.forEach {
            it.completeExceptionally(IllegalStateException("OAuth foreground service stopped before sign-in started"))
        }
        pendingStarts.clear()
        sessions.set(0)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        // Android retains a channel's original importance; migrate the previous low-priority channel.
        internal const val CHANNEL_ID = "account_oauth_active_v2"
        private const val TAG = "OAuthCallbackService"
        private const val EXTRA_START_REQUEST = "oauth_start_request"
        private const val NOTIFICATION_ID = 4557
        private const val SESSION_TIMEOUT_MS = 16 * 60_000L
        private const val START_TIMEOUT_MS = 10_000L
        private val sessions = AtomicInteger(0)
        private val pendingStarts = ConcurrentHashMap<String, CompletableDeferred<Unit>>()

        private fun start(context: Context): Pair<String, CompletableDeferred<Unit>> {
            val requestId = UUID.randomUUID().toString()
            val ready = CompletableDeferred<Unit>()
            pendingStarts[requestId] = ready
            sessions.incrementAndGet()
            try {
                ContextCompat.startForegroundService(context, Intent(context, OAuthCallbackService::class.java)
                    .putExtra(EXTRA_START_REQUEST, requestId))
            } catch (e: Exception) {
                pendingStarts.remove(requestId)
                sessions.updateAndGet { (it - 1).coerceAtLeast(0) }
                throw e
            }
            return requestId to ready
        }

        private fun stop(context: Context, requestId: String) {
            pendingStarts.remove(requestId)
            if (sessions.updateAndGet { (it - 1).coerceAtLeast(0) } == 0) {
                context.stopService(Intent(context, OAuthCallbackService::class.java))
            }
        }

        internal suspend fun <T> keepAlive(context: Context, block: suspend () -> T): T {
            val (requestId, ready) = start(context)
            try {
                // Do not open the browser until the service has actually entered foreground.
                // Promotion errors are returned to OAuth callers instead of crashing the process.
                withTimeoutOrNull(START_TIMEOUT_MS) { ready.await() }
                    ?: error("OAuth foreground service did not start within ${START_TIMEOUT_MS / 1000} seconds")
                return OAuthBrowser.withSession(block)
            } finally {
                stop(context, requestId)
            }
        }
    }
}
