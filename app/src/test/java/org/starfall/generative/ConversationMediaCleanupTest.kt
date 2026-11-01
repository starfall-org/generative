package org.starfall.generative

import androidx.room.Room
import android.net.Uri
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.starfall.generative.database.AppDatabase
import org.starfall.generative.database.ConversationRepository
import org.starfall.generative.features.home.chat.logic.service.deleteUnreferencedChatUploads
import org.starfall.generative.shared.files.TemporaryChatFiles
import org.starfall.generative.shared.files.ToolFiles
import org.starfall.generative.shared.files.importUserAttachment
import org.starfall.generative.shared.model.ChatRole
import org.starfall.generative.shared.model.Conversation
import org.starfall.generative.shared.model.MessageVersion
import org.starfall.generative.shared.model.StoredMessage

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ConversationMediaCleanupTest {
    private fun chat(id: String, role: ChatRole, file: String): Conversation = Conversation(
        id, id, 1, 1, messages = listOf(StoredMessage("message", role,
            listOf(MessageVersion(files = listOf(file)))))
    )

    @Test fun importedUserCopyIsRemovedOnlyAfterTheLastChatStopsUsingIt() = runBlocking {
        val root = Files.createTempDirectory("import-cleanup").toFile()
        val store = ToolFiles(File(root, "tool_media"))
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java).build()
        try {
            val repo = ConversationRepository(db, toolFiles = store)
            val source = "content://media/external/images/media/44"
            val copy = File(store.directory, "copy.jpg").apply { writeText("user's uploaded image") }
            store.recordImportedAttachment(source, copy.name)
            repo.saveConversation(chat("first", ChatRole.USER, source))
            repo.saveConversation(chat("second", ChatRole.USER, source))

            repo.deleteConversation("first")
            deleteUnreferencedChatUploads(repo, store, setOf(copy.name), repo.userFileReferences())
            assertTrue(copy.exists())

            repo.deleteConversation("second")
            deleteUnreferencedChatUploads(repo, store, setOf(copy.name), repo.userFileReferences())
            assertFalse(copy.exists())
            assertTrue(store.importedCopyNames(source).isEmpty())
        } finally { db.close(); root.deleteRecursively() }
    }

    @Test fun deletingChatRemovesDurableUploadButNeverTheOriginalDocument() = runBlocking {
        val root = Files.createTempDirectory("durable-chat-cleanup").toFile()
        val store = ToolFiles(File(root, "tool_media"), File(root, "UploadCaches"))
        val original = File(root, "source.jpg").apply { writeText("original user photo") }
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val attached = importUserAttachment(context, store, Uri.fromFile(original), "image/jpeg")
            val repo = ConversationRepository(db, toolFiles = store)
            repo.saveConversation(chat("session", ChatRole.USER, attached))
            assertTrue(original.delete())
            assertEquals("original user photo", File(attached).readText())
            repo.deleteConversation("session")
            deleteUnreferencedChatUploads(repo, store, setOf(store.nameOf(attached)!!), emptyList())
            assertFalse(File(attached).exists())
            assertFalse(original.exists())
        } finally { db.close(); root.deleteRecursively() }
    }

    @Test fun generatedFileSurvivesDeletingItsOriginAndAChatThatReattachesIt() = runBlocking {
        val root = Files.createTempDirectory("generated-cleanup").toFile()
        val store = ToolFiles(File(root, "tool_media"))
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java).build()
        try {
            val repo = ConversationRepository(db, toolFiles = store)
            val output = File(store.directory, "output.png").apply { writeText("generated image") }
            repo.saveConversation(chat("origin", ChatRole.MODEL, "tool-file:${output.name}"))
            repo.deleteConversation("origin")
            repo.saveConversation(chat("reuse", ChatRole.USER, output.absolutePath))
            repo.deleteConversation("reuse")
            deleteUnreferencedChatUploads(repo, store, setOf(output.name), repo.userFileReferences())
            assertTrue(output.exists())
            assertTrue(store.isGeneratedMedia(output.name))
        } finally { db.close(); root.deleteRecursively() }
    }

    @Test fun deletingAChatDoesNotDeleteANameCollisionInTemporaryChatCache() = runBlocking {
        val root = Files.createTempDirectory("temp-collision").toFile()
        val store = ToolFiles(File(root, "tool_media"))
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java).build()
        try {
            val permanent = File(store.directory, "same.png").apply { writeText("uploaded") }
            val cache = File(root, "cache")
            store.openTemporarySession("temporary", cache)
            val temporary = File(cache, "temporary/same.png").apply { writeText("temporary") }
            val repo = ConversationRepository(db, toolFiles = store)
            repo.saveConversation(chat("remove", ChatRole.USER, permanent.absolutePath))
            repo.deleteConversation("remove")
            deleteUnreferencedChatUploads(repo, store, setOf(permanent.name), emptyList())
            assertFalse(permanent.exists())
            assertTrue(temporary.exists())
            store.closeTemporarySession("temporary")
        } finally { db.close(); root.deleteRecursively() }
    }

    @Test fun temporaryChatImportsDoNotCreatePermanentOwnership() = runBlocking {
        val root = Files.createTempDirectory("temporary-cleanup").toFile()
        val store = ToolFiles(File(root, "tool_media"))
        try {
            withContext(TemporaryChatFiles("temporary-id")) {
                store.recordImportedAttachment("content://temporary/file", "temporary.png")
                store.markGeneratedMedia(listOf("tool-file:temporary.png"))
            }
            assertTrue(store.importedCopyNames("content://temporary/file").isEmpty())
            assertFalse(store.isGeneratedMedia("temporary.png"))
        } finally { root.deleteRecursively() }
    }
}
