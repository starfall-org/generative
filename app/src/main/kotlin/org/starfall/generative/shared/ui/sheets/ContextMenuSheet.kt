package org.starfall.generative.shared.ui.sheets

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Icon

/** One contextual action. Input selection and completion controls do not belong here. */
data class ContextMenuAction(
    val label: String,
    val onClick: () -> Unit,
    val icon: ImageVector? = null,
    @DrawableRes val iconRes: Int? = null,
    val enabled: Boolean = true,
    val destructive: Boolean = false,
    val destructiveColor: Color? = null
) {
    init {
        require(icon != null || iconRes != null) { "ContextMenuAction requires an icon" }
    }
}

/** Headerless action sheet used for contextual actions formerly shown in a DropdownMenu. */
@Composable
fun ContextMenuSheet(
    actions: List<ContextMenuAction>,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    contentBehindNavigationBar: Boolean = false
) {
    BottomSheet(
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        contentBehindNavigationBar = contentBehindNavigationBar
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            actions.forEachIndexed { index, action ->
                val actionColor = if (action.destructive) {
                    action.destructiveColor ?: MaterialTheme.colorScheme.error
                } else MaterialTheme.colorScheme.onSurface
                Surface(
                    onClick = {
                        onDismissRequest()
                        action.onClick()
                    },
                    enabled = action.enabled,
                    modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp),
                    shape = RoundedCornerShape(12.dp),
                    color = Color.Transparent,
                    contentColor = actionColor
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        action.icon?.let { androidx.compose.material3.Icon(it, null) }
                        action.iconRes?.let { Icon(painterResource(it), null) }
                        Text(action.label, color = actionColor, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        }
    }
}
