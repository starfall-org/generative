package org.starfall.generative

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.starfall.generative.database.repositories.RemoteAttachmentRepository
import org.starfall.generative.database.room.AppDatabase
import org.starfall.generative.integrations.modelapis.AiService
import org.starfall.generative.integrations.modelapis.attachments.*
import org.starfall.generative.integrations.modelapis.sdk.OfficialProviderSdk
import org.starfall.generative.features.home.shared.logic.ToolRequestService
import org.starfall.generative.shared.helpers.networking.ToolHttp
import org.starfall.generative.shared.model.*
import org.starfall.generative.shared.model.api.ModelApiType
import org.starfall.generative.shared.model.auth.Authorization
import org.starfall.generative.shared.model.generation.GenerationEvent
import org.starfall.generative.shared.model.language.ModelConfiguration
import org.starfall.generative.shared.model.provider.ModelProvider
import org.starfall.generative.shared.model.provider.ProviderConfiguration
import org.starfall.generative.shared.subfeatures.media.logic.AttachmentResolver
import org.starfall.generative.shared.subfeatures.media.logic.ToolFiles
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = LibraryTestApplication::class)
class AttachmentUploadIntegrationTest {
    @Suppress("DEPRECATION")
    @Before fun mimeTypes() {
        val map = org.robolectric.shadow.api.Shadow.extract<org.robolectric.shadows.ShadowMimeTypeMap>(android.webkit.MimeTypeMap.getSingleton())
        map.addExtensionMimeTypeMapping("pdf", "application/pdf")
        map.addExtensionMimeTypeMapping("png", "image/png")
    }
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private fun json(body: String, status: Int = 200) = MockResponse().setResponseCode(status).setHeader("Content-Type", "application/json").setBody(body)
    private fun fileResponse(id: String) = json("""{"id":"$id","object":"file","bytes":8,"created_at":1,"filename":"input.pdf","purpose":"user_data"}""")
    private fun answer() = json("""{"id":"reply","object":"chat.completion","created":1,"model":"chat","choices":[{"index":0,"message":{"role":"assistant","content":"OK"},"finish_reason":"stop"}]}""")
    private fun provider(server: MockWebServer, type: ModelApiType = ModelApiType.OPENAI) = ModelProvider(
        "p", "Provider", type, Authorization(value = "test-key"), baseUrl = server.url("/v1").toString(),
        config = ProviderConfiguration(supportStream = false, modelConfigs = mapOf("chat" to ModelConfiguration(supportsVision = true))))
    private fun message(name: String = "input.pdf") = File(context.cacheDir, name).apply { writeText("contents") }.let { file ->
        StoredMessage("u", ChatRole.USER, listOf(MessageVersion("Read this", files = listOf(file.absolutePath))))
    }

    private fun scenario(block: suspend (MockWebServer) -> Unit) = runBlocking {
        val server = MockWebServer()
        server.start()
        try { withTimeout(30_000) { block(server) } } finally { server.shutdown() }
    }

    @Test fun uploadsBeforeGenerationPersistsAcrossServiceRestartAndNeverSendsFileDataAgain() = scenario { server ->
        val requests = CopyOnWriteArrayList<RecordedRequest>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                return if (request.path!!.startsWith("/v1/files")) fileResponse("file-1") else answer()
            }
        }
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val p = provider(server)
            val message = message()
            val first = AiService(context, remoteAttachmentStore = RemoteAttachmentRepository(db.remoteAttachmentDao()))
            val events = first.streamEvents(p, "chat", listOf(message)).toList()
            assertTrue(events.first() is GenerationEvent.PreparingAttachments)
            assertEquals("OK", events.filterIsInstance<GenerationEvent.Text>().single().text)
            val second = AiService(context, remoteAttachmentStore = RemoteAttachmentRepository(db.remoteAttachmentDao()))
            second.streamEvents(p.copy(config = p.config.copy(modelConfigs = p.config.modelConfigs + ("other" to ModelConfiguration()))), "other", listOf(message)).toList()
            assertEquals(1, requests.count { it.method == "POST" && it.path == "/v1/files" })
            assertEquals("/v1/files", requests.first().path)
            val generations = requests.filter { it.path == "/v1/chat/completions" }
            assertEquals(2, generations.size)
            generations.forEach {
                val body = it.body.clone().readUtf8()
                assertTrue(body.contains("file_id")); assertFalse(body.contains("file_data")); assertFalse(body.contains("base64"))
            }
            assertTrue(File(message.files.single()).exists())
        } finally { db.close() }
    }

    @Test fun missingRemoteReferenceReuploadsOnceBeforeOutput() = scenario { server ->
        val uploads = AtomicInteger()
        val generations = AtomicInteger()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.method == "POST" && request.path == "/v1/files" -> fileResponse("file-${uploads.incrementAndGet()}")
                request.path!!.startsWith("/v1/files/") -> fileResponse("file-${uploads.get()}")
                generations.incrementAndGet() == 1 -> json("""{"error":{"message":"File file-1 not found","type":"invalid_request_error","code":"file_not_found"}}""", 400)
                else -> answer()
            }
        }
        val result = AiService(context).streamEvents(provider(server), "chat", listOf(message())).toList()
        assertEquals(2, uploads.get()); assertEquals(2, generations.get())
        assertEquals("OK", result.filterIsInstance<GenerationEvent.Text>().single().text)
    }

    @Test fun repeatedInvalidReferenceStopsAfterOneRetry() = scenario { server ->
        val uploads = AtomicInteger()
        val generations = AtomicInteger()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = if (request.path == "/v1/files") fileResponse("file-${uploads.incrementAndGet()}")
                else { generations.incrementAndGet(); json("""{"error":{"message":"File file-${uploads.get()} not found","type":"invalid_request_error"}}""", 400) }
        }
        assertTrue(runCatching { AiService(context).streamEvents(provider(server), "chat", listOf(message())).toList() }.isFailure)
        assertEquals(2, uploads.get()); assertEquals(2, generations.get())
    }

    @Test fun gatewayFallbackOnlyForUnsupportedUploadEndpoint() = scenario { server ->
        for (status in listOf(405, 501, 401, 403, 413, 429, 500, 503)) {
            val generations = AtomicInteger()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = if (request.path == "/v1/files")
                    json("""{"error":{"message":"upload unavailable","type":"api_error"}}""", status)
                else { generations.incrementAndGet(); answer() }
            }
            val result = runCatching { AiService(context).streamEvents(provider(server), "chat", listOf(message())).toList() }
            if (status in listOf(405, 501)) { assertTrue("status=$status", result.isSuccess); assertEquals(1, generations.get()) }
            else { assertTrue("status=$status", result.isFailure); assertEquals(0, generations.get()) }
        }
    }

    @Test fun filesOverrideIsNotRoutedToCustomGenerationEndpoint() = scenario { server ->
        val paths = CopyOnWriteArrayList<String>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                paths += request.path!!
                return if (request.path!!.startsWith("/custom/files")) fileResponse("file-1") else answer()
            }
        }
        val p = provider(server).let { it.copy(config = it.config.copy(customEndpoints = mapOf(
            "chat" to server.url("/custom/chat").toString(), "files" to server.url("/custom/files").toString()))) }
        val ai = AiService(context)
        repeat(2) { ai.streamEvents(p, "chat", listOf(message())).toList() }
        assertEquals(listOf("/custom/files", "/custom/chat", "/custom/files/file-1", "/custom/chat"), paths.toList())
    }

    @Test fun responsesImageAndAnthropicPdfEncodeRemoteReferences() = scenario { server ->
        val source = AttachmentResolver(context)
        val sdk = OfficialProviderSdk(source)
        val uploader = SdkAttachmentUploader(sdk, source)
        val preflight = AttachmentPreflight(source, uploader, MemoryRemoteAttachmentStore())
        val paths = CopyOnWriteArrayList<String>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                paths += request.path!!
                return if (request.getHeader("x-api-key") != null)
                    json("""{"id":"file-claude","type":"file","filename":"input.pdf","mime_type":"application/pdf","size_bytes":8,"created_at":"2026-01-01T00:00:00Z","downloadable":false}""")
                else fileResponse("file-openai")
            }
        }
        val image = message("image.png")
        val responses = provider(server, ModelApiType.OPENAI_RESPONSES)
        val prepared = preflight.prepare(responses, ModelConfiguration(supportsVision = true), image.files)
        val part = sdk.openAiResponseContent(image, prepared).last().inputImage().get()
        assertEquals("file-openai", part.fileId().get()); assertFalse(part.imageUrl().isPresent)
        val pdf = message()
        val claude = provider(server, ModelApiType.ANTHROPIC)
        val claudePrepared = preflight.prepare(claude, ModelConfiguration(), pdf.files)
        assertEquals("file-claude", sdk.anthropicContent(pdf, claudePrepared).last().document().get().source().file().get().fileId())
        assertEquals(listOf("/v1/files", "/v1/files"), paths.toList())
    }

    @Test fun toolChatUsesTheSameRemoteCacheAndWireFilePart() = scenario { server ->
        val requests = CopyOnWriteArrayList<RecordedRequest>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                return if (request.path!!.startsWith("/v1/files")) fileResponse("file-1") else answer()
            }
        }
        val ai = AiService(context)
        val p = provider(server)
        val m = message()
        val prepared = ai.prepareAttachments(p, "chat", m.files)
        val items = ai.toolAttachments(m, ToolFiles(context), mutableMapOf(), prepared)
        val history = listOf(buildJsonObject { put("role", "user"); put("content", "Read"); put("_attachments", items) })
        repeat(2) { ToolRequestService(ToolHttp(), ai).request(p, "chat", history, emptyList(), "", false, {}, {}) }
        assertEquals(1, requests.count { it.method == "POST" && it.path == "/v1/files" })
        requests.filter { it.path == "/v1/chat/completions" }.forEach { request ->
            val body = request.body.clone().readUtf8()
            assertTrue(body.contains("file_id")); assertFalse(body.contains("localReference")); assertFalse(body.contains("base64"))
        }
    }
    @Test fun geminiUploadsImagesWaitsForActiveAndReusesUri() = scenario { server ->
        val uploads = AtomicInteger()
        val paths = CopyOnWriteArrayList<String>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                paths += request.path!!
                return when {
                    request.path!!.contains("/upload/") -> {
                        uploads.incrementAndGet()
                        MockResponse().setHeader("X-Goog-Upload-URL", server.url("/session").toString())
                    }
                    request.path == "/session" -> json("""{"file":{"name":"files/image","uri":"https://files.example/image","mimeType":"image/png","state":"PROCESSING"}}""")
                        .setHeader("X-Goog-Upload-Status", "final")
                    request.path!!.endsWith("/files/image") -> json("""{"name":"files/image","uri":"https://files.example/image","mimeType":"image/png","state":"ACTIVE","expirationTime":"2099-01-01T00:00:00Z"}""")
                    else -> json("""{"error":{"message":"Unexpected route"}}""", 400)
                }
            }
        }
        val source = AttachmentResolver(context)
        val sdk = OfficialProviderSdk(source)
        val preflight = AttachmentPreflight(source, SdkAttachmentUploader(sdk, source), MemoryRemoteAttachmentStore())
        val m = message("google-image.png")
        val p = provider(server, ModelApiType.GOOGLE).copy(baseUrl = server.url("/v1beta").toString())
        repeat(2) {
            val prepared = preflight.prepare(p, ModelConfiguration(supportsVision = true), m.files)
            sdk.google(p).use { client ->
                val part = sdk.googleParts(client, m, prepared).last()
                assertEquals("https://files.example/image", part.fileData().get().fileUri().get())
                assertFalse(part.inlineData().isPresent)
            }
        }
        assertEquals(1, uploads.get())
        assertTrue(paths.count { it.endsWith("/files/image") } >= 2)
    }

    @Test fun streamedOutputPreventsAutomaticReplay() = scenario { server ->
        val uploads = AtomicInteger()
        val generations = AtomicInteger()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.path == "/v1/files") return fileResponse("file-${uploads.incrementAndGet()}")
                generations.incrementAndGet()
                val firstChunk = "data: {\"id\":\"reply\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"chat\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"Partial\"},\"finish_reason\":null}]}\n\n"
                return MockResponse().setHeader("Content-Type", "text/event-stream")
                    .setBody(firstChunk +
                        "data: {\"error\":{\"message\":\"File file-1 not found\",\"type\":\"invalid_request_error\"}}\n\n")
                    .throttleBody(firstChunk.toByteArray().size.toLong(), 500, java.util.concurrent.TimeUnit.MILLISECONDS)
            }
        }
        val p = provider(server).let { it.copy(config = it.config.copy(supportStream = true)) }
        val events = mutableListOf<GenerationEvent>()
        val result = runCatching { AiService(context).streamEvents(p, "chat", listOf(message())).toList(events) }
        assertTrue("Expected stream to fail after first chunk, events=$events", result.isFailure)
        assertTrue("Expected streamed text before failure, got events=$events; error=${result.exceptionOrNull()}",
            events.filterIsInstance<GenerationEvent.Text>().any { it.text == "Partial" })
        assertEquals(1, uploads.get()); assertEquals(1, generations.get())
    }



    @Test fun changingModelProviderAccountProjectEndpointAndProtocolRespectsUploadScopes() = scenario { server ->
        val requests = CopyOnWriteArrayList<RecordedRequest>()
        val uploads = AtomicInteger()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                return when {
                    request.method == "POST" && request.path!!.endsWith("/files") ->
                        fileResponse("file-${uploads.incrementAndGet()}")
                    request.method == "GET" && request.path!!.contains("/files/") ->
                        fileResponse(request.path!!.substringAfterLast('/'))
                    else -> answer()
                }
            }
        }
        val ai = AiService(context, remoteAttachmentStore = MemoryRemoteAttachmentStore())
        val attachment = message()
        val first = provider(server)
        suspend fun prepared(p: ModelProvider, modelName: String = "chat"): String =
            ai.prepareAttachments(p, modelName, attachment.files).getValue(attachment.files.single()).remote!!.remoteId

        val firstId = prepared(first)
        assertEquals(firstId, prepared(first.copy(id = "same-backend")))
        assertEquals(firstId, prepared(first, "another-model"))
        val newCredential = prepared(first.copy(auth = Authorization(value = "other-key")))
        val newProject = prepared(first.copy(config = first.config.copy(headers = mapOf("OpenAI-Project" to "other"))))
        val newEndpoint = prepared(first.copy(config = first.config.copy(
            customEndpoints = mapOf("files" to server.url("/custom/files").toString()))))
        val newProtocol = prepared(first.copy(type = ModelApiType.OPENAI_RESPONSES))
        assertEquals(5, uploads.get())
        assertEquals(5, setOf(firstId, newCredential, newProject, newEndpoint, newProtocol).size)
        assertEquals(1, requests.count { it.method == "POST" && it.path == "/custom/files" })
        assertEquals(4, requests.count { it.method == "POST" && it.path == "/v1/files" })
        assertEquals(firstId, prepared(first))
        assertEquals(5, uploads.get())
        assertTrue(File(attachment.files.single()).isFile)
    }

    @Test fun portableBackupKeepsLocalAttachmentsButNotRemoteFileIds() = runBlocking {
        val source = AttachmentResolver(context)
        val uploader = object : AttachmentUploader {
            var uploads = 0
            override fun capability(provider: ModelProvider, file: org.starfall.generative.shared.subfeatures.media.logic.ResolvedAttachment) =
                UploadCapability(true, true, 1024 * 1024L, "user_data")
            override fun scope(provider: ModelProvider) = provider.auth.token
            override suspend fun upload(provider: ModelProvider, file: org.starfall.generative.shared.subfeatures.media.logic.ResolvedAttachment, key: String) =
                RemoteAttachmentRecord(key, file.reference, "file-${++uploads}")
            override suspend fun exists(provider: ModelProvider, remote: RemoteAttachmentRecord) = true
            override fun isUnsupportedEndpoint(error: Exception) = false
        }
        val original = message("backup-document.pdf")
        val remote = AttachmentPreflight(source, uploader, MemoryRemoteAttachmentStore())
        val p = ModelProvider("p", "P", ModelApiType.OPENAI,
            Authorization(value = "private"), baseUrl = "https://example.test/v1")
        assertEquals("file-1", remote.prepare(p, ModelConfiguration(), original.files)
            .getValue(original.files.single()).remote!!.remoteId)
        val local = File(original.files.single())
        val bytes = java.io.ByteArrayOutputStream()
        val backup = org.starfall.generative.features.appdata.model.AppDataBackup(
            exportedAt = "2026-11-11T12:00:00Z",
            conversations = listOf(Conversation("chat", "Saved", 1, 1, listOf(original))),
            uploadFileNames = listOf(local.name)
        )
        org.starfall.generative.features.appdata.logic.writeZipBackup(bytes, backup,
            emptyMap(), emptyMap(), emptyMap(), uploadFiles = mapOf(local.name to local))
        assertFalse(bytes.toString(Charsets.UTF_8.name()).contains("file-1"))
        val directory = File(context.cacheDir, "attachment-backup-restore").apply {
            deleteRecursively()
            mkdirs()
        }
        try {
            val restored = org.starfall.generative.features.appdata.logic.readZipBackup(
                java.io.ByteArrayInputStream(bytes.toByteArray()), directory)
            assertEquals(original.files, restored.data.conversations.single().messages.single().files)
            val imported = restored.uploadFiles.getValue(local.name)
            assertEquals(local.readBytes().toList(), imported.readBytes().toList())
            val newScope = AttachmentPreflight(source, uploader, MemoryRemoteAttachmentStore())
            val reusedLocal = newScope.prepare(p, ModelConfiguration(), listOf(imported.absolutePath))
            assertEquals("file-2", reusedLocal.getValue(imported.absolutePath).remote!!.remoteId)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun missingHistoricalAttachmentBlocksGenerationInsteadOfOmittingIt() = scenario { server ->
        val requests = CopyOnWriteArrayList<RecordedRequest>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                return answer()
            }
        }
        val missing = File(context.cacheDir, "deleted-from-history.pdf").absolutePath
        File(missing).delete()
        val user = StoredMessage("old-user", ChatRole.USER,
            listOf(MessageVersion("Please inspect this.", files = listOf(missing))))
        val result = runCatching {
            AiService(context).streamEvents(provider(server), "chat", listOf(user)).toList()
        }
        assertTrue(result.isFailure)
        assertEquals(0, requests.size)
        assertEquals(listOf(missing), user.files)
    }

    @Test fun unknownUpload404FallsBackButAuthenticationAndFile404DoNot() = scenario { server ->
        for ((status, reason, fallbackExpected) in listOf(
            Triple(404, "route not found", true),
            Triple(404, "file not found", false),
            Triple(400, "bad upload", false),
            Triple(401, "unauthorized", false),
            Triple(403, "forbidden", false),
            Triple(410, "gone", false),
            Triple(408, "timeout", false),
            Triple(429, "rate limit", false)
        )) {
            val calls = CopyOnWriteArrayList<RecordedRequest>()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    calls += request
                    return if (request.path == "/v1/files")
                        json("""{"error":{"message":"$reason","type":"invalid_request_error"}}""", status)
                    else answer()
                }
            }
            val result = runCatching {
                AiService(context).streamEvents(provider(server), "chat", listOf(message())).toList()
            }
            assertEquals("status=$status; reason=$reason", fallbackExpected, result.isSuccess)
            assertEquals(1, calls.count { it.path == "/v1/files" })
            assertEquals(if (fallbackExpected) 1 else 0, calls.count { it.path == "/v1/chat/completions" })
        }
    }

}
