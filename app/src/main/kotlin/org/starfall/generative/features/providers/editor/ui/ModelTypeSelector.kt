package org.starfall.generative.features.providers.editor.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import org.starfall.generative.R
import org.starfall.generative.shared.model.language.ModelType
import org.starfall.generative.shared.ui.inputs.SelectionDropdown

@Composable
internal fun ModelTypeSelector(selected: ModelType, onSelected: (ModelType) -> Unit) {
    SelectionDropdown(
        label = stringResource(R.string.model_type),
        options = ModelType.entries.map { it to it.displayName },
        selected = selected,
        onSelect = onSelected,
        modifier = Modifier.fillMaxWidth(),
        fieldModifier = Modifier.testTag("model-type-selector")
    )
}
