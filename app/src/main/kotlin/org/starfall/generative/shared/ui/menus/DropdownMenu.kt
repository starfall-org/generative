package org.starfall.generative.shared.ui.menus

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExposedDropdownMenu as MaterialExposedDropdownMenu
import androidx.compose.material3.ExposedDropdownMenuBoxScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupProperties
import org.starfall.generative.shared.ui.theme.LocalAmoledMode
import org.starfall.generative.shared.ui.layout.prominentContainerBorder
import org.starfall.generative.shared.ui.layout.LocalItemTransparency

/** Menus deliberately keep 30 percentage points more opacity than ordinary items. */
@Composable
private fun menuContainerColor() = MaterialTheme.colorScheme.surfaceContainerHigh.copy(
    alpha = (1f - LocalItemTransparency.current.coerceIn(0f, 1f) + 0.30f).coerceAtMost(1f)
)

/** Menus use 30 percentage points more opacity than the component setting, in every theme. */
@Composable
fun DropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    properties: PopupProperties = PopupProperties(focusable = true),
    content: @Composable ColumnScope.() -> Unit
) {
    val menuColor = menuContainerColor()
    val hostContext = LocalContext.current
    val hostConfiguration = LocalConfiguration.current
    val hostResources = LocalResources.current
    androidx.compose.material3.DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        properties = properties,
        shape = RoundedCornerShape(14.dp),
        containerColor = menuColor,
        tonalElevation = if (LocalAmoledMode.current) 0.dp else 6.dp,
        shadowElevation = if (LocalAmoledMode.current) 0.dp else 8.dp,
        border = prominentContainerBorder(),
        content = {
            // A Popup can have a separate window configuration. Preserve the in-app locale.
            CompositionLocalProvider(
                LocalContext provides hostContext,
                LocalConfiguration provides hostConfiguration,
                LocalResources provides hostResources
            ) { content() }
        }
    )
}

/** Exposed menus retain Material's anchor sizing and keyboard behaviour. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExposedDropdownMenuBoxScope.ExposedDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    containerColor: Color? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val menuColor = containerColor ?: menuContainerColor()
    val hostContext = LocalContext.current
    val hostConfiguration = LocalConfiguration.current
    val hostResources = LocalResources.current
    MaterialExposedDropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        containerColor = menuColor,
        tonalElevation = if (LocalAmoledMode.current) 0.dp else 6.dp,
        shadowElevation = if (LocalAmoledMode.current) 0.dp else 8.dp,
        border = prominentContainerBorder(),
        content = {
            // A Popup can have a separate window configuration. Preserve the in-app locale.
            CompositionLocalProvider(
                LocalContext provides hostContext,
                LocalConfiguration provides hostConfiguration,
                LocalResources provides hostResources
            ) { content() }
        }
    )
}
