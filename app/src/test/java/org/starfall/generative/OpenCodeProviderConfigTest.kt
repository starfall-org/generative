package org.starfall.generative

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import org.starfall.generative.integrations.opencode.*
import org.starfall.generative.shared.model.api.ModelApiType

@OptIn(ExperimentalSerializationApi::class)
class OpenCodeProviderConfigTest {
    private val json = Json { allowComments = true; allowTrailingComma = true }
    private fun obj(raw: String) = json.parseToJsonElement(raw).jsonObject
    private val provider = obj("""{"name":"Gateway","package":"@opencode/ai/providers/openai-compatible","settings":{"baseURL":"https://gateway.example/v1","apiKey":"test-key"},"models":{"model-a":{}}}""")

    @Test fun nativeProviderUsesAppApiTypeAndExplicitModelIds() {
        assertEquals(provider, openCodeProviderConfig("Gateway", ModelApiType.OPENAI,
            "https://gateway.example/v1", "test-key", listOf("model-a")))
        val responses = openCodeProviderConfig("Gateway", ModelApiType.OPENAI_RESPONSES,
            "https://gateway.example/v1", "", listOf("org/model", "org/model"))!!
        assertEquals("@opencode/ai/providers/openai-compatible/responses", responses.getValue("package").jsonPrimitive.content)
        assertEquals(setOf("org/model"), responses.getValue("models").jsonObject.keys)
        assertNull(responses.getValue("settings").jsonObject["apiKey"])
        assertNull(openCodeProviderConfig("Gateway", ModelApiType.GITHUB_COPILOT, "https://example.com", "", listOf("a")))
        assertNull(openCodeProviderConfig("Gateway", ModelApiType.OPENAI, "file:///tmp/path", "", listOf("a")))
        assertNull(openCodeProviderConfig("Gateway", ModelApiType.OPENAI, "https://example.com", "", emptyList()))
    }

    @Test fun insertsIntoJsoncWithoutRewritingOtherProvidersOrComments() {
        val old = """
            // This comment stays.
            {
              "model": "old/model",
              "nested": { "providers": {"keep": {}} },
              "providers": {
                /* keep this key reference */ "old": {"settings":{"apiKey":"{env:OLD_KEY}","baseURL":"https://old.example/v1"}},
              },
              "instructions": ["AGENTS.md"],
            }
        """.trimIndent()
        val updated = insertOpenCodeProvider(old, "gateway", provider)
        val inserted = "\n    \"gateway\": " + provider.toString() + ","
        // The only byte modification is insertion inside the root providers object.
        assertEquals(old, updated.replaceFirst(inserted, ""))
        val before = obj(old)
        val after = obj(updated)
        assertEquals(before.filterKeys { it != "providers" }, after.filterKeys { it != "providers" })
        assertEquals(before.getValue("providers").jsonObject["old"], after.getValue("providers").jsonObject["old"])
        assertEquals(provider, after.getValue("providers").jsonObject["gateway"])
    }

    @Test fun createsProvidersSectionAndRejectsDuplicateOrMalformedFile() {
        val raw = """{"mcp":{"servers":{}}}"""
        val updated = obj(insertOpenCodeProvider(raw, "gateway", provider))
        assertEquals(obj(raw)["mcp"], updated["mcp"])
        assertEquals(provider, updated.getValue("providers").jsonObject["gateway"])
        assertEquals(provider, obj(insertOpenCodeProvider("""{"providers":{}}""", "gateway", provider))
            .getValue("providers").jsonObject["gateway"])
        for (source in listOf("""{"providers":{"gateway":{}}}""", """{"providers":42}""", """{broken""")) {
            assertTrue(runCatching { insertOpenCodeProvider(source, "gateway", provider) }.isFailure)
        }
    }

    private val sources = """[
        {"type":"document","path":"/home/user/.config/opencode/opencode.json","info":{"providers":{}}},
        {"type":"directory","path":"/home/user/.config/opencode"},
        {"type":"document","path":"/work/opencode.jsonc","info":{"providers":{}}},
        {"type":"document","path":"/etc/opencode/opencode.json","info":{"providers":{}}}
    ]"""
    private val entries get() = json.parseToJsonElement(sources).jsonArray.map { it.jsonObject }

    @Test fun targetIsRequestedProjectOrGlobalNotManagedSystemFile() {
        assertEquals(OpenCodeProviderConfigFile("/home/user/.config/opencode/opencode.json", true),
            selectOpenCodeProviderConfig(entries, null))
        assertEquals(OpenCodeProviderConfigFile("/work/opencode.jsonc", true), selectOpenCodeProviderConfig(entries, "/work"))
        assertEquals(OpenCodeProviderConfigFile("/other/opencode.json", false), selectOpenCodeProviderConfig(entries, "/other"))
        assertTrue(runCatching { selectOpenCodeProviderConfig(entries, "/etc/opencode") }.isFailure)
    }

    @Test fun fileWriteUsesRawConfigNotRedactedConfigSourceAndReloadsAfterWrite() = runBlocking {
        MockWebServer().use { server ->
            val raw = """{"providers":{"old":{"settings":{"apiKey":"keep-original"}}},"mcp":{"servers":{}}}"""
            server.enqueue(MockResponse().setBody(sources))
            server.enqueue(MockResponse().setBody(sources))
            server.enqueue(MockResponse().setBody("""{"data":[{"id":"old"}]}"""))
            server.enqueue(MockResponse().setBody(raw))
            server.enqueue(MockResponse().setBody("""{"data":{"path":"/work/opencode.jsonc"}}"""))
            server.enqueue(MockResponse().setResponseCode(204))
            val api = OpenCodeApi(server.url("/").toString(), OpenCodeCredentials("opencode", "test"))
            val target = api.providerConfigFile("/work")
            assertTrue(api.addConfiguredProvider(target, "gateway", provider, "/work"))
            repeat(2) {
                val config = server.takeRequest()
                assertEquals("/api/config", config.requestUrl?.encodedPath)
                assertEquals("/work", config.requestUrl?.queryParameter("location[directory]"))
            }
            assertEquals("/api/provider", server.takeRequest().requestUrl?.encodedPath)
            val read = server.takeRequest()
            assertEquals("/api/fs/read/opencode.jsonc", read.requestUrl?.encodedPath)
            assertEquals("/work", read.requestUrl?.queryParameter("location[directory]"))
            val write = server.takeRequest()
            assertEquals("POST", write.method)
            assertEquals("/api/experimental/fs/write", write.requestUrl?.encodedPath)
            assertEquals("/work/opencode.jsonc", write.requestUrl?.queryParameter("path"))
            assertTrue(write.getHeader("Content-Type")!!.startsWith("application/octet-stream"))
            assertEquals(insertOpenCodeProvider(raw, "gateway", provider), write.body.readUtf8())
            assertEquals("/api/location/reload", server.takeRequest().requestUrl?.encodedPath)
        }
    }

    @Test fun duplicateCatalogIdNeverWritesFile() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(sources))
            server.enqueue(MockResponse().setBody("""{"data":[{"id":"gateway"}]}"""))
            val api = OpenCodeApi(server.url("/").toString(), OpenCodeCredentials("opencode", "test"))
            val target = OpenCodeProviderConfigFile("/work/opencode.jsonc", true)
            assertTrue(runCatching { api.addConfiguredProvider(target, "gateway", provider, "/work") }.isFailure)
            assertEquals(2, server.requestCount)
        }
    }

    @Test fun successfulWriteWithFailedReloadIsReportedSeparately() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(sources))
            server.enqueue(MockResponse().setBody("""{"data":[]}"""))
            server.enqueue(MockResponse().setBody("{}"))
            server.enqueue(MockResponse().setBody("""{"data":{"path":"/work/opencode.jsonc"}}"""))
            server.enqueue(MockResponse().setResponseCode(503))
            val api = OpenCodeApi(server.url("/").toString(), OpenCodeCredentials("opencode", "test"))
            assertFalse(api.addConfiguredProvider(OpenCodeProviderConfigFile("/work/opencode.jsonc", true),
                "gateway", provider, "/work"))
            assertEquals(5, server.requestCount)
        }
    }
    @Test fun providerEditAndDeletePreserveOtherEntriesAndRawSecretReferences() {
        val raw = """
            {
              "providers": {
                "first": {"name":"First"},
                // preserve this note
                "gateway": {"name":"Gateway","settings":{"apiKey":"{env:KEY}","timeout":300},"models":{"model-a":{"limit":{"output":8192}}}},
                "last": {"name":"Last"},
              },
              "mcp": {"servers": {}},
            }
        """.trimIndent()
        val path = listOf("providers", "gateway")
        val entry = OpenCodeCatalogEntry(OpenCodeProviderConfigFile("/config/opencode.jsonc", true), path,
            requireNotNull(openCodeConfigObject(raw, path)))
        val initial = entry.toProvider(org.starfall.generative.shared.model.provider.ModelProvider(
            "gateway", "Gateway", ModelApiType.OPENAI, baseUrl = "https://gateway.example/v1"))
        val updated = entry.withProvider(initial, initial.copy(name = "Updated"))
        assertEquals("{env:KEY}", updated.getValue("settings").jsonObject.getValue("apiKey").jsonPrimitive.content)
        assertEquals(entry.value["models"], updated["models"])
        val edited = replaceOpenCodeConfigEntry(raw, entry, updated)
        assertEquals(raw.replace(entry.value.toString(), updated.toString()), edited)
        val deleted = replaceOpenCodeConfigEntry(edited, entry.copy(value = updated), null)
        assertTrue(deleted.contains("// preserve this note"))
        assertEquals(setOf("first", "last"), obj(deleted).getValue("providers").jsonObject.keys)
        assertEquals(obj(raw)["mcp"], obj(deleted)["mcp"])
        assertTrue(runCatching { replaceOpenCodeConfigEntry(edited, entry, null) }.isFailure)
    }

    @Test fun mcpRenameAndEditPreserveServerOptionsAndUnrelatedServices() {
        val raw = """{"mcp":{"servers":{"tool":{"type":"remote","url":"https://old.example/mcp","pool":true,"oauth":{"scope":"tools"}},"keep":{"type":"local","command":["keep"]}}}}"""
        val path = listOf("mcp", "servers", "tool")
        val entry = OpenCodeCatalogEntry(OpenCodeProviderConfigFile("/config/opencode.jsonc", true), path,
            requireNotNull(openCodeConfigObject(raw, path)))
        val initial = entry.toMcp()
        val replacement = entry.withMcp(initial, initial.copy(name = "renamed", url = "https://new.example/mcp"))
        val changed = replaceOpenCodeConfigEntry(raw, entry, replacement, newKey = "renamed")
        assertEquals("""{"type":"remote","url":"https://new.example/mcp","pool":true,"oauth":{"scope":"tools"}}""", replacement.toString())
        assertEquals(replacement, openCodeConfigObject(changed, listOf("mcp", "servers", "renamed")))
        assertEquals(openCodeConfigObject(raw, listOf("mcp", "servers", "keep")),
            openCodeConfigObject(changed, listOf("mcp", "servers", "keep")))
        assertNull(openCodeConfigObject(changed, path))
        assertTrue(runCatching { replaceOpenCodeConfigEntry(raw, entry, replacement, newKey = "keep") }.isFailure)
    }

    @Test fun writeRejectsExternalChangesAndOnlyWritesToTheOpenedConfigSource() = runBlocking {
        MockWebServer().use { server ->
            val path = listOf("providers", "gateway")
            val sources = """[{"type":"directory","path":"/config"},{"type":"document","path":"/config/opencode.jsonc","info":{"providers":{"gateway":{}}}}]"""
            val raw = """{"providers":{"gateway":{}}}"""
            server.enqueue(MockResponse().setBody(sources))
            server.enqueue(MockResponse().setBody(raw))
            val api = OpenCodeApi(server.url("/").toString(), OpenCodeCredentials("opencode", "test"))
            val entry = api.catalogConfigEntry(path, null)

            server.enqueue(MockResponse().setBody(sources))
            server.enqueue(MockResponse().setBody(raw))
            server.enqueue(MockResponse().setBody("""{"providers":{"gateway":{}},"model":"someone/else"}"""))
            assertTrue(runCatching { api.changeCatalogConfigEntry(entry, provider, null) }.isFailure)
            repeat(5) { assertEquals("GET", server.takeRequest().method) }

            server.enqueue(MockResponse().setBody(sources))
            server.enqueue(MockResponse().setBody(raw))
            server.enqueue(MockResponse().setBody(raw))
            server.enqueue(MockResponse().setBody("""{"data":{"path":"/config/opencode.jsonc"}}"""))
            server.enqueue(MockResponse().setResponseCode(204))
            assertTrue(api.changeCatalogConfigEntry(entry, null, null))
            repeat(3) { assertEquals("GET", server.takeRequest().method) }
            val write = server.takeRequest()
            assertEquals("/config/opencode.jsonc", write.requestUrl?.queryParameter("path"))
            assertEquals("""{"providers":{}}""", write.body.readUtf8())
            assertEquals("/api/location/reload", server.takeRequest().requestUrl?.encodedPath)
        }
    }

}
