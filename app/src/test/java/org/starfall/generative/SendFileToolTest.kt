package org.starfall.generative

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import org.starfall.generative.shared.subfeatures.media.logic.*
import org.starfall.generative.shared.helpers.networking.*
import org.starfall.generative.database.preferences.*
import java.nio.file.Files
import org.starfall.generative.shared.model.auth.Authorization
import org.starfall.generative.features.home.chat.logic.SendFileTool

class SendFileToolTest {
    @Test fun sendsExistingOtherFileWithoutCopyingAndRejectsMissingFiles() = runBlocking {
        val directory = Files.createTempDirectory("send-file").toFile()
        try {
            val files = ToolFiles(directory)
            val pdf = files.save("%PDF-1.4 test".byteInputStream(), "application/pdf")
            val sender = SendFileTool(ToolHttp(files))
            val result = sender.execute(obj("type" to str("other"), "uri" to str("tool-file:$pdf")))
            assertTrue(pdf.endsWith(".pdf"))
            assertEquals("tool-file:$pdf", result.text("uri"))
            assertEquals(1, files.list().size)
            assertTrue(runCatching { sender.execute(obj("type" to str("other"), "uri" to str("tool-file:missing.pdf"))) }.isFailure)
            assertTrue(runCatching { sender.execute(obj("type" to str("invalid"), "uri" to str("tool-file:$pdf"))) }.isFailure)
        } finally { directory.deleteRecursively() }
    }

    @Test fun downloadsUrlOnceWithoutProviderCredentials() = runBlocking {
        val server = MockWebServer()
        server.start()
        val directory = Files.createTempDirectory("send-file-http").toFile()
        val bytes = ByteArray(32).also { it[0] = 0x89.toByte(); it[1] = 0x50 }
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                assertNull(request.getHeader("Authorization"))
                return MockResponse().setHeader("Content-Type", "image/png").setBody(okio.Buffer().write(bytes))
            }
        }
        try {
            val files = ToolFiles(directory)
            val sender = SendFileTool(ToolHttp(files))
            val args = obj("type" to str("image"), "uri" to str(server.url("/image").toString()))
            val first = sender.execute(args)
            assertEquals(first, sender.execute(args))
            assertEquals(1, server.requestCount)
            assertArrayEquals(bytes, files.resolve(first.text("uri").removePrefix("tool-file:"))!!.readBytes())
        } finally { server.shutdown(); directory.deleteRecursively() }
    }

    @Test fun sendsSeveralFilesInOneCall() = runBlocking {
        val directory = Files.createTempDirectory("send-file-many").toFile()
        try {
            val files = ToolFiles(directory)
            val image = files.save(ByteArray(16).inputStream(), "image/png")
            val video = files.save(ByteArray(16).inputStream(), "video/mp4")
            val result = SendFileTool(ToolHttp(files)).execute(obj(
                "files" to JsonArray(listOf(
                    obj("type" to str("image"), "uri" to str("tool-file:$image")),
                    obj("type" to str("video"), "uri" to str("tool-file:$video"))
                ))
            ))

            assertEquals(2, result["files"]!!.jsonArray.size)
            assertEquals("tool-file:$image", result["files"]!!.jsonArray[0].jsonObject.text("uri"))
            assertEquals("tool-file:$video", result["files"]!!.jsonArray[1].jsonObject.text("uri"))
        } finally { directory.deleteRecursively() }
    }
}
