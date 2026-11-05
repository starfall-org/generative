package org.starfall.generative.integrations.files

import android.content.Context
import java.io.File
import java.nio.charset.CodingErrorAction
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.starfall.generative.shared.model.AgentSkill
import org.starfall.generative.shared.model.DEFAULT_AGENT_SKILL_ID
import org.starfall.generative.shared.model.isValidAgentSkillName
import org.starfall.generative.shared.model.parseAgentSkillDocument
import org.starfall.generative.shared.model.agentSkillDocument

/** Stores user Agent Skills under the Android-managed app files directory. */
class AgentSkillsStore(private val directory: File) {
    constructor(context: Context) : this(File(context.filesDir, DIRECTORY_NAME)) {
        // Recover skills from builds that temporarily inserted an unnecessary appdata layer.
        val mistakenlyNestedRoot = File(context.filesDir, "appdata")
        migrateLegacyDirectory(File(mistakenlyNestedRoot, DIRECTORY_NAME))
        if (mistakenlyNestedRoot.list()?.isEmpty() == true) mistakenlyNestedRoot.delete()
    }

    private val lock = Any()

    init {
        directory.mkdirs()
    }

    /** Recover skills from a previous location without overwriting the current directory. */
    private fun migrateLegacyDirectory(legacyDirectory: File) = synchronized(lock) {
        if (!legacyDirectory.isDirectory) return@synchronized
        legacyDirectory.listFiles()?.filter { it.isDirectory }?.forEach { oldFolder ->
            if (!isValidAgentSkillName(oldFolder.name) || oldFolder.name == DEFAULT_AGENT_SKILL_ID) {
                return@forEach
            }
            // The skill already in the correct app files directory is authoritative.
            val newFolder = File(directory, oldFolder.name)
            if (newFolder.exists()) return@forEach
            // Only move recognizable skills; leave other files in their old location.
            if (readSkill(oldFolder) == null) return@forEach
            check(oldFolder.renameTo(newFolder)) {
                "Could not relocate skill ${oldFolder.name}. Original files are unchanged."
            }
        }
        if (legacyDirectory.list()?.isEmpty() == true) legacyDirectory.delete()
    }

    fun readBlocking(): List<AgentSkill> = synchronized(lock) {
        val skills = directory.listFiles()
            ?.asSequence()
            ?.filter { it.isDirectory }
            ?.sortedBy { it.name }
            ?.mapNotNull(::readSkill)
            ?.toList()
            .orEmpty()
        val orderFile = File(directory, ORDER_FILE_NAME)
        val priority = if (orderFile.isFile && orderFile.length() <= MAX_ORDER_BYTES) {
            runCatching { orderFile.readLines().distinct().withIndex().associate { it.value to it.index } }
                .getOrDefault(emptyMap())
        } else emptyMap()
        skills.sortedWith(compareBy<AgentSkill> { priority[it.name] ?: Int.MAX_VALUE }.thenBy { it.name })
    }

    suspend fun replaceAll(skills: List<AgentSkill>) = withContext(Dispatchers.IO) {
        synchronized(lock) {
            directory.mkdirs()
            val normalized = skills
                .filterNot { it.id == DEFAULT_AGENT_SKILL_ID || it.name == DEFAULT_AGENT_SKILL_ID }
                .map(::validateSkill)
                .associateBy { it.name }

            directory.listFiles()
                // Do not delete a malformed or externally managed skill folder: it may
                // contain valuable files that simply aren't parseable by this client.
                ?.filter { it.isDirectory && it.name !in normalized.keys &&
                    it.name != DEFAULT_AGENT_SKILL_ID && readSkill(it) != null }
                ?.forEach { it.deleteRecursively() }

            normalized.values.forEach(::writeSkill)
            // Preserve user-defined order across process restarts without reordering folders.
            val order = File(directory, ORDER_FILE_NAME)
            val temporary = File(directory, ".$ORDER_FILE_NAME.${UUID.randomUUID()}.tmp")
            try {
                temporary.writeText(normalized.keys.joinToString("\n"), Charsets.UTF_8)
                if (!temporary.renameTo(order)) {
                    check(order.delete() && temporary.renameTo(order)) { "Could not update Agent Skills order." }
                }
            } finally {
                temporary.delete()
            }
        }
    }

    suspend fun delete(name: String) = withContext(Dispatchers.IO) {
        synchronized(lock) {
            val target = safeChild(name)
            if (target != null) target.deleteRecursively()
        }
    }

    private fun readSkill(skillDirectory: File): AgentSkill? {
        val file = File(skillDirectory, SKILL_FILE_NAME)
        if (!file.isFile || !isValidAgentSkillName(skillDirectory.name)) return null
        val text = runCatching { file.readText(Charsets.UTF_8) }.getOrNull() ?: return null
        return parseSkillFile(skillDirectory.name, text)
    }

    private fun parseSkillFile(directoryName: String, text: String): AgentSkill? =
        parseAgentSkillDocument(text, expectedName = directoryName)

    /** Read directly from the APK: bundled skills are never copied into mutable user storage. */
    fun readBundledCreateSkill(context: Context): AgentSkill {
        val text = context.assets.open("$DIRECTORY_NAME/$DEFAULT_AGENT_SKILL_ID/$SKILL_FILE_NAME")
            .bufferedReader(Charsets.UTF_8).use { it.readText() }
        return requireNotNull(parseSkillFile(DEFAULT_AGENT_SKILL_ID, text)) {
            "Bundled create-skill/SKILL.md is invalid"
        }
    }

    /** Export complete valid user-skill folders (including optional YAML fields and resources). */
    fun exportFiles(): Map<String, File> = synchronized(lock) {
        val results = linkedMapOf<String, File>()
        directory.listFiles()?.filter { it.isDirectory && it.name != DEFAULT_AGENT_SKILL_ID &&
            readSkill(it) != null }?.sortedBy { it.name }?.forEach { root ->
            fun walk(folder: File, relative: String, depth: Int) {
                check(results.size < MAX_BACKUP_FILES) { "Too many files in Agent Skills backup" }
                if (depth > MAX_RESOURCE_DEPTH) return
                folder.listFiles()?.sortedBy { it.name }?.forEach { entry ->
                    if (java.nio.file.Files.isSymbolicLink(entry.toPath()) ||
                        entry.name in setOf(".", "..") || '/' in entry.name || '\\' in entry.name) return@forEach
                    val path = if (relative.isEmpty()) entry.name else "$relative/${entry.name}"
                    if (entry.isDirectory) walk(entry, path, depth + 1)
                    else if (entry.isFile) {
                        check(results.size < MAX_BACKUP_FILES) { "Too many files in Agent Skills backup" }
                        results["${root.name}/$path"] = entry
                    }
                }
            }
            walk(root, "", 0)
        }
        results
    }

    /** Discover resource paths without eagerly loading resource contents into the model context. */
    fun listResources(context: Context, name: String): List<String> = synchronized(lock) {
        require(isValidAgentSkillName(name)) { "Invalid skill name" }
        if (name == DEFAULT_AGENT_SKILL_ID) {
            val root = "$DIRECTORY_NAME/$name"
            val files = mutableListOf<String>()
            fun walk(relative: String, depth: Int) {
                if (depth > MAX_RESOURCE_DEPTH || files.size >= MAX_RESOURCE_LIST) return
                val prefix = if (relative.isEmpty()) root else "$root/$relative"
                context.assets.list(prefix).orEmpty().sorted().forEach { child ->
                    if (files.size >= MAX_RESOURCE_LIST) return@forEach
                    val path = if (relative.isEmpty()) child else "$relative/$child"
                    if (path == SKILL_FILE_NAME) return@forEach
                    if (context.assets.list("$root/$path").orEmpty().isNotEmpty()) {
                        walk(path, depth + 1)
                    } else files += path
                }
            }
            walk("", 0)
            files
        } else {
            val root = skillDirectory(name)
            val files = mutableListOf<String>()
            fun walk(dir: File, relative: String, depth: Int) {
                if (depth > MAX_RESOURCE_DEPTH || files.size >= MAX_RESOURCE_LIST) return
                dir.listFiles()?.sortedBy { it.name }?.forEach { child ->
                    if (files.size >= MAX_RESOURCE_LIST) return@forEach
                    if (java.nio.file.Files.isSymbolicLink(child.toPath())) return@forEach
                    val path = if (relative.isEmpty()) child.name else "$relative/${child.name}"
                    if (path == SKILL_FILE_NAME) return@forEach
                    if (child.isDirectory) walk(child, path, depth + 1)
                    else if (child.isFile) files += path
                }
            }
            walk(root, "", 0)
            files
        }
    }

    /** Read only bounded UTF-8 text files inside a validated skill directory. Never execute scripts. */
    fun readResource(context: Context, name: String, relativePath: String): String = synchronized(lock) {
        require(isValidAgentSkillName(name)) { "Invalid skill name" }
        val parts = relativePath.split('/')
        require(parts.isNotEmpty() && parts.size <= MAX_RESOURCE_DEPTH + 1 &&
            parts.all { it.isNotBlank() && it != "." && it != ".." && '\\' !in it && ':' !in it } &&
            relativePath != SKILL_FILE_NAME && !relativePath.startsWith('/')) {
            "Invalid skill resource path"
        }
        val bytes = if (name == DEFAULT_AGENT_SKILL_ID) {
            val path = "$DIRECTORY_NAME/$name/$relativePath"
            // Bundled APK assets are trusted and small; avoid Java 9 readNBytes (Android API compatibility).
            context.assets.open(path).use { it.readBytes() }
        } else {
            val root = skillDirectory(name)
            val resource = File(root, relativePath)
            require(resource.canonicalFile.toPath().startsWith(root.canonicalFile.toPath()) &&
                resource.canonicalFile != root.canonicalFile && resource.isFile &&
                !parts.indices.any { i ->
                    java.nio.file.Files.isSymbolicLink(File(root, parts.take(i + 1).joinToString("/")).toPath())
                }) { "Skill resource is not a regular file inside the skill directory" }
            require(resource.length() <= MAX_RESOURCE_BYTES) { "Skill resource is too large" }
            resource.readBytes()
        }
        require(bytes.size <= MAX_RESOURCE_BYTES) { "Skill resource is too large" }
        require(0.toByte() !in bytes) { "Binary skill resources cannot be read as text" }
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(java.nio.ByteBuffer.wrap(bytes)).toString()
    }

    private fun skillDirectory(name: String): File {
        val folder = requireNotNull(safeChild(name)) { "Invalid skill name" }
        require(readSkill(folder) != null) { "Skill does not exist or SKILL.md is invalid" }
        return folder
    }

    private fun writeSkill(skill: AgentSkill) {
        val targetDirectory = safeChild(skill.name) ?: error("Invalid Agent Skill directory name.")
        targetDirectory.mkdirs()
        val target = File(targetDirectory, SKILL_FILE_NAME)
        val temporary = File(targetDirectory, ".$SKILL_FILE_NAME.${UUID.randomUUID()}.tmp")
        try {
            val existingFrontmatter = if (target.isFile && readSkill(targetDirectory) != null) {
                target.readText(Charsets.UTF_8)
            } else null
            val finalSkill = if (skill.extraFrontmatter == null && existingFrontmatter != null) {
                skill.copy(extraFrontmatter = parseSkillFile(skill.name, existingFrontmatter)?.extraFrontmatter)
            } else skill
            temporary.writeText(agentSkillDocument(finalSkill), Charsets.UTF_8)
            if (!temporary.renameTo(target)) {
                target.delete()
                check(temporary.renameTo(target)) { "Could not save ${skill.name}/$SKILL_FILE_NAME." }
            }
        } finally {
            temporary.delete()
        }
    }

    private fun validateSkill(skill: AgentSkill): AgentSkill {
        val name = skill.name.trim()
        val description = skill.description.trim()
        val content = skill.content.trim()
        require(isValidAgentSkillName(name)) {
            "Agent Skill name must use lowercase letters, numbers, and single hyphens."
        }
        require(description.isNotBlank() && description.length <= MAX_DESCRIPTION_LENGTH) {
            "Agent Skill description must contain 1-$MAX_DESCRIPTION_LENGTH characters."
        }
        require(content.isNotBlank()) { "Agent Skill instructions are required." }
        return skill.copy(id = name, name = name, description = description, content = content)
    }

    private fun safeChild(name: String): File? {
        if (!isValidAgentSkillName(name)) return null
        val target = File(directory, name)
        return target.takeIf { it.canonicalFile.parentFile == directory.canonicalFile }
    }

    companion object {
        const val DIRECTORY_NAME = "agent-skills"
        const val SKILL_FILE_NAME = "SKILL.md"
        private const val ORDER_FILE_NAME = ".order"
        private const val MAX_ORDER_BYTES = 64 * 1024L
        const val MAX_DESCRIPTION_LENGTH = 1024
        private const val MAX_RESOURCE_DEPTH = 5
        private const val MAX_RESOURCE_LIST = 100
        private const val MAX_BACKUP_FILES = 2000
        private const val MAX_RESOURCE_BYTES = 64 * 1024
    }
}
