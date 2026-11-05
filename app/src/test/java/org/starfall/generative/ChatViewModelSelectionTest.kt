package org.starfall.generative

import android.content.Context
import android.os.Looper
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.starfall.generative.features.home.chat.logic.ChatViewModel
import org.starfall.generative.features.home.chat.logic.tool.ToolChatViewModel
import org.starfall.generative.integrations.mcp.McpService
import org.starfall.generative.integrations.modelapis.AiService
import org.starfall.generative.shared.subfeatures.media.logic.ToolFiles
import org.starfall.generative.integrations.speech.SpeechAudioPlayer
import org.starfall.generative.integrations.speech.SpeechSynthesisService
import org.starfall.generative.integrations.speech.TtsHelper
import org.starfall.generative.shared.model.ChatRole
import org.starfall.generative.shared.model.Conversation
import org.starfall.generative.shared.model.MessageVersion
import org.starfall.generative.shared.model.StoredMessage
import org.starfall.generative.shared.helpers.networking.ToolHttp
import org.starfall.generative.database.repositories.ConversationRepository
import org.starfall.generative.database.repositories.McpRepository
import org.starfall.generative.database.repositories.ModelRepository
import org.starfall.generative.database.repositories.SpeechRepository
import org.starfall.generative.database.room.AppDatabase
import org.starfall.generative.database.room.entities.ConversationMigrationQuarantineEntity
import org.starfall.generative.database.preferences.AppPreferencesRepository
import org.starfall.generative.database.preferences.ToolSettingsStore
import java.io.File
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = LibraryTestApplication::class)
@LooperMode(LooperMode.Mode.PAUSED)
class ChatViewModelSelectionTest {
    @Test fun startNewChatWinsOverAnInFlightConversationSelection() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val root = Files.createTempDirectory("selection-race").toFile()
        val files = ToolFiles(File(root, "tool_media"))
        val conversation = Conversation(
            id = "persisted",
            title = "Persisted",
            createdAt = 1,
            updatedAt = 1,
            messages = listOf(StoredMessage("message", ChatRole.USER, listOf(MessageVersion(content = "hello"))))
        )
        val conversationRepo = ConversationRepository(db)
        conversationRepo.saveConversation(conversation)
        val preferences = AppPreferencesRepository(context)
        preferences.setContinueLastConversation(false)
        val viewModel = createViewModel(context, db, files, conversationRepo, preferences)
        try {
            val database = db.openHelper.writableDatabase
            database.beginTransaction()
            viewModel.selectConversation(conversation.id)
            assertEquals("A disk read must be observable before messages arrive", conversation.id,
                viewModel.loadingConversationId.value)
            // Start the history read while SQLite is blocked, then create the blank draft before
            // releasing it. The old implementation applied the queued result after this point.
            shadowOf(Looper.getMainLooper()).idle()
            viewModel.startNewChat()
            assertNull("A new chat cancels the history loading veil", viewModel.loadingConversationId.value)
            database.setTransactionSuccessful()
            database.endTransaction()

            repeat(100) {
                shadowOf(Looper.getMainLooper()).idle()
                Thread.sleep(10)
            }

            assertEquals("New Chat", viewModel.currentConversation.value?.title)
            assertNotEquals(conversation.id, viewModel.currentConversation.value?.id)
        } finally {
            db.close()
            root.deleteRecursively()
        }
    }

    @Test fun deletingQuarantinedConversationDoesNotDecodeItOrDeleteSharedFiles() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val root = Files.createTempDirectory("quarantined-delete").toFile()
        val files = ToolFiles(File(root, "tool_media"))
        File(files.directory, "shared.bin").writeText("shared")
        File(files.directory, "only-here.bin").writeText("only here")
        val repository = ConversationRepository(db)
        val shared = Conversation("shared", "Shared", 1, 1, messages = listOf(
            StoredMessage("shared-message", ChatRole.USER, listOf(MessageVersion(files = listOf("tool-file:shared.bin"))))
        ))
        val quarantined = Conversation("quarantined", "Quarantined", 1, 1, messages = listOf(
            StoredMessage("quarantined-message", ChatRole.USER, listOf(MessageVersion(
                files = listOf("tool-file:shared.bin", "tool-file:only-here.bin")
            )))
        ))
        repository.saveConversation(shared)
        repository.saveConversation(quarantined)
        db.conversationDao().insertMigrationIssue(
            ConversationMigrationQuarantineEntity(quarantined.id, "messages_json", "{broken", "invalid_json")
        )
        val preferences = AppPreferencesRepository(context)
        preferences.setContinueLastConversation(false)
        val viewModel = createViewModel(context, db, files, repository, preferences)
        try {
            viewModel.deleteConversation(quarantined.id)
            awaitMain {
                db.openHelper.readableDatabase.query(
                    "SELECT COUNT(*) FROM conversations WHERE id = ?", arrayOf(quarantined.id)
                ).use { it.moveToFirst() && it.getLong(0) == 0L }
            }
            awaitMain { !File(files.directory, "only-here.bin").exists() }
            assertTrue(File(files.directory, "shared.bin").exists())
            assertFalse(File(files.directory, "only-here.bin").exists())
            db.openHelper.readableDatabase.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
        } finally {
            db.close()
            root.deleteRecursively()
        }
    }

    private fun createViewModel(
        context: Context,
        db: AppDatabase,
        files: ToolFiles,
        conversationRepo: ConversationRepository,
        preferences: AppPreferencesRepository
    ): ChatViewModel {
        val http = ToolHttp(files)
        val ai = AiService(context)
        return ChatViewModel(
            conversationRepo = conversationRepo,
            modelRepo = ModelRepository(db, ai),
            mcpRepo = McpRepository(db, McpService(http)),
            prefsRepo = preferences,
            toolChat = ToolChatViewModel(http, McpService(http), ai),
            toolStore = ToolSettingsStore(context),
            speechRepo = SpeechRepository(db),
            ttsHelper = TtsHelper(context),
            speechSynthesis = SpeechSynthesisService(),
            speechAudioPlayer = SpeechAudioPlayer(context),
            toolFiles = files
        )
    }

    private fun awaitMain(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (!condition() && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        assertTrue("ViewModel operation did not complete", condition())
    }
}
