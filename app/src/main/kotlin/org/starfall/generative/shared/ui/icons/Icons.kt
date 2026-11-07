@file:Suppress("unused")

package org.starfall.generative.shared.ui.icons

import androidx.compose.ui.graphics.vector.ImageVector
import org.starfall.generative.shared.ui.icons.generated.vscode_codicons.VscodeCodiconsMcp

/** Compatibility facade preserving old Material symbol names while using Compose Fluent vectors. */
object Icons {
    object Outlined : MaterialIconAliases() {
        val DocumentSparkle: ImageVector get() = FluentUi.Regular.DocumentSparkle
        val Mcp: ImageVector get() = VscodeCodiconsMcp
    }

    object Default : MaterialIconAliases()

    object Filled : MaterialIconAliases()

    object AutoMirrored {
        object Filled : MaterialIconAliases()

        object Outlined : MaterialIconAliases()
    }
}
