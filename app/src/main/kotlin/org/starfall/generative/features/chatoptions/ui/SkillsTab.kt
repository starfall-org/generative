package org.starfall.generative.features.chatoptions.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.layout.size
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import org.starfall.generative.shared.subfeatures.icons.ui.EntityIcon
import org.starfall.generative.shared.ui.icons.Icons
import org.starfall.generative.shared.ui.lists.ReorderableCollection
import org.starfall.generative.shared.ui.lists.ItemCard
import org.starfall.generative.shared.ui.lists.EmptyState
import org.starfall.generative.shared.ui.lists.FadeGridListContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import org.starfall.generative.R
import org.starfall.generative.shared.model.AgentSkill
import org.starfall.generative.shared.model.DEFAULT_AGENT_SKILL_ID
import org.starfall.generative.shared.ui.dialogs.AlertDialog
import org.starfall.generative.shared.ui.menus.ItemContextMenu

@Composable
internal fun SkillsTab(
    skills: List<AgentSkill>,
    onEditSkill: (AgentSkill) -> Unit,
    onDeleteSkill: (AgentSkill) -> Unit,
    onReorderSkills: (List<String>) -> Unit,
    onExportSkill: (AgentSkill) -> Unit = {},
    isGrid: Boolean = false,
    bottomPadding: Dp = 0.dp
) {
    var skillPendingDelete by remember { mutableStateOf<AgentSkill?>(null) }
    var viewingBundledSkill by remember { mutableStateOf<AgentSkill?>(null) }

    if (skills.isEmpty()) {
        EmptyState(Icons.Outlined.Extension, stringResource(R.string.agent_skills_empty))
    } else {
        FadeGridListContent(isGrid = isGrid, modifier = Modifier.fillMaxSize()) { gridMode ->
            ReorderableCollection(
                items = skills, key = { it.id },
                canReorder = { it.id != DEFAULT_AGENT_SKILL_ID },
                columns = GridCells.Fixed(if (gridMode) 2 else 1),
                contentPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 8.dp + bottomPadding),
                modifier = Modifier.fillMaxSize(),
                onReorder = onReorderSkills,
                preview = { skill ->
                    ItemCard(isGrid = gridMode, onClick = {},
                        icon = { EntityIcon(null, Modifier.size(42.dp), fallback = Icons.Outlined.Extension) },
                        actions = {}, content = { Text(skill.name, maxLines = 1) })
                }
            ) { skill, dragModifier ->
                    val bundled = skill.id == DEFAULT_AGENT_SKILL_ID
                    ItemCard(
                        isGrid = gridMode,
                        modifier = dragModifier,
                        onClick = { if (bundled) viewingBundledSkill = skill else onEditSkill(skill) },
                        icon = { EntityIcon(null, Modifier.size(42.dp), fallback = Icons.Outlined.Extension) },
                        actions = {
                            ItemContextMenu(
                                onEdit = { onEditSkill(skill) },
                                onDelete = { skillPendingDelete = skill },
                                deleteColor = MaterialTheme.colorScheme.error,
                                firstActionText = stringResource(R.string.agent_skill_export),
                                firstActionImageVector = Icons.Outlined.Download,
                                onFirstAction = { onExportSkill(skill) },
                                showEdit = !bundled,
                                showDelete = !bundled
                            )
                        },
                        content = {
                            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(skill.name, style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    skill.description.ifBlank {
                                        stringResource(R.string.agent_skill_metadata, skill.content.length,
                                            estimateSkillTokens(skill.content))
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis
                                )
                                Text(skill.content, style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.outline, maxLines = 2,
                                    overflow = TextOverflow.Ellipsis)
                            }
                        }
                    )
            }
        }
    }

    viewingBundledSkill?.let { skill ->
        AlertDialog(
            onDismissRequest = { viewingBundledSkill = null },
            title = { Text(skill.name) },
            scrollableContent = false,
            text = {
                Column(
                    Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(skill.description, style = MaterialTheme.typography.bodyMedium)
                    Text(skill.content, style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(onClick = { viewingBundledSkill = null }) {
                    Text(stringResource(R.string.common_close))
                }
            }
        )
    }

    skillPendingDelete?.let { skill ->
        AlertDialog(
            onDismissRequest = { skillPendingDelete = null },
            title = { Text(stringResource(R.string.dialog_delete_skill_title)) },
            text = { Text(stringResource(R.string.agent_skill_delete_confirmation, skill.name)) },
            confirmButton = {
                TextButton(onClick = {
                    onDeleteSkill(skill)
                    skillPendingDelete = null
                }) {
                    Text(stringResource(R.string.common_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { skillPendingDelete = null }) {
                    Text(stringResource(R.string.common_close))
                }
            }
        )
    }
}

private fun estimateSkillTokens(content: String): Int =
    if (content.isBlank()) 0 else ((content.length + 3) / 4).coerceAtLeast(1)
