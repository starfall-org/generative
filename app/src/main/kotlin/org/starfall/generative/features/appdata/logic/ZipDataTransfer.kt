package org.starfall.generative.features.appdata.logic

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.starfall.generative.features.appdata.model.AppDataBackup
import org.starfall.generative.features.appdata.model.ZipAppDataBackup

private const val MANIFEST_ENTRY = "manifest.json"
private const val ICON_PREFIX = "icons/"
private const val FILE_PREFIX = "files/"
private const val UPLOAD_PREFIX = "uploads/"
private const val IDENTITY_PREFIX = "remote-identities/"
private const val SKILL_PREFIX = "skills/"
private const val MAX_ENTRY_BYTES = 256L * 1024 * 1024
private const val MAX_ARCHIVE_BYTES = 1L * 1024 * 1024 * 1024

private val backupJson = Json {
    prettyPrint = true
    encodeDefaults = true
    ignoreUnknownKeys = true
}

fun writeZipBackup(
    output: OutputStream,
    data: AppDataBackup,
    iconFiles: Map<String, File>,
    toolFiles: Map<String, File>,
    identityFiles: Map<String, Pair<File, File?>>,
    skillFiles: Map<String, File> = emptyMap(),
    uploadFiles: Map<String, File> = emptyMap()
) {
    val names = mutableSetOf<String>()
    ZipOutputStream(BufferedOutputStream(output)).use { zip ->
        var totalBytes = 0L

        fun checkEntrySize(name: String, size: Long) {
            check(size <= MAX_ENTRY_BYTES) { "Backup entry is too large: $name (maximum 256 MiB)." }
            check(totalBytes + size <= MAX_ARCHIVE_BYTES) { "Backup archive is too large (maximum 1 GiB)." }
        }

        fun addBytes(name: String, bytes: ByteArray) {
            checkEntrySize(name, bytes.size.toLong())
            check(names.add(name)) { "Duplicate backup entry: $name" }
            zip.putNextEntry(ZipEntry(name))
            zip.write(bytes)
            zip.closeEntry()
            totalBytes += bytes.size
        }

        fun addFile(name: String, file: File) {
            require(file.isFile) { "Backup source file is missing: ${file.name}" }
            checkEntrySize(name, file.length())
            check(names.add(name)) { "Duplicate backup entry: $name" }
            zip.putNextEntry(ZipEntry(name))
            file.inputStream().use { input ->
                val buffer = ByteArray(32768)
                var entryBytes = 0L
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    val nextEntryBytes = entryBytes + count
                    check(nextEntryBytes <= MAX_ENTRY_BYTES) {
                        "Backup entry is too large: $name (maximum 256 MiB)."
                    }
                    check(totalBytes + count <= MAX_ARCHIVE_BYTES) {
                        "Backup archive is too large (maximum 1 GiB)."
                    }
                    zip.write(buffer, 0, count)
                    entryBytes = nextEntryBytes
                    totalBytes += count
                }
            }
            zip.closeEntry()
        }

        addBytes(MANIFEST_ENTRY, backupJson.encodeToString(data).toByteArray(Charsets.UTF_8))
        iconFiles.forEach { (name, file) -> addFile(ICON_PREFIX + name, file) }
        toolFiles.forEach { (name, file) -> addFile(FILE_PREFIX + name, file) }
        uploadFiles.forEach { (name, file) -> addFile(UPLOAD_PREFIX + name, file) }
        identityFiles.forEach { (id, files) ->
            addFile("$IDENTITY_PREFIX$id", files.first)
            files.second?.let { addFile("$IDENTITY_PREFIX$id.pub", it) }
        }
        skillFiles.forEach { (path, file) ->
            require(validSkillBackupPath(path)) { "Unsafe skill backup path: $path" }
            addFile(SKILL_PREFIX + path, file)
        }
    }
}

/** A skill ZIP member must be a regular relative file under a valid skill name. */
private fun validSkillBackupPath(path: String): Boolean {
    val parts = path.split('/')
    return parts.size in 2..7 &&
        org.starfall.generative.shared.model.isValidAgentSkillName(parts.first()) &&
        parts.first() != org.starfall.generative.shared.model.DEFAULT_AGENT_SKILL_ID &&
        parts.drop(1).all { it.isNotBlank() && it != "." && it != ".." && '\\' !in it && ':' !in it }
}

/** Converts the previous flat AppPreferences snapshot to the grouped schema without losing state. */
private fun migrateVersionTwoBackup(root: JsonObject): JsonObject {
    val appState = root["appState"] as? JsonObject ?: return JsonObject(root + ("formatVersion" to JsonPrimitive(3)))
    val state = appState.toMutableMap()

    val sidebar = state.remove("sidebar") ?: buildJsonObject { }
    val lastConversation = state.remove("lastActiveConversationId") ?: JsonNull
    val mcpInitialized = state.remove("mcpPresetsInitialized") ?: JsonPrimitive(false)
    val contentApiInitialized = state.remove("contentApiPresetInitialized") ?: JsonPrimitive(false)
    state["chatRelations"] = buildJsonObject {
        put("sidebar", sidebar)
        put("lastActiveConversationId", lastConversation)
        put("mcpPresetsInitialized", mcpInitialized)
        put("contentApiPresetInitialized", contentApiInitialized)
    }

    state.remove("selectedProfileId")
    val providerId = state.remove("selectedProviderId")?.jsonPrimitive?.contentOrNull ?: ""
    val modelId = state.remove("selectedModelId")?.jsonPrimitive?.contentOrNull ?: ""
    state["selectedModel"] = buildJsonObject {
        put("providerId", JsonPrimitive(providerId))
        put("modelId", JsonPrimitive(modelId))
    }

    val legacyPrompt = state.remove("defaultSystemPrompt")?.jsonPrimitive?.contentOrNull.orEmpty()
    val savedLibrary = state["instructionLibrary"]
    if (savedLibrary == null || savedLibrary == JsonNull) {
        state["instructionLibrary"] = if (legacyPrompt.isBlank()) {
            buildJsonObject {
                put("prompts", JsonArray(emptyList()))
                put("selectedIds", JsonArray(emptyList()))
            }
        } else {
            buildJsonObject {
                put("prompts", JsonArray(listOf(buildJsonObject {
                    put("id", JsonPrimitive("legacy-system-prompt"))
                    put("name", JsonPrimitive("Default system instruction"))
                    put("content", JsonPrimitive(legacyPrompt))
                    put("role", JsonPrimitive("system"))
                })))
                put("selectedIds", JsonArray(listOf(JsonPrimitive("legacy-system-prompt"))))
            }
        }
    }

    val oldSkills = state["agentSkills"]
    if (oldSkills is JsonArray) {
        state["agentSkills"] = buildJsonObject { put("skills", oldSkills) }
    }

    return JsonObject(root + mapOf(
        "formatVersion" to JsonPrimitive(3),
        "appState" to JsonObject(state)
    ))
}

/** Extracts a ZIP backup into [directory] after validating all paths and size limits. */
fun readZipBackup(input: InputStream, directory: File): ZipAppDataBackup {
    require(directory.mkdirs() || directory.isDirectory) { "Could not create backup staging directory." }
    var totalBytes = 0L
    var manifest: AppDataBackup? = null
    val iconFiles = linkedMapOf<String, File>()
    val toolFiles = linkedMapOf<String, File>()
    val uploadFiles = linkedMapOf<String, File>()
    val privateIdentities = linkedMapOf<String, File>()
    val publicIdentities = linkedMapOf<String, File>()
    val skillFiles = linkedMapOf<String, File>()
    var extractionIndex = 0
    val seen = mutableSetOf<String>()

    ZipInputStream(BufferedInputStream(input)).use { zip ->
        while (true) {
            val entry = zip.nextEntry ?: break
            if (entry.isDirectory) continue
            val name = entry.name
            check(seen.add(name)) { "Duplicate backup entry: $name" }
            check(name == MANIFEST_ENTRY || name.startsWith(ICON_PREFIX) || name.startsWith(FILE_PREFIX) ||
                name.startsWith(IDENTITY_PREFIX) || name.startsWith(SKILL_PREFIX) ||
                name.startsWith(UPLOAD_PREFIX)) {
                "Unsupported backup entry: $name"
            }
            check(!name.startsWith('/') && ".." !in name.split('/')) { "Unsafe backup entry path." }
            val relative = when {
                name == MANIFEST_ENTRY -> "manifest.json"
                name.startsWith(ICON_PREFIX) -> name.removePrefix(ICON_PREFIX)
                name.startsWith(FILE_PREFIX) -> name.removePrefix(FILE_PREFIX)
                name.startsWith(UPLOAD_PREFIX) -> name.removePrefix(UPLOAD_PREFIX)
                name.startsWith(SKILL_PREFIX) -> name.removePrefix(SKILL_PREFIX)
                else -> name.removePrefix(IDENTITY_PREFIX)
            }
            require(if (name.startsWith(SKILL_PREFIX)) validSkillBackupPath(relative)
                else relative.isNotBlank() && '/' !in relative) { "Invalid backup entry name: $name" }
            // Flat transformed names can collide; use unique staged files instead.
            val destination = File(directory, "entry_${++extractionIndex}")
            var written = 0L
            destination.outputStream().use { output ->
                val buffer = ByteArray(32768)
                while (true) {
                    val count = zip.read(buffer)
                    if (count < 0) break
                    written += count
                    totalBytes += count
                    check(written <= MAX_ENTRY_BYTES) { "Backup entry is too large: $name" }
                    check(totalBytes <= MAX_ARCHIVE_BYTES) { "Backup archive is too large." }
                    output.write(buffer, 0, count)
                }
            }
            when {
                name == MANIFEST_ENTRY -> {
                    val root = backupJson.parseToJsonElement(destination.readText(Charsets.UTF_8)).jsonObject
                    val version = root["formatVersion"]?.jsonPrimitive?.intOrNull
                    val compatibleRoot = if (version == 2) migrateVersionTwoBackup(root) else root
                    manifest = backupJson.decodeFromJsonElement(compatibleRoot)
                }
                name.startsWith(ICON_PREFIX) -> iconFiles[relative] = destination
                name.startsWith(FILE_PREFIX) -> toolFiles[relative] = destination
                name.startsWith(UPLOAD_PREFIX) -> uploadFiles[relative] = destination
                name.startsWith(SKILL_PREFIX) -> skillFiles[relative] = destination
                name.startsWith(IDENTITY_PREFIX) && relative.endsWith(".pub") ->
                    publicIdentities[relative.removeSuffix(".pub")] = destination
                name.startsWith(IDENTITY_PREFIX) -> privateIdentities[relative] = destination
            }
            zip.closeEntry()
        }
    }

    val data = requireNotNull(manifest) { "The backup archive has no manifest." }
    require(data.type == "full_backup") { "The archive is not a Generative full backup." }
    require(data.formatVersion == AppDataBackup.CURRENT_FORMAT_VERSION) { "Unsupported backup version." }
    val identities = privateIdentities.mapValues { (id, file) -> file to publicIdentities[id] }
    return ZipAppDataBackup(data, iconFiles, toolFiles, identities, skillFiles, uploadFiles)
}
