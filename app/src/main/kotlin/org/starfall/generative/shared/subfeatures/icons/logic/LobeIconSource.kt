package org.starfall.generative.shared.subfeatures.icons.logic

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Only the filename index is fetched in advance; image bytes are requested on demand.
 * When [catalogCacheDir] is set the index is kept on disk there, so it is downloaded once
 * instead of once per process start.
 */
internal class LobeIconSource(
    private val indexUrl: String = "https://data.jsdelivr.com/v1/package/npm/@lobehub/icons-static-png@1.97.1/flat",
    private val imageBase: String = "https://cdn.jsdelivr.net/npm/@lobehub/icons-static-png@1.97.1/light/",
    private val darkImageBase: String = imageBase.removeSuffix("light/") + "dark/",
    @Volatile var catalogCacheDir: File? = null,
) {
    private val client = OkHttpClient.Builder().callTimeout(15, TimeUnit.SECONDS).build()
    @Volatile private var catalog: Set<String>? = null
    @Volatile private var darkCatalog: Set<String> = emptySet()
    private var retryAfter = 0L

    fun find(candidate: String, dark: Boolean = false, model: Boolean = false): String? {
        if (catalog == null) synchronized(this) {
            if (catalog == null && !loadCatalogFromDisk()) {
                if (System.currentTimeMillis() < retryAfter) return null
                try {
                    val bytes = download(indexUrl, 2 * 1024 * 1024)
                    val (light, dark) = parseCatalog(bytes)
                    catalog = light
                    darkCatalog = dark
                    saveCatalogToDisk(bytes)
                } catch (error: Exception) {
                    retryAfter = System.currentTimeMillis() + 60_000
                    throw error
                }
            }
        }
        return match(candidate, if (dark) darkCatalog else catalog.orEmpty(), model)
    }

    private fun parseCatalog(bytes: ByteArray): Pair<Set<String>, Set<String>> {
        val json = Json.parseToJsonElement(bytes.decodeToString())
        val filenames = json.jsonObject.getValue("files").jsonArray.mapNotNull { item ->
            item.jsonObject["name"]?.jsonPrimitive?.content
                ?.takeIf { Regex("^/(light|dark)/[a-z0-9-]+\\.png$").matches(it) }
        }
        return filenames.filter { it.startsWith("/light/") }.map { it.removePrefix("/light/") }.toSet() to
            filenames.filter { it.startsWith("/dark/") }.map { it.removePrefix("/dark/") }.toSet()
    }

    /** One file per index URL, so bumping the pinned icon version never reuses an older index. */
    private fun catalogFile(): File? = catalogCacheDir?.let {
        File(it, "lobe-icon-catalog-${Integer.toHexString(indexUrl.hashCode())}.json")
    }

    private fun loadCatalogFromDisk(): Boolean {
        val file = catalogFile()?.takeIf { it.isFile } ?: return false
        return try {
            if (System.currentTimeMillis() - file.lastModified() > CATALOG_MAX_AGE_MS) return false
            val (light, dark) = parseCatalog(file.readBytes())
            if (light.isEmpty()) { file.delete(); return false }
            catalog = light
            darkCatalog = dark
            true
        } catch (_: Exception) {
            file.delete()
            false
        }
    }

    private fun saveCatalogToDisk(bytes: ByteArray) {
        val file = catalogFile() ?: return
        try {
            file.parentFile?.mkdirs()
            val staging = File.createTempFile("catalog-", ".tmp", file.parentFile)
            try {
                staging.writeBytes(bytes)
                check(staging.renameTo(file))
            } finally { staging.delete() }
        } catch (_: Exception) {
            // The cache is only an optimisation; the in-memory catalog is already loaded.
        }
    }

    fun image(filename: String, dark: Boolean = false): ByteArray =
        download((if (dark) darkImageBase else imageBase) + filename, 2 * 1024 * 1024)

    private fun download(url: String, limit: Int): ByteArray =
        client.newCall(Request.Builder().url(url).build()).execute().use { response ->
            check(response.isSuccessful) { "Icon HTTP ${response.code}" }
            val body = checkNotNull(response.body)
            body.byteStream().use { stream ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = stream.read(buffer)
                    if (count < 0) break
                    require(output.size() + count <= limit) { "Icon response too large" }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
        }

    companion object {
        private const val CATALOG_MAX_AGE_MS = 30L * 24 * 60 * 60 * 1000

        private fun normalized(name: String) = name.lowercase(java.util.Locale.ROOT).replace(Regex("[^a-z0-9]"), "")

        internal fun match(candidate: String, files: Set<String>, model: Boolean = false): String? {
            val aliases = mapOf("gpt" to "openai", "chatgpt" to "openai", "o1" to "openai",
                "o3" to "openai", "o4" to "openai", "glm" to "chatglm")
            // A provider name can contain a brand plus descriptive words. Look up
            // the actual brand filename rather than requiring a filename for the full name.
            // Model candidates are already ordered from the first hyphen segment outward.
            // Do not split them as provider names here: doing so turns "muse-spark" into
            // "muse" and "spark", allowing the shorter, unrelated spark logo to win.
            val names = if (model) listOf(normalized(candidate)) else iconMatchNames(candidate, model = false)
                .flatMap { part ->
                val key = normalized(part)
                listOfNotNull(key.takeIf { it.isNotEmpty() }, aliases[key])
            }.distinct()
            val sortedFiles = files.sorted()
            fun fileKey(file: String): String {
                var stem = file.removeSuffix(".png")
                listOf("-color", "-dark", "-light").firstOrNull { stem.endsWith(it) }?.let {
                    stem = stem.removeSuffix(it)
                }
                return normalized(stem)
            }
            for (name in names) {
                sortedFiles.firstOrNull { it.endsWith("-color.png") && fileKey(it) == name }
                    ?.let { return it }
                sortedFiles.firstOrNull { fileKey(it) == name }
                    ?.let { return it }
            }
            return null
        }
    }
}
