package org.starfall.generative.shared.subfeatures.media.logic

import org.starfall.generative.shared.model.ChatRole
import org.starfall.generative.shared.model.Conversation

/**
 * Names of the app-private files the user attached to this conversation, across every edited version of
 * their messages. User-owned documents (`content://` URIs) are never included: the app does not own them.
 *
 * Files are stored once per content hash, so a file the user re-attached from Storage may also be something
 * the assistant or a tool produced here. Those are left out, because they are generated output rather than
 * an upload.
 */
internal fun Conversation.uploadedFileNames(files: ToolFiles): Set<String> {
    val uploaded = messages.filter { it.role == ChatRole.USER }
        .flatMap { message -> message.versions.flatMap { it.files } }
        .flatMap { reference -> listOfNotNull(files.nameOf(reference)) + files.importedCopyNames(reference) }
    return uploaded.toSet() - generatedFileNames(files)
}

/** All assistant/tool output, except send_file which only forwards existing files. */
internal fun Conversation.generatedFileNames(files: ToolFiles): Set<String> = messages
    .filter { it.role != ChatRole.USER }
    .flatMap { message -> message.versions.flatMap { version ->
        val forwarded = version.toolActivity.filter { it.wireName == "send_file" }
            .flatMap { it.files }.mapNotNull(files::nameOf).toSet()
        (version.files.mapNotNull(files::nameOf) - forwarded) +
            version.toolActivity.filter { it.wireName != "send_file" }
                .flatMap { it.files }.mapNotNull(files::nameOf)
    } }
    .filterNot(files::isUserUpload)
    .toSet()
