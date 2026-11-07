package org.starfall.generative.shared.ui.sheets

import android.view.View
import android.view.Window
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import kotlin.math.hypot
import org.starfall.generative.shared.ui.background.LocalProfileModalImages
import org.starfall.generative.shared.ui.background.ProfileBackground
import org.starfall.generative.shared.ui.theme.LocalAmoledMode
import org.starfall.generative.shared.ui.theme.modalScrimColor
import org.starfall.generative.shared.ui.layout.AmoledBorderEdge
import org.starfall.generative.shared.ui.layout.amoledEdgeBorder
import org.starfall.generative.shared.ui.layout.modalContainerColor
import androidx.compose.ui.graphics.drawscope.DrawScope

/** Material 3 owns the modal window, gestures, anchors and dismissal animations. */
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
    /** Fill the available height for content that requests an expanded presentation. */
    expandOnOpen: Boolean = false,
    content: @Composable ColumnScope.() -> Unit
) {
    // Expanded is content-sized in ModalBottomSheet. Skip the half-screen stop so
    // even medium/long content opens at its natural height rather than being clipped.
    val sheetState = rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded)
    )
    val amoled = LocalAmoledMode.current
    val sheetImage = LocalProfileModalImages.current.sheet
    val sheetTransparency = LocalProfileModalImages.current.sheetTransparency
    val scrimColor = modalScrimColor(BottomSheetDefaults.ScrimColor)
    val settledBrush = remember { SettledBrush() }
    var containerOriginOnScreen by remember { mutableStateOf(Offset.Zero) }
    val hostContext = LocalContext.current
    val hostConfiguration = LocalConfiguration.current
    val hostResources = LocalResources.current

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        modifier = modifier.testTag("app-bottom-sheet"),
        sheetState = sheetState,
        sheetMaxWidth = androidx.compose.ui.unit.Dp.Unspecified,
        shape = shape,
        containerColor = modalContainerColor(containerColor),
        scrimColor = scrimColor,
        dragHandle = null,
        contentWindowInsets = { WindowInsets.statusBars.only(WindowInsetsSides.Top) },
        properties = ModalBottomSheetProperties(shouldDismissOnBackPress = dismissOnBack)
    ) {
        // Dialog owns a separate window and may restore the device locale. Carry over the
        // runtime language used by the parent screen without recreating the Activity.
        CompositionLocalProvider(
            LocalContext provides hostContext,
            LocalConfiguration provides hostConfiguration,
            LocalResources provides hostResources
        ) {
            ConfigureBottomSheetWindow(navigationBarColor ?: containerColor)
            Box(
                Modifier.fillMaxWidth().testTag("bottom-sheet-window")
                    .then(if (amoled) Modifier.amoledEdgeBorder(
                        shape, MaterialTheme.colorScheme.outlineVariant, 1.dp, AmoledBorderEdge.Top
                    ) else Modifier)
            ) {
                ProfileBackground(sheetImage, Modifier.matchParentSize(), sheetTransparency)
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
                        .then(if (fixedHeightFraction != null)
                            Modifier.fillMaxHeight(fixedHeightFraction.coerceIn(0f, 1f))
                        else if (expandOnOpen) Modifier.fillMaxHeight()
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
