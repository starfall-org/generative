package com.jcraft.jsch

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.starfall.generative.integrations.remote.ActivePortForwards
import org.starfall.generative.integrations.remote.RemoteMachineConnectionManager
import org.starfall.generative.shared.model.RemoteMachine

class RuntimeSshLeaseTest {
    @Test fun closingRuntimeDisconnectsWhenTheLastTaskReleasesTheSharedSshSession() = runBlocking {
        val session = TestSession()
        val manager = manager(session)
        val machine = RemoteMachine("shared", "Shared")
        val runtime = manager.openExecStream(machine, "runtime", disconnectWhenIdle = true)
        val task = manager.openExecStream(machine, "task", disconnectWhenIdle = false)
        runtime.close()
        val nextTask = manager.openExecStream(machine, "next task", disconnectWhenIdle = false)

        task.close()
        assertTrue(session.isConnected)
        nextTask.close()
        withTimeout(5000) { session.disconnected.await() }
        assertFalse(manager.isConnected(machine.id))
        assertEquals(3, session.channels)
    }

    @Test fun closingRuntimeLeavesPortForwardingAliveUntilItIsStopped() = runBlocking {
        val session = TestSession()
        val manager = manager(session)
        val machine = RemoteMachine("forwarded", "Forwarded")
        val runtime = manager.openExecStream(machine, "runtime", disconnectWhenIdle = true)
        manager.startPortForward(machine)
        runtime.close()

        assertTrue(session.isConnected)
        assertEquals(listOf("forward"), manager.forwardedPorts.value[machine.id])
        assertTrue(manager.stopPortForward(machine.id))
        withTimeout(5000) { session.disconnected.await() }
        assertFalse(manager.isConnected(machine.id))
    }

    private fun manager(session: TestSession) = RemoteMachineConnectionManager(
        openSessionCallback = { _, _, _ -> session },
        startForwards = { _, _ -> ActivePortForwards(listOf("forward"), emptyList()) }
    )

    private class TestSession : Session(JSch(), "test", "localhost", 22) {
        val disconnected = CompletableDeferred<Unit>()
        var channels = 0
            private set

        override fun isConnected(): Boolean = !disconnected.isCompleted

        override fun disconnect() {
            disconnected.complete(Unit)
        }

        override fun openChannel(type: String): Channel {
            require(type == "exec")
            channels++
            return object : ChannelExec() {
                override fun connect(timeout: Int) = Unit
                override fun disconnect() = Unit
                override fun getInputStream() = ByteArrayInputStream(byteArrayOf())
                override fun getOutputStream() = ByteArrayOutputStream()
            }
        }
    }
}
