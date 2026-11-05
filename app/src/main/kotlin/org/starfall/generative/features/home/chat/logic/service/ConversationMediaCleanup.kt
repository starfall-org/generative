package org.starfall.generative.features.home.chat.logic.service

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.starfall.generative.database.repositories.ConversationRepository
import org.starfall.generative.shared.subfeatures.media.logic.ToolFiles

/** Translate user references (including content:// documents) into app-owned copies only. */
internal fun localChatUploadNames(files: ToolFiles, references: Collection<String>): Set<String> =
    references.flatMap { reference ->
        listOfNotNull(files.nameOf(reference)) + files.importedCopyNames(reference)
    }.toSet()

/** Remove user uploads after deleting their chat rows, but never delete generated tool output. */
internal suspend fun deleteUnreferencedChatUploads(
    repository: ConversationRepository,
    files: ToolFiles,
    candidates: Set<String>,
    protectedReferences: Collection<String>
) {
    if (candidates.isEmpty()) return
    val stillWanted = localChatUploadNames(files, protectedReferences)
    val orphaned = repository.unreferencedFiles(
        candidates - stillWanted - candidates.filter(files::isGeneratedMedia).toSet()
    )
    if (orphaned.isNotEmpty()) withContext(Dispatchers.IO) { files.deletePermanentUploads(orphaned) }
}
