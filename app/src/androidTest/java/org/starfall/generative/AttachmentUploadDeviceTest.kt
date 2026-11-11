package org.starfall.generative

import android.content.Context
import androidx.core.content.FileProvider
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4
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
import org.starfall.generative.shared.subfeatures.media.logic.importUserAttachment
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class AttachmentUploadDeviceTest {
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val createdFiles = mutableListOf<File>()

    @After fun cleanTestFiles() { createdFiles.forEach(File::delete) }
    private fun json(body: String, status: Int = 200) = MockResponse().setResponseCode(status).setHeader("Content-Type", "application/json").setBody(body)
    private fun fileResponse(id: String) = json("""{"id":"$id","object":"file","bytes":8,"created_at":1,"filename":"input.pdf","purpose":"user_data"}""")
    private fun answer() = json("""{"id":"reply","object":"chat.completion","created":1,"model":"chat","choices":[{"index":0,"message":{"role":"assistant","content":"OK"},"finish_reason":"stop"}]}""")
    private fun provider(server: MockWebServer, type: ModelApiType = ModelApiType.OPENAI) = ModelProvider(
        "p", "Provider", type, Authorization(value = "test-key"), baseUrl = server.url("/v1").toString(),
        config = ProviderConfiguration(supportStream = false, modelConfigs = mapOf("chat" to ModelConfiguration(supportsVision = true))))
    private fun message(name: String = "input.pdf") = File(context.cacheDir, "device-attachment-" + java.util.UUID.randomUUID() + "-" + name).apply { writeText("contents"); createdFiles += this }.let { file ->
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

    @Test fun contentProviderFileIsSnapshottedThenUploadedAfterSourceIsRemoved() = scenario { server ->
        val requests = CopyOnWriteArrayList<RecordedRequest>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                return if (request.path!!.startsWith("/v1/files")) fileResponse("file-content-uri")
                    else answer()
            }
        }
        val source = File(ToolFiles.appStorageDirectory(context),
            "device-uri-" + java.util.UUID.randomUUID() + ".pdf")
        source.writeText("PDF text sent from Android's content resolver")
        val uri = FileProvider.getUriForFile(context, context.packageName + ".tool-files", source)
        var snapshot: String? = null
        try {
            snapshot = importUserAttachment(context, ToolFiles(context), uri, "application/pdf")
            assertNotEquals(uri.toString(), snapshot)
            assertTrue(File(snapshot).isFile)
            assertEquals(source.readText(), File(snapshot).readText())
            assertTrue(source.delete())
            val user = StoredMessage("uri-user", ChatRole.USER,
                listOf(MessageVersion(content = "Read file", files = listOf(snapshot))))
            val events = AiService(context).streamEvents(provider(server), "chat", listOf(user)).toList()
            assertEquals("OK", events.filterIsInstance<GenerationEvent.Text>().single().text)
            assertEquals("/v1/files", requests.first().path)
            assertEquals("/v1/chat/completions", requests.last().path)
            assertTrue(requests.last().body.clone().readUtf8().contains("file-content-uri"))
            assertEquals(listOf(snapshot), user.files)
        } finally {
            source.delete()
            snapshot?.let { File(it).delete() }
        }
    }
}
