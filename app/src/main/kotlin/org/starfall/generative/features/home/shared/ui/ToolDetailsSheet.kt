package org.starfall.generative.features.home.shared.ui

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import org.starfall.generative.shared.ui.text.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.starfall.generative.R
import org.starfall.generative.shared.subfeatures.media.ui.MediaViewer
import org.starfall.generative.shared.subfeatures.media.logic.ToolFiles
import org.starfall.generative.shared.model.ToolActivity
import org.starfall.generative.shared.ui.sheets.ContentSheet
import org.starfall.generative.shared.ui.icons.SharedIcon
import org.starfall.generative.shared.ui.icons.Icons
import org.starfall.generative.shared.ui.code.CodeBlockSurface
import org.starfall.generative.shared.ui.code.LazyCodeBlock
import org.starfall.generative.features.home.shared.ui.ToolSheetState
import org.starfall.generative.shared.ui.layout.AppSwipeTabs
import org.starfall.generative.shared.ui.layout.prominentContainerBorder
import org.starfall.generative.shared.ui.layout.prominentContainerColor

private val toolJson = Json { prettyPrint = true }

@Composable
internal fun ToolDetailsSheetHost(state: ToolSheetState, activities: List<ToolActivity>, visible: Boolean = true) {
    val activity = state.selectedId?.let { id -> activities.find { it.id == id } } ?: return
    ToolDetailsSheet(activity = activity, visible = visible, onDismiss = { state.selectedId = null })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ToolDetailsSheet(
    activity: ToolActivity,
    visible: Boolean = true,
    onDismiss: () -> Unit
) {
    val argsPager = key(activity.id) { androidx.compose.foundation.pager.rememberPagerState(pageCount = { 2 }) }
    val context = LocalContext.current
    val revision by ToolFiles.revision.collectAsState()
    val responseText = remember(activity.response, activity.summary) {
        unescapeToolResponse(activity.response.ifBlank { activity.summary })
    }
    val fileNames = remember(activity.files) {
        activity.files.map { it.removePrefix("tool-file:") }.filter { it.isNotBlank() }.distinct()
    }
    var viewingFile by remember(activity.id) { mutableStateOf<File?>(null) }
    val scrollState = rememberScrollState()
    val sheetState = rememberSaveableStateHolder()
    val responseLanguage = remember(responseText) { toolResponseLanguage(responseText) }
    val responseIsJson = responseLanguage == "json"
    val downloadResponse = rememberResponseDownload(
        fileName = "${
            activity.name.replace(
                Regex("[^A-Za-z0-9._-]+"),
                "_"
            )
        }_response.${if (responseIsJson) "json" else "txt"}",
        mime = if (responseIsJson) "application/json" else "text/plain"
    ) {
        // Build the export only after the user taps download; tool responses are never stored as text files.
        ResponseSource(responseText)
    }
    val canDownload = responseText.isNotBlank()

    if (visible) sheetState.SaveableStateProvider(activity.id) {
    ContentSheet(
        onDismissRequest = onDismiss
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(scrollState)
                .padding(start = 20.dp, end = 20.dp, bottom = 28.dp)
        ) {
            Text(
                text = activity.name,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(Modifier.height(24.dp))
            Text(
                text = stringResource(R.string.tool_details_args),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(10.dp))

            AppSwipeTabs(
                tabs = listOf(stringResource(R.string.tool_details_pretty), stringResource(R.string.common_raw)),
                pagerState = argsPager,
                modifier = Modifier.fillMaxWidth().height(240.dp),
                showDivider = false,
                spacing = 10.dp
            ) { page ->
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    if (page == 0) PrettyArguments(activity.arguments)
                    else RawValueBlock(activity.arguments.ifBlank { "{}" })
                }
            }

            Spacer(Modifier.height(26.dp))
            Text(
                text = stringResource(R.string.tool_details_response),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(10.dp))
            // Lazy chunked rendering: no character cap, only visible chunks are composed and highlighted.
            LazyCodeBlock(
                language = responseLanguage,
                text = responseText.ifBlank {
                    if (activity.status == "running") stringResource(R.string.tool_details_waiting_response) else stringResource(R.string.tool_details_no_response)
                },
                modifier = Modifier.fillMaxWidth(),
                maxHeight = 440.dp,
                onDownload = if (canDownload) downloadResponse else null
            )

            if (fileNames.isNotEmpty()) {
                Spacer(Modifier.height(26.dp))
                Text(
                    text = stringResource(R.string.common_files),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(10.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    fileNames.forEach { name ->
                        val file = remember(name, revision) { ToolFiles(context).resolve(name) }
                        ToolFileRow(name, file) { file?.let { viewingFile = it } }
                    }
                }
            }
        }
    }
    }
    if (visible) viewingFile?.let { file -> MediaViewer(ToolFiles(context), file) { viewingFile = null } }
}


/**
 * Tool responses are sometimes wrapped in a JSON string or carry literal escape sequences ("\n", "\t", …),
 * which would otherwise show up as raw text on one line. Decode a JSON string wrapper first, but
 * preserve escape sequences in JSON objects and arrays so they remain valid JSON and highlight correctly.
 * Only unescape plain-text responses as a fallback.
 */
internal fun unescapeToolResponse(raw: String): String {
    val trimmed = raw.trim()
    if (trimmed.length >= 2 && trimmed.startsWith('"') && trimmed.endsWith('"')) {
        val decoded = runCatching {
            (Json.parseToJsonElement(trimmed) as? JsonPrimitive)?.takeIf { it.isString }?.content
        }.getOrNull()
        if (decoded != null) return decoded
    }
    // Literal backslash escapes are part of serialized JSON, not presentation escapes.
    if (looksLikeJson(raw)) return raw
    if (!raw.contains('\\')) return raw
    val out = StringBuilder(raw.length)
    var i = 0
    while (i < raw.length) {
        val c = raw[i]
        if (c == '\\' && i + 1 < raw.length) {
            when (raw[i + 1]) {
                'n' -> {
                    out.append('\n'); i += 2
                }

                't' -> {
                    out.append('\t'); i += 2
                }

                'r' -> {
                    out.append('\r'); i += 2
                }

                'b' -> {
                    out.append('\b'); i += 2
                }

                'f' -> {
                    out.append('\u000C'); i += 2
                }

                '"' -> {
                    out.append('"'); i += 2
                }

                '/' -> {
                    out.append('/'); i += 2
                }

                '\\' -> {
                    out.append('\\'); i += 2
                }

                else -> {
                    out.append(c); i++
                }
            }
        } else {
            out.append(c)
            i++
        }
    }
    return out.toString()
}

@Composable
private fun ToolFileRow(name: String, file: File?, onOpen: () -> Unit) {
    val context = LocalContext.current
    Surface(
        onClick = onOpen,
        enabled = file != null,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = prominentContainerColor(),
        border = prominentContainerBorder()
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            SharedIcon(Icons.Outlined.InsertDriveFile, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Column(Modifier.weight(1f)) {
                Text(
                    name, style = MaterialTheme.typography.bodyMedium, maxLines = 2,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
                Text(
                    file?.let { android.text.format.Formatter.formatFileSize(context, it.length()) } ?: stringResource(R.string.file_deleted),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun PrettyArguments(raw: String) {
    val parsed = remember(raw) { parseJsonObject(raw) }
    if (parsed == null || parsed.isEmpty()) {
        RawValueBlock(raw.ifBlank { "{}" })
        return
    }

    val borderColor = MaterialTheme.colorScheme.outlineVariant
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, borderColor, RoundedCornerShape(10.dp))
    ) {
        parsed.entries.forEachIndexed { index, (key, value) ->
            Row(modifier = Modifier.fillMaxWidth()) {
                Box(
                    modifier = Modifier
                        .weight(0.36f)
                        .border(0.5.dp, borderColor)
                        .padding(horizontal = 10.dp, vertical = 11.dp)
                ) {
                    Text(
                        text = key,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                Box(
                    modifier = Modifier
                        .weight(0.64f)
                        .border(0.5.dp, borderColor)
                        .padding(horizontal = 10.dp, vertical = 11.dp)
                ) {
                    SelectionContainer {
                        Text(
                            text = displayJsonValue(value),
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace,
                                lineHeight = 18.sp
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

/** Same rendering rules as chat code blocks: font size, highlighting and word wrap all come from [CodeBlockSurface]. */
@Composable
private fun RawValueBlock(value: String) {
    val language = remember(value) { if (looksLikeJson(value)) "json" else "text" }
    CodeBlockSurface(language = language, code = value)
}

private class ResponseSource(val text: String)

/** Returns a click handler that opens the system "save as" picker; nothing is written until a location is chosen. */
@Composable
private fun rememberResponseDownload(fileName: String, mime: String, source: () -> ResponseSource): () -> Unit {
    val context = LocalContext.current
    val localizedResources = LocalResources.current
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf<ResponseSource?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(mime)) { uri: Uri? ->
        val data = pending
        pending = null
        if (uri == null || data == null) return@rememberLauncherForActivityResult
        scope.launch {
            val saved = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri, "wt")!!.use { out ->
                        out.bufferedWriter(Charsets.UTF_8).use { it.write(data.text) }
                    }
                }.isSuccess
            }
            Toast.makeText(context, localizedResources.getString(if (saved) R.string.file_download_saved else R.string.file_download_failed), Toast.LENGTH_SHORT).show()
        }
    }
    return { pending = source(); launcher.launch(fileName) }
}

private fun parseJsonObject(raw: String): JsonObject? = runCatching {
    Json.parseToJsonElement(raw.ifBlank { "{}" }).jsonObject
}.getOrNull()

private fun displayJsonValue(value: JsonElement): String = when (value) {
    is JsonPrimitive -> if (value.isString) value.content else value.toString()
    else -> runCatching { toolJson.encodeToString(JsonElement.serializer(), value) }.getOrDefault(value.toString())
}

internal fun toolResponseLanguage(value: String): String = if (looksLikeJson(value)) "json" else "text"

private fun looksLikeJson(value: String): Boolean {
    val first = value.firstOrNull { !it.isWhitespace() } ?: return false
    if (first != '{' && first != '[') return false
    // Parsing builds a whole tree; for large values only the outer shape is checked to avoid memory spikes.
    if (value.length > 100_000) {
        val last = value.lastOrNull { !it.isWhitespace() }
        return (first == '{' && last == '}') || (first == '[' && last == ']')
    }
    return runCatching { Json.parseToJsonElement(value) }.isSuccess
}
