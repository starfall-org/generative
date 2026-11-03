package org.starfall.generative.features.home.chat.ui.markdown

import android.graphics.drawable.Drawable
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.width
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import org.starfall.generative.features.home.chat.logic.markdown.MarkdownRenderWork
import ru.noties.jlatexmath.JLatexMathDrawable
import kotlin.math.roundToInt
import org.starfall.generative.R
import org.starfall.generative.features.home.chat.ui.AssetKey
import org.starfall.generative.features.home.chat.ui.ChatRenderCaches
import org.starfall.generative.features.home.chat.ui.EvictableCache
import org.starfall.generative.features.home.chat.ui.TrackPendingWork
import org.starfall.generative.features.home.chat.ui.TrackReadyAssets
import org.starfall.generative.shared.ui.text.GatedSelectionContainer

internal enum class LatexDisplayMode {
    Overflow,
    FitWidth;

    fun next(): LatexDisplayMode = when (this) {
        Overflow -> FitWidth
        FitWidth -> Overflow
    }
}

/**
 * Renders one formula with JLatexMath and keeps the source visible while the
 * drawable is being built. The source is also the fallback for unsupported
 * commands and malformed formulas.
 */
@Composable
internal fun LatexFormulaRenderer(
    formula: String,
    textStyle: TextStyle = MaterialTheme.typography.bodyLarge,
    displayMode: Boolean = true,
    modifier: Modifier = Modifier,
    widthMode: LatexDisplayMode = LatexDisplayMode.Overflow
) {
    val color = if (textStyle.color.isSpecified) textStyle.color else MaterialTheme.colorScheme.onSurface
    val textSize = if (textStyle.fontSize.isSpecified) textStyle.fontSize else 16.sp
    val density = androidx.compose.ui.platform.LocalDensity.current
    val textSizePx = with(density) { textSize.toPx().coerceAtLeast(1f) }
    val isDarkTheme = androidx.compose.foundation.isSystemInDarkTheme()
    val cacheKey = latexCacheKey(formula, textSizePx, color.toArgb(), isDarkTheme)
    val result by produceState<LatexRenderResult>(
        initialValue = LatexDrawables.peek(cacheKey)?.let { LatexRenderResult.Ready(it) } ?: LatexRenderResult.Loading,
        key1 = cacheKey
    ) {
        value = try {
            LatexRenderResult.Ready(prepareLatexDrawable(cacheKey))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            android.util.Log.w("LatexRenderer", "Could not render formula", error)
            LatexRenderResult.Fallback(formula, error)
        }
    }

    TrackPendingWork(result == LatexRenderResult.Loading)
    TrackReadyAssets(if (result is LatexRenderResult.Ready) listOf(AssetKey.Latex(cacheKey)) else emptyList())
    when (val renderResult = result) {
        LatexRenderResult.Loading -> LatexFallbackText(formula, textStyle, modifier)
        is LatexRenderResult.Fallback -> LatexFallbackText(renderResult.source, textStyle, modifier)
        is LatexRenderResult.Ready -> {
            RenderReadyLatex(
                drawable = renderResult.drawable,
                modifier = modifier,
                displayMode = displayMode,
                widthMode = widthMode,
                contentDescription = stringResource(R.string.latex_rendered_formula)
            )
        }
    }
}

@Composable
private fun RenderReadyLatex(
    drawable: Drawable,
    modifier: Modifier,
    displayMode: Boolean,
    widthMode: LatexDisplayMode,
    contentDescription: String
) {
    val density = androidx.compose.ui.platform.LocalDensity.current
    val intrinsicWidth = with(density) { drawable.intrinsicWidth.coerceAtLeast(1).toDp() }
    val intrinsicHeight = with(density) { drawable.intrinsicHeight.coerceAtLeast(1).toDp() }
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val boundedWidth = if (constraints.hasBoundedWidth) maxWidth else intrinsicWidth
        val fitWidth = intrinsicWidth.coerceAtMost(boundedWidth).coerceAtLeast(1.dp)
        val fitHeight = if (intrinsicWidth > 0.dp) {
            intrinsicHeight * (fitWidth / intrinsicWidth)
        } else intrinsicHeight
        val imageWidth = if (widthMode == LatexDisplayMode.FitWidth) fitWidth else intrinsicWidth
        val imageHeight = if (widthMode == LatexDisplayMode.FitWidth) fitHeight else intrinsicHeight
        val image: @Composable () -> Unit = {
            Image(
                painter = rememberLatexDrawablePainter(drawable),
                contentDescription = contentDescription,
                modifier = Modifier.width(imageWidth).height(imageHeight),
                contentScale = ContentScale.FillBounds
            )
        }
        if (widthMode == LatexDisplayMode.Overflow && intrinsicWidth > boundedWidth) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                contentAlignment = if (displayMode) Alignment.Center else Alignment.CenterStart
            ) {
                Box(Modifier.width(intrinsicWidth).height(intrinsicHeight)) { image() }
            }
        } else {
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = if (displayMode) Alignment.Center else Alignment.CenterStart
            ) {
                image()
            }
        }
    }
}

@Composable
private fun LatexFallbackText(source: String, style: TextStyle, modifier: Modifier) {
    GatedSelectionContainer {
        androidx.compose.material3.Text(
            text = latexFallbackSource(source),
            modifier = modifier,
            style = style.copy(fontFamily = FontFamily.Serif)
        )
    }
}

private sealed interface LatexRenderResult {
    data object Loading : LatexRenderResult
    data class Ready(val drawable: Drawable) : LatexRenderResult
    data class Fallback(val source: String, val error: Throwable) : LatexRenderResult
}

internal data class LatexCacheKey(
    val formula: String,
    val textSizePx: Int,
    val colorArgb: Int,
    val isDarkTheme: Boolean
)

internal fun latexCacheKey(
    formula: String,
    textSizePx: Float,
    colorArgb: Int,
    isDarkTheme: Boolean
): LatexCacheKey = LatexCacheKey(
    formula = formula,
    textSizePx = textSizePx.roundToInt().coerceAtLeast(1),
    colorArgb = colorArgb,
    isDarkTheme = isDarkTheme
)

/** The raw source is intentionally preserved when a formula cannot be parsed. */
internal fun latexFallbackSource(source: String): String = source

/** One budget shared with parsing and off-screen preparation, rather than one worker per formula. */
internal suspend fun prepareLatexDrawable(key: LatexCacheKey): Drawable = MarkdownRenderWork.run {
    LatexDrawables.getOrCreate(key)
}

internal object LatexDrawables : EvictableCache {
    private const val MAX_CACHE_WEIGHT = 128_000
    /** Number of actual TeX construction attempts (cache misses), not lookup requests. */
    internal val constructionAttempts = java.util.concurrent.atomic.AtomicLong()
    private val cache = object : LruCache<LatexCacheKey, Result<Drawable>>(MAX_CACHE_WEIGHT) {
        override fun sizeOf(key: LatexCacheKey, value: Result<Drawable>): Int = key.formula.length.coerceAtLeast(1_024)
    }

    init {
        ChatRenderCaches.register(this)
    }

    @Synchronized
    override fun trimToFraction(fraction: Float) {
        if (fraction <= 0f) cache.evictAll() else cache.trimToSize((MAX_CACHE_WEIGHT * fraction).toInt())
    }

    @Synchronized
    fun peek(key: LatexCacheKey): Drawable? = cache.get(key)?.getOrNull()

    fun isPrepared(key: LatexCacheKey): Boolean = cache.get(key) != null

    // LruCache protects its short get/put operations. Never hold a cache monitor while TeX
    // is being laid out: main-thread peek() must remain cheap during background preparation.
    fun getOrCreate(key: LatexCacheKey): Drawable {
        cache.get(key)?.let { return it.getOrThrow() }
        constructionAttempts.incrementAndGet()
        val result = try {
            Result.success<Drawable>(JLatexMathDrawable.builder(key.formula)
                .textSize(key.textSizePx.toFloat()).color(key.colorArgb).build())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Result.failure<Drawable>(error)
        }
        // Unsupported formulas are also cached so live preparation cannot repeatedly rebuild them.
        cache.put(key, result)
        return result.getOrThrow()
    }

    /** Null entries are formulas that could not be laid out (they stay as their source text). */
    fun peekAll(keys: List<LatexCacheKey>): List<Drawable?> = keys.map { peek(it) }

    fun buildOrNull(key: LatexCacheKey): Drawable? = try {
        getOrCreate(key)
    } catch (error: Throwable) {
        android.util.Log.w("LatexRenderer", "Could not render formula: ${key.formula}", error)
        null
    }

    fun buildAll(keys: List<LatexCacheKey>): List<Drawable?> = keys.map { buildOrNull(it) }
}

@Composable
private fun rememberLatexDrawablePainter(drawable: Drawable): Painter {
    return androidx.compose.runtime.remember(drawable) { LatexDrawablePainter(drawable) }
}

internal class LatexDrawablePainter(private val drawable: Drawable) : Painter() {
    override val intrinsicSize = Size(
        drawable.intrinsicWidth.coerceAtLeast(1).toFloat(),
        drawable.intrinsicHeight.coerceAtLeast(1).toFloat()
    )

    override fun DrawScope.onDraw() {
        drawable.setBounds(0, 0, size.width.toInt(), size.height.toInt())
        drawIntoCanvas { canvas -> drawable.draw(canvas.nativeCanvas) }
    }
}
