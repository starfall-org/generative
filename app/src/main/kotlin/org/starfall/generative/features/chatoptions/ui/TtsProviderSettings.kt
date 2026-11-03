package org.starfall.generative.features.chatoptions.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.starfall.generative.R
import org.starfall.generative.shared.model.SpeechEngine
import org.starfall.generative.shared.model.iconMatchName
import org.starfall.generative.shared.model.SpeechService
import org.starfall.generative.shared.model.effectiveTtsProviderId
import org.starfall.generative.shared.model.provider.ModelProvider
import org.starfall.generative.shared.ui.icons.EntityIcon
import org.starfall.generative.shared.ui.icons.Icons
import org.starfall.generative.shared.ui.icons.providerInitials
import org.starfall.generative.shared.ui.layout.SettingsCard
import org.starfall.generative.shared.ui.model.DefaultSelectionItem
import org.starfall.generative.shared.ui.sheets.ItemListSheet

/** Selects the saved speech service used when reading messages aloud. */
@Composable
internal fun TtsProviderSettings(
    speechServices: List<SpeechService>,
    providers: List<ModelProvider>,
    selectedSpeechServiceId: String?,
    onProviderChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val selectedId = speechServices.effectiveTtsProviderId(selectedSpeechServiceId)
    val selectedService = speechServices.firstOrNull { it.id == selectedId }
    var expanded by remember { mutableStateOf(false) }

    SettingsCard(modifier = modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                EntityIcon(null, Modifier.size(42.dp), fallback = Icons.Outlined.RecordVoiceOver)
                Spacer(Modifier.width(14.dp))
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(stringResource(R.string.tts_service),
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
                    Text(stringResource(R.string.tts_service_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Box(Modifier.fillMaxWidth()) {
                DefaultSelectionItem(
                    title = selectedService?.name
                        ?: stringResource(R.string.tts_service_none),
                    icon = {
                        EntityIcon(
                            image = selectedService?.icon,
                            modifier = Modifier.size(48.dp),
                            text = providerInitials(selectedService?.name.orEmpty()),
                            fallback = Icons.Outlined.RecordVoiceOver,
                            matchName = selectedService?.engine?.iconMatchName ?: selectedService?.name
                        )
                    },
                    onClick = { expanded = true }
                )
                if (expanded) {
                    ItemListSheet(
                        title = stringResource(R.string.tts_service),
                        onDismissRequest = { expanded = false },
                        items = speechServices.map { service ->
                            val provider = providers.firstOrNull { it.id == service.provider }
                            val providerName = when (service.engine) {
                                SpeechEngine.SYSTEM -> stringResource(R.string.speech_android_system_tts)
                                SpeechEngine.PROVIDER -> provider?.name ?: service.provider
                                SpeechEngine.ELEVENLABS -> "ElevenLabs"
                                SpeechEngine.FISH_AUDIO -> "Fish Audio"
                                SpeechEngine.CARTESIA -> "Cartesia"
                                SpeechEngine.SPEECHIFY -> "Speechify"
                            }
                            val voice = service.voiceName.ifBlank { service.voice }
                            val detail = service.modelId?.let {
                                stringResource(R.string.speech_card_model_voice_line, it, voice)
                            } ?: stringResource(R.string.speech_card_voice_line, voice)
                            chatOptionSelectionItem(
                                key = service.id, value = service,
                                title = service.name,
                                subtitle = providerName,
                                detail = detail,
                                selected = service.id == selectedId,
                                icon = {
                                    EntityIcon(
                                        image = service.icon ?: provider?.icon?.takeUnless { service.engine.standalone },
                                        modifier = Modifier.size(42.dp),
                                        text = providerInitials(provider?.name ?: service.name),
                                        fallback = Icons.Outlined.RecordVoiceOver,
                                        matchName = service.engine.iconMatchName ?: provider?.name ?: service.name
                                    )
                                },
                                onClick = { onProviderChange(service.id); expanded = false },
                                modifier = Modifier.testTag("tts-service-option-${service.id}")
                            )
                        },
                        emptyMessage = stringResource(R.string.tts_service_empty)
                    )
                }

            }
            if (speechServices.isEmpty()) {
                Text(
                    stringResource(R.string.tts_service_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
