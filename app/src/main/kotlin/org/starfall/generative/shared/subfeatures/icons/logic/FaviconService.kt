package org.starfall.generative.shared.subfeatures.icons.logic

import android.graphics.Bitmap
import android.content.Context
import kotlinx.coroutines.runBlocking
import android.text.Html
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import org.starfall.generative.shared.helpers.networking.ToolHttp

internal fun faviconHomepage(customUrl: String, baseUrl: String): HttpUrl {
    val value = customUrl.trim().ifBlank { baseUrl.trim() }
    require(value.isNotBlank()) { "Enter a homepage or image URL or set the provider Base URL." }
    val url = (if ("://" in value) value else "https://$value").toHttpUrlOrNull()
        ?: error("Enter a valid HTTP or HTTPS homepage or image URL.")
    require(url.username.isEmpty() && url.password.isEmpty()) { "The homepage URL must not contain embedded credentials." }
    return if (customUrl.isBlank()) url.newBuilder().encodedPath("/").query(null).fragment(null).build()
        else url.newBuilder().fragment(null).build()
}

internal fun faviconFallbackUrls(homepage: HttpUrl): List<HttpUrl> {
    val origin = homepage.newBuilder().encodedPath("/").query(null).fragment(null).build()
    val hosts = listOfNotNull(origin.host, origin.topPrivateDomain()).distinct()
    return listOf("favicon.ico", "favicon.png").flatMap { file ->
        hosts.map { host -> origin.newBuilder().host(host).encodedPath("/$file").build() }
    }
}

/** Read icon links only inside head; ignore comments/scripts and resolve relative URLs. */
internal fun faviconHeadUrls(html: String, page: HttpUrl): List<HttpUrl> {
    val head = Regex("<head\\b[^>]*>(.*?)</head\\s*>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        .find(html)?.groupValues?.get(1) ?: return emptyList()
    val clean = head.replace(Regex("<!--.*?-->|<(script|style)\\b[^>]*>.*?</\\1\\s*>",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), "")
    val tags = Regex("<(link|base)\\b(?:[^>\"']|\"[^\"]*\"|'[^']*')*>", RegexOption.IGNORE_CASE).findAll(clean)
    val attr = Regex("([a-zA-Z_:][a-zA-Z0-9_:.-]*)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s>]+))")
    var base = page
    var hasBase = false
    val icons = mutableListOf<Pair<String, String>>()
    tags.forEach { tag ->
        val attrs = attr.findAll(tag.value).associate { match ->
            match.groupValues[1].lowercase() to Html.fromHtml(
                match.groupValues.drop(2).firstOrNull { it.isNotEmpty() }.orEmpty(), Html.FROM_HTML_MODE_LEGACY).toString()
        }
        val href = attrs["href"].orEmpty()
        if (tag.groupValues[1].equals("base", true) && !hasBase && href.isNotBlank()) {
            page.resolve(href)?.let { base = it; hasBase = true }
        } else if (tag.groupValues[1].equals("link", true)) {
            val rel = attrs["rel"].orEmpty().lowercase().split(Regex("\\s+"))
            if ("icon" in rel || rel.any { it == "apple-touch-icon" || it == "apple-touch-icon-precomposed" })
                icons += attrs["rel"].orEmpty() to href
        }
    }
    return icons.sortedBy { if (it.first.lowercase().contains("apple-touch-icon")) 1 else 0 }
        .mapNotNull { (_, href) -> href.takeIf { it.isNotBlank() }?.let(base::resolve) }
        .filter { it.username.isEmpty() && it.password.isEmpty() }.distinct().take(16)
}

internal data class FaviconResult(val bitmap: Bitmap, val url: HttpUrl)

/** Fetches public web pages without provider auth, with bounded reads and cancellation. */
internal class FaviconService(private val context: Context, private val http: ToolHttp = ToolHttp(client = OkHttpClient.Builder()
    .connectTimeout(10, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS)
    .callTimeout(15, TimeUnit.SECONDS).followRedirects(false).build())) {
    private data class Resource(val bytes: ByteArray, val url: HttpUrl)

    private suspend fun download(url: HttpUrl, html: Boolean = false): Resource {
        var target = url
        repeat(6) {
            val resource = http.withResponse(http.request(target.toString()).header("Accept",
                if (html) "text/html,application/xhtml+xml" else "image/*,*/*;q=0.5").get().build()) { response ->
                if (response.isRedirect) {
                    target = response.header("Location")?.let(target::resolve) ?: error("Redirect has no valid URL.")
                    require(target.username.isEmpty() && target.password.isEmpty()) { "Redirect contains embedded credentials." }
                    null
                } else {
                    check(response.isSuccessful) { "HTTP ${response.code}" }
                    val body = response.body
                    val limit = if (html) 1024 * 1024 else 2 * 1024 * 1024
                    require(body.contentLength() <= limit) { "Response exceeds ${limit / 1024} KB." }
                    val bytes = body.byteStream().use { input ->
                        val out = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            require(out.size() + count <= limit) { "Response exceeds ${limit / 1024} KB." }
                            out.write(buffer, 0, count)
                        }
                        out.toByteArray()
                    }
                    Resource(bytes, response.request.url)
                }
            }
            if (resource != null) return resource
        }
        error("Too many redirects.")
    }

    suspend fun get(customUrl: String, baseUrl: String): FaviconResult = withContext(Dispatchers.IO) {
        val homepage = faviconHomepage(customUrl, baseUrl)
        val failures = mutableListOf<String>()
        // A custom URL may point straight at an image; try that before treating it as a homepage.
        if (customUrl.isNotBlank()) {
            try {
                val resource = download(homepage)
                decodeFavicon(context, resource.bytes)?.let { return@withContext FaviconResult(it, resource.url) }
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) { /* not a direct image; fall back to homepage discovery */ }
        }
        val links = try {
            val page = download(homepage, html = true)
            faviconHeadUrls(page.bytes.toString(Charsets.UTF_8), page.url)
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) {
            failures += "Homepage: ${error.message ?: "could not load the page"}"
            emptyList()
        }
        for (url in (links + faviconFallbackUrls(homepage)).distinct()) {
            try {
                val resource = download(url)
                val bitmap = decodeFavicon(context, resource.bytes) ?: error("Not a supported SVG, PNG, JPEG, WebP, GIF, or ICO image.")
                return@withContext FaviconResult(bitmap, resource.url)
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { failures += "${url.host}${url.encodedPath}: ${error.message ?: "could not load the icon"}" }
        }
        error("No favicon could be retrieved. Tried icon links in the homepage head, then favicon.ico and favicon.png on the host and root domain.\n\n" + failures.joinToString("\n"))
    }

    /**
     * Like [get], but returns every distinct image found (a direct image link, the homepage's icon
     * links, then favicon.ico/png fallbacks) so the person can choose between them.
     */
    suspend fun getAll(customUrl: String, baseUrl: String, limit: Int = 12): List<FaviconResult> = withContext(Dispatchers.IO) {
        val homepage = faviconHomepage(customUrl, baseUrl)
        val failures = java.util.Collections.synchronizedList(mutableListOf<String>())
        if (customUrl.isNotBlank()) {
            try {
                val resource = download(homepage)
                decodeFavicon(context, resource.bytes)?.let { return@withContext listOf(FaviconResult(it, resource.url)) }
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) { /* not a direct image; fall back to homepage discovery */ }
        }
        val links = try {
            val page = download(homepage, html = true)
            faviconHeadUrls(page.bytes.toString(Charsets.UTF_8), page.url)
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) {
            failures += "Homepage: ${error.message ?: "could not load the page"}"
            emptyList()
        }
        val gate = kotlinx.coroutines.sync.Semaphore(4)
        val found = coroutineScope {
            (links + faviconFallbackUrls(homepage)).distinct().map { url ->
                async {
                    gate.withPermit {
                        try {
                            val resource = download(url)
                            val bitmap = decodeFavicon(context, resource.bytes)
                                ?: error("Not a supported SVG, PNG, JPEG, WebP, GIF, or ICO image.")
                            FaviconResult(bitmap, resource.url) to resource.bytes.contentHashCode()
                        } catch (error: CancellationException) { throw error }
                        catch (error: Exception) {
                            failures += "${url.host}${url.encodedPath}: ${error.message ?: "could not load the icon"}"
                            null
                        }
                    }
                }
            }.awaitAll().filterNotNull()
        }
        // Different URLs often serve the same bytes (e.g. root-domain and www favicons).
        val unique = found.distinctBy { it.second }.map { it.first }.distinctBy { it.url.toString() }.take(limit)
        if (unique.isEmpty()) error("No favicon could be retrieved. Tried icon links in the homepage head, then favicon.ico and favicon.png on the host and root domain.\n\n" + failures.joinToString("\n"))
        unique
    }
}

/** ICO's DIB format needs a small compatibility decoder; standard raster decoding belongs to Coil. */
internal fun decodeFavicon(context: Context, bytes: ByteArray): Bitmap? {
    if (bytes.size >= 6 && bytes[0] == 0.toByte() && bytes[1] == 0.toByte() && bytes[2] == 1.toByte() && bytes[3] == 0.toByte())
        return decodeIco(bytes) { png -> runBlocking { AppImages.decode(context, png, 256) } }
    return runBlocking { AppImages.decode(context, bytes, 256) }
}
