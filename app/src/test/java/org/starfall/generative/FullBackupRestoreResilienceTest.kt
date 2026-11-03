package org.starfall.generative

import android.content.Context
import android.os.Looper
import androidx.compose.runtime.saveable.SaverScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.starfall.generative.features.appdata.logic.AppDataTransferService
import org.starfall.generative.features.appdata.logic.writeZipBackup
import org.starfall.generative.features.appdata.model.AppDataBackup
import org.starfall.generative.features.home.chat.logic.ChatViewModel
import org.starfall.generative.features.home.chat.logic.service.ConversationTitleGenerator
import org.starfall.generative.features.home.chat.logic.tool.ToolChatViewModel
import org.starfall.generative.integrations.mcp.McpOAuthService
import org.starfall.generative.integrations.mcp.McpService
import org.starfall.generative.integrations.modelapis.AiService
import org.starfall.generative.integrations.remote.RemoteMachineService
import org.starfall.generative.navigation.NullableFilePathSaver
import org.starfall.generative.shared.files.ToolFiles
import org.starfall.generative.shared.icons.IconStore
import org.starfall.generative.shared.media.SpeechAudioPlayer
import org.starfall.generative.shared.media.SpeechSynthesisService
import org.starfall.generative.shared.media.TtsHelper
import org.starfall.generative.shared.model.ChatRole
import org.starfall.generative.shared.model.Conversation
import org.starfall.generative.shared.model.MessageVersion
import org.starfall.generative.shared.model.StoredMessage
import org.starfall.generative.shared.model.ToolActivity
import org.starfall.generative.shared.model.SystemToolConfig
import org.starfall.generative.shared.model.api.ModelApiType
import org.starfall.generative.shared.model.auth.Authorization
import org.starfall.generative.shared.model.language.ModelConfiguration
import org.starfall.generative.shared.model.provider.ModelProvider
import org.starfall.generative.shared.model.provider.ProviderConfiguration
import org.starfall.generative.shared.networking.ToolHttp
import org.starfall.generative.database.ConversationRepository
import org.starfall.generative.database.McpRepository
import org.starfall.generative.database.ModelRepository
import org.starfall.generative.database.ProfileRepository
import org.starfall.generative.database.SpeechRepository
import org.starfall.generative.database.AppDatabase
import org.starfall.generative.shared.storage.preferences.AppPreferencesRepository
import org.starfall.generative.shared.storage.preferences.ToolSettingsStore

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = LibraryTestApplication::class)
@LooperMode(LooperMode.Mode.PAUSED)
class FullBackupRestoreResilienceTest {
    @Before
    fun setUpKeystore() {
        installTestAndroidKeyStore()
    }

    @Test
    fun failedRestoreRollsBackAndCleansUpSafetySnapshot() = runBlocking {
        withHarness { harness ->
            val existing = sampleConversation("before-restore", "Before Restore")
            harness.conversations.saveConversation(existing)
            val archiveBytes = zipBytesFor(
                AppDataBackup(
                    exportedAt = "2026-10-25T00:00:00Z",
                    conversations = listOf(sampleConversation("imported", "Imported Chat"))
                )
            )
            harness.transfer.applyFaultForTest = { isRollback ->
                if (!isRollback) error("Simulated disk full")
            }

            try {
                harness.transfer.import(ByteArrayInputStream(archiveBytes), "backup.zip") {}
                fail("Expected restore failure to propagate")
            } catch (expected: IllegalStateException) {
                assertEquals("Simulated disk full", expected.message)
            }

            assertNotNull(harness.conversations.loadById("before-restore"))
            assertNull(harness.conversations.loadById("imported"))
            assertTrue(harness.transfer.recoveryDirectory.listFiles().orEmpty().isEmpty())
        }
    }

    @Test
    fun failedRollbackPreservesRecoverySnapshotInFilesDirAndSurfacesReasonAndPath() = runBlocking {
        withHarness { harness ->
            val existing = sampleConversation("before-restore", "Keep My History")
            harness.conversations.saveConversation(existing)
            val archiveBytes = zipBytesFor(
                AppDataBackup(
                    exportedAt = "2026-10-25T00:00:00Z",
                    conversations = listOf(sampleConversation("imported", "Broken Import"))
                )
            )
            harness.transfer.applyFaultForTest = { isRollback ->
                if (!isRollback) error("Primary restore failed") else error("Rollback also failed")
            }

            val thrown = try {
                harness.transfer.import(ByteArrayInputStream(archiveBytes), "backup.zip") {}
                fail("Expected combined restore and rollback failure")
                null
            } catch (error: IllegalStateException) {
                error
            }!!

            val message = thrown.message.orEmpty()
            assertTrue("Message must include original cause: $message", message.contains("Primary restore failed"))
            // A coroutine/restore wrapper may preserve the failure in a nested cause.
            // Assert both root failures without depending on their wrapper depth.
            val causes = generateSequence(thrown as Throwable?) { it.cause }.toList()
            assertTrue(causes.any { it.message == "Primary restore failed" })
            assertTrue(causes.flatMap { it.suppressed.toList() }
                .any { it.message == "Rollback also failed" })

            val preserved = harness.transfer.recoveryDirectory.listFiles().orEmpty().singleOrNull()
            assertNotNull("Safety snapshot must remain in filesDir/backup-recovery", preserved)
            assertTrue(message.contains(preserved!!.absolutePath))
            assertTrue(preserved.parentFile?.absolutePath?.startsWith(harness.context.filesDir.absolutePath) == true)

            harness.transfer.applyFaultForTest = null
            FileInputStream(preserved).use { input ->
                harness.transfer.import(input, preserved.name) {}
            }
            assertEquals("Keep My History", harness.conversations.loadById("before-restore")?.title)
            preserved.delete()
        }
    }

    @Test
    fun pickerHandoffSaverAndAbandonedExportCleanupPreserveActiveAndRecoveryArchives() = runBlocking {
        withHarness { harness ->
            val saverScope = SaverScope { true }
            assertNull(with(NullableFilePathSaver) { saverScope.save(null) })
            assertNull(NullableFilePathSaver.restore(""))

            val exportDir = harness.transfer.exportCacheDirectory.apply { mkdirs() }
            val retained = File(exportDir, "retained.zip").apply { writeText("active") }
            val abandoned = File(exportDir, "abandoned.zip").apply { writeText("stale") }
            val recoveryDir = harness.transfer.recoveryDirectory.apply { mkdirs() }
            val recovery = File(recoveryDir, "recovery.zip").apply { writeText("keep") }

            val savedPath = with(NullableFilePathSaver) { saverScope.save(retained) }
            val restoredFile = NullableFilePathSaver.restore(savedPath!!)
            assertEquals(retained.absolutePath, restoredFile?.absolutePath)

            harness.transfer.cleanupAbandonedExports(retainedPath = restoredFile?.absolutePath)
            assertTrue(retained.exists())
            assertFalse(abandoned.exists())
            assertTrue(recovery.exists())

            harness.transfer.cleanupAbandonedExports()
            assertFalse(retained.exists())
            assertTrue(recovery.exists())
            recovery.delete()
        }
    }

    @Test
    fun restoreGateRejectsChatMutationsWithoutDirtyingStateOnFailedRestore() = runBlocking {
        withHarness { harness ->
            harness.preferences.setContinueLastConversation(false)
            val existing = sampleConversation("existing", "Original Title")
            harness.conversations.saveConversation(existing)
            val viewModel = harness.createChatViewModel()
            viewModel.selectConversation(existing)
            idleMain()
            awaitCondition { viewModel.conversations.value.any { it.id == "existing" } }

            viewModel.prepareForRestore()
            assertFalse(viewModel.sendMessage("Rejected during restore"))
            assertTrue(viewModel.queuedMessages.value.isEmpty())
            viewModel.deleteConversation("existing")
            viewModel.renameConversation("existing", "Should Not Stick")
            viewModel.clearAllConversations()
            idleMain()

            // Simulate a failed restore: resetAfterRestore() does not run, yet UI flags must be clean.
            viewModel.finishRestore(succeeded = false)
            idleMain()

            val visible = viewModel.conversations.first { it.isNotEmpty() }.single()
            assertEquals("existing", visible.id)
            assertEquals("Original Title", visible.title)
            assertEquals("Original Title", viewModel.currentConversation.value?.title)

            // Repeated deletion after the failed restore must still work and remove the conversation.
            viewModel.deleteConversation("existing")
            awaitCondition { runBlocking { harness.conversations.loadById("existing") == null } }
        }
    }

    @Test
    fun deletingSelectedChatsRemovesOnlyUnusedUploads() = verifyHistoryMediaDeletion(clearAll = false)

    @Test
    fun clearingHistoryPreservesGeneratedMedia() = verifyHistoryMediaDeletion(clearAll = true)

    private fun verifyHistoryMediaDeletion(clearAll: Boolean) = runBlocking {
        withHarness { harness ->
            harness.preferences.setContinueLastConversation(false)
            fun storedFile(name: String) = File(harness.files.directory, name).apply { writeText(name) }
            val upload = storedFile("upload.png")
            val sharedUpload = storedFile("shared-upload.png")
            val draftUpload = storedFile("draft-upload.png")
            val generated = storedFile("generated.png")
            val toolOutput = storedFile("tool-output.mp4")
            val outsideDocument = File(harness.root, "user-owned.png").apply { writeText("original") }
            val conversation = sampleConversation("drop", "Drop").copy(messages = listOf(
                StoredMessage("user", ChatRole.USER, listOf(
                    MessageVersion(content = "Old upload", files = listOf(upload.absolutePath)),
                    MessageVersion(content = "New attachments", files = listOf(
                        sharedUpload.absolutePath, draftUpload.absolutePath, generated.absolutePath,
                        toolOutput.toURI().toString(), outsideDocument.absolutePath,
                        "content://media/external/images/1"
                    ))
                ), activeVersionIndex = 1),
                StoredMessage("assistant", ChatRole.MODEL, listOf(MessageVersion(
                    content = "Generated media",
                    files = listOf("tool-file:${generated.name}"),
                    toolActivity = listOf(ToolActivity("tool", "generate_video", files = listOf("tool-file:${toolOutput.name}")))
                )))
            ))
            val keep = sampleConversation("keep", "Keep").copy(messages = listOf(
                StoredMessage("user", ChatRole.USER, listOf(MessageVersion(
                    content = "Shared upload", files = listOf(sharedUpload.absolutePath)
                )))
            ))
            harness.conversations.saveConversation(conversation)
            harness.conversations.saveConversation(keep)
            val viewModel = harness.createChatViewModel()
            idleMain()

            if (clearAll) {
                viewModel.clearAllConversations(listOf(draftUpload.absolutePath))
            } else {
                viewModel.deleteConversations(setOf(conversation.id), listOf(draftUpload.absolutePath))
            }
            awaitCondition { !upload.exists() }

            assertTrue("Assistant output must survive history deletion", generated.exists())
            assertTrue("Tool output must survive history deletion", toolOutput.exists())
            assertTrue("Files attached to the composer must survive", draftUpload.exists())
            assertTrue("User-owned documents must survive", outsideDocument.exists())
            assertEquals(!clearAll, sharedUpload.exists())
            assertNull(harness.conversations.loadById(conversation.id))
            if (clearAll) assertNull(harness.conversations.loadById(keep.id))
            else assertNotNull(harness.conversations.loadById(keep.id))
        }
    }

    @Test
    fun cancellingTitleGeneratorDuringRestorePreventsStaleConversationOverwrite() = runBlocking {
        val requestStarted = CountDownLatch(1)
        val allowResponse = CountDownLatch(1)
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requestStarted.countDown()
                allowResponse.await(5, TimeUnit.SECONDS)
                return MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"choices":[{"message":{"role":"assistant","content":"Leaked Old Title"}}]}""")
            }
        }
        server.start()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            withHarness { harness ->
                val preRestore = sampleConversation("conv-1", "Fallback")
                harness.conversations.saveConversation(preRestore)
                val currentConversation = MutableStateFlow<Conversation?>(preRestore)
                val restoring = AtomicBoolean(false)
                val provider = ModelProvider(
                    id = "provider",
                    name = "Test Provider",
                    type = ModelApiType.OPENAI,
                    auth = Authorization(value = "test-key"),
                    baseUrl = server.url("/v1").toString(),
                    config = ProviderConfiguration(
                        supportStream = false,
                        modelIds = listOf("gpt-test"),
                        modelConfigs = mapOf("gpt-test" to ModelConfiguration(displayName = "gpt-test"))
                    )
                )
                val generator = ConversationTitleGenerator(
                    scope = scope,
                    conversationRepo = harness.conversations,
                    toolChat = ToolChatViewModel(harness.http, harness.mcpService, harness.aiService),
                    currentConversation = currentConversation,
                    configuredTextModel = { provider to SystemToolConfig(providerId = provider.id, modelId = "gpt-test") },
                    existingGeneration = { null },
                    isRestoreInProgress = { restoring.get() },
                    withRestoreGuard = { if (!restoring.get()) it() },
                    writeConversation = { block ->
                        if (restoring.get()) {
                            false
                        } else {
                            scope.launch { block() }
                            true
                        }
                    }
                )

                generator.generate(preRestore, expectedFallbackTitle = "Fallback")
                assertTrue(requestStarted.await(5, TimeUnit.SECONDS))

                // Begin restore: block writes, cancel & join in-flight title jobs, then replace DB.
                restoring.set(true)
                allowResponse.countDown()
                generator.cancelAll()
                harness.conversations.replaceAll(listOf(sampleConversation("conv-1", "Restored Title")))
                currentConversation.value = null
                restoring.set(false)

                Thread.sleep(100)
                assertEquals("Restored Title", harness.conversations.loadById("conv-1")?.title)
            }
        } finally {
            allowResponse.countDown()
            scope.cancel()
            server.shutdown()
        }
    }

    private fun sampleConversation(id: String, title: String) = Conversation(
        id = id,
        title = title,
        createdAt = 1L,
        updatedAt = 2L,
        messages = listOf(
            StoredMessage("msg-$id", ChatRole.USER, listOf(MessageVersion(content = "Hello $id")))
        )
    )

    private fun zipBytesFor(backup: AppDataBackup): ByteArray =
        ByteArrayOutputStream().also { out ->
            writeZipBackup(out, backup, emptyMap(), emptyMap(), emptyMap())
        }.toByteArray()

    private class Harness(
        val context: Context,
        val db: AppDatabase,
        val root: File,
        val files: ToolFiles,
        val http: ToolHttp,
        val aiService: AiService,
        val mcpService: McpService,
        val conversations: ConversationRepository,
        val preferences: AppPreferencesRepository,
        val toolStore: ToolSettingsStore,
        val models: ModelRepository,
        val mcp: McpRepository,
        val speech: SpeechRepository,
        val transfer: AppDataTransferService
    ) {
        fun createChatViewModel(): ChatViewModel = ChatViewModel(
            conversationRepo = conversations,
            modelRepo = models,
            mcpRepo = mcp,
            prefsRepo = preferences,
            toolChat = ToolChatViewModel(http, mcpService, aiService),
            toolStore = toolStore,
            speechRepo = speech,
            ttsHelper = TtsHelper(context),
            speechSynthesis = SpeechSynthesisService(),
            speechAudioPlayer = SpeechAudioPlayer(context),
            toolFiles = files
        )
    }

    private suspend fun withHarness(block: suspend (Harness) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val root = Files.createTempDirectory("backup-resilience-test").toFile()
        try {
            val files = ToolFiles(File(root, "tool_media"))
            val http = ToolHttp(files)
            val aiService = AiService(context)
            val mcpOAuth = McpOAuthService(context, http)
            val mcpService = McpService(http, mcpOAuth)
            val icons = IconStore(context)
            val conversations = ConversationRepository(db)
            val profiles = ProfileRepository(db)
            val models = ModelRepository(db, aiService, icons)
            val mcp = McpRepository(db, mcpService, icons, mcpOAuth)
            val speech = SpeechRepository(db)
            val preferences = AppPreferencesRepository(context)
            val toolStore = ToolSettingsStore(context)
            val remoteMachines = RemoteMachineService(context)
            val transfer = AppDataTransferService(
                context = context,
                modelRepository = models,
                mcpRepository = mcp,
                speechRepository = speech,
                profileRepository = profiles,
                conversationRepository = conversations,
                preferencesRepository = preferences,
                toolSettingsStore = toolStore,
                iconStore = icons,
                toolFiles = files,
                remoteMachineService = remoteMachines
            )
            block(
                Harness(
                    context = context,
                    db = db,
                    root = root,
                    files = files,
                    http = http,
                    aiService = aiService,
                    mcpService = mcpService,
                    conversations = conversations,
                    preferences = preferences,
                    toolStore = toolStore,
                    models = models,
                    mcp = mcp,
                    speech = speech,
                    transfer = transfer
                )
            )
        } finally {
            db.close()
            root.deleteRecursively()
        }
    }

    private fun idleMain() {
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun awaitCondition(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            idleMain()
            if (condition()) return
            Thread.sleep(10)
        }
        idleMain()
        assertTrue(condition())
    }
}
