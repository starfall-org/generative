package org.starfall.generative.features.chatoptions.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedButton
import org.starfall.generative.shared.ui.inputs.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import org.starfall.generative.R
import org.starfall.generative.shared.ui.dialogs.AlertDialog
import org.starfall.generative.shared.ui.icons.SharedIcon
import org.starfall.generative.shared.ui.icons.Icons

@Composable
internal fun SkillImportDialog(
    busy: Boolean,
    error: String?,
    onChooseFile: () -> Unit,
    onImportGithub: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var url by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.agent_skill_import)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                OutlinedButton(
                    onClick = onChooseFile,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth().testTag("agent_skill_import_file")
                ) {
                    SharedIcon(Icons.Outlined.FileOpen, contentDescription = null)
                    Text(stringResource(R.string.agent_skill_choose_file), Modifier.padding(start = 8.dp))
                }
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text(stringResource(R.string.agent_skill_github_url)) },
                    placeholder = { Text(stringResource(R.string.agent_skill_github_hint)) },
                    modifier = Modifier.fillMaxWidth().testTag("agent_skill_github_url"),
                    singleLine = true,
                    enabled = !busy,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None,
                        autoCorrectEnabled = false, keyboardType = KeyboardType.Password)
                )
                if (busy) CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
                error?.let { Text(it, color = androidx.compose.material3.MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Button(
                onClick = { onImportGithub(url.trim()) },
                enabled = !busy && url.isNotBlank(),
                modifier = Modifier.testTag("agent_skill_import_github")
            ) {
                SharedIcon(Icons.Outlined.Download, contentDescription = null)
                Text(stringResource(R.string.agent_skill_import_from_github), Modifier.padding(start = 8.dp))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) {
                Text(stringResource(R.string.common_close))
            }
        }
    )
}
