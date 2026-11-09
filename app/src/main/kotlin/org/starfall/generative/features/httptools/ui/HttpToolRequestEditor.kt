package org.starfall.generative.features.httptools.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import org.starfall.generative.R
import org.starfall.generative.features.httptools.logic.previewHttpRequest
import org.starfall.generative.shared.model.CustomHttpTool
import org.starfall.generative.shared.model.HttpToolBodyFormat
import org.starfall.generative.shared.ui.inputs.SelectionDropdown
import org.starfall.generative.shared.ui.layout.SettingsCard

@Composable
internal fun HttpToolRequestEditor(
    method: String, onMethodChange: (String) -> Unit, url: String, onUrlChange: (String) -> Unit,
    query: String, onQueryChange: (String) -> Unit, headers: String, onHeadersChange: (String) -> Unit,
    body: String, onBodyChange: (String) -> Unit, bodyFormat: HttpToolBodyFormat,
    onBodyFormatChange: (HttpToolBodyFormat) -> Unit, parameters: List<String>, draft: () -> CustomHttpTool
) {
    SettingsCard {
        Text(stringResource(R.string.http_tool_request_section), style = MaterialTheme.typography.titleMedium)
        SelectionDropdown(label = stringResource(R.string.http_tool_method),
            options = listOf("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS").map { it to it },
            selected = method, onSelect = onMethodChange, modifier = Modifier.fillMaxWidth())
        HttpLiteralField(stringResource(R.string.http_tool_url), url, onUrlChange, singleLine = true)
        Text(stringResource(R.string.http_tool_mapping_help), style = MaterialTheme.typography.bodySmall)
        HttpRequestTemplateField(stringResource(R.string.http_tool_query), query, onQueryChange, parameters,
            tag = "http_query", objectOnly = true)
        HttpRequestTemplateField(stringResource(R.string.http_tool_headers), headers, onHeadersChange, parameters,
            tag = "http_headers", objectOnly = true, headers = true)
        if (method !in listOf("GET", "HEAD") || body.isNotEmpty()) {
            SelectionDropdown(label = stringResource(R.string.http_request_body_format), selected = bodyFormat,
                onSelect = onBodyFormatChange, options = HttpToolBodyFormat.entries.map { it to it.name })
            HttpRequestTemplateField(stringResource(R.string.http_tool_body), body, onBodyChange, parameters,
                tag = "http_body", json = bodyFormat == HttpToolBodyFormat.JSON, allowEmpty = true)
            if (method in listOf("GET", "HEAD") && body.isNotBlank()) {
                Text(stringResource(R.string.http_request_body_not_allowed), color = MaterialTheme.colorScheme.error)
            }
        }
        HttpRequestPreview(draft)
    }
}

@Composable
private fun HttpRequestPreview(draft: () -> CustomHttpTool) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    var sample by rememberSaveable { mutableStateOf("{}") }
    HttpAdvancedSection(expanded, { expanded = it }, stringResource(R.string.http_request_preview)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.http_request_preview_help), style = MaterialTheme.typography.bodySmall)
            HttpRequestTemplateField(stringResource(R.string.http_request_sample), sample, { sample = it }, emptyList(),
                tag = "http_sample", objectOnly = true, insertable = false)
            val result = runCatching { previewHttpRequest(draft(), sample) }
            if (result.isSuccess) {
                Text(result.getOrThrow(), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.testTag("http_preview"))
            } else {
                Text(stringResource(R.string.http_request_preview_invalid), color = MaterialTheme.colorScheme.error)
            }
        }
    }
}
