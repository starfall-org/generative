package org.starfall.generative

import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.starfall.generative.shared.subfeatures.media.logic.ToolFileProvenance

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ToolFileProvenanceTest {
    @Test fun generatedAndImportedOwnershipSurviveReopeningTheIndex() {
        val root = Files.createTempDirectory("media-provenance").toFile()
        try {
            val index = root.resolve("media.index")
            val externalUri = "content://media/external/images/media/20"
            ToolFileProvenance(index).apply {
                markGenerated(listOf("generated.png"))
                recordImport(externalUri, "uploaded.jpg")
            }
            val recovered = ToolFileProvenance(index)
            assertTrue(recovered.isGenerated("generated.png"))
            assertEquals(setOf("uploaded.jpg"), recovered.importedCopies(externalUri))
            recovered.forget(listOf("uploaded.jpg"))
            val reopened = ToolFileProvenance(index)
            assertTrue(reopened.isGenerated("generated.png"))
            assertTrue(reopened.importedCopies(externalUri).isEmpty())
        } finally { root.deleteRecursively() }
    }

    @Test fun restoredProvenanceReplacesStaleOwnership() {
        val root = Files.createTempDirectory("media-restore").toFile()
        try {
            val index = ToolFileProvenance(root.resolve("media.index"))
            index.markGenerated(listOf("old.png"))
            index.recordImport("content://old", "old-copy.png")
            index.replace(listOf("new.png"), mapOf("content://new" to listOf("new-copy.png")))
            val restored = ToolFileProvenance(root.resolve("media.index"))
            assertFalse(restored.isGenerated("old.png"))
            assertTrue(restored.isGenerated("new.png"))
            assertTrue(restored.importedCopies("content://old").isEmpty())
            assertEquals(setOf("new-copy.png"), restored.importedCopies("content://new"))
        } finally { root.deleteRecursively() }
    }
}
