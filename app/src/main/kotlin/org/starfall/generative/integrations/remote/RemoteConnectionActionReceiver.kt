package org.starfall.generative.integrations.remote

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Handles the Disconnect button of a [RemoteConnectionService] notification. */
class RemoteConnectionActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != RemoteConnectionService.ACTION_DISCONNECT) return
        val machineId = intent.getStringExtra(RemoteConnectionService.EXTRA_MACHINE_ID) ?: return
        val owner = context.applicationContext as? RemoteMachineServiceOwner ?: return
        // Non-blocking: the connection closes on the manager's IO scope, then the service drops the notification.
        owner.remoteMachineService.disconnectMachine(machineId)
    }
}
