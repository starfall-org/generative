package org.starfall.generative

import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
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
import org.starfall.generative.integrations.remote.normalizeOpenSshUserCertificate
import org.starfall.generative.shared.model.ToolSettings
import org.starfall.generative.shared.model.WebSocketProxyConfig
import org.starfall.generative.shared.model.remote.RemoteSecretRequest
import org.starfall.generative.shared.model.parsePortForwardRule
import org.starfall.generative.shared.model.remoteMachineToolEnabled
import org.starfall.generative.shared.model.selectedRemoteMachine
import org.starfall.generative.shared.model.withSelectedRemoteMachine
import org.starfall.generative.shared.model.withRemoteMachineEnabled

class RemoteMachineTest {
    @Test
    fun sshUserCertificateIsValidatedAndNormalized() {
        val algorithm = "ssh-ed25519-cert-v01@openssh.com"
        val bytes = java.nio.ByteBuffer.allocate(4 + algorithm.length + 8)
            .putInt(algorithm.length)
            .put(algorithm.toByteArray(Charsets.US_ASCII))
            .put(ByteArray(8))
            .array()
        val encoded = java.util.Base64.getEncoder().encodeToString(bytes)
        assertEquals("$algorithm $encoded", normalizeOpenSshUserCertificate("  $algorithm  $encoded  user@example  "))

        for (invalid in listOf(
            "-----BEGIN CERTIFICATE-----\n...\n-----END CERTIFICATE-----",
            "ssh-ed25519 $encoded",
            "$algorithm !!!",
            "ssh-rsa-cert-v01@openssh.com $encoded"
        )) {
            assertTrue(runCatching { normalizeOpenSshUserCertificate(invalid) }.isFailure)
        }
    }

    @Test
    fun sshCertificateSurvivesSerializationAndOlderMachinesRemainReadable() {
        val machine = RemoteMachine(
            id = "cert", name = "Signed SSH", identityFile = "/keys/client",
            sshCertificateName = "client-cert.pub", sshCertificate = "ssh-ed25519-cert-v01@openssh.com AAAA"
        )
        val restored = Json.decodeFromString<RemoteMachine>(Json.encodeToString(machine))
        assertEquals(machine, restored)
        val legacy = Json.decodeFromString<RemoteMachine>("""{"id":"old","name":"Old machine"}""")
        assertEquals("", legacy.sshCertificate)
        assertEquals("", legacy.sshCertificateName)
    }

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
    fun switchingServersDisablesOldServerAndDoesNotAutomaticallyEnableNewServer() {
        val allIds = listOf("one", "two", "three")
        val machines = allIds.map { RemoteMachine(it, it) }
        val previous = ToolSettings(
            selectedMachineId = "one",
            machinesEnabled = true,
            enabledMachines = mapOf("one" to true, "two" to false, "stale" to true)
        )

        val selected = previous.withSelectedRemoteMachine("two", allIds)
        assertEquals("two", selected.selectedMachineId)
        assertEquals(true, selected.rememberedEnabledMachines["one"])
        assertTrue(selected.enabledMachines.values.none { it })
        assertFalse(selected.machinesEnabled)
        assertEquals(machines[1], selectedRemoteMachine(selected, machines))
        assertFalse(remoteMachineToolEnabled(selected, machines[0], REMOTE_TOOL_EXECUTE))
        assertFalse(remoteMachineToolEnabled(selected, machines[1], REMOTE_TOOL_EXECUTE))

        // First-time selection is off; only Switch can create a saved "on" preference.
        val enabled = selected.withRemoteMachineEnabled("two", true, allIds)
        assertEquals(true, enabled.rememberedEnabledMachines["two"])
        assertEquals(setOf("two"), enabled.enabledMachines.filterValues { it }.keys)
        assertTrue(enabled.machinesEnabled)
        assertTrue(remoteMachineToolEnabled(enabled, machines[1], REMOTE_TOOL_EXECUTE))
        assertFalse(remoteMachineToolEnabled(enabled, machines[0], REMOTE_TOOL_EXECUTE))

        // Tapping the already selected server should not inadvertently turn it off.
        assertEquals(enabled, enabled.withSelectedRemoteMachine("two", allIds))
        // Toggling an unselected server has no effect.
        assertEquals(enabled, enabled.withRemoteMachineEnabled("one", true, allIds))

        val switchedBack = enabled.withSelectedRemoteMachine("one", allIds)
        assertEquals(setOf("one"), switchedBack.enabledMachines.filterValues { it }.keys)
        assertTrue(switchedBack.machinesEnabled)
        assertFalse(remoteMachineToolEnabled(switchedBack, machines[1], REMOTE_TOOL_EXECUTE))
        assertTrue(remoteMachineToolEnabled(switchedBack, machines[0], REMOTE_TOOL_EXECUTE))
        assertEquals(true, switchedBack.rememberedEnabledMachines["two"])

        val backToTwo = switchedBack.withSelectedRemoteMachine("two", allIds)
        assertEquals(setOf("two"), backToTwo.enabledMachines.filterValues { it }.keys)
        assertTrue(remoteMachineToolEnabled(backToTwo, machines[1], REMOTE_TOOL_EXECUTE))
        assertFalse(remoteMachineToolEnabled(backToTwo, machines[0], REMOTE_TOOL_EXECUTE))

        val unset = backToTwo.withSelectedRemoteMachine(null, allIds)
        assertEquals("", unset.selectedMachineId)
        assertNull(selectedRemoteMachine(unset, machines))
        assertEquals(allIds.toSet() + "stale", unset.enabledMachines.keys)
        assertTrue(unset.enabledMachines.values.none { it })
        assertEquals(true, unset.rememberedEnabledMachines["one"])
        assertEquals(true, unset.rememberedEnabledMachines["two"])
        assertFalse(unset.machinesEnabled)
        assertFalse(remoteMachineToolEnabled(unset, machines[0], REMOTE_TOOL_EXECUTE))
        assertFalse(remoteMachineToolEnabled(unset, machines[1], REMOTE_TOOL_EXECUTE))

        // Re-selecting restores the saved switch, but never activates another server.
        val reselected = unset.withSelectedRemoteMachine("one", allIds)
        assertTrue(remoteMachineToolEnabled(reselected, machines[0], REMOTE_TOOL_EXECUTE))
        assertFalse(remoteMachineToolEnabled(reselected, machines[1], REMOTE_TOOL_EXECUTE))
        assertEquals(setOf("one"), reselected.enabledMachines.filterValues { it }.keys)

        // Explicitly turning the switch off must be remembered too.
        val disabled = reselected.withRemoteMachineEnabled("one", false, allIds)
        assertEquals(false, disabled.rememberedEnabledMachines["one"])
        val disabledReselected = disabled.withSelectedRemoteMachine(null, allIds)
            .withSelectedRemoteMachine("one", allIds)
        assertFalse(remoteMachineToolEnabled(disabledReselected, machines[0], REMOTE_TOOL_EXECUTE))
        assertFalse(disabledReselected.machinesEnabled)

        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            previous.withSelectedRemoteMachine("unknown", allIds)
        }
    }

    @Test
    fun savedPerServerSwitchesSurviveSerializationWithoutGrantingToolAccess() {
        val machines = listOf(RemoteMachine("one", "One"), RemoteMachine("two", "Two"))
        val ids = machines.map { it.id }
        val saved = ToolSettings(selectedMachineId = "one")
            .withRemoteMachineEnabled("one", true, ids)
            .withSelectedRemoteMachine("two", ids)
            .withRemoteMachineEnabled("two", false, ids)
            .withSelectedRemoteMachine(null, ids)

        val json = Json.encodeToString(ToolSettings.serializer(), saved)
        val restored = Json.decodeFromString(ToolSettings.serializer(), json)
        assertEquals(mapOf("one" to true, "two" to false), restored.rememberedEnabledMachines)
        assertEquals("", restored.selectedMachineId)
        assertTrue(restored.enabledMachines.values.none { it })
        assertFalse(remoteMachineToolEnabled(restored, machines[0], REMOTE_TOOL_EXECUTE))

        val selectedOne = restored.withSelectedRemoteMachine("one", ids)
        assertTrue(remoteMachineToolEnabled(selectedOne, machines[0], REMOTE_TOOL_EXECUTE))
        assertFalse(remoteMachineToolEnabled(selectedOne, machines[1], REMOTE_TOOL_EXECUTE))
        val selectedTwo = selectedOne.withSelectedRemoteMachine("two", ids)
        assertFalse(remoteMachineToolEnabled(selectedTwo, machines[0], REMOTE_TOOL_EXECUTE))
        assertFalse(remoteMachineToolEnabled(selectedTwo, machines[1], REMOTE_TOOL_EXECUTE))
    }

    @Test
    fun legacyEnabledFlagsDoNotGrantToolsWithoutExplicitSelection() {
        val machines = listOf(RemoteMachine("one", "One"), RemoteMachine("two", "Two"))
        val legacy = ToolSettings(machinesEnabled = true,
            enabledMachines = mapOf("one" to true, "two" to true))
        assertNull(selectedRemoteMachine(legacy, machines))
        assertFalse(remoteMachineToolEnabled(legacy, machines[0], REMOTE_TOOL_EXECUTE))
        assertFalse(remoteMachineToolEnabled(legacy, machines[1], REMOTE_TOOL_EXECUTE))
        assertFalse(remoteMachineToolEnabled(ToolSettings(machinesEnabled = true),
            machines[0], REMOTE_TOOL_EXECUTE))
        val noSelection = ToolSettings(selectedMachineId = "", enabledMachines = mapOf("" to true))
        val invalidIdMachine = RemoteMachine("", "Invalid ID")
        assertFalse(remoteMachineToolEnabled(noSelection, invalidIdMachine, REMOTE_TOOL_EXECUTE))
        assertEquals(noSelection, noSelection.withRemoteMachineEnabled("", true, listOf("")))

        val selected = legacy.withSelectedRemoteMachine("two", machines.map { it.id })
        assertEquals(machines[1], selectedRemoteMachine(selected, machines))
        assertTrue(selected.enabledMachines.values.none { it })
        assertFalse(remoteMachineToolEnabled(selected, machines[1], REMOTE_TOOL_EXECUTE))
        val enabled = selected.withRemoteMachineEnabled("two", true, machines.map { it.id })
        assertTrue(remoteMachineToolEnabled(enabled, machines[1], REMOTE_TOOL_EXECUTE))
        assertFalse(remoteMachineToolEnabled(enabled, machines[0], REMOTE_TOOL_EXECUTE))
    }

}
