package org.starfall.generative.integrations.remote

import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.ChannelShell
import com.jcraft.jsch.Session
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.starfall.generative.shared.model.RemoteMachine
import org.starfall.generative.shared.model.remote.RemoteSecretPrompt



/** Owns persistent SSH sessions and interactive/exec channels for remote-machine operations. */
internal class RemoteMachineConnectionManager(
    private val openSessionCallback: suspend (RemoteMachine, String?, RemoteSecretPrompt?) -> Session,
    /** Forwarding is opt-in, never part of opening a plain SSH session. */
    private val startForwards: (Session, RemoteMachine) -> ActivePortForwards
) {
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val connections = ConcurrentHashMap<String, SharedConnection>()
    private val terminals = ConcurrentHashMap<String, ConcurrentHashMap<String, RemoteTerminal>>()
    private val machineLocks = ConcurrentHashMap<String, Mutex>()
    private val _connectedMachineIds = MutableStateFlow<Set<String>>(emptySet())
    val connectedMachineIds: StateFlow<Set<String>> = _connectedMachineIds.asStateFlow()
    private val _forwardedPorts = MutableStateFlow<Map<String, List<String>>>(emptyMap())
    /** Applied forward labels per connected machine; machines without any forward are absent. */
    val forwardedPorts: StateFlow<Map<String, List<String>>> = _forwardedPorts.asStateFlow()

    private suspend fun openSession(machine: RemoteMachine, passwordOverride: String? = null,
        askSecret: RemoteSecretPrompt? = null): Session =
        openSessionCallback(machine, passwordOverride, askSecret)

    suspend fun execute(
        machine: RemoteMachine,
        command: String,
        timeoutSeconds: Int,
        terminalId: String?
    ): RemoteCommandResult = runSshCommand(machine, command, timeoutSeconds, terminalId)

    suspend fun openTerminal(machine: RemoteMachine, passwordOverride: String? = null,
        askSecret: RemoteSecretPrompt? = null): RemoteTerminal {
        val shared = acquireShared(machine.id, create = machine, passwordOverride = passwordOverride, askSecret = askSecret)
            ?: error("Could not open an SSH connection.")
        return try {
            val (channel, input, output) = openShellChannel(shared.session, machine)
            val id = UUID.randomUUID().toString()
            val terminal = RemoteTerminal(
                id = id,
                session = shared.session,
                channel = channel,
                input = input,
                output = output,
                ownsSession = false,
                onClosed = {
                    val remaining = terminals[machine.id]
                    remaining?.remove(id)
                    if (remaining.isNullOrEmpty()) {
                        if (remaining != null) terminals.remove(machine.id, remaining)
                        // The last terminal of this machine is gone, so the shared SSH
                        // connection has nothing left to serve: close it as soon as this
                        // terminal releases it instead of waiting for the idle timeout.
                        // requestClose() must run before release() so the release starts
                        // the immediate (zero-delay) shutdown rather than the idle one.
                        // A pinned connection (port forwarding) outlives its terminals.
                        if (!shared.pinned) shared.requestClose()
                    }
                    shared.release()
                }
            )
            terminals.computeIfAbsent(machine.id) { ConcurrentHashMap() }[id] = terminal
            terminal
        } catch (error: Throwable) {
            withContext(NonCancellable + Dispatchers.IO) {
                runCatching { shared.release() }
                if (error is CancellationException) throw error
                // A shell channel failing must not tear down an otherwise healthy SSH connection,
                // especially one running independent port forwards.
                if (!shared.session.isConnected) runCatching { dropConnection(shared) }
            }
            throw error
        }
    }

    /** Tool-created terminals have no UI reader, so drain their output into the same capture buffer. */
    suspend fun openToolTerminal(machine: RemoteMachine, workspace: String? = null): RemoteTerminal {
        val terminal = openTerminal(machine)
        ioScope.launch {
            val buffer = ByteArray(4096)
            try {
                while (isActive && terminal.isOpen) {
                    if (terminal.input.read(buffer) < 0) break
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: java.io.IOException) {
                // A dropped or closed shell also ends its output stream.
            } finally {
                terminal.close()
            }
        }
        try {
            if (workspace != null) withContext(Dispatchers.IO) {
                terminal.output.write(remoteTerminalWorkspaceCommand(workspace).toByteArray(Charsets.UTF_8))
                terminal.output.flush()
            }
        } catch (error: Throwable) {
            terminal.close()
            throw error
        }
        return terminal
    }

    /** Read captured output without advancing the UI's terminal stream or running a command. */
    fun readTerminal(machineId: String, terminalId: String?, lines: Int): Pair<String, String> {
        require(lines in 1..1000) { "lines must be between 1 and 1000" }
        val available = terminals[machineId].orEmpty().values.filter { it.isOpen }
        val terminal = if (terminalId.isNullOrBlank()) available.maxByOrNull { it.createdAtNanos }
            else available.firstOrNull { it.id == terminalId }
        requireNotNull(terminal) { "No matching open terminal for this machine" }
        return terminal.id to terminal.readOutputLines(lines)
    }

    fun listTerminals(machineId: String): List<RemoteTerminalInfo> =
            terminals[machineId].orEmpty().values
            .filter { it.isOpen }
            .map { RemoteTerminalInfo(it.id, it.isOpen) }
            .sortedBy { info -> terminals[machineId]?.get(info.id)?.createdAtNanos ?: Long.MIN_VALUE }

    fun closeTerminal(machineId: String, terminalId: String?): Boolean {
        val selected = terminalId?.let { terminals[machineId]?.get(it) }
            ?: terminals[machineId]?.values?.filter { it.isOpen }?.maxByOrNull { it.createdAtNanos }
        selected?.close()
        return selected != null
    }

    fun closeAllTerminals(machineId: String) {
        terminals.remove(machineId)?.values?.forEach(RemoteTerminal::close)
    }

    fun isConnected(machineId: String): Boolean = connections[machineId]?.session?.isConnected == true
    fun disconnectMachine(machineId: String) { ioScope.launch { disconnectMachineNow(machineId) } }
    fun disconnectAllWhenIdle() { ioScope.launch {
        connections.values.toList().forEach { connection ->
            machineLocks.getOrPut(connection.machineId) { Mutex() }.withLock {
                // The user may have started forwarding while this idle sweep was queued.
                if (connections[connection.machineId] === connection && !connection.pinned &&
                    connection.requestClose()
                ) closeConnection(connection)
            }
        }
    } }

    /**
     * Explicitly starts forwarding on the existing SSH connection (or opens one if necessary).
     * Existing shell/exec channels remain live. Changing rules replaces only the forwarding listeners.
     */
    suspend fun startPortForward(
        machine: RemoteMachine,
        passwordOverride: String? = null,
        askSecret: RemoteSecretPrompt? = null
    ) {
        val shared = acquireShared(machine.id, create = machine, passwordOverride = passwordOverride, askSecret = askSecret)
            ?: error("Could not open an SSH connection.")
        try {
            withContext(Dispatchers.IO) {
                machineLocks.getOrPut(machine.id) { Mutex() }.withLock {
                    check(connections[machine.id] === shared && shared.session.isConnected) {
                        "SSH connection was closed before port forwarding started."
                    }
                    // Reconfigure only forward listeners; never close existing shell channels.
                    shared.forwards?.close()
                    shared.forwards = null
                    publishConnected()
                    shared.forwards = startForwards(shared.session, machine)
                    // A pending chat-idle close may have been requested after acquireShared().
                    // Explicit forwarding always wins over such a queued idle disconnect.
                    shared.keepAliveForForwards()
                    publishConnected()
                }
            }
        } finally {
            withContext(NonCancellable + Dispatchers.IO) { shared.release() }
        }
    }

    /** Stops forwarding independently of any terminal channels on the same SSH session. */
    suspend fun stopPortForward(machineId: String): Boolean = withContext(Dispatchers.IO) {
        machineLocks.getOrPut(machineId) { Mutex() }.withLock {
            val shared = connections[machineId] ?: return@withLock false
            val forwards = shared.forwards ?: return@withLock false
            forwards.close()
            shared.forwards = null
            publishConnected()
            if (terminals[machineId].isNullOrEmpty() && shared.requestClose()) closeConnection(shared)
            true
        }
    }

    suspend fun disconnectMachineNow(machineId: String): Boolean = withContext(NonCancellable + Dispatchers.IO) {
        machineLocks.getOrPut(machineId) { Mutex() }.withLock {
            val connection = connections[machineId] ?: run {
                closeAllTerminals(machineId)
                return@withLock false
            }
            closeConnection(connection)
            true
        }
    }

    /** One SSH session shared by tool commands and terminals of the same machine. */
    private inner class SharedConnection(
        val machineId: String,
        val session: Session,
    ) {
        @Volatile var forwards: ActivePortForwards? = null
        val pinned: Boolean get() = forwards != null
        private var users = 0
        private var closeOnRelease = false
        private var idleJob: Job? = null
        private var monitorJob: Job? = null

        val inUse: Boolean @Synchronized get() = users > 0

        @Synchronized
        fun acquire() {
            users++
            closeOnRelease = false
            idleJob?.cancel()
            idleJob = null
        }

        /** Cancel any close request queued before an explicit Port Forward start completed. */
        @Synchronized
        fun keepAliveForForwards() {
            closeOnRelease = false
            idleJob?.cancel()
            idleJob = null
        }

        /** Closes now when nothing uses the connection, else as soon as the last user releases it. */
        @Synchronized
        fun requestClose(): Boolean {
            if (users == 0) return true
            closeOnRelease = true
            return false
        }

        /** Every acquire() needs exactly one release(); the idle countdown starts at the last one. */
        @Synchronized
        fun release() {
            if (users > 0) users--
            if (users == 0) {
                if (pinned && !closeOnRelease) return
                val wait = if (closeOnRelease) 0L else RemoteMachineService.IDLE_DISCONNECT_MILLIS
                idleJob?.cancel()
                idleJob = ioScope.launch {
                    delay(wait)
                    disconnectIfIdle(this@SharedConnection)
                }
            }
        }

        /** Drops the entry when the server or network closed the session, so the UI stays truthful. */
        fun startMonitor() {
            monitorJob = ioScope.launch {
                while (isActive) {
                    delay(RemoteMachineService.CONNECTION_POLL_MILLIS)
                    if (!session.isConnected) {
                        machineLocks.getOrPut(machineId) { Mutex() }.withLock {
                            if (connections[machineId] === this@SharedConnection && !session.isConnected) {
                                closeConnection(this@SharedConnection)
                            }
                        }
                        break
                    }
                }
            }
        }

        /** Blocking network I/O: call off the main thread. */
        fun shutdown() {
            monitorJob?.cancel()
            synchronized(this) {
                idleJob?.cancel()
                idleJob = null
            }
            forwards?.close()
            forwards = null
            runCatching { session.disconnect() }
        }
    }

    private suspend fun disconnectIfIdle(connection: SharedConnection) {
        machineLocks.getOrPut(connection.machineId) { Mutex() }.withLock {
            if (connection.inUse || connection.pinned || connections[connection.machineId] !== connection) return
            closeConnection(connection)
        }
    }

    private fun closeConnection(connection: SharedConnection) {
        if (!connections.remove(connection.machineId, connection)) return
        closeAllTerminals(connection.machineId)
        publishConnected()
        connection.shutdown()
    }

    private suspend fun dropConnection(connection: SharedConnection) =
        withContext(NonCancellable + Dispatchers.IO) {
            machineLocks.getOrPut(connection.machineId) { Mutex() }.withLock { closeConnection(connection) }
        }

    private fun publishConnected() {
        _forwardedPorts.value = connections.values
            .mapNotNull { shared -> shared.forwards?.labels?.takeIf { it.isNotEmpty() }?.let { shared.machineId to it } }
            .toMap()
        _connectedMachineIds.value = connections.keys.toSet()
    }

    private suspend fun acquireConnection(machine: RemoteMachine): SharedConnection =
        acquireShared(machine.id, create = machine) ?: error("Could not open an SSH connection.")

    /**
     * Returns the machine's live connection with one use acquired, opening it when [create] is
     * given. Without [create] it returns null if the machine is not connected.
     */
    private suspend fun acquireShared(
        machineId: String,
        create: RemoteMachine?,
        passwordOverride: String? = null,
        askSecret: RemoteSecretPrompt? = null
    ): SharedConnection? =
        withContext(Dispatchers.IO) {
            machineLocks.getOrPut(machineId) { Mutex() }.withLock {
                var connection = connections[machineId]
                if (connection != null && !connection.session.isConnected) {
                    closeConnection(connection)
                    connection = null
                }
                if (connection == null) {
                    if (create == null) return@withLock null
                    val session = openSession(create, passwordOverride, askSecret)
                    connection = SharedConnection(machineId, session)
                    connections[machineId] = connection
                    connection.startMonitor()
                    publishConnected()
                }
                connection.acquire()
                connection
            }
        }

    private suspend fun openShellChannel(
        session: Session,
        machine: RemoteMachine
    ): Triple<ChannelShell, InputStream, OutputStream> = withContext(Dispatchers.IO) {
        val channel = session.openChannel("shell") as ChannelShell
        try {
            channel.setPtyType("xterm-256color")
            // Ask for a UTF-8 locale; without it servers that don't set LANG themselves run the shell
            // in the POSIX locale and mangle (or escape) every multi-byte character. Servers whose
            // sshd lacks AcceptEnv just ignore these requests.
            channel.setEnv("LANG", "C.UTF-8")
            channel.setEnv("LC_ALL", "C.UTF-8")
            // pty mode IUTF8 (opcode 42) = 1, terminated by TTY_OP_END, so the line discipline erases
            // whole characters on Backspace instead of single bytes.
            channel.setTerminalMode(byteArrayOf(42, 0, 0, 0, 1, 0))
            // JSch only wires the piped streams into the channel thread if they are requested BEFORE
            // connect(). Asking for them afterwards left the channel with no stdin reader (nothing the
            // user typed was ever sent) and could drop the first output bytes.
            val input = channel.getInputStream()
            val output = channel.getOutputStream()
            channel.connect(machine.connectTimeoutSeconds * 1_000)
            Triple(channel, input, output)
        } catch (error: Throwable) {
            runCatching { channel.disconnect() }
            throw error
        }
    }

    private class ExecChannel(
        val channel: ChannelExec,
        val input: InputStream,
        val errors: ByteArrayOutputStream
    )

    private fun openExecChannel(session: Session, machine: RemoteMachine, command: String): ExecChannel {
        val channel = session.openChannel("exec") as ChannelExec
        val errors = ByteArrayOutputStream()
        try {
            channel.setCommand(command)
            channel.setInputStream(null)
            channel.setErrStream(errors)
            val input = channel.getInputStream()
            channel.connect(machine.connectTimeoutSeconds * 1_000)
            return ExecChannel(channel, input, errors)
        } catch (error: Throwable) {
            runCatching { channel.disconnect() }
            throw error
        }
    }

    /** Runs a command over the machine's persistent connection, opening it first when needed. */
    private suspend fun runSshCommand(
        machine: RemoteMachine,
        command: String,
        timeoutSeconds: Int,
        terminalId: String?
    ): RemoteCommandResult {
        val terminal = terminalId?.let { terminals[machine.id]?.get(it) }
            ?: terminals[machine.id]?.values?.filter { it.isOpen }?.maxByOrNull { it.createdAtNanos }
        require(terminal != null && terminal.isOpen) {
            "Open a terminal before executing a command on this remote machine."
        }
        var retried = false
        while (true) {
            val connection = acquireConnection(machine)
            val exec = try {
                withContext(Dispatchers.IO) { openExecChannel(connection.session, machine, command) }
            } catch (error: Throwable) {
                connection.release()
                if (error is CancellationException) throw error
                // A rejected exec channel must not terminate healthy terminal/forward sessions.
                // Only reconnect when the transport has actually gone away.
                if (connection.session.isConnected) throw error
                // The command never started, so reconnecting once cannot repeat the command.
                dropConnection(connection)
                if (retried) throw error
                retried = true
                continue
            }
            try {
                return collectExecResult(exec, timeoutSeconds)
            } finally {
                withContext(NonCancellable + Dispatchers.IO) {
                    runCatching { exec.channel.disconnect() }
                }
                connection.release()
            }
        }
    }

    private fun stopExec(channel: ChannelExec) {
        // The session stays open, so ask the server to end the process too (best effort; older
        // servers ignore the signal) before closing the channel.
        runCatching { channel.sendSignal("TERM") }
        runCatching { channel.disconnect() }
    }

    private suspend fun collectExecResult(exec: ExecChannel, timeoutSeconds: Int): RemoteCommandResult {
        // JSch's read calls block their thread and ignore coroutine cancellation, so reading runs in
        // a child coroutine and the channel is closed when cancelled. That makes the Stop button
        // take effect immediately instead of after the remote command.
        val limitSeconds = timeoutSeconds.coerceIn(1, 600)
        val collected = ByteArrayOutputStream()
        var timedOut = false
        coroutineScope {
            val reader = async(Dispatchers.IO) {
                val buffer = ByteArray(8_192)
                try {
                    while (true) {
                        val count = exec.input.read(buffer)
                        if (count < 0) break
                        synchronized(collected) { collected.write(buffer, 0, count) }
                    }
                } catch (_: java.io.IOException) {
                    // The channel was closed by cancel/timeout below.
                }
            }
            try {
                withTimeout(limitSeconds * 1_000L) { reader.await() }
            } catch (_: TimeoutCancellationException) {
                // A timeout is a tool result for the model, not a cancelled generation:
                // TimeoutCancellationException is a CancellationException and would
                // otherwise abort the whole chat turn as if the user pressed Stop.
                timedOut = true
                stopExec(exec.channel)
                reader.cancel()
            } catch (error: Throwable) {
                stopExec(exec.channel)
                reader.cancel()
                throw error
            }
        }
        val output = synchronized(collected) { collected.toString(Charsets.UTF_8.name()) }
        // The channel can finish writing stderr just after stdout reaches EOF.
        delay(20)
        val stderr = String(exec.errors.toByteArray(), Charsets.UTF_8)
        return if (timedOut) {
            RemoteCommandResult(
                output,
                (stderr + "\n[Command timed out after ${limitSeconds}s and was terminated; output above is partial.]").trim(),
                -1
            )
        } else {
            RemoteCommandResult(output, stderr, exec.channel.getExitStatus())
        }
    }

}
