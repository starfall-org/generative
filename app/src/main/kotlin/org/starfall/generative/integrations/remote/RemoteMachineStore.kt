package org.starfall.generative.integrations.remote

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.starfall.generative.shared.storage.preferences.MigratedStringPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.starfall.generative.shared.model.GCloudOAuthClient
import org.starfall.generative.shared.model.RemoteAuthentication
import org.starfall.generative.shared.model.RemoteBinary
import org.starfall.generative.shared.model.RemoteIdentityKey
import org.starfall.generative.shared.model.RemoteMachine
import org.starfall.generative.shared.model.RemoteMachineState
import org.starfall.generative.shared.model.RemoteMachineType
import org.starfall.generative.shared.model.RemoteOAuthToken
import org.starfall.generative.shared.model.WebSocketProxyConfig

internal const val REMOTE_MACHINE_STORE_PREFERENCES = "remote_machines"

internal fun remoteMachineSshConfigFile(context: Context): File =
    File(File(context.applicationContext.filesDir, "remote-machines"), "ssh_config")

internal fun remotePasswordReference(username: String, host: String, port: Int): String {
    val address = host.trim().let { if (it.contains(':') && !it.startsWith('[')) "[$it]" else it }
    return "${username.trim()}@$address:${port.coerceIn(1, 65535)}"
}

@Serializable
private data class PersistedRemoteMachineState(
    val schemaVersion: Int = 4,
    /** Machine settings are now stored directly instead of being reconstructed from ssh_config. */
    val machines: List<RemoteMachine> = emptyList(),
    val passwords: Map<String, String> = emptyMap(),
    val oauthClients: List<GCloudOAuthClient> = emptyList(),
    val oauthTokens: Map<String, RemoteOAuthToken> = emptyMap(),
    val binaries: List<RemoteBinary> = emptyList(),
    val identities: List<RemoteIdentityKey> = emptyList()
)

/** Schema 3, read only. SSH settings in this version were hydrated from ssh_config. */
@Serializable
private data class PersistedRemoteMachineStateV3(
    val schemaVersion: Int = 3,
    val machines: List<PersistedMachine> = emptyList(),
    val passwords: Map<String, String> = emptyMap(),
    val oauthClients: List<GCloudOAuthClient> = emptyList(),
    val oauthTokens: Map<String, RemoteOAuthToken> = emptyMap(),
    val binaries: List<RemoteBinary> = emptyList(),
    val identities: List<RemoteIdentityKey> = emptyList()
)

@Serializable
private data class PersistedMachine(
    val ssh: PersistedSshMachine? = null,
    val cloud: PersistedGCloudMachine? = null
)

@Serializable
private data class PersistedSshMachine(
    val id: String,
    val name: String,
    val host: String,
    val username: String,
    val passwordReference: String
)

@Serializable
private data class PersistedGCloudMachine(
    val id: String,
    val name: String,
    val oauthClientId: String,
    val environmentName: String,
    val aiToolsEnabled: Boolean,
    val aiTools: Map<String, Boolean>,
    val passwordReference: String = ""
)

/** Previous persisted schema, read only for an in-place migration. */
@Serializable
private data class PersistedRemoteMachineStateV2(
    val schemaVersion: Int = 2,
    val machines: List<PersistedMachineV2> = emptyList(),
    val oauthClients: List<GCloudOAuthClient> = emptyList(),
    val oauthTokens: Map<String, RemoteOAuthToken> = emptyMap(),
    val binaries: List<RemoteBinary> = emptyList(),
    val identities: List<RemoteIdentityKey> = emptyList()
)

@Serializable
private data class PersistedMachineV2(
    val ssh: PersistedSshMachineV2? = null,
    val cloud: RemoteMachine? = null
)

@Serializable
private data class PersistedSshMachineV2(
    val id: String,
    val name: String,
    val host: String,
    val username: String,
    val password: String
)

/**
 * Independent persistence for the Remote Machines extension.
 *
 * Keeping this store separate means adding/removing the feature never requires a Room migration.
 * The complete JSON payload is encrypted because it contains SSH passwords, key passphrases and
 * OAuth refresh tokens.
 */
class RemoteMachineStore(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = MigratedStringPreferences.get(appContext, REMOTE_MACHINE_STORE_PREFERENCES)
    private val sshConfigFile = remoteMachineSshConfigFile(appContext)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val lock = Mutex()
    private var needsMigration = false
    private var passwords = emptyMap<String, String>()
    private val _state = MutableStateFlow(RemoteMachineState())
    val state: StateFlow<RemoteMachineState> = _state.asStateFlow()
    private val initialized = CompletableDeferred<Unit>()
    private val initializationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        // Keystore decrypt, legacy DataStore migration, and file I/O may be slow.
        // Never perform these operations in a UI-owned constructor.
        initializationScope.launch {
            try {
                lock.withLock {
                    val loaded = readState()
                    if (needsMigration) {
                        writeState(loaded)
                        // Only remove old SSH configuration after the encrypted save commits.
                        runCatching { sshConfigFile.delete() }
                    }
                    _state.value = loaded
                }
                initialized.complete(Unit)
            } catch (error: Throwable) {
                initialized.completeExceptionally(error)
                if (error is CancellationException) throw error
            }
        }
    }

    /** A backup or mutation must never treat an uninitialized state as an empty collection. */
    suspend fun awaitInitialized() { initialized.await() }

    suspend fun update(transform: (RemoteMachineState) -> RemoteMachineState) {
        awaitInitialized()
        withContext(Dispatchers.IO) {
            lock.withLock {
                val updated = transform(_state.value)
                writeState(updated)
                runCatching { sshConfigFile.delete() }
                _state.value = updated
            }
        }
    }

    suspend fun clear() {
        update { RemoteMachineState() }
    }

    suspend fun replaceState(state: RemoteMachineState) {
        awaitInitialized()
        withContext(Dispatchers.IO) {
            lock.withLock {
                writeState(state)
                runCatching { sshConfigFile.delete() }
                _state.value = state
            }
        }
    }

    private fun readState(): RemoteMachineState {
        val configFileText = readSshConfigFile()
        val stored = preferences.getString(KEY, null)
            ?: return migrateConfigOnly(configFileText.orEmpty())
        return runCatching {
            val payload = if (stored.startsWith(PREFIX)) decrypt(stored) else stored
            val root = json.parseToJsonElement(payload).jsonObject
            val schemaVersion = root["schemaVersion"]?.jsonPrimitive?.content?.toIntOrNull()
            if (schemaVersion == 4) {
                val persisted = json.decodeFromString<PersistedRemoteMachineState>(payload)
                passwords = persisted.passwords
                if (configFileText != null) needsMigration = true
                RemoteMachineState(
                    machines = persisted.machines.mapIndexed { index, machine ->
                        hydrateMachine(machine, index)
                    },
                    oauthClients = persisted.oauthClients,
                    oauthTokens = persisted.oauthTokens,
                    // Executable binaries were removed from Remote Machines. Do not resurrect the
                    // old index even if a user restores an older payload.
                    binaries = emptyList(),
                    identities = persisted.identities
                )
            } else if (schemaVersion == 3) {
                val persisted = json.decodeFromString<PersistedRemoteMachineStateV3>(payload)
                passwords = persisted.passwords
                val baseMachines = persisted.machines.mapIndexedNotNull { index, item ->
                    item.cloud?.let { cloud ->
                        RemoteMachine(
                            id = cloud.id,
                            name = cloud.name,
                            type = RemoteMachineType.GCLOUD_SHELL,
                            port = 6_000,
                            passwordReference = cloud.passwordReference,
                            password = passwords[cloud.passwordReference].orEmpty(),
                            oauthClientId = cloud.oauthClientId,
                            environmentName = cloud.environmentName,
                            aiToolsEnabled = cloud.aiToolsEnabled,
                            aiTools = cloud.aiTools,
                            sortOrder = index
                        )
                    } ?: item.ssh?.let { ssh ->
                        RemoteMachine(
                            id = ssh.id,
                            name = ssh.name,
                            host = ssh.host,
                            username = ssh.username,
                            passwordReference = ssh.passwordReference,
                            password = passwords[ssh.passwordReference].orEmpty(),
                            sortOrder = index
                        )
                    }
                }
                val originalConfig = configFileText.orEmpty()
                val syncedBase = SshConfigParser.syncMachines(baseMachines, originalConfig)
                val config = syncedBase.filter { it.type == RemoteMachineType.GCLOUD_SHELL }
                    .fold(originalConfig) { text, machine ->
                        SshConfigParser.upsertMachine(text, machine.copy(host = "", port = 6_000))
                    }
                needsMigration = true
                val machines = SshConfigParser.syncMachines(syncedBase, config)
                RemoteMachineState(
                    machines = machines.mapIndexed { index, machine -> sanitizeMachine(machine, index) },
                    oauthClients = persisted.oauthClients,
                    oauthTokens = persisted.oauthTokens,
                    binaries = emptyList(),
                    identities = persisted.identities
                )
            } else if (schemaVersion == 2) {
                val persisted = json.decodeFromString<PersistedRemoteMachineStateV2>(payload)
                needsMigration = true
                val oldMachines = persisted.machines.mapIndexedNotNull { index, item ->
                    item.cloud?.let { cloud ->
                        val reference = cloud.passwordReference.ifBlank {
                            cloud.host.takeIf(String::isNotBlank)?.let {
                                remotePasswordReference(cloud.username.ifBlank { cloud.gcloudUsername }, it, 6_000)
                            }.orEmpty()
                        }
                        cloud.copy(host = "", port = 6_000, passwordReference = reference, sortOrder = index)
                    }
                        ?: item.ssh?.let { ssh ->
                            RemoteMachine(
                                id = ssh.id,
                                name = ssh.name,
                                host = ssh.host,
                                username = ssh.username,
                                passwordReference = remotePasswordReference(ssh.username, ssh.host, 22),
                                password = ssh.password,
                                sortOrder = index
                            )
                        }
                }
                val config = canonicalizeConfig(oldMachines, configFileText.orEmpty())
                val machines = SshConfigParser.syncMachines(oldMachines, config)
                passwords = collectPasswords(machines)
                RemoteMachineState(
                    machines = machines.mapIndexed { index, machine -> sanitizeMachine(machine, index) },
                    oauthClients = persisted.oauthClients,
                    oauthTokens = persisted.oauthTokens,
                    binaries = emptyList(),
                    identities = persisted.identities
                )
            } else {
                // Migrate the old encrypted state: it contained full SSH machine records and the
                // config text. The config file becomes canonical, then writeState stores only the
                // minimal SSH records on disk.
                val legacy = json.decodeFromString<RemoteMachineState>(payload)
                needsMigration = true
                val existingConfig = configFileText ?: legacy.sshConfig
                val machines = legacy.machines.map { machine ->
                    if (machine.type == RemoteMachineType.GCLOUD_SHELL) {
                        val username = machine.username.ifBlank { machine.gcloudUsername }
                        val reference = machine.passwordReference.ifBlank {
                            machine.host.takeIf(String::isNotBlank)?.let { remotePasswordReference(username, it, 6_000) }.orEmpty()
                        }
                        machine.copy(
                            host = "",
                            port = 6_000,
                            username = username,
                            gcloudUsername = username,
                            passwordReference = reference
                        )
                    } else machine
                }
                val config = canonicalizeConfig(machines, existingConfig)
                val synced = SshConfigParser.syncMachines(machines, config)
                passwords = collectPasswords(synced)
                RemoteMachineState(
                    machines = synced.mapIndexed { index, machine -> sanitizeMachine(machine, index) },
                    oauthClients = legacy.oauthClients,
                    oauthTokens = legacy.oauthTokens,
                    binaries = emptyList(),
                    identities = legacy.identities
                )
            }
        }.getOrDefault(migrateConfigOnly(configFileText.orEmpty()))
    }

    private fun writeState(state: RemoteMachineState) {
        val passwordReferences = collectPasswords(state.machines)
        val persisted = PersistedRemoteMachineState(
            machines = state.machines.map { machine ->
                sanitizeMachine(machine, machine.sortOrder).copy(password = "", identityPassphrase = "")
            },
            passwords = passwordReferences,
            oauthClients = state.oauthClients,
            oauthTokens = state.oauthTokens,
            binaries = emptyList(),
            identities = state.identities
        )
        val payload = encrypt(json.encodeToString(persisted))
        preferences.putString(KEY, payload)
    }

    private fun hydrateMachine(machine: RemoteMachine, index: Int): RemoteMachine {
        val secret = passwords[machine.passwordReference]
            ?: if (machine.passwordReference.isBlank()) {
                val reference = remotePasswordReference(machine.username, machine.host, machine.port)
                passwords[reference]
            } else null
        return sanitizeMachine(
            machine.copy(
                password = if (machine.authentication == RemoteAuthentication.PASSWORD) secret.orEmpty() else "",
                identityPassphrase = if (machine.authentication == RemoteAuthentication.PRIVATE_KEY) secret.orEmpty() else "",
                sortOrder = if (machine.sortOrder == Int.MAX_VALUE) index else machine.sortOrder
            ),
            index
        )
    }

    private fun sanitizeMachine(machine: RemoteMachine, index: Int): RemoteMachine = machine.copy(
        websocketProxy = machine.websocketProxy?.normalized()?.takeIf { it.isConfigured },
        sortOrder = if (machine.sortOrder == Int.MAX_VALUE) index else machine.sortOrder
    )

    private fun migrateConfigOnly(config: String): RemoteMachineState {
        if (config.isBlank()) return RemoteMachineState()
        needsMigration = true
        val machines = SshConfigParser.parse(config)
            .flatMap { host ->
                host.aliases.filterNot { alias -> alias.startsWith('!') || '*' in alias || '?' in alias }
                    .map { alias -> SshConfigParser.find(config, alias)?.let(SshConfigParser::toMachine) }
            }
            .filterNotNull()
            .distinctBy { it.name.lowercase() }
            .mapIndexed { index, machine -> sanitizeMachine(machine, index) }
        passwords = collectPasswords(machines)
        return RemoteMachineState(machines = machines)
    }

    private fun canonicalizeConfig(machines: List<RemoteMachine>, initialConfig: String): String =
        machines.fold(initialConfig) { text, machine ->
            if (SshConfigParser.hasExplicitAlias(text, machine.name)) {
                if (machine.type == RemoteMachineType.GCLOUD_SHELL) {
                    SshConfigParser.upsertMachine(text, machine.copy(host = "", port = 6_000))
                } else text
            } else {
                SshConfigParser.upsertMachine(text, machine)
            }
        }

    private fun collectPasswords(machines: List<RemoteMachine>): Map<String, String> =
        machines.asSequence()
            .mapNotNull { machine ->
                val secret = if (machine.authentication == RemoteAuthentication.PRIVATE_KEY) {
                    machine.identityPassphrase
                } else machine.password
                val reference = when (machine.type) {
                    RemoteMachineType.SSH -> remotePasswordReference(machine.username, machine.host, machine.port)
                    RemoteMachineType.GCLOUD_SHELL -> machine.passwordReference.ifBlank {
                        machine.host.takeIf(String::isNotBlank)?.let {
                            remotePasswordReference(machine.username.ifBlank { machine.gcloudUsername }, it, 6_000)
                        }.orEmpty()
                    }
                }
                secret.takeIf { it.isNotEmpty() && reference.isNotBlank() }?.let {
                    reference to it
                }
            }
            .toMap()

    private fun readSshConfigFile(): String? =
        if (sshConfigFile.isFile) runCatching { sshConfigFile.readText(Charsets.UTF_8) }.getOrNull() else null

    private fun key(create: Boolean): SecretKey = synchronized(KEY_LOCK) {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return@synchronized it }
        check(create) { "Remote Machines encryption key is unavailable." }
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build()
            )
        }.generateKey()
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(true))
        val bytes = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        return PREFIX + Base64.getEncoder().encodeToString(bytes)
    }

    private fun decrypt(value: String): String {
        val bytes = Base64.getDecoder().decode(value.removePrefix(PREFIX))
        require(bytes.size > GCM_IV_LENGTH) { "Invalid Remote Machines payload." }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            key(false),
            GCMParameterSpec(128, bytes.copyOfRange(0, GCM_IV_LENGTH))
        )
        return String(cipher.doFinal(bytes, GCM_IV_LENGTH, bytes.size - GCM_IV_LENGTH), Charsets.UTF_8)
    }

    private companion object {
        const val KEY = "state"
        const val KEY_ALIAS = "generative.remote-machines.v1"
        const val PREFIX = "keystore:v1:"
        const val GCM_IV_LENGTH = 12
        val KEY_LOCK = Any()
    }
}
