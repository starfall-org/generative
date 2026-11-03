package org.starfall.generative.features.home.chat.ui.markdown

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import com.mikepenz.markdown.model.State
import org.starfall.generative.features.home.chat.logic.markdown.MarkdownRenderCacheIdentity

/** Parsed stable chunks and the current tail share one measured document tree. */
@Composable
internal fun RenderFrozenMarkdownChunks(
    chunks: List<FrozenMarkdownChunk>,
    tail: String,
    tailUsesPlainTextFallback: Boolean,
    tailSeed: State?,
    identity: MarkdownRenderCacheIdentity?,
    modifier: Modifier = Modifier
) {
    Column(modifier.fillMaxWidth()) {
        chunks.forEachIndexed { index, chunk ->
            key(frozenChunkSlot(index, chunk)) { FrozenMarkdownChunkRow(chunk) }
        }
        if (tail.isNotBlank()) {
            key("frozen-tail") {
                if (tailUsesPlainTextFallback) FrozenStreamingTailText(tail)
                else MarkdownRenderer(
                    content = tail,
                    isStreaming = true,
                    initialParse = tailSeed,
                    cacheIdentity = identity?.forBlock("chunk-tail-${chunks.size}")
                )
            }
        }
    }
}

/** Shared data type with the chunker; its stable text fingerprint is computed off Main at freeze time. */
internal class FrozenMarkdownChunk(
    val text: String,
    val parsed: State,
    val cacheIdentity: MarkdownRenderCacheIdentity?,
    val fingerprint: String
)

private fun frozenChunkSlot(index: Int, chunk: FrozenMarkdownChunk): String =
    "frozen-chunk:$index:${chunk.fingerprint}"

@Composable
private fun FrozenStreamingTailText(text: String) {
    OverflowPlainText(text)
}

@Composable
private fun FrozenMarkdownChunkRow(chunk: FrozenMarkdownChunk) {
    CompositionLocalProvider(
        LocalMarkdownContentFingerprint provides chunk.fingerprint
    ) {
        MarkdownRenderer(
            content = chunk.text,
            isStreaming = false,
            initialParse = chunk.parsed,
            cacheIdentity = chunk.cacheIdentity,
            modifier = Modifier.fillMaxWidth()
        )
    }
}
