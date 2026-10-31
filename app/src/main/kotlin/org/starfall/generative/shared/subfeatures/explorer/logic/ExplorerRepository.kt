package org.starfall.generative.shared.subfeatures.explorer.logic

/** Opaque source identity: the explorer does not know which backend owns it. */
data class ExplorerLocation(
    val sourceId: String = "",
    val path: String = "",
    val label: String = ""
) {
    val isHome: Boolean get() = sourceId.isEmpty()

    fun parent(): ExplorerLocation = when {
        path.isNotEmpty() -> copy(path = path.substringBeforeLast('/', ""))
        else -> ExplorerLocation()
    }
}

data class ExplorerItem(
    val name: String,
    val location: ExplorerLocation,
    val directory: Boolean,
    val size: Long = 0L,
    val modified: Long = 0L
)

/** Host-implemented storage operations; file viewing is a separate host callback. */
interface ExplorerRepository {
    suspend fun list(location: ExplorerLocation): List<ExplorerItem>
    suspend fun createFolder(parent: ExplorerLocation, name: String)
    suspend fun rename(item: ExplorerItem, newName: String)
    suspend fun delete(item: ExplorerItem)
}
