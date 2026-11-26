package org.starfall.generative.shared.ui.providers

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
    types: List<ModelApiType> = ModelApiType.entries,
    onSelected: (ModelApiType) -> Unit
) {
    SelectionDropdown(
        label = stringResource(R.string.provider_api_type),
        options = types.filterNot { it == ModelApiType.GEMINI_CLI }.map { it to it.displayName },
        selected = selected,
        onSelect = onSelected,
        modifier = Modifier.fillMaxWidth(),
        displayValue = selected.takeIf { it == ModelApiType.GEMINI_CLI }?.displayName,
        fieldModifier = Modifier.testTag("provider-type-selector")
    )
}
