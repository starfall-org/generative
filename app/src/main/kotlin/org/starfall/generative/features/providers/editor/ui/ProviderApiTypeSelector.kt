package org.starfall.generative.features.providers.editor.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import org.starfall.generative.R
import org.starfall.generative.shared.model.api.ModelApiType
import org.starfall.generative.shared.ui.inputs.SelectionDropdown

@Composable
internal fun ProviderApiTypeSelector(
    selected: ModelApiType,
    onSelected: (ModelApiType) -> Unit
) {
    SelectionDropdown(
        label = stringResource(R.string.provider_api_type),
        options = ModelApiType.entries.map { it to it.displayName },
        selected = selected,
        onSelect = onSelected,
        modifier = Modifier.fillMaxWidth(),
        fieldModifier = Modifier.testTag("provider-type-selector")
    )
}
