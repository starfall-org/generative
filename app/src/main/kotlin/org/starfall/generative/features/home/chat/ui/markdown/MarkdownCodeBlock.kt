package org.starfall.generative.features.home.chat.ui.markdown

import org.starfall.generative.features.home.chat.ui.LocalChatForeground

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import org.starfall.generative.shared.ui.dialogs.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.starfall.generative.R
import org.starfall.generative.shared.model.preferences.CodeBlockDisplayMode
import org.starfall.generative.shared.ui.code.CodeBlockSurface
import org.starfall.generative.shared.ui.code.LocalCodeRenderingPreferences
import org.starfall.generative.shared.ui.icons.SharedIcon
import org.starfall.generative.shared.ui.icons.Icons
import org.starfall.generative.shared.ui.layout.nestedContainerBorder
import org.starfall.generative.shared.ui.layout.nestedContainerColor

private const val MAX_INLINE_CODE_CHARS = 16_000
private const val MAX_INLINE_CODE_LINES = 600

/** Chat code block renderer; very large blocks stay inline in the message selection tree. */
@Composable
internal fun RenderCodeBlock(
    language: String,
    source: String,
    startOffset: Int,
    endOffset: Int,
    isStreaming: Boolean,
    trimTrailingWhitespace: Boolean = false
) {
    val start = startOffset.coerceIn(0, source.length)
    val end = adjustedCodeEnd(source, start, endOffset, trimTrailingWhitespace)
    val codeLength = end - start
    val initialLineCount = remember(source, start, end, isStreaming) {
        if (isStreaming) null else if (codeLength <= MAX_INLINE_CODE_CHARS) countLines(source, start, end) else null
    }
    val lineCount by produceState<Int?>(initialLineCount, source, start, end, isStreaming, codeLength) {
        if (!isStreaming && codeLength <= MAX_INLINE_CODE_CHARS && value == null) {
            value = withContext(Dispatchers.Default) { countLines(source, start, end) }
        }
    }
    var expanded by remember(language, source, start, end) { mutableStateOf(false) }
    val foreground = LocalChatForeground.current
    var drawerVisible by remember(language, source, start, end, foreground) { mutableStateOf(false) }

    if (isHugeCode(codeLength, lineCount)) {
        RenderHugeCodeBlock(language, source, start, end, isStreaming)
        return
    }
    RenderConfiguredCodeBlock(
        language = language,
        source = source,
        start = start,
        end = end,
        lineCount = lineCount,
        isStreaming = isStreaming,
        expanded = expanded,
        drawerVisible = drawerVisible,
        onExpandedChange = { expanded = it },
        onOpen = { drawerVisible = true },
        onClose = { drawerVisible = false }
    )
}

private fun adjustedCodeEnd(source: String, start: Int, endOffset: Int, trimTrailingWhitespace: Boolean): Int {
    var end = endOffset.coerceIn(start, source.length)
    if (trimTrailingWhitespace) {
        while (end > start && source[end - 1].isWhitespace()) end--
    }
    return end
}

private fun isHugeCode(codeLength: Int, lineCount: Int?): Boolean =
    codeLength > MAX_INLINE_CODE_CHARS || (lineCount ?: 0) > MAX_INLINE_CODE_LINES

@Composable
private fun RenderHugeCodeBlock(
    language: String,
    source: String,
    start: Int,
    end: Int,
    isStreaming: Boolean
) {
    val code = remember(source, start, end) { source.substring(start, end) }
    CodeBlockSurface(
        language = language,
        code = code,
        modifier = Modifier.padding(vertical = 4.dp),
        originalCode = code,
        isStreaming = isStreaming
    )
}

@Composable
private fun RenderConfiguredCodeBlock(
    language: String,
    source: String,
    start: Int,
    end: Int,
    lineCount: Int?,
    isStreaming: Boolean,
    expanded: Boolean,
    drawerVisible: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onOpen: () -> Unit,
    onClose: () -> Unit
) {
    val preferences = LocalCodeRenderingPreferences.current
    val code = remember(source, start, end) { source.substring(start, end) }
    val codeLanguageFallback = stringResource(R.string.code_language_fallback)
    val isLong = isLongCodeBlock(language, isStreaming, lineCount, preferences)
    val displayMode = preferences.codeBlockDisplayMode
    if (displayMode == CodeBlockDisplayMode.SHOW_IN_DRAWER && isLong) {
        LongCodeBlockDrawerCard(language, codeLanguageFallback, lineCount, onOpen)
        if (drawerVisible) {
            CodeBlockDialog(
                language = language,
                code = code,
                isStreaming = isStreaming,
                onDismiss = onClose
            )
        }
        return
    }
    RenderInlineCodeBlock(
        language = language,
        code = code,
        isStreaming = isStreaming,
        displayMode = displayMode,
        maxLines = preferences.codeBlockMaxLines,
        isLong = isLong,
        expanded = expanded,
        onExpandedChange = onExpandedChange
    )
}

private fun isLongCodeBlock(
    language: String,
    isStreaming: Boolean,
    lineCount: Int?,
    preferences: org.starfall.generative.shared.model.preferences.AppPreferences
): Boolean = !isStreaming &&
    (preferences.codeBlockIncludeUndefined || language.isNotBlank()) &&
    lineCount.orZero() > preferences.codeBlockMaxLines

@Composable
private fun LongCodeBlockDrawerCard(
    language: String,
    languageFallback: String,
    lineCount: Int?,
    onOpen: () -> Unit
) {
    Surface(
        onClick = onOpen,
        shape = RoundedCornerShape(10.dp),
        color = nestedContainerColor(),
        border = nestedContainerBorder(),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
    ) {
        Box(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                stringResource(R.string.code_block_summary, language.ifBlank { languageFallback }, lineCount.orZero()),
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelLarge
            )
        }
    }
}

@Composable
private fun RenderInlineCodeBlock(
    language: String,
    code: String,
    isStreaming: Boolean,
    displayMode: CodeBlockDisplayMode,
    maxLines: Int,
    isLong: Boolean,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit
) {
    val visibleCode = if (displayMode == CodeBlockDisplayMode.EXTENDABLE && isLong && !expanded) {
        code.lineSequence().take(maxLines).joinToString("\n")
    } else code
    CodeBlockSurface(
        language = language,
        code = visibleCode,
        modifier = Modifier.padding(vertical = 4.dp),
        originalCode = code,
        isStreaming = isStreaming,
        onExpansionChange = if (displayMode == CodeBlockDisplayMode.EXTENDABLE && isLong) {
            { onExpandedChange(!expanded) }
        } else null,
        expanded = expanded
    )
}

@Composable
private fun CodeBlockDialog(
    language: String,
    code: String,
    isStreaming: Boolean,
    onDismiss: () -> Unit
) {
    val closeLabel = stringResource(R.string.code_block_close)
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
        edgeToEdge = true,
        shape = RectangleShape,
        containerColor = MaterialTheme.colorScheme.background,
        title = {
            Text(language.ifBlank { "code" },
                style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace))
        },
        content = {
            Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                CodeBlockSurface(
                    language = language,
                    code = code,
                    modifier = Modifier.padding(vertical = 4.dp),
                    originalCode = code,
                    isStreaming = isStreaming,
                    onExpansionChange = null,
                    expanded = true
                )
            }
        },
        buttons = {
            TextButton(onClick = onDismiss, modifier = Modifier.semantics { contentDescription = closeLabel }) {
                Text(stringResource(R.string.common_close))
            }
        }
    )
}

/** Only visible/prefetched code lines are composed; pathological individual lines are safely abbreviated. */

private fun countLines(source: String, startOffset: Int, endOffset: Int): Int {
    val start = startOffset.coerceIn(0, source.length)
    val end = endOffset.coerceIn(start, source.length)
    if (end <= start) return 0
    var lineBreaks = 0
    for (index in start until end) if (source[index] == '\n') lineBreaks++
    return lineBreaks + if (source[end - 1] == '\n') 0 else 1
}

private fun Int?.orZero(): Int = this ?: 0
