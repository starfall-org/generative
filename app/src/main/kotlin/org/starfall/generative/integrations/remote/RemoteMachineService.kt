package org.starfall.generative.integrations.remote

import android.content.Context
import android.net.Uri
import android.util.Log
import com.google.android.gms.auth.api.identity.Identity
import com.jcraft.jsch.JSch
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.Session
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.add
import org.starfall.generative.shared.model.GCloudOAuthClient
import org.starfall.generative.shared.model.PortForwardRule
import org.starfall.generative.shared.model.REMOTE_TOOL_CLOSE_TERMINAL
import org.starfall.generative.shared.model.REMOTE_TOOL_DISCONNECT
import org.starfall.generative.shared.model.REMOTE_TOOL_EXECUTE
import org.starfall.generative.shared.model.REMOTE_TOOL_LIST_TERMINAL
import org.starfall.generative.shared.model.REMOTE_TOOL_READ_TERMINAL
import org.starfall.generative.shared.model.REMOTE_TOOL_OPEN_TERMINAL
import org.starfall.generative.shared.model.REMOTE_TOOL_START_MACHINE
import org.starfall.generative.shared.model.REMOTE_TOOL_UPDATE_IP
import org.starfall.generative.shared.model.REMOTE_SFTP_HOME
import org.starfall.generative.shared.model.RemoteAuthentication
import org.starfall.generative.shared.model.RemoteIdentityKey
import org.starfall.generative.shared.model.RemoteMachine
import org.starfall.generative.shared.model.RemoteMachineType
import org.starfall.generative.shared.model.remote.REMOTE_GOOGLE_CLOUD_SCOPE
import org.starfall.generative.shared.storage.preferences.RemoteTerminalPreferenceStore
import org.starfall.generative.shared.storage.preferences.remoteTerminalInputModePreferenceKey
import androidx.datastore.preferences.core.stringPreferencesKey
import org.starfall.generative.shared.model.remote.RemoteSecretPrompt
import org.starfall.generative.shared.model.remote.RemoteTerminalSession



/**
 * SSH and Cloud Shell operations for the extension. No UI code is involved here, which keeps the
 * feature usable by both the terminal screen and the model tool runtime.
 */
class RemoteMachineService(
    private val context: Context,
    val store: RemoteMachineStore = RemoteMachineStore(context)
) {
    private val appContext = context.applicationContext
    private val assets = RemoteMachineAssetsService(context, store)
    private val connectionManager = RemoteMachineConnectionManager(
        openSessionCallback = { machine, password, prompt -> openSession(machine, password, prompt) },
        startForwards = ::applyPortForwarding
    )
    private val cloudShell = RemoteMachineCloudShellService(context, store, assets)
    private val sftpSessions = RemoteSftpSessionPool { machine -> openSession(machine) }

    val state = store.state

    internal fun exportIdentityFiles(): Map<String, Pair<File, File?>> = state.value.identities.associate { identity ->
        val privateKey = File(identity.path).takeIf { it.isFile }
            ?: error("The private key for SSH identity ${identity.name} is missing.")
        val publicKey = identity.publicKeyPath.takeIf(String::isNotBlank)?.let(::File)?.takeIf { it.isFile }
        identity.id to (privateKey to publicKey)
    }

    /**
     * Machines that currently hold a persistent SSH connection (opened by the connect tool or by
     * execute). A terminal's private connection is not listed.
     */
    val connectedMachineIds: StateFlow<Set<String>> get() = connectionManager.connectedMachineIds

    /** Forwards explicitly started by the user (saved rules do not activate themselves). */
    val forwardedPorts: StateFlow<Map<String, List<String>>> get() = connectionManager.forwardedPorts

    /**
     * Explicit forwarding opt-in. Reuses the terminal SSH connection if available, without ending
     * terminal channels. Ordinary terminal, SFTP and proxy sessions never start forwarding.
     */
    suspend fun startPortForward(
        machine: RemoteMachine,
        passwordOverride: String? = null,
        askSecret: RemoteSecretPrompt? = null
    ) {
        require(machine.portForwarding.hasActiveForwards) {
            "Turn on a port forward with at least one valid rule in this machine's settings first."
        }
        connectionManager.startPortForward(machine, passwordOverride, askSecret)
    }

    suspend fun stopPortForward(machineId: String): Boolean = connectionManager.stopPortForward(machineId)

    /** Saving forwarding settings must not disconnect any live terminal session. */
    suspend fun savePortForwarding(machine: RemoteMachine) {
        val config = machine.portForwarding.validated()
        store.update { current ->
            require(current.machines.any { it.id == machine.id }) { "Remote machine no longer exists." }
            current.copy(machines = current.machines.map { existing ->
                if (existing.id == machine.id) existing.copy(portForwarding = config) else existing
            })
        }
    }

    suspend fun saveMachine(machine: RemoteMachine) {
        val username = machine.username.trim()
        val normalizedPort = if (machine.type == RemoteMachineType.GCLOUD_SHELL) 6_000 else machine.port.coerceIn(1, 65535)
        val passwordReference = if (machine.type == RemoteMachineType.GCLOUD_SHELL) {
            machine.host.trim().takeIf(String::isNotBlank)?.let {
                remotePasswordReference(username.ifBlank { machine.gcloudUsername }, it, normalizedPort)
            } ?: machine.passwordReference
        } else {
            remotePasswordReference(username, machine.host.trim(), normalizedPort)
        }
        val normalized = machine.copy(
            name = machine.name.trim(),
            host = if (machine.type == RemoteMachineType.GCLOUD_SHELL) "" else machine.host.trim(),
            username = username,
            port = normalizedPort,
            passwordReference = passwordReference,
            connectTimeoutSeconds = machine.connectTimeoutSeconds.coerceIn(1, 300),
            keepAliveSeconds = machine.keepAliveSeconds.coerceIn(0, 3_600),
            sshOptions = normalizeSshOptions(machine.sshOptions),
            sshCertificate = machine.sshCertificate.takeIf(String::isNotBlank)?.let(::normalizeOpenSshUserCertificate).orEmpty(),
            websocketProxy = machine.websocketProxy?.normalized()?.takeIf { it.isConfigured },
            proxyMachineId = machine.proxyMachineId.trim(),
            sftpDirectory = machine.sftpDirectory.trim().ifBlank { REMOTE_SFTP_HOME },
            portForwarding = machine.portForwarding.validated(),
            preferredAuthentications = if (machine.authentication == RemoteAuthentication.PASSWORD) {
                if (machine.keyboardInteractive) "keyboard-interactive,password" else "password"
            } else "publickey"
        )
        require(normalized.name.isNotBlank()) { "Machine name is required." }
        require(normalized.proxyMachineId != normalized.id) { "A machine cannot proxy through itself." }
        require(normalized.websocketProxy == null || normalized.proxyMachineId.isBlank()) {
            "Choose either WebSocket Proxy or another proxy machine, not both."
        }
        validateProxyMachine(normalized)
        store.update { current ->
            val order = if (current.machines.any { it.id == normalized.id }) normalized.sortOrder
            else current.machines.maxOfOrNull { it.sortOrder }.orZero() + 1
            val saved = normalized.copy(sortOrder = order)
            current.copy(
                machines = current.machines
                    .filterNot { it.id == normalized.id }
                    .plus(saved)
                    .sortedWith(compareBy<RemoteMachine> { it.sortOrder }.thenBy { it.name.lowercase() })
            )
        }
        // The next connection must use the saved settings.
        disconnectMachineNow(normalized.id)
        sftpSessions.invalidate(normalized.id)
    }

    /** Replaces the complete Remote Machines state and restores uploaded identity files. */
    suspend fun replaceState(snapshot: org.starfall.generative.shared.model.RemoteMachineState, identityFiles: Map<String, Pair<File, File?>>) {
        state.value.machines.forEach { disconnectMachineNow(it.id) }
        val identityDirectory = File(appContext.filesDir, "remote-machines/identities").apply { mkdirs() }
        identityDirectory.listFiles().orEmpty().forEach { it.delete() }

        val rewrittenIdentities = snapshot.identities.map { identity ->
            val assets = identityFiles[identity.id] ?: error("The SSH identity ${identity.name} is missing from the backup.")
            val privateFile = File(identityDirectory, identity.id)
            assets.first.inputStream().use { input -> privateFile.outputStream().use { input.copyTo(it) } }
            privateFile.setReadable(false, false)
            privateFile.setWritable(true, true)
            val publicFile = assets.second?.let { source ->
                val destination = File(identityDirectory, "${identity.id}.pub")
                source.inputStream().use { input -> destination.outputStream().use { input.copyTo(it) } }
                destination
            }
            identity.copy(path = privateFile.absolutePath, publicKeyPath = publicFile?.absolutePath.orEmpty())
        }
        val pathsByOldReference = snapshot.identities.associate { identity ->
            identity.id to (identity.path to identity.name)
        }
        val newPathById = rewrittenIdentities.associateBy { it.id }
        val rewrittenMachines = snapshot.machines.map { machine ->
            val identity = snapshot.identities.firstOrNull { candidate ->
                val old = pathsByOldReference.getValue(candidate.id)
                machine.identityFile == old.first || machine.identityFile == old.second ||
                    machine.identityFile.substringAfterLast('/') == old.second
            }
            val replacement = identity?.let { newPathById.getValue(it.id).path }
            if (replacement == null) machine else machine.copy(identityFile = replacement)
        }
        store.replaceState(snapshot.copy(machines = rewrittenMachines, identities = rewrittenIdentities))
    }

    /** Tool switches do not affect SSH transport, so changing them must not interrupt live sessions. */
    suspend fun saveMachineTools(machine: RemoteMachine) {
        store.update { current ->
            current.copy(machines = current.machines.map { existing ->
                if (existing.id == machine.id) {
                    existing.copy(aiToolsEnabled = machine.aiToolsEnabled, aiTools = machine.aiTools)
                } else existing
            })
        }
    }

    suspend fun deleteMachine(id: String) {
        disconnectMachineNow(id)
        sftpSessions.invalidate(id)
        cloudShell.invalidateCache(id)
        store.update { current ->
            current.copy(
                machines = current.machines.filterNot { it.id == id }
            )
        }
        runCatching { File(appContext.filesDir, "remote-machines/keys/$id").delete() }
        try {
            RemoteTerminalPreferenceStore.getInstance(appContext).updateAndWait {
                it.remove(stringPreferencesKey(remoteTerminalInputModePreferenceKey(id)))
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Terminal preferences are ancillary to deleting the machine itself.
        }
    }

    private fun validateProxyMachine(machine: RemoteMachine) {
        if (machine.proxyMachineId.isBlank()) return
        val machines = state.value.machines.associateBy { it.id } + (machine.id to machine)
        var next = machine.proxyMachineId
        val seen = mutableSetOf(machine.id)
        while (next.isNotBlank()) {
            require(seen.add(next)) { "Remote Machine proxy settings cannot contain a cycle." }
            val proxy = machines[next] ?: error("Selected proxy machine no longer exists.")
            require(proxy.proxyMachineId.isBlank()) {
                "A proxy machine cannot itself use another proxy machine."
            }
            next = proxy.proxyMachineId
        }
    }

    suspend fun reorderMachines(ids: List<String>) {
        store.update { current ->
            val positions = ids.withIndex().associate { it.value to it.index }
            current.copy(machines = current.machines
                .map { machine -> machine.copy(sortOrder = positions[machine.id] ?: machine.sortOrder) }
                .sortedWith(compareBy<RemoteMachine> { it.sortOrder }.thenBy { it.name.lowercase() }))
        }
    }

    suspend fun saveOAuthClient(client: GCloudOAuthClient) {
        require(client.name.trim().isNotBlank()) { "OAuth client name is required." }
        require(client.clientId.trim().isNotBlank()) { "OAuth client ID is required." }
        store.update { current ->
            current.copy(oauthClients = current.oauthClients.filterNot { it.id == client.id } + client.copy(
                name = client.name.trim(), clientId = client.clientId.trim()
            ))
        }
    }

    suspend fun deleteOAuthClient(id: String) {
        store.update { current ->
            current.copy(
                oauthClients = current.oauthClients.filterNot { it.id == id },
                oauthTokens = current.oauthTokens - id,
                machines = current.machines.map { machine ->
                    if (machine.oauthClientId == id) machine.copy(oauthClientId = "") else machine
                }
            )
        }
    }

    suspend fun parseOAuthClientJson(uri: Uri): GCloudOAuthClient = assets.parseOAuthClientJson(uri)

    suspend fun importIdentityKey(uri: Uri): RemoteIdentityKey = assets.importIdentityKey(uri)

    suspend fun importPublicKey(keyId: String, uri: Uri): RemoteIdentityKey = assets.importPublicKey(keyId, uri)

    suspend fun deleteIdentityKey(id: String) = assets.deleteIdentityKey(id)

    suspend fun authorizeGCloud(client: GCloudOAuthClient): Result<Unit> = cloudShell.authorizeGCloud(client)

    suspend fun storeAndroidOAuthToken(client: GCloudOAuthClient, accessToken: String) =
        cloudShell.storeAndroidOAuthToken(client, accessToken)

    suspend fun refreshGCloudIp(machine: RemoteMachine, forceRefresh: Boolean = true): Result<RemoteMachine> =
        cloudShell.refreshGCloudIp(machine, forceRefresh)

    suspend fun fetchGCloudUsername(machine: RemoteMachine): Result<String> = cloudShell.fetchGCloudUsername(machine)

    suspend fun startGCloudMachine(machine: RemoteMachine): Result<RemoteMachine> = cloudShell.startGCloudMachine(machine)

    suspend fun execute(
        machine: RemoteMachine,
        command: String,
        timeoutSeconds: Int = 120,
        terminalId: String? = null
    ): RemoteCommandResult {
        require(command.isNotBlank()) { "The command is empty." }
        return connectionManager.execute(machine, command, timeoutSeconds, terminalId)
    }

    suspend fun executeTool(machine: RemoteMachine, toolName: String, arguments: JsonObject): JsonObject {
        require(machine.isToolEnabled(toolName)) { "Remote Machine tool is disabled." }
        return when (toolName) {
            REMOTE_TOOL_UPDATE_IP -> refreshGCloudIp(machine, forceRefresh = true).fold(
                onSuccess = { updated ->
                    buildJsonObject {
                        put("status", JsonPrimitive("updated"))
                        put("host", updated.host)
                        put("port", updated.port)
                    }
                },
                onFailure = { throw it }
            )
            REMOTE_TOOL_START_MACHINE -> startGCloudMachine(machine).fold(
                onSuccess = { updated ->
                    buildJsonObject {
                        put("status", JsonPrimitive("started"))
                        put("host", updated.host)
                        put("port", updated.port)
                    }
                },
                onFailure = { throw it }
            )
            REMOTE_TOOL_OPEN_TERMINAL -> {
                val terminal = connectionManager.openToolTerminal(machine)
                buildJsonObject {
                    put("status", "opened")
                    put("terminal_id", terminal.id)
                }
            }
            REMOTE_TOOL_CLOSE_TERMINAL -> buildJsonObject {
                val terminalId = arguments["terminal_id"]?.jsonPrimitive?.contentOrNull
                put("status", if (connectionManager.closeTerminal(machine.id, terminalId)) "closed" else "not_open")
                terminalId?.let { put("terminal_id", it) }
            }
            REMOTE_TOOL_LIST_TERMINAL -> buildJsonObject {
                putJsonArray("terminals") {
                    connectionManager.listTerminals(machine.id).forEach { terminal ->
                        add(buildJsonObject {
                            put("terminal_id", terminal.id)
                            put("is_connected", terminal.isConnected)
                        })
                    }
                }
            }
            REMOTE_TOOL_READ_TERMINAL -> {
                val lines = arguments["lines"]?.jsonPrimitive?.intOrNull
                    ?: error("lines must be an integer from 1 to 1000")
                require(lines in 1..1000) { "lines must be between 1 and 1000" }
                val terminalId = arguments["terminal_id"]?.jsonPrimitive?.contentOrNull
                val (resolvedId, output) = connectionManager.readTerminal(machine.id, terminalId, lines)
                buildJsonObject {
                    put("terminal_id", resolvedId)
                    put("lines", lines)
                    put("output", output)
                }
            }
            REMOTE_TOOL_DISCONNECT -> buildJsonObject {
                put("status", if (disconnectMachineNow(machine.id)) "disconnected" else "not_connected")
            }
            REMOTE_TOOL_EXECUTE -> {
                val command = arguments["command"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val timeout = arguments["timeout_seconds"]?.jsonPrimitive?.longOrNull
                    ?.toInt()?.coerceIn(1, 600) ?: 120
                val terminalId = arguments["terminal_id"]?.jsonPrimitive?.contentOrNull
                val result = execute(machine, command, timeout, terminalId)
                buildJsonObject {
                    put("output", result.output)
                    put("stderr", result.error)
                    put("exit_code", result.exitCode)
                    put("isError", result.isError)
                }
            }
            else -> error("Unknown Remote Machine tool: $toolName")
        }
    }

    suspend fun openTerminal(
        machine: RemoteMachine,
        passwordOverride: String? = null,
        askSecret: RemoteSecretPrompt? = null
    ): RemoteTerminalSession = connectionManager.openTerminal(machine, passwordOverride, askSecret)

    /** Opens an SFTP channel on demand for the Files provider. */
    internal suspend fun acquireSftpLease(machine: RemoteMachine): RemoteSftpLease =
        sftpSessions.acquire(machine)

    internal fun invalidateSftp(machineId: String) = sftpSessions.invalidate(machineId)

    fun isConnected(machineId: String): Boolean = connectionManager.isConnected(machineId)
    fun disconnectMachine(machineId: String) = connectionManager.disconnectMachine(machineId)
    fun disconnectAllWhenIdle() = connectionManager.disconnectAllWhenIdle()
    suspend fun disconnectMachineNow(machineId: String): Boolean = connectionManager.disconnectMachineNow(machineId)

    private suspend fun openSession(
        machine: RemoteMachine,
        passwordOverride: String? = null,
        askSecret: RemoteSecretPrompt? = null
    ): Session {
        var actual = if (machine.type == RemoteMachineType.GCLOUD_SHELL) {
            refreshGCloudIp(machine, forceRefresh = false).getOrThrow()
        } else machine
        val proxyMachine = actual.proxyMachineId.takeIf(String::isNotBlank)?.let { id ->
            state.value.machines.firstOrNull { it.id == id }
                ?: error("Selected proxy machine no longer exists.")
        }
        val proxySession = proxyMachine?.let { connectCancellable(it, null, null) }
        val session = try {
            connectCancellable(actual, passwordOverride, askSecret, proxySession)
        } catch (error: Exception) {
            runCatching { proxySession?.disconnect() }
            if (error is CancellationException || actual.type != RemoteMachineType.GCLOUD_SHELL) throw error
            actual = refreshGCloudIp(actual).getOrThrow()
            connectCancellable(actual, passwordOverride, askSecret, proxySession)
        }
        return session
    }

    /**
     * JSch's connect call blocks its thread and ignores coroutine cancellation, so it runs in a child
     * coroutine and the half-open session is closed when cancelled (the Stop button).
     */
    private suspend fun connectCancellable(
        machine: RemoteMachine,
        passwordOverride: String?,
        askSecret: RemoteSecretPrompt? = null,
        proxySession: Session? = null
    ): Session =
        withContext(Dispatchers.IO) {
            val pending = AtomicReference<Session?>()
            coroutineScope {
                val opening = async(Dispatchers.IO) {
                    connect(machine, passwordOverride, askSecret, proxySession, onCreated = { pending.set(it) })
                }
                try {
                    opening.await()
                } catch (error: Throwable) {
                    runCatching { pending.get()?.disconnect() }
                    opening.cancel()
                    throw error
                }
            }
        }

    private fun connect(
        machine: RemoteMachine,
        passwordOverride: String? = null,
        askSecret: RemoteSecretPrompt? = null,
        proxySession: Session? = null,
        onCreated: (Session) -> Unit = {}
    ): Session {
        require(machine.host.isNotBlank()) { "Remote host is missing." }
        require(machine.username.isNotBlank()) { "Remote user is missing." }
        AndroidSshAlgorithms.install()
        val jsch = JSch()
        if (machine.identityFile.isNotBlank()) {
            val identity = assets.resolveIdentity(machine.identityFile)
                ?: error("Identity file not found: ${machine.identityFile.substringAfterLast('/')}. Upload it in Remote Settings or fix IdentityFile.")
            addMachineSshIdentity(jsch, identity, machine.identityPassphrase, machine.sshCertificate)
        }
        val session = jsch.getSession(machine.username, machine.host, machine.port)
        val passwordMode = machine.authentication == RemoteAuthentication.PASSWORD
        val password = if (passwordMode) passwordOverride ?: machine.password else ""
        val useKeyboardInteractive = passwordMode && (machine.keyboardInteractive || password.isEmpty())
        val plainSavedPassword = passwordMode && !machine.keyboardInteractive && password.isNotEmpty()
        // UserInfo also handles an encrypted key's passphrase when public-key auth is selected.
        if (!plainSavedPassword) {
            session.setUserInfo(PasswordKeyboardInteractiveUserInfo(machine.username, password, askSecret))
        }
        if (password.isNotEmpty()) session.setPassword(password.toByteArray(Charsets.UTF_8))
        session.setConfig("StrictHostKeyChecking", if (machine.strictHostKeyChecking) "yes" else "no")
        session.setConfig(
            "PreferredAuthentications",
            machine.preferredAuthentications.ifBlank {
                if (useKeyboardInteractive) {
                // Try PAM/keyboard-interactive first. JSch's password implementation retries the
                // same method internally after a failure, which can exhaust servers that expose
                // password login only as keyboard-interactive before KBI is reached.
                    "keyboard-interactive,password"
                } else if (machine.authentication == RemoteAuthentication.PASSWORD) {
                    "password"
                } else {
                    "publickey"
                }
            }
        )
        if (machine.compression) session.setConfig("compression.s2c", "zlib,none")
        if (machine.compression) session.setConfig("compression.c2s", "zlib,none")
        // setServerAliveInterval also writes the socket read timeout, so it must come after the connect
        // timeout: the other order silently replaced the keepalive interval with the connect timeout.
        session.timeout = machine.connectTimeoutSeconds * 1_000
        if (machine.keepAliveSeconds > 0) {
            session.setServerAliveInterval(machine.keepAliveSeconds * 1_000)
            // JSch's default of 1 drops the session after a single unanswered keepalive, which a phone
            // network easily causes right after the radio wakes up.
            session.setServerAliveCountMax(KEEPALIVE_MAX_MISSES)
        }
        machine.websocketProxy?.takeIf { it.isConfigured }?.let { proxy ->
            session.setProxy(WebSocketProxy(proxy))
        }
        proxySession?.let { session.setProxy(SshJumpProxy { it }) }
        machine.sshOptions.forEach { (key, value) -> session.setConfig(key, value) }
        onCreated(session)
        try {
            session.connect(machine.connectTimeoutSeconds * 1_000)
        } catch (failure: JSchException) {
            if (askSecret == null && password.isEmpty() && machine.identityFile.isBlank() &&
                failure.isAuthenticationFailure()
            ) {
                throw IllegalStateException(
                    "The server requires authentication, but this machine has no saved password or private key. Add one in the machine settings.",
                    failure
                )
            }
            throw failure
        }
        // Keepalive off means no read timeout once connected (the connect timeout must not end idle sessions).
        if (machine.keepAliveSeconds == 0) runCatching { session.timeout = 0 }
        // Simply connecting SSH must never activate the machine's saved forwarding rules.
        return session
    }

    /**
     * Called only by the explicit Port Forward action. Stop handles allow forwards to be changed or
     * stopped without disconnecting any shell, exec or SFTP channel on the same SSH connection.
     */
    private fun applyPortForwarding(session: Session, machine: RemoteMachine): ActivePortForwards {
        val forwarding = machine.portForwarding.validated()
        val labels = mutableListOf<String>()
        val stops = mutableListOf<() -> Unit>()
        val failures = mutableListOf<String>()
        forwarding.activeLocalRules.forEach { rule ->
            runCatching { session.setPortForwardingL(LOCAL_FORWARD_BIND, rule.listenPort, rule.targetHost, rule.targetPort) }
                .onSuccess {
                    labels += "L ${rule.label()}"
                    stops += { session.delPortForwardingL(LOCAL_FORWARD_BIND, rule.listenPort) }
                }
                .onFailure {
                    failures += "L ${rule.listenPort}: ${it.message}"
                    Log.w(TAG, "Local forward ${rule.listenPort} failed", it)
                }
        }
        forwarding.activeRemoteRules.forEach { rule ->
            runCatching { session.setPortForwardingR(rule.listenPort, rule.targetHost, rule.targetPort) }
                .onSuccess {
                    labels += "R ${rule.label()}"
                    stops += { session.delPortForwardingR(rule.listenPort) }
                }
                .onFailure {
                    failures += "R ${rule.listenPort}: ${it.message}"
                    Log.w(TAG, "Remote forward ${rule.listenPort} failed", it)
                }
        }
        forwarding.activeDynamicPorts.forEach { port ->
            runCatching { RemoteSocksForwarder(session, port, machine.connectTimeoutSeconds * 1_000).also { it.start() } }
                .onSuccess {
                    labels += "SOCKS $port"
                    stops += { it.stop() }
                }
                .onFailure {
                    failures += "SOCKS $port: ${it.message}"
                    Log.w(TAG, "Dynamic forward $port failed", it)
                }
        }
        // A failed rule must not silently leave partial forwarding running.
        // Roll back opened ports and report every failing rule to the user.
        if (failures.isNotEmpty() || labels.isEmpty()) {
            ActivePortForwards(labels, stops).close()
            error("Port forwarding failed: ${failures.joinToString("; ").ifBlank { "No valid forward rules." }}")
        }
        return ActivePortForwards(labels, stops)
    }

    /** `8080→10808`, or `8080→db:3306` when the target is not this loopback. */
    private fun PortForwardRule.label(): String =
        if (targetHost == "localhost" || targetHost == "127.0.0.1") "$listenPort→$targetPort"
        else "$listenPort→$targetHost:$targetPort"

    private fun JSchException.isAuthenticationFailure(): Boolean {
        val text = message.orEmpty().lowercase()
        return "auth fail" in text || "auth cancel" in text || "userauth fail" in text
    }

    private fun Int?.orZero(): Int = this ?: 0

    companion object {
        private const val TAG = "RemoteMachineService"
        private const val LOCAL_FORWARD_BIND = "127.0.0.1"
        private const val KEEPALIVE_MAX_MISSES = 3
        const val CLOUD_ENDPOINT_CACHE_TTL_MILLIS = 15 * 60 * 1_000L
        /** A persistent SSH connection with no command or terminal using it is closed after this. */
        const val IDLE_DISCONNECT_MILLIS = 5 * 60 * 1_000L
        const val CONNECTION_POLL_MILLIS = 5_000L
        const val GOOGLE_AUTHORIZATION_ENDPOINT = "https://accounts.google.com/o/oauth2/v2/auth"
        const val GOOGLE_TOKEN_ENDPOINT = "https://oauth2.googleapis.com/token"
        const val GOOGLE_SCOPE = REMOTE_GOOGLE_CLOUD_SCOPE
        const val ANDROID_ACCESS_TOKEN_LIFETIME_MS = 3_300_000L
        val CLOUD_SHELL_SUPPORTED_KEY_ALGORITHMS = setOf(
            "ssh-rsa",
            "ecdsa-sha2-nistp256",
            "ecdsa-sha2-nistp384",
            "ecdsa-sha2-nistp521"
        )
    }
}
