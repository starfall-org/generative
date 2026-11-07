package org.starfall.generative

import org.junit.Assert.*
import org.junit.Test
import org.starfall.generative.integrations.remote.RemoteCommandResult
import org.starfall.generative.integrations.serverside.ServersideHandshakeException
import org.starfall.generative.integrations.serverside.parseServersideHandshake

class ServersideHandshakeTest {
    private fun invitation(protocol: Int = 4, expires: Long = 200) =
        """{"protocol":$protocol,"endpoint":"server:7090","fingerprint":"${"a".repeat(64)}","ticket":"secret-ticket","expires":$expires}"""

    @Test fun oldSettingsPayloadDefaultsServersideToOff() {
        val settings = kotlinx.serialization.json.Json.decodeFromString(
            org.starfall.generative.shared.model.ToolSettings.serializer(), "{}")
        assertTrue(settings.serversideMachines.isEmpty())
    }

    @Test fun acceptsProtocolFourInvitationWithoutRequiringAReleaseVersion() {
        val result = parseServersideHandshake(RemoteCommandResult(invitation()), 100)
        assertEquals("server:7090", result.endpoint)
        assertEquals(200L, result.expires)
    }

    @Test fun malformedExpiredAndUnsupportedInvitationsNeverLeakTickets() {
        for (output in listOf(invitation(protocol = 3), invitation(expires = 99),
            invitation().replace("server:7090", ""), "secret-ticket not JSON")) {
            val error = assertThrows(ServersideHandshakeException::class.java) {
                parseServersideHandshake(RemoteCommandResult(output), 100)
            }
            assertFalse(error.cliMissing)
            assertFalse(error.message.orEmpty().contains("secret-ticket"))
        }
    }

    @Test fun requiresDedicatedMissingMarkerRatherThanGenericCommandExitCodes() {
        val missing = assertThrows(ServersideHandshakeException::class.java) {
            parseServersideHandshake(RemoteCommandResult("GENERATIVE_SERVERSIDE_MISSING", exitCode = 73), 100)
        }
        assertTrue(missing.cliMissing)
        for (code in listOf(1, 73, 126, 127)) {
            val failure = assertThrows(ServersideHandshakeException::class.java) {
                parseServersideHandshake(RemoteCommandResult("", "daemon unavailable", code), 100)
            }
            assertFalse(failure.cliMissing)
        }
    }
}
