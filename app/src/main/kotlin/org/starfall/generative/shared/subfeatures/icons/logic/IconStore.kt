package org.starfall.generative.shared.subfeatures.icons.logic

import android.content.Context
import org.starfall.generative.shared.state.preferences.applicationPreferences
import org.starfall.generative.shared.model.icons.IconRule
import org.starfall.generative.shared.model.icons.IconStoreState
import org.starfall.generative.shared.model.icons.StoredIcon
import android.graphics.Bitmap
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Try each model family first, then progressively restore hyphen suffixes, before the vendor. */
internal fun iconMatchNames(name: String, model: Boolean): List<String> {
    val clean = name.trim()
    return (if (model) listOf(clean.substringAfterLast('/')) else listOf(clean)).flatMap { part ->
            if (!model) {
                // Exact names win. Then try complete word groups, longest first,
                // so descriptive prefixes/suffixes work for every brand, including
                // multi-word brands, without matching fragments inside other words.
                val providerName = part.trim()
                val words = Regex("[\\p{L}\\p{N}]+").findAll(providerName).map { it.value }.toList()
                buildList {
                    add(providerName)
                    for (length in words.size downTo 1) {
                        for (start in 0..words.size - length) {
                            add(words.subList(start, start + length).joinToString(" "))
                        }
                    }
                }
            } else {
                val segments = part.trim().split('-')
                buildList {
                    val first = segments.firstOrNull().orEmpty()
                    val leadingLetters = first.takeWhile(Char::isLetter)
                    if (leadingLetters.isNotEmpty() && leadingLetters.length < first.length) {
                        add(leadingLetters)
                    }
                    segments.indices.forEach { index ->
                        add(segments.take(index + 1).joinToString("-"))
                    }
                }
            }
        }
        .filter { it.isNotEmpty() }.distinct()
}

/** Result of an icon lookup: [data] is Coil request data, or null when no image was found. */
class IconLookup(val data: Any?)

/** Icons belong to the app, independent of temporary photo-picker URI permissions. */
class IconStore(private val context: Context) {
    private val directory get() = File(context.filesDir, "entity-icons").apply { mkdirs() }

    private val preferences = context.applicationPreferences().stringPreferences("named-entity-icons")
    private val assets = context.applicationPreferences().stringPreferences("icon-assets")
    private val automatic = context.applicationPreferences().stringPreferences("automatic-entity-icons")
    private val variants = context.applicationPreferences().stringPreferences("icon-variants")
    /** Original file names of app-owned (non-shared) icons, for display next to the logo. */
    private val names = context.applicationPreferences().stringPreferences("icon-file-names")
    companion object {
        private val changes = MutableStateFlow(0L)
        val revision = changes.asStateFlow()
        private val lookupChanges = MutableStateFlow(0L)
        val lookupRevision = lookupChanges.asStateFlow()
        private val storedIconName = Regex("^((?:entity-)?icon-[a-f0-9-]+|lobe-[a-z0-9-]+)\\.png$")
        private val downloadLock = Mutex()
        private val mutationLock = Any()
        private val source = LobeIconSource()
        private val failedDownloads = java.util.concurrent.ConcurrentHashMap<String, Long>()
        private val missingDarkVariants: MutableSet<String> = ConcurrentHashMap.newKeySet()

        /** Parsed + compiled rules, valid for exactly the raw JSON they were built from. */
        private class RulesSnapshot(val raw: String, val rules: List<IconRule>, val compiled: List<Pair<Regex, String>>)
        @Volatile private var rulesSnapshot: RulesSnapshot? = null

        /** In-memory results of [lookupIcon]; survives screens leaving composition. */
        private data class LookupKey(
            val root: String, val image: String?, val matchName: String?, val fallbackMatchName: String?, val model: Boolean,
            val dark: Boolean, val skipExplicit: Boolean, val revision: Long
        )
        private class LookupEntry(val lookup: IconLookup, val expiresAt: Long)
        private val lookupCache = ConcurrentHashMap<LookupKey, LookupEntry>()
        private const val LOOKUP_MISS_TTL_MS = 60_000L
        private const val LOOKUP_CACHE_LIMIT = 1024
    }

    /** App-specific cache dir (/storage/emulated/0/Android/data/<package>/cache), or internal cache if unavailable. */
    private fun catalogDirectory(): File = File(context.externalCacheDir ?: context.cacheDir, "icon-catalog")

    private fun sharedSource(): LobeIconSource = source.also {
        if (it.catalogCacheDir == null) it.catalogCacheDir = catalogDirectory()
    }

    private fun currentRules(): RulesSnapshot? {
        val saved = preferences.getString("rules", null) ?: return null
        rulesSnapshot?.takeIf { it.raw == saved }?.let { return it }
        val rules = runCatching { Json.decodeFromString<List<IconRule>>(saved) }.getOrDefault(emptyList())
        val compiled = rules.mapNotNull { rule ->
            runCatching { Regex(rule.pattern, RegexOption.IGNORE_CASE) to rule.image }.getOrNull()
        }
        return RulesSnapshot(saved, rules, compiled).also { rulesSnapshot = it }
    }

    private fun lookupKey(image: String?, matchName: String?, fallbackMatchName: String?, model: Boolean, dark: Boolean, skipExplicit: Boolean) =
        LookupKey(context.filesDir.path, image, matchName, fallbackMatchName, model, dark, skipExplicit, lookupChanges.value)

    /** Cheap, main-thread-safe cache read. Null means "not looked up yet" (or a stale miss). */
    fun peekIcon(image: String?, matchName: String?, model: Boolean, dark: Boolean, skipExplicit: Boolean = false, fallbackMatchName: String? = null): IconLookup? {
        val entry = lookupCache[lookupKey(image, matchName, fallbackMatchName, model, dark, skipExplicit)] ?: return null
        return entry.lookup.takeIf { it.data != null || entry.expiresAt > System.currentTimeMillis() }
    }

    /** Resolves the image to show and remembers the answer in RAM. Called on IO. */
    /**
     * [fallbackMatchName] (e.g. the model id when [matchName] is a display name) is only tried when
     * the primary name produced no logo, so users no longer have to clear the display name.
     */
    internal suspend fun lookupIcon(image: String?, matchName: String?, model: Boolean, dark: Boolean, skipExplicit: Boolean = false, fallbackMatchName: String? = null): Any? {
        val key = lookupKey(image, matchName, fallbackMatchName, model, dark, skipExplicit)
        lookupCache[key]?.let { if (it.lookup.data != null || it.expiresAt > System.currentTimeMillis()) return it.lookup.data }
        val data = (if (skipExplicit) null else imageData(image, dark))
            ?: imageData(matchName?.let { resolve(it, model) }, dark)
            ?: fallbackMatchName?.takeIf { it.isNotBlank() && !it.equals(matchName, ignoreCase = true) }
                ?.let { imageData(resolve(it, model), dark) }
        if (lookupCache.size >= LOOKUP_CACHE_LIMIT) lookupCache.clear()
        lookupCache[key] = LookupEntry(IconLookup(data), System.currentTimeMillis() + LOOKUP_MISS_TTL_MS)
        return data
    }

    fun rules(): List<IconRule> {
        currentRules()?.let { return it.rules }
        // Upgrade earlier name caches into one shared collection.
        return preferences.all.mapNotNull { (key, value) ->
            if (':' !in key) null
            else IconRule(pattern = Regex.escape(key.substringAfter(':')), image = value)
        }.distinctBy { it.pattern }
    }

    fun saveRules(rules: List<IconRule>) = synchronized(mutationLock) {
        require(rules.all { it.pattern.isNotBlank() && runCatching { Regex(it.pattern) }.isSuccess })
        preferences.replaceAll(mapOf("rules" to Json.encodeToString(rules)))
        lookupCache.clear()
        changes.value += 1
        lookupChanges.value += 1
    }

    fun entries(): List<StoredIcon> {
        val rules = rules()
        val variantImages = variants.all.values.toSet()
        return directory.listFiles().orEmpty().filter { storedIconName.matches(it.name) &&
            !it.name.startsWith("entity-icon-") && !it.name.startsWith("lobe-dark-") && it.name !in variantImages }.map { file ->
            StoredIcon(file.name, assets.getString(file.name, file.name) ?: file.name,
                rules.filter { it.image == file.name }.map { it.pattern },
                lightImage = themedImage(file.name, false),
                darkImage = variants.getString("dark:${file.name}", null)?.takeIf { File(directory, it).isFile })
        }.sortedBy { it.filename.lowercase(java.util.Locale.ROOT) }
    }

    /** Captures icon metadata and the names of all app-owned icon files for backup. */
    fun exportState(): IconStoreState = synchronized(mutationLock) {
        IconStoreState(
            rules = rules(),
            assetLabels = assets.all,
            automatic = automatic.all,
            variants = variants.all,
            fileNames = names.all,
            files = directory.listFiles().orEmpty()
                .filter { storedIconName.matches(it.name) && it.isFile }
                .map { it.name }
                .sorted()
        )
    }

    /** Returns an app-owned icon file for streaming into a backup archive. */
    fun exportFile(name: String): File? = synchronized(mutationLock) {
        require(storedIconName.matches(name)) { "Invalid icon file name." }
        File(directory, name).takeIf { it.isFile }
    }

    /** Restores icon metadata and files, removing stale icon files from the current installation. */
    fun restore(state: IconStoreState, files: Map<String, File>) = synchronized(mutationLock) {
        require(state.files.distinct().size == state.files.size) { "The icon backup contains duplicate files." }
        state.files.forEach { name -> require(storedIconName.matches(name)) { "Invalid icon file name." } }
        files.keys.forEach { name -> require(name in state.files) { "Unexpected icon file in backup." } }
        state.files.forEach { name ->
            val source = files[name] ?: error("The icon file $name is missing from the backup.")
            require(source.isFile) { "The icon file $name is unreadable." }
        }

        directory.listFiles().orEmpty().filter { storedIconName.matches(it.name) }.forEach { it.delete() }
        state.files.forEach { name ->
            val destination = File(directory, name)
            files.getValue(name).inputStream().use { input -> destination.outputStream().use { input.copyTo(it) } }
        }
        preferences.replaceAll(mapOf("rules" to Json.encodeToString(state.rules)))
        assets.replaceAll(state.assetLabels)
        automatic.replaceAll(state.automatic)
        variants.replaceAll(state.variants)
        names.replaceAll(state.fileNames)
        rulesSnapshot = null
        lookupCache.clear()
        changes.value += 1
        lookupChanges.value += 1
    }

    fun editMatches(image: String, patterns: List<String>) = synchronized(mutationLock) {
        require(storedIconName.matches(image))
        val existing = rules()
        val old = existing.filter { it.image == image }
        val replacement = patterns.distinct().map { pattern ->
            old.firstOrNull { it.pattern == pattern } ?: IconRule(pattern = pattern, image = image)
        }
        // Retain the relative precedence of existing rules, appending newly added cases.
        saveRules(existing.filter { it.image != image || it.pattern in patterns } +
            replacement.filter { rule -> old.none { it.id == rule.id } })
        assets.putString(image, assets.getString(image, image) ?: image)
    }

    fun delete(image: String) = synchronized(mutationLock) {
        require(storedIconName.matches(image))
        val file = File(directory, image)
        check(!file.exists() || file.delete())
        saveRules(rules().filterNot { it.image == image })
        automatic.update { entries.removeAll { it.value == image } }
        assets.remove(image)
        variants.update {
            remove("light:$image")
            remove("dark:$image")
        }
        changes.value += 1
    }

    /** Called on IO only, after explicit icons and user regex rules have been considered. */
    internal suspend fun resolve(name: String, model: Boolean = false, iconSource: LobeIconSource = sharedSource()): String? {
        find(name, model)?.let { image ->
            if (image.startsWith("lobe-") && !image.startsWith("lobe-dark-") && needsDarkVariant(image)) {
                downloadLock.withLock { ensureDarkVariant(image, iconSource) }
            }
            return image
        }
        val remoteCandidates = buildList {
            if (model) LobeModelIconResolver.resolve(name)?.let(::add)
            addAll(iconMatchNames(name, model))
        }.distinctBy { it.lowercase(java.util.Locale.ROOT) }
        // Fast path: the first candidate is what the slow loop would try first, so answering from its
        // already-downloaded file gives the same result without queueing behind other icons' downloads.
        remoteCandidates.firstOrNull()?.let { first ->
            automatic.getString(first.lowercase(java.util.Locale.ROOT), null)
                ?.takeIf { File(directory, it).isFile }?.let { cached ->
                    if (needsDarkVariant(cached)) downloadLock.withLock { ensureDarkVariant(cached, iconSource) }
                    return cached
                }
        }
        return downloadLock.withLock {
            find(name, model)?.let { return@withLock it }
            for (candidate in remoteCandidates) {
                var attemptedId: String? = null
                val key = candidate.lowercase(java.util.Locale.ROOT)
                automatic.getString(key, null)?.let { cached ->
                    if (File(directory, cached).isFile) {
                        ensureDarkVariant(cached, iconSource)
                        return@withLock cached
                    }
                }
                try {
                    val filename = iconSource.find(candidate, model = model) ?: continue
                    val id = "lobe-$filename"
                    attemptedId = id
                    if ((failedDownloads[id] ?: 0L) > System.currentTimeMillis()) continue
                    val file = File(directory, id)
                    if (!file.exists()) {
                        val bytes = iconSource.image(filename)
                        require(AppImages.decode(context, bytes, 256) != null) { "Invalid icon image" }
                        synchronized(mutationLock) {
                            val staging = File.createTempFile("download-", ".tmp", directory)
                            try {
                                staging.writeBytes(bytes)
                                check(staging.renameTo(file))
                            } finally { staging.delete() }
                        }
                    }
                    ensureDarkVariant(id, iconSource)
                    synchronized(mutationLock) {
                        if (file.exists()) {
                            assets.putString(id, filename)
                            automatic.putString(key, id)
                            changes.value += 1
                        }
                    }
                    return@withLock find(name, model) ?: id
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    // Offline/missing images leave the existing UI fallback intact; retry later.
                    attemptedId?.let { failedDownloads[it] = System.currentTimeMillis() + 60_000 }
                }
            }
            null
        }
    }

    private fun needsDarkVariant(image: String) =
        image.startsWith("lobe-") && !image.startsWith("lobe-dark-") &&
            !variants.contains("dark:$image") && image !in missingDarkVariants

    private suspend fun ensureDarkVariant(image: String, iconSource: LobeIconSource) {
        if (!image.startsWith("lobe-") || image.startsWith("lobe-dark-") ||
            variants.contains("dark:$image") || image in missingDarkVariants) return
        val failureKey = "dark:$image"
        if ((failedDownloads[failureKey] ?: 0L) > System.currentTimeMillis()) return
        try {
            val brand = image.removePrefix("lobe-").removeSuffix(".png").removeSuffix("-color")
            val filename = iconSource.find(brand, dark = true)
            if (filename == null) { missingDarkVariants += image; return }
            val id = "lobe-dark-$filename"
            val file = File(directory, id)
            if (!file.isFile) {
                val bytes = iconSource.image(filename, dark = true)
                require(AppImages.decode(context, bytes, 256) != null) { "Invalid icon image" }
                val staging = File.createTempFile("download-", ".tmp", directory)
                try { staging.writeBytes(bytes); check(staging.renameTo(file)) } finally { staging.delete() }
            }
            synchronized(mutationLock) {
                variants.putString("dark:$image", id)
                changes.value += 1
            }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { failedDownloads[failureKey] = System.currentTimeMillis() + 60_000 }
    }

    fun themedImage(image: String, dark: Boolean): String {
        val light = variants.getString("light:$image", null)?.takeIf { File(directory, it).isFile } ?: image
        return if (dark) variants.getString("dark:$image", null)?.takeIf { File(directory, it).isFile } ?: light else light
    }

    fun setVariant(image: String, replacement: String?, dark: Boolean) = synchronized(mutationLock) {
        require(storedIconName.matches(image))
        require(replacement == null || (storedIconName.matches(replacement) && File(directory, replacement).isFile))
        val key = "${if (dark) "dark" else "light"}:$image"
        if (replacement == null) variants.remove(key) else variants.putString(key, replacement)
        lookupCache.clear()
        changes.value += 1
        lookupChanges.value += 1
    }

    fun find(name: String, model: Boolean = false): String? {
        val compiled = currentRules()?.compiled ?: rules().mapNotNull { rule ->
            runCatching { Regex(rule.pattern, RegexOption.IGNORE_CASE) to rule.image }.getOrNull()
        }
        for (candidate in iconMatchNames(name, model)) {
            compiled.firstOrNull { it.first.matches(candidate) }?.let { return it.second }
        }
        return null
    }

    fun cache(name: String, image: String, model: Boolean = false): Unit = synchronized(mutationLock) {
        if (image.startsWith("entity-icon-")) return
        val candidate = iconMatchNames(name, model).firstOrNull() ?: return
        val pattern = Regex.escape(candidate)
        val existing = rules()
        val index = existing.indexOfFirst { it.pattern.equals(pattern, ignoreCase = true) }
        if (index >= 0 && existing[index].image == image) return
        val rule = if (index >= 0) existing[index].copy(image = image) else IconRule(pattern = pattern, image = image)
        saveRules(listOf(rule) + existing.filterNot { it.id == rule.id })
    }

    /**
     * Fetch a public brand favicon only when no usable named icon is already stored.
     * Store it as a shared icon and match it by brand; explicit entity icons still win.
     * Failed requests are throttled to avoid repeated network work on each screen visit.
     */
    suspend fun ensureNamedFavicon(name: String, homepage: String) = withContext(Dispatchers.IO) {
        fun alreadyAvailable(): Boolean =
            find(name)?.let { imageData(it) } != null ||
                iconMatchNames(name, false).any { candidate ->
                    automatic.getString(candidate.lowercase(java.util.Locale.ROOT), null)
                        ?.let { imageData(it) } != null
                }
        if (alreadyAvailable()) return@withContext
        val key = "favicon:${name.lowercase(java.util.Locale.ROOT)}"
        if ((failedDownloads[key] ?: 0L) > System.currentTimeMillis()) return@withContext
        try {
            val result = FaviconService(context).get("", homepage)
            try {
                synchronized(mutationLock) {
                    if (!alreadyAvailable()) {
                        val id = importBitmap(result.bitmap, name, shared = true)
                        cache(name, id)
                    }
                }
            } finally {
                result.bitmap.recycle()
            }
            failedDownloads.remove(key)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Offline devices keep the existing placeholder and can retry later.
            failedDownloads[key] = System.currentTimeMillis() + 5 * 60_000L
        }
    }

    /** File name the person picked or fetched for [image], if known. */
    fun fileName(image: String?): String? =
        image?.let { names.getString(it, null) ?: assets.getString(it, null) }?.takeIf { it.isNotBlank() }

    fun importImage(uri: Uri, shared: Boolean = true, displayName: String? = null): String {
        val staging = File.createTempFile("import-", ".tmp", directory)
        try {
            val input = context.contentResolver.openInputStream(uri) ?: error("Cannot open image")
            input.use { source ->
                staging.outputStream().use { output ->
                    val buffer = ByteArray(8192)
                    var total = 0L
                    while (true) {
                        val read = source.read(buffer)
                        if (read < 0) break
                        total += read
                        require(total <= 20L * 1024 * 1024) { "Image is too large" }
                        output.write(buffer, 0, read)
                    }
                }
            }
            val bitmap = runBlocking { AppImages.decode(context, staging, 256) }
                ?: error("Cannot decode image")
            val id = "${if (shared) "" else "entity-"}icon-${UUID.randomUUID()}.png"
            val target = File(directory, id)
            try {
                target.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            } catch (error: Throwable) {
                target.delete()
                throw error
            }
            val filename = runCatching {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                }
            }.getOrNull() ?: uri.lastPathSegment ?: id
            val label = displayName?.takeIf { it.isNotBlank() } ?: filename
            if (shared) assets.putString(id, label)
            else names.putString(id, label)
            changes.value += 1
            return id
        } finally {
            staging.delete()
        }
    }

    /** Save a confirmed favicon preview as an app-owned icon. Called on IO. */
    internal fun importBitmap(bitmap: Bitmap, fileName: String? = null, shared: Boolean = false): String {
        val staging = File.createTempFile("favicon-", ".png", context.cacheDir)
        try {
            staging.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            return importImage(Uri.fromFile(staging), shared = shared, displayName = fileName)
        } finally { staging.delete() }
    }

    /** Resolve explicit icons to Coil request data without decoding in the UI. */
    fun imageData(image: String?, dark: Boolean = false): Any? {
        if (image == null) return null
        if (Uri.parse(image).scheme?.lowercase() in listOf("http", "https")) return image
        val id = themedImage(image, dark)
        if (!storedIconName.matches(id)) return null
        return File(directory, id).takeIf { it.isFile }
    }

    /** Compatibility for IO-only import/export clients; Coil owns decoding and caching. */
    internal suspend fun loadIcon(image: String?, dark: Boolean = false): Bitmap? {
        val data = imageData(image, dark) ?: return null
        if ((failedDownloads["remote:$image"] ?: 0L) > System.currentTimeMillis()) return null
        val bitmap = AppImages.decode(context, data, 256)
        if (bitmap == null) failedDownloads["remote:$image"] = System.currentTimeMillis() + 60_000
        return bitmap
    }

    /** Called on IO, never from a composable. */
    fun load(id: String?): Bitmap? = imageData(id)?.let { data ->
        runBlocking { AppImages.decode(context, data, 256) }
    }

    /**
     * Removes imported files that are no longer referenced by either persisted entities or
     * shared matching rules. This is intentionally reference-driven so deleting a cache rule
     * never removes an icon that is still explicitly assigned to a Provider, Model or MCP server.
     */
    fun prune(entityImages: Collection<String?>) = synchronized(mutationLock) {
        lookupCache.clear()
        val referenced = buildSet {
            rules().mapTo(this) { it.image }
            entityImages.filterNotNull().filterTo(this) { storedIconName.matches(it) }
            assets.all.keys.filterTo(this) { storedIconName.matches(it) }
            variants.all.values.filterTo(this) { storedIconName.matches(it) }
        }
        directory.listFiles().orEmpty().forEach { file ->
            val staleIcon = storedIconName.matches(file.name) && file.name !in referenced
            val staleImport = file.name.startsWith("import-") && file.extension == "tmp"
            if (staleIcon || staleImport) file.delete()
        }
    }
}
