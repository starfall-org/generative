package org.starfall.generative

import com.sun.net.httpserver.HttpServer
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.starfall.generative.shared.subfeatures.logging.logic.DebugLog
import org.starfall.generative.shared.subfeatures.logging.logic.DebugLogInterceptor
import org.starfall.generative.shared.subfeatures.logging.logic.DebugLogKtorPlugin
import org.starfall.generative.shared.subfeatures.logging.logic.DebugLogRedactor
import org.starfall.generative.shared.model.logging.DebugLogCategory
import org.starfall.generative.shared.model.logging.DebugLogCategory.*
import org.starfall.generative.shared.model.logging.DebugLogLevel
import java.net.InetSocketAddress

class DebugLogTest {
    @Before fun reset() = DebugLog.configure(false, emptySet())
    @After fun tearDown() = DebugLog.configure(false, emptySet())

    @Test fun categorySelectionAndMinimumLevelControlCapture() {
        DebugLog.configure(true, setOf(NETWORK), DebugLogLevel.WARN)
        var built = false
        DebugLog.log(NETWORK, "HTTP", DebugLogLevel.DEBUG) { built = true; "private payload" }
        DebugLog.log(SYSTEM, "Logic", DebugLogLevel.ERROR) { "outside scope" }
        assertFalse(built)
        assertTrue(DebugLog.entries.value.isEmpty())
        DebugLog.error(NETWORK, "HTTP", "Network failed", IllegalStateException("broken"))
        assertEquals(listOf(NETWORK), DebugLog.entries.value.map { it.category })
        assertEquals(DebugLogLevel.ERROR, DebugLog.entries.value.single().level)
        assertTrue(DebugLog.entries.value.single().stackTrace!!.contains("IllegalStateException"))
    }

    @Test fun nativePrioritiesExposeTraceDebugInfoWarnAndError() {
        assertEquals(android.util.Log.VERBOSE, DebugLogLevel.TRACE.priority)
        assertEquals(android.util.Log.DEBUG, DebugLogLevel.DEBUG.priority)
        assertEquals(android.util.Log.INFO, DebugLogLevel.INFO.priority)
        assertEquals(android.util.Log.WARN, DebugLogLevel.WARN.priority)
        assertEquals(android.util.Log.ERROR, DebugLogLevel.ERROR.priority)
        DebugLog.configure(true, DebugLogCategory.all, DebugLogLevel.TRACE)
        DebugLogLevel.entries.forEach { level ->
            DebugLog.log(SYSTEM, "Runtime", level) { level.name }
        }
        assertEquals(DebugLogLevel.entries, DebugLog.entries.value.map { it.level })
        DebugLog.configure(true, DebugLogCategory.all, DebugLogLevel.ERROR)
        DebugLog.log(SYSTEM, "Runtime", DebugLogLevel.WARN) { "hidden" }
        assertTrue(DebugLog.entries.value.isEmpty())
        DebugLog.log(SYSTEM, "Runtime", DebugLogLevel.ERROR) { "visible error" }
        assertEquals(listOf(DebugLogLevel.ERROR), DebugLog.entries.value.map { it.level })
    }

    @Test fun legacySourceSettingsAndBackupEnumsMigrateToTwoSources() {
        assertEquals(DebugLogCategory.all, DebugLogCategory.fromStored(null))
        assertEquals(emptySet<DebugLogCategory>(), DebugLogCategory.fromStored(emptySet()))
        assertEquals(setOf(NETWORK), DebugLogCategory.fromStored(setOf("payload", "model", "network")))
        assertEquals(setOf(SYSTEM), DebugLogCategory.fromStored(setOf("tools", "tokens", "storage")))
        assertEquals(setOf(SYSTEM), DebugLogCategory.fromStored(setOf("system")))
        assertEquals(DebugLogCategory.all, DebugLogCategory.fromStored(setOf("errors")))
        assertEquals(DebugLogCategory.all,
            DebugLogCategory.fromStored(setOf("network", "payload", "tools", "tokens", "errors")))
        assertEquals(NETWORK, DebugLogCategory.fromValue("speech"))
        assertEquals(SYSTEM, DebugLogCategory.fromValue("generation"))
        assertEquals(DebugLogLevel.WARN, DebugLogLevel.fromStored(null))
        assertEquals(DebugLogLevel.ERROR, DebugLogLevel.fromStored("ERROR"))
        val serializer = kotlinx.serialization.json.Json
        val old = serializer.decodeFromString(
            org.starfall.generative.shared.model.logging.DebugLogCategorySerializer, "\"errors\"")
        assertEquals(SYSTEM, old)
        val encoded = serializer.encodeToString(
            org.starfall.generative.shared.model.logging.DebugLogCategorySerializer, NETWORK)
        assertEquals("\"network\"", encoded)
    }

    @Test fun operationFailureIsVisibleAtErrorThresholdWithoutSuccessNoise() {
        DebugLog.configure(true, setOf(SYSTEM), DebugLogLevel.ERROR)
        val start = DebugLog.beginOperation(SYSTEM, "Store", "Writing data", "messageCount=4")
        DebugLog.finishOperation(start, "Saved")
        assertTrue(DebugLog.entries.value.isEmpty())
        val failure = DebugLog.beginOperation(SYSTEM, "Store", "Writing data")
        DebugLog.finishOperation(failure, "Save failed", failed = true,
            error = IllegalStateException("storage error"))
        val entry = DebugLog.entries.value.single()
        assertEquals(DebugLogLevel.ERROR, entry.level)
        assertNotNull(entry.durationMillis)
        assertTrue(entry.format().contains("Stack trace:"))
        DebugLog.clear()
        DebugLog.finishOperation(failure, "Late result")
        assertTrue(DebugLog.entries.value.isEmpty())
    }

    @Test fun redactionCoversCopiedEntryAndLogcatMessage() {
        DebugLog.configure(true, DebugLogCategory.all, DebugLogLevel.TRACE)
        DebugLog.error(NETWORK, "HTTP", "Authorization: Bearer sk-private-token-12345",
            RuntimeException("Authorization: Bearer sk-secret-key-12345"))
        val entry = DebugLog.entries.value.single()
        assertFalse(entry.format().contains("sk-private-token-12345"))
        assertFalse(entry.format().contains("sk-secret-key-12345"))
        assertTrue(entry.stackTrace!!.contains("RuntimeException"))
        assertTrue(DebugLogRedactor.isSensitiveHeader("X-Api-Key"))
    }

    @Test fun okHttpCapturesStatusAndTimingsNotRequestOrResponseBodies() {
        DebugLog.configure(true, setOf(NETWORK), DebugLogLevel.INFO)
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("secret response"))
            server.enqueue(MockResponse().setResponseCode(500).setBody("server secret"))
            val client = OkHttpClient.Builder().addInterceptor(DebugLogInterceptor()).build()
            client.newCall(Request.Builder().url(server.url("/v1?key=private-credential"))
                .header("Authorization", "Bearer secret").build()).execute().close()
            client.newCall(Request.Builder().url(server.url("/v1")).build()).execute().close()
        }
        val entries = DebugLog.entries.value
        assertEquals(2, entries.size)
        assertEquals(listOf(DebugLogLevel.INFO, DebugLogLevel.ERROR), entries.map { it.level })
        assertEquals(200, entries[0].statusCode)
        assertEquals(500, entries[1].statusCode)
        assertEquals("GET", entries[0].method)
        assertNotNull(entries[1].durationMillis)
        assertFalse(DebugLog.exportAll().contains("private-credential"))
        assertFalse(DebugLog.exportAll().contains("secret response"))
        assertFalse(DebugLog.exportAll().contains("server secret"))
    }

    @Test fun networkErrorStillCapturedWhenInfoIsDisabled() {
        DebugLog.configure(true, setOf(NETWORK), DebugLogLevel.ERROR)
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("ok"))
            server.enqueue(MockResponse().setResponseCode(401).setBody("unauthorized"))
            val client = OkHttpClient.Builder().addInterceptor(DebugLogInterceptor()).build()
            client.newCall(Request.Builder().url(server.url("/ok")).build()).execute().close()
            client.newCall(Request.Builder().url(server.url("/bad")).build()).execute().close()
        }
        assertEquals(1, DebugLog.entries.value.size)
        assertEquals(401, DebugLog.entries.value.single().statusCode)
    }

    @Test fun ktorUsesSameNetworkSourceAndSeverity() = runBlocking {
        DebugLog.configure(true, setOf(NETWORK), DebugLogLevel.ERROR)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            exchange.sendResponseHeaders(404, -1)
            exchange.close()
        }
        server.start()
        try {
            HttpClient(CIO) { install(DebugLogKtorPlugin) }.use { client ->
                assertEquals(404, client.get("http://127.0.0.1:${server.address.port}/").status.value)
            }
            assertEquals(NETWORK, DebugLog.entries.value.single().category)
            assertEquals(DebugLogLevel.ERROR, DebugLog.entries.value.single().level)
        } finally { server.stop(0) }
    }

    @Test fun boundedBufferAndDisablingRemoveOnlyInAppViewerEntries() {
        DebugLog.configure(true, setOf(SYSTEM), DebugLogLevel.TRACE)
        repeat(DebugLog.MAX_ENTRIES + 4) { DebugLog.log(SYSTEM, "Logic") { "entry $it" } }
        assertEquals(DebugLog.MAX_ENTRIES, DebugLog.entries.value.size)
        assertEquals("entry 4", DebugLog.entries.value.first().message)
        DebugLog.configure(false, DebugLogCategory.all)
        assertTrue(DebugLog.entries.value.isEmpty())
    }
}
