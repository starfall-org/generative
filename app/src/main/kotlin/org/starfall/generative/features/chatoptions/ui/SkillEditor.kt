package org.starfall.generative.features.chatoptions.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.starfall.generative.R
import org.starfall.generative.shared.model.AgentSkill
import org.starfall.generative.shared.model.agentSkillDocument
import org.starfall.generative.shared.model.parseAgentSkillDocument
import org.starfall.generative.shared.ui.icons.SharedIcon
import org.starfall.generative.shared.ui.icons.Icons
import org.starfall.generative.shared.ui.screens.EditorScreenTemplate
import org.starfall.generative.shared.ui.inputs.EditorTextField

/** Edit and exchange one complete SKILL.md, not three disconnected form fields. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SkillEditor(
    skill: AgentSkill?,
    onBack: (AgentSkill?) -> Unit
) {
    val key = skill?.id.orEmpty()
    val starter = AgentSkill(
        id = "",
        name = stringResource(R.string.agent_skill_new_name),
        description = stringResource(R.string.agent_skill_description_template),
        content = stringResource(R.string.agent_skill_template)
    )
    var document by rememberSaveable(key) { mutableStateOf(agentSkillDocument(skill ?: starter)) }
    val parsed = parseAgentSkillDocument(document, preserveEmptyExtras = true)

    fun draft(): AgentSkill? = parsed?.copy(id = skill?.id.orEmpty())

    EditorScreenTemplate(
        title = {
            Text(
                parsed?.name ?: stringResource(R.string.agent_skill_details),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        onBack = { onBack(draft()) },
        actions = {
            IconButton(enabled = parsed != null, onClick = { onBack(draft()) }) {
                SharedIcon(Icons.Outlined.Save, stringResource(R.string.agent_skill_save))
            }
            Spacer(Modifier.width(4.dp))
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // A lightweight file tab, instead of a labeled, rounded input box.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("SKILL.md", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary)
                Text(
                    stringResource(R.string.agent_skill_editor_line_count, document.count { it == '\n' } + 1),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
            EditorTextField(
                value = document,
                onValueChange = { document = it },
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false
                ),
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 16.dp)
                    .testTag("agent_skill_document_input"),
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 14.sp,
                    lineHeight = 21.sp
                )
            )
            if (parsed == null) {
                Text(
                    stringResource(R.string.agent_skill_invalid_document),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 10.dp),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}
