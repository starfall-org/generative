package org.starfall.generative

import kotlinx.serialization.json.jsonObject
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.starfall.generative.shared.model.McpInfo
import org.starfall.generative.shared.model.McpProtocol
import org.starfall.generative.integrations.mcp.McpService
import org.starfall.generative.shared.helpers.networking.ToolHttp
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class McpTransportCompatibilityTest {
    @Test(timeout = 15000)
    fun streamableHttp415FallsBackToLegacySse() = runBlocking {
        val server = SseTestServer(InetSocketAddress("127.0.0.1", 0))
        val executor = Executors.newCachedThreadPool()
        val stream = AtomicReference<java.io.OutputStream?>(null)
        val streamOpened = AtomicBoolean(false)
        val postCount = AtomicInteger(0)
        val getSeen = AtomicBoolean(false)
        server.executor = executor

        fun writeResponse(exchange: SseTestExchange, code: Int) {
            exchange.sendResponseHeaders(code, -1)
            exchange.close()
        }

        fun sendEvent(message: String) {
            val output = stream.get() ?: error("Legacy SSE stream was not opened")
            synchronized(output) {
                output.write("event: message\ndata: $message\n\n".toByteArray(StandardCharsets.UTF_8))
                output.flush()
            }
        }

        server.createContext("/mcp") { exchange ->
            if (exchange.requestMethod == "POST") {
                postCount.incrementAndGet()
                // The endpoint is an old SSE endpoint, not Streamable HTTP.
                writeResponse(exchange, 415)
            } else if (exchange.requestMethod == "GET") {
                getSeen.set(true)
                exchange.responseHeaders.add("Content-Type", "text/event-stream")
                exchange.sendResponseHeaders(200, 0)
                val output = exchange.responseBody
                stream.set(output)
                streamOpened.set(true)
                output.write("event: endpoint\ndata: /messages\n\n".toByteArray(StandardCharsets.UTF_8))
                output.flush()
                try {
                    while (!Thread.currentThread().isInterrupted) Thread.sleep(100)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                } finally {
                    output.close()
                    exchange.close()
                }
            } else {
                writeResponse(exchange, 405)
            }
        }

        server.createContext("/messages") { exchange ->
            val body = exchange.requestBody.bufferedReader().use { it.readText() }
            val request = Json.parseToJsonElement(body).jsonObject
            when (request["method"]?.toString()?.trim('"')) {
                "initialize" -> {
                    sendEvent(buildJsonObject {
                        put("jsonrpc", "2.0")
                        put("id", request["id"]!!)
                        put("result", buildJsonObject {
                            put("protocolVersion", "2025-06-18")
                            put("capabilities", buildJsonObject { put("tools", buildJsonObject {}) })
                            put("serverInfo", buildJsonObject { put("name", "legacy-test"); put("version", "1") })
                        })
                    }.toString())
                    writeResponse(exchange, 202)
                }
                "notifications/initialized" -> writeResponse(exchange, 202)
                "tools/list" -> {
                    sendEvent(buildJsonObject {
                        put("jsonrpc", "2.0")
                        put("id", request["id"]!!)
                        put("result", buildJsonObject {
                            put("tools", kotlinx.serialization.json.buildJsonArray {
                                add(buildJsonObject {
                                    put("name", "echo")
                                    put("inputSchema", buildJsonObject { put("type", "object") })
                                })
                            })
                        })
                    }.toString())
                    writeResponse(exchange, 202)
                }
                else -> writeResponse(exchange, 400)
            }
        }

        server.start()
        try {
            val url = "http://127.0.0.1:${server.address.port}/mcp"
            val tools = McpService(ToolHttp()).discover(
                McpInfo("legacy", "Legacy", McpProtocol.STREAMABLE_HTTP, url)
            )
            assertEquals(listOf("echo"), tools.map { it.originalName })
            assertTrue(streamOpened.get())
            assertTrue(getSeen.get())
            assertTrue(postCount.get() >= 1)
        } finally {
            stream.get()?.close()
            server.stop()
            executor.shutdownNow()
        }
    }
}

/** An actual streaming response, without depending on JDK-only com.sun classes in Android tests. */
private class SseTestServer(address: InetSocketAddress) {
    private val socket = java.net.ServerSocket().apply { bind(address) }
    val address get() = socket.localSocketAddress as InetSocketAddress
    var executor: java.util.concurrent.ExecutorService = Executors.newCachedThreadPool()
    private val handlers = mutableMapOf<String, (SseTestExchange) -> Unit>()
    fun createContext(path: String, handler: (SseTestExchange) -> Unit) { handlers[path] = handler }
    fun start() {
        executor.execute {
            while (!socket.isClosed) {
                val client = try { socket.accept() } catch (_: java.net.SocketException) { break }
                executor.execute {
                    client.use {
                        val exchange = SseTestExchange(client)
                        handlers[exchange.path]?.invoke(exchange) ?: run { exchange.sendResponseHeaders(404, -1) }
                    }
                }
            }
        }
    }
    fun stop() { socket.close() }
}

private class SseTestExchange(private val socket: java.net.Socket) {
    val path: String
    val requestMethod: String
    val requestBody: java.io.InputStream
    val responseBody: java.io.OutputStream = socket.getOutputStream()
    class Headers : LinkedHashMap<String, String>() { fun add(name: String, value: String) { put(name, value) } }
    val responseHeaders = Headers()
    init {
        socket.soTimeout = 5000
        val input = socket.getInputStream()
        fun line(): String = buildString {
            while (true) {
                val byte = input.read()
                check(byte >= 0) { "Incomplete test request" }
                if (byte == 10) break
                if (byte != 13) append(byte.toChar())
            }
        }
        val first = line().split(' ')
        requestMethod = first[0]
        path = first[1].substringBefore('?')
        var length = 0
        while (true) {
            val header = line()
            if (header.isEmpty()) break
            if (header.startsWith("Content-Length:", true)) length = header.substringAfter(':').trim().toInt()
        }
        val body = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val read = input.read(body, offset, length - offset)
            check(read > 0) { "Incomplete test request body" }
            offset += read
        }
        requestBody = java.io.ByteArrayInputStream(body)
    }
    fun sendResponseHeaders(code: Int, length: Long) {
        val headers = buildString {
            append("HTTP/1.1 $code OK\r\nConnection: close\r\n")
            if (length != 0L) append("Content-Length: ${length.coerceAtLeast(0)}\r\n")
            responseHeaders.forEach { (name, value) -> append("$name: $value\r\n") }
            append("\r\n")
        }
        responseBody.write(headers.toByteArray())
        responseBody.flush()
    }
    fun close() = socket.close()
}
