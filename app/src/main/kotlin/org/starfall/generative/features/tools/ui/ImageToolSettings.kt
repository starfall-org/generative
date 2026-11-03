package org.starfall.generative.features.tools.ui

import org.starfall.generative.shared.ui.layout.scrollableScaffoldPadding
import org.starfall.generative.shared.ui.layout.edgeToEdgeTopAppBarColors
import org.starfall.generative.shared.ui.menus.DropdownMenu
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.*
import org.starfall.generative.R
import org.starfall.generative.shared.ui.ToolSwitch
import androidx.compose.ui.res.stringResource
import org.starfall.generative.shared.model.SystemToolConfig
import org.starfall.generative.shared.model.provider.ModelProvider
import org.starfall.generative.shared.networking.text
import org.starfall.generative.shared.ui.dialogs.AlertDialog
import org.starfall.generative.shared.ui.icons.SharedIcon
import org.starfall.generative.shared.ui.icons.Icons
import org.starfall.generative.shared.ui.layout.LocalScreenTransitionActive
import org.starfall.generative.shared.ui.menus.DropdownMenuItem
import org.starfall.generative.shared.ui.inputs.OutlinedTextField
import org.starfall.generative.shared.model.media.ImageOptionField
import org.starfall.generative.shared.helpers.media.imageOptionFields
import org.starfall.generative.shared.helpers.media.validateImageOptions
import org.starfall.generative.shared.helpers.media.optionAt
import org.starfall.generative.shared.helpers.media.withOption
import org.starfall.generative.shared.helpers.media.videoOptionFields
import org.starfall.generative.shared.helpers.media.validateVideoOptions

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImageToolSettings(provider: ModelProvider, config: SystemToolConfig, onSave: (JsonObject, Set<String>) -> Unit, onBack: () -> Unit, video: Boolean = false) {
    val format = remember { Json { prettyPrint = true } }
    val savedOptions = if (video) config.videoOptions else config.imageOptions
    var draft by rememberSaveable(provider.id, config.modelId, video) { mutableStateOf(format.encodeToString(JsonObject.serializer(), savedOptions)) }
    var advanced by rememberSaveable { mutableStateOf(false) }
    var disabledParams by remember(provider.id, config.modelId, video) { mutableStateOf(config.disabledParams) }
    var confirmDiscard by remember { mutableStateOf(false) }
    val parsed = runCatching { Json.parseToJsonElement(draft).jsonObject }.getOrNull()
    val error = if (parsed == null) stringResource(R.string.speech_json_invalid_hint) else if (config.modelId.isBlank()) null else runCatching {
        if (video) validateVideoOptions(provider, config.modelId, parsed)
        else validateImageOptions(provider, config.modelId, parsed)
    }.exceptionOrNull()?.message
    val dirty = parsed != savedOptions || disabledParams != config.disabledParams
    fun back() { if (dirty) confirmDiscard = true else onBack() }
    BackHandler(enabled = LocalScreenTransitionActive.current) { back() }
    Scaffold(
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        topBar = {
            TopAppBar(
                colors = edgeToEdgeTopAppBarColors(),
                title = { Text(stringResource(if (video) R.string.tool_video_settings else R.string.tool_image_settings)) },
                navigationIcon = {
                    IconButton(onClick = { back() }) {
                        SharedIcon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.common_back))
                    }
                },
                actions = {
                    Button(
                        enabled = error == null,
                        onClick = { parsed?.let { onSave(it, disabledParams) } },
                        modifier = Modifier.padding(end = 8.dp)
                    ) { Text(stringResource(R.string.common_save)) }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().consumeWindowInsets(padding).imePadding(),
            contentPadding = scrollableScaffoldPadding(padding, horizontal = 16.dp, top = 16.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text(provider.name + " / " + (provider.config.modelConfigs[config.modelId]?.displayName?.ifBlank { config.modelId } ?: config.modelId),
                    style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.image_tool_model_options_description),
                    style = MaterialTheme.typography.bodySmall)
            }
            item { ToolSwitch(stringResource(R.string.image_tool_advanced_json), advanced) { advanced = it } }
            if (advanced) item {
                OutlinedTextField(
                    value = draft, onValueChange = { if (it.length <= 65536) draft = it },
                    label = { Text(stringResource(R.string.image_tool_additional_body)) },
                    supportingText = { Text(stringResource(R.string.image_tool_request_body_help)) },
                    isError = error != null, minLines = 8, maxLines = 18, modifier = Modifier.fillMaxWidth()
                )
            } else if (parsed != null) {
                items(if (video) videoOptionFields(provider, config.modelId) else imageOptionFields(provider, config.modelId), key = { it.path }) { field ->
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            ImageOptionInput(field, parsed.optionAt(field.path)) { value ->
                                draft = format.encodeToString(JsonObject.serializer(), parsed.withOption(field.path, value))
                            }
                        }
                        Switch(
                            checked = field.path !in disabledParams,
                            onCheckedChange = { enabled ->
                                disabledParams = if (enabled) disabledParams - field.path else disabledParams + field.path
                            }
                        )
                    }
                }
            } else item { Text(stringResource(R.string.image_tool_json_invalid_hint), color = MaterialTheme.colorScheme.error) }
            error?.let { problem -> item { Text(problem, color = MaterialTheme.colorScheme.error) } }
            item { TextButton(onClick = { draft = "{}"; disabledParams = emptySet() }) { Text(stringResource(R.string.image_tool_reset_defaults)) } }
        }
    }
    if (confirmDiscard) AlertDialog(
        onDismissRequest = { confirmDiscard = false },
        title = { Text(stringResource(R.string.common_discard_changes)) }, text = { Text(stringResource(if (video) R.string.image_tool_discard_video_message else R.string.image_tool_discard_image_message)) },
        confirmButton = { TextButton(onClick = onBack) { Text(stringResource(R.string.common_discard)) } },
        dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text(stringResource(R.string.common_keep_editing)) } }
    )
}

@Composable
internal fun ImageOptionInput(
    field: ImageOptionField,
    value: JsonElement?,
    resetSignal: Int = 0,
    onFocusChange: (Boolean) -> Unit = {},
    onChange: (JsonElement?) -> Unit
) {
    val current = (value as? JsonPrimitive)?.takeUnless { it == JsonNull }?.content.orEmpty()
    var text by remember { mutableStateOf(current) }
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(current, focused, resetSignal) { if (!focused) text = current }
    var choosing by remember { mutableStateOf(false) }
    val choices = if (field.kind == "boolean") listOf("true", "false") else field.choices
    val invalid = text.isNotBlank() && when (field.kind) {
        "integer" -> text.toLongOrNull() == null
        "number" -> text.toDoubleOrNull()?.isFinite() != true
        else -> false
    }
    Column {
        OutlinedTextField(
            value = text,
            onValueChange = { input ->
                text = input
                if (input.isBlank()) onChange(null)
                else when (field.kind) {
                    "integer" -> onChange(input.toLongOrNull()?.let { JsonPrimitive(it) } ?: JsonPrimitive(input))
                    "number" -> onChange(input.toDoubleOrNull()?.takeIf { it.isFinite() }?.let { JsonPrimitive(it) } ?: JsonPrimitive(input))
                    else -> onChange(JsonPrimitive(input))
                }
            },
            label = { Text(if (field.labelRes != 0) stringResource(field.labelRes) else field.label) },
            placeholder = { Text(stringResource(R.string.image_tool_provider_default)) },
            readOnly = field.kind == "boolean", singleLine = true, isError = invalid,
            modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused; onFocusChange(it.isFocused) },
             supportingText = if (field.min != null || field.max != null) {{ Text(stringResource(R.string.image_tool_range_hint, field.min ?: "…", field.max ?: "…")) }} else null
        )
        if (choices.isNotEmpty()) Box {
            TextButton(onClick = { choosing = true }) { Text(stringResource(R.string.image_tool_choose_value)) }
            DropdownMenu(expanded = choosing, onDismissRequest = { choosing = false }) {
                DropdownMenuItem(text = { Text(stringResource(R.string.image_tool_provider_default)) }, onClick = { onChange(null); text = ""; choosing = false })
                choices.forEach { choice ->
                    DropdownMenuItem(text = { Text(choice) }, onClick = {
                        onChange(when (field.kind) {
                            "boolean" -> JsonPrimitive(choice.toBoolean())
                            "integer" -> JsonPrimitive(choice.toLong())
                            "number" -> JsonPrimitive(choice.toDouble())
                            else -> JsonPrimitive(choice)
                        })
                        text = choice; choosing = false
                    })
                }
            }
        }
    }
}
