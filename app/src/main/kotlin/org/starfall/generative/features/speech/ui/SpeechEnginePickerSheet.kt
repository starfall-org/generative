package org.starfall.generative.features.speech.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.starfall.generative.R
import org.starfall.generative.features.speech.logic.brandHomepage
import org.starfall.generative.shared.model.SpeechEngine
import org.starfall.generative.shared.model.iconMatchName
import org.starfall.generative.shared.model.provider.ModelProvider
import org.starfall.generative.shared.ui.icons.EntityIcon
import org.starfall.generative.shared.ui.icons.Icons
import org.starfall.generative.shared.ui.icons.SharedIcon
import org.starfall.generative.shared.ui.icons.providerInitials
import org.starfall.generative.shared.ui.layout.prominentContainerBorder
import org.starfall.generative.shared.ui.layout.prominentContainerColor
import org.starfall.generative.shared.ui.lists.ItemCard
import org.starfall.generative.shared.ui.sheets.BottomSheet

/** Speech engines and compatible configured model providers share one selectable card layout. */
@Composable
internal fun SpeechEnginePickerSheet(
    engine: SpeechEngine,
    providerId: String,
    providers: List<ModelProvider>,
    modelsByProvider: Map<String, List<String>>,
    onSelectSystem: () -> Unit,
    onSelectStandalone: (SpeechEngine) -> Unit,
    onSelectProvider: (ModelProvider) -> Unit,
    onDismiss: () -> Unit
) {
    BottomSheet(onDismissRequest = onDismiss) {
        Text(
            stringResource(R.string.speech_platform_picker_title),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            style = MaterialTheme.typography.titleLarge
        )
        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f, fill = false).testTag("speech-platform-list"),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item(key = "system") {
                SpeechPlatformCard(
                    title = stringResource(R.string.speech_android_system_tts),
                    iconName = null,
                    iconImage = null,
                    selected = engine == SpeechEngine.SYSTEM,
                    modifier = Modifier.testTag("speech-engine-option-SYSTEM"),
                    onClick = onSelectSystem
                )
            }
            items(SpeechEngine.entries.filter { it.standalone }, key = { it.name }) { option ->
                SpeechPlatformCard(
                    title = speechEngineLabel(option),
                    subtitle = stringResource(R.string.speech_engine_direct_description),
                    detail = option.brandHomepage()?.removePrefix("https://"),
                    iconName = option.iconMatchName,
                    iconImage = null,
                    selected = engine == option,
                    modifier = Modifier.testTag("speech-engine-option-${option.name}"),
                    onClick = { onSelectStandalone(option) }
                )
            }
            items(providers.filter { it.id in modelsByProvider }, key = { "provider-${it.id}" }) { provider ->
                val count = modelsByProvider[provider.id].orEmpty().size
                SpeechPlatformCard(
                    title = provider.name,
                    subtitle = stringResource(R.string.speech_engine_provider_description),
                    detail = pluralStringResource(R.plurals.speech_engine_model_count, count, count),
                    iconName = provider.name,
                    iconImage = provider.icon,
                    selected = engine == SpeechEngine.PROVIDER && providerId == provider.id,
                    modifier = Modifier.testTag("speech-provider-option-${provider.id}"),
                    onClick = { onSelectProvider(provider) }
                )
            }
        }
    }
}

@Composable
private fun SpeechPlatformCard(
    title: String,
    subtitle: String? = null,
    iconName: String?,
    iconImage: String?,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    detail: String? = null
) {
    ItemCard(
        isGrid = false,
        onClick = onClick,
        modifier = modifier,
        color = prominentContainerColor(opacityBoost = if (selected) 0.12f else 0f),
        border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
            else prominentContainerBorder(1.5.dp),
        icon = {
            EntityIcon(
                image = iconImage,
                modifier = Modifier.size(42.dp),
                matchName = iconName,
                fallback = Icons.Outlined.RecordVoiceOver,
                text = providerInitials(title)
            )
        },
        actions = {
            if (selected) SharedIcon(
                Icons.Default.Check,
                contentDescription = stringResource(R.string.common_selected),
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.primary
            )
        },
        content = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (!subtitle.isNullOrBlank()) Text(subtitle, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (!detail.isNullOrBlank()) Text(detail, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    )
}
