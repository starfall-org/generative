package org.starfall.generative.features.providers.shared.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.starfall.generative.R
import org.starfall.generative.features.providers.shared.model.ModelConnectionProgress
import org.starfall.generative.features.providers.shared.model.ModelConnectionStatus
import org.starfall.generative.shared.ui.theme.warningColor
import org.starfall.generative.shared.ui.icons.SharedIcon
import org.starfall.generative.shared.ui.icons.FluentUi

/**
 * Presentation shared by the connection test dialog rows and the editor status strip, so an outcome
 * always reads the same way: passed, warning for an empty reply, or failed.
 */
@Composable
internal fun ModelConnectionStatus.icon(): ImageVector = when (this) {
    ModelConnectionStatus.PASSED -> FluentUi.Regular.CheckmarkCircle
    // An exclamation in a circle marks "answered, but with nothing to show"; the bell-shaped Alert glyph stays on notifications.
    ModelConnectionStatus.WARNING -> FluentUi.Regular.ErrorCircle
    ModelConnectionStatus.FAILED -> FluentUi.Regular.DismissCircle
}

/** Accent for the outcome; a warning is drawn in the app's amber so it reads as caution, not failure. */
@Composable
internal fun ModelConnectionStatus.accent(): Color = when (this) {
    ModelConnectionStatus.PASSED -> MaterialTheme.colorScheme.primary
    ModelConnectionStatus.WARNING -> warningColor()
    ModelConnectionStatus.FAILED -> MaterialTheme.colorScheme.error
}

/** Body color for the outcome message; warnings repeat the same amber as their icon. */
@Composable
internal fun ModelConnectionStatus.textColor(): Color = when (this) {
    ModelConnectionStatus.PASSED -> MaterialTheme.colorScheme.onSurface
    ModelConnectionStatus.WARNING -> warningColor()
    ModelConnectionStatus.FAILED -> MaterialTheme.colorScheme.error
}

/** Text describing the outcome, falling back to the transport error message for failures. */
@Composable
internal fun ModelConnectionStatus.message(result: Result<String>): String = when (this) {
    // The provider answered without any text, so say only that instead of claiming a successful reply.
    ModelConnectionStatus.WARNING -> stringResource(R.string.connection_no_content)
    else -> result.getOrNull()?.takeIf { it.isNotBlank() }
        ?: result.exceptionOrNull()?.localizedMessage ?: stringResource(R.string.connection_failed)
}

/**
 * Icon-only counters for a test run, sized for the dialog header and the editor status strip: an
 * answered/total figure plus one chip per outcome with models. Outcomes with no models are left out.
 * Screen readers still get words through the chip descriptions.
 */
@Composable
internal fun ModelConnectionCounts(progress: ModelConnectionProgress, showProgress: Boolean, modifier: Modifier = Modifier) {
    val counts = listOf(
        ModelConnectionStatus.PASSED to progress.passed,
        ModelConnectionStatus.WARNING to progress.warnings,
        ModelConnectionStatus.FAILED to progress.failures
    ).filter { it.second > 0 }
    if (!showProgress && counts.isEmpty()) return
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (showProgress) CountChip(
            icon = FluentUi.Regular.NetworkCheck,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            display = "${progress.tested}/${progress.total}",
            description = stringResource(R.string.connection_count_progress, progress.tested, progress.total)
        )
        counts.forEach { (status, count) ->
            CountChip(
                icon = status.icon(),
                tint = status.accent(),
                display = count.toString(),
                description = stringResource(status.countDescription(), count)
            )
        }
    }
}

@Composable
private fun CountChip(icon: ImageVector, tint: Color, display: String, description: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = description }
    ) {
        SharedIcon(icon, null, tint = tint, modifier = Modifier.size(14.dp))
        Text(display, style = MaterialTheme.typography.labelLarge, color = tint)
    }
}

/** Resource holding the words for one count chip; the chip itself only shows an icon and a number. */
private fun ModelConnectionStatus.countDescription(): Int = when (this) {
    ModelConnectionStatus.PASSED -> R.string.connection_count_passed
    ModelConnectionStatus.WARNING -> R.string.connection_count_warning
    ModelConnectionStatus.FAILED -> R.string.connection_count_failed
}
