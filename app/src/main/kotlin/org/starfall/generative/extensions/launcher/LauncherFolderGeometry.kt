package org.starfall.generative.extensions.launcher

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size

/** Pure measured cell geometry; independent of any Compose list/grid renderer. */
object LauncherFolderGeometry {
    /** Lazy item offsets are already physical in RTL; animated tile bounds are not used. */
    fun slot(
        viewport: Rect,
        x: Float,
        y: Float,
        width: Float,
        height: Float,
        leftPaddingPx: Float,
        topPaddingPx: Float
    ): Rect? {
        if (!viewport.isUsable() || width <= 0f || height <= 0f) return null
        val candidate = Rect(
            Offset(viewport.left + leftPaddingPx + x, viewport.top + topPaddingPx + y),
            Size(width, height)
        )
        return candidate.intersect(viewport).takeIf { it.isUsable() }
    }
}
