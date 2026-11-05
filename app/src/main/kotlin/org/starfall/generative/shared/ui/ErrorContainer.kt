package org.starfall.generative.shared.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.starfall.generative.R
import org.starfall.generative.shared.ui.icons.Icons
import org.starfall.generative.shared.ui.icons.SharedIcon
import org.starfall.generative.shared.ui.layout.appearanceContainerColor
import org.starfall.generative.shared.ui.theme.LocalDarkMode
import org.starfall.generative.shared.ui.theme.warningColor
import org.starfall.generative.shared.ui.text.GatedSelectionContainer

/** Message presentation only; existing error data and retry mechanics are unchanged. */
enum class AlertSeverity { ERROR, WARNING }

/** Consistent, selectable error/warning details with optional actions and built-in copying. */
@Composable
fun ErrorContainer(
    message: String,
    modifier: Modifier = Modifier,
    title: String? = null,
    onRetry: (() -> Unit)? = null,
    onDismiss: (() -> Unit)? = null,
    copyText: String = message,
    initiallyExpanded: Boolean = false,
    expandable: Boolean = true,
    collapsedLines: Int = 3,
    messageStyle: TextStyle? = null,
    copyButtonModifier: Modifier = Modifier.testTag("error-container-copy"),
    severity: AlertSeverity = AlertSeverity.ERROR
) {
    val context = LocalContext.current
    val localizedResources = LocalResources.current
    var expanded by remember(message, initiallyExpanded, severity) { mutableStateOf(initiallyExpanded) }
    val isWarning = severity == AlertSeverity.WARNING
    val accent = if (isWarning) warningColor() else MaterialTheme.colorScheme.error
    val foreground = if (isWarning) MaterialTheme.colorScheme.onSurface
        else MaterialTheme.colorScheme.onErrorContainer
    val containerColor = when {
        !isWarning -> MaterialTheme.colorScheme.errorContainer
        LocalDarkMode.current -> Color(0xFF423310)
        else -> Color(0xFFFFF2CB)
    }
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = appearanceContainerColor(containerColor, opacityBoost = 0.2f),
        border = BorderStroke(1.dp, accent)
    ) {
        Column(
            Modifier.fillMaxWidth().animateContentSize()
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.weight(1f)
                        .then(if (expandable) Modifier.clickable { expanded = !expanded } else Modifier),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SharedIcon(if (isWarning) Icons.Outlined.Warning else Icons.Outlined.ErrorOutline,
                        null, Modifier.size(18.dp), tint = accent)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        title ?: stringResource(if (isWarning) R.string.common_warning else R.string.common_error),
                        style = MaterialTheme.typography.labelLarge,
                        color = foreground,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (onRetry != null) {
                    TextButton(onClick = onRetry, colors = ButtonDefaults.textButtonColors(contentColor = foreground)) {
                        Text(stringResource(R.string.common_retry))
                    }
                }
                IconButton(
                    onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText(
                            localizedResources.getString(if (isWarning) R.string.common_warning else R.string.message_error_clip_label),
                            copyText
                        ))
                        Toast.makeText(context, localizedResources.getString(
                            if (isWarning) R.string.message_warning_copied else R.string.message_error_copied
                        ), Toast.LENGTH_SHORT).show()
                    },
                    modifier = copyButtonModifier.size(32.dp)
                ) {
                    SharedIcon(Icons.Outlined.ContentCopy,
                        stringResource(if (isWarning) R.string.common_copy else R.string.message_copy_error),
                        modifier = Modifier.size(18.dp), tint = foreground)
                }
                if (onDismiss != null) {
                    IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp).testTag("error-container-dismiss")) {
                        SharedIcon(Icons.Outlined.Close,
                            stringResource(if (isWarning) R.string.common_close else R.string.chat_dismiss_error),
                            modifier = Modifier.size(18.dp), tint = foreground)
                    }
                }
                if (expandable) {
                    IconButton(
                        onClick = { expanded = !expanded },
                        modifier = Modifier.size(32.dp).testTag("error-container-toggle")
                    ) {
                        SharedIcon(
                            if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                            stringResource(when {
                                isWarning && expanded -> R.string.collapse
                                isWarning -> R.string.expand
                                expanded -> R.string.message_collapse_error
                                else -> R.string.message_expand_error
                            }),
                            modifier = Modifier.size(18.dp), tint = foreground
                        )
                    }
                }
            }
            GatedSelectionContainer {
                Text(
                    message,
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    color = foreground,
                    style = messageStyle ?: MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    maxLines = if (expandable && !expanded) collapsedLines.coerceAtLeast(1) else Int.MAX_VALUE,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
