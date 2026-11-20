package org.starfall.generative.shared.ui.providers

import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.Checkbox
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import org.starfall.generative.R
import org.starfall.generative.shared.ui.icons.Icons
import org.starfall.generative.shared.ui.icons.SharedIcon
import org.starfall.generative.shared.ui.menus.DropdownMenu
import org.starfall.generative.shared.ui.menus.DropdownMenuItem

@Composable
internal fun <Field> RowScope.ProviderSearchFilter(
    fields: List<Pair<Field, Int>>,
    defaultField: Field,
    searchFields: Set<Field>,
    searchFilterOpen: Boolean,
    onSearchFieldsChange: (Set<Field>) -> Unit,
    onSearchFilterOpenChange: (Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    IconButton(onClick = { onSearchFilterOpenChange(true) }, modifier = Modifier.testTag("provider_search_filter")) {
        SharedIcon(
            Icons.Outlined.FilterList,
            stringResource(R.string.provider_search_filter),
            tint = if (searchFields == setOf(defaultField))
                MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary
        )
    }
    DropdownMenu(
        expanded = searchFilterOpen,
        onDismissRequest = { onSearchFilterOpenChange(false); onDismiss() }
    ) {
        fields.forEach { (field, label) ->
            val selected = field in searchFields
            DropdownMenuItem(
                text = { Text(stringResource(label)) },
                leadingIcon = { Checkbox(checked = selected, onCheckedChange = null) },
                enabled = !selected || searchFields.size > 1,
                onClick = {
                    onSearchFieldsChange(if (selected) searchFields - field else searchFields + field)
                }
            )
        }
    }
}
