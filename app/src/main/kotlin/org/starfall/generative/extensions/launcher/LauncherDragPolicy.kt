package org.starfall.generative.extensions.launcher

import androidx.compose.runtime.Immutable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Activation belongs to the host; use a source handle when immediate drags compete with scrolling. */
sealed interface LauncherDragActivation {
    /** Null follows the platform long-press timeout. */
    data class LongPress(val timeoutMillis: Long? = null) : LauncherDragActivation {
        init { require(timeoutMillis == null || timeoutMillis >= 0) }
    }
    data object AfterSlop : LauncherDragActivation
}

@Immutable
data class LauncherDragStyle(
    val pickupScale: Float = LauncherDragDefaults.PickupScale,
    val pickupDurationMillis: Int = LauncherDragDefaults.PickupDurationMillis,
    val landingDurationMillis: Int = LauncherDragDefaults.LandingDurationMillis,
    val elevation: Dp = 0.dp
) {
    init {
        require(pickupScale.isFinite() && pickupScale > 0f)
        require(pickupDurationMillis >= 0 && landingDurationMillis >= 0)
        require(elevation.value.isFinite() && elevation.value >= 0f)
    }
}

/** Collision is independent of drawing, target priority and the source's touch registration. */
sealed interface LauncherHitTest {
    data object Pointer : LauncherHitTest
    data object VisualCenter : LauncherHitTest

    /** A centered fraction of the target, useful for folder entry inside a larger reorder slot. */
    data class Center(val widthFraction: Float = 0.5f, val heightFraction: Float = 0.5f) : LauncherHitTest {
        init {
            require(widthFraction > 0f && widthFraction <= 1f)
            require(heightFraction > 0f && heightFraction <= 1f)
        }
    }

    /** Minimum fraction of the dragged artwork covered by the target. Edge contact alone never hits. */
    data class Overlap(val minimumFraction: Float = 0.5f) : LauncherHitTest {
        init { require(minimumFraction > 0f && minimumFraction <= 1f) }
    }

    /** Pure predicate in window pixels, for circles, handles or application-specific collision rules. */
    class Custom(val contains: (LauncherDragSession<*>, Rect) -> Boolean) : LauncherHitTest

    fun contains(drag: LauncherDragSession<*>, bounds: Rect): Boolean {
        if (!bounds.isUsable()) return false
        return when (this) {
            Pointer -> bounds.contains(drag.pointer)
            VisualCenter -> bounds.contains(drag.visualCenter)
            is Center -> {
                val half = Offset(bounds.width * widthFraction / 2, bounds.height * heightFraction / 2)
                Rect(bounds.center - half, bounds.center + half).contains(drag.visualCenter)
            }
            is Overlap -> {
                val artwork = drag.dragRegion.translate(drag.shadowBounds.topLeft)
                val intersection = artwork.intersect(bounds)
                artwork.isUsable() && intersection.isUsable() &&
                    intersection.width * intersection.height / (artwork.width * artwork.height) >= minimumFraction
            }
            is Custom -> contains.invoke(drag, bounds)
        }
    }
}

internal fun Rect.isUsable(): Boolean = left.isFinite() && top.isFinite() && right.isFinite() &&
    bottom.isFinite() && width > 0f && height > 0f
