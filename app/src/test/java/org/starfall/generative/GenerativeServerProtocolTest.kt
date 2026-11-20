package org.starfall.generative

import org.junit.Assert.*
import org.junit.Test
import org.starfall.generative.integrations.generativeserver.*

class GenerativeServerProtocolTest {
    @Test fun requestAndReplyPreserveWireNames() {
        val request = GenerativeServerCodec.encode(RpcRequest("one", "session.get", "chat"))
        assertTrue(request.contains("\"session_id\":\"chat\""))
        val reply = GenerativeServerCodec.decodeReply("""{"id":"one","ok":true,"data":{"revision":3},"future":1}""", "one")
        assertNotNull(GenerativeServerCodec.requireSuccess(reply))
    }
    @Test fun rejectsWrongIdAndMalformedErrors() {
        for (payload in listOf("""{"id":"other","ok":true}""", """{"id":"one","ok":false}""", "not json")) {
            try { GenerativeServerCodec.decodeReply(payload, "one"); fail("accepted $payload") }
            catch (_: GenerativeServerProtocolException) { }
        }
    }
    @Test fun surfacesServerErrors() {
        val reply = GenerativeServerCodec.decodeReply("""{"id":"one","ok":false,"error":{"code":"revision_conflict","message":"Conflict","retryable":false}}""", "one")
        try { GenerativeServerCodec.requireSuccess(reply); fail("accepted error") }
        catch (error: GenerativeServerRpcException) { assertEquals("revision_conflict", error.rpcError.code) }
    }
    @Test fun invitationRequiresHttpsPinAndKeepsCodeOutOfString() {
        val pin = "sha256/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
        val encodedPin = java.net.URLEncoder.encode(pin, "UTF-8")
        val invitation = GenerativeServerInvitation.parse("https://example.com:443/connect#code=secret&pin=$encodedPin&v=1")
        assertEquals(pin, invitation.pin)
        assertFalse(invitation.toString().contains("secret"))
        for (url in listOf(
            "http://example.com/connect#code=secret&pin=$encodedPin&v=1",
            "https://example.com/connect?code=secret#code=secret&pin=$encodedPin&v=1",
            "https://example.com/connect#code=secret&pin=$encodedPin&v=1&v=1",
            "https://example.com/connect#code=secret&pin=sha256%2Fbad&v=1",
        )) {
            try { GenerativeServerInvitation.parse(url); fail("accepted invalid invitation") }
            catch (_: IllegalArgumentException) { }
        }
    }
}
