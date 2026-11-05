package org.starfall.generative.shared.ui.code

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.starfall.generative.database.preferences.WordWrapMode
import org.starfall.generative.shared.ui.layout.nestedContainerBorder
import org.starfall.generative.shared.ui.layout.nestedContainerColor
import org.starfall.generative.shared.ui.LoadingIndicator

/**
 * Code surface for texts that may be huge: only the chunks on screen are composed (and highlighted).
 * Header, font size, highlighting and word-wrap rules are the same as [CodeBlockSurface]. The block wraps its
 * content up to [maxHeight] and scrolls inside itself beyond that.
 */
@Composable
internal fun LazyCodeBlock(
    language: String,
    text: String,
    modifier: Modifier = Modifier,
    maxHeight: Dp = 440.dp,
    onDownload: (() -> Unit)? = null
) {
    val preferences = LocalCodeRenderingPreferences.current
    var sessionWrap by remember(text) { mutableStateOf(false) }
    val mode = if (preferences.wordWrapMode == WordWrapMode.OFF && sessionWrap) WordWrapMode.VIEWPORT else preferences.wordWrapMode
    val textStyle = codeTextStyle()
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val characterWidth = remember(textStyle, density) {
        (measurer.measure("0".repeat(100), textStyle, softWrap = false).size.width / 100f).coerceAtLeast(1f)
    }
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val starts by produceState<IntArray?>(null, text) {
        value = withContext(Dispatchers.Default) { chunkStarts(text) }
    }
    val horizontalState = rememberScrollState()
    val listState = rememberLazyListState()

    Surface(
        shape = RoundedCornerShape(10.dp),
        color = nestedContainerColor(),
        border = nestedContainerBorder(),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(Modifier.fillMaxWidth()) {
            CodeBlockHeader(
                language = language,
                isStreaming = false,
                originalCode = text,
                sessionWrap = sessionWrap,
                onToggleWrap = { sessionWrap = !sessionWrap },
                onDownload = onDownload
            )
            val chunks = starts
            if (chunks == null) {
                Box(Modifier.fillMaxWidth().height(56.dp), contentAlignment = Alignment.Center) {
                    LoadingIndicator(Modifier.size(24.dp))
                }
            } else {
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val viewport = with(density) { (maxWidth - 24.dp).coerceAtLeast(1.dp).toPx() }
                    val target = codeWrapWidth(mode, viewport, (characterWidth * preferences.wordWrapColumn).coerceAtMost(200_000f))
                    val bodyModifier = if (target == null) Modifier else Modifier.width(with(density) { target.toDp() } + 24.dp)
                    val scrollsHorizontally = mode == WordWrapMode.OFF || mode == WordWrapMode.COLUMN
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.heightIn(max = maxHeight)
                            .then(if (scrollsHorizontally) Modifier.horizontalScroll(horizontalState) else Modifier.fillMaxWidth())
                    ) {
                        items(count = chunks.size, key = { it }) { index ->
                            val from = chunks[index]
                            val to = if (index + 1 < chunks.size) chunks[index + 1] else text.length
                            val chunk = remember(text, from, to) { text.substring(from, to) }
                            val highlighted by produceState(AnnotatedString(chunk), chunk, language, dark) {
                                value = withContext(Dispatchers.Default) { highlightedCode(language, chunk, dark) }
                            }
                            Text(
                                highlighted,
                                style = textStyle,
                                softWrap = mode != WordWrapMode.OFF,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = bodyModifier.padding(
                                    start = 12.dp,
                                    end = 12.dp,
                                    top = if (index == 0) 12.dp else 0.dp,
                                    bottom = if (index == chunks.lastIndex) 12.dp else 0.dp
                                )
                            )
                        }
                    }
                }
            }
        }
    }
}
