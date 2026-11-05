package org.starfall.generative.shared.ui.menus

import androidx.annotation.DrawableRes
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import org.starfall.generative.shared.ui.icons.SharedIcon

/** A contextual action with a required icon, independent of its presentation. */
data class ContextMenuAction(
    val label: String,
    val onClick: () -> Unit,
    val icon: ImageVector? = null,
    @DrawableRes val iconRes: Int? = null,
    val enabled: Boolean = true,
    val destructive: Boolean = false,
    val destructiveColor: Color? = null,
    val trailingContent: (@Composable () -> Unit)? = null
) {
    init {
        require(icon != null || iconRes != null) { "ContextMenuAction requires an icon" }
    }
}

/** Anchored contextual actions, using the shared appearance-aware DropdownMenu. */
@Composable
fun ContextDropdownMenu(
    expanded: Boolean,
    actions: List<ContextMenuAction>,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = modifier
    ) {
        actions.forEach { action ->
            val actionColor = if (action.destructive) {
                action.destructiveColor ?: MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurface
            }
            DropdownMenuItem(
                text = { Text(action.label, style = MaterialTheme.typography.bodyLarge) },
                onClick = {
                    onDismissRequest()
                    action.onClick()
                },
                leadingIcon = {
                    action.icon?.let { SharedIcon(it, contentDescription = null) }
                    action.iconRes?.let { Icon(painterResource(it), contentDescription = null) }
                },
                trailingIcon = action.trailingContent,
                enabled = action.enabled,
                colors = MenuDefaults.itemColors(
                    textColor = actionColor,
                    leadingIconColor = actionColor,
                    trailingIconColor = actionColor,
                    disabledTextColor = actionColor.copy(alpha = 0.38f),
                    disabledLeadingIconColor = actionColor.copy(alpha = 0.38f),
                    disabledTrailingIconColor = actionColor.copy(alpha = 0.38f)
                )
            )
        }
    }
}
