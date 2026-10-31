package org.starfall.generative.features.machines.shared.logic

import android.content.Context
import android.net.Uri
import java.io.FileNotFoundException
import java.io.InputStream
import java.io.OutputStream
import org.starfall.generative.shared.subfeatures.explorer.logic.ExplorerItem
import org.starfall.generative.shared.subfeatures.explorer.logic.ExplorerLocation
import org.starfall.generative.shared.subfeatures.explorer.logic.ExplorerRepository
import org.starfall.generative.integrations.remote.RemoteMachineService
import org.starfall.generative.integrations.remote.RemoteSftpExplorer

/** Remote Machines owns its SFTP browser binding; Storage never imports this feature. */
class RemoteMachineExplorerRepository(
    context: Context,
    private val service: RemoteMachineService,
    private val machineId: String
) : ExplorerRepository {
    private val remote = RemoteSftpExplorer(service)
    private val app = context.applicationContext
    val root: ExplorerLocation
        get() {
            val machine = service.state.value.machines.firstOrNull { it.id == machineId }
            val title = machine?.name?.takeIf { it.isNotBlank() } ?: machineId
            return ExplorerLocation(sourceId = "machine:$machineId", label = "SFTP_$title")
        }

    override suspend fun list(location: ExplorerLocation): List<ExplorerItem> {
        requireMachine(location)
        return remote.list(machineId, location.path).map { file ->
            ExplorerItem(file.name, location.copy(path = file.relativePath), file.directory, file.size, file.modified)
        }
    }

    override suspend fun createFolder(parent: ExplorerLocation, name: String) {
        requireMachine(parent)
        remote.createFolder(machineId, parent.path, name)
    }

    override suspend fun rename(item: ExplorerItem, newName: String) {
        requireMachine(item.location)
        remote.rename(machineId, item.location.path, newName)
    }

    override suspend fun delete(item: ExplorerItem) {
        requireMachine(item.location)
        remote.delete(machineId, item.location.path)
    }

    suspend fun upload(parent: ExplorerLocation, name: String, input: InputStream) {
        requireMachine(parent)
        remote.upload(machineId, parent.path, name, input)
    }

    suspend fun download(item: ExplorerItem, output: OutputStream) {
        requireMachine(item.location)
        if (item.directory) throw FileNotFoundException("Not a file")
        remote.download(machineId, item.location.path, output)
    }

    fun documentUri(item: ExplorerItem): Uri {
        requireMachine(item.location)
        if (item.directory) throw FileNotFoundException("Not a file")
        return remote.documentUri(app, machineId, item.location.path)
    }

    private fun requireMachine(location: ExplorerLocation) {
        require(location.sourceId == "machine:$machineId") { "Unknown remote machine location" }
    }
}
