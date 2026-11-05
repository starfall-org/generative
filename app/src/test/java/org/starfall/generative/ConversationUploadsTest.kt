package org.starfall.generative

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.starfall.generative.shared.subfeatures.media.logic.ToolFiles
import org.starfall.generative.shared.subfeatures.media.logic.uploadedFileNames
import org.starfall.generative.shared.model.*
import org.starfall.generative.database.repositories.ConversationRepository
import org.starfall.generative.database.room.AppDatabase
import java.io.File
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ConversationUploadsTest {
    private fun files(): Pair<ToolFiles, File> {
        val root = Files.createTempDirectory("uploads").toFile()
        return ToolFiles(File(root, "tool_media")) to File(root, "tool_media")
    }

    private fun user(vararg refs: String) =
        StoredMessage("u${refs.hashCode()}", ChatRole.USER, listOf(MessageVersion(content = "hi", files = refs.toList())))

    private fun chat(id: String, vararg messages: StoredMessage) = Conversation(id, id, 1, 1, messages = messages.toList())

    @Test fun nameOfAcceptsEveryStoredFormOfAnAppPrivateFileAndNothingElse() {
        val (store, dir) = files()
        File(dir, "a.png").writeText("x")
        assertEquals("a.png", store.nameOf("a.png"))
        assertEquals("a.png", store.nameOf("tool-file:a.png"))
        assertEquals("a.png", store.nameOf(File(dir, "a.png").absolutePath))
        assertEquals("a.png", store.nameOf(File(dir, "a.png").toURI().toString()))
        assertNull(store.nameOf("content://media/external/images/1"))
        assertNull(store.nameOf("/somewhere/else/a.png"))
        assertNull(store.nameOf("${dir.absolutePath}/../outside.png"))
        assertNull(store.nameOf("a.png.part"))
    }

    @Test fun uploadedFilesIgnoreUserOwnedDocumentsAndGeneratedOutput() {
        val (store, dir) = files()
        val pasted = File(dir, "pasted.png").absolutePath
        val reused = File(dir, "generated.png").absolutePath
        val generated = StoredMessage("m", ChatRole.MODEL, listOf(MessageVersion(
            files = listOf("tool-file:generated.png"),
            toolActivity = listOf(ToolActivity("t", "tool", files = listOf("tool-file:other.png"))))))
        val conversation = chat("c",
            user(pasted, "content://media/external/images/1"),
            user(reused),
            generated)
        assertEquals(setOf("pasted.png"), conversation.uploadedFileNames(store))
    }

    @Test fun sendFileForwardingAnUploadDoesNotMakeItGenerated() = runBlocking {
        val (store, dir) = files()
        val uploaded = File(dir, "uploaded.png").apply { writeText("user content") }
        val forwarded = StoredMessage("assistant", ChatRole.MODEL, listOf(MessageVersion(
            files = listOf("tool-file:uploaded.png"),
            toolActivity = listOf(ToolActivity(
                "forward", "Send File", wireName = "send_file",
                files = listOf("uploaded.png")
            ))
        )))
        val conversation = chat("c", user(uploaded.absolutePath), forwarded)
        assertEquals(setOf("uploaded.png"), conversation.uploadedFileNames(store))

        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java).build()
        try {
            val repo = ConversationRepository(db, toolFiles = store)
            repo.saveConversation(conversation)
            assertTrue(repo.generatedFileReferences().isEmpty())
            assertFalse(store.isGeneratedMedia("uploaded.png"))
        } finally { db.close() }
    }

    @Test fun uploadsFromEditedMessageVersionsAreIncluded() {
        val (store, dir) = files()
        val old = File(dir, "old.png").absolutePath
        val new = File(dir, "new.png").absolutePath
        val edited = StoredMessage("u", ChatRole.USER, listOf(MessageVersion(files = listOf(old)), MessageVersion(files = listOf(new))), activeVersionIndex = 1)
        assertEquals(setOf("old.png", "new.png"), chat("c", edited).uploadedFileNames(store))
    }

    @Test fun externalUserAttachmentCopiesAreTrackedWithoutOwningTheOriginalUri() = runBlocking {
        val (store, dir) = files()
        val external = "content://media/external/images/media/17"
        File(dir, "copied.png").writeText("upload")
        store.recordImportedAttachment(external, "copied.png")
        assertNull(store.nameOf(external)) // Never delete the user's original document.
        assertEquals(setOf("copied.png"), chat("one", user(external)).uploadedFileNames(store))
        assertEquals(setOf("copied.png"), store.importedCopyNames(external))

        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java).build()
        try {
            val repository = ConversationRepository(db)
            repository.saveConversation(chat("one", user(external)))
            repository.saveConversation(chat("two", user(external)))
            repository.deleteConversation("one")
            assertTrue(external in repository.userFileReferences())
            assertTrue(store.importedCopyNames(external).isNotEmpty())
            repository.deleteConversation("two")
            assertFalse(external in repository.userFileReferences())
            assertEquals(setOf("copied.png"), repository.unreferencedFiles(store.importedCopyNames(external)))
            store.delete(setOf("copied.png"))
            assertFalse(File(dir, "copied.png").exists())
            assertTrue(store.importedCopyNames(external).isEmpty())
        } finally { db.close() }
    }

    @Test fun aGeneratedFileStaysProtectedAfterItsOriginalConversationIsDeleted() = runBlocking {
        val (store, dir) = files()
        File(dir, "generated.png").writeText("image")
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java).build()
        try {
            val repository = ConversationRepository(db, toolFiles = store)
            val output = StoredMessage("m", ChatRole.MODEL,
                listOf(MessageVersion(files = listOf("tool-file:generated.png"))))
            repository.saveConversation(chat("origin", output))
            assertTrue(store.isGeneratedMedia("generated.png"))
            repository.deleteConversation("origin")
            assertTrue(store.isGeneratedMedia("generated.png"))

            // Reuse the same generated output as a user attachment in another conversation.
            repository.saveConversation(chat("reuse", user(File(dir, "generated.png").absolutePath)))
            repository.deleteConversation("reuse")
            assertTrue(store.isGeneratedMedia("generated.png"))
            assertTrue(File(dir, "generated.png").isFile)
        } finally { db.close() }
    }

    @Test fun sharedFileIsOnlyReleasedWhenNoRemainingChatMentionsIt() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java).build()
        try {
            val (_, dir) = files()
            val shared = File(dir, "shared.png").absolutePath
            val alone = File(dir, "alone.png").absolutePath
            val repository = ConversationRepository(db)
            repository.saveConversation(chat("keep", user(shared)))
            repository.saveConversation(chat("drop", user(shared, alone)))
            repository.deleteConversations(listOf("drop"))
            assertEquals(setOf("alone.png"), repository.unreferencedFiles(listOf("shared.png", "alone.png")))
            repository.deleteConversations(listOf("keep"))
            assertEquals(setOf("shared.png", "alone.png"), repository.unreferencedFiles(listOf("shared.png", "alone.png")))
        } finally { db.close() }
    }
}
