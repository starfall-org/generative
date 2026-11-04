package org.starfall.generative.shared.ui.model

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.starfall.generative.R
import org.starfall.generative.shared.model.language.ModelConfiguration
import org.starfall.generative.shared.ui.inputs.ExpandableSearchField
import org.starfall.generative.shared.ui.icons.EntityIcon
import org.starfall.generative.shared.ui.icons.SharedIcon
import org.starfall.generative.shared.ui.icons.Icons

/** Expandable search, section controls, and an optional shortcut to the selected model. */
@Composable
internal fun ModelPickerToolbar(
    query: String,
    onQueryChange: (String) -> Unit,
    toggleEnabled: Boolean,
    allCollapsed: Boolean,
    onToggleAllCollapsed: () -> Unit,
    selectedModelId: String,
    selectedModel: ModelConfiguration?,
    onOpenSelectedModel: (() -> Unit)?,
    onSearchBoundsChange: (Rect?) -> Unit = {}
) {
    var searchExpanded by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val modelName = selectedModel?.displayName?.takeIf { it.isNotBlank() }
        ?: selectedModelId.ifBlank { stringResource(R.string.select_model) }
    val openSelectedDescription = stringResource(R.string.open_selected_model)

    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
            .heightIn(min = 56.dp).testTag("model-picker-toolbar"),
            // The outlined search field is taller than the collapsed icon button. Never
            // let losing focus shorten the toolbar and briefly move the bottom sheet.
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        FilledTonalIconButton(
            enabled = toggleEnabled,
            onClick = onToggleAllCollapsed,
            modifier = Modifier.size(40.dp)
        ) {
            SharedIcon(
                if (allCollapsed) Icons.Outlined.ExpandMore else Icons.Outlined.ExpandLess,
                contentDescription = stringResource(
                    if (allCollapsed) R.string.expand_all_model_sections else R.string.collapse_all_model_sections
                )
            )
        }
        ExpandableSearchField(
            value = query,
            onValueChange = onQueryChange,
            expanded = searchExpanded,
            onExpandedChange = { searchExpanded = it },
            placeholder = stringResource(R.string.search_models),
            onBoundsChange = onSearchBoundsChange,
            modifier = Modifier.weight(1f),
            testTag = "model-picker-search"
        )
            if (onOpenSelectedModel != null) {
                androidx.compose.foundation.layout.Box(
                    Modifier.widthIn(max = 180.dp),
                    contentAlignment = Alignment.CenterEnd
                ) {
                    Surface(
                        onClick = {
                            if (searchExpanded) {
                                searchExpanded = false
                                focusManager.clearFocus()
                            }
                            onOpenSelectedModel()
                        },
                        modifier = Modifier.height(40.dp)
                            .testTag("open-selected-model-button")
                            .semantics { contentDescription = openSelectedDescription },
                        shape = RoundedCornerShape(20.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                    ) {
                        Row(
                            modifier = Modifier.padding(start = 8.dp, end = if (searchExpanded) 8.dp else 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(if (searchExpanded) 0.dp else 8.dp)
                        ) {
                            EntityIcon(
                                image = selectedModel?.icon,
                                modifier = Modifier.size(28.dp).testTag("selected-model-icon"),
                                text = modelInitial(selectedModelId),
                                fallback = if (selectedModelId.isBlank()) Icons.Outlined.Add else null,
                                matchName = modelName,
                                fallbackMatchName = selectedModelId,
                                model = true
                            )
                            if (!searchExpanded) {
                                Text(
                                    text = modelName,
                                    modifier = Modifier.weight(1f, fill = false),
                                    style = MaterialTheme.typography.labelMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Clip
                                )
                            }
                        }
                    }
                }
            }
    }
}
