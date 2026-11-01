package org.starfall.generative.integrations.remote

import com.jcraft.jsch.ChannelDirectTCPIP
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.Session
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

/**
 * Dynamic (`-D`) forwarding. JSch has no SOCKS server, so this listens on 127.0.0.1:[port], speaks SOCKS5
 * (no authentication, CONNECT only) and relays each connection through a `direct-tcpip` channel of [session].
 * The listener closes itself once the session is no longer connected.
 */
internal class RemoteSocksForwarder(
    private val session: Session,
    private val port: Int,
    private val connectTimeoutMillis: Int
) {
    private val server = ServerSocket()
    private val clients = ConcurrentHashMap.newKeySet<Socket>()
    @Volatile private var stopped = false

    /** Binds the port; throws [IOException] when it is unavailable. */
    fun start() {
        try {
            server.reuseAddress = true
            server.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port))
            server.soTimeout = ACCEPT_POLL_MILLIS
            thread(isDaemon = true, name = "socks-forward-$port") { acceptLoop() }
        } catch (error: Throwable) {
            stop()
            throw error
        }
    }

    /** Stop this SOCKS listener and its clients while keeping SSH running. */
    fun stop() {
        stopped = true
        runCatching { server.close() }
        clients.forEach { runCatching { it.close() } }
    }

    private fun acceptLoop() {
        try {
            while (session.isConnected && !server.isClosed) {
                val client = try {
                    server.accept()
                } catch (_: SocketTimeoutException) {
                    continue
                }
                clients += client
                if (stopped) {
                    runCatching { client.close() }
                    clients.remove(client)
                    break
                }
                thread(isDaemon = true, name = "socks-client-$port") { handle(client) }
            }
        } catch (_: IOException) {
            // The listener was closed or failed; nothing else to clean up.
        } finally {
            runCatching { server.close() }
        }
    }

    private fun handle(client: Socket) {
        var channel: ChannelDirectTCPIP? = null
        try {
            client.tcpNoDelay = true
            client.soTimeout = HANDSHAKE_TIMEOUT_MILLIS
            val input = DataInputStream(client.getInputStream())
            val output = client.getOutputStream()
            val request = readRequest(input, output) ?: return
            val direct = session.openChannel("direct-tcpip") as ChannelDirectTCPIP
            channel = direct
            direct.setHost(request.host)
            direct.setPort(request.port)
            // Streams must be taken before connect(), otherwise JSch has no reader for the channel.
            val channelInput = direct.inputStream
            val channelOutput = direct.outputStream
            try {
                direct.connect(connectTimeoutMillis)
            } catch (_: JSchException) {
                reply(output, REPLY_HOST_UNREACHABLE)
                return
            }
            reply(output, REPLY_SUCCEEDED)
            client.soTimeout = 0
            val upstream = thread(isDaemon = true, name = "socks-up-$port") {
                pump(client.getInputStream(), channelOutput)
                runCatching { direct.disconnect() }
            }
            pump(channelInput, output)
            runCatching { client.close() }
            upstream.join()
        } catch (_: IOException) {
            // Client went away or the handshake was malformed.
        } finally {
            runCatching { channel?.disconnect() }
            runCatching { client.close() }
            clients.remove(client)
        }
    }

    private class Target(val host: String, val port: Int)

    /** Reads the SOCKS5 greeting and CONNECT request; replies with an error and returns null when unsupported. */
    private fun readRequest(input: DataInputStream, output: OutputStream): Target? {
        if (input.readUnsignedByte() != SOCKS_VERSION) return null
        input.readFully(ByteArray(input.readUnsignedByte())) // offered auth methods; only "no authentication" is served
        output.write(byteArrayOf(SOCKS_VERSION.toByte(), 0))
        output.flush()
        if (input.readUnsignedByte() != SOCKS_VERSION) return null
        val command = input.readUnsignedByte()
        input.readUnsignedByte() // reserved
        val host = when (input.readUnsignedByte()) {
            ADDRESS_IPV4 -> InetAddress.getByAddress(ByteArray(4).also(input::readFully)).hostAddress
            ADDRESS_DOMAIN -> String(ByteArray(input.readUnsignedByte()).also(input::readFully), Charsets.UTF_8)
            ADDRESS_IPV6 -> InetAddress.getByAddress(ByteArray(16).also(input::readFully)).hostAddress
            else -> {
                reply(output, REPLY_ADDRESS_UNSUPPORTED)
                return null
            }
        }
        val targetPort = input.readUnsignedShort()
        if (command != COMMAND_CONNECT) {
            reply(output, REPLY_COMMAND_UNSUPPORTED)
            return null
        }
        return Target(host.orEmpty(), targetPort)
    }

    private fun reply(output: OutputStream, code: Int) {
        output.write(byteArrayOf(SOCKS_VERSION.toByte(), code.toByte(), 0, ADDRESS_IPV4.toByte(), 0, 0, 0, 0, 0, 0))
        output.flush()
    }

    private fun pump(from: InputStream, to: OutputStream) {
        val buffer = ByteArray(BUFFER_BYTES)
        try {
            while (true) {
                val read = from.read(buffer)
                if (read < 0) break
                to.write(buffer, 0, read)
                to.flush()
            }
        } catch (_: IOException) {
            // One side closed; the caller tears down the other.
        }
    }

    private companion object {
        const val SOCKS_VERSION = 5
        const val COMMAND_CONNECT = 1
        const val ADDRESS_IPV4 = 1
        const val ADDRESS_DOMAIN = 3
        const val ADDRESS_IPV6 = 4
        const val REPLY_SUCCEEDED = 0
        const val REPLY_HOST_UNREACHABLE = 4
        const val REPLY_COMMAND_UNSUPPORTED = 7
        const val REPLY_ADDRESS_UNSUPPORTED = 8
        const val ACCEPT_POLL_MILLIS = 1_000
        const val HANDSHAKE_TIMEOUT_MILLIS = 10_000
        const val BUFFER_BYTES = 16 * 1024
    }
}
