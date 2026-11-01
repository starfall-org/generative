package org.starfall.generative.integrations.remote

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import org.starfall.generative.R
import org.starfall.generative.shared.model.RemoteMachine
import org.starfall.generative.shared.terminal.RemoteTerminalLaunch

/**
 * Foreground service that shows one ongoing notification per machine that holds a persistent SSH
 * connection, each with a Disconnect action. It also keeps the process alive, which port forwards
 * need: they live and die with the SSH session.
 *
 * One machine's notification is the foreground-service notification; the others are ordinary
 * ongoing notifications. The service stops itself when the last connection closes.
 */
class RemoteConnectionService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observeJob: Job? = null
    private var foregroundMachineId: String? = null
    private var lastStartId = 0
    /** Notification ids this service has posted, so removal never depends on the system's active list alone. */
    private val postedIds = mutableSetOf<Int>()

    /** Only connection state belongs in the notification; forwarding status is managed separately. */
    private class Connected(val machine: RemoteMachine)

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        val machineService = (application as? RemoteMachineServiceOwner)?.remoteMachineService
        if (machineService == null) {
            // Nothing to show, but a started foreground service must call startForeground() before it stops.
            enterForeground(PLACEHOLDER_NOTIFICATION_ID, placeholderNotification())
            stopSelf(startId)
            return START_NOT_STICKY
        }
        // Every start request must reach startForeground() (even a repeated one, even when the last
        // connection just closed), so the foreground notification is re-posted here, not only on changes.
        foregroundMachineId = null
        val connected = connectedMachines(machineService)
        if (connected.isEmpty()) enterForeground(PLACEHOLDER_NOTIFICATION_ID, placeholderNotification())
        render(connected)
        if (observeJob == null) {
            observeJob = scope.launch {
                combine(
                    machineService.connectedMachineIds,
                    machineService.state
                ) { ids, state ->
                    state.machines.filter { it.id in ids }.map(::Connected)
                }.collect { render(it) }
            }
        }
        // Connections belong to this process; after process death there is nothing to restore.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        observeJob?.cancel()
        scope.cancel()
        cancelAllNotifications(NotificationManagerCompat.from(this))
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun connectedMachines(service: RemoteMachineService): List<Connected> {
        val ids = service.connectedMachineIds.value
        return service.state.value.machines.filter { it.id in ids }.map(::Connected)
    }

    private fun cancelAllNotifications(manager: NotificationManagerCompat) {
        (ownNotificationIds() + postedIds).toSet().forEach { manager.cancel(it) }
        postedIds.clear()
    }

    @SuppressLint("MissingPermission") // Without POST_NOTIFICATIONS the notification is simply not shown.
    private fun render(connected: List<Connected>) {
        val manager = NotificationManagerCompat.from(this)
        if (connected.isEmpty()) {
            cancelAllNotifications(manager)
            stopForeground(STOP_FOREGROUND_REMOVE)
            foregroundMachineId = null
            // Only stops when no newer start request is pending, so a connection that opens right now keeps it alive.
            stopSelf(lastStartId)
            return
        }
        val wanted = connected.map { notificationId(it.machine.id) }.toSet()
        foregroundMachineId?.takeIf { id -> connected.none { it.machine.id == id } }?.let { gone ->
            // Detach first so cancelling the old anchor does not tear down the whole service.
            stopForeground(STOP_FOREGROUND_DETACH)
            manager.cancel(notificationId(gone))
            postedIds.remove(notificationId(gone))
            foregroundMachineId = null
        }
        // Also removes notifications that a previous process left behind (the app was killed while connected).
        (ownNotificationIds() + postedIds).toSet().filter { it !in wanted }.forEach {
            manager.cancel(it)
            postedIds.remove(it)
        }
        val anchor = foregroundMachineId ?: connected.first().machine.id
        connected.forEach { entry ->
            val id = notificationId(entry.machine.id)
            val notification = notificationFor(entry)
            if (entry.machine.id == anchor && foregroundMachineId == null) {
                if (enterForeground(id, notification)) foregroundMachineId = entry.machine.id
            } else {
                manager.notify(id, notification)
            }
            postedIds += id
        }
    }

    /** Returns false when the device rejected the foreground start (the service then stops). */
    private fun enterForeground(id: Int, notification: Notification): Boolean = try {
        // The type must be passed explicitly on Android 14+ for a special-use foreground service.
        ServiceCompat.startForeground(this, id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        true
    } catch (error: Exception) {
        // Background-start restrictions or a vendor rule. Never let this become an app crash.
        Log.e(TAG, "Unable to enter foreground", error)
        stopSelf()
        false
    }

    private fun notificationFor(entry: Connected): Notification {
        val machine = entry.machine
        val name = machine.name.ifBlank { machine.displayHost }
        val text = getString(R.string.remote_connection_notification_text)
        val disconnect = PendingIntent.getBroadcast(
            this,
            notificationId(machine.id),
            Intent(this, RemoteConnectionActionReceiver::class.java)
                .setAction(ACTION_DISCONNECT)
                // The data URI keeps each machine's PendingIntent distinct even on a requestCode clash.
                .setData(Uri.fromParts("machine", machine.id, null))
                .putExtra(EXTRA_MACHINE_ID, machine.id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return baseNotification()
            .setContentIntent(RemoteTerminalLaunch.createIntent(this, machine.id)?.let { launch ->
                PendingIntent.getActivity(this, notificationId(machine.id), launch,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            })
            .setContentTitle(getString(R.string.remote_connection_notification_title, name))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .addAction(0, getString(R.string.remote_server_disconnect), disconnect)
            .build()
    }

    private fun placeholderNotification(): Notification =
        baseNotification().setContentTitle(getString(R.string.remote_connection_channel_name)).build()

    private fun baseNotification(): NotificationCompat.Builder {
        // Resolved at runtime so this layer does not depend on the app's activity class.
        val launch = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val open = launch?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_terminal_material_24)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(open)
    }

    /** Ids of this service's notifications that are currently posted (a previous process included). */
    private fun ownNotificationIds(): List<Int> =
        getSystemService(NotificationManager::class.java)?.activeNotifications.orEmpty()
            .filter { it.notification.channelId == CHANNEL_ID }
            .map { it.id }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.remote_connection_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = getString(R.string.remote_connection_channel_description) }
        )
    }

    companion object {
        const val ACTION_DISCONNECT = "org.starfall.generative.action.REMOTE_DISCONNECT"
        const val EXTRA_MACHINE_ID = "machine_id"
        private const val TAG = "RemoteConnectionService"
        private const val CHANNEL_ID = "remote_connection_channel"
        private const val PLACEHOLDER_NOTIFICATION_ID = 4557
        private const val NOTIFICATION_ID_BASE = 100_000

        /** Stable per machine, so a refreshed notification replaces its previous one. */
        internal fun notificationId(machineId: String): Int = NOTIFICATION_ID_BASE + (machineId.hashCode() and 0x7FFFFF)

        /**
         * Starts the service, or re-delivers the start to the running one. It stops itself once nothing is
         * connected. Calling it while it runs is cheap and avoids racing a service that is about to stop.
         */
        fun start(context: Context) {
            runCatching {
                ContextCompat.startForegroundService(context, Intent(context, RemoteConnectionService::class.java))
            }.onFailure { Log.w(TAG, "Unable to start the connection notification: ${it.message}") }
        }

        /** Drops a machine's notification at once; the service also removes it when the session closes. */
        fun cancelNotification(context: Context, machineId: String) =
            NotificationManagerCompat.from(context).cancel(notificationId(machineId))
    }
}
