package org.starfall.generative.features.tools.ui

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.Json
import org.starfall.generative.R
import org.starfall.generative.shared.model.media.MediaRequestStep

internal typealias MediaVariableToken = org.starfall.generative.shared.ui.inputs.TemplateVariableToken

internal data class MediaVariableCandidate(
    val value: String,
    val description: Int,
    val step: Int = 0,
    val pathHint: String? = null
)

internal fun mediaVariableToken(text: String, cursor: Int): MediaVariableToken? =
    org.starfall.generative.shared.ui.inputs.templateVariableToken(text, cursor)

internal fun mediaVariableMatches(
    candidates: List<MediaVariableCandidate>, prefix: String, limit: Int = 10
): List<MediaVariableCandidate> {
    val query = prefix.trim()
    val matching = candidates.filter { it.value.startsWith(query, ignoreCase = true) }
    return matching.sortedWith(compareBy<MediaVariableCandidate> {
        // Exact and ready-to-use names first, then paths requiring another segment.
        if (it.value.equals(query, ignoreCase = true)) 0 else if (it.pathHint == null) 1 else 2
    }.thenBy { it.value.length }).take(limit)
}

/** Replace the entire existing token (including its closing braces) but no surrounding text. */
internal fun completeMediaVariable(
    field: TextFieldValue, token: MediaVariableToken, candidate: MediaVariableCandidate
): TextFieldValue {
    val insertion = "{{${candidate.value}}}"
    val updated = field.text.replaceRange(token.start, token.end, insertion)
    val caret = token.start + insertion.length - (if (candidate.pathHint != null) 2 else 0)
    return TextFieldValue(updated, TextRange(caret))
}

/** Completion candidates follow the actual template resolver's supported variable grammar. */
internal fun mediaVariableCandidates(
    stepNumber: Int,
    previousSteps: List<MediaRequestStep> = emptyList(),
    testedResponsePaths: Map<Int, List<String>> = emptyMap()
): List<MediaVariableCandidate> = buildList {
    fun addVariable(value: String, description: Int, step: Int = 0, pathHint: String? = null) {
        add(MediaVariableCandidate(value, description, step, pathHint))
    }
    addVariable("prompt", R.string.media_var_prompt)
    addVariable("model", R.string.media_var_model)
    addVariable("api_token", R.string.media_var_api_token)
    addVariable("input_files", R.string.media_var_input_files)
    addVariable("input_file[0]", R.string.media_var_first_input_file)
    // The newest preceding step is normally the most useful source.
    for (step in (1 until stepNumber).reversed()) {
        val prefix = "step$step."
        addVariable(prefix + "response.body", R.string.media_var_step_response_body, step)
        addVariable(prefix + "response.body.", R.string.media_var_step_response_body_path, step, "path")
        addVariable(prefix + "response.headers.", R.string.media_var_step_response_header, step, "header")
        addVariable(prefix + "response.status", R.string.media_var_step_status, step)
        addVariable(prefix + "request.body", R.string.media_var_step_request_body, step)
        addVariable(prefix + "request.body.", R.string.media_var_step_request_body, step, "path")
        addVariable(prefix + "request.headers.", R.string.media_var_step_request_header, step, "header")
        addVariable(prefix + "request.method", R.string.media_var_step_method, step)
        addVariable(prefix + "url", R.string.media_var_step_url, step)

        val previous = previousSteps.getOrNull(step - 1)
        previous?.headers?.keys?.filter { it.isNotBlank() }?.forEach { name ->
            addVariable(prefix + "request.headers.$name", R.string.media_var_step_request_header, step)
        }
        previous?.body?.let { body ->
            runCatching { Json.parseToJsonElement(body) }.getOrNull()?.let { json ->
                discoverMediaJsonPaths(json).forEach { path ->
                    addVariable(prefix + "request.body.$path", R.string.media_var_step_request_body, step)
                }
            }
        }
        testedResponsePaths[step]?.forEach { path ->
            addVariable(prefix + "response.body.$path", R.string.media_var_step_response_body_path, step)
        }
    }
}.distinctBy { it.value }

/** Only suggest paths present in configured requests or actual tested responses. */
internal fun discoverMediaJsonPaths(value: JsonElement, maxPaths: Int = 48): List<String> {
    val found = mutableListOf<String>()
    fun visit(node: JsonElement, path: String, depth: Int) {
        if (found.size >= maxPaths || depth > 5) return
        if (path.isNotEmpty()) found += path
        when (node) {
            is JsonObject -> node.entries.forEach { (name, child) ->
                if (name.isNotBlank() && name.none { it == '.' || it == '[' || it == ']' }) {
                    visit(child, if (path.isEmpty()) name else "$path.$name", depth + 1)
                }
            }
            is JsonArray -> node.firstOrNull()?.let { visit(it, "$path[0]", depth + 1) }
            else -> Unit
        }
    }
    visit(value, "", 0)
    return found.take(maxPaths)
}
