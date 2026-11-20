package org.starfall.generative

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
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
                {"id":"msg_b","type":"assistant","model":{"providerID":"p","id":"m"},"content":[{"type":"text","text":"Hi"}]},
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
            server.enqueue(MockResponse().setResponseCode(204))
            enqueue("""{"data":{"id":"msg_a"}}""")
            enqueue(history)
            enqueue("""{"data":$session}""")
            enqueue(history)
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
            val finished = withTimeout(5_000) { workspace.state.first { !it.busy } }
            assertEquals(listOf("Hello", "Hi"), finished.conversation?.messages?.map { it.content })
            assertEquals("ses_a", finished.items.single().id)
            assertEquals(null, finished.actionFailure)

            workspace.disable()
            workspace.enable("connection")
            val reopened = withTimeout(5_000) { workspace.state.first { !it.busy } }
            assertEquals(finished.conversation, reopened.conversation)
            workspace.disable()
        }
    }
}
