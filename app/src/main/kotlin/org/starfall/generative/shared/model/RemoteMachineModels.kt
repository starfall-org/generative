package org.starfall.generative.shared.model

import kotlinx.serialization.Serializable

/** The connection types currently supported by Remote Machines. */
@Serializable
enum class RemoteMachineType {
    SSH,
    GCLOUD_SHELL
}

@Serializable
enum class RemoteAuthentication {
    PASSWORD,
    PRIVATE_KEY
}

/** Settings for a WebSocket endpoint that tunnels the SSH byte stream. */
@Serializable
data class WebSocketProxyConfig(
    val url: String = "",
    val headers: Map<String, String> = emptyMap(),
    val connectTimeoutSeconds: Int = 15,
    val skipCertificateVerification: Boolean = false,
    /** Optional PEM-encoded CA certificate used in addition to the platform trust store. */
    val caCertificatePem: String = "",
    val handshakeTimeoutSeconds: Int = 15,
    val readTimeoutSeconds: Int = 0,
    val writeTimeoutSeconds: Int = 0,
    val closeTimeoutSeconds: Int = 5,
    val subProtocols: List<String> = emptyList()
) {
    fun normalized(): WebSocketProxyConfig {
        val cleanUrl = url.trim()
        require(cleanUrl.isBlank() || cleanUrl.startsWith("ws://") || cleanUrl.startsWith("wss://")) {
            "WebSocket proxy URL must start with ws:// or wss://."
        }
        return copy(
            url = cleanUrl,
            headers = headers.mapKeys { it.key.trim() }.filterKeys(String::isNotBlank)
                .mapValues { it.value },
            connectTimeoutSeconds = connectTimeoutSeconds.coerceIn(1, 300),
            handshakeTimeoutSeconds = handshakeTimeoutSeconds.coerceIn(1, 300),
            readTimeoutSeconds = readTimeoutSeconds.coerceIn(0, 3_600),
            writeTimeoutSeconds = writeTimeoutSeconds.coerceIn(0, 3_600),
            closeTimeoutSeconds = closeTimeoutSeconds.coerceIn(1, 300),
            subProtocols = subProtocols.map(String::trim).filter(String::isNotBlank).distinct()
        )
    }

    val isConfigured: Boolean get() = url.isNotBlank()
}

/** One -L / -R style rule: [listenPort] accepts connections that are forwarded to [targetHost]:[targetPort]. */
data class PortForwardRule(val listenPort: Int, val targetHost: String, val targetPort: Int)

/**
 * Port forwarding switches and rules for a machine. A type that is disabled keeps its rules but applies nothing.
 * Local and remote rules are `listenPort[:targetPort]` (or `listenPort:targetHost:targetPort`); dynamic (SOCKS5) entries are plain local ports.
 */
@Serializable
data class PortForwardingConfig(
    val localEnabled: Boolean = false,
    val localRules: List<String> = emptyList(),
    val remoteEnabled: Boolean = false,
    val remoteRules: List<String> = emptyList(),
    val dynamicEnabled: Boolean = false,
    val dynamicPorts: List<Int> = emptyList()
) {
    val activeLocalRules: List<PortForwardRule>
        get() = if (localEnabled) localRules.mapNotNull(::parsePortForwardRule) else emptyList()
    val activeRemoteRules: List<PortForwardRule>
        get() = if (remoteEnabled) remoteRules.mapNotNull(::parsePortForwardRule) else emptyList()
    val activeDynamicPorts: List<Int>
        get() = if (dynamicEnabled) dynamicPorts else emptyList()
    val hasActiveForwards: Boolean
        get() = activeLocalRules.isNotEmpty() || activeRemoteRules.isNotEmpty() || activeDynamicPorts.isNotEmpty()

    /** Trims entries, drops blanks and duplicates. Throws [IllegalArgumentException] with a user-facing message. */
    fun validated(): PortForwardingConfig {
        val local = localRules.map(String::trim).filter(String::isNotBlank).distinct()
        val remote = remoteRules.map(String::trim).filter(String::isNotBlank).distinct()
        local.forEach { requireNotNull(parsePortForwardRule(it)) { "Invalid local forward \"$it\". Use e.g. 8080 or 8080:10808." } }
        remote.forEach { requireNotNull(parsePortForwardRule(it)) { "Invalid remote forward \"$it\". Use e.g. 8080 or 8080:10808." } }
        val dynamic = dynamicPorts.distinct()
        dynamic.forEach { require(it in 1..65535) { "Invalid SOCKS port $it. Use 1-65535." } }
        val localListen = local.mapNotNull(::parsePortForwardRule).map { it.listenPort } + dynamic
        val duplicate = localListen.groupingBy { it }.eachCount().entries.firstOrNull { it.value > 1 }
        require(duplicate == null) { "Local port ${duplicate?.key} is used by more than one forward." }
        val remoteDuplicate = remote.mapNotNull(::parsePortForwardRule).groupingBy { it.listenPort }.eachCount()
            .entries.firstOrNull { it.value > 1 }
        require(remoteDuplicate == null) { "Remote port ${remoteDuplicate?.key} is used by more than one forward." }
        return copy(localRules = local, remoteRules = remote, dynamicPorts = dynamic)
    }
}

/**
 * Parses a forward rule: `8080` (same port on both sides), `8080:10808` (listen on 8080, target port 10808) or the
 * explicit `8080:host:10808`. `->`, `>` and `→` work as separators too. The host defaults to localhost.
 * Returns null when malformed or a port is outside 1-65535.
 */
fun parsePortForwardRule(text: String): PortForwardRule? {
    val parts = text.trim().replace("->", ":").replace("→", ":").replace(">", ":").split(':').map(String::trim)
    val (listenText, host, portText) = when (parts.size) {
        1 -> Triple(parts[0], "localhost", parts[0])
        2 -> Triple(parts[0], "localhost", parts[1])
        3 -> Triple(parts[0], parts[1], parts[2])
        else -> return null
    }
    val listen = listenText.toIntOrNull() ?: return null
    val port = portText.toIntOrNull() ?: return null
    if (host.isBlank() || listen !in 1..65535 || port !in 1..65535) return null
    return PortForwardRule(listen, host, port)
}

/** Runtime machine view. SSH settings are persisted directly in the encrypted Remote Machines store. */
@Serializable
data class RemoteMachine(
    val id: String,
    val name: String,
    val type: RemoteMachineType = RemoteMachineType.SSH,
    val host: String = "",
    val port: Int = 22,
    val username: String = "",
    /** Exact credential lookup key; never a wildcard. Cloud Shell may refresh it with its endpoint. */
    val passwordReference: String = "",
    val authentication: RemoteAuthentication = RemoteAuthentication.PRIVATE_KEY,
    val password: String = "",
    /** Use SSH keyboard-interactive/PAM prompts instead of only the plain password method. */
    val keyboardInteractive: Boolean = true,
    val identityFile: String = "",
    val identityPassphrase: String = "",
    /** Uploaded OpenSSH user certificate; embedded so encrypted settings and backups remain self-contained. */
    val sshCertificate: String = "",
    val sshCertificateName: String = "",
    /** Effective SSH config value. Empty means use the app's auth-mode default. */
    val preferredAuthentications: String = "",
    val strictHostKeyChecking: Boolean = false,
    /** Additional JSch session options. Empty by default and only shown after the user adds one. */
    val sshOptions: Map<String, String> = emptyMap(),
    val websocketProxy: WebSocketProxyConfig? = null,
    /** ID of another Remote Machine used as an SSH direct-tcpip jump host. */
    val proxyMachineId: String = "",
    val connectTimeoutSeconds: Int = 15,
    val keepAliveSeconds: Int = 30,
    val compression: Boolean = false,
    val oauthClientId: String = "",
    val environmentName: String = "default",
    val gcloudUsername: String = "",
    /** Root folder exposed by the machine's Files provider. Blank or [REMOTE_SFTP_HOME] means $HOME. */
    val sftpDirectory: String = REMOTE_SFTP_HOME,
    val portForwarding: PortForwardingConfig = PortForwardingConfig(),
    val aiToolsEnabled: Boolean = true,
    /** Per-tool switches configured on the machine. Missing entries are enabled for compatibility. */
    val aiTools: Map<String, Boolean> = emptyMap(),
    val sortOrder: Int = Int.MAX_VALUE
) {
    val displayHost: String
        get() = host.ifBlank { if (type == RemoteMachineType.GCLOUD_SHELL) "Cloud Shell IP not loaded" else "Host not configured" }

    fun supportedToolNames(): List<String> = remoteMachineToolNames(type)

    fun isToolEnabled(toolName: String): Boolean = aiToolsEnabled &&
        (aiTools[toolName] ?: if (toolName == REMOTE_TOOL_UPDATE_IP) aiTools[REMOTE_TOOL_START_MACHINE] else null) != false
}

@Serializable
enum class GoogleOAuthClientType { DESKTOP, ANDROID }

@Serializable
data class GCloudOAuthClient(
    val id: String,
    val name: String,
    val clientId: String,
    val clientSecret: String = "",
    val type: GoogleOAuthClientType = GoogleOAuthClientType.DESKTOP
)

@Serializable
data class RemoteOAuthToken(
    val accessToken: String,
    val refreshToken: String = "",
    val tokenType: String = "Bearer",
    val expiresAt: Long? = null
)

/** Legacy record: executables are now managed in Settings; kept only to migrate old saved state. */
@Serializable
data class RemoteBinary(
    val id: String,
    val name: String,
    val path: String
)

data class RemoteBinaryTestResult(
    val output: String,
    val exitCode: Int,
    val timedOut: Boolean,
    val outputTruncated: Boolean
)

/**
 * An uploaded SSH private key kept once in app storage. Machines never embed key material; they
 * only point at a key through their `IdentityFile` (the key's [path] or its [name]).
 */
@Serializable
data class RemoteIdentityKey(
    val id: String,
    val name: String,
    val path: String,
    /** Uploaded OpenSSH public key (.pub) belonging to this private key; empty when none. */
    val publicKeyPath: String = ""
)

/** Everything belonging to the extension is persisted outside of the application's Room schema. */
@Serializable
data class RemoteMachineState(
    val machines: List<RemoteMachine> = emptyList(),
    val sshConfig: String = "",
    val oauthClients: List<GCloudOAuthClient> = emptyList(),
    val oauthTokens: Map<String, RemoteOAuthToken> = emptyMap(),
    /** Legacy, see [RemoteBinary]. Always empty after the one-time migration. */
    val binaries: List<RemoteBinary> = emptyList(),
    val identities: List<RemoteIdentityKey> = emptyList()
)

/** Tools offered for a machine type. Terminal tools never create a connection implicitly. */
fun remoteMachineToolNames(type: RemoteMachineType): List<String> = when (type) {
    RemoteMachineType.SSH -> listOf(
        REMOTE_TOOL_OPEN_TERMINAL,
        REMOTE_TOOL_CLOSE_TERMINAL,
        REMOTE_TOOL_LIST_TERMINAL,
        REMOTE_TOOL_READ_TERMINAL,
        REMOTE_TOOL_EXECUTE,
        REMOTE_TOOL_DISCONNECT
    )
    RemoteMachineType.GCLOUD_SHELL ->
        listOf(
            REMOTE_TOOL_UPDATE_IP,
            REMOTE_TOOL_OPEN_TERMINAL,
            REMOTE_TOOL_CLOSE_TERMINAL,
            REMOTE_TOOL_LIST_TERMINAL,
            REMOTE_TOOL_READ_TERMINAL,
            REMOTE_TOOL_EXECUTE,
            REMOTE_TOOL_DISCONNECT
        )
}

/** SFTP directory token that expands to the signed-in user's home directory. */
const val REMOTE_SFTP_HOME = "\$HOME"

const val REMOTE_TOOL_OPEN_TERMINAL = "open_terminal"
const val REMOTE_TOOL_CLOSE_TERMINAL = "close_terminal"
const val REMOTE_TOOL_LIST_TERMINAL = "list_terminal"
const val REMOTE_TOOL_READ_TERMINAL = "read_terminal"
const val REMOTE_TOOL_EXECUTE = "execute"
const val REMOTE_TOOL_DISCONNECT = "disconnect"
const val REMOTE_TOOL_START_MACHINE = "start_machine"
const val REMOTE_TOOL_UPDATE_IP = "update_ip"
