package org.starfall.generative.navigation

import android.content.Intent
import android.content.Context
import android.net.Uri
import org.starfall.generative.shared.subfeatures.media.logic.ToolFiles

/** One external share is a composer draft, never a request to send a message. */
internal data class ExternalShare(val text: String?, val streams: List<Uri>, val fallbackMime: String?)

internal data class ExternalShareRequest(val id: Long, val content: ExternalShare)

/** Parse both legacy and modern share senders; ClipData may hold the only stream URI. */
@Suppress("DEPRECATION") // Parcelable retrieval also supports devices below API 33.
internal fun parseExternalShare(intent: Intent): ExternalShare? {
    val action = intent.action
    if (action !in setOf(Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE, Intent.ACTION_PROCESS_TEXT)) return null

    val text = (if (action == Intent.ACTION_PROCESS_TEXT)
        intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)
    else intent.getCharSequenceExtra(Intent.EXTRA_TEXT))
        ?.toString()?.take(100_000)?.takeIf { it.isNotEmpty() }

    val streams = if (action == Intent.ACTION_PROCESS_TEXT) emptyList() else buildList {
        if (action == Intent.ACTION_SEND_MULTIPLE) {
            intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.let(::addAll)
        } else {
            intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.let(::add)
        }
        intent.clipData?.let { clip ->
            for (index in 0 until clip.itemCount) clip.getItemAt(index).uri?.let(::add)
        }
        intent.data?.let(::add)
    }.filter { it.scheme == "content" || it.scheme == "file" }.distinct().take(20)

    if (text == null && streams.isEmpty()) return null
    return ExternalShare(text, streams, intent.type)
}

/** Snapshot a shared file while the sender still grants us access. */
internal suspend fun stageSharedAttachment(
    context: Context,
    files: ToolFiles,
    uri: Uri,
    fallbackMime: String?,
    temporaryChatId: String?
): String = org.starfall.generative.shared.subfeatures.media.logic.importUserAttachment(
    context, files, uri, fallbackMime, temporaryChatId
)
