package org.starfall.generative.shared.subfeatures.terminal

import android.content.Context
import android.content.Intent
import android.net.Uri

/** Shared contract between connection notifications and the Activity's navigation host. */
internal object RemoteTerminalLaunch {
    private const val ACTION_OPEN = "org.starfall.generative.action.OPEN_TERMINAL"
    private const val EXTRA_MACHINE_ID = "terminal_machine_id"

    fun createIntent(context: Context, machineId: String): Intent? =
        context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
            action = ACTION_OPEN
            data = Uri.fromParts("terminal", machineId, null)
            putExtra(EXTRA_MACHINE_ID, machineId)
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

    fun consumeMachineId(intent: Intent?): String? {
        if (intent?.action != ACTION_OPEN) return null
        val id = intent.getStringExtra(EXTRA_MACHINE_ID)?.takeIf { it.isNotBlank() }
        intent.removeExtra(EXTRA_MACHINE_ID)
        return id
    }
}
