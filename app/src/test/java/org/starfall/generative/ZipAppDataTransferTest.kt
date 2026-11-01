package org.starfall.generative

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.io.RandomAccessFile
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.starfall.generative.features.appdata.logic.readZipBackup
import org.starfall.generative.features.appdata.logic.writeZipBackup
import org.starfall.generative.features.appdata.model.AppDataBackup
import org.starfall.generative.shared.icons.model.IconRule
import org.starfall.generative.shared.icons.model.IconStoreState
import org.starfall.generative.shared.model.ToolSettings

class ZipAppDataTransferTest {
    @Test fun completeBackupRoundTripsManifestAndBinaryAssets() {
        val source = Files.createTempDirectory("generative-backup-source").toFile()
        val staging = Files.createTempDirectory("generative-backup-staging").toFile()
        try {
            val icon = source.resolve("icon-a.png").apply { writeBytes(byteArrayOf(1, 2, 3)) }
            val upload = source.resolve("upload.pdf").apply { writeBytes(byteArrayOf(4, 5, 6, 7)) }
            val identity = source.resolve("identity").apply { writeText("PRIVATE KEY") }
            val publicIdentity = source.resolve("identity.pub").apply { writeText("ssh-rsa public") }
            val backup = AppDataBackup(
                exportedAt = "2026-10-25T00:00:00Z",
                toolSettings = ToolSettings(sendFileEnabled = false),
                icons = IconStoreState(
                    rules = listOf(IconRule(pattern = "Provider", image = icon.name)),
                    files = listOf(icon.name)
                ),
                storedFileNames = listOf(upload.name),
                appPreferences = AppDataBackup(exportedAt = "2026-10-25T00:00:00Z").appPreferences.copy(featuresLogoImage = icon.name),
                mcpOAuthTokens = mapOf("server" to JsonObject(emptyMap()))
            )

            val bytes = ByteArrayOutputStream().also {
                writeZipBackup(
                    it,
                    backup,
                    iconFiles = mapOf(icon.name to icon),
                    toolFiles = mapOf(upload.name to upload),
                    identityFiles = mapOf("identity-id" to (identity to publicIdentity))
                )
            }.toByteArray()
            val restored = readZipBackup(ByteArrayInputStream(bytes), staging)

            assertEquals(backup, restored.data)
            assertEquals(byteArrayOf(1, 2, 3).toList(), restored.iconFiles.getValue(icon.name).readBytes().toList())
            assertEquals(byteArrayOf(4, 5, 6, 7).toList(), restored.toolFiles.getValue(upload.name).readBytes().toList())
            assertEquals("PRIVATE KEY", restored.remoteIdentityFiles.getValue("identity-id").first.readText())
            assertEquals("ssh-rsa public", restored.remoteIdentityFiles.getValue("identity-id").second!!.readText())
            assertTrue(bytes.copyOfRange(0, 2).contentEquals(byteArrayOf('P'.code.toByte(), 'K'.code.toByte())))
        } finally {
            source.deleteRecursively()
            staging.deleteRecursively()
        }
    }

    @Test fun uploadCachesAndGeneratedMediaRoundTripToSeparateZipDirectories() {
        val source = Files.createTempDirectory("generative-separated-files").toFile()
        val staging = Files.createTempDirectory("generative-separated-staging").toFile()
        try {
            val generated = File(source, "generated.png").apply { writeText("model output") }
            val uploaded = File(source, "upload_document.pdf").apply { writeText("user attachment") }
            val backup = AppDataBackup(exportedAt = "2026-11-01T00:00:00Z",
                storedFileNames = listOf(generated.name), uploadFileNames = listOf(uploaded.name))
            val bytes = ByteArrayOutputStream().also { output ->
                writeZipBackup(output, backup, emptyMap(), mapOf(generated.name to generated),
                    emptyMap(), uploadFiles = mapOf(uploaded.name to uploaded))
            }.toByteArray()
            val restored = readZipBackup(ByteArrayInputStream(bytes), staging)
            assertEquals(backup, restored.data)
            assertEquals("model output", restored.toolFiles.getValue(generated.name).readText())
            assertEquals("user attachment", restored.uploadFiles.getValue(uploaded.name).readText())
        } finally { source.deleteRecursively(); staging.deleteRecursively() }
    }

    @Test fun skillDirectoryResourcesRoundTripWithoutFlatteningPaths() {
        val source = Files.createTempDirectory("generative-skill-backup-").toFile()
        val staging = Files.createTempDirectory("generative-skill-staging-").toFile()
        try {
            val document = source.resolve("SKILL.md").apply { writeText("---\nname: review\ndescription: Review files\n---\n\n# Review") }
            val reference = source.resolve("REFERENCE.md").apply { writeText("Do not lose references") }
            val nested = source.resolve("template.txt").apply { writeText("nested content") }
            val backup = AppDataBackup(exportedAt = "2026-10-27T00:00:00Z")
            val bytes = ByteArrayOutputStream().also { output ->
                writeZipBackup(output, backup, emptyMap(), emptyMap(), emptyMap(),
                    mapOf(
                        "review/SKILL.md" to document,
                        "review/references/REFERENCE.md" to reference,
                        "review/assets/reference/template.txt" to nested
                    ))
            }.toByteArray()
            val restored = readZipBackup(ByteArrayInputStream(bytes), staging)
            assertEquals(setOf("review/SKILL.md", "review/references/REFERENCE.md", "review/assets/reference/template.txt"),
                restored.skillFiles.keys)
            assertEquals("Do not lose references", restored.skillFiles.getValue("review/references/REFERENCE.md").readText())
            assertEquals("nested content", restored.skillFiles.getValue("review/assets/reference/template.txt").readText())
        } finally {
            source.deleteRecursively()
            staging.deleteRecursively()
        }
    }

    @Test fun refusesArchiveSkillDirectoryTraversal() {
        val bytes = ByteArrayOutputStream().also { output ->
            ZipOutputStream(output).use { zip ->
                zip.putNextEntry(ZipEntry("skills/review/../secret.txt"))
                zip.write("no".toByteArray())
                zip.closeEntry()
            }
        }.toByteArray()
        val staging = Files.createTempDirectory("generative-skill-traversal-").toFile()
        try {
            try {
                readZipBackup(ByteArrayInputStream(bytes), staging)
                org.junit.Assert.fail("Traversal should have been rejected")
            } catch (_: IllegalStateException) { }
        } finally { staging.deleteRecursively() }
    }

    @Test(expected = IllegalStateException::class)
    fun rejectsBackupEntriesLargerThanImportLimitDuringExport() {
        val largeFile = Files.createTempFile("generative-oversized-backup", ".bin").toFile()
        try {
            RandomAccessFile(largeFile, "rw").use { it.setLength(256L * 1024 * 1024 + 1) }
            writeZipBackup(
                ByteArrayOutputStream(),
                AppDataBackup(exportedAt = "2026-10-25T00:00:00Z"),
                iconFiles = emptyMap(),
                toolFiles = mapOf(largeFile.name to largeFile),
                identityFiles = emptyMap()
            )
        } finally {
            largeFile.delete()
        }
    }

    @Test(expected = IllegalStateException::class)
    fun rejectsPathTraversalEntries() {
        val bytes = ByteArrayOutputStream().also { output ->
            ZipOutputStream(output).use { zip ->
                zip.putNextEntry(ZipEntry("../manifest.json"))
                zip.write("{}".toByteArray())
                zip.closeEntry()
            }
        }.toByteArray()
        val staging = Files.createTempDirectory("generative-invalid-backup").toFile()
        try {
            readZipBackup(ByteArrayInputStream(bytes), staging)
        } finally {
            staging.deleteRecursively()
        }
    }
}
