package com.jcraft.jsch

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.starfall.generative.integrations.generativeserver.GenerativeServerSshTransport
import org.starfall.generative.integrations.remote.ActivePortForwards
import org.starfall.generative.integrations.remote.RemoteMachineConnectionManager
import org.starfall.generative.integrations.remote.RemoteExecStream
import org.starfall.generative.shared.model.RemoteMachine

class RuntimeSshLeaseTest {
    @Test fun foregroundStartPrecedesSshAndCancellationClearsPendingConnection() = runBlocking {
        val opening = CompletableDeferred<Unit>()
        var foregroundRequested = false
        val manager = RemoteMachineConnectionManager(
            openSessionCallback = { _, _, _ ->
                assertTrue(foregroundRequested)
                opening.complete(Unit)
                awaitCancellation()
            },
            startForwards = { _, _ -> error("Unexpected port forwarding") },
            onConnectionStarting = { foregroundRequested = true }
        )
        val request = launch { manager.openExecStream(RemoteMachine("pending", "Pending"), "runtime") }
        try {
            withTimeout(5000) { opening.await() }
            assertEquals(1, manager.pendingConnections.value)
            assertTrue(manager.connectedMachineIds.value.isEmpty())
        } finally {
            request.cancelAndJoin()
        }
        assertEquals(0, manager.pendingConnections.value)
    }

    @Test fun pairingClosesTheSshSessionAfterReadingTheResponse() = runBlocking {
        val response = """{"protocol":5,"client_id":"owner","secret":"test","fingerprint":"server","transport":"tcp"}"""
        val session = TestSession(response.toByteArray())
        val manager = manager(session)
        val machine = RemoteMachine("pairing", "Pairing")

        val paired = GenerativeServerSshTransport.pairUsing { command ->
            if (command.contains("install.sh")) {
                RemoteExecStream(
                    ByteArrayInputStream("generative-cli-ready\n".toByteArray()), ByteArrayOutputStream()
                ) {}
            } else {
                manager.openExecStream(machine, command)
            }
        }

        assertEquals("owner", paired.identity.clientId)
        withTimeout(5000) { session.disconnected.await() }
        assertFalse(manager.isConnected(machine.id))
    }

    @Test fun closingRuntimeDisconnectsWhenTheLastTaskReleasesTheSharedSshSession() = runBlocking {
        val session = TestSession()
        val manager = manager(session)
        val machine = RemoteMachine("shared", "Shared")
        val runtime = manager.openExecStream(machine, "runtime")
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
        val runtime = manager.openExecStream(machine, "runtime")
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

    private class TestSession(private val response: ByteArray = byteArrayOf()) : Session(JSch(), "test", "localhost", 22) {
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
                override fun getInputStream() = ByteArrayInputStream(response)
                override fun getOutputStream() = ByteArrayOutputStream()
            }
        }
    }
}
