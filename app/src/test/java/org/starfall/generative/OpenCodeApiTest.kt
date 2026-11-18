package org.starfall.generative

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import okhttp3.Credentials
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import org.starfall.generative.integrations.opencode.OpenCodeApi
import org.starfall.generative.integrations.opencode.OpenCodeCredentials
import org.starfall.generative.integrations.opencode.OpenCodeProtocolException

class OpenCodeApiTest {
    private fun MockWebServer.api() = OpenCodeApi(url("/").toString(), OpenCodeCredentials("opencode", "secret"))

    @Test fun inventoriesUseAuthenticatedServerEndpoints() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"location":{"directory":"/work"},"data":[{"id":"p","name":"Provider","activation":"auto","package":"pkg"}]}"""))
            server.enqueue(MockResponse().setBody("""{"location":{"directory":"/work"},"data":[{"name":"tools","status":{"status":"connected"}}]}"""))
            val api = server.api()
            assertEquals("Provider", api.providers().single()["name"]?.toString()?.trim('"'))
            assertEquals("tools", api.mcpServers().single()["name"]?.toString()?.trim('"'))
            for (path in listOf("/api/provider", "/api/mcp")) {
                val request = server.takeRequest()
                assertEquals(path, request.path)
                assertEquals(Credentials.basic("opencode", "secret", Charsets.UTF_8), request.getHeader("Authorization"))
            }
        }
    }

    @Test fun mcpOperationsAreBoundToLocationAndAreNotRetried() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"location":{"directory":"/work one"},"data":[]}"""))
            server.enqueue(MockResponse().setResponseCode(204))
            server.enqueue(MockResponse().setResponseCode(503))
            val api = server.api()
            assertTrue(api.mcpServers("/work one").isEmpty())
            api.setMcpConnected("tools", true, "/work one")
            try { api.setMcpConnected("tools", false, "/other"); fail("Expected HTTP error") }
            catch (_: org.starfall.generative.integrations.opencode.OpenCodeHttpException) { }
            val list = server.takeRequest()
            assertEquals("/api/mcp", list.requestUrl?.encodedPath)
            assertEquals("/work one", list.requestUrl?.queryParameter("location[directory]"))
            val connect = server.takeRequest()
            assertEquals("POST", connect.method)
            assertEquals("/api/experimental/mcp/tools/connect", connect.requestUrl?.encodedPath)
            assertEquals("/work one", connect.requestUrl?.queryParameter("location[directory]"))
            val disconnect = server.takeRequest()
            assertEquals("/api/experimental/mcp/tools/disconnect", disconnect.requestUrl?.encodedPath)
            assertEquals("/other", disconnect.requestUrl?.queryParameter("location[directory]"))
            assertEquals(3, server.requestCount)
        }
    }

    @Test fun runtimeMcpAddAndRemoveUseSelectedLocationAndExactConfig() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(204))
            server.enqueue(MockResponse().setResponseCode(204))
            val config = Json.parseToJsonElement("""{"type":"local","command":["node","server.js"],"environment":{"TOKEN":"secret"}}""").jsonObject
            server.api().addMcp("custom", config, "/work/project")
            server.api().removeMcp("custom", "/work/project")
            val add = server.takeRequest()
            assertEquals("PUT", add.method)
            assertEquals("/api/experimental/mcp/custom", add.requestUrl?.encodedPath)
            assertEquals("/work/project", add.requestUrl?.queryParameter("location[directory]"))
            assertEquals(config, Json.parseToJsonElement(add.body.readUtf8()).jsonObject["config"])
            val remove = server.takeRequest()
            assertEquals("DELETE", remove.method)
            assertEquals("/api/experimental/mcp/custom", remove.requestUrl?.encodedPath)
            assertEquals("/work/project", remove.requestUrl?.queryParameter("location[directory]"))
        }
    }

    @Test fun integrationKeyAndOAuthUseServerProjectAndExactPayloads() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"data":{"id":"p","name":"Provider","methods":[]}}"""))
            server.enqueue(MockResponse().setResponseCode(204))
            server.enqueue(MockResponse().setBody("""{"data":{"attemptID":"attempt","mode":"code","url":"https://example.com","instructions":"Enter code"}}"""))
            server.enqueue(MockResponse().setBody("""{"data":{"status":"pending"}}"""))
            server.enqueue(MockResponse().setResponseCode(204))
            server.enqueue(MockResponse().setBody("""{"data":{"status":"complete"}}"""))
            server.enqueue(MockResponse().setResponseCode(204))
            val api = server.api()
            val answer = Json.parseToJsonElement("""{"region":"us"}""").jsonObject
            assertEquals("p", api.integration("p", "/work")["id"]?.toString()?.trim('"'))
            api.connectIntegrationKey("p", "key-secret", answer, "/work")
            api.beginIntegrationOAuth("p", "browser", answer, "/work")
            api.integrationOAuthStatus("p", "attempt", "/work")
            api.completeIntegrationOAuth("p", "attempt", "code", "/work")
            assertEquals("\"complete\"", api.integrationOAuthStatus("p", "attempt", "/work")["status"].toString())
            api.cancelIntegrationOAuth("p", "attempt", "/work")
            val expected = listOf(
                Triple("GET", "/api/integration/p", null),
                Triple("POST", "/api/integration/p/connect/key", """{"key":"key-secret","answer":{"region":"us"}}"""),
                Triple("POST", "/api/integration/p/connect/oauth", """{"methodID":"browser","answer":{"region":"us"}}"""),
                Triple("GET", "/api/integration/p/connect/oauth/attempt", null),
                Triple("POST", "/api/integration/p/connect/oauth/attempt/complete", """{"code":"code"}"""),
                Triple("GET", "/api/integration/p/connect/oauth/attempt", null),
                Triple("DELETE", "/api/integration/p/connect/oauth/attempt", null)
            )
            expected.forEach { (method, path, body) ->
                val request = server.takeRequest()
                assertEquals(method, request.method)
                assertEquals(path, request.requestUrl?.encodedPath)
                assertEquals("/work", request.requestUrl?.queryParameter("location[directory]"))
                assertEquals(Credentials.basic("opencode", "secret", Charsets.UTF_8), request.getHeader("Authorization"))
                if (body != null) assertEquals(Json.parseToJsonElement(body), Json.parseToJsonElement(request.body.readUtf8()))
            }
        }
    }

    @Test fun integrationAuthErrorsAreNotRetried() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(401))
            try { server.api().connectIntegrationKey("p", "secret", directory = "/work"); fail("Expected HTTP error") }
            catch (e: org.starfall.generative.integrations.opencode.OpenCodeHttpException) { assertEquals(401, e.status) }
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun projectListingAndDirectoryBrowsingUseOpenCodeApi() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""[{"id":"p1","name":"Project","canonical":"/work/project"}]"""))
            server.enqueue(MockResponse().setBody("""{"data":[{"path":"project","type":"directory"},{"path":"readme","type":"file"}]}"""))
            val api = server.api()
            assertEquals("/work/project", api.projects().single().directory)
            assertEquals(listOf("project"), api.directories("/work"))
            assertEquals("/api/project", server.takeRequest().path)
            val list = server.takeRequest()
            assertEquals("/work", list.requestUrl?.queryParameter("path"))
            assertEquals(Credentials.basic("opencode", "secret", Charsets.UTF_8), list.getHeader("Authorization"))
        }
    }

    @Test fun sessionsFollowCursorsAndEncodePromptAndModel() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"data":[{"id":"ses_a"}],"cursor":{"next":"page2"}}"""))
            server.enqueue(MockResponse().setBody("""{"data":[{"id":"ses_b"}],"cursor":{}}"""))
            server.enqueue(MockResponse().setResponseCode(204))
            server.enqueue(MockResponse().setBody("""{"data":{"id":"msg_a"}}"""))
            val api = server.api()
            assertEquals(2, api.sessions("/work/project").size)
            api.setModel("ses_a", "provider", "model")
            api.prompt("ses_a", "Hello")
            val first = server.takeRequest()
            assertEquals("/work/project", first.requestUrl?.queryParameter("directory"))
            assertEquals("page2", server.takeRequest().requestUrl?.queryParameter("cursor"))
            val model = server.takeRequest()
            assertEquals("/api/session/ses_a/model", model.path)
            assertEquals(Json.parseToJsonElement("""{"model":{"providerID":"provider","id":"model"}}"""), Json.parseToJsonElement(model.body.readUtf8()))
            val prompt = server.takeRequest()
            assertEquals("/api/session/ses_a/prompt", prompt.path)
            assertEquals("""{"text":"Hello"}""", prompt.body.readUtf8())
        }
    }

    @Test fun repeatedCursorsAreRejected() = runBlocking {
        MockWebServer().use { server ->
            repeat(2) { server.enqueue(MockResponse().setBody("""{"data":[{"id":"ses_a"}],"cursor":{"next":"same"}}""")) }
            try { server.api().sessions("/work"); fail("Expected a cursor error") }
            catch (_: OpenCodeProtocolException) { }
        }
    }

    @Test fun eventsParseSseMessages() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream")
                .setBody("data: {\"type\":\"session.text.delta\",\"data\":{}}\n\n"))
            val event = server.api().events().first()
            assertEquals(Json.parseToJsonElement("""{"type":"session.text.delta","data":{}}""").jsonObject, event)
            assertEquals("text/event-stream", server.takeRequest().getHeader("Accept"))
        }
    }
    @Test fun eventEndpointRejectsHtmlFromAProxy() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("Content-Type", "text/html").setBody("<html>login</html>"))
            try { server.api().events().first(); fail("Expected invalid event stream") }
            catch (_: OpenCodeProtocolException) { }
        }
    }

}
