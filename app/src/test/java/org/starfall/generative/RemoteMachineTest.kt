package org.starfall.generative

import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.starfall.generative.shared.model.PortForwardRule
import org.starfall.generative.shared.model.PortForwardingConfig
import org.starfall.generative.shared.model.REMOTE_TOOL_DISCONNECT
import org.starfall.generative.shared.model.REMOTE_TOOL_EXECUTE
import org.starfall.generative.shared.model.REMOTE_TOOL_READ_TERMINAL
import org.starfall.generative.shared.model.REMOTE_TOOL_START_MACHINE
import org.starfall.generative.shared.model.REMOTE_TOOL_UPDATE_IP
import org.starfall.generative.shared.model.RemoteMachine
import org.starfall.generative.shared.model.RemoteMachineType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.starfall.generative.integrations.remote.PasswordKeyboardInteractiveUserInfo
import org.starfall.generative.integrations.remote.SshConfigParser
import org.starfall.generative.integrations.remote.normalizeSshOptions
import org.starfall.generative.shared.model.ToolSettings
import org.starfall.generative.shared.model.WebSocketProxyConfig
import org.starfall.generative.shared.model.remote.RemoteSecretRequest
import org.starfall.generative.shared.model.parsePortForwardRule
import org.starfall.generative.shared.model.remoteMachineToolEnabled
import org.starfall.generative.shared.model.selectedRemoteMachine
import org.starfall.generative.shared.model.withSelectedRemoteMachine

class RemoteMachineTest {
    @Test
    fun sshConfigImportsConnectionAndCommandOptions() {
        val hosts = SshConfigParser.parse(
            """
            Host staging
              HostName staging.example.com
              User deploy
              Port 2201
              IdentityFile ~/.ssh/staging
              ProxyCommand helper %h %p
              LocalCommand notify-send connected
              ServerAliveInterval 45
            """.trimIndent()
        )

        val machine = SshConfigParser.toMachine(hosts.single())
        assertEquals("staging", machine.name)
        assertEquals("staging.example.com", machine.host)
        assertEquals(2201, machine.port)
        assertEquals("deploy", machine.username)
        assertEquals("~/.ssh/staging", machine.identityFile)
        assertEquals(45, machine.keepAliveSeconds)
    }

    @Test
    fun everyMachineExposesDisconnectAndExecuteAndCloudShellAddsUpdateIp() {
        assertEquals(
            listOf("open_terminal", "close_terminal", "list_terminal", REMOTE_TOOL_READ_TERMINAL, REMOTE_TOOL_EXECUTE, REMOTE_TOOL_DISCONNECT),
            RemoteMachine("ssh", "SSH").supportedToolNames()
        )
        assertEquals(
            listOf(REMOTE_TOOL_UPDATE_IP, "open_terminal", "close_terminal", "list_terminal", REMOTE_TOOL_READ_TERMINAL, REMOTE_TOOL_EXECUTE, REMOTE_TOOL_DISCONNECT),
            RemoteMachine("cloud", "Cloud", type = RemoteMachineType.GCLOUD_SHELL).supportedToolNames()
        )
        assertTrue(RemoteMachine("cloud", "Cloud", type = RemoteMachineType.GCLOUD_SHELL).displayHost.isNotBlank())
        assertFalse(RemoteMachine("ssh", "SSH", aiTools = mapOf(REMOTE_TOOL_READ_TERMINAL to false))
            .isToolEnabled(REMOTE_TOOL_READ_TERMINAL))
    }

    @Test
    fun savingAndDeletingAHostBlockPreservesOtherSshConfigEntries() {
        val machine = RemoteMachine(
            id = "one",
            name = "My server",
            host = "10.0.0.8",
            username = "dev",
            port = 2222,
            strictHostKeyChecking = true
        )
        val initial = "Host another\n  HostName other.example\n"
        val withMachine = SshConfigParser.upsertMachine(initial, machine)
        assertTrue(withMachine.contains("Host My-server"))
        assertTrue(withMachine.contains("Host another"))
        assertTrue(withMachine.contains("StrictHostKeyChecking yes"))

        val updated = SshConfigParser.upsertMachine(withMachine, machine.copy(host = "10.0.0.9"))
        assertEquals(1, Regex("Host My-server").findAll(updated).count())
        assertTrue(updated.contains("HostName 10.0.0.9"))
        assertTrue(SshConfigParser.removeMachine(updated, machine).contains("Host another"))
    }

    @Test
    fun passwordAuthenticationAnswersKeyboardInteractivePrompts() {
        val userInfo = PasswordKeyboardInteractiveUserInfo("deploy", "secret")

        assertArrayEquals(
            arrayOf("secret"),
            userInfo.promptKeyboardInteractive(
                "deploy@staging",
                "",
                "",
                arrayOf("Password for deploy@staging: "),
                booleanArrayOf(false)
            )
        )
        assertArrayEquals(
            arrayOf("deploy", "secret"),
            userInfo.promptKeyboardInteractive(
                "deploy@staging",
                "",
                "",
                arrayOf("login:", "Password:"),
                booleanArrayOf(true, false)
            )
        )
        assertArrayEquals(
            arrayOf("secret"),
            userInfo.promptKeyboardInteractive(
                "deploy@staging", "", "", arrayOf("Verification code:"), booleanArrayOf(false)
            )
        )
    }

    @Test
    fun emptyPasswordAsksOnlyWhenTheServerRequestsOne() {
        val requests = mutableListOf<RemoteSecretRequest>()
        val userInfo = PasswordKeyboardInteractiveUserInfo("deploy", "") { request ->
            requests += request
            "typed"
        }
        // Nothing is asked until JSch actually invokes a prompt (a no-auth server never does).
        assertTrue(requests.isEmpty())

        assertTrue(userInfo.promptPassword("Password for deploy@host"))
        assertEquals("typed", userInfo.password)
        assertFalse(requests.single().passphrase)

        assertArrayEquals(
            arrayOf("typed"),
            userInfo.promptKeyboardInteractive("d", "", "", arrayOf("Password:"), booleanArrayOf(false))
        )
        assertEquals(2, requests.size)

        assertTrue(userInfo.promptPassphrase("Passphrase for key"))
        assertEquals("typed", userInfo.passphrase)
        assertTrue(requests.last().passphrase)
    }

    @Test
    fun emptyPasswordWithoutPromptOrWhenCancelledAuthenticationFails() {
        val silent = PasswordKeyboardInteractiveUserInfo("deploy", "")
        assertFalse(silent.promptPassword("Password:"))
        assertNull(silent.promptKeyboardInteractive("d", "", "", arrayOf("Password:"), booleanArrayOf(false)))

        val cancelled = PasswordKeyboardInteractiveUserInfo("deploy", "") { null }
        assertFalse(cancelled.promptPassword("Password:"))
        assertNull(cancelled.promptKeyboardInteractive("d", "", "", arrayOf("Password:"), booleanArrayOf(false)))
    }

    @Test
    fun savedPasswordNeverPrompts() {
        var asked = false
        val userInfo = PasswordKeyboardInteractiveUserInfo("deploy", "secret") { asked = true; "typed" }
        assertTrue(userInfo.promptPassword("Password:"))
        assertEquals("secret", userInfo.password)
        userInfo.promptKeyboardInteractive("d", "", "", arrayOf("Password:"), booleanArrayOf(false))
        assertFalse(asked)
    }

    @Test
    fun passwordMachineUsesKeyboardInteractiveByDefaultForExistingRecords() {
        assertTrue(
            RemoteMachine(
                id = "password",
                name = "Password",
                authentication = org.starfall.generative.shared.model.RemoteAuthentication.PASSWORD
            ).keyboardInteractive
        )
    }

    @Test
    fun machineAndPerToolSwitchesAreIndependent() {
        val machine = RemoteMachine(
            id = "cloud",
            name = "Cloud",
            type = RemoteMachineType.GCLOUD_SHELL,
            aiTools = mapOf(REMOTE_TOOL_START_MACHINE to false)
        )
        val settings = ToolSettings(enabledMachines = mapOf(machine.id to true))

        assertTrue(remoteMachineToolEnabled(settings, machine, REMOTE_TOOL_EXECUTE))
        assertTrue(!remoteMachineToolEnabled(settings, machine, REMOTE_TOOL_START_MACHINE))
        assertTrue(!remoteMachineToolEnabled(settings.copy(enabledMachines = mapOf(machine.id to false)), machine, REMOTE_TOOL_EXECUTE))
    }

    @Test
    fun renamingAMachineMovesItsBlockAndKeepsUnmanagedOptions() {
        val machine = RemoteMachine(id = "one", name = "old", host = "10.0.0.8", username = "dev")
        val initial = "Host old\n  HostName 10.0.0.8\n  ForwardAgent yes\n\nHost other\n  HostName o.example\n"
        val renamed = SshConfigParser.upsertMachine(initial, machine.copy(name = "new"), previousName = "old")
        assertTrue(renamed.contains("Host new"))
        assertTrue(!renamed.contains("Host old"))
        assertTrue(renamed.contains("ForwardAgent yes"))
        assertTrue(renamed.contains("Host other"))
    }

    @Test
    fun deletingIsCaseInsensitiveOnKeywordAndOnlyDropsOwnAliasFromSharedBlocks() {
        val machine = RemoteMachine(id = "one", name = "box")
        val lowercase = "host box\n  HostName 1.1.1.1\n\nHost keep\n  HostName k\n"
        val removed = SshConfigParser.removeMachine(lowercase, machine)
        assertTrue(!removed.contains("box"))
        assertTrue(removed.contains("Host keep"))
        val shared = SshConfigParser.removeMachine("Host box other\n  HostName 1.1.1.1\n", machine)
        assertTrue(shared.contains("Host other"))
        assertTrue(!shared.contains("box"))
    }

    @Test
    fun editingSshConfigIsAppliedToMatchingMachinesAndKeepsSecrets() {
        val machine = RemoteMachine(id = "one", name = "box", host = "1.1.1.1", username = "dev", password = "secret")
        val edited = "Host box\n  HostName 2.2.2.2\n  Port 2200\n  User root\n  Compression yes\n"
        val synced = SshConfigParser.syncMachines(listOf(machine), edited).single()
        assertEquals("2.2.2.2", synced.host)
        assertEquals(2200, synced.port)
        assertEquals("root", synced.username)
        assertTrue(synced.compression)
        assertEquals(0, synced.keepAliveSeconds)
        assertEquals("secret", synced.password)
    }

    @Test
    fun publicKeyNormalizationKeepsAlgorithmAndDataAndRejectsMismatches() {
        // blob = length-prefixed "ssh-rsa" followed by filler
        val blob = java.util.Base64.getEncoder().encodeToString(
            byteArrayOf(0, 0, 0, 7) + "ssh-rsa".toByteArray() + byteArrayOf(1, 2, 3)
        )
        assertEquals("ssh-rsa $blob", org.starfall.generative.integrations.remote.normalizeOpenSshPublicKey("# note\nssh-rsa $blob user@host\n"))
        assertTrue(runCatching { org.starfall.generative.integrations.remote.normalizeOpenSshPublicKey("ecdsa-sha2-nistp256 $blob") }.isFailure)
        assertTrue(runCatching { org.starfall.generative.integrations.remote.normalizeOpenSshPublicKey("-----BEGIN OPENSSH PRIVATE KEY-----") }.isFailure)
        assertTrue(runCatching { org.starfall.generative.integrations.remote.normalizeOpenSshPublicKey("") }.isFailure)
    }

    @Test
    fun extraJschOptionsAreValidatedAndWebsocketProxySettingsAreNormalized() {
        assertEquals(
            mapOf("kex" to "diffie-hellman-group14-sha1"),
            normalizeSshOptions(mapOf("kex" to "diffie-hellman-group14-sha1"))
        )
        assertTrue(runCatching { normalizeSshOptions(mapOf("NotARealJschOption" to "1")) }.isFailure)
        assertTrue(runCatching { normalizeSshOptions(mapOf("StrictHostKeyChecking" to "yes")) }.isFailure)

        val proxy = WebSocketProxyConfig(
            url = " wss://proxy.example/%h/%p ",
            headers = mapOf(" Authorization " to "Bearer token"),
            connectTimeoutSeconds = 0,
            subProtocols = listOf("ssh", "ssh")
        ).normalized()
        assertEquals("wss://proxy.example/%h/%p", proxy.url)
        assertEquals(mapOf("Authorization" to "Bearer token"), proxy.headers)
        assertEquals(1, proxy.connectTimeoutSeconds)
        assertEquals(listOf("ssh"), proxy.subProtocols)
    }

    @Test
    fun portForwardRulesParseAndRejectMalformedInput() {
        assertEquals(PortForwardRule(8080, "db.internal", 3306), parsePortForwardRule(" 8080:db.internal:3306 "))
        assertEquals(PortForwardRule(8080, "localhost", 8080), parsePortForwardRule("8080"))
        assertEquals(PortForwardRule(8080, "localhost", 10808), parsePortForwardRule("8080:10808"))
        assertEquals(PortForwardRule(8080, "localhost", 10808), parsePortForwardRule("8080 -> 10808"))
        assertNull(parsePortForwardRule("8080:a:b:22"))
        assertNull(parsePortForwardRule("abc"))
        assertNull(parsePortForwardRule("0:host:22"))
        assertNull(parsePortForwardRule("8080:host:70000"))
        assertNull(parsePortForwardRule("8080::22"))
    }

    @Test
    fun portForwardingAppliesOnlyEnabledTypesAndValidatesPorts() {
        val config = PortForwardingConfig(
            localEnabled = true,
            localRules = listOf("8080:db:3306", " 8080:db:3306 ", ""),
            remoteEnabled = false,
            remoteRules = listOf("9090:localhost:3000"),
            dynamicEnabled = true,
            dynamicPorts = listOf(1080, 1080)
        ).validated()
        assertEquals(listOf("8080:db:3306"), config.localRules)
        assertEquals(listOf(1080), config.dynamicPorts)
        assertEquals(listOf(PortForwardRule(8080, "db", 3306)), config.activeLocalRules)
        assertTrue(config.activeRemoteRules.isEmpty())
        assertEquals(listOf(1080), config.activeDynamicPorts)
        assertTrue(config.hasActiveForwards)
        assertFalse(PortForwardingConfig(localRules = listOf("8080:db:3306")).hasActiveForwards)
    }

    @Test
    fun portForwardingRejectsInvalidAndConflictingPorts() {
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            PortForwardingConfig(localRules = listOf("oops")).validated()
        }
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            PortForwardingConfig(dynamicPorts = listOf(0)).validated()
        }
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            PortForwardingConfig(localRules = listOf("1080:a:80"), dynamicPorts = listOf(1080)).validated()
        }
    }
    @Test
    fun selectingMachineIsAtomicAndUnsetDisablesLegacyTools() {
        val allIds = listOf("one", "two", "three")
        val previous = ToolSettings(machinesEnabled = true,
            enabledMachines = mapOf("one" to true, "two" to true, "stale" to true))
        val selected = previous.withSelectedRemoteMachine("two", allIds)
        assertEquals(mapOf("one" to false, "two" to true, "three" to false, "stale" to false),
            selected.enabledMachines)
        assertTrue(selected.machinesEnabled)

        val unset = selected.withSelectedRemoteMachine(null, allIds)
        assertEquals(allIds.toSet() + "stale", unset.enabledMachines.keys)
        assertTrue(unset.enabledMachines.values.none { it })
        assertFalse(unset.machinesEnabled)
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            previous.withSelectedRemoteMachine("unknown", allIds)
        }
    }

    @Test
    fun selectedRemoteMachineUsesEnabledMachineAndUnsetOverridesLegacyDefault() {
        val machines = listOf(RemoteMachine("one", "One"), RemoteMachine("two", "Two"))
        assertEquals(machines[1], selectedRemoteMachine(
            ToolSettings(machinesEnabled = true,
                enabledMachines = mapOf("one" to false, "two" to true)), machines))
        assertNull(selectedRemoteMachine(
            ToolSettings(machinesEnabled = false,
                enabledMachines = mapOf("one" to false, "two" to false)), machines))
        assertEquals(machines[0], selectedRemoteMachine(
            ToolSettings(machinesEnabled = true), machines))
    }

}
