package org.starfall.generative.shared.helpers.audio

import android.content.Context
import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import java.io.File

/** Shared playback configuration for speech, attachments and the storage viewer. */
internal object MediaPlayers {
    fun create(context: Context, speech: Boolean = false): ExoPlayer = ExoPlayer.Builder(context.applicationContext)
        .build().apply {
            setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA)
                .setContentType(if (speech) C.AUDIO_CONTENT_TYPE_SPEECH else C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
            setHandleAudioBecomingNoisy(true)
        }

    fun item(reference: String): MediaItem = MediaItem.fromUri(
        Uri.parse(reference).takeIf { it.scheme != null } ?: Uri.fromFile(File(reference)))
}
