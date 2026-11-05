package org.starfall.generative.features.providers.list.ui

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
import org.starfall.generative.features.providers.list.logic.ProviderSearchField
import org.starfall.generative.shared.ui.icons.Icons
import org.starfall.generative.shared.ui.icons.SharedIcon
import org.starfall.generative.shared.ui.menus.DropdownMenu
import org.starfall.generative.shared.ui.menus.DropdownMenuItem

/** Providers alone adds attribute filters; search editing and the AppBar belong to ListScreenTemplate. */
@Composable
internal fun RowScope.ProviderSearchFilter(
    searchFields: Set<ProviderSearchField>,
    searchFilterOpen: Boolean,
    onSearchFieldsChange: (Set<ProviderSearchField>) -> Unit,
    onSearchFilterOpenChange: (Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    IconButton(onClick = { onSearchFilterOpenChange(true) }, modifier = Modifier.testTag("provider_search_filter")) {
        SharedIcon(
            Icons.Outlined.FilterList,
            stringResource(R.string.provider_search_filter),
            tint = if (searchFields == setOf(ProviderSearchField.PROVIDER_NAME))
                MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary
        )
    }
    DropdownMenu(
        expanded = searchFilterOpen,
        onDismissRequest = { onSearchFilterOpenChange(false); onDismiss() }
    ) {
        listOf(
            ProviderSearchField.PROVIDER_NAME to R.string.provider_search_provider_name,
            ProviderSearchField.FOLDER_NAME to R.string.common_folder_name,
            ProviderSearchField.MODEL_NAME to R.string.provider_search_model_name
        ).forEach { (field, label) ->
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
