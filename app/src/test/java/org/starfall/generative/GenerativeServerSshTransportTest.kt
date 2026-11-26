package org.starfall.generative

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
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
import org.starfall.generative.integrations.generativeserver.GenerativeServerClient
import org.starfall.generative.integrations.generativeserver.GenerativeServerCodec
import org.starfall.generative.integrations.generativeserver.PairedIdentity
import org.starfall.generative.integrations.generativeserver.RpcRequest
import org.junit.Assert.*
import org.junit.Test
import org.starfall.generative.integrations.remote.RemoteExecStream
import org.starfall.generative.integrations.generativeserver.GenerativeServerSshTransport

class GenerativeServerSshTransportTest {
    @Test fun frameRoundTripAndSizeValidation() {
        val payload = """{"id":"request","op":"session.list","data":{"limit":1}}"""
        val buffer = ByteArrayOutputStream()
        GenerativeServerSshTransport.writeFrame(buffer, payload)
        assertEquals(payload, GenerativeServerSshTransport.readFrame(ByteArrayInputStream(buffer.toByteArray())))
        val invalid = ByteArrayInputStream(byteArrayOf(0, 64, 0, 1))
        assertThrows(java.io.IOException::class.java) { GenerativeServerSshTransport.readFrame(invalid) }
    }

    @Test fun sshStdioPairingParsesTheExistingServerContract() = runBlocking {
        var released = 0
        val payload = """{"protocol":5,"client_id":"owner","secret":"secret","fingerprint":"server","endpoint":"tcp://server","transport":"tcp"}"""
        val commands = mutableListOf<String>()
        val paired = GenerativeServerSshTransport.pairUsing { command ->
            commands += command
            val output = if (commands.size == 1) "generative-cli-ready\n" else payload
            RemoteExecStream(ByteArrayInputStream(output.toByteArray()), ByteArrayOutputStream()) { released++ }
        }
        assertEquals(2, commands.size)
        assertTrue(commands[0].contains("serverside-generative/main/install.sh"))
        assertTrue(commands[1].contains("exec \"\$cli\" pair --stdio --transport tcp"))
        assertEquals("owner", paired.identity.clientId)
        assertEquals("server", paired.identity.serverId)
        assertEquals(2, released)
    }

    @Test fun failedCliSetupStopsBeforePairing() = runBlocking {
        var opened = 0
        var released = 0
        try {
            GenerativeServerSshTransport.pairUsing {
                opened++
                RemoteExecStream(ByteArrayInputStream(byteArrayOf()), ByteArrayOutputStream()) { released++ }
            }
            fail("Pairing continued after failed CLI setup")
        } catch (failure: IOException) {
            assertEquals("Generative CLI setup failed", failure.message)
        }
        assertEquals(1, opened)
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
            val auth = GenerativeServerCodec.json.parseToJsonElement(GenerativeServerSshTransport.readFrame(serverIn)).jsonObject
            assertEquals("auth", auth.getValue("type").jsonPrimitive.content)
            assertEquals("owner", auth.getValue("client_id").jsonPrimitive.content)
            GenerativeServerSshTransport.writeFrame(serverOut, """{"type":"paired","protocol":5,"client_id":"owner"}""")
            val rpc = GenerativeServerCodec.json.parseToJsonElement(GenerativeServerSshTransport.readFrame(serverIn)).jsonObject
            assertEquals("capabilities.get", rpc.getValue("op").jsonPrimitive.content)
            GenerativeServerSshTransport.writeFrame(serverOut, """{"session_id":"chat","seq":1,"request_id":"old","kind":"delta","data":{"text":"old"}}""")
            GenerativeServerSshTransport.writeFrame(serverOut, """{"id":"check","ok":true,"data":{"version":1}}""")
            val revoke = GenerativeServerCodec.json.parseToJsonElement(GenerativeServerSshTransport.readFrame(serverIn)).jsonObject
            assertEquals("identity.revoke", revoke.getValue("op").jsonPrimitive.content)
            val id = revoke.getValue("id").jsonPrimitive.content
            GenerativeServerSshTransport.writeFrame(serverOut, """{"id":"$id","ok":true,"data":{"revoked":true}}""")
        }
        val identity = PairedIdentity("owner", "secret", "server")
        val client = GenerativeServerClient(GenerativeServerSshTransport({ command ->
            assertTrue(command.contains("exec \"\$cli\" stream --stdio"))
            assertFalse(command.contains("install.sh"))
            stream
        }, identity), identity)
        withTimeout(5000) {
            client.withConnection {
                assertTrue(client.rpc(RpcRequest("check", "capabilities.get")).ok)
                client.revoke()
            }
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
            GenerativeServerSshTransport.readFrame(serverIn)
            GenerativeServerSshTransport.writeFrame(serverOut, """{"type":"paired","protocol":5,"client_id":"owner"}""")
            GenerativeServerSshTransport.readFrame(serverIn)
            observed.complete(Unit)
        }
        val identity = PairedIdentity("owner", "secret", "server")
        val client = GenerativeServerClient(GenerativeServerSshTransport({ stream }, identity), identity)
        val waiting = launch { client.rpc(RpcRequest("blocked", "session.get")) }
        withTimeout(5000) { observed.await(); waiting.cancelAndJoin() }
        assertEquals(1, releases)
        daemon.await()
        Unit
    }

    @Test fun closingWhileWaitingForAuthenticationReleasesTheExecChannel() = runBlocking {
        val clientIn = PipedInputStream(8192)
        val serverOut = PipedOutputStream(clientIn)
        val serverIn = PipedInputStream(8192)
        val clientOut = PipedOutputStream(serverIn)
        val released = CompletableDeferred<Unit>()
        val stream = RemoteExecStream(clientIn, clientOut) {
            serverOut.close()
            clientIn.close()
            clientOut.close()
            serverIn.close()
            released.complete(Unit)
        }
        val identity = PairedIdentity("owner", "secret", "server")
        val client = GenerativeServerClient(GenerativeServerSshTransport({ stream }, identity), identity)
        val rpc = async { runCatching { client.rpc(RpcRequest("blocked", "capabilities.get")) } }
        withTimeout(5000) {
            val auth = async(Dispatchers.IO) { GenerativeServerSshTransport.readFrame(serverIn) }
            assertEquals("auth", GenerativeServerCodec.json.parseToJsonElement(auth.await()).jsonObject.getValue("type").jsonPrimitive.content)
            client.close()
            released.await()
            assertTrue(rpc.await().isFailure)
        }
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
