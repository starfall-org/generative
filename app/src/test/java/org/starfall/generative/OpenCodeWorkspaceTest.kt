package org.starfall.generative

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.starfall.generative.features.home.chat.logic.remote.RemoteActionFailure
import org.starfall.generative.features.home.chat.logic.remote.RemoteWorkspace
import org.starfall.generative.integrations.opencode.OpenCodeApi
import org.starfall.generative.integrations.opencode.OpenCodeCredentials

class OpenCodeWorkspaceTest {
    @Test fun sendsAndRestoresTextChatThroughTheSelectedOpenCodeConnection() = runBlocking {
        MockWebServer().use { server ->
            val session = """{"id":"ses_a","title":"Chat","time":{"created":1,"updated":2},"model":{"providerID":"p","id":"m"}}"""
            val history = """{"data":[
                {"id":"older","type":"idle","outcome":"failed"},
                {"id":"msg_a","type":"user","text":"Hello"},
                {"id":"msg_b","type":"assistant","model":{"providerID":"p","id":"m"},"content":[
                    {"type":"reasoning","text":"Check the input"},
                    {"type":"tool","id":"tool_a","name":"lookup","state":{"status":"completed","input":{"key":"a"},"content":[{"type":"text","text":"found"}]}},
                    {"type":"text","text":"Hi"}
                ]},
                {"id":"idle","type":"idle","outcome":"succeeded"}
            ],"cursor":{}}"""
            fun enqueue(body: String) = server.enqueue(MockResponse().setBody(body))
            fun catalog(sessions: String) {
                enqueue("""{"directory":"/work"}""")
                enqueue("""{"data":[{"id":"p","name":"Provider"}]}""")
                enqueue("""{"data":[{"id":"m","providerID":"p","name":"Model","type":"chat","enabled":true},
                    {"id":"off","providerID":"p","name":"Disabled","enabled":false}]}""")
                enqueue("""{"data":$sessions,"cursor":{}}""")
            }
            catalog("[]")
            enqueue("""{"data":$session}""")
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(listOf(
                """{"type":"server.connected","data":{}}""",
                """{"type":"session.inbox.delivered","data":{"sessionID":"ses_a","inboxID":"msg_a"}}""",
                """{"type":"session.text.delta","data":{"sessionID":"other","assistantMessageID":"irrelevant","ordinal":0,"delta":"Wrong session"}}""",
                """{"type":"session.reasoning.delta","data":{"sessionID":"ses_a","assistantMessageID":"msg_b","ordinal":0,"delta":"Check the input"}}""",
                """{"type":"session.tool.input.started","data":{"sessionID":"ses_a","assistantMessageID":"msg_b","id":"tool_a","name":"lookup"}}""",
                """{"type":"session.tool.called","data":{"sessionID":"ses_a","assistantMessageID":"msg_b","id":"tool_a","input":{"key":"a"}}}""",
                """{"type":"session.tool.success","data":{"sessionID":"ses_a","assistantMessageID":"msg_b","id":"tool_a","content":[{"type":"text","text":"found"}]}}""",
                """{"type":"session.text.delta","data":{"sessionID":"ses_a","assistantMessageID":"msg_b","ordinal":0,"delta":"H"}}"""
            ).joinToString("") { "data: $it\n\n" }))
            enqueue("""{"data":{"id":"msg_a"}}""")
            server.enqueue(MockResponse().setBody("""{"data":[],"cursor":{}}""")
                .setBodyDelay(200, java.util.concurrent.TimeUnit.MILLISECONDS))
            enqueue(history)
            enqueue("""{"data":$session}""")
            catalog("[$session]")
            enqueue("""{"data":$session}""")
            enqueue(history)

            var lastSession: String? = null
            val workspace = RemoteWorkspace(
                sessionFactory = { error("OpenCode must use its own HTTP API") },
                scope = this,
                loadLastSession = { lastSession },
                saveLastSession = { _, sessionId -> lastSession = sessionId },
                openCodeFactory = { OpenCodeApi(server.url("/").toString(), OpenCodeCredentials("opencode", "secret")) }
            )
            workspace.enable("connection")
            val connected = withTimeout(5_000) { workspace.state.first { !it.busy } }
            assertTrue(connected.openCode)
            assertFalse(connected.error)
            assertEquals(listOf("m"), connected.providers.single().config.modelIds)
            workspace.setLocalProviders("connection", emptyList())
            assertEquals(connected.providers, workspace.state.value.providers)
            assertTrue(workspace.send("Hello", emptyList()))
            val submitted = withTimeout(5_000) {
                workspace.state.first { it.running && it.conversation?.messages?.firstOrNull()?.id == "msg_a" }
            }
            assertEquals("ses_a", submitted.items.single().id)
            assertTrue(submitted.items.single().hasMessages)
            assertEquals("Hello", submitted.conversation?.messages?.first()?.content)
            val streaming = withTimeout(5_000) {
                workspace.state.first { it.running && it.conversation?.messages?.lastOrNull()?.content == "H" }
            }
            assertEquals(listOf("msg_a", "msg_b"), streaming.conversation?.messages?.map { it.id })
            val live = requireNotNull(streaming.conversation).messages.last().activeVersion
            assertEquals("Check the input", live.reasoningContent)
            assertEquals("lookup", live.toolActivity.single().name)
            assertEquals("success", live.toolActivity.single().status)
            assertEquals("found", live.toolActivity.single().response.textBlocking())
            assertEquals("Check the input".length, live.toolActivity.single().reasoningOffset)
            val finished = withTimeout(5_000) { workspace.state.first { !it.busy } }
            assertEquals(listOf("Hello", "Hi"), finished.conversation?.messages?.map { it.content })
            val stored = requireNotNull(finished.conversation).messages.last().activeVersion
            assertEquals(live.reasoningContent, stored.reasoningContent)
            assertEquals(live.toolActivity, stored.toolActivity)
            assertEquals("ses_a", finished.items.single().id)
            assertEquals(null, finished.actionFailure)

            workspace.disable()
            workspace.enable("connection")
            val reopened = withTimeout(5_000) { workspace.state.first { !it.busy } }
            assertEquals(finished.conversation?.copy(structuralRevision = 0), reopened.conversation)

            workspace.newChat()
            withTimeout(5_000) { workspace.state.first { !it.busy && it.conversation == null } }
            server.enqueue(MockResponse().setResponseCode(503))
            assertTrue(workspace.send("New chat", emptyList()))
            val failed = withTimeout(5_000) { workspace.state.first { !it.busy } }
            assertEquals(RemoteActionFailure.CONVERSATION_CREATE_FAILED, failed.actionFailure)
            assertEquals(null, failed.conversation)
            assertEquals(listOf("ses_a"), failed.items.map { it.id })

            repeat(4) { server.takeRequest() }
            val create = server.takeRequest()
            assertEquals("POST", create.method)
            assertEquals("/api/session", create.path)
            assertEquals(Json.parseToJsonElement("""{"location":{"directory":"/work"},"model":{"providerID":"p","id":"m"}}"""),
                Json.parseToJsonElement(create.body.readUtf8()))
            assertEquals("/api/event", server.takeRequest().path)
            assertEquals("/api/session/ses_a/prompt", server.takeRequest().path)
            workspace.disable()
        }
    }
}
