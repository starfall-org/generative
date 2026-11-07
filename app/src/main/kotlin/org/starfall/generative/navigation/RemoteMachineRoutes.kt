package org.starfall.generative.navigation

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.navArgument
import org.starfall.generative.R
import org.starfall.generative.features.machines.details.ui.MachineDetailsScreen
import org.starfall.generative.features.machines.details.ui.RemoteMachineSettings
import org.starfall.generative.features.machines.list.ui.PortForwardDialog
import org.starfall.generative.features.machines.list.ui.RemoteMachinesScreen
import org.starfall.generative.features.machines.shared.logic.RemoteMachinesViewModel
import org.starfall.generative.features.machines.shared.ui.terminal.RemoteTerminalScreen
import org.starfall.generative.shared.model.RemoteMachine
import org.starfall.generative.shared.model.RemoteMachineState
import org.starfall.generative.shared.ui.LoadingIndicator
import org.starfall.generative.shared.ui.screens.RetainedScreenContent

internal data class MachineEditorPage(val machine: RemoteMachine, val isNew: Boolean)
internal enum class RemoteMachineSettingsPage { SETTINGS }

internal fun RetainedNavGraphBuilder.remoteMachineRoutes(
    navController: NavHostController,
    remoteMachinesViewModel: RemoteMachinesViewModel,
    remoteMachineState: () -> RemoteMachineState,
    remoteMachinePage: MutableState<Any?>,
    openTerminal: (RemoteMachine) -> Unit
) {
    composable(
        "terminal/{machineId}",
        arguments = listOf(navArgument("machineId") { type = NavType.StringType })
    ) { entry ->
        val machineId = entry.arguments?.getString("machineId")
        val machine = remoteMachineState().machines.firstOrNull { it.id == machineId }
        if (machine != null) {
            key(machine.id) {
                RemoteTerminalScreen(
                    machine = machine,
                    openTerminal = { selectedMachine, askSecret ->
                        remoteMachinesViewModel.openTerminal(selectedMachine, askSecret = askSecret)
                    },
                    disconnect = remoteMachinesViewModel::disconnect,
                    onDismiss = { navController.popBackStack() }
                )
            }
        } else {
            // Wait for stored machines to load on a cold notification launch.
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                LoadingIndicator()
            }
        }
    }

    composable(AppDestination.REMOTE_MACHINES.route) {
        val context = LocalContext.current
        // Sub-pages are transient: never restore an editor/settings page on re-entry.
        DisposableEffect(Unit) {
            onDispose {
                remoteMachinePage.value = null
            }
        }
        RetainedScreenContent(
            editor = remoteMachinePage.value
        ) { selected ->
            when {
                selected is MachineEditorPage -> MachineDetailsScreen(
                    initial = selected.machine,
                    state = remoteMachineState(),
                    onSave = {
                        remoteMachinesViewModel.saveMachine(it)
                        remoteMachinePage.value = null
                    },
                    onBack = { remoteMachinePage.value = null },
                    onRefreshIp = { remoteMachinesViewModel.refreshIp(it) },
                    onFetchUsername = { remoteMachinesViewModel.fetchGCloudUsername(it) },
                    onAuthorizeOAuth = { remoteMachinesViewModel.authorize(it) },
                    onStoreAndroidOAuthToken = { client, token ->
                        remoteMachinesViewModel.storeAndroidOAuthToken(
                            client,
                            token
                        )
                    }
                )

                selected == RemoteMachineSettingsPage.SETTINGS -> RemoteMachineSettings(
                    state = remoteMachineState(),
                    viewModel = remoteMachinesViewModel,
                    onBack = { remoteMachinePage.value = null }
                )

                else -> RemoteMachinesScreen(
                    viewModel = remoteMachinesViewModel,
                    onBack = { navController.popBackStack() },
                    onOpenEditor = { machine, isNew ->
                        remoteMachinePage.value = MachineEditorPage(machine, isNew)
                    },
                    onOpenSettings = { remoteMachinePage.value = RemoteMachineSettingsPage.SETTINGS },
                    onOpenTerminal = { machine ->
                        openTerminal(machine)
                    },
                    onOpenFiles = { machine -> ExplorerActivity.open(context, machineId = machine.id) }
                )
            }
        }
    }
}

@Composable
internal fun NavigationPortForwardDialog(
    selectedMachine: RemoteMachine?,
    remoteMachineState: RemoteMachineState,
    remoteMachinesViewModel: RemoteMachinesViewModel,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val localizedResources = LocalResources.current
    selectedMachine?.let { selected ->
        val machine = remoteMachineState.machines.firstOrNull { it.id == selected.id }
        if (machine == null) {
            LaunchedEffect(selected.id) { onDismiss() }
        } else {
            val activeForwards by remoteMachinesViewModel.forwardedPorts.collectAsStateWithLifecycle()
            PortForwardDialog(
                machine = machine,
                activeForwards = activeForwards[machine.id].orEmpty(),
                onDismiss = { onDismiss() },
                onStop = {
                    onDismiss()
                    remoteMachinesViewModel.stopPortForward(machine.id) { result ->
                        val message = result.fold(
                            onSuccess = { localizedResources.getString(R.string.remote_server_port_forward_stopped) },
                            onFailure = { it.message ?: it.toString() }
                        )
                        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                    }
                },
                onSaveAndStart = { updated ->
                    onDismiss()
                    remoteMachinesViewModel.saveMachineAndStartPortForward(updated) { result ->
                        val message = result.fold(
                            onSuccess = { localizedResources.getString(R.string.remote_server_port_forward_started) },
                            onFailure = { it.message ?: it.toString() }
                        )
                        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                    }
                }
            )
        }
    }
}
