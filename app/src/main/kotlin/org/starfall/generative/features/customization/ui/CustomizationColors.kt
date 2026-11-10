package org.starfall.generative.features.customization.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.starfall.generative.shared.ui.inputs.ControlledSlider
import org.starfall.generative.R
import org.starfall.generative.shared.model.preferences.AppPreferences
import org.starfall.generative.shared.model.preferences.CustomPalette
import org.starfall.generative.shared.ui.theme.color
import org.starfall.generative.shared.ui.theme.LocalAmoledMode
import org.starfall.generative.shared.ui.theme.ThemePresets
import org.starfall.generative.shared.ui.icons.Icons
import org.starfall.generative.shared.ui.icons.SharedIcon
import org.starfall.generative.shared.ui.layout.appearanceContainerColor
import org.starfall.generative.shared.ui.layout.nestedContainerBorder

/** Color selection and component opacity are one customization capability, not general settings. */
@Composable
internal fun CustomizationColors(
    appearance: AppPreferences,
    onDynamicColorChange: (Boolean) -> Unit,
    onColorSchemeChange: (String) -> Unit,
    onCustomPaletteChange: (CustomPalette) -> Unit,
    onItemTransparencyChange: (Float) -> Unit
) {
    var showMixer by remember { mutableStateOf(false) }
    var opacity by remember { mutableFloatStateOf(appearance.itemTransparency) }
    LaunchedEffect(appearance.itemTransparency) { opacity = appearance.itemTransparency }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            Modifier.fillMaxWidth().toggleable(appearance.useDynamicColor, role = Role.Switch,
                onValueChange = onDynamicColorChange),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.settings_dynamic_color),
                    style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.settings_dynamic_color_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = appearance.useDynamicColor, onCheckedChange = null)
        }
        if (!appearance.useDynamicColor) {
            Text(stringResource(R.string.settings_color_palette),
                style = MaterialTheme.typography.titleMedium)
            Row(
                modifier = Modifier.fillMaxWidth().testTag("customization-palette-row"),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ThemePresets.forEach { preset ->
                    val selected = appearance.colorSchemeName == preset.name
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = appearanceContainerColor(preset.preview, opacityBoost = 0.15f),
                        border = if (selected) BorderStroke(2.dp, preset.preview) else nestedContainerBorder(),
                        modifier = Modifier.weight(1f).height(68.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .toggleable(selected, role = Role.RadioButton) {
                                onColorSchemeChange(preset.name)
                            }
                            .semantics { contentDescription = preset.name }
                            .testTag("palette-preset-${preset.name}")
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center) {
                            Box(Modifier.size(26.dp).clip(CircleShape).background(preset.preview),
                                contentAlignment = Alignment.Center) {
                                if (selected) SharedIcon(Icons.Outlined.Check, null,
                                    tint = Color.White, modifier = Modifier.size(18.dp))
                            }
                            Text(preset.name.lowercase().replaceFirstChar { it.uppercase() },
                                style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
                val selected = appearance.colorSchemeName == "CUSTOM"
                val first = appearance.customPalette.color(0)
                val customName = stringResource(R.string.settings_customize_palette)
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = appearanceContainerColor(MaterialTheme.colorScheme.surfaceContainerHigh),
                    border = if (selected) BorderStroke(2.dp, first) else nestedContainerBorder(),
                    modifier = Modifier.weight(1f).height(68.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .toggleable(selected, role = Role.RadioButton) { showMixer = true }
                        .semantics { contentDescription = customName }
                        .testTag("palette-preset-CUSTOM")
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center) {
                        Box(Modifier.size(26.dp).clip(CircleShape).background(Brush.sweepGradient(
                            listOf(appearance.customPalette.color(0), appearance.customPalette.color(1),
                                appearance.customPalette.color(2), first))),
                            contentAlignment = Alignment.Center) {
                            SharedIcon(Icons.Outlined.Edit, null,
                                tint = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(16.dp))
                        }
                        Text(stringResource(R.string.settings_customize_palette),
                            style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Text(stringResource(R.string.settings_item_transparency),
            style = MaterialTheme.typography.titleMedium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = {
                opacity = 0.5f
                onItemTransparencyChange(0.5f)
            }, enabled = !LocalAmoledMode.current) {
                SharedIcon(Icons.Default.RestartAlt,
                    stringResource(R.string.settings_item_transparency_reset))
            }
            ControlledSlider(
                value = opacity, onValueChange = { opacity = it },
                onValueChangeFinished = { onItemTransparencyChange(opacity) },
                modifier = Modifier.weight(1f).testTag("customization-opacity-slider"),
                enabled = !LocalAmoledMode.current
            )
            Text("${(opacity * 100).toInt()}%",
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(start = 8.dp))
        }
    }

    if (showMixer) {
        CustomPaletteDialog(
            initial = appearance.customPalette,
            onSave = {
                onCustomPaletteChange(it)
                showMixer = false
            },
            onDismiss = { showMixer = false }
        )
    }
}
