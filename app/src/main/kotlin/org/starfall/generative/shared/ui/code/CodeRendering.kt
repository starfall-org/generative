package org.starfall.generative.shared.ui.code

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import org.starfall.generative.shared.model.preferences.AppPreferences
import org.starfall.generative.shared.model.preferences.WordWrapMode

/**
 * App-wide code rendering settings (word wrap, font size, preview toggle). Provided once at the app root,
 * so every code surface in the app (chat, tool details, model editor, file preview) follows the same settings.
 */
internal val LocalCodeRenderingPreferences = staticCompositionLocalOf { AppPreferences() }

/** The navigation layer opens the preview without giving code rendering a navigation dependency. */
internal val LocalOpenCodePreview = staticCompositionLocalOf<(String, String) -> Unit> { { _, _ -> } }

/** Width of the text area, excluding code block padding. Null means no wrapping. */
internal fun codeWrapWidth(mode: WordWrapMode, viewport: Float, column: Float): Float? = when (mode) {
    WordWrapMode.OFF -> null
    WordWrapMode.VIEWPORT -> viewport
    WordWrapMode.COLUMN -> column
    WordWrapMode.BOUNDED -> minOf(viewport, column)
}

@Composable
internal fun codeTextStyle(): TextStyle {
    val size = (LocalCodeRenderingPreferences.current.effectiveMessageFontSize * 0.78f).sp
    return MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = size, lineHeight = size * 1.44f)
}

private const val LAZY_CHUNK_LINES = 80
private const val LAZY_CHUNK_CHARS = 4_000

/**
 * Start offsets of consecutive chunks of [text]. Only offsets are kept (no substring copies), a chunk
 * ends at a line break once it holds [maxLines] lines or [maxChars] characters, and a single line longer
 * than [maxChars] is split. One linear pass, so it stays cheap for very large inputs.
 */
internal fun chunkStarts(text: String, maxLines: Int = LAZY_CHUNK_LINES, maxChars: Int = LAZY_CHUNK_CHARS): IntArray {
    val starts = ArrayList<Int>()
    var start = 0
    while (start < text.length) {
        starts.add(start)
        val limit = minOf(text.length, start + maxChars)
        var i = start
        var lines = 0
        var lastBreak = -1
        while (i < limit) {
            if (text[i] == '\n') {
                lines++
                lastBreak = i + 1
                if (lines >= maxLines) break
            }
            i++
        }
        start = when {
            lines >= maxLines -> lastBreak
            limit == text.length -> text.length
            lastBreak > start -> lastBreak
            else -> limit
        }
    }
    return starts.toIntArray()
}
