package org.starfall.generative.features.home.chat.ui.markdown

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mikepenz.markdown.annotator.annotatorSettings
import com.mikepenz.markdown.annotator.buildMarkdownAnnotatedString
import com.mikepenz.markdown.compose.Markdown
import com.mikepenz.markdown.compose.components.MarkdownComponentModel
import com.mikepenz.markdown.compose.components.MarkdownComponents
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.elements.MarkdownBulletList
import com.mikepenz.markdown.compose.elements.MarkdownOrderedList
import com.mikepenz.markdown.compose.elements.listDepth
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.model.State
import com.mikepenz.markdown.model.markdownPadding
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.getTextInNode
import org.starfall.generative.R
import org.starfall.generative.features.home.chat.logic.markdown.MarkdownRenderCacheIdentity
import org.starfall.generative.features.home.chat.logic.markdown.MathPiece
import org.starfall.generative.features.home.chat.logic.markdown.MathSplitter
import org.starfall.generative.features.home.chat.logic.markdown.PersistentMarkdownAstCache
import org.starfall.generative.features.home.chat.logic.markdown.markdownFenceSource
import org.starfall.generative.features.home.chat.ui.MarkdownParseCache
import org.starfall.generative.shared.ui.code.LocalCodeRenderingPreferences
import org.starfall.generative.shared.ui.icons.SharedIcon
import org.starfall.generative.shared.ui.icons.Icons
import org.starfall.generative.shared.ui.text.GatedSelectionContainer
import org.starfall.generative.shared.ui.text.messageMarkdownTypography
import org.starfall.generative.shared.ui.text.toComposeFontFamily

/** Library primitives with app-owned code toolbar, error and LaTeX UI. */
@Composable
fun MarkdownRenderer(
    content: String,
    modifier: Modifier = Modifier,
    isStreaming: Boolean = false,
    latexMode: String? = null,
    fillMaxWidth: Boolean = true,
    /**
     * The library puts a block-sized spacer before every top-level block, including the first, and none
     * after the last. Inside a padded container (the user bubble) that makes the top gap larger than the
     * bottom one; this removes the leading spacer so both gaps match.
     */
    trimLeadingBlockSpace: Boolean = false,
    /**
     * Parse to show on the first frame instead of [State.Loading]. Used by [ChunkedStreamingMarkdown] for
     * a streaming tail: it may be slightly older than [content], which is the same staleness a
     * streaming renderer already shows while it re-parses.
     */
    initialParse: State? = null,
    cacheIdentity: MarkdownRenderCacheIdentity? = null
) {
    val context = LocalContext.current.applicationContext
    val persistentCache = remember(context) { PersistentMarkdownAstCache.get(context) }
    RegisterMarkdownCacheInvalidators(persistentCache)
    if (content.isBlank()) return
    if (content.length > MAX_MARKDOWN_RENDER_CHARS) {
        // Do not discard the tail of a completed AI response. Markdown parsing is bounded for
        // responsiveness, but the plain-text fallback must preserve the complete source.
        CompletePlainTextMarkdown(content, modifier, fillMaxWidth)
        return
    }
    RenderMarkdownDocument(
        content = content,
        modifier = modifier,
        isStreaming = isStreaming,
        latexMode = latexMode,
        fillMaxWidth = fillMaxWidth,
        trimLeadingBlockSpace = trimLeadingBlockSpace,
        initialParse = initialParse,
        cacheIdentity = cacheIdentity,
        persistentCache = persistentCache
    )
}

@Composable
private fun RegisterMarkdownCacheInvalidators(persistentCache: PersistentMarkdownAstCache) {
    SideEffect {
        persistentCache.registerMemoryInvalidator(MarkdownParseCache) { conversationId, messageId ->
            when {
                conversationId == null -> MarkdownParseCache.clearAll()
                messageId == null -> MarkdownParseCache.invalidateConversation(conversationId)
                else -> MarkdownParseCache.invalidateMessage(conversationId, messageId)
            }
        }
    }
}

@Composable
private fun CompletePlainTextMarkdown(content: String, modifier: Modifier, fillMaxWidth: Boolean) {
    GatedSelectionContainer {
        OverflowPlainText(
            text = content,
            modifier = modifier,
            style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 22.sp),
            expandToWidth = fillMaxWidth
        )
    }
}

@Composable
private fun RenderMarkdownDocument(
    content: String,
    modifier: Modifier,
    isStreaming: Boolean,
    latexMode: String?,
    fillMaxWidth: Boolean,
    trimLeadingBlockSpace: Boolean,
    initialParse: State?,
    cacheIdentity: MarkdownRenderCacheIdentity?,
    persistentCache: PersistentMarkdownAstCache
) {
    val parsed = rememberMarkdownParsePresentation(content, initialParse, cacheIdentity, isStreaming, persistentCache)
    val type = MaterialTheme.typography
    val preferences = LocalCodeRenderingPreferences.current
    val effectiveLatexMode = latexMode ?: preferences.latexMode
    val bodySize = preferences.effectiveMessageFontSize.sp
    val messageFont = preferences.messageFontFamily.toComposeFontFamily()
    val markdownTypography = messageMarkdownTypography()
    // When an action begins, previously streamed text becomes a standalone Markdown block.
    // Parsing that block is asynchronous: never replace already visible text with a blank area.
    if (parsed.state is State.Loading ||
        (parsed.state is State.Success && parsed.state.content != content)
    ) {
        CompletePlainTextMarkdown(content, modifier, fillMaxWidth)
        return
    }
    Markdown(
        state = parsed.state,
        modifier = modifier
            .then(if (trimLeadingBlockSpace) Modifier.trimLeadingSpace(MARKDOWN_BLOCK_PADDING) else Modifier)
            .then(if (fillMaxWidth) Modifier.fillMaxWidth() else Modifier),
        colors = markdownColor(),
        typography = markdownTypography,
        padding = markdownPadding(block = MARKDOWN_BLOCK_PADDING),
        components = chatMarkdownComponents(isStreaming, effectiveLatexMode, fillMaxWidth),
        success = { state, components, successModifier ->
            RenderMarkdownSuccess(
                state = state,
                components = components,
                modifier = successModifier
            )
        },
        error = { Text(content, style = type.bodyLarge.copy(fontFamily = messageFont, fontSize = bodySize)) }
    )
}

@Composable
private fun RenderMarkdownSuccess(
    state: State.Success,
    components: MarkdownComponents,
    modifier: Modifier
) {
    // Every Markdown block is composed in source order and measured by Compose. Lazy loading belongs
    // to the outer conversation list; no child block gets a guessed height or a private viewport.
    MarkdownSuccessWithBoundedHtml(state, components, modifier)
}

internal const val MAX_MARKDOWN_RENDER_CHARS = 128_000
internal const val MAX_INCREMENTAL_UNSTABLE_TAIL_CHARS = 8_000
internal const val MAX_INCREMENTAL_PARSER_APPEND_CHARS = 4_096
internal val MARKDOWN_BLOCK_PADDING = 8.dp

@Composable
internal fun chatMarkdownComponents(
    isStreaming: Boolean,
    latexMode: String,
    fillMaxWidth: Boolean
): MarkdownComponents = markdownComponents(
    codeFence = { model ->
        val fence = markdownFenceSource(model.content, model.node)
        RenderCodeBlock(
            language = fence.language,
            source = model.content,
            startOffset = fence.codeStartOffset,
            endOffset = fence.codeEndOffset,
            isStreaming = isStreaming && !fence.closed && model.node.endOffset == model.content.length
        )
    },
    codeBlock = { model ->
        RenderCodeBlock(
            language = "",
            source = model.content,
            startOffset = model.node.startOffset,
            endOffset = model.node.endOffset,
            isStreaming = false,
            trimTrailingWhitespace = true
        )
    },
    heading1 = { model -> RenderMarkdownHeading(model, model.typography.h1, latexMode, fillMaxWidth) },
    heading2 = { model -> RenderMarkdownHeading(model, model.typography.h2, latexMode, fillMaxWidth) },
    heading3 = { model -> RenderMarkdownHeading(model, model.typography.h3, latexMode, fillMaxWidth) },
    heading4 = { model -> RenderMarkdownHeading(model, model.typography.h4, latexMode, fillMaxWidth) },
    heading5 = { model -> RenderMarkdownHeading(model, model.typography.h5, latexMode, fillMaxWidth) },
    heading6 = { model -> RenderMarkdownHeading(model, model.typography.h6, latexMode, fillMaxWidth) },
    setextHeading1 = { model ->
        RenderMarkdownHeading(model, model.typography.h1, latexMode, fillMaxWidth, MarkdownTokenTypes.SETEXT_CONTENT)
    },
    setextHeading2 = { model ->
        RenderMarkdownHeading(model, model.typography.h2, latexMode, fillMaxWidth, MarkdownTokenTypes.SETEXT_CONTENT)
    },
    paragraph = { model -> RenderParagraph(model, latexMode, fillMaxWidth) },
    orderedList = { model ->
        MarkdownOrderedList(model.content, model.node, style = model.typography.ordered, depth = model.listDepth)
    },
    unorderedList = { model ->
        MarkdownBulletList(model.content, model.node, style = model.typography.bullet, depth = model.listDepth)
    },
    table = { model -> RenderMarkdownTable(model) }
)

/** Cuts [amount] from the top of the content: it is measured in full, then placed higher and reported shorter. */
private fun Modifier.trimLeadingSpace(amount: androidx.compose.ui.unit.Dp): Modifier = layout { measurable, constraints ->
    val shift = amount.roundToPx()
    val placeable = measurable.measure(constraints)
    layout(placeable.width, (placeable.height - shift).coerceAtLeast(0)) { placeable.place(0, -shift) }
}

/**
 * Renders markdown paragraphs and inline formatting without per-chunk animations.
 */
@Composable
private fun RenderParagraph(model: MarkdownComponentModel, latexMode: String, fillMaxWidth: Boolean) {
    val sourceLength = (model.node.endOffset - model.node.startOffset).coerceAtLeast(0)
    val preferences = LocalCodeRenderingPreferences.current
    val bodySize = preferences.effectiveMessageFontSize.sp
    val messageFont = preferences.messageFontFamily.toComposeFontFamily()
    val bodyStyle = MaterialTheme.typography.bodyLarge.copy(
        fontFamily = messageFont,
        fontSize = bodySize,
        lineHeight = bodySize * 1.375f,
        color = MaterialTheme.colorScheme.onSurface
    )
    if (sourceLength > LARGE_PARAGRAPH_EAGER_LIMIT) {
        LargeMarkdownParagraph(
            source = model.content,
            startOffset = model.node.startOffset,
            endOffset = model.node.endOffset,
            style = bodyStyle
        )
        return
    }
    val text = model.node.getTextInNode(model.content).toString()
    val context = LocalContext.current
    val localizedResources = LocalResources.current
    val isError = remember(text) { isErrorText(text) }
    // Null when the paragraph holds no math (or rendering is disabled): the plain Markdown path is kept.
    val mathPieces = remember(text, latexMode) {
        MathSplitter.split(text, latexMode).takeIf { pieces -> pieces.any { it is MathPiece.Math } }
    }

    when {
        isError -> {
            val errorColor = MaterialTheme.colorScheme.error
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.25f),
                border = androidx.compose.foundation.BorderStroke(1.dp, errorColor.copy(alpha = 0.4f)),
                modifier = Modifier
                    .then(if (fillMaxWidth) Modifier.fillMaxWidth() else Modifier)
                    .padding(vertical = 4.dp)
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(modifier = Modifier.weight(1f)) {
                        GatedSelectionContainer {
                            Text(
                                text = text,
                                style = MaterialTheme.typography.bodyLarge.copy(
                                     fontFamily = messageFont,
                                     fontSize = bodySize,
                                     lineHeight = bodySize * 1.375f,
                                    color = errorColor,
                                    fontWeight = FontWeight.Medium
                                )
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    IconButton(
                        onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText(localizedResources.getString(R.string.markdown_error_clip_label), text))
                            Toast.makeText(context, localizedResources.getString(R.string.markdown_copied_error_text), Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.size(28.dp)
                    ) {
                        SharedIcon(
                            imageVector = Icons.Outlined.ContentCopy,
                            contentDescription = stringResource(R.string.message_copy_error),
                            tint = errorColor,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }
        mathPieces != null -> {
            RenderMathParagraph(
                pieces = mathPieces,
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontFamily = messageFont,
                    fontSize = bodySize,
                    lineHeight = bodySize * 1.375f,
                    color = MaterialTheme.colorScheme.onSurface
                ),
                fillMaxWidth = fillMaxWidth
            )
        }
        else -> {
            val settings = annotatorSettings()
            val annotated = buildAnnotatedString {
                    buildMarkdownAnnotatedString(model.content, model.node, settings)
            }
            GatedSelectionContainer {
                MarkdownClickableText(
                    text = annotated,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontFamily = messageFont,
                        fontSize = bodySize,
                        lineHeight = bodySize * 1.375f,
                        color = MaterialTheme.colorScheme.onSurface
                    ),
                     modifier = if (fillMaxWidth) Modifier.fillMaxWidth() else Modifier
                 )
            }
        }
    }
}

private fun isErrorText(text: String): Boolean {
    val trimmed = text.trim()
    return trimmed.startsWith("[Error:", ignoreCase = true) ||
           trimmed.startsWith("Error:", ignoreCase = true) ||
           trimmed.startsWith("API Error", ignoreCase = true) ||
           trimmed.startsWith("Exception:", ignoreCase = true) ||
           trimmed.startsWith("Failed:", ignoreCase = true) ||
           trimmed.contains("[Error:", ignoreCase = true)
}
