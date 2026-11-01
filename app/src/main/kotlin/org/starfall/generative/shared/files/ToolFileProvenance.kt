package org.starfall.generative.shared.files

import android.util.AtomicFile
import java.io.File
import java.util.Base64

/**
 * Durable ownership metadata outside MediaStorage. Deleting a chat must not erase the fact that
 * a file was generated, or lose track of copies made from Android content:// attachments.
 * This index does not own the original user document and never deletes its URI.
 */
internal class ToolFileProvenance(path: File) {
    private val file = AtomicFile(path)
    private val generated = mutableSetOf<String>()
    private val imported = mutableMapOf<String, MutableSet<String>>()
    private val validName = Regex("[a-zA-Z0-9._-]+")

    init {
        if (file.baseFile.exists()) {
            runCatching {
                file.openRead().bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        val fields = line.split('\t')
                        when (fields.firstOrNull()) {
                            "G" -> fields.getOrNull(1)?.takeIf(::valid)?.let(generated::add)
                            "I" -> if (fields.size == 3 && valid(fields[2])) {
                                val source = String(Base64.getUrlDecoder().decode(fields[1]), Charsets.UTF_8)
                                imported.getOrPut(source) { mutableSetOf() }.add(fields[2])
                            }
                        }
                    }
                }
            }
        }
    }

    @Synchronized fun markGenerated(names: Collection<String>) {
        if (generated.addAll(names.filter(::valid))) write()
    }

    @Synchronized fun isGenerated(name: String): Boolean = name in generated

    @Synchronized fun recordImport(source: String, name: String) {
        if (!valid(name) || source.isBlank()) return
        if (imported.getOrPut(source) { mutableSetOf() }.add(name)) write()
    }

    @Synchronized fun importedCopies(source: String): Set<String> = imported[source]?.toSet().orEmpty()

    @Synchronized fun generatedNames(): Set<String> = generated.toSet()

    @Synchronized fun importedNames(): Map<String, List<String>> =
        imported.mapValues { (_, names) -> names.sorted() }

    @Synchronized fun replace(generatedNames: Collection<String>, imports: Map<String, List<String>>) {
        generated.clear()
        generated.addAll(generatedNames.filter(::valid))
        imported.clear()
        imports.forEach { (source, names) ->
            if (source.isNotBlank()) names.filter(::valid).takeIf { it.isNotEmpty() }?.let {
                imported[source] = it.toMutableSet()
            }
        }
        write()
    }

    @Synchronized fun forget(names: Collection<String>) {
        if (names.isEmpty()) return
        val removed = names.toSet()
        val changed = generated.removeAll(removed) || imported.values.any { it.any(removed::contains) }
        imported.values.forEach { it.removeAll(removed) }
        imported.entries.removeAll { it.value.isEmpty() }
        if (changed) write()
    }

    private fun valid(name: String): Boolean = validName.matches(name) &&
        !name.endsWith(".part") && !name.endsWith(".spill")

    private fun write() {
        file.baseFile.parentFile?.mkdirs()
        val output = file.startWrite()
        try {
            output.bufferedWriter().apply {
                generated.sorted().forEach { append("G\t").append(it).append('\n') }
                imported.toSortedMap().forEach { (source, names) ->
                    val key = Base64.getUrlEncoder().withoutPadding()
                        .encodeToString(source.toByteArray(Charsets.UTF_8))
                    names.sorted().forEach { append("I\t").append(key).append('\t').append(it).append('\n') }
                }
                flush()
            }
            file.finishWrite(output)
        } catch (error: Exception) {
            file.failWrite(output)
            throw error
        }
    }
}
