package org.starfall.generative.features.providers.editor.ui

import org.starfall.generative.shared.ui.layout.edgeToEdgeTopAppBarColors
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.starfall.generative.R
import org.starfall.generative.features.providers.shared.ui.ModelConfigDialog
import org.starfall.generative.features.providers.shared.ui.ModelToolsDialog
import org.starfall.generative.shared.model.language.ModelConfiguration
import org.starfall.generative.shared.model.language.ModelType
import org.starfall.generative.shared.model.media.MediaGenerationMode
import org.starfall.generative.shared.model.media.modelIndependent
import org.starfall.generative.shared.model.media.MediaRequestProfile
import org.starfall.generative.shared.model.media.withDefaultMediaProfile
import org.starfall.generative.shared.model.language.SELECTABLE_REASONING_EFFORTS
import org.starfall.generative.shared.model.language.ThinkingPassback
import org.starfall.generative.shared.model.language.passbackMode
import org.starfall.generative.shared.model.language.supportedReasoningEfforts
import org.starfall.generative.shared.model.language.withThinkingPassback
import org.starfall.generative.shared.model.provider.ModelProvider
import org.starfall.generative.shared.storage.preferences.ModelConfigurationMemory
import org.starfall.generative.shared.ui.icons.SharedIcon
import org.starfall.generative.shared.ui.icons.IconPickerRow
import org.starfall.generative.shared.ui.icons.Icons
import org.starfall.generative.shared.ui.layout.LocalScreenTransitionActive
import org.starfall.generative.shared.ui.dialogs.AlertDialog
import org.starfall.generative.shared.ui.inputs.OutlinedTextField
import org.starfall.generative.shared.ui.code.CodeBlockSurface
import org.starfall.generative.shared.ui.model.modelInitial

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelEditor(
    provider: ModelProvider,
    initialModelId: String,
    existingModelIds: Set<String>,
    customImageMediaProfiles: Map<String, MediaRequestProfile> = emptyMap(),
    customVideoMediaProfiles: Map<String, MediaRequestProfile> = emptyMap(),
    onSave: (String, ModelConfiguration) -> Unit,
    onBack: () -> Unit,
    onSaveIcon: (String, String?) -> Unit = { _, _ -> }
) {
    val context = LocalContext.current
    val modelMemory = remember(context) { ModelConfigurationMemory(context) }
    var modelId by remember { mutableStateOf(initialModelId) }
    var config by remember { mutableStateOf(provider.config.modelConfigs[initialModelId] ?: ModelConfiguration()) }
    var contextWindowText by remember { mutableStateOf(config.contextWindowTokens.toString()) }
    val contextWindow = contextWindowText.toIntOrNull()?.takeIf { it > 0 }
    var iconImporting by remember { mutableStateOf(false) }
    var iconChanged by remember { mutableStateOf(false) }
    var iconModelId by remember { mutableStateOf<String?>(null) }
    var profileExpanded by remember { mutableStateOf(false) }
    var showSampling by remember { mutableStateOf(false) }
    var showModelTools by remember { mutableStateOf(false) }
    var showModelJsonDialog by remember { mutableStateOf(false) }
    val trimmedId = modelId.trim()
    val isVideoGen = config.modelType == ModelType.VIDEO_GENERATION
    val mediaProfiles = (if (isVideoGen) customVideoMediaProfiles else customImageMediaProfiles)
        .withDefaultMediaProfile(isVideoGen).filterValues { !it.modelIndependent }
    val chosenMediaProfile = mediaProfiles[config.mediaRequestProfileId]
        ?: mediaProfiles.values.first { it.mode == MediaGenerationMode.DEFAULT }
    val isMediaModel = config.modelType == ModelType.IMAGE_GENERATION || isVideoGen
    val modelJson = if (initialModelId.isNotBlank() && trimmedId == initialModelId) config.modelJson else JsonObject(emptyMap())
    val prettyJson = remember { Json { prettyPrint = true } }
    val modelJsonCode = remember(modelJson) { prettyJson.encodeToString(JsonObject.serializer(), modelJson) }
    LaunchedEffect(trimmedId) {
        if (initialModelId.isBlank()) withContext(Dispatchers.IO) { modelMemory.get(trimmedId) }?.let { remembered ->
            config = remembered.copy(displayName = config.displayName, icon = config.icon)
            contextWindowText = remembered.contextWindowTokens.toString()
        }
    }
    val idRequired = stringResource(R.string.model_id_required)
    val idWhitespace = stringResource(R.string.model_id_no_whitespace)
    val idDuplicate = stringResource(R.string.model_id_duplicate)
    val idApiHint = stringResource(R.string.model_id_api_hint)
    val idError = when {
        trimmedId.isBlank() -> idRequired
        trimmedId.any { it.isWhitespace() } -> idWhitespace
        trimmedId != initialModelId && trimmedId != iconModelId && trimmedId in existingModelIds -> idDuplicate
        else -> null
    }

    fun saveIcon() {
        if (iconChanged && (initialModelId.isNotBlank() || idError == null)) {
            val id = initialModelId.ifBlank { iconModelId ?: trimmedId }
            iconModelId = id
            onSaveIcon(id, config.icon)
            iconChanged = false
        }
    }
    LaunchedEffect(trimmedId, idError) { saveIcon() }

    fun saveAndBack() {
        if (idError == null && contextWindow != null) {
            val updated = config.copy(
                // API mode is derived from the selected profile, never configured separately.
                mediaRequestProfileId = if (isMediaModel) chosenMediaProfile.id else config.mediaRequestProfileId,
                mediaApiType = if (isMediaModel) chosenMediaProfile.mode else config.mediaApiType,
                contextWindowTokens = contextWindow,
                modelJson = modelJson,
                displayName = config.displayName.trim(),
                reasoningEffort = config.reasoningEffort?.trim()?.ifEmpty { null },
                reasoningEfforts = config.supportedReasoningEfforts
            )
            // Preserve standalone ModelEditor behavior without blocking the click on DataStore.
            modelMemory.rememberInBackground(trimmedId, updated)
            onSave(trimmedId, updated)
            onBack()
        }
    }

    BackHandler(enabled = LocalScreenTransitionActive.current, onBack = onBack)
    Scaffold(
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                colors = edgeToEdgeTopAppBarColors(),
                title = {
                    Text(
                        text = config.displayName.trim().ifBlank {
                            trimmedId.ifBlank {
                                stringResource(if (initialModelId.isBlank()) R.string.add_model_title else R.string.edit_model_title)
                            }
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        SharedIcon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                },
                actions = {
                    TextButton(onClick = ::saveAndBack, enabled = idError == null && contextWindow != null && !iconImporting) {
                        Text(stringResource(R.string.common_save))
                    }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier.fillMaxSize().consumeWindowInsets(padding).imePadding()
                .verticalScroll(rememberScrollState()).padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            IconPickerRow(
                config.icon,
                onChange = { config = config.copy(icon = it); iconChanged = true; saveIcon() },
                text = modelInitial(modelId),
                onBusyChange = { iconImporting = it },
                matchName = config.displayName.ifBlank { modelId },
                fallbackMatchName = modelId,
                model = true,
                faviconBaseUrl = provider.baseUrl
            )
            OutlinedTextField(
                value = config.displayName,
                onValueChange = { config = config.copy(displayName = it) },
                label = { Text(stringResource(R.string.common_name)) },
                placeholder = {
                    Text(trimmedId.ifBlank {
                        stringResource(if (initialModelId.isBlank()) R.string.add_model_title else R.string.edit_model_title)
                    })
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = modelId,
                onValueChange = { modelId = it },
                label = { Text(stringResource(R.string.model_id)) },
                supportingText = { Text(idError?.takeIf { trimmedId.isNotBlank() } ?: idApiHint) },
                isError = trimmedId.isNotBlank() && idError != null,
                maxLines = 3,
                modifier = Modifier.fillMaxWidth()
            )
            ModelTypeSelector(config.modelType) { type ->
                config = if (type != config.modelType &&
                    (type == ModelType.IMAGE_GENERATION || type == ModelType.VIDEO_GENERATION)) {
                    config.copy(modelType = type, mediaRequestProfileId = "", mediaApiType = MediaGenerationMode.DEFAULT)
                } else config.copy(modelType = type)
                profileExpanded = false
            }
            if (config.modelType == ModelType.TEXT_GENERATION) {
                OutlinedTextField(
                    value = contextWindowText,
                    onValueChange = { if (it.all(Char::isDigit)) contextWindowText = it },
                    label = { Text(stringResource(R.string.context_window)) },
                    supportingText = if (contextWindow == null) ({ Text(stringResource(R.string.context_window_invalid)) }) else null,
                    isError = contextWindow == null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                HorizontalDivider()
                Text(stringResource(R.string.capabilities), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.input_media), style = MaterialTheme.typography.bodyMedium)
                MultiChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    SegmentedButton(
                        checked = config.supportsVision,
                        onCheckedChange = { config = config.copy(supportsVision = it) },
                        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 3),
                        icon = { SharedIcon(Icons.Outlined.Image, contentDescription = null) },
                        label = { Text(stringResource(R.string.common_image)) }
                    )
                    SegmentedButton(
                        checked = config.supportsVideoInput,
                        onCheckedChange = { config = config.copy(supportsVideoInput = it) },
                        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 3),
                        icon = { SharedIcon(Icons.Outlined.Videocam, contentDescription = null) },
                        label = { Text(stringResource(R.string.common_video)) }
                    )
                    SegmentedButton(
                        checked = config.supportsAudioInput,
                        onCheckedChange = { config = config.copy(supportsAudioInput = it) },
                        shape = SegmentedButtonDefaults.itemShape(index = 2, count = 3),
                        icon = { SharedIcon(Icons.Outlined.Audiotrack, contentDescription = null) },
                        label = { Text(stringResource(R.string.common_audio)) }
                    )
                }
                ModelCapabilitySwitch(stringResource(R.string.model_reasoning_support), config.supportsThinking) {
                    config = config.copy(supportsThinking = it)
                }
                if (config.supportsThinking) {
                    // Which levels exist is marked here; the one in use is chosen in the chat's selected-model sheet.
                    Text(stringResource(R.string.model_reasoning_levels), style = MaterialTheme.typography.titleSmall)
                    val markedEfforts = config.supportedReasoningEfforts
                    MultiChoiceSegmentedButtonRow(Modifier.fillMaxWidth().testTag("reasoning-levels")) {
                        SELECTABLE_REASONING_EFFORTS.forEachIndexed { index, level ->
                            SegmentedButton(
                                checked = level in markedEfforts,
                                onCheckedChange = { checked ->
                                    val next = if (checked) markedEfforts + level else markedEfforts - level
                                    config = config.copy(
                                        reasoningEfforts = SELECTABLE_REASONING_EFFORTS.filter { it in next }
                                    )
                                },
                                shape = SegmentedButtonDefaults.itemShape(index, SELECTABLE_REASONING_EFFORTS.size),
                                icon = {},
                                modifier = Modifier.testTag("reasoning-level-$level")
                            ) {
                                Text(
                                    when (level) {
                                        "minimal" -> stringResource(R.string.model_reasoning_level_min)
                                        "medium" -> stringResource(R.string.model_reasoning_level_med)
                                        "xhigh" -> stringResource(R.string.model_reasoning_level_xhigh)
                                        else -> level.replaceFirstChar { it.uppercase() }
                                    },
                                    maxLines = 1,
                                    softWrap = false,
                                    style = MaterialTheme.typography.labelMedium
                                )
                            }
                        }
                    }
                    Text(stringResource(R.string.send_thinking_content_back), style = MaterialTheme.typography.titleSmall)
                    val passbackModes = listOf(ThinkingPassback.AUTO, ThinkingPassback.ON, ThinkingPassback.OFF)
                    val passbackLabels = listOf(
                        stringResource(R.string.common_auto),
                        stringResource(R.string.common_on),
                        stringResource(R.string.common_off)
                    )
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().testTag("passback-thinking-mode")) {
                        passbackModes.forEachIndexed { index, mode ->
                            SegmentedButton(
                                selected = config.passbackMode == mode,
                                onClick = { config = config.withThinkingPassback(mode) },
                                shape = SegmentedButtonDefaults.itemShape(index, passbackModes.size),
                                modifier = Modifier.testTag("passback-thinking-${mode.name.lowercase()}")
                            ) {
                                Text(passbackLabels[index], maxLines = 1)
                            }
                        }
                    }
                    Text(
                        stringResource(R.string.send_thinking_content_back_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                ModelCapabilitySwitch(stringResource(R.string.model_tool_support), config.supportsToolCalls) {
                    config = config.copy(supportsToolCalls = it)
                }
                HorizontalDivider()
                Text(stringResource(R.string.stream), style = MaterialTheme.typography.titleMedium)
                val streamOptions = listOf(null, true, false)
                val streamLabels = listOf(
                    stringResource(R.string.common_default),
                    stringResource(R.string.common_on),
                    stringResource(R.string.common_off)
                )
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().testTag("streaming-mode")) {
                    streamOptions.forEachIndexed { index, value ->
                        SegmentedButton(
                            selected = config.supportStream == value,
                            onClick = { config = config.copy(supportStream = value) },
                            shape = SegmentedButtonDefaults.itemShape(index, streamOptions.size)
                        ) {
                            Text(streamLabels[index])
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = { showModelTools = true },
                        modifier = Modifier.weight(1f).testTag("model-tools-button")
                    ) {
                        Text(stringResource(R.string.model_tools_title), maxLines = 1)
                    }
                    OutlinedButton(
                        onClick = { showSampling = true },
                        modifier = Modifier.weight(1f).testTag("model-arguments-button")
                    ) {
                        Text(stringResource(R.string.model_arguments), maxLines = 1)
                    }
                }
            } else if (isMediaModel) {
                ExposedDropdownMenuBox(
                    expanded = profileExpanded,
                    onExpandedChange = { profileExpanded = !profileExpanded }
                ) {
                    OutlinedTextField(
                        value = chosenMediaProfile.name,
                        onValueChange = {}, readOnly = true,
                        label = { Text(stringResource(if (isVideoGen) R.string.media_video_profile else R.string.media_image_profile)) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(profileExpanded) },
                        modifier = Modifier.menuAnchor(
                            ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled = true
                        ).fillMaxWidth().testTag("media-profile-selector")
                    )
                    ExposedDropdownMenu(profileExpanded, { profileExpanded = false }) {
                        mediaProfiles.values.forEach { profile ->
                            DropdownMenuItem(
                                text = { Text(profile.name + " · " +
                                    stringResource(if (profile.mode == MediaGenerationMode.DEFAULT) R.string.common_default else R.string.media_chain_custom)) },
                                onClick = {
                                    config = config.copy(
                                        mediaApiType = profile.mode,
                                        mediaRequestProfileId = profile.id
                                    )
                                    profileExpanded = false
                                }
                            )
                        }
                    }
                }
            }
            OutlinedButton(
                onClick = { showModelJsonDialog = true },
                modifier = Modifier.fillMaxWidth().testTag("model-json-button")
            ) {
                Text(stringResource(R.string.model_json))
            }
        }
    }
    if (showModelJsonDialog) {
        AlertDialog(
            onDismissRequest = { showModelJsonDialog = false },
            title = { Text(stringResource(R.string.model_json)) },
            scrollableContent = false,
            text = {
                Box(
                    Modifier.fillMaxWidth()
                        .heightIn(max = 480.dp)
                        .verticalScroll(rememberScrollState())
                        .testTag("model-json-code")
                ) {
                    CodeBlockSurface(language = "json", code = modelJsonCode)
                }
            },
            confirmButton = {
                TextButton(onClick = { showModelJsonDialog = false }) {
                    Text(stringResource(R.string.common_close))
                }
            }
        )
    }
    if (showModelTools) {
        ModelToolsDialog(
            provider = provider,
            modelId = initialModelId.ifBlank { trimmedId },
            config = config,
            onSave = { config = it },
            onDismiss = { showModelTools = false }
        )
    }
    if (showSampling) {
        ModelConfigDialog(
            provider = provider.copy(
                config = provider.config.copy(
                    modelConfigs = provider.config.modelConfigs + (initialModelId to config)
                )
            ),
            modelId = initialModelId,
            onSave = { config = it },
            onDismiss = { showSampling = false }
        )
    }
}

@Composable
private fun ModelCapabilitySwitch(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f).padding(end = 12.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
