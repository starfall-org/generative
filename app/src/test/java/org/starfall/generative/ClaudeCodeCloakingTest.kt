package org.starfall.generative

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.starfall.generative.features.home.shared.logic.ToolRequestService
import org.starfall.generative.integrations.modelapis.AiService
import org.starfall.generative.integrations.modelapis.account.AccountTokenState
import org.starfall.generative.integrations.modelapis.account.AccountTokenStore
import org.starfall.generative.integrations.modelapis.anthropic.CLAUDE_CODE_IDENTITY
import org.starfall.generative.integrations.modelapis.anthropic.CLAUDE_CODE_USER_AGENT
import org.starfall.generative.integrations.modelapis.anthropic.claudeCodeSystemReminder
import org.starfall.generative.integrations.modelapis.claudecode.ClaudeCodeAdapter
import org.starfall.generative.shared.subfeatures.media.logic.AttachmentResolver
import org.starfall.generative.shared.model.ToolDefinition
import org.starfall.generative.shared.model.api.ModelApiType
import org.starfall.generative.shared.model.auth.AuthMethod
import org.starfall.generative.shared.model.auth.Authorization
import org.starfall.generative.shared.model.provider.ModelProvider
import org.starfall.generative.shared.model.provider.ProviderConfiguration
import org.starfall.generative.shared.helpers.networking.*

@Config(sdk = [28])
@RunWith(RobolectricTestRunner::class)
class ClaudeCodeCloakingTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val adapter get() = ClaudeCodeAdapter(context, AttachmentResolver(context))
    private val source = ModelProvider("claude", "Claude", ModelApiType.CLAUDE_CODE,
        baseUrl = ModelApiType.CLAUDE_CODE.defaultBaseUrl)

    @Before fun prepareKeystore() = installTestAndroidKeyStore()

    @Test fun requestLocalAliasesCoverDeclarationsHistoryChoiceAndResponses() {
        val mcp = "mcp__existing__lookup"
        val body = obj(
            "model" to str("claude-sonnet-5"),
            "system" to str("User instructions"),
            "messages" to JsonArray(listOf(
                obj("role" to str("user"), "content" to JsonArray(listOf(obj("type" to str("text"), "text" to str("hi"))))),
                obj("role" to str("assistant"), "content" to JsonArray(listOf(
                    obj("type" to str("tool_use"), "id" to str("old"), "name" to str("lookup"), "input" to obj())
                ))),
                obj("role" to str("user"), "content" to JsonArray(listOf(
                    obj("type" to str("tool_result"), "tool_use_id" to str("old"), "content" to JsonArray(listOf(
                        obj("type" to str("tool_reference"), "tool_name" to str("lookup"))
                    )))
                )))
            )),
            "tools" to JsonArray(listOf(
                obj("name" to str("lookup"), "description" to str("Look up"), "input_schema" to obj()),
                obj("name" to str(mcp), "description" to str("Existing MCP"), "input_schema" to obj()),
                obj("type" to str("web_search_20250305"), "name" to str("web_search"))
            )),
            "tool_choice" to obj("type" to str("tool"), "name" to str("lookup"))
        )
        val result = adapter.normalizeToolRequest(source, source, body, "User instructions")
        val topSystem = result.getValue("system").jsonArray
        assertEquals(1, topSystem.size)
        assertEquals(CLAUDE_CODE_IDENTITY, topSystem.single().jsonObject.text("text"))
        assertFalse(topSystem.toString().contains("User instructions"))

        val tools = result.getValue("tools").jsonArray.map { it.jsonObject }
        val alias = tools[0].text("name")
        assertTrue(alias.startsWith("mcp__"))
        assertTrue(alias.length <= 64)
        assertNotEquals("lookup", alias)
        assertEquals(mcp, tools[1].text("name"))
        assertEquals("web_search", tools[2].text("name"))
        assertEquals("web_search_20250305", tools[2].text("type"))
        assertEquals(alias, result.getValue("tool_choice").jsonObject.text("name"))

        val messages = result.getValue("messages").jsonArray.map { it.jsonObject }
        assertEquals(listOf("user", "system", "assistant", "user"), messages.map { it.text("role") })
        assertEquals("User instructions", messages[1].getValue("content").jsonArray.single().jsonObject.text("text"))
        assertEquals(alias, messages[2].getValue("content").jsonArray[0].jsonObject.text("name"))
        val reference = messages[3].getValue("content").jsonArray[0].jsonObject
            .getValue("content").jsonArray[0].jsonObject
        assertEquals(alias, reference.text("tool_name"))

        val response = obj("content" to JsonArray(listOf(
            obj("type" to str("tool_use"), "id" to str("new"), "name" to str(alias), "input" to obj()),
            obj("type" to str("tool_reference"), "tool_name" to str(alias))
        )))
        val restored = adapter.restoreToolResponse(source, source, body, response).getValue("content").jsonArray
        assertEquals("lookup", restored[0].jsonObject.text("name"))
        assertEquals("lookup", restored[1].jsonObject.text("tool_name"))
        assertEquals(result, adapter.normalizeToolRequest(source, source, body, "User instructions"))
    }

    @Test fun legacySystemAndBetaProfilesFollowTheReferenceRules() {
        val legacyBody = obj(
            "model" to str("claude-sonnet-4-5"),
            "system" to str("Legacy instructions"),
            "messages" to JsonArray(listOf(obj("role" to str("user"), "content" to JsonArray(listOf(
                obj("type" to str("text"), "text" to str("hello"))
            )))))
        )
        val legacy = adapter.normalizeToolRequest(source, source, legacyBody, "Legacy instructions")
        val user = legacy.getValue("messages").jsonArray.single().jsonObject
        assertEquals(claudeCodeSystemReminder("Legacy instructions"),
            user.getValue("content").jsonArray[0].jsonObject.text("text"))
        assertTrue(legacy.getValue("messages").jsonArray.none { it.jsonObject.text("role") == "system" })

        val wire = source.copy(type = ModelApiType.ANTHROPIC)
        fun beta(body: JsonObject) = adapter.prepareRequestProvider(source, wire, body)
            .config.headers.getValue("anthropic-beta").split(',')
        val modern = beta(obj("model" to str("claude-sonnet-5")))
        assertTrue("mid-conversation-system-2026-04-07" in modern)
        assertFalse("mid-conversation-tool-changes-2026-07-01" in modern)
        assertTrue("effort-2025-11-24" in modern)
        val old = beta(legacyBody)
        assertFalse("mid-conversation-system-2026-04-07" in old)
        assertFalse("mid-conversation-tool-changes-2026-07-01" in old)
        val haiku = beta(obj("model" to str("claude-haiku-4-5")))
        assertFalse("effort-2025-11-24" in haiku)
        val display = beta(obj("model" to str("claude-opus-4-6"), "thinking" to obj(
            "type" to str("adaptive"), "display" to str("summarized"))))
        assertFalse("redact-thinking-2026-02-12" in display)
    }

    @Test fun advisorHistoryKeepsCallerSystemOutOfMessageIndices() {
        val body = obj(
            "model" to str("claude-sonnet-5"),
            "system" to str("Do not shift advisor history"),
            "messages" to JsonArray(listOf(
                obj("role" to str("assistant"), "content" to JsonArray(listOf(
                    obj("type" to str("server_tool_use"), "name" to str("advisor"), "input" to obj())
                ))),
                obj("role" to str("user"), "content" to JsonArray(listOf(
                    obj("type" to str("advisor_tool_result"), "content" to str("encrypted"))
                )))
            ))
        )
        val result = adapter.normalizeToolRequest(source, source, body, "Do not shift advisor history")
        assertEquals(body["messages"], result["messages"])
        val system = result.getValue("system").jsonArray
        assertEquals(listOf(CLAUDE_CODE_IDENTITY, "Do not shift advisor history"),
            system.map { it.jsonObject.text("text") })
    }

    @Test(timeout = 60000) fun toolCallsRestoreOriginalNamesForJsonAndSse() = runBlocking {
        for (stream in listOf(false, true)) {
            MockWebServer().use { server ->
                val id = "claude-roundtrip-$stream"
                AccountTokenStore(context, "claude_code").save(id,
                    AccountTokenState("test-access", "test-refresh"))
                server.dispatcher = object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse {
                        val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
                        val alias = body.getValue("tools").jsonArray[0].jsonObject.text("name")
                        val tool = obj("type" to str("tool_use"), "id" to str("new"),
                            "name" to str(alias), "input" to obj("query" to str("test")))
                        if (!stream) return MockResponse().setHeader("Content-Type", "application/json")
                            .setBody(obj("id" to str("msg"), "type" to str("message"), "role" to str("assistant"),
                                "model" to str("claude-sonnet-5"), "content" to JsonArray(listOf(tool)),
                                "stop_reason" to str("tool_use"), "usage" to obj("input_tokens" to JsonPrimitive(1),
                                    "output_tokens" to JsonPrimitive(1))).toString())
                        val events = listOf(
                            obj("type" to str("message_start"), "message" to obj(
                                "id" to str("msg"), "type" to str("message"), "role" to str("assistant"),
                                "model" to str("claude-sonnet-5"), "content" to JsonArray(emptyList()),
                                "stop_reason" to JsonNull, "stop_sequence" to JsonNull,
                                "usage" to obj("input_tokens" to JsonPrimitive(1), "output_tokens" to JsonPrimitive(0)))),
                            obj("type" to str("content_block_start"), "index" to JsonPrimitive(0), "content_block" to tool),
                            obj("type" to str("content_block_delta"), "index" to JsonPrimitive(0), "delta" to obj(
                                "type" to str("input_json_delta"), "partial_json" to str("{\"query\":\"test\"}"))),
                            obj("type" to str("content_block_stop"), "index" to JsonPrimitive(0)),
                            obj("type" to str("message_delta"), "delta" to obj("stop_reason" to str("tool_use")),
                                "usage" to obj("output_tokens" to JsonPrimitive(1))),
                            obj("type" to str("message_stop"))
                        )
                        return MockResponse().setHeader("Content-Type", "text/event-stream")
                            .setBody(events.joinToString("") { "data: $it\n\n" })
                    }
                }
                val provider = ModelProvider(id, "Claude", ModelApiType.CLAUDE_CODE,
                    auth = Authorization(AuthMethod.OAUTH, value = "account:claude_code:v1"),
                    baseUrl = server.url("/v1").toString(), config = ProviderConfiguration(supportStream = stream))
                val history = listOf(
                    obj("role" to str("user"), "content" to str("hi")),
                    obj("role" to str("assistant"), "content" to str(""), "tool_calls" to JsonArray(listOf(
                        obj("id" to str("old"), "type" to str("function"), "function" to obj(
                            "name" to str("lookup"), "arguments" to str("{}")))
                    ))),
                    obj("role" to str("tool"), "tool_call_id" to str("old"), "content" to str("result")),
                    obj("role" to str("user"), "content" to str("again"))
                )
                val turn = ToolRequestService(ToolHttp(), AiService(context)).request(provider, "claude-sonnet-5",
                    history, listOf(ToolDefinition("lookup", "Look up", obj("type" to str("object")))),
                    "Instructions", false, onText = {}, onReasoning = {})
                val call = turn.getValue("tool_calls").jsonArray.single().jsonObject
                assertEquals("lookup", call.getValue("function").jsonObject.text("name"))
                assertEquals("test", call.getValue("function").jsonObject.getValue("arguments").jsonObject.text("query"))

                val recorded = server.takeRequest()
                val request = Json.parseToJsonElement(recorded.body.readUtf8()).jsonObject
                val alias = request.getValue("tools").jsonArray[0].jsonObject.text("name")
                assertTrue(alias.startsWith("mcp__"))
                assertNotEquals("lookup", alias)
                assertEquals(CLAUDE_CODE_USER_AGENT, recorded.getHeader("User-Agent"))
                assertEquals(CLAUDE_CODE_IDENTITY, request.getValue("system").jsonArray.single().jsonObject.text("text"))
                val messages = request.getValue("messages").jsonArray.map { it.jsonObject }
                assertEquals(listOf("user", "system", "assistant", "user"), messages.map { it.text("role") })
                assertEquals(alias, messages[2].getValue("content").jsonArray[0].jsonObject.text("name"))
                val betas = recorded.getHeader("anthropic-beta").orEmpty().split(',')
                assertTrue("mid-conversation-system-2026-04-07" in betas)
                assertFalse("mid-conversation-tool-changes-2026-07-01" in betas)
            }
        }
    }
}
