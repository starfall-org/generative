package org.starfall.generative.shared.helpers.networking

import java.io.OutputStream
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.encodeToStream
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okio.BufferedSink

/** Serialize lazy JSON arrays incrementally, keeping Content-Length for existing HTTP endpoints. */
@OptIn(ExperimentalSerializationApi::class)
internal class StreamingJsonBody(private val value: JsonElement) : RequestBody() {
    override fun contentType(): MediaType = "application/json".toMediaType()

    private val length by lazy {
        val counter = object : OutputStream() {
            var count = 0L
            override fun write(b: Int) { count++ }
            override fun write(b: ByteArray, off: Int, len: Int) { count += len }
        }
        Json.encodeToStream(JsonElement.serializer(), value, counter)
        counter.count
    }

    override fun contentLength(): Long = length
    override fun writeTo(sink: BufferedSink) {
        Json.encodeToStream(JsonElement.serializer(), value, sink.outputStream())
    }
}
