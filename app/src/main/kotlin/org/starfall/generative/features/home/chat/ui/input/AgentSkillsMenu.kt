package org.starfall.generative.features.home.chat.ui.input

import org.starfall.generative.shared.ui.lists.FadingLazyColumn
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupPositionProvider
import org.starfall.generative.R
import org.starfall.generative.shared.model.AgentSkill
import org.starfall.generative.shared.ui.icons.SharedIcon
import org.starfall.generative.shared.ui.icons.Icons

@Composable
internal fun AgentSkillsMenu(
    skills: List<AgentSkill>,
    query: String,
    onSelect: (AgentSkill) -> Unit,
    onDismiss: () -> Unit,
    onOpenSkillsSettings: (() -> Unit)? = null,
    modifier: Modifier = Modifier.fillMaxWidth()
) {
    val normalizedQuery = query.trim()
    val matchingSkills = skills.filter { skill ->
            normalizedQuery.isBlank() ||
            skill.name.contains(normalizedQuery, ignoreCase = true) ||
            skill.description.contains(normalizedQuery, ignoreCase = true) ||
            skill.content.contains(normalizedQuery, ignoreCase = true)
    }

    Surface(
        modifier = modifier
            .heightIn(max = 280.dp)
            .testTag("agent-skills-menu"),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.5f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
        tonalElevation = 2.dp
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    stringResource(R.string.agent_skills_menu_title),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f)
                )
                onOpenSkillsSettings?.let { openSettings ->
                    IconButton(
                        onClick = openSettings,
                        modifier = Modifier.testTag("agent-skills-manage")
                    ) {
                        SharedIcon(
                            Icons.Outlined.Settings,
                            stringResource(R.string.agent_skills_manage)
                        )
                    }
                }
                IconButton(onClick = onDismiss) {
                    SharedIcon(Icons.Default.Close, stringResource(R.string.common_close))
                }
            }
            when {
                skills.isEmpty() -> Text(
                    stringResource(R.string.agent_skills_menu_empty),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                matchingSkills.isEmpty() -> Text(
                    stringResource(R.string.agent_skills_menu_no_matches),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                else -> FadingLazyColumn(
                    modifier = Modifier.heightIn(max = 220.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    items(matchingSkills, key = { it.id }) { skill ->
                        Surface(
                            onClick = { onSelect(skill) },
                            modifier = Modifier.fillMaxWidth(),
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0f)
                        ) {
                            Column(
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                                verticalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                Text(
                                    skill.name,
                                    style = MaterialTheme.typography.titleMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    skill.description.ifBlank { skill.content },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

internal fun agentSkillMenuQuery(text: String): String =
    text.removePrefix("/").takeWhile { !it.isWhitespace() }

internal class AgentSkillsMenuPositionProvider(
    private val verticalGapPx: Int
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize
    ): IntOffset {
        val left = when (layoutDirection) {
            LayoutDirection.Ltr -> anchorBounds.left + (anchorBounds.width - popupContentSize.width) / 2
            LayoutDirection.Rtl -> anchorBounds.right - popupContentSize.width -
                (anchorBounds.width - popupContentSize.width) / 2
        }
        val preferredTop = anchorBounds.top - popupContentSize.height - verticalGapPx
        val top = if (preferredTop >= 0) {
            preferredTop
        } else {
            anchorBounds.bottom + verticalGapPx
        }
        return IntOffset(
            x = left.coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0)),
            y = top.coerceIn(0, (windowSize.height - popupContentSize.height).coerceAtLeast(0))
        )
    }
}

/** Show the selected skill as a slash mention, without inserting SKILL.md. */
internal fun String.withSelectedSkillInvocation(name: String): String {
    val instructions = if (startsWith('/')) {
        val commandEnd = indexOfFirst(Char::isWhitespace)
        if (commandEnd < 0) "" else substring(commandEnd).trimStart()
    } else trimStart()
    return "/$name" + if (instructions.isEmpty()) "" else " $instructions"
}

/** Removing or changing the visible invocation removes its activation provenance. */
internal fun String.hasSelectedSkillInvocation(name: String): Boolean {
    val prefix = "/$name"
    return startsWith(prefix) && (length == prefix.length || get(prefix.length).isWhitespace())
}
