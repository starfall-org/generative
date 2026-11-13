package org.starfall.generative


import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.serialization.json.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.toList
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import org.robolectric.annotation.Config
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.starfall.generative.shared.model.*
import org.starfall.generative.integrations.files.*
import org.starfall.generative.shared.subfeatures.media.logic.*
import org.starfall.generative.shared.helpers.networking.*
import org.starfall.generative.database.preferences.*
import org.starfall.generative.features.mcp.shared.ui.*
import org.starfall.generative.integrations.mcp.*
import org.starfall.generative.features.machines.shared.ui.*
import org.starfall.generative.integrations.remote.*
import org.starfall.generative.features.home.chat.*
import org.starfall.generative.integrations.modelapis.*
import java.nio.file.Files
import org.starfall.generative.shared.model.auth.Authorization
import org.starfall.generative.shared.model.generation.GenerationEvent
import org.starfall.generative.shared.model.api.ModelApiType
import org.starfall.generative.shared.model.language.ModelConfiguration
import org.starfall.generative.shared.model.provider.ModelProvider
import org.starfall.generative.shared.model.language.ModelType
import org.starfall.generative.shared.model.provider.ProviderConfiguration
import org.starfall.generative.features.home.chat.logic.tool.ToolChatViewModel

@Config(sdk = [28])
@RunWith(RobolectricTestRunner::class)
class ToolChatIntegrationTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test(timeout = 60000) fun manyToolCallsAreExecutedInOrderWithAndWithoutStreaming() = runBlocking {
        for (streaming in listOf(false, true)) {
            val server = MockWebServer()
            server.start()
            val root = Files.createTempDirectory("many-tool-calls-").toFile()
            var modelRequests = 0
            var resultIds = emptyList<String>()
            val ids = (0 until 25).map { "call-$it" }
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
                    modelRequests++
                    if (modelRequests > 1) {
                        val results = body.getValue("messages").jsonArray.map { it.jsonObject }
                            .filter { it.text("role") == "tool" }
                        resultIds = results.map { it.text("tool_call_id") }
                        return if (streaming) MockResponse().setBody(
                            "data: {\"choices\":[{\"delta\":{\"content\":\"Done\"}}]}\n\ndata: [DONE]\n\n")
                        else MockResponse().setBody("""{"choices":[{"message":{"content":"Done"}}]}""")
                    }
                    val calls = ids.map { id -> obj("id" to str(id), "type" to str("function"),
                        "function" to obj("name" to str("search_tools"), "arguments" to str("{\"query\":\"test\"}"))) }
                    return if (streaming) {
                        val frames = calls.mapIndexed { index, call ->
                            val delta = JsonObject(call + ("index" to JsonPrimitive(index)))
                            "data: ${obj("choices" to JsonArray(listOf(obj("delta" to obj("tool_calls" to JsonArray(listOf(delta)))))))}\n\n"
                        }.joinToString("")
                        MockResponse().setBody(frames + "data: [DONE]\n\n")
                    } else {
                        MockResponse().setBody(obj("choices" to JsonArray(listOf(obj("message" to obj(
                            "role" to str("assistant"), "tool_calls" to JsonArray(calls)))))).toString())
                    }
                }
            }
            try {
                val provider = ModelProvider("p", "Chat", ModelApiType.OPENAI,
                    baseUrl = server.url("/v1").toString(),
                    config = ProviderConfiguration(supportStream = streaming))
                val http = ToolHttp(ToolFiles(root))
                val events = ToolChatViewModel(http, McpService(http), AiService(context)).generate(
                    provider, "chat", listOf(StoredMessage("u", ChatRole.USER, listOf(MessageVersion("Search")))),
                    "", emptyList(), listOf(provider), { ToolSettings(searchToolsEnabled = true) }
                ).toList()
                assertEquals(2, modelRequests)
                assertEquals(ids, resultIds)
                assertEquals(25, events.filterIsInstance<GenerationEvent.Tool>().count { it.activity.status == "running" })
                assertEquals("Done", events.filterIsInstance<GenerationEvent.Text>().joinToString("") { it.text })
            } finally {
                server.shutdown()
                root.deleteRecursively()
            }
        }
    }

    @Test(timeout = 60000) fun manyCallsAreReturnedInProviderSpecificHistory() = runBlocking {
        for (type in listOf(ModelApiType.OPENAI_RESPONSES, ModelApiType.ANTHROPIC, ModelApiType.GOOGLE, ModelApiType.OLLAMA)) {
            val server = MockWebServer()
            server.start()
            val root = Files.createTempDirectory("many-provider-calls-").toFile()
            val requests = mutableListOf<JsonObject>()
            val ids = (0 until 20).map { "call-$it" }
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
                    val first = synchronized(requests) { requests.add(body); requests.size == 1 }
                    val response = when (type) {
                        ModelApiType.OPENAI_RESPONSES -> obj("status" to str("completed"), "output" to JsonArray(
                            if (first) ids.map { id -> obj("type" to str("function_call"), "call_id" to str(id),
                                "name" to str("search_tools"), "arguments" to str("{\"query\":\"test\"}")) }
                            else listOf(obj("type" to str("message"), "content" to JsonArray(listOf(
                                obj("type" to str("output_text"), "text" to str("Done"))))))))
                        ModelApiType.ANTHROPIC -> obj("content" to JsonArray(
                            if (first) ids.map { id -> obj("type" to str("tool_use"), "id" to str(id),
                                "name" to str("search_tools"), "input" to obj("query" to str("test"))) }
                            else listOf(obj("type" to str("text"), "text" to str("Done")))))
                        ModelApiType.GOOGLE -> obj("candidates" to JsonArray(listOf(obj("content" to obj("parts" to JsonArray(
                            if (first) ids.map { obj("functionCall" to obj("name" to str("search_tools"),
                                "args" to obj("query" to str("test")))) }
                            else listOf(obj("text" to str("Done")))))))))
                        else -> obj("message" to obj("content" to str(if (first) "" else "Done"), "tool_calls" to JsonArray(
                            if (first) ids.map { id -> obj("id" to str(id), "function" to obj("name" to str("search_tools"),
                                "arguments" to obj("query" to str("test")))) } else emptyList())))
                    }
                    return MockResponse().setBody(response.toString())
                }
            }
            try {
                val base = if (type == ModelApiType.GOOGLE) "/v1beta" else if (type == ModelApiType.OLLAMA) "/api" else "/v1"
                val provider = ModelProvider("p", "Chat", type, baseUrl = server.url(base).toString(),
                    config = ProviderConfiguration(supportStream = false))
                val http = ToolHttp(ToolFiles(root))
                val events = ToolChatViewModel(http, McpService(http), AiService(context)).generate(
                    provider, "chat", listOf(StoredMessage("u", ChatRole.USER, listOf(MessageVersion("Search")))),
                    "", emptyList(), listOf(provider), { ToolSettings(searchToolsEnabled = true) }
                ).toList()
                assertEquals(type.toString(), 2, requests.size)
                val second = requests[1]
                val resultIds = when (type) {
                    ModelApiType.OPENAI_RESPONSES -> second.getValue("input").jsonArray.map { it.jsonObject }
                        .filter { it.text("type") == "function_call_output" }.map { it.text("call_id") }
                    ModelApiType.ANTHROPIC -> second.getValue("messages").jsonArray.flatMap { it.jsonObject.getValue("content").jsonArray }
                        .map { it.jsonObject }.filter { it.text("type") == "tool_result" }.map { it.text("tool_use_id") }
                    ModelApiType.GOOGLE -> second.getValue("contents").jsonArray.flatMap { it.jsonObject.getValue("parts").jsonArray }
                        .count { it.jsonObject.containsKey("functionResponse") }.let { count -> List(count) { "result" } }
                    else -> second.getValue("messages").jsonArray.map { it.jsonObject }
                        .filter { it.text("role") == "tool" }.map { it.text("tool_call_id") }
                }
                if (type == ModelApiType.GOOGLE) assertEquals(type.toString(), 20, resultIds.size)
                else assertEquals(type.toString(), ids, resultIds)
                assertEquals("Done", events.filterIsInstance<GenerationEvent.Text>().joinToString("") { it.text })
            } finally {
                server.shutdown()
                root.deleteRecursively()
            }
        }
    }

    @Test fun pinnedWebSearchServiceUsesFixedWirePrefixAndOriginalMcpToolName() = runBlocking {
        for (pinned in listOf(false, true)) {
            val server = MockWebServer()
            server.start()
            val root = Files.createTempDirectory("web-search-tool-").toFile()
            val seenModelNames = mutableListOf<String>()
            val calledMcpNames = mutableListOf<String>()
            val expected = if (pinned) "WebSearch_awesome_tool" else "My_Server_awesome_tool"
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.method == "DELETE") return MockResponse().setResponseCode(204)
                    if (request.method == "GET" && request.path == "/mcp") return MockResponse().setResponseCode(405)
                    val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
                    fun json(value: JsonElement) = MockResponse()
                        .addHeader("Content-Type", "application/json").setBody(value.toString())
                    if (request.path == "/mcp") {
                        val result = when (body.text("method")) {
                            "notifications/initialized" -> return MockResponse().setResponseCode(202)
                            "initialize" -> obj("protocolVersion" to str("2025-06-18"), "capabilities" to obj("tools" to obj()),
                                "serverInfo" to obj("name" to str("test"), "version" to str("1")))
                            "tools/list" -> obj("tools" to JsonArray(listOf(obj("name" to str("awesome_tool"),
                                "inputSchema" to obj("type" to str("object"))))))
                            "tools/call" -> {
                                calledMcpNames += body.requireObject("params").text("name")
                                obj("content" to JsonArray(listOf(obj("type" to str("text"), "text" to str("search result")))))
                            }
                            else -> error("Unexpected MCP method: ${body.text("method")}")
                        }
                        return json(obj("jsonrpc" to str("2.0"), "id" to body.getValue("id"), "result" to result))
                    }
                    val toolNames = body.getValue("tools").jsonArray.map {
                        it.jsonObject.requireObject("function").text("name")
                    }
                    seenModelNames += toolNames
                    assertEquals(listOf(expected), toolNames)
                    val reply = if (seenModelNames.size == 1) {
                        obj("role" to str("assistant"), "content" to str(""),
                            "tool_calls" to JsonArray(listOf(obj("id" to str("call1"), "type" to str("function"),
                                "function" to obj("name" to str(expected), "arguments" to str("{}"))))))
                    } else {
                        assertTrue(body.toString().contains("search result"))
                        obj("role" to str("assistant"), "content" to str("Done"))
                    }
                    return json(obj("choices" to JsonArray(listOf(obj("message" to reply)))))
                }
            }
            try {
                val provider = ModelProvider("p", "Chat", ModelApiType.OPENAI,
                    baseUrl = server.url("/v1").toString(),
                    config = ProviderConfiguration(supportStream = false))
                val mcp = McpInfo("search", "My Server", url = server.url("/mcp").toString())
                val http = ToolHttp(ToolFiles(root))
                val settings = ToolSettings(
                    webSearchProviderId = if (pinned) mcp.id else null,
                    webSearchEnabled = pinned
                )
                val events = ToolChatViewModel(http, McpService(http), AiService(context)).generate(
                    provider, "chat", listOf(StoredMessage("u", ChatRole.USER, listOf(MessageVersion("Search")))),
                    "", listOf(mcp), listOf(provider), { settings }
                ).toList()
                assertEquals(2, seenModelNames.size)
                assertEquals(listOf("awesome_tool"), calledMcpNames)
                assertEquals(expected, events.filterIsInstance<GenerationEvent.Tool>()
                    .first { it.activity.wireName == expected }.activity.wireName)
                assertEquals("Done", events.filterIsInstance<GenerationEvent.Text>().joinToString("") { it.text })
            } finally {
                server.shutdown()
                root.deleteRecursively()
            }
        }
    }

    @Test fun disabledSearchDoesNotAttachSkillToolsForMatchingTaskOrPastSkill() = runBlocking {
        val server = MockWebServer()
        server.start()
        val root = Files.createTempDirectory("no-skill-tools-").toFile()
        try {
            server.enqueue(MockResponse().addHeader("Content-Type", "application/json").setBody(
                """{"choices":[{"message":{"role":"assistant","content":"Hello!"}}]}"""))
            val provider = ModelProvider("p", "Chat", ModelApiType.OPENAI,
                baseUrl = server.url("/v1").toString(),
                config = ProviderConfiguration(supportStream = false))
            val http = ToolHttp(ToolFiles(root))
            val skill = AgentSkill("review-code", "review-code", "Never send this body", "Review code")
            val events = ToolChatViewModel(http, McpService(http), AiService(context),
                availableSkills = { listOf(skill) }
            ).generate(provider, "chat",
                listOf(
                    StoredMessage("previous", ChatRole.USER, listOf(MessageVersion(
                        "Use skill review-code", selectedSkillName = "review-code"))),
                    StoredMessage("previous-reply", ChatRole.MODEL, listOf(MessageVersion(
                        content = "I reviewed it", toolActivity = listOf(ToolActivity(
                            "old-call", "activate_skill", "activate_skill", "success",
                            arguments = """{"name":"review-code"}""".toToolPayload()))))),
                    StoredMessage("u", ChatRole.USER, listOf(MessageVersion("Review this code")))
                ),
                "System instructions", emptyList(), listOf(provider),
                { ToolSettings(searchToolsEnabled = true) }).toList()
            val body = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
            val toolNames = body.getValue("tools").jsonArray.map {
                it.jsonObject.getValue("function").jsonObject.getValue("name").jsonPrimitive.content
            }
            assertEquals(listOf("search_tools"), toolNames)
            assertFalse(body.toString().contains("available_skills"))
            assertFalse(body.toString().contains("activate_skill"))
            assertFalse(body.toString().contains("read_skill_resource"))
            // Earlier user text remains ordinary conversation history, but skill tools
            // and their previous tool-call metadata must not be replayed.
            assertFalse(body.toString().contains("Never send this body"))
            assertTrue(events.filterIsInstance<GenerationEvent.Text>().isNotEmpty())
        } finally {
            server.shutdown()
            root.deleteRecursively()
        }
    }

    @Test fun enabledSkillSearchOnlyAttachesOtherToolsAfterSearchAndActivation() = runBlocking {
        val server = MockWebServer()
        server.start()
        val root = Files.createTempDirectory("skills-search-turns-").toFile()
        val skillsRoot = Files.createTempDirectory("skills-search-files-").toFile()
        try {
            val store = AgentSkillsStore(skillsRoot)
            val skill = AgentSkill("review-code", "review-code", "Inspect references/checklist.md", "Review code changes")
            val irrelevant = AgentSkill("translate-text", "translate-text", "Private translation instructions", "Translate text")
            store.replaceAll(listOf(skill, irrelevant))
            val checklist = skillsRoot.resolve("review-code/references/checklist.md")
            requireNotNull(checklist.parentFile).mkdirs()
            checklist.writeText("Verify tests and edge cases")
            val requests = mutableListOf<JsonObject>()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
                    val round = synchronized(requests) { requests.add(body); requests.size }
                    fun invoke(name: String, args: String) = obj(
                        "role" to str("assistant"), "content" to str(""),
                        "tool_calls" to JsonArray(listOf(obj(
                            "id" to str("skill-call-$round"), "type" to str("function"),
                            "function" to obj("name" to str(name), "arguments" to str(args))
                        )))
                    )
                    val reply = when (round) {
                        1 -> invoke("agent_skills_search", """{"query":"review code"}""")
                        2 -> invoke("activate_skill", """{"name":"review-code"}""")
                        3 -> invoke("read_skill_resource", """{"name":"review-code","path":"references/checklist.md"}""")
                        else -> obj("role" to str("assistant"), "content" to str("Review done"))
                    }
                    return MockResponse().addHeader("Content-Type", "application/json")
                        .setBody(obj("choices" to JsonArray(listOf(obj("message" to reply)))).toString())
                }
            }
            val provider = ModelProvider("p", "Chat", ModelApiType.OPENAI,
                baseUrl = server.url("/v1").toString(),
                config = ProviderConfiguration(supportStream = false))
            val http = ToolHttp(ToolFiles(root))
            val events = ToolChatViewModel(http, McpService(http), AiService(context),
                availableSkills = { listOf(skill, irrelevant) },
                skillResources = { store.listResources(context, it) },
                readSkillResource = { name, path -> store.readResource(context, name, path) }
            ).generate(provider, "chat",
                listOf(StoredMessage("u", ChatRole.USER, listOf(MessageVersion("Review this code")))),
                "", emptyList(), listOf(provider), { ToolSettings(agentSkillsSearchEnabled = true) }).toList()
            fun toolNames(body: JsonObject): Set<String> = body.getValue("tools").jsonArray.map {
                it.jsonObject.getValue("function").jsonObject.getValue("name").jsonPrimitive.content
            }.toSet()
            assertEquals(4, requests.size)
            assertEquals(setOf("agent_skills_search"), toolNames(requests[0]))
            assertEquals(setOf("agent_skills_search", "activate_skill"), toolNames(requests[1]))
            assertEquals(setOf("agent_skills_search", "activate_skill", "read_skill_resource"), toolNames(requests[2]))
            assertFalse(requests[0].toString().contains("Inspect references/checklist.md"))
            assertTrue(requests[1].toString().contains("Review code changes"))
            assertFalse(requests[1].toString().contains("Private translation instructions"))
            assertFalse(requests[1].toString().contains("translate-text"))
            assertTrue(requests[2].toString().contains("Inspect references/checklist.md"))
            assertTrue(requests[3].toString().contains("Verify tests and edge cases"))
            assertTrue(events.filterIsInstance<GenerationEvent.Text>().any { it.text.contains("Review done") })
        } finally {
            server.shutdown()
            root.deleteRecursively()
            skillsRoot.deleteRecursively()
        }
    }

    @Test fun agentDiscoversLoadsAndReadsSkillResourcesOnDemand() = runBlocking {
        val server = MockWebServer()
        server.start()
        val root = Files.createTempDirectory("skill-tools-integration-").toFile()
        val skillsRoot = Files.createTempDirectory("skill-directory-").toFile()
        try {
            val store = AgentSkillsStore(skillsRoot)
            val skill = AgentSkill("review", "review", "Use references/checks.md for review details", "Review patches")
            store.replaceAll(listOf(skill))
            val reference = skillsRoot.resolve("review/references/checks.md")
            requireNotNull(reference.parentFile).mkdirs()
            reference.writeText("Check tests and boundary cases")
            val requests = mutableListOf<String>()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
                    synchronized(requests) { requests += body.toString() }
                    val round = synchronized(requests) { requests.size }
                    val response = when (round) {
                        1 -> obj("role" to str("assistant"), "content" to str(""),
                            "tool_calls" to JsonArray(listOf(obj("id" to str("call-activate"), "type" to str("function"),
                                "function" to obj("name" to str("activate_skill"), "arguments" to str("""{"name":"review"}"""))))))
                        2 -> obj("role" to str("assistant"), "content" to str(""),
                            "tool_calls" to JsonArray(listOf(obj("id" to str("call-resource"), "type" to str("function"),
                                "function" to obj("name" to str("read_skill_resource"),
                                    "arguments" to str("""{"name":"review","path":"references/checks.md"}"""))))))
                        else -> obj("role" to str("assistant"), "content" to str("Review complete"))
                    }
                    return MockResponse().addHeader("Content-Type", "application/json").setBody(
                        obj("choices" to JsonArray(listOf(obj("message" to response)))).toString())
                }
            }
            val provider = ModelProvider("p", "Chat", ModelApiType.OPENAI,
                baseUrl = server.url("/v1").toString(),
                config = ProviderConfiguration(supportStream = false))
            val http = ToolHttp(ToolFiles(root))
            val events = ToolChatViewModel(http, McpService(http), AiService(context),
                availableSkills = { listOf(skill) },
                skillResources = { store.listResources(context, it) },
                readSkillResource = { name, path -> store.readResource(context, name, path) }
            ).generate(provider, "chat",
                listOf(StoredMessage("u", ChatRole.USER, listOf(MessageVersion("Please review this patch", selectedSkillName = "review")))),
                "", emptyList(), listOf(provider), { ToolSettings() }).toList()
            assertEquals(3, requests.size)
            assertTrue(requests[0].contains("Review patches"))
            assertFalse(requests[0].contains("Use references/checks.md"))
            assertTrue(requests[0].contains("activate_skill"))
            assertFalse(requests[0].contains("agent_skills_search"))
            assertFalse(requests[0].contains("read_skill_resource"))
            assertTrue(requests[1].contains("read_skill_resource"))
            assertTrue(requests[1].contains("Use references/checks.md"))
            assertTrue(requests[2].contains("Check tests and boundary cases"))
            assertTrue(events.filterIsInstance<GenerationEvent.Text>().any { it.text.contains("Review complete") })
        } finally {
            server.shutdown()
            root.deleteRecursively()
            skillsRoot.deleteRecursively()
        }
    }

    @Test fun contentApiDisplaysVideoWithoutExposingMediaUrlOrOfferingSendFile() = contentApiScenario()

    private fun contentApiScenario() = runBlocking {
        val server = MockWebServer()
        server.start()
        val root = Files.createTempDirectory("content-api-loop").toFile()
        val url = server.url("/cdn/video?signature=private").toString()
        val video = ByteArray(32).also { "ftyp".toByteArray().copyInto(it, 4) }
        var rounds = 0
        var followup = ""
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                fun json(value: JsonElement) = MockResponse().setHeader("Content-Type", "application/json").setBody(value.toString())
                if (request.method == "DELETE") return MockResponse().setResponseCode(204)
                if (request.method == "GET" && request.path == "/mcp") return MockResponse().setResponseCode(405)
                if (request.path?.startsWith("/cdn/video") == true) {
                    assertNull(request.getHeader("Authorization"))
                    return MockResponse().setHeader("Content-Type", "video/mp4").setBody(okio.Buffer().write(video))
                }
                val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
                if (request.path == "/mcp") {
                    val result = when (body.text("method")) {
                        "notifications/initialized" -> return MockResponse().setResponseCode(202)
                        "initialize" -> obj("protocolVersion" to str("2025-06-18"), "capabilities" to obj("tools" to obj()), "serverInfo" to obj("name" to str("test"), "version" to str("1")))
                        "tools/list" -> obj("tools" to JsonArray(listOf(obj("name" to str("extract"), "inputSchema" to obj("type" to str("object"))))))
                        "tools/call" -> obj("content" to JsonArray(listOf(obj("type" to str("text"), "text" to str(obj(
                            "success" to JsonPrimitive(true), "title" to str("Video title"),
                            "original_url" to str("https://v.douyin.com/example/"),
                            "media" to JsonArray(listOf(obj("url" to str(url), "type" to str("video"))))
                        ).toString())))))
                        else -> error("Unexpected method")
                    }
                    return json(obj("jsonrpc" to str("2.0"), "id" to body.getValue("id"), "result" to result))
                }
                rounds++
                val message = if (rounds == 1) {
                    val name = body["tools"]!!.jsonArray.first().jsonObject["function"]!!.jsonObject.text("name")
                    obj("role" to str("assistant"), "content" to str("Fetching video."),
                        "tool_calls" to JsonArray(listOf(obj("id" to str("call1"), "type" to str("function"),
                            "function" to obj("name" to str(name), "arguments" to str("{}"))))))
                } else if (rounds == 2) {
                    followup = body.toString()
                    assertFalse(body["tools"]!!.jsonArray.any { it.jsonObject["function"]!!.jsonObject.text("name") == "send_file" })
                    obj("role" to str("assistant"), "content" to str("I found a video."))
                } else {
                    obj("role" to str("assistant"), "content" to str("The video is displayed above."))
                }
                return json(obj("choices" to JsonArray(listOf(obj("message" to message)))))
            }
        }
        try {
            val provider = ModelProvider("p", "Chat", ModelApiType.OPENAI, baseUrl = server.url("/v1").toString(),
                config = ProviderConfiguration(supportStream = false))
            val preset = McpInfo("content-api", "Content API", url = server.url("/mcp").toString())
            val store = ToolFiles(root)
            val http = ToolHttp(store)
            val events = ToolChatViewModel(http, McpService(http), AiService(context)).generate(provider, "chat",
                listOf(StoredMessage("u", ChatRole.USER, listOf(MessageVersion("Get this video")))),
                "", listOf(preset), listOf(provider), { ToolSettings() }).toList()
            assertEquals(2, rounds)
            assertFalse(followup.contains(url))
            assertFalse(followup.contains("signature=private"))
            assertTrue(followup.contains("v.douyin.com/example"))
            assertTrue(followup.contains("Video title"))
            val activity = events.filterIsInstance<GenerationEvent.Tool>()
                .last { it.activity.name.endsWith("/ extract") }.activity
            assertEquals("success", activity.status)
            assertTrue(activity.inlineMedia)
            assertTrue(activity.files.any { it.endsWith(".mp4") })
            assertArrayEquals(video, store.resolve(activity.files.first { it.endsWith(".mp4") })!!.readBytes())
            assertTrue(store.list().any { it.extension == "mp4" })
            assertFalse(activity.response.text().contains(url))
        } finally { server.shutdown(); root.deleteRecursively() }
    }

    @Test fun directImageGenerationSendsOriginalPromptWithoutCallingTextModel() = runBlocking {
        val server = MockWebServer()
        server.start()
        val root = Files.createTempDirectory("direct-image-test").toFile()
        val bytes = ByteArray(100) { 42 }.also { it[0] = 0x89.toByte(); it[1] = 0x50 }
        val b64 = java.util.Base64.getEncoder().encodeToString(bytes)
        server.enqueue(MockResponse().addHeader("Content-Type", "application/json")
            .setBody("{\"data\":[{\"b64_json\":\"$b64\"}]}"))
        try {
            val provider = ModelProvider("p", "local", ModelApiType.OPENAI,
                baseUrl = server.url("/v1").toString())
            val http = ToolHttp(ToolFiles(root))
            val engine = ToolChatViewModel(http, McpService(http), AiService(context))
            val prompt = "A watercolor landscape with a blue lake"
            val events = engine.generateMedia(provider, "image-model", ModelType.IMAGE_GENERATION,
                prompt, buildJsonObject { put("quality", "high") }).toList()
            assertEquals(1, server.requestCount)
            val request = server.takeRequest()
            assertEquals("/v1/images/generations", request.path)
            val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
            assertEquals(prompt, body["prompt"]!!.jsonPrimitive.content)
            assertEquals("image-model", body["model"]!!.jsonPrimitive.content)
            assertEquals("high", body["quality"]!!.jsonPrimitive.content)
            assertTrue(events.filterIsInstance<GenerationEvent.Text>().isEmpty())
            val result = events.filterIsInstance<GenerationEvent.Tool>().last().activity
            assertEquals("success", result.status)
            assertTrue(result.files.isNotEmpty())
        } finally {
            server.shutdown()
            root.deleteRecursively()
        }
    }


    @Test fun failedImageToolIsReturnedToModelAsExplicitErrorResult() = runBlocking {
        val server = MockWebServer()
        server.start()
        val root = Files.createTempDirectory("tool-chat-error-test").toFile()
        var rounds = 0
        var followupBody = ""
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return when (request.path) {
                    "/v1/images/generations" -> MockResponse()
                        .setResponseCode(500)
                        .addHeader("Content-Type", "application/json")
                        .setBody("""{"error":{"message":"image backend unavailable"}}""")
                    "/v1/chat/completions" -> {
                        rounds++
                        if (rounds == 1) {
                            MockResponse().addHeader("Content-Type", "text/event-stream").setBody(
                                """data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call1","function":{"name":"generate_image","arguments":"{\"prompt\":\"a tree\"}"}}]}}]}

data: [DONE]

"""
                            )
                        } else {
                            followupBody = request.body.readUtf8()
                            MockResponse().addHeader("Content-Type", "text/event-stream").setBody(
                                """data: {"choices":[{"delta":{"content":"The image tool failed."}}]}

data: [DONE]

"""
                            )
                        }
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        try {
            val provider = ModelProvider(
                "p", "local", ModelApiType.OPENAI,
                baseUrl = server.url("/v1").toString(),
                config = ProviderConfiguration(
                    modelIds = listOf("chat", "image"),
                    modelConfigs = mapOf(
                        "chat" to ModelConfiguration(supportsToolCalls = true),
                        "image" to ModelConfiguration(modelType = ModelType.IMAGE_GENERATION)
                    )
                )
            )
            val files = ToolFiles(root)
            val http = ToolHttp(files)
            val events = ToolChatViewModel(http, McpService(http), AiService(context)).generate(
                provider,
                "chat",
                listOf(StoredMessage("u", ChatRole.USER, listOf(MessageVersion("Draw a tree")))),
                "",
                emptyList(),
                listOf(provider),
                {
                    ToolSettings(
                        system = mapOf(
                            "generate_image" to SystemToolConfig(true, "p", "image")
                        )
                    )
                }
            ).toList()

            assertEquals(2, rounds)
            val followup = Json.parseToJsonElement(followupBody).jsonObject
            val toolContent = followup["messages"]!!.jsonArray.last().jsonObject.text("content")
            val toolError = Json.parseToJsonElement(toolContent).jsonObject
            assertTrue(toolError["isError"]!!.jsonPrimitive.boolean)
            assertEquals("error", toolError.text("status"))
            assertTrue(toolError.text("error").contains("image backend unavailable") || toolError.text("error").contains("HTTP 500"))
            assertEquals(
                "The image tool failed.",
                events.filterIsInstance<GenerationEvent.Text>().joinToString("") { it.text }
            )
            val tool = events.filterIsInstance<GenerationEvent.Tool>().last().activity
            assertEquals("error", tool.status)
            assertTrue(tool.response.text().contains("\"isError\":true"))
        } finally {
            server.shutdown()
            root.deleteRecursively()
        }
    }

    @Test fun modelCallsImageToolAndResumesWithStreamedAnswerWithoutBase64InChat() = runBlocking {
        val server=MockWebServer();server.start()
        val root=Files.createTempDirectory("tool-chat-test").toFile()
        var rounds=0
        var mediaCalls=0
        var imageRequest=""
        val bytes=ByteArray(24000){42};bytes[0]=0x89.toByte();bytes[1]=0x50
        val b64=java.util.Base64.getEncoder().encodeToString(bytes)
        server.dispatcher=object:Dispatcher(){
            override fun dispatch(request:RecordedRequest):MockResponse {
                return if(request.path=="/v1/images/generations") {
                    mediaCalls++
                    imageRequest=request.body.readUtf8()
                    MockResponse().addHeader("Content-Type","application/json").setBody("{\"data\":[{\"b64_json\":\"$b64\"}]}")
                } else if(request.path=="/v1/chat/completions") {
                    rounds++
                    if(rounds==1) MockResponse().addHeader("Content-Type","text/event-stream").setBody(
                        "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call1\",\"function\":{\"name\":\"generate_image\",\"arguments\":\"{\\\"prompt\\\":\\\"a tree\\\"}\"}}]}}]}\n\ndata: [DONE]\n\n")
                    else {
                        val body=request.body.readUtf8()
                        assertTrue(body.contains("tool_call_id"));assertFalse(body.contains(b64))
                        assertFalse(body.contains("\"name\":\"send_file\""))
                        MockResponse().addHeader("Content-Type","text/event-stream").setBody("data: {\"choices\":[{\"delta\":{\"content\":\"Image ready.\"}}]}\n\ndata: [DONE]\n\n")
                    }
                } else MockResponse().setResponseCode(404)
            }
        }
        try {
            val provider=ModelProvider("p","local",ModelApiType.OPENAI,baseUrl=server.url("/v1").toString(),config=ProviderConfiguration(
                modelIds=listOf("chat","image"),modelConfigs=mapOf("chat" to ModelConfiguration(supportsToolCalls=true),"image" to ModelConfiguration(modelType=ModelType.IMAGE_GENERATION))))
            val files=ToolFiles(root);val http=ToolHttp(files)
            val engine=ToolChatViewModel(http,McpService(http),AiService(context))
            val events=engine.generate(provider,"chat",listOf(StoredMessage("u",ChatRole.USER,listOf(MessageVersion("Draw a tree")))),"",emptyList(),listOf(provider),{ToolSettings(system=mapOf("generate_image" to SystemToolConfig(true,"p","image").withImageOptions(buildJsonObject { put("quality","high"); put("output_format","webp") })))}).toList()
            assertEquals(1,mediaCalls);assertEquals(2,rounds)
            val imageBody=Json.parseToJsonElement(imageRequest).jsonObject
            assertEquals("high",imageBody["quality"]!!.jsonPrimitive.content)
            assertEquals("webp",imageBody["output_format"]!!.jsonPrimitive.content)
            assertEquals("Image ready.",events.filterIsInstance<GenerationEvent.Text>().joinToString(""){it.text})
            val tool=events.filterIsInstance<GenerationEvent.Tool>().last().activity
            assertEquals("success",tool.status);assertTrue(tool.files.any{it.endsWith(".png")})
            assertFalse(events.toString().contains(b64))
            assertArrayEquals(bytes,files.list().first{it.extension=="png"}.readBytes())
        } finally {server.shutdown();root.deleteRecursively()}
    }
}
