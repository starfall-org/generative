@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package org.starfall.generative.features.machines.shared.ui

import org.starfall.generative.shared.ui.layout.edgeToEdgeTopAppBarColors
import org.starfall.generative.shared.ui.ErrorContainer
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.starfall.generative.R
import org.starfall.generative.shared.ui.layout.FixedAppBarTitle
import org.starfall.generative.shared.ui.icons.SharedIcon
import org.starfall.generative.shared.ui.icons.Icons
import org.starfall.generative.shared.ui.layout.SettingsCard
import org.starfall.generative.shared.ui.inputs.OutlinedTextField

/**
 * Shared building blocks for every Remote Machines screen. They deliberately reuse the app's
 * [SettingsCard] so the machines feature follows the same design language as the rest of the
 * settings-style screens instead of rolling its own card, field and row styles.
 */

/** Top app bar shared by the Remote Machines list, settings and editor screens. */
@Composable
internal fun RemoteMachinesTopBar(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    actions: @Composable () -> Unit = {}
) {
    TopAppBar(
        colors = edgeToEdgeTopAppBarColors(),
        modifier = modifier,
        title = {
            if (subtitle == null) FixedAppBarTitle(title) else Column {
                Text(
                    title,
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        },
        navigationIcon = {
            IconButton(onClick = onBack) {
                SharedIcon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.common_back))
            }
        },
        actions = { actions() }
    )
}

/** A titled settings section, styled exactly like the sections on the other settings screens. */
@Composable
internal fun RemoteSection(
    title: String,
    modifier: Modifier = Modifier,
    description: String? = null,
    actions: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit
) {
    SettingsCard(modifier = modifier) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                title,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (actions != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    actions()
                }
            }
        }
        if (description != null) {
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        content()
    }
}

/** Raw SSH/host/path/secret input: no sentence capitalization, autocorrection or IME suggestions. */
internal fun remoteLiteralKeyboardOptions(
    keyboardType: KeyboardType = KeyboardType.Password
) = KeyboardOptions(
    capitalization = KeyboardCapitalization.None,
    autoCorrectEnabled = false,
    keyboardType = keyboardType
)

@Composable
internal fun RemoteTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    readOnly: Boolean = false,
    visual: VisualTransformation = VisualTransformation.None,
    trailingIcon: (@Composable () -> Unit)? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    minLines: Int = 1,
    maxLines: Int = if (minLines > 1) 20 else 1,
    singleLine: Boolean = minLines == 1,
    font: FontFamily? = null
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        modifier = modifier.fillMaxWidth(),
        supportingText = supporting?.let { { Text(it) } },
        readOnly = readOnly,
        visualTransformation = visual,
        trailingIcon = trailingIcon,
        keyboardOptions = keyboardOptions,
        minLines = minLines,
        maxLines = maxLines,
        singleLine = singleLine,
        textStyle = if (font == null) LocalTextStyle.current else LocalTextStyle.current.copy(fontFamily = font)
    )
}

/** A label + switch row, with an optional supporting explanation under the label. */
@Composable
internal fun RemoteToggle(
    label: String,
    checked: Boolean,
    modifier: Modifier = Modifier,
    description: String? = null,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (description != null) {
                Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked, onCheckedChange)
    }
}

/** Rounded icon tile used at the leading edge of machine/key/client rows, as on other list screens. */
@Composable
internal fun RemoteIconTile(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.primaryContainer,
    contentColor: Color = MaterialTheme.colorScheme.onPrimaryContainer
) {
    Surface(modifier = modifier.size(48.dp), shape = RoundedCornerShape(14.dp), color = containerColor) {
        Box(contentAlignment = Alignment.Center) {
            SharedIcon(icon, null, tint = contentColor, modifier = Modifier.size(24.dp))
        }
    }
}

/** Drawable-backed variant of [RemoteIconTile] (for example the terminal glyph). */
@Composable
internal fun RemoteIconTile(
    iconRes: Int,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.primaryContainer,
    contentColor: Color = MaterialTheme.colorScheme.onPrimaryContainer
) {
    Surface(modifier = modifier.size(48.dp), shape = RoundedCornerShape(14.dp), color = containerColor) {
        Box(contentAlignment = Alignment.Center) {
            SharedIcon(iconRes, null, tint = contentColor, modifier = Modifier.size(24.dp))
        }
    }
}

/**
 * One list row inside a [RemoteSection] or a card: leading icon tile, title + optional subtitle,
 * then any trailing actions the caller supplies. Shared by the machines list and settings screens.
 */
@Composable
internal fun RemoteListRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    subtitleColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    subtitleMaxLines: Int = 2,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null
) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = subtitleColor, maxLines = subtitleMaxLines, overflow = TextOverflow.Ellipsis)
            }
        }
        if (trailing != null) trailing()
    }
}

/** Small connection-status dot: primary when connected, muted outline when not. */
@Composable
internal fun RemoteStatusDot(connected: Boolean, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(10.dp)
            .clip(CircleShape)
            .background(if (connected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)
    )
}

/** Existing machine dialogs share the application's error surface. */
@Composable
internal fun RemoteErrorText(message: String, modifier: Modifier = Modifier) {
    ErrorContainer(message = message, modifier = modifier, expandable = false)
}
