package org.starfall.generative.features.customization.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.starfall.generative.shared.ui.inputs.ControlledSlider
import org.starfall.generative.R
import org.starfall.generative.database.preferences.AppPreferences
import kotlin.math.roundToInt

@Composable
internal fun InputAreaSettings(
    appearance: AppPreferences,
    onTransparencyChange: (Float) -> Unit,
    onBlurChange: (Float) -> Unit
) {
    var transparency by remember(appearance.inputAreaTransparency) {
        mutableFloatStateOf(appearance.inputAreaTransparency.coerceIn(0f, 1f))
    }
    var blur by remember(appearance.inputAreaBlur) {
        mutableFloatStateOf(appearance.inputAreaBlur.coerceIn(0f, 32f))
    }
    val transparencyLabel = stringResource(R.string.customization_input_transparency)
    val blurLabel = stringResource(R.string.customization_input_blur)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("$transparencyLabel · ${(transparency * 100).roundToInt()}%")
        ControlledSlider(
            value = transparency, onValueChange = { transparency = it },
            onValueChangeFinished = { onTransparencyChange(transparency) },
            modifier = Modifier.testTag("input-area-transparency-slider")
                .semantics { contentDescription = transparencyLabel }
        )
        Text("$blurLabel · ${blur.roundToInt()} dp")
        ControlledSlider(
            value = blur, onValueChange = { blur = it }, valueRange = 0f..32f,
            onValueChangeFinished = { onBlurChange(blur) },
            modifier = Modifier.testTag("input-area-blur-slider")
                .semantics { contentDescription = blurLabel }
        )
        Text(stringResource(R.string.customization_input_blur_description),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = {
            val defaults = AppPreferences()
            transparency = defaults.inputAreaTransparency
            blur = defaults.inputAreaBlur
            onTransparencyChange(transparency)
            onBlurChange(blur)
        }, modifier = Modifier.testTag("input-area-appearance-reset")) {
            Text(stringResource(R.string.common_reset))
        }
    }
}
