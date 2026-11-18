package org.starfall.generative

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import org.starfall.generative.features.serverconnection.logic.runtimeMcpConfig

class RuntimeMcpConfigTest {
    @Test fun remoteFormPreservesHeadersAndProtocolOptions() {
        val config = runtimeMcpConfig("remote", "https://example.com/mcp", listOf("Authorization" to "Bearer secret"),
            """{"oauth":false,"timeout":{"startup":5000}}""")
        assertEquals(Json.parseToJsonElement("""{"type":"remote","url":"https://example.com/mcp","headers":{"Authorization":"Bearer secret"},"oauth":false,"timeout":{"startup":5000}}"""), config)
    }

    @Test fun localCommandRunsOnServerAndRetainsEnvironment() {
        assertEquals(Json.parseToJsonElement("""{"type":"local","command":["node","server.js"],"environment":{"TOKEN":"secret"}}"""),
            runtimeMcpConfig("local", "", emptyList(), """{"command":["node","server.js"],"environment":{"TOKEN":"secret"}}"""))
    }

    @Test fun invalidConfigCannotOverrideFormFieldsOrSendInvalidCommands() {
        assertNull(runtimeMcpConfig("remote", "", emptyList(), "{}"))
        assertNull(runtimeMcpConfig("remote", "https://example.com", emptyList(), """{"url":"https://other.com"}"""))
        assertNull(runtimeMcpConfig("remote", "https://example.com", listOf("X" to "a", "x" to "b"), "{}"))
        assertNull(runtimeMcpConfig("local", "", emptyList(), """{"command":[]}"""))
        assertNull(runtimeMcpConfig("local", "", emptyList(), """{"command":[1]}"""))
    }
}
