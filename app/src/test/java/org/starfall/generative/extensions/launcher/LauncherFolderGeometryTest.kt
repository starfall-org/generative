package org.starfall.generative.extensions.launcher

import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The folder's logical insertion cells must never depend on animated UI bounds. */
class LauncherFolderGeometryTest {
    private val viewport = Rect(100f, 200f, 500f, 600f)

    @Test fun rowSlotUsesMeasuredItemOffsetAndPadding() {
        assertEquals(
            Rect(116f, 242f, 484f, 322f),
            LauncherFolderGeometry.slot(viewport, 0f, 34f, 368f, 80f, 16f, 8f)
        )
    }

    @Test fun clippingDoesNotCreateDropAreasOutsideTheFolder() {
        assertEquals(
            Rect(116f, 200f, 484f, 228f),
            LauncherFolderGeometry.slot(viewport, 0f, -60f, 368f, 80f, 16f, 8f)
        )
        assertNull(LauncherFolderGeometry.slot(viewport, 0f, -250f, 368f, 80f, 16f, 8f))
    }

    @Test fun logicalGridSlotKeepsPhysicalHorizontalOffset() {
        assertEquals(
            Rect(316f, 208f, 484f, 308f),
            LauncherFolderGeometry.slot(viewport, 200f, 0f, 168f, 100f, 16f, 8f)
        )
    }
}
