package org.starfall.generative.integrations.modelapis.devin

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.GZIPInputStream
import kotlinx.serialization.json.JsonObject
import org.starfall.generative.shared.model.ToolDefinition

/** The small Connect/Protobuf contract actually used by the Devin CLI. No Go runtime needed. */
internal object DevinWire {
    const val CHAT_PATH = "/exa.api_server_pb.ApiServerService/GetChatMessage"
    const val CLIENT_VERSION = "3000.10.21"
    private const val MAX_FRAME = 16 * 1024 * 1024
    private const val MAX_EXPANDED = 64 * 1024 * 1024

    internal class ProtoWriter {
        private val bytes = ByteArrayOutputStream()
        private fun varint(value: Long) {
            var v = value
            while ((v and -128L) != 0L) {
                bytes.write(((v and 0x7fL) or 0x80L).toInt())
                v = v ushr 7
            }
            bytes.write(v.toInt())
        }
        fun number(field: Int, value: Long) { varint((field * 8).toLong()); varint(value) }
        fun string(field: Int, value: String) { data(field, value.toByteArray(Charsets.UTF_8)) }
        fun data(field: Int, value: ByteArray) {
            varint((field * 8 + 2).toLong()); varint(value.size.toLong()); bytes.write(value)
        }
        fun fixed64(field: Int, value: Double) {
            varint((field * 8 + 1).toLong())
            bytes.write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putDouble(value).array())
        }
        fun nested(field: Int, builder: ProtoWriter.() -> Unit) = data(field, ProtoWriter().apply(builder).build())
        fun build() = bytes.toByteArray()
    }

    internal data class Field(val number: Int, val wireType: Int, val data: ByteArray, val numberValue: Long = 0) {
        fun text() = String(data, Charsets.UTF_8)
    }

    internal fun fields(payload: ByteArray): List<Field> {
        var pos = 0
        fun varint(): Long {
            var result = 0L
            var shift = 0
            while (shift < 70) {
                require(pos < payload.size) { "Truncated Devin protobuf varint" }
                val value = payload[pos++].toInt() and 0xff
                result = result or ((value and 0x7f).toLong() shl shift)
                if (value and 0x80 == 0) return result
                shift += 7
            }
            error("Invalid Devin protobuf varint")
        }
        val out = ArrayList<Field>()
        while (pos < payload.size) {
            val key = varint()
            val no = (key ushr 3).toInt()
            val wire = (key and 7L).toInt()
            require(no > 0) { "Invalid Devin protobuf field number" }
            when (wire) {
                0 -> out += Field(no, wire, byteArrayOf(), varint())
                1, 5 -> {
                    val size = if (wire == 1) 8 else 4
                    require(pos + size <= payload.size) { "Truncated Devin protobuf fixed field" }
                    out += Field(no, wire, payload.copyOfRange(pos, pos + size)); pos += size
                }
                2 -> {
                    val length = varint()
                    require(length in 0..MAX_EXPANDED.toLong() && length <= payload.size - pos) {
                        "Invalid Devin protobuf bytes length"
                    }
                    val end = pos + length.toInt()
                    out += Field(no, wire, payload.copyOfRange(pos, end)); pos = end
                }
                else -> error("Unsupported Devin protobuf wire type $wire")
            }
        }
        return out
    }

    internal data class Turn(
        val source: Int, val content: String, val id: String = UUID.randomUUID().toString(),
        val toolCalls: List<Call> = emptyList(), val toolCallId: String = "",
        val thinking: String = "", val images: List<Pair<String, String>> = emptyList(),
        val signature: ByteArray = byteArrayOf(), val signatureType: String = ""
    )
    internal data class Call(val id: String, val name: String, val arguments: String, val invalidArguments: String = "")
    internal data class Usage(val input: Long, val output: Long, val cached: Long, val written: Long)
    internal data class Delta(
        val text: String, val reasoning: String, val calls: List<Call>, val stopReason: Long,
        val usage: Usage?, val signature: ByteArray = byteArrayOf(), val signatureType: String = ""
    )

    /** Stable 732-character device identity (the client metadata field #31). */
    internal fun fingerprint(seed: String): String = buildString {
        var index = 0
        while (length < 732) {
            val hash = MessageDigest.getInstance("SHA-256")
                .digest("$seed-${index++}".toByteArray(Charsets.UTF_8))
            hash.forEach { append("%02x".format(it.toInt() and 0xff)) }
        }
    }.take(732)

    internal fun buildChat(
        token: String, modelUID: String, systemPrompt: String, turns: List<Turn>,
        tools: List<ToolDefinition>, temperature: Double?, maxTokens: Int,
        sessionId: String, turnIndex: Int
    ): ByteArray = ProtoWriter().apply {
        nested(1) {
            string(1, "chisel"); string(2, CLIENT_VERSION); string(3, token)
            string(4, "en"); string(5, "linux"); string(7, CLIENT_VERSION)
            string(12, "chisel"); string(31, fingerprint(token))
        }
        if (systemPrompt.isNotBlank()) string(2, systemPrompt)
        turns.forEach { turn -> nested(3) {
            string(1, turn.id); number(2, turn.source.toLong()); string(3, turn.content)
            turn.toolCalls.forEach { call -> nested(6) {
                string(1, call.id); string(2, call.name); string(3, call.arguments)
            } }
            if (turn.toolCallId.isNotBlank()) string(7, turn.toolCallId)
            turn.images.forEach { (mime, base64) -> nested(10) { string(1, base64); string(2, mime) } }
            if (turn.thinking.isNotBlank()) string(11, turn.thinking)
            if (turn.signature.isNotEmpty()) data(12, turn.signature)
            if (turn.signatureType.isNotBlank()) string(18, turn.signatureType)
        } }
        number(7, 5)
        nested(8) {
            number(1, 1); number(2, maxTokens.coerceAtLeast(1).toLong()); number(3, 400)
            fixed64(5, temperature ?: 1.0); number(7, 40); fixed64(8, 0.95f.toDouble())
        }
        tools.forEach { tool -> nested(10) {
            string(1, tool.name); string(2, tool.description)
            data(3, tool.schema.toString().toByteArray(Charsets.UTF_8))
        } }
        nested(15) {
            string(1, sessionId)
            if (turnIndex > 0) number(2, turnIndex.toLong())
            number(3, 4)
            if (turns.lastOrNull()?.source == 1 && turnIndex == 0) number(4, 14)
        }
        string(16, sessionId)
        number(20, 1)
        string(21, modelUID)
    }.build()

    internal fun decodeDelta(payload: ByteArray): Delta {
        var text = ""; var thinking = ""; var stop = 0L; var usage: Usage? = null
        var sig = byteArrayOf(); var sigType = ""
        val calls = mutableListOf<Call>()
        fields(payload).forEach { field -> when (field.number) {
            3 -> text += field.text()
            9 -> thinking += field.text()
            5 -> if (field.wireType == 0) stop = field.numberValue
            6 -> {
                val parts = fields(field.data)
                fun str(n: Int) = parts.filter { it.number == n }.joinToString("") { it.text() }
                calls += Call(str(1), str(2), str(3), str(4))
            }
            7 -> {
                val parts = fields(field.data)
                fun value(n: Int) = parts.filter { it.number == n && it.wireType == 0 }.sumOf { it.numberValue }
                usage = Usage(value(2), value(3), value(5), value(4))
            }
            10 -> sig += field.data
            21 -> sigType = field.text()
        } }
        return Delta(text, thinking, calls, stop, usage, sig, sigType)
    }

    internal fun envelope(proto: ByteArray): ByteArray = ByteBuffer.allocate(5 + proto.size)
        .put(0.toByte()).putInt(proto.size).put(proto).array()

    internal data class Frame(val flag: Int, val payload: ByteArray)
    internal fun nextFrame(input: InputStream): Frame? {
        val header = ByteArray(5)
        val first = input.read()
        if (first < 0) return null
        header[0] = first.toByte()
        readExactly(input, header, 1, 4)
        val length = ByteBuffer.wrap(header, 1, 4).int
        require(length in 0..MAX_FRAME) { "Invalid Devin Connect frame size" }
        val body = ByteArray(length)
        readExactly(input, body, 0, length)
        val flag = header[0].toInt() and 0xff
        require(flag and 0xfc == 0) { "Invalid Devin Connect frame flag" }
        val payload = if (flag and 1 == 1) GZIPInputStream(body.inputStream()).use {
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val read = it.read(buffer)
                if (read < 0) break
                require(out.size() + read <= MAX_EXPANDED) { "Devin gzip frame too large" }
                out.write(buffer, 0, read)
            }
            out.toByteArray()
        } else body
        return Frame(flag, payload)
    }

    private fun readExactly(stream: InputStream, bytes: ByteArray, offset: Int, length: Int) {
        var done = 0
        while (done < length) {
            val got = stream.read(bytes, offset + done, length - done)
            if (got < 0) throw EOFException("Truncated Devin Connect frame")
            done += got
        }
    }
}
