package org.starfall.generative.features.speech.ui

import org.starfall.generative.features.speech.logic.brandHomepage
import org.starfall.generative.features.speech.logic.requestSpeechEngineIcons
import org.starfall.generative.shared.ui.menus.DropdownMenu
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.starfall.generative.R
import org.starfall.generative.shared.media.supportsSpeechProvider
import org.starfall.generative.shared.model.SpeechService
import org.starfall.generative.shared.model.SpeechEngine
import org.starfall.generative.shared.model.iconMatchName
import org.starfall.generative.integrations.speech.adapter
import org.starfall.generative.integrations.speech.SpeechSynthesisModel
import androidx.compose.ui.text.input.KeyboardType
import org.starfall.generative.shared.model.api.ModelApiType
import org.starfall.generative.shared.model.language.ModelType
import org.starfall.generative.shared.model.provider.ModelProvider
import org.starfall.generative.shared.ui.icons.SharedIcon
import org.starfall.generative.shared.ui.icons.IconPickerRow
import org.starfall.generative.shared.ui.icons.Icons
import org.starfall.generative.shared.ui.icons.providerInitials
import org.starfall.generative.shared.ui.screens.EditorScreenTemplate
import org.starfall.generative.shared.ui.menus.DropdownMenuItem
import org.starfall.generative.shared.ui.inputs.ApiKeyField
import org.starfall.generative.shared.ui.inputs.OutlinedTextField

/** Full-screen speech service editor. All configuration and preview actions are kept inline. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpeechEditor(
    initialService: SpeechService,
    isNew: Boolean,
    providers: List<ModelProvider>,
    isPlaying: Boolean,
    onTest: (SpeechService, String) -> Unit,
    onDismiss: () -> Unit,
    onSave: (SpeechService) -> Unit,
    fetchModels: (suspend (SpeechService) -> List<SpeechSynthesisModel>)? = null,
    onSaveIcon: (SpeechService) -> Unit = {}
) {
    val context = LocalContext.current
    val localizedResources = LocalResources.current
    LaunchedEffect(context) { requestSpeechEngineIcons(context) }
    val ttsModelsByProvider = remember(providers) {
        providers.filter(::supportsSpeechProvider).associate { provider ->
            provider.id to provider.config.modelConfigs
                .filter { (id, config) -> config.modelType == ModelType.TEXT_TO_SPEECH && provider.config.modelIds?.contains(id) != false }
                .keys
                .toList()
        }.filterValues { it.isNotEmpty() }
    }

    var engine by remember(initialService.id) { mutableStateOf(initialService.engine) }
    var voiceName by remember(initialService.id) { mutableStateOf(initialService.voiceName) }
    var showVoices by remember { mutableStateOf(false) }
    val standalone = engine.standalone
    var name by remember(initialService.id) { mutableStateOf(initialService.name) }
    var serviceIcon by remember(initialService.id) { mutableStateOf(initialService.icon) }
    var iconImporting by remember { mutableStateOf(false) }
    var providerId by remember(initialService.id) {
        mutableStateOf(
            initialService.provider
        )
    }
    var modelId by remember(initialService.id) {
        mutableStateOf(initialService.modelId)
    }
    var voice by remember(initialService.id) { mutableStateOf(initialService.voice) }
    var speed by remember(initialService.id) { mutableFloatStateOf(initialService.speed) }
    var pitch by remember(initialService.id) { mutableFloatStateOf(initialService.pitch) }
    var instructions by remember(initialService.id) { mutableStateOf(initialService.instructions) }
    var responseFormat by remember(initialService.id) { mutableStateOf(initialService.responseFormat) }
    var languageCode by remember(initialService.id) { mutableStateOf(initialService.languageCode) }
    var extraBodyText by remember(initialService.id) { mutableStateOf(initialService.extraBody.toString()) }
    var chunkSizeText by remember(initialService.id) { mutableStateOf(initialService.sentencesPerChunk.coerceAtLeast(1).toString()) }
    val chunkSize = chunkSizeText.toIntOrNull()?.takeIf { it > 0 }
    var apiKey by remember(initialService.id) { mutableStateOf(initialService.apiKey) }
    var providerExpanded by remember { mutableStateOf(false) }
    var modelExpanded by remember { mutableStateOf(false) }
    var advancedExpanded by remember(initialService.id, engine) { mutableStateOf(initialService.extraBody.isNotEmpty()) }

    val systemServiceName = stringResource(R.string.speech_android_system_tts)
    val newServiceName = stringResource(R.string.speech_custom_tts)
    val defaultServiceName = when (engine) {
        SpeechEngine.SYSTEM -> systemServiceName
        SpeechEngine.PROVIDER -> providers.firstOrNull { it.id == providerId }?.name.orEmpty()
        else -> speechEngineLabel(engine)
    }
    fun updateDefaultName(nextName: String) {
        val defaults = setOf(newServiceName, "Custom TTS", defaultServiceName) +
            if (engine == SpeechEngine.SYSTEM) setOf("Android System TTS") else emptySet()
        if (name.isBlank() || name.trim() in defaults) name = nextName
    }

    val availableModels = ttsModelsByProvider[providerId].orEmpty()
    val system = engine == SpeechEngine.SYSTEM
    val selectedProvider = providers.firstOrNull { it.id == providerId }
    val google = providers.find { it.id == providerId }?.type == ModelApiType.GOOGLE
    val providerBaseUrl = providers.find { it.id == providerId }?.baseUrl.orEmpty()
    val supportsInstructions = !standalone && (google || modelId !in listOf("tts-1", "tts-1-hd"))
    val extraBody = remember(extraBodyText) { runCatching { Json.parseToJsonElement(extraBodyText.ifBlank { "{}" }) as? JsonObject }.getOrNull() }
    LaunchedEffect(providerId, engine) {
        if (standalone) return@LaunchedEffect
        if (!providerId.equals("system", true) && modelId !in availableModels) {
            modelId = availableModels.firstOrNull()
        }
        if (providerId.equals("system", true)) modelId = null
    }
    LaunchedEffect(providerId, modelId, engine) {
        if (standalone) return@LaunchedEffect
        if (!system && voice.equals("Default", true)) voice = if (google) "Kore" else "alloy"
        if (system && voice in listOf("alloy", "Kore")) voice = "Default"
    }

    fun currentService() = initialService.copy(
        name = name.trim(),
        engine = engine,
        voiceName = voiceName,
        // A provider icon is only the default. A saved service icon always wins.
        icon = serviceIcon,
        provider = providerId,
        modelId = modelId,
        voice = voice.trim(),
        speed = speed,
        sentencesPerChunk = chunkSize ?: initialService.sentencesPerChunk,
        pitch = pitch,
        instructions = if (supportsInstructions) instructions else "",
        responseFormat = responseFormat,
        languageCode = languageCode.trim(),
        apiKey = apiKey.trim(),
        extraBody = extraBody ?: JsonObject(emptyMap())
    )

    val engineValidationError = if (standalone && extraBody != null) runCatching {
        adapter(engine).synthesis(currentService(), "Test")
    }.exceptionOrNull()?.message else null
    val canSave = name.isNotBlank() && (system || chunkSize != null) &&
        (system || if (standalone) extraBody != null && engineValidationError == null else (modelId in availableModels && extraBody != null))

    if (showVoices && standalone) SpeechVoiceSheet(currentService(), onChoose = {
        voice = it.id; voiceName = it.name; showVoices = false
    }, onDismiss = { showVoices = false })
    if (providerExpanded) SpeechEnginePickerSheet(
        engine = engine,
        providerId = providerId,
        providers = providers,
        modelsByProvider = ttsModelsByProvider,
        onSelectSystem = {
            updateDefaultName(systemServiceName)
            providerId = "system"
            engine = SpeechEngine.SYSTEM
            providerExpanded = false
        },
        onSelectStandalone = { next ->
            updateDefaultName(speechEngineLabel(next))
            engine = next
            providerId = ""
            voice = ""
            voiceName = ""
            apiKey = ""
            extraBodyText = "{}"
            speed = 1f
            // The model is chosen from the service catalog after entering an API key.
            modelId = null
            responseFormat = when (next) {
                SpeechEngine.ELEVENLABS -> "mp3_44100_128"
                SpeechEngine.CARTESIA -> "wav"
                else -> "mp3"
            }
            providerExpanded = false
        },
        onSelectProvider = { provider ->
            updateDefaultName(provider.name)
            if (standalone) {
                apiKey = ""
                extraBodyText = "{}"
                responseFormat = "mp3"
            }
            engine = SpeechEngine.PROVIDER
            providerId = provider.id
            voice = if (provider.type == ModelApiType.GOOGLE) "Kore" else "alloy"
            providerExpanded = false
        },
        onDismiss = { providerExpanded = false }
    )
    Surface(Modifier.fillMaxSize(), color = androidx.compose.ui.graphics.Color.Transparent) {
        EditorScreenTemplate(
            title = stringResource(if (isNew) R.string.speech_add_service else R.string.speech_edit_title),
            onBack = onDismiss,
            handleSystemBack = !providerExpanded,
            modifier = Modifier.fillMaxSize(),
            actions = {
                Button(
                    onClick = { onSave(currentService()) },
                    enabled = canSave && !iconImporting,
                    modifier = Modifier.padding(end = 8.dp)
                ) { Text(stringResource(R.string.common_save)) }
            }
        ) { padding ->
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize().consumeWindowInsets(padding)
                    .verticalScroll(rememberScrollState()).padding(padding)
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.speech_service_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("speech-service-name")
                )

                IconPickerRow(
                    // Keep the provider icon visible while no service-specific icon
                    // is saved. Choosing or importing an icon replaces it; removing
                    // that icon falls back to the provider again.
                    image = serviceIcon,
                    onChange = { serviceIcon = it; onSaveIcon(currentService()) },
                    text = providerInitials(name),
                    fallback = Icons.Outlined.RecordVoiceOver,
                    onBusyChange = { iconImporting = it },
                    matchName = engine.iconMatchName ?: selectedProvider?.name ?: name,
                    faviconBaseUrl = engine.brandHomepage() ?: providerBaseUrl,
                    defaultImage = selectedProvider?.icon?.takeUnless { standalone }
                )

                OutlinedButton(
                    onClick = { providerExpanded = true },
                    modifier = Modifier.fillMaxWidth().testTag("speech-engine-selector")
                ) {
                    Text(
                        if (system) systemServiceName
                        else if (standalone) speechEngineLabel(engine)
                        else selectedProvider?.name ?: providerId,
                        modifier = Modifier.weight(1f)
                    )
                    SharedIcon(Icons.Default.KeyboardArrowDown, contentDescription = null)
                }

                if (standalone) {
                    SpeechApiKeyField(apiKey, { nextKey ->
                        if (nextKey != apiKey) {
                            // Whitespace-only changes do not change the credential used for fetching.
                            if (nextKey.trim() != apiKey.trim()) modelId = null
                            apiKey = nextKey
                        }
                    }, standalone = true, visibilityKey = initialService.id to engine)
                    SpeechModelField(service = currentService(), onChange = { modelId = it },
                        fetchModels = fetchModels)
                } else if (!system) {
                    Box {
                        OutlinedButton(
                            onClick = { modelExpanded = true },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(modelId ?: stringResource(R.string.speech_select_tts_model), modifier = Modifier.weight(1f))
                            SharedIcon(Icons.Default.KeyboardArrowDown, contentDescription = null)
                        }
                        DropdownMenu(
                            expanded = modelExpanded,
                            onDismissRequest = { modelExpanded = false }
                        ) {
                            availableModels.forEach { id ->
                                val displayName = providers
                                    .find { it.id == providerId }
                                    ?.config
                                    ?.modelConfigs
                                    ?.get(id)
                                    ?.displayName
                                    ?.takeIf { it.isNotBlank() }
                                DropdownMenuItem(
                                    text = { Text(displayName ?: id) },
                                    onClick = {
                                        modelId = id
                                        modelExpanded = false
                                    }
                                )
                            }
                        }
                    }
                } else if (ttsModelsByProvider.isEmpty()) {
                    Text(
                        stringResource(R.string.speech_no_tts_models),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                OutlinedTextField(
                    value = voice,
                    onValueChange = { voice = it; voiceName = "" },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, keyboardType = KeyboardType.Password),
                    trailingIcon = if (standalone) { { IconButton(onClick = { showVoices = true }, enabled = apiKey.isNotBlank()) { SharedIcon(Icons.Outlined.FormatListBulleted, stringResource(R.string.speech_choose_voice)) } } } else null,
                    supportingText = if (voiceName.isNotBlank()) { { Text(voiceName) } } else null,
                    label = {
                        Text(
                            if (system || standalone) {
                                stringResource(R.string.speech_voice)
                            } else {
                                 if (google) stringResource(R.string.speech_voice_gemini_hint) else stringResource(R.string.speech_voice_id_hint)
                            }
                        )
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                if (!google) {
                    val speedRange = if (system) 0.5f..2.0f else 0.25f..4f
                    val speedSliderState = remember(speedRange) {
                        SliderState(value = speed, trackRange = speedRange)
                    }
                    speedSliderState.value = speed
                    Text(stringResource(R.string.speech_speed_value, String.format("%.1f", speed)))
                    Slider(
                        state = speedSliderState,
                        onValueChange = { speed = it },
                        modifier = Modifier.fillMaxWidth().testTag("speech-speed-slider")
                    )
                }

                if (!system) {
                    if (supportsInstructions) {
                        OutlinedTextField(
                            value = instructions, onValueChange = { instructions = it },
                            label = { Text(stringResource(R.string.speech_voice_instructions)) },
                            supportingText = { Text(stringResource(if (google) R.string.speech_voice_instructions_help_google else R.string.speech_voice_instructions_help, "")) },
                            modifier = Modifier.fillMaxWidth(), minLines = 2, maxLines = 4
                        )
                    }
                    if (google || engine == SpeechEngine.SPEECHIFY) {
                        OutlinedTextField(
                            value = languageCode, onValueChange = { languageCode = it },
                            label = { Text(stringResource(R.string.speech_language_code)) },
                            placeholder = { Text("vi-VN") }, singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    if (!google) {
                        var formatExpanded by remember { mutableStateOf(false) }
                        Box {
                            OutlinedButton(onClick = { formatExpanded = true }, modifier = Modifier.fillMaxWidth()) {
                                Text(stringResource(R.string.speech_audio_format, responseFormat), Modifier.weight(1f))
                                SharedIcon(Icons.Default.KeyboardArrowDown, null)
                            }
                            DropdownMenu(expanded = formatExpanded, onDismissRequest = { formatExpanded = false }) {
                                (when(engine) {
                                    SpeechEngine.ELEVENLABS -> listOf("mp3_44100_128", "mp3_22050_32")
                                    SpeechEngine.FISH_AUDIO -> listOf("mp3", "wav", "opus")
                                    SpeechEngine.CARTESIA -> listOf("wav")
                                    SpeechEngine.SPEECHIFY -> listOf("mp3", "wav", "ogg", "aac")
                                    else -> listOf("mp3", "wav", "aac", "flac", "opus", "pcm")
                                }).forEach { format ->
                                    DropdownMenuItem(text = { Text(format) }, onClick = { responseFormat = format; formatExpanded = false })
                                }
                            }
                        }
                    }
                    OutlinedTextField(
                        value = chunkSizeText, onValueChange = { chunkSizeText = it },
                        label = { Text(stringResource(R.string.speech_sentences_per_chunk)) },
                        supportingText = { Text(stringResource(R.string.speech_chunk_boundaries)) },
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None,
                            autoCorrectEnabled = false, keyboardType = KeyboardType.Number),
                        isError = chunkSizeText.isNotBlank() && chunkSize == null,
                        singleLine = true, modifier = Modifier.fillMaxWidth().testTag("speech-chunk-size")
                    )
                    if (standalone) TextButton(onClick = { advancedExpanded = !advancedExpanded }) {
                        Text(stringResource(R.string.image_tool_advanced_json), Modifier.weight(1f))
                        SharedIcon(if (advancedExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, null)
                    }
                    if (!standalone || advancedExpanded) OutlinedTextField(
                        value = extraBodyText, onValueChange = { extraBodyText = it },
                        label = { Text(stringResource(R.string.speech_additional_parameters)) },
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, keyboardType = KeyboardType.Password),
                        isError = extraBody == null,
                        supportingText = if (extraBody == null) { { Text(stringResource(R.string.speech_json_invalid_hint)) } }
                            else if (!standalone) { { Text(stringResource(R.string.speech_provider_parameters_help)) } } else null,
                        modifier = Modifier.fillMaxWidth(), minLines = 2, maxLines = 5
                    )
                    if (!standalone) SpeechApiKeyField(apiKey, { apiKey = it }, standalone = false, visibilityKey = initialService.id to engine)
                }

                if (system) {
                    val pitchSliderState = remember {
                        SliderState(value = pitch, trackRange = 0.5f..2.0f)
                    }
                    pitchSliderState.value = pitch
                    Text(stringResource(R.string.speech_pitch_value, String.format("%.1f", pitch)))
                    Slider(
                        state = pitchSliderState,
                        onValueChange = { pitch = it }
                    )
                }

                engineValidationError?.takeIf { apiKey.isNotBlank() && !modelId.isNullOrBlank() && voice.isNotBlank() }?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                OutlinedButton(
                    onClick = {
                        onTest(currentService(), localizedResources.getString(R.string.speech_test_announcement))
                    },
                    enabled = isPlaying || canSave,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    SharedIcon(
                        if (isPlaying) Icons.Outlined.StopCircle else Icons.Default.PlayArrow,
                        contentDescription = null
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(if (isPlaying) R.string.speech_stop else R.string.speech_test_voice))
                }
            }
        }
    }
}

internal fun speechEngineLabel(engine: SpeechEngine): String = when(engine) {
    SpeechEngine.ELEVENLABS -> "ElevenLabs"
    SpeechEngine.FISH_AUDIO -> "Fish Audio"
    SpeechEngine.CARTESIA -> "Cartesia"
    SpeechEngine.SPEECHIFY -> "Speechify"
    else -> engine.name
}

@Composable
private fun SpeechApiKeyField(value: String, onChange: (String) -> Unit, standalone: Boolean, visibilityKey: Any?) {
    ApiKeyField(
        value = value, onValueChange = onChange,
        label = { Text(stringResource(if (standalone) R.string.speech_engine_api_key else R.string.speech_api_key_override)) },
        supportingText = if (!standalone) { { Text(stringResource(R.string.speech_api_key_help)) } } else null,
        visibilityKey = visibilityKey,
        modifier = Modifier.fillMaxWidth()
    )
}
