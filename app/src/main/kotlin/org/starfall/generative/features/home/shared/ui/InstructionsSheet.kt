package org.starfall.generative.features.home.shared.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.starfall.generative.R
import org.starfall.generative.shared.model.SavedInstruction
import org.starfall.generative.shared.ui.icons.SharedIcon
import org.starfall.generative.shared.ui.icons.Icons
import org.starfall.generative.shared.ui.layout.groupedItemShape
import org.starfall.generative.shared.ui.layout.prominentContainerBorder
import org.starfall.generative.shared.ui.layout.prominentContainerColor
import org.starfall.generative.shared.ui.sheets.BottomSheet

/** Bottom sheet that toggles instructions for the configured conversation, folder or global scope. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InstructionsSheet(onDismiss: () -> Unit) {
    BottomSheet(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    ) {
        InstructionsSheetContent(onLeave = onDismiss)
    }
}

/** [onLeave] runs just before the sheet hands over to Chat Options, so the caller can close it. */
@Composable
internal fun InstructionsSheetContent(
    onBack: (() -> Unit)? = null,
    onLeave: () -> Unit = {}
) {
    val controls = LocalInstructionControls.current
    val library = controls.library
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(bottom = 24.dp)
    ) {
        QuickActionsSubpageHeader(
            title = stringResource(R.string.common_instructions),
            onBack = onBack,
            actions = {
                IconButton(
                    onClick = {
                        onLeave()
                        controls.openInstructions()
                    },
                    modifier = Modifier.size(40.dp)
                ) {
                    SharedIcon(
                        Icons.Outlined.Settings,
                        stringResource(R.string.instruction_open_instructions)
                    )
                }
            }
        )
        if (library.instructions.isEmpty()) {
            Text(
                stringResource(R.string.instruction_library_empty),
                modifier = Modifier.padding(top = 16.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                library.instructions.forEachIndexed { index, instruction ->
                    InstructionToggleRow(
                        instruction = instruction,
                        active = instruction.id in library.selectedIds,
                        shape = groupedItemShape(index, library.instructions.size, outerCorner = 20.dp, innerCorner = 6.dp),
                        onToggle = { controls.setInstructionEnabled(instruction.id, it) },
                        onEdit = {
                            onLeave()
                            controls.editInstruction(instruction.id)
                        }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun InstructionToggleRow(
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
