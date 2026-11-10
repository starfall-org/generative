package org.starfall.generative.shared.ui.code

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.starfall.generative.R
import org.starfall.generative.shared.model.preferences.WordWrapMode
import org.starfall.generative.shared.ui.layout.nestedContainerBorder
import org.starfall.generative.shared.ui.layout.nestedContainerColor
import org.starfall.generative.shared.ui.text.glyphTarget
import org.starfall.generative.shared.ui.text.rememberGlyphTarget

/**
 * The one code block used across the app. Header (language, wrap toggle, preview, download, copy), font size,
 * highlighting and word wrap all follow [LocalCodeRenderingPreferences], so chat, tool details, the model editor
 * and file previews look and behave the same. Selection gating is a no-op outside chat.
 *
 * [code] is what is shown; [originalCode] is what copy, preview and download use (they differ only when the
 * visible text is truncated by the "extendable" display mode).
 */
@Composable
internal fun CodeBlockSurface(
    language: String,
    code: String,
    modifier: Modifier = Modifier,
    originalCode: String = code,
    isStreaming: Boolean = false,
    onExpansionChange: (() -> Unit)? = null,
    expanded: Boolean = false,
    onDownload: (() -> Unit)? = null
) {
    val preferences = LocalCodeRenderingPreferences.current
    var sessionWrap by remember(language, originalCode) { mutableStateOf(false) }
    val mode = if (preferences.wordWrapMode == WordWrapMode.OFF && sessionWrap) WordWrapMode.VIEWPORT else preferences.wordWrapMode
    val wrapCode = mode != WordWrapMode.OFF
    val textStyle = codeTextStyle()
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val characterWidth = remember(textStyle, density) { (measurer.measure("0".repeat(100), textStyle, softWrap = false).size.width / 100f).coerceAtLeast(1f) }
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val highlighted by produceState(AnnotatedString(code), code, language, dark) {
        value = withContext(Dispatchers.Default) { highlightedCode(language, code, dark) }
    }
    val codeScrollState = rememberScrollState()
    val glyphTarget = rememberGlyphTarget()
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = nestedContainerColor(),
        border = nestedContainerBorder(),
        modifier = modifier.fillMaxWidth()
    ) {
        Column {
            CodeBlockHeader(
                language = language,
                isStreaming = isStreaming,
                originalCode = originalCode,
                sessionWrap = sessionWrap,
                onToggleWrap = { sessionWrap = !sessionWrap },
                onDownload = onDownload
            )
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val viewport = with(density) { (maxWidth - 24.dp).coerceAtLeast(1.dp).toPx() }
                val target = codeWrapWidth(mode, viewport, (characterWidth * preferences.wordWrapColumn).coerceAtMost(200_000f))
                val bodyModifier = if (target == null) Modifier else Modifier.width(with(density) { target.toDp() } + 24.dp)
                Box(Modifier.fillMaxWidth().then(if (mode == WordWrapMode.OFF || mode == WordWrapMode.COLUMN) Modifier.horizontalScroll(codeScrollState) else Modifier)) {
                    Text(highlighted, style = textStyle,
                        softWrap = wrapCode, modifier = bodyModifier.padding(12.dp).glyphTarget(glyphTarget),
                        onTextLayout = { glyphTarget?.layout = it }, color = MaterialTheme.colorScheme.onSurface)
                }
            }
            onExpansionChange?.let { toggle ->
                TextButton(onClick = toggle, modifier = Modifier.align(Alignment.End).padding(end = 8.dp, bottom = 4.dp)) {
                    Text(stringResource(if (expanded) R.string.collapse else R.string.code_block_extend))
                }
            }
        }
    }
}
