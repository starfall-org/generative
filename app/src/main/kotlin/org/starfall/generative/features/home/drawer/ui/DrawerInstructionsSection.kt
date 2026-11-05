package org.starfall.generative.features.home.drawer.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.starfall.generative.R
import org.starfall.generative.features.home.shared.ui.LocalInstructionControls
import org.starfall.generative.shared.model.SavedInstruction
import org.starfall.generative.shared.ui.icons.Icons
import org.starfall.generative.shared.ui.layout.groupedItemShape
import org.starfall.generative.shared.ui.layout.prominentContainerBorder
import org.starfall.generative.shared.ui.layout.prominentContainerColor

/** Toggles the current instruction scope directly in the conversation drawer. */
@Composable
internal fun DrawerInstructionsSection(
    expanded: Boolean,
    onToggle: () -> Unit,
    maxExpandedHeight: Dp,
    modifier: Modifier,
    shape: Shape
) {
    val controls = LocalInstructionControls.current
    val library = controls.library
    val activeCount = library.instructions.count { it.id in library.selectedIds }
    DrawerExpandableCard(
        title = stringResource(R.string.common_instructions),
        subtitle = stringResource(R.string.drawer_instructions_selected, activeCount, library.instructions.size),
        icon = Icons.Outlined.DocumentSparkle,
        expanded = expanded,
        onToggle = onToggle,
        onSettings = controls.openInstructions,
        settingsDescription = stringResource(R.string.instruction_open_instructions),
        headerTag = "drawer_instructions_header",
        maxExpandedHeight = maxExpandedHeight,
        shape = shape,
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.fillMaxWidth()
                .heightIn(max = (maxExpandedHeight - 84.dp).coerceAtLeast(80.dp))
                .verticalScroll(rememberScrollState())
                .testTag("drawer_instructions_list")
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            if (library.instructions.isEmpty()) {
                Text(stringResource(R.string.instruction_library_empty),
                    modifier = Modifier.padding(8.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                library.instructions.forEachIndexed { index, instruction ->
                    DrawerInstructionToggleRow(
                        instruction = instruction,
                        active = instruction.id in library.selectedIds,
                        shape = groupedItemShape(index, library.instructions.size, outerCorner = 20.dp, innerCorner = 6.dp),
                        onToggle = { controls.setInstructionEnabled(instruction.id, it) },
                        onEdit = { controls.editInstruction(instruction.id) }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DrawerInstructionToggleRow(
    instruction: SavedInstruction,
    active: Boolean,
    shape: Shape,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .semantics(mergeDescendants = true) { toggleableState = ToggleableState(active) }
            .combinedClickable(
                role = Role.Switch,
                onLongClickLabel = stringResource(R.string.instruction_edit_accessibility, instruction.name),
                onLongClick = onEdit,
                onClick = { onToggle(!active) }
            ),
        shape = shape,
        color = prominentContainerColor(),
        border = prominentContainerBorder()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    instruction.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Switch(checked = active, onCheckedChange = null)
        }
    }
}
