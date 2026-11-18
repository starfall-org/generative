package org.starfall.generative

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.starfall.generative.integrations.serverside.ServerSideClient
import org.starfall.generative.integrations.serverside.ServerSideCodec
import org.starfall.generative.integrations.serverside.PairedIdentity
import org.starfall.generative.integrations.serverside.RpcRequest
import org.junit.Assert.*
import org.junit.Test
import org.starfall.generative.integrations.remote.RemoteExecStream
import org.starfall.generative.integrations.serverside.ServerSideSshTransport

class ServerSideSshTransportTest {
    @Test fun frameRoundTripAndSizeValidation() {
        val payload = """{"id":"request","op":"session.list","data":{"limit":1}}"""
        val buffer = ByteArrayOutputStream()
        ServerSideSshTransport.writeFrame(buffer, payload)
        assertEquals(payload, ServerSideSshTransport.readFrame(ByteArrayInputStream(buffer.toByteArray())))
        val invalid = ByteArrayInputStream(byteArrayOf(0, 64, 0, 1))
        assertThrows(java.io.IOException::class.java) { ServerSideSshTransport.readFrame(invalid) }
    }

    @Test fun sshStdioPairingParsesTheExistingServerContract() = runBlocking {
        var released = 0
        val payload = """{"protocol":5,"client_id":"owner","secret":"secret","fingerprint":"server","endpoint":"tcp://server","transport":"tcp"}"""
        val paired = ServerSideSshTransport.pairUsing { command ->
            assertEquals("serverside pair --stdio --transport tcp", command)
            RemoteExecStream(ByteArrayInputStream(payload.toByteArray()), ByteArrayOutputStream()) { released++ }
        }
        assertEquals("owner", paired.identity.clientId)
        assertEquals("server", paired.identity.serverId)
        assertEquals(1, released)
    }

    @Test fun sshStdioRpcSkipsPushEventsAndRevokesOnlyAfterConfirmation() = runBlocking {
        var releases = 0
        val clientIn = PipedInputStream(8192)
        val serverOut = PipedOutputStream(clientIn)
        val serverIn = PipedInputStream(8192)
        val clientOut = PipedOutputStream(serverIn)
        val stream = RemoteExecStream(clientIn, clientOut) {
            releases++; clientIn.close(); clientOut.close(); serverOut.close(); serverIn.close()
        }
        val daemon = async(Dispatchers.IO) {
            val auth = ServerSideCodec.json.parseToJsonElement(ServerSideSshTransport.readFrame(serverIn)).jsonObject
            assertEquals("auth", auth.getValue("type").jsonPrimitive.content)
            assertEquals("owner", auth.getValue("client_id").jsonPrimitive.content)
            ServerSideSshTransport.writeFrame(serverOut, """{"type":"paired","protocol":5,"client_id":"owner"}""")
            val rpc = ServerSideCodec.json.parseToJsonElement(ServerSideSshTransport.readFrame(serverIn)).jsonObject
            assertEquals("capabilities.get", rpc.getValue("op").jsonPrimitive.content)
            ServerSideSshTransport.writeFrame(serverOut, """{"session_id":"chat","seq":1,"request_id":"old","kind":"delta","data":{"text":"old"}}""")
            ServerSideSshTransport.writeFrame(serverOut, """{"id":"check","ok":true,"data":{"version":1}}""")
            val revoke = ServerSideCodec.json.parseToJsonElement(ServerSideSshTransport.readFrame(serverIn)).jsonObject
            assertEquals("identity.revoke", revoke.getValue("op").jsonPrimitive.content)
            val id = revoke.getValue("id").jsonPrimitive.content
            ServerSideSshTransport.writeFrame(serverOut, """{"id":"$id","ok":true,"data":{"revoked":true}}""")
        }
        val identity = PairedIdentity("owner", "secret", "server")
        val client = ServerSideClient(ServerSideSshTransport({ command ->
            assertEquals("serverside stream --stdio", command)
            stream
        }, identity), identity)
        withTimeout(5000) {
            assertTrue(client.rpc(RpcRequest("check", "capabilities.get")).ok)
            client.revoke()
            daemon.await()
        }
        assertEquals(1, releases)
    }

    @Test fun cancellingBlockedSshRpcReleasesExecChannel() = runBlocking {
        var releases = 0
        val clientIn = PipedInputStream(8192)
        val serverOut = PipedOutputStream(clientIn)
        val serverIn = PipedInputStream(8192)
        val clientOut = PipedOutputStream(serverIn)
        val stream = RemoteExecStream(clientIn, clientOut) {
            releases++; clientIn.close(); clientOut.close(); serverOut.close(); serverIn.close()
        }
        val observed = CompletableDeferred<Unit>()
        val daemon = async(Dispatchers.IO) {
            ServerSideSshTransport.readFrame(serverIn)
            ServerSideSshTransport.writeFrame(serverOut, """{"type":"paired","protocol":5,"client_id":"owner"}""")
            ServerSideSshTransport.readFrame(serverIn)
            observed.complete(Unit)
        }
        val identity = PairedIdentity("owner", "secret", "server")
        val client = ServerSideClient(ServerSideSshTransport({ stream }, identity), identity)
        val waiting = launch { client.rpc(RpcRequest("blocked", "session.get")) }
        withTimeout(5000) { observed.await(); waiting.cancelAndJoin() }
        assertEquals(1, releases)
        daemon.await()
        Unit
    }

    @Test fun execChannelCloseReleasesTheSharedSshLeaseOnlyOnce() {
        var releases = 0
        val stream = RemoteExecStream(ByteArrayInputStream(byteArrayOf()), ByteArrayOutputStream()) {
            releases++
        }
        stream.close()
        stream.close()
        assertEquals(1, releases)
    }
}
