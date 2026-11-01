package org.starfall.generative.shared.ui.machines

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import org.starfall.generative.R
import org.starfall.generative.shared.model.RemoteMachine

/** Title and machine subtitle for machine editor dialogs. */
@Composable
internal fun MachineDialogTitle(
    title: String,
    machine: RemoteMachine,
    modifier: Modifier = Modifier
) {
    Column(modifier) {
        Text(
            title,
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            machine.name.ifBlank { stringResource(R.string.remote_server_unnamed) },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
