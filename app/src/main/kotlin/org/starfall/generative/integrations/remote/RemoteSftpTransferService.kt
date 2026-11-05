package org.starfall.generative.integrations.remote

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.IBinder
import android.os.SystemClock
import android.provider.OpenableColumns
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import java.io.FileNotFoundException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.starfall.generative.R

/**
 * Runs SFTP uploads/downloads outside the Explorer screen's lifecycle. A single foreground
 * notification tracks the current file, and later selections are queued until it finishes.
 */
class RemoteSftpTransferService : Service() {
    private data class Transfer(
        val machineId: String,
        val path: String,
        val uri: Uri,
        val upload: Boolean,
        val name: String = ""
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val pending = ArrayDeque<Transfer>()
    private val manager by lazy { NotificationManagerCompat.from(this) }
    private var running = false
    private var lastStartId = 0
    private var processed = 0
    private var failed = 0
    private var lastNotifyAt = 0L
    private var latestNotification: Notification? = null
    private val changedMachines = mutableSetOf<String>()

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.sftp_transfer_channel),
                NotificationManager.IMPORTANCE_LOW)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        val machineId = intent?.getStringExtra(EXTRA_MACHINE_ID)
        val path = intent?.getStringExtra(EXTRA_PATH)
        val tasks = if (intent != null && machineId != null && path != null) {
            when (intent.action) {
                ACTION_UPLOAD -> intent.getStringArrayListExtra(EXTRA_URIS).orEmpty()
                    .map { Transfer(machineId, path, it.toUri(), upload = true) }
                ACTION_DOWNLOAD -> listOfNotNull(
                    intent.getStringExtra(EXTRA_URI)?.let {
                        Transfer(machineId, path, it.toUri(), upload = false,
                            name = intent.getStringExtra(EXTRA_NAME).orEmpty())
                    })
                else -> emptyList()
            }
        } else emptyList()

        pending.addAll(tasks)
        // Re-enter foreground for every start request, including requests queued during a transfer.
        try {
            enterForeground(latestNotification ?: notification(
                getString(R.string.sftp_transfer_preparing), "", null, ongoing = true))
        } catch (error: Exception) {
            Log.e(TAG, "Could not start SFTP foreground transfer", error)
            if (!running) {
                pending.clear()
                stopSelfResult(startId)
            }
            return START_NOT_STICKY
        }
        if (!running) {
            if (pending.isEmpty()) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelfResult(startId)
            } else {
                running = true
                scope.launch { processQueue() }
            }
        }
        return START_NOT_STICKY
    }

    private suspend fun processQueue() {
        val service = runCatching {
            (application as? RemoteMachineServiceOwner)?.remoteMachineService
        }.getOrNull()
        if (service == null) {
            failed = pending.size
            processed = failed
            pending.clear()
            finishQueue()
            return
        }
        val remote = RemoteSftpExplorer(service)
        while (pending.isNotEmpty()) {
            val transfer = pending.removeFirst()
            val index = processed + 1
            val total = index + pending.size
            var name = transfer.name.ifBlank { transfer.uri.lastPathSegment.orEmpty() }
            var size = -1L
            try {
                if (transfer.upload) {
                    val details = withContext(Dispatchers.IO) {
                        contentResolver.query(transfer.uri,
                            arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                            null, null, null)?.use { cursor ->
                            if (!cursor.moveToFirst()) null else {
                                val titleIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                                val title = if (titleIndex >= 0) cursor.getString(titleIndex) else null
                                val length = if (sizeIndex < 0 || cursor.isNull(sizeIndex)) -1L
                                    else cursor.getLong(sizeIndex)
                                title to length
                            }
                        }
                    } ?: throw FileNotFoundException("Cannot read selected document")
                    name = details.first?.takeIf { it.isNotBlank() }
                        ?: throw FileNotFoundException("Missing document name")
                    size = details.second
                }
                val title = getString(
                    if (transfer.upload) R.string.sftp_transfer_uploading
                    else R.string.sftp_transfer_downloading, name)
                val label = getString(R.string.sftp_transfer_file_number, index, total)
                lastNotifyAt = 0L
                updateProgress(title, label, 0L, size, transfer.upload)
                withContext(Dispatchers.IO) {
                    if (transfer.upload) {
                        contentResolver.openInputStream(transfer.uri)?.use { input ->
                            remote.upload(transfer.machineId, transfer.path, name, input) { bytes ->
                                updateProgress(title, label, bytes, size, true)
                            }
                        } ?: throw FileNotFoundException("Cannot read selected document")
                    } else {
                        contentResolver.openOutputStream(transfer.uri, "wt")?.use { output ->
                            remote.download(transfer.machineId, transfer.path, output) { bytes, length ->
                                updateProgress(title, label, bytes, length, false)
                            }
                        } ?: throw FileNotFoundException("Cannot write selected document")
                    }
                }
                if (transfer.upload) changedMachines += transfer.machineId
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                failed++
                Log.w(TAG, "SFTP transfer failed", error)
            }
            processed++
        }
        finishQueue()
    }

    @SuppressLint("MissingPermission") // A foreground service still works if notifications are denied.
    private fun updateProgress(
        title: String, label: String, bytes: Long, total: Long, upload: Boolean
    ) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastNotifyAt < 250 && (total <= 0 || bytes != total)) return
        lastNotifyAt = now
        val progress = sftpTransferPercent(bytes, total)
        val detail = if (progress == null) label
            else getString(R.string.sftp_transfer_file_progress, label, progress)
        val shown = notification(title, detail, progress, ongoing = true, upload = upload)
        latestNotification = shown
        runCatching { manager.notify(NOTIFICATION_ID, shown) }
            .onFailure { Log.w(TAG, "Unable to show SFTP progress", it) }
    }

    @SuppressLint("MissingPermission")
    private fun finishQueue() {
        changedMachines.forEach { completedUploads.tryEmit(it) }
        changedMachines.clear()
        val title = if (failed == 0) getString(R.string.sftp_transfer_complete)
            else getString(R.string.sftp_transfer_failed)
        val summary = getString(R.string.sftp_transfer_result, processed - failed, processed)
        stopForeground(STOP_FOREGROUND_REMOVE)
        runCatching {
            manager.notify(NOTIFICATION_ID, notification(title, summary, null, ongoing = false))
        }.onFailure { Log.w(TAG, "Unable to show SFTP result", it) }
        latestNotification = null
        running = false
        processed = 0
        failed = 0
        stopSelfResult(lastStartId)
    }

    // The dataSync flag is an inlined integer; ServiceCompat handles pre-29 platforms.
    @SuppressLint("InlinedApi")
    private fun enterForeground(notification: Notification) {
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    private fun notification(
        title: String, text: String, percent: Int?, ongoing: Boolean, upload: Boolean? = null
    ): Notification {
        val open = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 0,
                it.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(when (upload) {
                true -> android.R.drawable.stat_sys_upload
                false -> android.R.drawable.stat_sys_download
                null -> R.drawable.ic_terminal_material_24
            })
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setSilent(true)
            .setOngoing(ongoing)
            .setAutoCancel(!ongoing)
            .apply { if (ongoing) setProgress(100, percent ?: 0, percent == null) }
            .build()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "RemoteSftpTransfer"
        private const val CHANNEL_ID = "sftp_file_transfers"
        private const val NOTIFICATION_ID = 7205
        private const val ACTION_UPLOAD = "org.starfall.generative.SFTP_UPLOAD"
        private const val ACTION_DOWNLOAD = "org.starfall.generative.SFTP_DOWNLOAD"
        private const val EXTRA_MACHINE_ID = "machine_id"
        private const val EXTRA_PATH = "path"
        private const val EXTRA_URIS = "uris"
        private const val EXTRA_URI = "uri"
        private const val EXTRA_NAME = "name"

        private val completedUploads = MutableSharedFlow<String>(extraBufferCapacity = 16)
        val uploadsCompleted = completedUploads.asSharedFlow()

        fun upload(context: Context, machineId: String, parent: String, uris: List<Uri>) {
            if (uris.isEmpty()) return
            val intent = Intent(context, RemoteSftpTransferService::class.java)
                .setAction(ACTION_UPLOAD)
                .putExtra(EXTRA_MACHINE_ID, machineId)
                .putExtra(EXTRA_PATH, parent)
                .putStringArrayListExtra(EXTRA_URIS, ArrayList(uris.map(Uri::toString)))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            intent.clipData = ClipData.newUri(context.contentResolver, "SFTP upload", uris.first()).apply {
                uris.drop(1).forEach { addItem(ClipData.Item(it)) }
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun download(context: Context, machineId: String, path: String, name: String, uri: Uri) {
            val intent = Intent(context, RemoteSftpTransferService::class.java)
                .setAction(ACTION_DOWNLOAD)
                .putExtra(EXTRA_MACHINE_ID, machineId)
                .putExtra(EXTRA_PATH, path)
                .putExtra(EXTRA_NAME, name)
                .putExtra(EXTRA_URI, uri.toString())
                .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            intent.clipData = ClipData.newUri(context.contentResolver, "SFTP download", uri)
            ContextCompat.startForegroundService(context, intent)
        }
    }
}


// Some document providers cannot report a size; show an indeterminate bar in that case.
internal fun sftpTransferPercent(bytes: Long, total: Long): Int? {
    if (total <= 0L) return null
    return ((bytes.coerceAtLeast(0L).toDouble() / total) * 100).toInt().coerceIn(0, 100)
}
