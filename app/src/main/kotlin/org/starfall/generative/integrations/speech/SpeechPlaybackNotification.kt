package org.starfall.generative.integrations.speech

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import org.starfall.generative.R
import java.util.UUID

/** Session-scoped playback notification. Message contents and credentials never enter the notification. */
class SpeechPlaybackNotification(context: Context) {
    private val context = context.applicationContext
    private val manager = NotificationManagerCompat.from(this.context)
    private var stopAction: (() -> Unit)? = null
    private var session: String? = null
    private var registered = false
    private val action = "${this.context.packageName}.STOP_SPEECH"
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == action && intent.getStringExtra("session") == session) stopAction?.invoke()
        }
    }

    init {
        this.context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, this.context.getString(R.string.notification_speech_channel), NotificationManager.IMPORTANCE_LOW)
                .apply { setSound(null, null); enableVibration(false) })
        manager.cancel(NOTIFICATION_ID)
    }

    fun show(serviceName: String, onStop: () -> Unit) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        if (!manager.areNotificationsEnabled()) return
        try {
            if (!registered) {
                ContextCompat.registerReceiver(context, receiver, IntentFilter(action).apply { addDataScheme("generative-tts") }, ContextCompat.RECEIVER_NOT_EXPORTED)
                registered = true
            }
            val token = session ?: UUID.randomUUID().toString().also { session = it }
            stopAction = onStop
            val stop = PendingIntent.getBroadcast(context, 0,
                Intent(action).setPackage(context.packageName).setData(Uri.parse("generative-tts://stop/$token")).putExtra("session", token),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
                PendingIntent.getActivity(context, 0, it.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            }
            manager.notify(NOTIFICATION_ID, NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_lock_silent_mode_off)
                .setContentTitle(context.getString(R.string.notification_speech_playing))
                .setContentText(serviceName)
                .setContentIntent(launch)
                .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOnlyAlertOnce(true).setShowWhen(false).setOngoing(true).setSilent(true)
                .addAction(0, context.getString(R.string.speech_stop), stop).build())
        } catch (_: SecurityException) { hide() }
    }

    fun hide() {
        stopAction = null
        session = null
        manager.cancel(NOTIFICATION_ID)
        if (registered) {
            context.unregisterReceiver(receiver)
            registered = false
        }
    }

    internal companion object {
        const val CHANNEL_ID = "speech_playback"
        const val NOTIFICATION_ID = 7104
    }
}
