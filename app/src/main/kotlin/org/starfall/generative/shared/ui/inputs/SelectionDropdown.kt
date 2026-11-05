package org.starfall.generative.shared.ui.inputs

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import org.starfall.generative.shared.ui.menus.DropdownMenuItem
import org.starfall.generative.shared.ui.menus.ExposedDropdownMenu

/**
 * Read-only Material 3 selection field. The caller owns the selection, while this component
 * owns only its transient menu state. Appearance comes from the shared menu component.
 *
 * [displayValue] allows a short selected label or placeholder independent of menu labels.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> SelectionDropdown(
    label: String,
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    displayValue: String? = null,
    fieldModifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    val canExpand = enabled && options.isNotEmpty()
    val menuExpanded = expanded && canExpand
    LaunchedEffect(canExpand) { if (!canExpand) expanded = false }
    val menuColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.9f)

    ExposedDropdownMenuBox(
        expanded = menuExpanded,
        onExpandedChange = { if (canExpand) expanded = it },
        modifier = modifier
    ) {
        OutlinedTextField(
            value = displayValue ?: options.firstOrNull { it.first == selected }?.second.orEmpty(),
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            enabled = canExpand,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(menuExpanded) },
            modifier = Modifier.fillMaxWidth()
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled = canExpand)
                .then(fieldModifier)
        )
        ExposedDropdownMenu(menuExpanded, { expanded = false }, containerColor = menuColor) {
            options.forEach { (value, text) ->
                DropdownMenuItem(
                    text = { Text(text) },
                    onClick = {
                        expanded = false
                        onSelect(value)
                    }
                )
            }
        }
    }
}
