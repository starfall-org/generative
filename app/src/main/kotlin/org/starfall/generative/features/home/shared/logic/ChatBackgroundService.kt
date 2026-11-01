package org.starfall.generative.features.home.shared.logic

import android.R
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import android.content.pm.ServiceInfo
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.starfall.generative.MainActivity
import org.starfall.generative.shared.model.ConversationSummaryProgress

/**
 * Foreground service that keeps the process, CPU and Wi-Fi radio alive while a response is
 * generating, so streaming/tool connections are not cut ("Software caused connection abort")
 * when the app is sent to the background or the screen turns off. While a conversation summary
 * runs, the same notification shows its progress.
 */
class ChatBackgroundService : Service() {

    companion object {
        private const val CHANNEL_ID = "chat_background_channel"
        private const val NOTIFICATION_ID = 4556
        // Safety net only; the service is stopped (and locks released) when generation ends.
        private const val LOCK_TIMEOUT_MS = 6 * 60 * 60_000L

        private val summaryState = MutableStateFlow<ConversationSummaryProgress?>(null)

        /** Progress of the running conversation summary, or null when none is running. */
        fun showSummaryProgress(progress: ConversationSummaryProgress?) {
            summaryState.value = progress
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var inForeground = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        serviceScope.launch {
            summaryState.collect { progress ->
                // Only a running summary needs updates; when it ends the notification is rebuilt by the
                // next start command, or the service stops. Posting before startForeground would also
                // create an ordinary notification instead of the foreground one.
                if (progress != null && inForeground) {
                    runCatching {
                        getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, createNotification())
                    }
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = createNotification()
        // Pass the declared type explicitly. On Android 14+ this is required for a
        // special-use foreground service and also makes the service notification a
        // real FGS notification instead of an ordinary status-bar notification.
        try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } catch (error: Exception) {
            // A device may reject a foreground-service type or permission. Do not let
            // that asynchronous service callback become an application crash.
            Log.e("ChatBackgroundService", "Unable to enter foreground", error)
            stopSelfResult(startId)
            return START_NOT_STICKY
        }
        inForeground = true
        acquireLocks()
        // Generation belongs to the current process. Restarting an empty service
        // after process death would show "AI is thinking" with no live generation.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        inForeground = false
        serviceScope.cancel()
        releaseLocks()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun acquireLocks() {
        // Renewed on every start command so a long generation never outlives the timeout.
        // Locks are an optimization only: a device/vendor is allowed to reject either
        // one, and that must never take down the foreground service or the app process.
        runCatching {
            if (wakeLock == null) {
                wakeLock = getSystemService(PowerManager::class.java)
                    ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Generative:Chat")
                    ?.apply { setReferenceCounted(false) }
            }
            wakeLock?.acquire(LOCK_TIMEOUT_MS)
        }.onFailure {
            wakeLock = null
        }
        runCatching {
            if (wifiLock == null) {
                @Suppress("DEPRECATION")
                val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                else WifiManager.WIFI_MODE_FULL_HIGH_PERF
                wifiLock = applicationContext.getSystemService(WifiManager::class.java)
                    ?.createWifiLock(mode, "Generative:Chat")
                    ?.apply { setReferenceCounted(false) }
            }
            if (wifiLock?.isHeld != true) wifiLock?.acquire()
        }.onFailure {
            wifiLock = null
        }
    }

    private fun releaseLocks() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        runCatching { wifiLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
        wifiLock = null
    }

    private fun createNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        )

        val summary = summaryState.value
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(
                if (summary != null) org.starfall.generative.R.string.notification_summarizing_conversation
                else org.starfall.generative.R.string.notification_ai_thinking
            ))
            .setContentText(summary?.label ?: getString(org.starfall.generative.R.string.notification_generation_text))
            .setSmallIcon(R.drawable.ic_menu_info_details)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setContentIntent(pendingIntent)
        if (summary != null) {
            builder.setProgress(100, (summary.progress.coerceIn(0f, 1f) * 100).roundToInt(), false)
        }
        return builder.build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    getString(org.starfall.generative.R.string.notification_generation_channel_name),
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = getString(org.starfall.generative.R.string.notification_generation_channel_description)
                }
                manager.createNotificationChannel(channel)
            }
        }
    }
}
