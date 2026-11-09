package org.starfall.generative.integrations.remote

import com.jcraft.jsch.ChannelShell
import com.jcraft.jsch.Session
import kotlinx.coroutines.CancellationException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean
import org.starfall.generative.shared.model.remote.RemoteTerminalSession


/** Result returned by an SSH command execution. */
data class RemoteCommandResult(
    val output: String,
    val error: String = "",
    val exitCode: Int = 0
) {
    val isError: Boolean get() = exitCode != 0
}

data class RemoteTerminalInfo(
    val id: String,
    val isConnected: Boolean
)

/**
 * A connected interactive shell. The owner must close it when the terminal screen is disposed.
 * A terminal that reuses a shared SSH connection only closes its own channel; one that opened a
 * private connection ([ownsSession]) closes the whole connection as well.
 */
class RemoteTerminal internal constructor(
    override val id: String,
    private val session: Session,
    private val channel: ChannelShell,
    input: InputStream,
    override val output: OutputStream,
    private val ownsSession: Boolean = false,
    private val onClosed: () -> Unit = {}
) : RemoteTerminalSession {
    private val closed = AtomicBoolean(false)
    private val outputCapture = RemoteTerminalOutputCapture()
    override val input: InputStream = CapturingTerminalInputStream(input, outputCapture)

    internal fun readOutputLines(lines: Int): String = outputCapture.latestLines(lines)
    internal val createdAtNanos: Long = System.nanoTime()

    override val isOpen: Boolean get() = !closed.get() && channel.isConnected

    /** Exit status of the remote shell, or -1 while it is running / if the connection was dropped. */
    override val exitStatus: Int get() = channel.exitStatus

    /** Tells the server the terminal size changed (blocking network I/O; call off the main thread). */
    override fun resize(columns: Int, rows: Int) {
        if (columns <= 0 || rows <= 0 || !channel.isConnected) return
        runCatching { channel.setPtySize(columns, rows, 0, 0) }
    }

    /**
     * Disconnects the channel (and the SSH session when this terminal owns it). JSch writes to the
     * socket when disconnecting, which throws NetworkOnMainThreadException on the UI thread
     * (previously swallowed, leaving the session open), so the work always runs on a background
     * thread. Safe to call repeatedly.
     */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        Thread {
            runCatching { channel.disconnect() }
            if (ownsSession) {
                runCatching { session.disconnect() }
            }
            runCatching { onClosed() }
        }.apply { isDaemon = true; name = "remote-terminal-disconnect" }.start()
    }
}

/** Initialize only AI-created shells; a failed directory change must not leave a usable shell elsewhere. */
internal fun remoteTerminalWorkspaceCommand(workspace: String): String {
    require(workspace.startsWith('/') && '\u0000' !in workspace) { "workspace must be an absolute directory path" }
    require(workspace.none { it < ' ' || it == '\u007f' }) { "workspace must not contain terminal control characters" }
    val quotedPath = "'" + workspace.replace("'", "'\"'\"'") + "'"
    return "cd $quotedPath || exit\n"
}
