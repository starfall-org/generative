package org.starfall.generative.features.tools.ui

import org.starfall.generative.shared.ui.layout.scrollableScaffoldPadding
import org.starfall.generative.shared.ui.layout.edgeToEdgeTopAppBarColors
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import org.starfall.generative.shared.ui.text.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.contentOrNull
import org.starfall.generative.integrations.media.MediaRequestChainExecutor
import org.starfall.generative.integrations.media.MusicGenerationClient
import org.starfall.generative.shared.model.media.MediaKind
import org.starfall.generative.shared.model.media.hasUnsafeMusicHeaders
import org.starfall.generative.shared.networking.ToolHttp
import org.starfall.generative.shared.model.SystemToolConfig
import org.starfall.generative.shared.model.media.MediaGenerationMode
import org.starfall.generative.shared.model.media.defaultMediaProfile
import org.starfall.generative.shared.model.media.modelIndependent
import org.starfall.generative.shared.ui.ToolSwitch
import org.starfall.generative.shared.helpers.media.imageOptionFields
import org.starfall.generative.shared.helpers.media.videoOptionFields
import org.starfall.generative.shared.helpers.media.optionAt
import org.starfall.generative.shared.helpers.media.selectMediaPath
import org.starfall.generative.shared.helpers.media.withOption
import org.starfall.generative.shared.helpers.media.suggestedImageOptions
import org.starfall.generative.shared.helpers.media.suggestedVideoOptions
import org.starfall.generative.shared.helpers.media.effectiveMediaDisabledParams
import org.starfall.generative.shared.helpers.media.validateImageOptions
import org.starfall.generative.shared.helpers.media.validateVideoOptions
import org.starfall.generative.shared.ui.menus.DropdownMenu
import org.starfall.generative.shared.ui.menus.DropdownMenuItem
import org.starfall.generative.R
import org.starfall.generative.shared.model.media.MediaFinalResponse
import org.starfall.generative.shared.model.media.MediaRequestProfile
import org.starfall.generative.shared.model.media.MediaRequestStep
import org.starfall.generative.shared.model.provider.ModelProvider
import org.starfall.generative.shared.ui.dialogs.AlertDialog
import org.starfall.generative.shared.ui.icons.SharedIcon
import org.starfall.generative.shared.ui.icons.Icons
import org.starfall.generative.shared.ui.layout.LocalScreenTransitionActive
import org.starfall.generative.shared.ui.layout.SettingsCard
import org.starfall.generative.shared.ui.sheets.ContentSheet
import org.starfall.generative.shared.ui.text.OutlinedTextField
import org.starfall.generative.shared.ui.layout.nestedContainerBorder
import org.starfall.generative.shared.ui.layout.nestedContainerColor

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaRequestProfileEditor(
    provider: ModelProvider?,
    profile: MediaRequestProfile,
    video: Boolean,
    config: SystemToolConfig = SystemToolConfig(),
    onAutoSave: (MediaRequestProfile) -> Unit,
    onBack: () -> Unit,
    kind: MediaKind = if (video) MediaKind.VIDEO else MediaKind.IMAGE
) {
    val music = kind == MediaKind.MUSIC
    var draft by remember(profile.id) { mutableStateOf(if (music) profile.copy(mode = MediaGenerationMode.CUSTOM) else profile) }
    var modeMenu by remember { mutableStateOf(false) }
    var advancedOptions by remember { mutableStateOf(false) }
    var optionsJson by remember(profile.id) { mutableStateOf(profile.options.toString()) }
    val parsedOptions = remember(optionsJson) {
        runCatching { Json.parseToJsonElement(optionsJson) as? JsonObject }.getOrNull()
    }
    val optionsError = if (draft.mode == MediaGenerationMode.DEFAULT && parsedOptions != null &&
        provider != null && config.modelId.isNotBlank()) {
        runCatching {
            if (video) validateVideoOptions(provider, config.modelId, parsedOptions)
            else validateImageOptions(provider, config.modelId, parsedOptions)
        }.exceptionOrNull()?.message
    } else if (draft.mode == MediaGenerationMode.DEFAULT && parsedOptions == null) {
        stringResource(R.string.speech_json_invalid_hint)
    } else null
    var editingStep by remember { mutableStateOf<Int?>(null) }
    var editingFinalResponse by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }
    val defaultTestPrompt = stringResource(R.string.media_chain_default_test_prompt)
    var testPrompt by rememberSaveable(profile.id) { mutableStateOf(defaultTestPrompt) }
    var testOutput by remember { mutableStateOf<String?>(null) }
    var testResponse by remember { mutableStateOf<JsonElement?>(null) }
    val testedResponsePaths = remember(profile.id) { mutableStateMapOf<Int, List<String>>() }
    var testing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var testJob by remember { mutableStateOf<Job?>(null) }
    DisposableEffect(Unit) { onDispose { testJob?.cancel() } }
    val validName = draft.name.trim().isNotEmpty()
    val canPersist = validName && (!music || !draft.hasUnsafeMusicHeaders()) && (draft.mode != MediaGenerationMode.DEFAULT || optionsError == null)
    val dirty = draft != profile || (draft.mode == MediaGenerationMode.DEFAULT && parsedOptions != draft.options)
    val saved by rememberUpdatedState(onAutoSave)
    fun normalizedDraft() = draft.copy(name = draft.name.trim(), options = parsedOptions ?: draft.options,
        mode = if (music) MediaGenerationMode.CUSTOM else draft.mode,
        requiresModel = if (!music && draft.mode == MediaGenerationMode.DEFAULT) true else draft.requiresModel,
        encryptedCredential = draft.encryptedCredential)
    // Debounce typing; flushing on Back prevents losing the last keystroke.
    LaunchedEffect(draft, parsedOptions, optionsError) {
        if (canPersist && normalizedDraft() != profile) {
            delay(250)
            saved(normalizedDraft())
        }
    }
    fun leave() {
        if (dirty && !canPersist) {
            confirmDiscard = true
        } else {
            if (canPersist && dirty) saved(normalizedDraft())
            onBack()
        }
    }
    fun closeStepEditor() {
        testJob?.cancel()
        testJob = null
        testing = false
        testOutput = null
        testResponse = null
        editingStep = null
    }
    fun removeStep(index: Int) {
        if (draft.chain.steps.size <= 1) return
        draft = draft.copy(chain = draft.chain.copy(
            steps = draft.chain.steps.filterIndexed { stepIndex, _ -> stepIndex != index }
        ))
        testedResponsePaths.clear() // Indices have changed.
        closeStepEditor()
    }
    BackHandler(enabled = LocalScreenTransitionActive.current) { leave() }
    Scaffold(
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        modifier = Modifier.testTag("media-profile-editor-screen"),
        topBar = {
            TopAppBar(
                colors = edgeToEdgeTopAppBarColors(),
                title = {
                    Text(draft.name.ifBlank { stringResource(R.string.media_gen_profile_name) }, maxLines = 1)
                },
                navigationIcon = {
                    IconButton(onClick = ::leave) {
                        SharedIcon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.common_back))
                    }
                },
                actions = {
                    if (!music) Box {
                        TextButton(onClick = { modeMenu = true }, enabled = profile.id != defaultMediaProfile(video).id) {
                            Text(stringResource(if (draft.mode == MediaGenerationMode.DEFAULT) R.string.common_default else R.string.media_chain_custom))
                            SharedIcon(Icons.Default.ArrowDropDown, null)
                        }
                        DropdownMenu(modeMenu, { modeMenu = false }) {
                            MediaGenerationMode.entries.forEach { mode ->
                                DropdownMenuItem(
                                    text = { Text(stringResource(if (mode == MediaGenerationMode.DEFAULT) R.string.common_default else R.string.media_chain_custom)) },
                                    onClick = {
                                        draft = draft.copy(mode = mode, requiresModel = if (mode == MediaGenerationMode.DEFAULT) true else draft.requiresModel)
                                        modeMenu = false
                                    }
                                )
                            }
                        }
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().imePadding(),
            contentPadding = scrollableScaffoldPadding(padding, horizontal = 16.dp, top = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                OutlinedTextField(
                    value = draft.name,
                    onValueChange = { draft = draft.copy(name = it) },
                    label = { Text(stringResource(R.string.media_gen_profile_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("media-profile-name-field")
                )
                if (provider != null) {
                    Text(
                        provider.name,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
            }
            if (!music && draft.mode == MediaGenerationMode.CUSTOM) item {
                SettingsCard {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.media_gen_profile_require_model),
                            modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                        Switch(
                            checked = draft.requiresModel,
                            onCheckedChange = { draft = draft.copy(requiresModel = it) },
                            modifier = Modifier.testTag("media-profile-require-model")
                        )
                    }
                }
            }
            if (music && draft.hasUnsafeMusicHeaders()) item {
                Text(stringResource(R.string.music_unsafe_headers), color = MaterialTheme.colorScheme.error)
            }
            if (music) item {
                MusicProfileCredentialField(draft.id, draft.encryptedCredential) {
                    draft = draft.copy(encryptedCredential = it)
                }
            }
            if (draft.mode == MediaGenerationMode.DEFAULT) {
                item {
                    if (provider == null || config.modelId.isBlank()) {
                        Text(stringResource(R.string.media_gen_profile_no_model_hint),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall)
                    } else {
                        Spacer(Modifier.height(10.dp))
                        Text(stringResource(R.string.media_default_params_tip),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (!video && org.starfall.generative.shared.helpers.media.isDalleImageModel(provider.type, config.modelId)) {
                            Text(stringResource(R.string.media_default_base64_note),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        ToolSwitch(stringResource(R.string.image_tool_advanced_json), advancedOptions) { advancedOptions = it }
                    }
                }
                if (video && provider != null && config.modelId.isNotBlank()) {
                    item { Text(stringResource(R.string.media_default_video_options),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                if (advancedOptions || provider == null || config.modelId.isBlank()) {
                    item {
                        OutlinedTextField(
                            value = optionsJson, onValueChange = { if (it.length <= 65536) optionsJson = it },
                            label = { Text(stringResource(R.string.image_tool_additional_body)) },
                            isError = parsedOptions == null, minLines = 8, maxLines = 18,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                } else if (parsedOptions != null) {
                    val suggested = if (video) suggestedVideoOptions(provider.type) else suggestedImageOptions(provider.type, config.modelId)
                    val enabledParams = effectiveMediaDisabledParams(draft,
                        if (video) "generate_video" else "generate_image", config.disabledParams)
                    items(if (video) videoOptionFields(provider, config.modelId) else imageOptionFields(provider, config.modelId), key = { it.path }) { field ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                ImageOptionInput(field, parsedOptions.optionAt(field.path) ?: suggested.optionAt(field.path)) { value ->
                                    val next = parsedOptions.withOption(field.path, value)
                                    optionsJson = next.toString()
                                }
                            }
                            Switch(
                                checked = field.path !in enabledParams,
                                onCheckedChange = { enabled ->
                                    draft = draft.copy(
                                        disabledParams = if (enabled) enabledParams - field.path else enabledParams + field.path,
                                        paramsConfigured = true
                                    )
                                }
                            )
                        }
                    }
                }
                if (optionsError != null) item { Text(optionsError, color = MaterialTheme.colorScheme.error) }
            } else {
            item {
                SettingsCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.media_chain_steps), style = MaterialTheme.typography.titleMedium)
                            Text(
                                stringResource(R.string.media_chain_steps_help),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(onClick = {
                            draft = draft.copy(chain = draft.chain.copy(steps = draft.chain.steps + MediaRequestStep()))
                            editingStep = draft.chain.steps.lastIndex
                        }) {
                            SharedIcon(Icons.Default.Add, stringResource(R.string.common_add))
                        }
                    }
                    draft.chain.steps.forEachIndexed { index, step ->
                        Surface(
                            onClick = { editingStep = index },
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                            shape = MaterialTheme.shapes.medium,
                            color = nestedContainerColor(),
                            border = nestedContainerBorder()
                        ) {
                            Row(
                                Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(stringResource(R.string.media_chain_step, index + 1))
                                    Text(
                                        "${step.method} ${step.endpoint.ifBlank { "/" }}",
                                        fontFamily = FontFamily.Monospace,
                                        maxLines = 1,
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                                SharedIcon(Icons.Outlined.Edit, stringResource(R.string.common_edit))
                            }
                        }
                    }
                }
            }
            item {
                SettingsCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.media_chain_final_response), style = MaterialTheme.typography.titleMedium)
                            Text(
                                finalResponseSummary(draft.chain.finalResponse),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(onClick = { editingFinalResponse = true }) {
                            SharedIcon(Icons.Outlined.Edit, stringResource(R.string.common_edit))
                        }
                    }
                }
            }
                chainValidationError(draft.chain)?.takeUnless { message ->
                    message == R.string.media_chain_one_step_required || message == R.string.media_chain_media_path_required
                }?.let { message ->
                    item {
                        Text(stringResource(message), color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }

    editingStep?.let { selectedIndex ->
        val index = selectedIndex.coerceIn(0, draft.chain.steps.lastIndex)
        ContentSheet(onDismissRequest = ::closeStepEditor, fixedHeightFraction = 0.92f) {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).imePadding().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.media_chain_editing_step, index + 1),
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(
                        enabled = draft.chain.steps.size > 1,
                        onClick = { removeStep(index) }
                    ) {
                        SharedIcon(Icons.Outlined.Delete, stringResource(R.string.common_remove))
                    }
                }
                if (music || draft.modelIndependent || (provider != null && config.modelId.isNotBlank())) {
                    OutlinedTextField(
                        value = testPrompt,
                        onValueChange = { testPrompt = it },
                        label = { Text(stringResource(R.string.media_chain_test_prompt)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Button(
                        onClick = {
                            testJob?.cancel()
                            testOutput = null
                            testResponse = null
                            testedResponsePaths.keys.filter { it >= index + 1 }.forEach { testedResponsePaths.remove(it) }
                            testing = true
                            val currentProvider = provider
                            val currentChain = draft.chain
                            val currentPrompt = testPrompt
                            testJob = scope.launch {
                                try {
                                    val result = withContext(Dispatchers.IO) {
                                        if (music) MusicGenerationClient(ToolHttp()).test(draft.copy(chain = currentChain), currentPrompt, index)
                                        else MediaRequestChainExecutor(ToolHttp()).testThroughStep(
                                            currentChain, if (draft.modelIndependent) null else currentProvider,
                                            if (draft.modelIndependent) "" else config.modelId,
                                            currentPrompt, index, requiresModel = !draft.modelIndependent
                                        )
                                    }
                                    testOutput = "HTTP ${result.statusCode}\n${result.body}"
                                    testResponse = runCatching { Json.parseToJsonElement(result.body) }.getOrNull()
                                    testResponse?.let { testedResponsePaths[index + 1] = discoverMediaJsonPaths(it) }
                                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                                    throw cancelled
                                } catch (error: Exception) {
                                    val token = currentProvider?.auth?.token.orEmpty()
                                    val detail = error.message.orEmpty()
                                    testOutput = if (token.isBlank()) detail else detail.replace(token, "[redacted]")
                                } finally {
                                    testing = false
                                }
                            }
                        },
                        enabled = !testing
                    ) { Text(stringResource(R.string.media_chain_test_step, index + 1)) }
                    Text(stringResource(if (draft.modelIndependent) R.string.media_chain_test_no_model_note
                        else R.string.media_chain_test_file_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (testing) LinearProgressIndicator(Modifier.fillMaxWidth())
                    testResponse?.let { response ->
                        val mediaPath = findMediaResponsePath(response)
                        if (mediaPath != null) {
                            OutlinedButton(onClick = {
                                draft = draft.copy(chain = draft.chain.copy(finalResponse =
                                    draft.chain.finalResponse.copy(
                                        type = org.starfall.generative.shared.model.media.MediaResponseType.JSON,
                                        mediaPath = mediaPath,
                                        sourceStep = index + 1
                                    )
                                ))
                            }) { Text(stringResource(R.string.media_chain_apply_media_path, mediaPath)) }
                        }
                        val statusPath = findStatusResponsePath(response)
                        if (statusPath != null) {
                            OutlinedButton(onClick = {
                                val step = draft.chain.steps[index]
                                draft = draft.copy(chain = draft.chain.copy(steps = draft.chain.steps.toMutableList().apply {
                                    set(index, step.copy(observation = step.observation.copy(
                                        enabled = true, statusPath = statusPath
                                    )))
                                }))
                            }) { Text(stringResource(R.string.media_chain_apply_status_path, statusPath)) }
                        }
                    }
                    testOutput?.let { output ->
                        SelectionContainer {
                            Text(output, fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                } else {
                    Text(stringResource(R.string.media_chain_test_requires_model),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                MediaRequestStepEditor(
                    stepNumber = index + 1,
                    step = draft.chain.steps[index],
                    previousSteps = draft.chain.steps.take(index),
                    testedResponsePaths = testedResponsePaths,
                    onChange = { updated ->
                        testedResponsePaths.keys.filter { it >= index + 1 }.forEach { testedResponsePaths.remove(it) }
                        draft = draft.copy(
                            chain = draft.chain.copy(
                                steps = draft.chain.steps.mapIndexed { stepIndex, current ->
                                    if (stepIndex == index) updated else current
                                }
                            )
                        )
                    }
                )
            }
        }
    }

    if (editingFinalResponse) {
        ContentSheet(onDismissRequest = { editingFinalResponse = false }, fixedHeightFraction = 0.72f) {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).imePadding().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.media_chain_final_response),
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f)
                    )
                }
                MediaFinalResponseEditor(
                    finalResponse = draft.chain.finalResponse,
                    stepCount = draft.chain.steps.size,
                    music = music,
                    onChange = { updated ->
                        draft = draft.copy(chain = draft.chain.copy(finalResponse = updated))
                    }
                )
            }
        }
    }

    if (confirmDiscard) AlertDialog(
        onDismissRequest = { confirmDiscard = false },
        title = { Text(stringResource(R.string.common_discard_changes)) },
        text = { Text(stringResource(R.string.media_chain_discard_message)) },
        confirmButton = {
            TextButton(onClick = { confirmDiscard = false; onBack() }) {
                Text(stringResource(R.string.common_discard))
            }
        },
        dismissButton = {
            TextButton(onClick = { confirmDiscard = false }) {
                Text(stringResource(R.string.common_keep_editing))
            }
        }
    )
}

@Composable
private fun finalResponseSummary(response: MediaFinalResponse): String = when (response.type) {
    org.starfall.generative.shared.model.media.MediaResponseType.JSON ->
        response.mediaPath.ifBlank { stringResource(R.string.media_chain_json_path_missing) }
    else -> response.type.name.lowercase().replace('_', '-')
}

/** Suggest only paths observed in a real response, never paths inferred from the provider name. */
private fun findMediaResponsePath(response: JsonElement): String? =
    listOf("data[0].url", "data.url", "output[0].url", "output.url", "images[0].url", "result.url", "url")
        .firstOrNull { path ->
            runCatching { selectMediaPath(response, path) }.getOrNull()?.any { value ->
                (value as? JsonPrimitive)?.contentOrNull?.let {
                    it.startsWith("https://") || it.startsWith("http://")
                } == true
            } == true
        }

private fun findStatusResponsePath(response: JsonElement): String? =
    listOf("status", "data.status", "result.status", "state", "data.state")
        .firstOrNull { path ->
            runCatching { selectMediaPath(response, path) }.getOrNull()?.any {
                (it as? JsonPrimitive)?.contentOrNull?.isNotBlank() == true
            } == true
        }
