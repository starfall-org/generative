package org.starfall.generative.features.speech.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.BorderStroke
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
import org.starfall.generative.shared.ui.sheets.ItemListSheet
import org.starfall.generative.shared.ui.sheets.ItemListSheetItem

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
    data class PlatformOption(
        val key: String,
        val title: String,
        val subtitle: String?,
        val detail: String?,
        val iconName: String?,
        val iconImage: String?,
        val selected: Boolean,
        val onClick: () -> Unit,
        val tag: String
    )
    val options = buildList {
        add(
            PlatformOption(
                key = "system",
                title = stringResource(R.string.speech_android_system_tts),
                subtitle = null,
                detail = null,
                iconName = null,
                iconImage = null,
                selected = engine == SpeechEngine.SYSTEM,
                onClick = onSelectSystem,
                tag = "speech-engine-option-SYSTEM"
            )
        )
        SpeechEngine.entries.filter { it.standalone }.forEach { option ->
            add(PlatformOption(
                key = option.name,
                title = speechEngineLabel(option),
                subtitle = stringResource(R.string.speech_engine_direct_description),
                detail = option.brandHomepage()?.removePrefix("https://"),
                iconName = option.iconMatchName,
                iconImage = null,
                selected = engine == option,
                onClick = { onSelectStandalone(option) },
                tag = "speech-engine-option-${option.name}"
            ))
        }
        providers.filter { it.id in modelsByProvider }.forEach { provider ->
            val count = modelsByProvider[provider.id].orEmpty().size
            add(PlatformOption(
                key = "provider-${provider.id}",
                title = provider.name,
                subtitle = stringResource(R.string.speech_engine_provider_description),
                detail = pluralStringResource(R.plurals.speech_engine_model_count, count, count),
                iconName = provider.name,
                iconImage = provider.icon,
                selected = engine == SpeechEngine.PROVIDER && providerId == provider.id,
                onClick = { onSelectProvider(provider) },
                tag = "speech-provider-option-${provider.id}"
            ))
        }
    }
    ItemListSheet(
        title = stringResource(R.string.speech_platform_picker_title),
        items = options.map { option ->
            ItemListSheetItem(
                key = option.key,
                value = option,
                onClick = option.onClick,
                selected = option.selected,
                modifier = Modifier.testTag(option.tag),
                icon = {
                    EntityIcon(
                        image = option.iconImage,
                        modifier = Modifier.size(42.dp),
                        matchName = option.iconName,
                        fallback = Icons.Outlined.RecordVoiceOver,
                        text = providerInitials(option.title)
                    )
                },
                actions = {
                    if (option.selected) SharedIcon(
                        Icons.Default.Check,
                        contentDescription = stringResource(R.string.common_selected),
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                },
                content = {
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(option.title, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (!option.subtitle.isNullOrBlank()) Text(option.subtitle, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (!option.detail.isNullOrBlank()) Text(option.detail, style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                },
                color = prominentContainerColor(opacityBoost = if (option.selected) 0.12f else 0f),
                border = if (option.selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
                    else prominentContainerBorder(1.5.dp)
            )
        },
        onDismissRequest = onDismiss,
        listModifier = Modifier.testTag("speech-platform-list")
    )
}
