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
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.util.concurrent.atomic.AtomicInteger
import org.starfall.generative.MainActivity
import org.starfall.generative.R

/** Keeps browser callbacks and device-code polling active for the sign-in session. */
class OAuthCallbackService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val expire = Runnable { stopSelf() }
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
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
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(expire)
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null

        // OAuth is no longer active, so do not leave the foreground-service
        // notification behind as a stale ongoing notification.
        stopForeground(STOP_FOREGROUND_REMOVE)
        getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)

        sessions.set(0)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        // Android retains a channel's original importance; migrate the previous low-priority channel.
        internal const val CHANNEL_ID = "account_oauth_active_v2"
        private const val NOTIFICATION_ID = 4557
        private const val SESSION_TIMEOUT_MS = 16 * 60_000L
        private val sessions = AtomicInteger(0)

        fun start(context: Context) {
            sessions.incrementAndGet()
            try {
                ContextCompat.startForegroundService(context, Intent(context, OAuthCallbackService::class.java))
            } catch (e: Exception) {
                sessions.updateAndGet { (it - 1).coerceAtLeast(0) }
                throw e
            }
        }

        fun stop(context: Context) {
            if (sessions.updateAndGet { (it - 1).coerceAtLeast(0) } == 0) {
                context.stopService(Intent(context, OAuthCallbackService::class.java))
            }
        }

        internal suspend fun <T> keepAlive(context: Context, block: suspend () -> T): T {
            start(context)
            return try { OAuthBrowser.withSession(block) } finally { stop(context) }
        }
    }
}
