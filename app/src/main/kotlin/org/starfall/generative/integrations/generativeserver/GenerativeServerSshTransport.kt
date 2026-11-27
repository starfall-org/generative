package org.starfall.generative.integrations.generativeserver

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.put
import org.starfall.generative.integrations.remote.RemoteMachineService
import org.starfall.generative.integrations.remote.RemoteExecStream

/** Closing only the leased exec channel interrupts blocked SSH frame reads on cancellation. */
private suspend fun <T> cancellableSshIO(connection: RemoteExecStream, operation: () -> T): T =
        suspendCancellableCoroutine { continuation ->
            val worker = CoroutineScope(Dispatchers.IO).launch {
                try {
                    val result = operation()
                    if (continuation.isActive) continuation.resume(result)
                } catch (failure: Throwable) {
                    if (continuation.isActive) continuation.resumeWithException(failure)
                }
            }
            continuation.invokeOnCancellation { connection.cancel(); worker.cancel() }
        }

/** SSH identities are scoped to v5 TCP/stdio and cannot be reused for HTTPS. */
class GenerativeServerSshTransport internal constructor(
    private val openStream: suspend (String) -> RemoteExecStream,
    private val identity: PairedIdentity
) : GenerativeServerWire {
    constructor(
        remote: RemoteMachineService, machineId: String, identity: PairedIdentity,
    ) : this({ command ->
        remote.openExecStream(machineId.also { require(it.isNotBlank()) }, command)
    }, identity)

    private val lock = Mutex()
    private val lifecycle = Any()
    @Volatile private var channel: RemoteExecStream? = null
    @Volatile private var closed = false
    private var connectionUsers = 0

    override suspend fun <T> withConnection(block: suspend () -> T): T {
        synchronized(lifecycle) { connectionUsers++ }
        return try {
            block()
        } finally {
            val idleChannel = synchronized(lifecycle) {
                connectionUsers--
                if (connectionUsers == 0) channel.also { channel = null } else null
            }
            if (idleChannel != null) withContext(NonCancellable + Dispatchers.IO) { idleChannel.close() }
        }
    }

    override fun close() {
        val connection = synchronized(lifecycle) {
            closed = true
            channel.also { channel = null }
        }
        connection?.close()
    }

    /** A local token marker only: the framed stream authenticates with the paired secret. */
    override fun authenticate(identity: PairedIdentity): AccessToken {
        require(identity.clientId == this.identity.clientId && identity.serverId == this.identity.serverId)
        return AccessToken("ssh-stdio", 3600)
    }
    override fun rpc(token: AccessToken, request: RpcRequest): RpcReply = runBlocking { rpcAsync(token, request) }
    override fun events(token: AccessToken, sessionId: String, after: Long): List<SessionEvent> =
        runBlocking { eventsAsync(token, sessionId, after) }

    override suspend fun revokeAsync(token: AccessToken) {
        val reply = rpcAsync(token, RpcRequest(UUID.randomUUID().toString(), "identity.revoke"))
        val data = GenerativeServerCodec.requireSuccess(reply)?.jsonObject
        if (data?.get("revoked")?.jsonPrimitive?.boolean != true) {
            throw GenerativeServerProtocolException("Server did not confirm SSH identity revocation")
        }
        close()
    }

    override suspend fun rpcAsync(token: AccessToken, request: RpcRequest): RpcReply = withConnection {
        lock.withLock { exchange(request) }
    }

    private suspend fun exchange(request: RpcRequest): RpcReply {
        if (closed) throw IOException("SSH connection closed")
        val existing = channel
        val connection = existing ?: openStream(cliCommand(STREAM_COMMAND))
        return try {
            if (existing == null) synchronized(lifecycle) {
                if (closed) throw IOException("SSH connection closed")
                // Publish before authentication so leaving the runtime can stop a blocked read.
                channel = connection
            }
            cancellableSshIO(connection) {
                if (existing == null) {
                    writeFrame(connection.output, GenerativeServerCodec.json.encodeToString(
                        FramedAuth(type = "auth", protocol = 5, clientId = identity.clientId, secret = identity.secret)))
                    val reply = readFrame(connection.input)
                    val parsed = GenerativeServerCodec.json.parseToJsonElement(reply) as? JsonObject
                    if (parsed?.get("type")?.jsonPrimitive?.content != "paired" ||
                        parsed["protocol"]?.jsonPrimitive?.content != "5" ||
                        parsed["client_id"]?.jsonPrimitive?.content != identity.clientId) {
                        throw GenerativeServerProtocolException("SSH Generative Server authentication rejected")
                    }
                }
                writeFrame(connection.output, GenerativeServerCodec.encode(request))
                repeat(MAX_UNSOLICITED_FRAMES) {
                    val raw = readFrame(connection.input)
                    val obj = GenerativeServerCodec.json.parseToJsonElement(raw) as? JsonObject
                        ?: throw GenerativeServerProtocolException("Invalid SSH RPC frame")
                    if (obj.containsKey("id")) return@cancellableSshIO GenerativeServerCodec.decodeReply(raw, request.id)
                    // After session.open the daemon may push events on the same pipe.
                    // We read the owner-authorized session.events RPC with an explicit
                    // cursor instead, so these unsolicited frames never replace that cursor.
                    if (!obj.containsKey("kind")) throw GenerativeServerProtocolException("Unexpected SSH RPC frame")
                }
                throw GenerativeServerProtocolException("SSH event flood interrupted RPC reply")
            }
        } catch (failure: Throwable) {
            synchronized(lifecycle) {
                if (channel === connection) channel = null
            }
            connection.close()
            throw failure // Never retry a mutation or generation after uncertain delivery.
        }
    }

    override suspend fun eventsAsync(token: AccessToken, sessionId: String, after: Long): List<SessionEvent> {
        require(sessionId.isNotBlank() && after >= 0)
        val reply = rpcAsync(token, RpcRequest(UUID.randomUUID().toString(), "session.events", sessionId,
            buildJsonObject { put("after", after) }))
        val data = GenerativeServerCodec.requireSuccess(reply) ?: throw GenerativeServerProtocolException("Missing events")
        val events = try { GenerativeServerCodec.json.decodeFromString<List<SessionEvent>>(data.toString()) }
            catch (_: Exception) { throw GenerativeServerProtocolException("Invalid SSH events") }
        // The framed API is cursor polling, not HTTP long-poll. Do not busy-spin.
        if (events.isEmpty()) delay(500)
        return events
    }

    companion object {
        // SSH exec shells often omit user-local and Homebrew directories from PATH.
        private val findCli = """
            export PATH="${'$'}PATH:${'$'}HOME/.local/bin:${'$'}HOME/bin:/usr/local/bin:/opt/homebrew/bin"
            cli=${'$'}(command -v generative)
        """.trimIndent()

        // Compatibility with CLIs that require an explicit service start. A manually
        // started daemon can still accept pair/stream even without a managed service.
        private const val START_SERVICE = "\"\$cli\" service start </dev/null >/dev/null 2>&1 || :"
        private const val STREAM_COMMAND = "$START_SERVICE; exec \"\$cli\" stream --stdio"
        private const val CLI_READY = "generative-cli-ready\n"

        private val pairCommand = """
            $findCli
            if [ -z "${'$'}cli" ]; then
                script=${'$'}(mktemp) || exit 1
                trap 'rm -f "${'$'}script"' EXIT
                trap 'exit 1' HUP INT TERM
                curl -fsSL --proto '=https' --proto-redir '=https' \
                    https://raw.githubusercontent.com/starfall-org/generative-server/main/install.sh \
                    -o "${'$'}script" || exit 1
                INSTALL_DIR="${'$'}HOME/.local/bin" SERVERSIDE_INSTALL_SERVICE=1 sh "${'$'}script" >&2 || exit 1
                rm -f "${'$'}script"
                cli="${'$'}HOME/.local/bin/generative"
                [ -x "${'$'}cli" ] || exit 1
            else
                $START_SERVICE
            fi
            printf 'generative-cli-ready\n'
            exec "${'$'}cli" pair --stdio --transport tcp
        """.trimIndent()

        private fun shellCommand(script: String) = "sh -c '${script.replace("'", "'\"'\"'")}'"

        private fun cliCommand(command: String) =
            shellCommand("$findCli\n[ -n \"\$cli\" ] || exit 127\n$command")

        private const val MAX_FRAME_BYTES = 4 * 1024 * 1024
        private const val MAX_UNSOLICITED_FRAMES = 4096

        suspend fun pair(remote: RemoteMachineService, machineId: String): SshPairing =
            pairUsing { command -> remote.openExecStream(machineId.also { require(it.isNotBlank()) }, command) }

        internal suspend fun pairUsing(openStream: suspend (String) -> RemoteExecStream): SshPairing {
            val response = withTimeoutOrNull(180_000) {
                openStream(shellCommand(pairCommand)).use { command ->
                    cancellableSshIO(command) {
                        readLimited(command.input, 16 * 1024).toString(Charsets.UTF_8)
                    }
                }
            } ?: throw GenerativeCliSetupException()
            if (!response.startsWith(CLI_READY)) throw GenerativeCliSetupException()
            val paired = try { GenerativeServerCodec.json.decodeFromString<FramedPairing>(response.removePrefix(CLI_READY)) }
                catch (_: Exception) { throw GenerativeServerProtocolException("Invalid SSH pairing response") }
            if (paired.protocol != 5 || paired.transport != "tcp" || paired.clientId.isBlank() ||
                paired.secret.isBlank() || paired.fingerprint.isBlank()) {
                throw GenerativeServerProtocolException("Incompatible SSH pairing response")
            }
            return SshPairing(PairedIdentity(paired.clientId, paired.secret, paired.fingerprint), paired.endpoint)
        }

        internal fun writeFrame(output: OutputStream, payload: String) {
            val bytes = payload.toByteArray(Charsets.UTF_8)
            require(bytes.isNotEmpty() && bytes.size <= MAX_FRAME_BYTES)
            DataOutputStream(output).apply { writeInt(bytes.size); write(bytes); flush() }
        }
        internal fun readFrame(input: InputStream): String {
            val stream = DataInputStream(input)
            val size = stream.readInt()
            if (size !in 1..MAX_FRAME_BYTES) throw IOException("Invalid SSH frame size")
            return ByteArray(size).also(stream::readFully).toString(Charsets.UTF_8)
        }
        private fun readLimited(input: InputStream, limit: Int): ByteArray {
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (out.size() + count > limit) throw GenerativeServerProtocolException("SSH pairing response too large")
                out.write(buffer, 0, count)
            }
            return out.toByteArray()
        }
    }
}

internal class GenerativeCliSetupException : IOException("Generative CLI setup failed")

class SshPairing(val identity: PairedIdentity, val endpoint: String)

@Serializable
private data class FramedAuth(val type: String, val protocol: Int,
    @SerialName("client_id") val clientId: String, val secret: String)

@Serializable
private data class FramedPairing(val protocol: Int, @SerialName("client_id") val clientId: String,
    val secret: String, val fingerprint: String, val endpoint: String = "", val transport: String)
