package org.starfall.generative.features.customization.ui

import android.graphics.Color as AndroidColor
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import org.starfall.generative.shared.ui.inputs.ControlledSlider
import org.starfall.generative.R
import org.starfall.generative.shared.theme.CustomPalette
import org.starfall.generative.shared.theme.customColorScheme
import org.starfall.generative.shared.theme.parsePaletteHex
import org.starfall.generative.shared.theme.toPaletteHex
import org.starfall.generative.shared.ui.dialogs.Dialog
import org.starfall.generative.shared.ui.layout.nestedContainerBorder
import org.starfall.generative.shared.ui.inputs.OutlinedTextField

/** A draft-only HSV color mixer. Cancel never writes the appearance preference. */
@Composable
internal fun CustomPaletteDialog(
    initial: CustomPalette,
    onSave: (CustomPalette) -> Unit,
    onDismiss: () -> Unit
) {
    var draft by remember(initial) { mutableStateOf(initial) }
    var selectedRole by remember { mutableIntStateOf(0) }
    var previewDark by remember { mutableStateOf(false) }
    var hexDraft by remember(selectedRole) { mutableStateOf(draft.color(selectedRole).toPaletteHex()) }
    val selected = draft.color(selectedRole)
    // Retain hue while saturation/brightness reaches zero; RGB alone cannot represent it.
    var hsv by remember(selectedRole) {
        mutableStateOf(FloatArray(3).also { AndroidColor.colorToHSV(selected.toArgb(), it) })
    }
    val roleNames = listOf(
        stringResource(R.string.settings_palette_primary),
        stringResource(R.string.settings_palette_secondary),
        stringResource(R.string.settings_palette_accent),
        stringResource(R.string.settings_palette_neutral)
    )
    fun updateColor(color: Color) {
        draft = draft.withColor(selectedRole, color.toPaletteHex())
        hexDraft = color.toPaletteHex()
    }
    fun updateHsv(h: Float = hsv[0], s: Float = hsv[1], v: Float = hsv[2]) {
        hsv = floatArrayOf(h, s, v)
        updateColor(Color(AndroidColor.HSVToColor(hsv)))
    }

    Dialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_customize_palette)) },
        scrollableContent = true,
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SingleChoiceSegmentedButtonRow(
                    modifier = Modifier.fillMaxWidth().testTag("palette-role-group")
                ) {
                    roleNames.forEachIndexed { index, name ->
                        SegmentedButton(
                            selected = selectedRole == index,
                            onClick = { selectedRole = index },
                            shape = SegmentedButtonDefaults.itemShape(index, roleNames.size),
                            modifier = Modifier.weight(1f).testTag("palette-role-$index"),
                            icon = {},
                            label = {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Box(Modifier.size(10.dp).clip(CircleShape)
                                        .background(draft.color(index)))
                                    Text(name, style = MaterialTheme.typography.labelSmall,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        )
                    }
                }
                val scheme = customColorScheme(draft, previewDark)
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.settings_palette_preview),
                        style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    FilterChip(selected = !previewDark, onClick = { previewDark = false },
                        label = { Text(stringResource(R.string.settings_theme_light)) },
                        modifier = Modifier.testTag("palette-preview-light"))
                    FilterChip(selected = previewDark, onClick = { previewDark = true },
                        label = { Text(stringResource(R.string.settings_theme_dark)) },
                        modifier = Modifier.testTag("palette-preview-dark"))
                }
                MaterialTheme(colorScheme = scheme) {
                    Surface(
                        color = scheme.surface,
                        border = nestedContainerBorder(),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth().testTag("palette-preview")
                    ) {
                        Column(Modifier.padding(9.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                listOf(scheme.primary, scheme.secondary, scheme.tertiary).forEach { color ->
                                    Box(Modifier.weight(1f).height(16.dp)
                                        .clip(RoundedCornerShape(5.dp)).background(color))
                                }
                            }
                            Surface(color = scheme.primaryContainer, shape = RoundedCornerShape(7.dp)) {
                                Text(stringResource(R.string.settings_palette_sample),
                                    color = scheme.onPrimaryContainer,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp))
                            }
                        }
                    }
                }
                ColorPlane(
                    hue = hsv[0], saturation = hsv[1], brightness = hsv[2], role = selectedRole,
                    onSelect = { saturation, brightness -> updateHsv(s = saturation, v = brightness) }
                )
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.settings_palette_hue), style = MaterialTheme.typography.labelMedium)
                    Box(Modifier.weight(1f)) {
                    Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp).height(8.dp)
                        .align(Alignment.Center).clip(RoundedCornerShape(5.dp))
                        .background(Brush.horizontalGradient((0..6).map { i ->
                            Color(AndroidColor.HSVToColor(floatArrayOf(i * 60f, 1f, 1f)))
                        })))
                    ControlledSlider(
                        value = hsv[0], onValueChange = { updateHsv(h = it) },
                        valueRange = 0f..360f, modifier = Modifier.testTag("palette-hue")
                    )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.settings_palette_saturation), style = MaterialTheme.typography.labelMedium)
                        ControlledSlider(value = hsv[1], onValueChange = { updateHsv(s = it) },
                            modifier = Modifier.testTag("palette-saturation"))
                    }
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.settings_palette_brightness), style = MaterialTheme.typography.labelMedium)
                        ControlledSlider(value = hsv[2], onValueChange = { updateHsv(v = it) },
                            modifier = Modifier.testTag("palette-brightness"))
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.size(38.dp).clip(RoundedCornerShape(10.dp)).background(selected))
                    OutlinedTextField(
                        value = hexDraft,
                        onValueChange = { input ->
                            hexDraft = input.uppercase().take(7)
                            parsePaletteHex(hexDraft)?.let { color ->
                                draft = draft.withColor(selectedRole, color.toPaletteHex())
                                hsv = FloatArray(3).also { AndroidColor.colorToHSV(color.toArgb(), it) }
                            }
                        },
                        label = { Text(stringResource(R.string.settings_palette_hex)) },
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false,
                            keyboardType = KeyboardType.Password
                        ),
                        isError = parsePaletteHex(hexDraft) == null,
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                        modifier = Modifier.weight(1f).testTag("palette-hex")
                    )
                }
            }
        },
        buttons = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_close)) }
            Button(
                onClick = { onSave(draft) },
                enabled = parsePaletteHex(hexDraft) != null,
                modifier = Modifier.testTag("palette-save")
            ) { Text(stringResource(R.string.common_save)) }
        }
    )
}

@Composable
private fun ColorPlane(
    hue: Float,
    saturation: Float,
    brightness: Float,
    role: Int,
    onSelect: (Float, Float) -> Unit
) {
    val select by rememberUpdatedState(onSelect)
    val hueColor = Color(AndroidColor.HSVToColor(floatArrayOf(hue, 1f, 1f)))
    Canvas(
        Modifier.fillMaxWidth().height(108.dp).clip(RoundedCornerShape(12.dp))
            .testTag("palette-color-plane")
            .pointerInput(role) {
                detectTapGestures { point ->
                    select((point.x / size.width).coerceIn(0f, 1f), (1f - point.y / size.height).coerceIn(0f, 1f))
                }
            }
            .pointerInput(role) {
                detectDragGestures(onDragStart = { point ->
                    select((point.x / size.width).coerceIn(0f, 1f), (1f - point.y / size.height).coerceIn(0f, 1f))
                }) { change, _ ->
                    select((change.position.x / size.width).coerceIn(0f, 1f),
                        (1f - change.position.y / size.height).coerceIn(0f, 1f))
                    change.consume()
                }
            }
    ) {
        drawRect(brush = Brush.horizontalGradient(listOf(Color.White, hueColor)))
        drawRect(brush = Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
        val marker = Offset(saturation * size.width, (1f - brightness) * size.height)
        drawCircle(Color.Black, radius = 10.dp.toPx(), center = marker, style = Stroke(width = 3.dp.toPx()))
        drawCircle(Color.White, radius = 9.dp.toPx(), center = marker, style = Stroke(width = 2.dp.toPx()))
    }
}
