package org.starfall.generative.shared.model

/*
 * Rules for AI response versions:
 *  - An empty response (nothing generated, no error) is never kept.
 *  - An error-only response is kept, but it is not a version: it never sits next to real versions
 *    for longer than the failed attempt, and the next attempt replaces it in place.
 *  - Only an explicit regenerate on a real response adds a version.
 */

internal fun looksLikeErrorText(content: String): Boolean {
    val lines = content.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
    if (lines.isEmpty()) return false
    return lines.all { line ->
        line.startsWith("[Error:", ignoreCase = true) && line.endsWith("]") ||
            line.startsWith("Error:", ignoreCase = true) ||
            line.startsWith("API Error", ignoreCase = true) ||
            line.startsWith("Exception:", ignoreCase = true) ||
            line.startsWith("Failed:", ignoreCase = true)
    }
}

/** A transport handshake or a file-transfer call without visible media is not a chat block. */
internal fun ToolActivity.hasVisibleAssistantBlock(): Boolean =
    !name.endsWith(": connect") && (name != "send_file" ||
        (responsePreviewLimit == 0 && files.any { !it.endsWith(".json", ignoreCase = true) }))

/** Something the user can read or keep, excluding text that consists only of an error. */
internal fun MessageVersion.hasResponseContent(): Boolean =
    (content.isNotBlank() && !looksLikeErrorText(content)) ||
        !reasoningContent.isNullOrBlank() || files.isNotEmpty() ||
        toolActivity.any { it.hasVisibleAssistantBlock() }

internal fun MessageVersion.isErrorOnly(): Boolean =
    !hasResponseContent() && (generationError != null || looksLikeErrorText(content))

internal fun MessageVersion.isEmptyResponse(): Boolean =
    generationError.isNullOrBlank() && !hasResponseContent() && !looksLikeErrorText(content)

/** Versions the user can page through; error-only attempts are not counted. */
internal fun StoredMessage.countedVersionIndices(): List<Int> =
    if (role != ChatRole.MODEL) versions.indices.toList()
    else versions.indices.filter { !versions[it].isErrorOnly() }

/** True when another version holds a real response, so a bubble resend must not touch this message. */
internal fun StoredMessage.hasOtherRealVersions(): Boolean {
    val active = activeVersionIndex.coerceIn(0, (versions.size - 1).coerceAtLeast(0))
    return versions.withIndex().any { (index, version) -> index != active && !version.isErrorOnly() && !version.isEmptyResponse() }
}

private fun StoredMessage.keeping(keep: (Int, MessageVersion) -> Boolean): StoredMessage? {
    if (versions.isEmpty()) return null
    val active = activeVersionIndex.coerceIn(versions.indices)
    val kept = versions.indices.filter { keep(it, versions[it]) }
    if (kept.size == versions.size) return this
    if (kept.isEmpty()) return null
    val newActive = kept.indexOf(active).takeIf { it >= 0 }
        ?: kept.indexOfLast { it < active }.takeIf { it >= 0 } ?: 0
    return copy(versions = kept.map { versions[it] }, activeVersionIndex = newActive)
}

/** Drops failed and empty attempts once a response version exists. */
internal fun StoredMessage.droppingStaleErrorVersions(keepActive: Boolean = false): StoredMessage {
    if (role != ChatRole.MODEL || versions.size < 2) return this
    val active = activeVersionIndex.coerceIn(versions.indices)
    if (versions.all { it.isErrorOnly() || it.isEmptyResponse() }) return this
    return keeping { index, version ->
        (!version.isErrorOnly() && !version.isEmptyResponse()) || (keepActive && index == active)
    } ?: this
}

/** A new attempt replaces an error-only or empty active version, otherwise it becomes a new version. */
internal fun StoredMessage.withAttempt(version: MessageVersion, replaceActive: Boolean): StoredMessage {
    if (versions.isEmpty()) return copy(versions = listOf(version), activeVersionIndex = 0)
    val active = activeVersionIndex.coerceIn(versions.indices)
    if (replaceActive) {
        val replaced = versions.toMutableList().also { it[active] = version }
        return copy(versions = replaced, activeVersionIndex = active)
    }
    val base = droppingStaleErrorVersions()
    return base.copy(versions = base.versions + version, activeVersionIndex = base.versions.size)
}

/**
 * A stopped reply may hold only reasoning or tool activity and no answer text. Providers reject an
 * assistant message with no non-whitespace content, so such replies are left out of the context sent
 * with the next request (the stored conversation is unchanged).
 */
internal fun List<StoredMessage>.withoutEmptyModelMessages(): List<StoredMessage> =
    filterNot { it.role == ChatRole.MODEL && it.content.isBlank() && it.files.isEmpty() }

/** Removes responses that hold nothing, so they are never saved. */
internal fun Conversation.pruningEmptyResponses(): Conversation {
    var changed = false
    var structureChanged = false
    val cleaned = messages.mapNotNull { message ->
        if (message.role != ChatRole.MODEL) return@mapNotNull message
        val pruned = message.keeping { _, version -> !version.isEmptyResponse() }
        if (pruned !== message) changed = true
        if (pruned == null) structureChanged = true
        pruned
    }
    return if (changed) copy(
        messages = cleaned,
        structuralRevision = structuralRevision + if (structureChanged) 1L else 0L
    ) else this
}
