package org.starfall.generative.features.appdata.logic

import org.starfall.generative.database.repositories.CustomHttpToolRepository
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.starfall.generative.R
import org.starfall.generative.features.appdata.model.AppDataBackup
import org.starfall.generative.features.appdata.model.RemoteTerminalPreferencesState
import org.starfall.generative.features.appdata.model.ZipAppDataBackup
import org.starfall.generative.integrations.remote.RemoteMachineService
import org.starfall.generative.shared.subfeatures.media.logic.ToolFiles
import org.starfall.generative.integrations.files.AgentSkillsStore
import org.starfall.generative.shared.subfeatures.icons.logic.IconStore
import org.starfall.generative.database.repositories.ConversationRepository
import org.starfall.generative.database.repositories.McpRepository
import org.starfall.generative.database.repositories.ModelRepository
import org.starfall.generative.database.repositories.ProfileRepository
import org.starfall.generative.database.repositories.SpeechRepository
import org.starfall.generative.shared.helpers.locale.AppLocale
import org.starfall.generative.database.preferences.AppPreferencesRepository
import org.starfall.generative.database.preferences.ToolSettingsStore
import org.starfall.generative.database.preferences.RemoteTerminalPreferenceStore
import org.starfall.generative.database.preferences.normalizeLegacyTerminalInput

data class AppDataImportResult(
    val itemCount: Int,
    val fullRestore: Boolean
)

/** Coordinates a complete, portable application backup rather than exporting individual tables. */
class AppDataTransferService(
    private val context: Context,
    private val modelRepository: ModelRepository,
    private val mcpRepository: McpRepository,
    private val speechRepository: SpeechRepository,
    private val profileRepository: ProfileRepository,
    private val conversationRepository: ConversationRepository,
    private val preferencesRepository: AppPreferencesRepository,
    private val toolSettingsStore: ToolSettingsStore,
    private val iconStore: IconStore,
    private val toolFiles: ToolFiles,
    private val remoteMachineService: RemoteMachineService,
    private val customHttpTools: CustomHttpToolRepository? = null
) {
    private val restoreMutex = Mutex()
    private val agentSkillsStore = AgentSkillsStore(context)
    private val transferScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val transferStateLock = Any()
    private val activeExportWrites = mutableSetOf<String>()
    private val _preparingExport = MutableStateFlow(false)
    private val _preparedExport = MutableStateFlow<File?>(null)
    private val _importInProgress = MutableStateFlow(false)

    val preparingExport: StateFlow<Boolean> = _preparingExport.asStateFlow()
    val preparedExport: StateFlow<File?> = _preparedExport.asStateFlow()
    val importInProgress: StateFlow<Boolean> = _importInProgress.asStateFlow()

    internal val exportCacheDirectory: File get() = File(context.cacheDir, "backup-exports")
    internal val recoveryDirectory: File get() = File(context.filesDir, "backup-recovery")
    internal var applyFaultForTest: ((isRollback: Boolean) -> Unit)? = null

    suspend fun createZipBackup(
        directory: File = exportCacheDirectory,
        prefix: String = "generative-backup-"
    ): File = withContext(Dispatchers.IO) {
        require(directory.mkdirs() || directory.isDirectory) { "Could not create backup directory." }
        // A cold RemoteMachineStore loads/decrypts asynchronously; do not export its placeholder state.
        remoteMachineService.store.awaitInitialized()
        modelRepository.awaitModelConfigurationMemoryWrites()
        val storedFiles = toolFiles.list().associateBy { it.name }
        val uploadFiles = toolFiles.listUploads().associateBy { it.name }
        val backedUpFileNames = storedFiles.keys + uploadFiles.keys
        val backup = AppDataBackup(
            exportedAt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).format(Date()),
            providers = modelRepository.loadProvidersForExport(),
            providerGroups = modelRepository.allGroups.first(),
            mcpServers = mcpRepository.loadServersForExport(),
            customHttpTools = customHttpTools?.snapshot().orEmpty(),
            speechServices = speechRepository.allServices.first(),
            conversations = conversationRepository.conversationList.first().mapNotNull { conversationRepository.loadById(it.id) },
            storedFileNames = storedFiles.keys.sorted(),
            uploadFileNames = uploadFiles.keys.sorted(),
            generatedMediaNames = toolFiles.generatedMediaNames().filter { it in backedUpFileNames }.sorted(),
            importedMediaNames = toolFiles.importedMediaNames().mapValues { (_, names) ->
                names.filter { it in backedUpFileNames }
            }.filterValues { it.isNotEmpty() },
            profiles = profileRepository.allProfiles.first(),
            appPreferences = preferencesRepository.backupSnapshot(),
            toolSettings = toolSettingsStore.snapshot(),
            remoteMachines = remoteMachineService.state.value,
            remoteTerminalPreferences = exportRemoteTerminalPreferences(),
            icons = iconStore.exportState(),
            accountCredentials = modelRepository.exportAccountCredentials(),
            mcpOAuthTokens = mcpRepository.exportOAuthTokens(),
            modelConfigurationMemory = modelRepository.exportModelConfigurationMemory()
        )
        val iconFiles = backup.icons.files.associateWith { name ->
            iconStore.exportFile(name) ?: error("The icon file $name is missing.")
        }
        val identityFiles = remoteMachineService.exportIdentityFiles()
        val skillFiles = agentSkillsStore.exportFiles()
        val destination = File.createTempFile(prefix, ".zip", directory)
        try {
            destination.outputStream().use { output ->
                writeZipBackup(output, backup, iconFiles, storedFiles, identityFiles, skillFiles, uploadFiles)
            }
            destination
        } catch (error: Throwable) {
            destination.delete()
            throw error
        }
    }

    /** Prepares an export archive in the background so Activity recreation does not cancel it. */
    fun prepareExport() {
        val started = synchronized(transferStateLock) {
            if (_preparingExport.value || _preparedExport.value != null || _importInProgress.value) {
                false
            } else {
                _preparingExport.value = true
                true
            }
        }
        if (!started) return

        transferScope.launch {
            var created: File? = null
            try {
                cleanupAbandonedExportsInternal(retainedPath = null, ignorePreparingFlag = true)
                created = createZipBackup()
                synchronized(transferStateLock) {
                    _preparedExport.value = created
                }
            } catch (cancelled: CancellationException) {
                created?.delete()
                throw cancelled
            } catch (_: Exception) {
                created?.delete()
                showToast(R.string.export_prepare_failed, Toast.LENGTH_LONG)
            } finally {
                synchronized(transferStateLock) {
                    _preparingExport.value = false
                }
            }
        }
    }

    /** Claims the prepared export archive immediately before launching the system document picker. */
    fun consumePreparedExport(): File? = synchronized(transferStateLock) {
        _preparedExport.value.also { _preparedExport.value = null }
    }

    /** Copies [archive] to the user-selected [uri] and always deletes the cached archive afterwards. */
    fun writeExportToUri(uri: Uri, archive: File) {
        val path = archive.absolutePath
        synchronized(transferStateLock) {
            activeExportWrites += path
        }
        transferScope.launch {
            try {
                context.contentResolver.openOutputStream(uri)?.use { output ->
                    FileInputStream(archive).use { it.copyTo(output) }
                } ?: error(localizedContext().getString(R.string.export_open_file_failed))
                showToast(R.string.export_zip_success, Toast.LENGTH_SHORT)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                showToast(R.string.export_write_failed, Toast.LENGTH_LONG)
            } finally {
                archive.delete()
                synchronized(transferStateLock) {
                    activeExportWrites -= path
                }
                deleteDirectoryIfEmpty(exportCacheDirectory)
            }
        }
    }

    /** Deletes cached export archives whose picker handoff was abandoned across process death. */
    fun cleanupAbandonedExports(retainedPath: String? = null) {
        cleanupAbandonedExportsInternal(retainedPath = retainedPath, ignorePreparingFlag = false)
    }

    private fun cleanupAbandonedExportsInternal(retainedPath: String?, ignorePreparingFlag: Boolean) {
        val protectedPaths = synchronized(transferStateLock) {
            if (!ignorePreparingFlag && _preparingExport.value) return
            buildSet {
                addAll(activeExportWrites)
                _preparedExport.value?.absolutePath?.let(::add)
                retainedPath?.takeIf { it.isNotBlank() }?.let(::add)
            }
        }
        val directory = exportCacheDirectory
        if (!directory.isDirectory) return
        directory.listFiles()?.forEach { candidate ->
            if (candidate.absolutePath !in protectedPaths) {
                candidate.deleteRecursively()
            }
        }
        deleteDirectoryIfEmpty(directory)
    }

    /** Runs a picker-selected import on the application transfer scope so recreation cannot abort it. */
    fun importFromUri(
        uri: Uri,
        beforeFullRestore: suspend () -> Unit,
        afterFullRestore: suspend (Boolean) -> Unit
    ) {
        val started = synchronized(transferStateLock) {
            if (_importInProgress.value || _preparingExport.value) {
                false
            } else {
                _importInProgress.value = true
                true
            }
        }
        if (!started) return

        transferScope.launch {
            var chatPreparedForRestore = false
            var restoreSucceeded = false
            try {
                val fileName = context.contentResolver
                    .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { cursor ->
                        if (cursor.moveToFirst()) cursor.getString(0) else null
                    }
                val result = context.contentResolver.openInputStream(uri)?.use { input ->
                    import(input, fileName) {
                        // Invoked only after the ZIP manifest, entries and file sets validate.
                        beforeFullRestore()
                        chatPreparedForRestore = true
                    }
                } ?: error(localizedContext().getString(R.string.export_open_file_failed))
                restoreSucceeded = result.fullRestore
                showToastText(
                    localizedContext().getString(R.string.import_success, result.itemCount),
                    Toast.LENGTH_SHORT
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                showToastText(
                    localizedContext().getString(R.string.import_failed, error.message.orEmpty()),
                    Toast.LENGTH_LONG
                )
            } finally {
                try {
                    if (chatPreparedForRestore) {
                        withContext(NonCancellable) { afterFullRestore(restoreSucceeded) }
                    }
                } finally {
                    synchronized(transferStateLock) {
                        _importInProgress.value = false
                    }
                }
            }
        }
    }

    suspend fun import(
        input: InputStream,
        fileNameHint: String? = null,
        beforeFullRestore: suspend () -> Unit
    ): AppDataImportResult = withContext(Dispatchers.IO) {
        val buffered = if (input is BufferedInputStream) input else BufferedInputStream(input)
        buffered.mark(4)
        val signature = ByteArray(4)
        val read = buffered.read(signature)
        buffered.reset()
        val isZip = read == 4 && signature[0] == 'P'.code.toByte() && signature[1] == 'K'.code.toByte()
        if (isZip || fileNameHint.orEmpty().lowercase(Locale.ROOT).endsWith(".zip")) {
            val staging = createStagingDirectory("generative-import-")
            try {
                val backup = readZipBackup(buffered, staging)
                restoreFullBackup(backup, beforeFullRestore)
                AppDataImportResult(itemCount = backup.data.providers.size + backup.data.providerGroups.size +
                    backup.data.mcpServers.size + backup.data.customHttpTools.size + backup.data.speechServices.size + backup.data.conversations.size +
                    backup.data.profiles.size, fullRestore = true)
            } finally {
                staging.deleteRecursively()
            }
        } else {
            val raw = buffered.reader(Charsets.UTF_8).use { it.readText() }
            val backup = parseFullAppDataExport(raw, fileNameHint)
            backup.providers?.let { modelRepository.importProviders(it, backup.providerGroups) }
            backup.mcpServers?.let { it.forEach { server -> mcpRepository.saveServer(server) } }
            backup.speechServices?.let { it.forEach { service -> speechRepository.saveService(service) } }
            backup.conversations?.let { it.forEach { conversation -> conversationRepository.saveConversation(conversation) } }
            AppDataImportResult(
                itemCount = backup.providers.orEmpty().size + backup.providerGroups.orEmpty().size +
                    backup.mcpServers.orEmpty().size + backup.speechServices.orEmpty().size +
                    backup.conversations.orEmpty().size,
                fullRestore = false
            )
        }
    }

    private suspend fun restoreFullBackup(
        imported: ZipAppDataBackup,
        beforeFullRestore: suspend () -> Unit
    ) = restoreMutex.withLock {
        // Reject malformed archives before asking the chat UI to discard transient state.
        validateBackupFiles(imported)
        beforeFullRestore()

        // Keep a private snapshot in filesDir (not evictable cacheDir) so a failed multi-store
        // restore can roll back, and preserve it if automatic rollback also fails.
        val safetySnapshot = createZipBackup(directory = recoveryDirectory, prefix = "generative-recovery-")
        var keepSnapshot = false
        try {
            try {
                withContext(NonCancellable) {
                    applyFullBackup(imported, isRollback = false)
                }
            } catch (restoreError: Throwable) {
                var rollbackError: Throwable? = null
                try {
                    withContext(NonCancellable) {
                        val rollbackDirectory = createStagingDirectory("generative-rollback-")
                        try {
                            val previous = FileInputStream(safetySnapshot).use { input ->
                                readZipBackup(input, rollbackDirectory)
                            }
                            validateBackupFiles(previous)
                            applyFullBackup(previous, isRollback = true)
                        } finally {
                            rollbackDirectory.deleteRecursively()
                        }
                    }
                } catch (error: Throwable) {
                    rollbackError = error
                }

                if (rollbackError != null) {
                    keepSnapshot = true
                    val reason = restoreError.message?.takeIf { it.isNotBlank() }
                        ?: restoreError::class.simpleName
                        ?: "unknown error"
                    throw IllegalStateException(
                        "Restore failed ($reason) and automatic rollback also failed; " +
                            "app data may be inconsistent. A recovery archive was kept at ${safetySnapshot.absolutePath}.",
                        restoreError
                    ).also { it.addSuppressed(rollbackError) }
                }
                throw restoreError
            }
        } finally {
            if (!keepSnapshot) {
                safetySnapshot.delete()
                deleteDirectoryIfEmpty(recoveryDirectory)
            }
        }
    }

    private fun validateBackupFiles(imported: ZipAppDataBackup) {
        val data = imported.data
        data.customHttpTools.forEach { it.validate() }
        require(data.customHttpTools.map { it.id }.distinct().size == data.customHttpTools.size) {
            "The backup contains duplicate Custom HTTP Tool IDs."
        }
        require(data.icons.files.toSet() == imported.iconFiles.keys) {
            "The icon files in the backup do not match its manifest."
        }
        require(data.remoteMachines.identities.map { it.id }.toSet() == imported.remoteIdentityFiles.keys) {
            "The SSH identity files in the backup do not match its manifest."
        }
        require(data.storedFileNames.toSet() == imported.toolFiles.keys &&
            data.uploadFileNames.toSet() == imported.uploadFiles.keys &&
            (imported.toolFiles.keys intersect imported.uploadFiles.keys).isEmpty()) {
            "The stored files in the backup do not match its manifest."
        }
        val allBackedUpFiles = imported.toolFiles.keys + imported.uploadFiles.keys
        require(data.generatedMediaNames.all { it in allBackedUpFiles } &&
            data.importedMediaNames.values.flatten().all { it in allBackedUpFiles }) {
            "Media ownership references files missing from the backup."
        }
        val backedUpSkills = data.appPreferences.agentSkills.skills.map { it.name }.toSet()
        require(imported.skillFiles.keys.all { path ->
            path.substringBefore('/') in backedUpSkills
        }) { "The backup contains files for unknown skills." }
        require(data.remoteMachines.identities.map { it.id }.distinct().size == data.remoteMachines.identities.size) {
            "The backup contains duplicate SSH identity IDs."
        }
    }

    private suspend fun applyFullBackup(imported: ZipAppDataBackup, isRollback: Boolean) {
        val data = imported.data
        modelRepository.replaceAll(data.providers, data.providerGroups)
        mcpRepository.replaceAll(data.mcpServers)
        customHttpTools?.replaceAll(data.customHttpTools)
        speechRepository.replaceAll(data.speechServices)
        profileRepository.replaceAll(data.profiles)
        conversationRepository.replaceAll(data.conversations)
        applyFaultForTest?.invoke(isRollback)
        preferencesRepository.replace(data.appPreferences)
        // Restore the original SKILL.md and all optional resources after the preference snapshot
        // recreates skill directories. Do not trust ZIP paths beyond the validated member names.
        imported.skillFiles.forEach { (path, source) ->
            val root = File(context.filesDir, AgentSkillsStore.DIRECTORY_NAME).canonicalFile
            val skillRoot = File(root, path.substringBefore('/')).canonicalFile
            val destination = File(root, path)
            require(skillRoot.parentFile == root &&
                destination.canonicalFile.toPath().startsWith(skillRoot.toPath()) &&
                destination.canonicalFile != skillRoot) {
                "Unsafe restored skill path"
            }
            val parent = requireNotNull(destination.parentFile) { "Unsafe restored skill path" }
            check(parent.mkdirs() || parent.isDirectory)
            source.inputStream().use { input -> destination.outputStream().use { input.copyTo(it) } }
        }
        toolSettingsStore.replace(data.toolSettings)
        modelRepository.importAccountCredentials(data.accountCredentials)
        mcpRepository.importOAuthTokens(data.mcpOAuthTokens)
        modelRepository.awaitModelConfigurationMemoryWrites()
        modelRepository.importModelConfigurationMemory(data.modelConfigurationMemory)
        restoreRemoteTerminalPreferences(data.remoteTerminalPreferences)
        iconStore.restore(data.icons, imported.iconFiles)
        toolFiles.clearAll()
        toolFiles.clearUploads()
        imported.toolFiles.forEach { (name, file) ->
            FileInputStream(file).use { toolFiles.restore(name, it) }
        }
        imported.uploadFiles.forEach { (name, file) ->
            FileInputStream(file).use { toolFiles.restoreUpload(name, it) }
        }
        // Older backups lack provenance; rebuild generated ownership from conversation history.
        toolFiles.replaceMediaProvenance(data.generatedMediaNames, data.importedMediaNames)
        toolFiles.markGeneratedMedia(conversationRepository.generatedFileReferences())
        remoteMachineService.replaceState(data.remoteMachines, imported.remoteIdentityFiles)
    }

    private suspend fun localizedContext(): Context {
        val settings = preferencesRepository.appPreferencesFlow.first()
        return AppLocale.wrap(context, settings.useSystemLanguage, settings.selectedLanguage)
    }

    private suspend fun showToast(resId: Int, duration: Int) {
        withContext(Dispatchers.Main) {
            Toast.makeText(localizedContext(), resId, duration).show()
        }
    }

    private suspend fun showToastText(message: String, duration: Int) {
        withContext(Dispatchers.Main) {
            Toast.makeText(localizedContext(), message, duration).show()
        }
    }

    private fun deleteDirectoryIfEmpty(directory: File) {
        if (directory.isDirectory && directory.list()?.isEmpty() == true) {
            directory.delete()
        }
    }

    private fun createStagingDirectory(prefix: String): File =
        File.createTempFile(prefix, "", context.cacheDir).apply {
            check(delete() && mkdirs()) { "Could not create temporary restore directory." }
        }

    private suspend fun exportRemoteTerminalPreferences(): RemoteTerminalPreferencesState {
        val values = RemoteTerminalPreferenceStore.getInstance(context).snapshot().asMap()
        return RemoteTerminalPreferencesState(
            ints = values.mapNotNull { (key, value) -> (value as? Int)?.let { key.name to it } }.toMap(),
            booleans = values.mapNotNull { (key, value) -> (value as? Boolean)?.let { key.name to it } }.toMap(),
            strings = values.mapNotNull { (key, value) -> (value as? String)?.let { key.name to it } }.toMap()
        )
    }

    private suspend fun restoreRemoteTerminalPreferences(state: RemoteTerminalPreferencesState) {
        RemoteTerminalPreferenceStore.getInstance(context).updateAndWait { preferences ->
            preferences.clear()
            state.ints.forEach { (key, value) -> preferences[intPreferencesKey(key)] = value }
            state.booleans.forEach { (key, value) -> preferences[booleanPreferencesKey(key)] = value }
            state.strings.forEach { (key, value) -> preferences[stringPreferencesKey(key)] = value }
            preferences.normalizeLegacyTerminalInput()
        }
    }
}
