package org.starfall.generative.shared.ui.sheets

import android.view.View
import android.view.Window
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.flow.first
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.view.WindowCompat
import org.starfall.generative.R
import kotlin.math.hypot
import org.starfall.generative.shared.ui.background.LocalProfileModalImages
import org.starfall.generative.shared.ui.background.ProfileBackground
import org.starfall.generative.shared.theme.LocalAmoledMode
import org.starfall.generative.shared.theme.modalScrimColor
import org.starfall.generative.shared.ui.layout.amoledSheetBorder
import org.starfall.generative.shared.ui.layout.modalContainerColor
import androidx.compose.ui.graphics.drawscope.DrawScope

/**
 * Material 3 owns drag anchors, nested scrolling and flings. BottomSheetScaffold
 * supplies a native content-sized peek anchor and an expanded full-height anchor;
 * the dialog retains modal dismissal, insets and window decoration.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BottomSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = BottomSheetDefaults.ExpandedShape,
    containerColor: Color = BottomSheetDefaults.ContainerColor,
    containerBrush: Brush? = null,
    containerOverlay: (DrawScope.() -> Unit)? = null,
    /** Screen-space origin for the selected-model circular reveal. */
    containerBrushRevealOrigin: (() -> Offset?)? = null,
    containerBrushRevealProgress: (() -> Float)? = null,
    containerBrushRetractionProgress: (() -> Float?)? = null,
    navigationBarColor: Color? = null,
    /** Linked sheet pages handle Back in Compose; Material handles it for a root sheet. */
    dismissOnBack: Boolean = true,
    contentBehindNavigationBar: Boolean = false,
    /** Used by media pickers/editors that intentionally occupy a fixed screen fraction. */
    fixedHeightFraction: Float? = null,
    /** Request the expanded anchor after layout; otherwise the peek follows the content. */
    expandOnOpen: Boolean = false,
    content: @Composable ColumnScope.() -> Unit
) {
    // Never force a half-screen initial anchor. A native scaffold peek anchor follows
    // the content height, and Expanded retains the full area below the status bar.
    val sheetState = rememberBottomSheetState(
        initialValue = SheetValue.PartiallyExpanded,
        // Hidden is only for swipe-to-dismiss, not a third visible resting height.
        enabledValues = setOf(SheetValue.Hidden, SheetValue.PartiallyExpanded, SheetValue.Expanded)
    )
    val scaffoldState = rememberBottomSheetScaffoldState(bottomSheetState = sheetState)
    val latestOnDismissRequest by rememberUpdatedState(onDismissRequest)
    LaunchedEffect(sheetState) {
        snapshotFlow { sheetState.currentValue }.collect { value ->
            if (value == SheetValue.Hidden) latestOnDismissRequest()
        }
    }
    val density = LocalDensity.current
    var measuredContentHeight by remember { mutableIntStateOf(0) }
    val peekHeight = with(density) { measuredContentHeight.coerceAtLeast(1).toDp() }
    LaunchedEffect(expandOnOpen, sheetState) {
        if (expandOnOpen) {
            snapshotFlow { sheetState.hasExpandedState }.first { it }
            sheetState.expand()
        }
    }
    val amoled = LocalAmoledMode.current
    val sheetImage = LocalProfileModalImages.current.sheet
    val sheetTransparency = LocalProfileModalImages.current.sheetTransparency
    val scrimColor = modalScrimColor(BottomSheetDefaults.ScrimColor)
    val settledBrush = remember { SettledBrush() }
    var containerOriginOnScreen by remember { mutableStateOf(Offset.Zero) }

    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
            dismissOnBackPress = dismissOnBack
        )
    ) {
        ConfigureBottomSheetWindow(navigationBarColor ?: containerColor)
        Box(Modifier.fillMaxSize().background(scrimColor)) {
            BottomSheetScaffold(
                sheetContent = {
                    Box(
                        Modifier.fillMaxSize().testTag("bottom-sheet-window")
                            .then(if (amoled) Modifier.amoledSheetBorder(
                                sheetState, shape, MaterialTheme.colorScheme.outlineVariant, 1.dp
                            ) else Modifier)
                    ) {
                        // Material translates the full-height surface between native peek/expanded anchors.
                        // Size the photo to the visible area so Crop tracks the sheet as it expands.
                        ProfileBackground(
                            sheetImage,
                            Modifier.matchParentSize().layout { measurable, constraints ->
                                val offset = try { sheetState.requireOffset() } catch (_: IllegalStateException) { 0f }
                                val visibleHeight = (constraints.maxHeight - offset.toInt())
                                    .coerceIn(0, constraints.maxHeight)
                                val photo = measurable.measure(
                                    constraints.copy(minHeight = visibleHeight, maxHeight = visibleHeight)
                                )
                                layout(constraints.maxWidth, constraints.maxHeight) { photo.placeRelative(0, 0) }
                            },
                            sheetTransparency
                        )
                        Box(Modifier.matchParentSize().onGloballyPositioned {
                            containerOriginOnScreen = it.localToScreen(Offset.Zero)
                        }.drawBehind {
                            if (containerBrush == null) {
                                settledBrush.brush = null
                                return@drawBehind
                            }
                            val brushAlpha = sheetDecorationBrushAlpha(sheetImage, sheetTransparency)
                            fun drawTarget() {
                                drawRect(containerBrush, alpha = brushAlpha)
                                containerOverlay?.invoke(this)
                            }
                            val localOrigin = containerBrushRevealOrigin?.invoke()?.let {
                                it - containerOriginOnScreen
                            }
                            val progress = (containerBrushRevealProgress?.invoke() ?: 1f).coerceIn(0f, 1f)
                            val retraction = containerBrushRetractionProgress?.invoke()
                            if (localOrigin != null && retraction != null) {
                                settledBrush.brush = null
                                val radius = hypot(size.width, size.height) * retraction.coerceIn(0f, 1f)
                                clipPath(Path().apply {
                                    addOval(androidx.compose.ui.geometry.Rect(localOrigin, radius))
                                }) { drawTarget() }
                            } else if (localOrigin == null || progress >= 0.52f) {
                                drawTarget()
                                settledBrush.brush = containerBrush
                            } else {
                                settledBrush.brush?.takeIf { it != containerBrush }?.let {
                                    drawRect(it, alpha = brushAlpha)
                                }
                                val radius = 14.dp.toPx() + hypot(size.width, size.height) *
                                    (progress / 0.52f).coerceIn(0f, 1f)
                                clipPath(Path().apply {
                                    addOval(androidx.compose.ui.geometry.Rect(localOrigin, radius))
                                }) { drawTarget() }
                            }
                        })
                        Column(
                            Modifier.fillMaxWidth()
                                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                                .onSizeChanged { measuredContentHeight = it.height }
                                .then(if (fixedHeightFraction != null)
                                    Modifier.fillMaxHeight(fixedHeightFraction.coerceIn(0f, 1f))
                                else Modifier)
                                .then(
                                if (contentBehindNavigationBar) Modifier
                                else Modifier.windowInsetsPadding(
                                    WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)
                                )
                            )
                        ) {
                            Box(
                                Modifier.fillMaxWidth().height(48.dp).testTag("bottom-sheet-drag-handle"),
                                contentAlignment = Alignment.Center
                            ) { BottomSheetDefaults.DragHandle() }
                            content()
                        }
                    }
                },
                scaffoldState = scaffoldState,
                modifier = modifier.fillMaxSize()
                    .windowInsetsPadding(WindowInsets.statusBars.only(WindowInsetsSides.Top))
                    .testTag("app-bottom-sheet"),
                sheetPeekHeight = peekHeight,
                sheetShape = shape,
                sheetContainerColor = modalContainerColor(containerColor),
                sheetDragHandle = null,
                containerColor = Color.Transparent,
                snackbarHost = {}
            ) {
                val dismissLabel = stringResource(R.string.common_close)
                Box(Modifier.fillMaxSize().testTag("bottom-sheet-scrim")
                    .semantics { contentDescription = dismissLabel }
                    .clickable(onClick = onDismissRequest))
            }
        }
    }
}

/** Max-effort decoration stays above a profile photo without hiding it. */
internal fun sheetDecorationBrushAlpha(photo: String?, transparency: Float): Float =
    if (!photo.isNullOrBlank() && transparency.coerceIn(0f, 1f) < 1f) 0.32f else 1f

/** Plain holder written while drawing; deliberately not snapshot state, so it never triggers redraws. */
private class SettledBrush { var brush: Brush? = null }

// Dialog navigation-bar color/divider setters have no direct replacement on older Android versions.
@Suppress("DEPRECATION")
@Composable
private fun ConfigureBottomSheetWindow(navigationBarColor: Color) {
    var view: View? = LocalView.current
    var window: Window? = null
    while (view != null && window == null) {
        if (view is DialogWindowProvider) window = view.window else view = view.parent as? View
    }
    DisposableEffect(window, navigationBarColor) {
        val target = window ?: return@DisposableEffect onDispose { }
        val oldDimAmount = target.attributes.dimAmount
        // The themed scrim is drawn in Compose, including behind the status bar.
        // Avoid a second platform dim layer over the activity.
        target.setDimAmount(0f)
        val oldNavigationBarColor = target.navigationBarColor
        val oldDividerColor = if (android.os.Build.VERSION.SDK_INT >= 28) target.navigationBarDividerColor else null
        val oldContrast = if (android.os.Build.VERSION.SDK_INT >= 29) target.isNavigationBarContrastEnforced else null
        val controller = WindowCompat.getInsetsController(target, target.decorView)
        val oldLightNavigationBars = controller.isAppearanceLightNavigationBars
        val argb = Color.Transparent.toArgb()

        target.navigationBarColor = argb
        if (android.os.Build.VERSION.SDK_INT >= 28) target.navigationBarDividerColor = argb
        if (android.os.Build.VERSION.SDK_INT >= 29) target.isNavigationBarContrastEnforced = false
        controller.isAppearanceLightNavigationBars = navigationBarColor.luminance() > 0.5f

        onDispose {
            target.setDimAmount(oldDimAmount)
            target.navigationBarColor = oldNavigationBarColor
            if (android.os.Build.VERSION.SDK_INT >= 28 && oldDividerColor != null) {
                target.navigationBarDividerColor = oldDividerColor
            }
            if (android.os.Build.VERSION.SDK_INT >= 29 && oldContrast != null) {
                target.isNavigationBarContrastEnforced = oldContrast
            }
            controller.isAppearanceLightNavigationBars = oldLightNavigationBars
        }
    }
}
