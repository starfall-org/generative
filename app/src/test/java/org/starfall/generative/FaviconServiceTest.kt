package org.starfall.generative

import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.*
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.starfall.generative.features.mcp.shared.ui.*
import org.starfall.generative.features.machines.shared.ui.*
import org.starfall.generative.integrations.remote.*
import org.starfall.generative.features.home.chat.*
import org.starfall.generative.integrations.modelapis.*
import org.starfall.generative.shared.subfeatures.icons.logic.*
import org.starfall.generative.shared.helpers.networking.ToolHttp
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.starfall.generative.shared.model.auth.Authorization

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FaviconServiceTest {
    private fun png(): ByteArray {
        val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        return java.io.ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it); bitmap.recycle() }.toByteArray()
    }
    private fun service() = FaviconService(ApplicationProvider.getApplicationContext(), ToolHttp(client = OkHttpClient.Builder().proxy(java.net.Proxy.NO_PROXY).dns(object : Dns {
        override fun lookup(hostname: String) = listOf(InetAddress.getByName("127.0.0.1"))
    }).followRedirects(false).build()))
    private fun image(bytes: ByteArray = png()) = MockResponse().setBody(okio.Buffer().write(bytes)).setHeader("Content-Type", "image/png")

    @Test fun homepageInferenceStripsApiPathsAndFallbacksRespectPublicSuffixes() {
        val home = faviconHomepage("", "https://api.example.co.uk/v1/chat/completions?key=secret")
        assertEquals("https://api.example.co.uk/", home.toString())
        assertEquals(listOf("https://api.example.co.uk/favicon.ico", "https://example.co.uk/favicon.ico",
            "https://api.example.co.uk/favicon.png", "https://example.co.uk/favicon.png"), faviconFallbackUrls(home).map { it.toString() })
        assertEquals("https://other.example/page", faviconHomepage("other.example/page", home.toString()).toString())
        assertEquals(2, faviconFallbackUrls(faviconHomepage("", "http://127.0.0.1:1234/api")).size)
        assertTrue(runCatching { faviconHomepage("", "") }.isFailure)
        assertTrue(runCatching { faviconHomepage("https://user:pass@example.com", "") }.isFailure)
    }

    @Test fun headLinksHandleRelativeUrlsBaseEntitiesAndIgnoreBodyCommentsAndScripts() {
        val home = faviconHomepage("https://example.com/page", "")
        val urls = faviconHeadUrls("""<html><head>
            <!-- <link rel="icon" href="fake.png"> -->
            <script>const fake = '<link rel="icon" href="fake2.png">';</script>
            <base href="/assets/">
            <LINK href='logo.png?a=1&amp;b=2' REL='shortcut icon'>
            <link rel=apple-touch-icon href=/apple.png>
            <link rel=stylesheet href=style.css>
            </head><body><link rel=icon href=/body.png></body></html>""", home)
        assertEquals(listOf("https://example.com/assets/logo.png?a=1&b=2", "https://example.com/apple.png"), urls.map { it.toString() })
    }

    @Test fun htmlIconIsPreviewedBeforeSavingAndDoesNotSendProviderCredentials() = runBlocking {
        val server = MockWebServer().apply { start() }
        try {
            server.enqueue(MockResponse().setBody("<head><link rel=icon href='/assets/logo.png'></head>"))
            server.enqueue(image())
            val context = ApplicationProvider.getApplicationContext<Context>()
            val store = IconStore(context)
            val before = java.io.File(context.filesDir, "entity-icons").listFiles().orEmpty().map { it.name }.toSet()
            val result = service().get("", server.url("/v1?api_key=secret").toString())
            val page = server.takeRequest()
            assertEquals("/", page.path)
            assertNull(page.getHeader("Authorization"))
            assertEquals("/assets/logo.png", server.takeRequest().path)
            assertEquals(before, java.io.File(context.filesDir, "entity-icons").listFiles().orEmpty().map { it.name }.toSet())
            val id = store.importBitmap(result.bitmap)
            assertTrue(id.startsWith("entity-icon-"))
            assertNotNull(store.load(id))
            result.bitmap.recycle()
        } finally { server.shutdown() }
    }

    @Test fun missingBrandFaviconIsPersistedAndMatchedWithoutRefetching() = runBlocking {
        val server = MockWebServer().apply { start() }
        try {
            server.enqueue(MockResponse().setBody("<head><link rel=icon href='/brand.png'></head>"))
            server.enqueue(image())
            val context = ApplicationProvider.getApplicationContext<Context>()
            val store = IconStore(context)
            val brand = "Test Speech Brand"
            store.ensureNamedFavicon(brand, server.url("/").toString())
            val id = store.find(brand)
            assertNotNull(id)
            assertTrue(id!!.startsWith("icon-"))
            assertNotNull(store.imageData(id))
            assertTrue(store.entries().any { it.filename == id })
            assertEquals(id, IconStore(context).find(brand))
            store.ensureNamedFavicon(brand, server.url("/").toString())
            assertEquals(2, server.requestCount)

            val explicit = store.importBitmap(Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888),
                "custom.png", shared = true)
            store.cache(brand, explicit)
            store.ensureNamedFavicon(brand, server.url("/").toString())
            assertEquals(explicit, IconStore(context).find(brand))
            assertEquals(2, server.requestCount)
        } finally { server.shutdown() }
    }

    @Test fun fallbackUsesHostIcoThenRootIcoThenHostPngThenRootPng() = runBlocking {
        val server = MockWebServer().apply { start() }
        try {
            // QueueDispatcher special-cases /favicon.ico without consuming its queue.
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when {
                    request.path == "/" -> MockResponse().setBody("<head><title>No icon links</title></head>")
                    request.path == "/favicon.png" && request.getHeader("Host")!!.startsWith("example.co.uk:") -> image()
                    else -> MockResponse().setResponseCode(404)
                }
            }
            val home = server.url("/").newBuilder().host("api.example.co.uk").build()
            val result = service().get("", home.toString())
            val requests = List(5) { server.takeRequest() }
            assertEquals(listOf("/", "/favicon.ico", "/favicon.ico", "/favicon.png", "/favicon.png"), requests.map { it.path })
            assertEquals(listOf("api.example.co.uk", "api.example.co.uk", "example.co.uk", "api.example.co.uk", "example.co.uk"),
                requests.map { it.getHeader("Host")!!.substringBefore(':') })
            assertEquals("example.co.uk", result.url.host)
            result.bitmap.recycle()
        } finally { server.shutdown() }
    }

    @Test fun redirectsResolveRelativeHeadIconsAgainstFinalPageAndInvalidImagesFallBack() = runBlocking {
        val server = MockWebServer().apply { start() }
        try {
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = when (request.path) {
                    "/" -> MockResponse().setResponseCode(302).setHeader("Location", "/site/index.html")
                    "/site/index.html" -> MockResponse().setBody("<head><link rel=icon href=bad.png></head>")
                    "/site/bad.png" -> MockResponse().setBody("not an image")
                    "/favicon.ico" -> image()
                    else -> MockResponse().setResponseCode(404)
                }
            }
            // A homepage belongs in baseUrl. customUrl intentionally performs an extra
            // direct-image probe before homepage discovery.
            val result = service().get("", server.url("/").toString())
            assertEquals(listOf("/", "/site/index.html", "/site/bad.png", "/favicon.ico"), List(4) { server.takeRequest().path })
            result.bitmap.recycle()
        } finally { server.shutdown() }
    }

    @Test fun icoSupportsEmbeddedPngAndLegacyDibTransparencyAndRejectsCorruptEntries() {
        fun ico(payload: ByteArray) = ByteBuffer.allocate(22 + payload.size).order(ByteOrder.LITTLE_ENDIAN).apply {
            putShort(0); putShort(1); putShort(1); put(1); put(1); put(0); put(0)
            putShort(1); putShort(32); putInt(payload.size); putInt(22); put(payload)
        }.array()
        val embedded = decodeFavicon(ApplicationProvider.getApplicationContext(), ico(png()))!!
        assertEquals(32, embedded.width); embedded.recycle()
        val dib = ByteBuffer.allocate(48).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(40); putInt(1); putInt(2); putShort(1); putShort(32)
            position(40); put(0); put(0); put(255.toByte()); put(0)
        }.array()
        val opaque = decodeFavicon(ApplicationProvider.getApplicationContext(), ico(dib))!!
        assertEquals(android.graphics.Color.RED, opaque.getPixel(0, 0)); opaque.recycle()
        dib[44] = 128.toByte()
        val transparent = decodeFavicon(ApplicationProvider.getApplicationContext(), ico(dib))!!
        assertEquals(0, android.graphics.Color.alpha(transparent.getPixel(0, 0))); transparent.recycle()
        assertNull(decodeFavicon(ApplicationProvider.getApplicationContext(), ico(png()).apply { this[18] = 255.toByte() }))
    }

    @Test fun allFailuresProduceAnExplicitMessageAndCancellationStopsTheRequest() = runBlocking {
        val server = MockWebServer().apply { start() }
        try {
            val slow = java.util.concurrent.atomic.AtomicBoolean(false)
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = if (slow.get())
                    MockResponse().setBody("<head></head>").setBodyDelay(5, java.util.concurrent.TimeUnit.SECONDS)
                    else MockResponse().setResponseCode(404)
            }
            val failure = runCatching { service().get("", server.url("/").toString()) }.exceptionOrNull()!!
            assertTrue(failure.message!!.contains("No favicon could be retrieved"))
            assertTrue(failure.message!!.contains("favicon.ico"))
            assertTrue(failure.message!!.contains("favicon.png"))
            repeat(3) { server.takeRequest() }
            slow.set(true)
            val job = launch { service().get("", server.url("/").toString()) }
            withContext(Dispatchers.IO) { assertNotNull(server.takeRequest(2, java.util.concurrent.TimeUnit.SECONDS)) }
            withTimeout(2000) { job.cancelAndJoin() }
        } finally { server.shutdown() }
    }
}
