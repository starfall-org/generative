package org.starfall.generative

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.starfall.generative.database.preferences.ToolSettingsStore
import org.starfall.generative.features.home.shared.logic.ServersideCoordinator
import org.starfall.generative.integrations.remote.RemoteCommandResult
import org.starfall.generative.integrations.serverside.ServersideHandshakeException
import org.starfall.generative.shared.model.RemoteMachine
import org.starfall.generative.shared.model.ToolSettings

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = LibraryTestApplication::class)
class ServersideCoordinatorTest {
    private val machine = RemoteMachine("a", "Alpha")
    private fun invitation() = RemoteCommandResult(
        """{"protocol":4,"endpoint":"host:7090","fingerprint":"${"a".repeat(64)}","ticket":"ticket","expires":${System.currentTimeMillis() / 1000 + 120}}"""
    )
    private suspend fun awaitIdle(coordinator: ServersideCoordinator) {
        withTimeout(5000) {
            // The lazy toggle job is dispatched after the call returns.
            yield()
            while (coordinator.pending.value.isNotEmpty()) delay(10)
        }
    }

    @Test fun enablesOnlyAfterHandshakeAndMissingCliDisablesOnSend() = runBlocking {
        val store = ToolSettingsStore(ApplicationProvider.getApplicationContext<Context>())
        store.replace(ToolSettings(selectedMachineId = machine.id))
        val gate = CompletableDeferred<RemoteCommandResult>()
        val coordinator = ServersideCoordinator(this, store, { gate.await() }, {
            RemoteCommandResult("GENERATIVE_SERVERSIDE_MISSING", exitCode = 73)
        })
        coordinator.setEnabled(machine, true)
        yield()
        assertFalse(store.snapshot().serversideMachines[machine.id] == true)
        gate.complete(invitation())
        awaitIdle(coordinator)
        assertTrue(store.snapshot().serversideMachines[machine.id] == true)
        try {
            coordinator.checkBeforeSend(machine)
            fail("Missing CLI must stop provider execution")
        } catch (_: ServersideHandshakeException) { }
        assertEquals(false, store.snapshot().serversideMachines[machine.id])
        assertTrue(coordinator.failure.value?.missing == true)
        coordinator.dismissError()
        assertNull(coordinator.failure.value)
    }

    @Test fun daemonFailureShowsErrorAndLeavesSwitchOff() = runBlocking {
        val store = ToolSettingsStore(ApplicationProvider.getApplicationContext<Context>())
        store.replace(ToolSettings(selectedMachineId = machine.id))
        val coordinator = ServersideCoordinator(this, store,
            { RemoteCommandResult("", "connect to running daemon: refused", 1) }, { invitation() })
        coordinator.setEnabled(machine, true)
        awaitIdle(coordinator)
        assertEquals(false, store.snapshot().serversideMachines[machine.id])
        assertEquals(false, coordinator.failure.value?.missing)
    }

    @Test fun turningOffCancelsHandshakeAndPreventsLateEnable() = runBlocking {
        val store = ToolSettingsStore(ApplicationProvider.getApplicationContext<Context>())
        store.replace(ToolSettings(selectedMachineId = machine.id))
        val gate = CompletableDeferred<RemoteCommandResult>()
        val coordinator = ServersideCoordinator(this, store, { gate.await() }, { invitation() })
        coordinator.setEnabled(machine, true)
        yield()
        coordinator.setEnabled(machine, false)
        gate.complete(invitation())
        awaitIdle(coordinator)
        assertEquals(false, store.snapshot().serversideMachines[machine.id])
        assertNull(coordinator.failure.value)
    }
    @Test fun switchingMachinesCancelsBootstrapAndDoesNotEnableEitherMachine() = runBlocking {
        val store = ToolSettingsStore(ApplicationProvider.getApplicationContext<Context>())
        store.replace(ToolSettings(selectedMachineId = machine.id))
        val gate = CompletableDeferred<RemoteCommandResult>()
        val coordinator = ServersideCoordinator(this, store, { gate.await() }, { invitation() })
        coordinator.setEnabled(machine, true)
        yield()
        coordinator.selectionChanged("b")
        store.update { it.copy(selectedMachineId = "b") }
        gate.complete(invitation())
        awaitIdle(coordinator)
        assertFalse(store.snapshot().serversideMachines.values.any { it })
        assertNull(coordinator.failure.value)
    }

    @Test fun sendTransportFailureDoesNotDisableOptIn() = runBlocking {
        val store = ToolSettingsStore(ApplicationProvider.getApplicationContext<Context>())
        store.replace(ToolSettings(selectedMachineId = machine.id, serversideMachines = mapOf(machine.id to true)))
        val coordinator = ServersideCoordinator(this, store, { invitation() }, { error("SSH disconnected") })
        try {
            coordinator.checkBeforeSend(machine)
            fail("Transport error must stop this request")
        } catch (_: ServersideHandshakeException) { }
        assertEquals(true, store.snapshot().serversideMachines[machine.id])
        assertEquals(false, coordinator.failure.value?.missing)
    }

}
