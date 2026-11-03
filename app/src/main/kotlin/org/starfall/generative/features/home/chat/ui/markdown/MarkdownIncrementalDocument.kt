package org.starfall.generative.features.home.chat.ui.markdown

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mikepenz.markdown.compose.MarkdownElement
import com.mikepenz.markdown.compose.components.MarkdownComponents
import org.intellij.markdown.ast.ASTNode
import org.starfall.generative.features.home.chat.model.StreamTextSnapshot
import org.starfall.generative.features.home.chat.logic.markdown.MathSplitter
import org.starfall.generative.shared.ui.text.LocalStreamingTextFade

/**
 * Incremental AST rendering with real Compose measurement. Stable nodes keep their composition slots;
 * only the unstable tail and any post-parser-limit text update. The conversation list owns laziness;
 * this renderer does not invent heights for content that has not been composed.
 */
@Composable
internal fun RenderIncrementalMarkdownDocument(
    stableNodes: List<ASTNode>,
    unstableNodes: List<ASTNode>,
    source: StreamTextSnapshot,
    overflowStart: Int?,
    components: MarkdownComponents,
    latexEnabled: Boolean,
    modifier: Modifier,
    preparedOverflow: String? = null
) {
    val sourceLength = source.length.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

    Column(modifier.fillMaxWidth()) {
        stableNodes.forEachIndexed { index, node ->
            key(incrementalBlockSlot("stable", index, node)) {
                StreamingMarkdownSourceBlock(node, source, stable = true, components = components, latexEnabled = latexEnabled)
            }
        }
        if (overflowStart == null) {
            unstableNodes.forEachIndexed { index, node ->
                key(incrementalBlockSlot("tail", index, node)) {
                    StreamingMarkdownSourceBlock(node, source, stable = false, components = components, latexEnabled = latexEnabled)
                }
            }
        } else {
            val start = overflowStart.coerceIn(0, sourceLength)
            // Active overflow is prepared on Dispatchers.Default before a UI commit.
            // A frozen snapshot may still need the last unparsed suffix once at Stop.
            val overflow = preparedOverflow ?: remember(source.latestSegment, source.length, start) {
                source.slice(start, sourceLength)
            }
            if (overflow.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                CompositionLocalProvider(LocalStreamingTextFade provides false) {
                    OverflowPlainText(overflow)
                }
            }
        }
    }
}

@Composable
private fun StreamingMarkdownSourceBlock(
    node: ASTNode,
    source: StreamTextSnapshot,
    stable: Boolean,
    components: MarkdownComponents,
    latexEnabled: Boolean
) {
    val sourceLength = source.length.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    val startOffset = node.startOffset.coerceIn(0, sourceLength)
    val endOffset = node.endOffset.coerceIn(startOffset, sourceLength)
    val sourceLengthKey = if (stable) 0L else source.length
    val text = remember(node, startOffset, endOffset, sourceLengthKey) {
        source.slice(startOffset, endOffset)
    }
    val shiftedNode = remember(node, startOffset) { ShiftedMarkdownAstNode(node, startOffset) }
    // The unstable AST slot keeps its last safely renderable source while a LaTeX delimiter is open.
    // A plain holder avoids scheduling an extra Compose recomposition just to remember that snapshot.
    val lastSafeSource = remember(node.type.name, node.startOffset) { LastSafeMarkdownSource() }
    val waitingForLatex = !stable && latexEnabled && MathSplitter.hasUnclosedMathDelimiter(text)
    val fallback = lastSafeSource.value.takeIf { waitingForLatex }
    if (waitingForLatex && fallback == null) {
        // Chunks continue accumulating in StreamTextSnapshot while this tail is withheld.
        return
    }
    val renderedNode = fallback?.node ?: shiftedNode
    val renderedText = fallback?.text ?: text
    // A changing tail used to SHA-256 the entire growing block on the UI thread for
    // every publication. The fingerprint is only useful for reusable *settled* nodes;
    // the transient tail has no persistent cache identity to store.
    val fingerprint = if (stable) remember(renderedText) { markdownContentFingerprint(renderedText) }
        else null
    SideEffect {
        if (!waitingForLatex) {
            lastSafeSource.value = LastSafeMarkdownSource.Value(shiftedNode, text)
        }
    }
    CompositionLocalProvider(
        LocalStreamingTextFade provides !stable,
        LocalMarkdownContentFingerprint provides fingerprint
    ) {
        MarkdownElement(
            node = renderedNode,
            components = components,
            content = renderedText,
            includeSpacer = true
        )
    }
}

private class LastSafeMarkdownSource {
    data class Value(val node: ASTNode, val text: String)
    var value: Value? = null
}

internal fun incrementalBlockSlot(kind: String, index: Int, node: ASTNode): String =
    // Do not include endOffset: the unstable tail grows on every token and must keep its composition slot.
    "incremental-$kind:$index:${node.type.name}:${node.startOffset}"
