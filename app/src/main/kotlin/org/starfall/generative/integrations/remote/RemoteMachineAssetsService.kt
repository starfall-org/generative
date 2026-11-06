package org.starfall.generative.integrations.remote

import android.content.Context
import android.net.Uri
import android.system.Os
import com.jcraft.jsch.JSch
import java.io.File
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import org.starfall.generative.shared.model.GCloudOAuthClient
import org.starfall.generative.shared.model.GoogleOAuthClientType
import org.starfall.generative.shared.model.RemoteIdentityKey
import org.starfall.generative.shared.model.RemoteMachine



/**
 * Limit permission repair to private keys owned by the app. Never chmod an arbitrary IdentityFile
 * path provided by the user. This also recovers keys restored with the old write-only permissions.
 */
internal fun ensureManagedIdentityPermissions(filesDir: File, file: File) {
    val identityDirectory = File(filesDir, "remote-machines/identities").canonicalFile
    if (file.canonicalFile.parentFile == identityDirectory) {
        Os.chmod(file.absolutePath, 0b110_000_000) // owner read + write, no group/other access
    }
}

/** Handles SSH identity files and OAuth client-file parsing for remote machines. */
internal class RemoteMachineAssetsService(
    context: Context,
    private val store: RemoteMachineStore
) {
    private val appContext = context.applicationContext
    private val json = Json { ignoreUnknownKeys = true }
    private val state = store.state

    companion object {
        private val CLOUD_SHELL_SUPPORTED_KEY_ALGORITHMS = setOf(
            "ssh-rsa", "ecdsa-sha2-nistp256", "ecdsa-sha2-nistp384", "ecdsa-sha2-nistp521"
        )
    }

    /** Imports a Google Desktop OAuth client file without persisting it until the user confirms it. */
    suspend fun parseOAuthClientJson(uri: Uri): GCloudOAuthClient = withContext(Dispatchers.IO) {
        val displayName = appContext.contentResolver.query(uri, arrayOf("_display_name"), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }?.substringBeforeLast('.')?.takeIf { it.isNotBlank() } ?: "Google Desktop Client"
        val raw = appContext.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
            ?: error("Could not read the OAuth client JSON file.")
        val root = json.parseToJsonElement(raw).jsonObject
        val installed = root["installed"]?.jsonObject
            ?: if (root["web"] != null) error("Web OAuth clients are not supported. Choose a Google Desktop app client JSON.")
            else error("The selected file is not a Google OAuth Desktop client JSON.")
        val clientId = installed["client_id"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        require(clientId.isNotBlank()) { "The selected OAuth client JSON has no installed.client_id." }
        GCloudOAuthClient(
            id = UUID.randomUUID().toString(),
            name = displayName,
            clientId = clientId,
            clientSecret = installed["client_secret"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty(),
            type = GoogleOAuthClientType.DESKTOP
        )
    }

    /** Uploads an SSH private key once; machines reference it through IdentityFile. */
    suspend fun importIdentityKey(uri: Uri): RemoteIdentityKey = withContext(Dispatchers.IO) {
        val resolver = appContext.contentResolver
        val displayName = resolver.query(uri, arrayOf("_display_name"), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "key-${UUID.randomUUID().toString().take(8)}"
        // Keep the path stable when replacing a key by the same display name: machines that
        // already reference this IdentityFile path continue to use the replacement.
        val id = state.value.identities.firstOrNull { it.name == displayName }?.id
            ?: UUID.randomUUID().toString()
        val directory = File(appContext.filesDir, "remote-machines/identities").apply { mkdirs() }
        val target = File(directory, id)
        if (target.isFile) ensureManagedIdentityPermissions(appContext.filesDir, target)
        resolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        } ?: error("Could not read the selected identity file.")
        ensureManagedIdentityPermissions(appContext.filesDir, target)
        // A replaced private key may no longer match its old public key, so that one is dropped too.
        state.value.identities.firstOrNull { it.id == id }?.publicKeyPath?.takeIf(String::isNotBlank)
            ?.let { runCatching { File(it).delete() } }
        val key = RemoteIdentityKey(id, displayName, target.absolutePath)
        // Re-uploading a key with the same name replaces it, so existing IdentityFile names keep working.
        val replaced = state.value.identities.filter { it.name == displayName && it.id != id }
        store.update { it.copy(identities = it.identities.filterNot { existing -> existing.name == displayName } + key) }
        replaced.forEach { runCatching { File(it.path).delete() } }
        key
    }

    /**
     * Attaches an OpenSSH public key (.pub file) to an uploaded private key. Google Cloud Shell needs
     * the public key to authorize the connection, so it is used instead of deriving one.
     */
    suspend fun importPublicKey(keyId: String, uri: Uri): RemoteIdentityKey = withContext(Dispatchers.IO) {
        val key = state.value.identities.firstOrNull { it.id == keyId } ?: error("SSH key not found.")
        val text = appContext.contentResolver.openInputStream(uri)?.use { it.bufferedReader().readText() }
            ?: error("Could not read the selected public key file.")
        val normalized = normalizeOpenSshPublicKey(text)
        val directory = File(appContext.filesDir, "remote-machines/identities").apply { mkdirs() }
        val target = File(directory, "$keyId.pub")
        target.writeText(normalized + "\n")
        val updated = key.copy(publicKeyPath = target.absolutePath)
        store.update { current ->
            current.copy(identities = current.identities.map { if (it.id == keyId) updated else it })
        }
        updated
    }

    suspend fun deleteIdentityKey(id: String) {
        val key = state.value.identities.firstOrNull { it.id == id }
        if (key != null) {
            require(state.value.machines.none { machine ->
                val pointer = machine.identityFile.trim()
                pointer == key.path || pointer == key.name || pointer.substringAfterLast('/') == key.name
            }) { "Remove this key from IdentityFile before deleting it." }
        }
        store.update { it.copy(identities = it.identities.filterNot { existing -> existing.id == id }) }
        key?.let {
            runCatching { File(it.path).delete() }
            if (it.publicKeyPath.isNotBlank()) runCatching { File(it.publicKeyPath).delete() }
        }
    }

    /**
     * Resolves a machine's IdentityFile pointer: a real file path, or the name of an uploaded key
     * (also written as `~/.ssh/<name>`).
     */
    internal fun resolveIdentity(pointer: String): File? {
        val trimmed = pointer.trim()
        File(trimmed).takeIf { it.isFile }?.let { file ->
            if (state.value.identities.any { it.path == file.absolutePath }) {
                ensureManagedIdentityPermissions(appContext.filesDir, file)
            }
            return file
        }
        val name = trimmed.substringAfterLast('/')
        return state.value.identities.firstOrNull { it.name == trimmed || it.name == name }
            ?.let { File(it.path) }?.takeIf { it.isFile }
            ?.also { ensureManagedIdentityPermissions(appContext.filesDir, it) }
    }

    /** Converts the configured private key to the OpenSSH public-key line Cloud Shell expects. */
    /** The public key uploaded next to this private key in settings, if any. */
    private fun uploadedPublicKey(privateKey: File): String? {
        val identity = state.value.identities.firstOrNull { it.path == privateKey.absolutePath } ?: return null
        val file = identity.publicKeyPath.takeIf(String::isNotBlank)?.let(::File)?.takeIf { it.isFile } ?: return null
        val normalized = normalizeOpenSshPublicKey(file.readText())
        val algorithm = normalized.substringBefore(' ')
        require(algorithm in CLOUD_SHELL_SUPPORTED_KEY_ALGORITHMS) {
            "Google Cloud Shell does not support SSH public-key type $algorithm. Use an RSA or ECDSA key."
        }
        return normalized
    }

    internal fun cloudShellPublicKey(machine: RemoteMachine): String? {
        val identityFile = machine.identityFile.trim().takeIf(String::isNotBlank) ?: return null
        val file = resolveIdentity(identityFile)
            ?: error("Identity file not found: ${identityFile.substringAfterLast('/')}. Upload it in Remote Settings or fix IdentityFile.")
        uploadedPublicKey(file)?.let { return it }
        val jsch = JSch()
        if (machine.identityPassphrase.isBlank()) jsch.addIdentity(file.absolutePath)
        else jsch.addIdentity(file.absolutePath, machine.identityPassphrase.toByteArray(Charsets.UTF_8))
        val identity = jsch.identityRepository.identities.firstOrNull()
            ?: error("Could not read the public key for IdentityFile.")
        val algorithm = identity.algName
        require(algorithm in CLOUD_SHELL_SUPPORTED_KEY_ALGORITHMS) {
            "Google Cloud Shell does not support SSH public-key type $algorithm. Use an RSA or ECDSA key."
        }
        val publicKeyBlob = identity.publicKeyBlob
            ?: error("Could not read the public key for IdentityFile.")
        return "$algorithm ${Base64.getEncoder().encodeToString(publicKeyBlob)}"
    }

}
