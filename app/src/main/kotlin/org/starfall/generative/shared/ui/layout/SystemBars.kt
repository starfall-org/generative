package org.starfall.generative.shared.ui.layout

import androidx.activity.ComponentActivity
import android.graphics.Color
import android.os.Build
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge

/** Applies Chat's edge-to-edge, genuinely transparent system bars to every visible Activity host. */
fun ComponentActivity.enableTransparentSystemBars() {
    // AndroidX owns platform-specific edge-to-edge and display-cutout compatibility.
    enableEdgeToEdge(
        statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
        navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT)
    )
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        window.isNavigationBarContrastEnforced = false
    }
}
