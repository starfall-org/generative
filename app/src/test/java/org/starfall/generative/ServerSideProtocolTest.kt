package org.starfall.generative

import org.junit.Assert.*
import org.junit.Test
import org.starfall.generative.integrations.serverside.*

class ServerSideProtocolTest {
    @Test fun requestAndReplyPreserveWireNames() {
        val request = ServerSideCodec.encode(RpcRequest("one", "session.get", "chat"))
        assertTrue(request.contains("\"session_id\":\"chat\""))
        val reply = ServerSideCodec.decodeReply("""{"id":"one","ok":true,"data":{"revision":3},"future":1}""", "one")
        assertNotNull(ServerSideCodec.requireSuccess(reply))
    }
    @Test fun rejectsWrongIdAndMalformedErrors() {
        for (payload in listOf("""{"id":"other","ok":true}""", """{"id":"one","ok":false}""", "not json")) {
            try { ServerSideCodec.decodeReply(payload, "one"); fail("accepted $payload") }
            catch (_: ServerSideProtocolException) { }
        }
    }
    @Test fun surfacesServerErrors() {
        val reply = ServerSideCodec.decodeReply("""{"id":"one","ok":false,"error":{"code":"revision_conflict","message":"Conflict","retryable":false}}""", "one")
        try { ServerSideCodec.requireSuccess(reply); fail("accepted error") }
        catch (error: ServerSideRpcException) { assertEquals("revision_conflict", error.rpcError.code) }
    }
    @Test fun invitationRequiresHttpsPinAndKeepsCodeOutOfString() {
        val pin = "sha256/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
        val encodedPin = java.net.URLEncoder.encode(pin, "UTF-8")
        val invitation = ServerSideInvitation.parse("https://example.com:443/connect#code=secret&pin=$encodedPin&v=1")
        assertEquals(pin, invitation.pin)
        assertFalse(invitation.toString().contains("secret"))
        for (url in listOf(
            "http://example.com/connect#code=secret&pin=$encodedPin&v=1",
            "https://example.com/connect?code=secret#code=secret&pin=$encodedPin&v=1",
            "https://example.com/connect#code=secret&pin=$encodedPin&v=1&v=1",
            "https://example.com/connect#code=secret&pin=sha256%2Fbad&v=1",
        )) {
            try { ServerSideInvitation.parse(url); fail("accepted invalid invitation") }
            catch (_: IllegalArgumentException) { }
        }
    }
}
