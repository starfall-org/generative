package org.starfall.generative.features.httptools.ui

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import kotlinx.serialization.json.JsonPrimitive
import org.starfall.generative.features.httptools.logic.insertHttpParameter
import org.starfall.generative.shared.ui.inputs.TemplateVariableToken

internal fun httpParameterMatches(parameters: List<String>, prefix: String, json: Boolean): List<String> {
    val query = prefix.trim()
    return parameters.distinct().filter {
        it.startsWith(query, ignoreCase = true) || json &&
            JsonPrimitive(it).toString().drop(1).dropLast(1).startsWith(query, ignoreCase = true)
    }.sortedWith(compareBy<String> { if (it.equals(query, ignoreCase = true)) 0 else 1 }.thenBy { it.length }).take(10)
}

internal fun completeHttpParameter(
    field: TextFieldValue, token: TemplateVariableToken, parameter: String, json: Boolean
): TextFieldValue {
    val (text, cursor) = insertHttpParameter(field.text, token.start, token.end, parameter, json)
    return TextFieldValue(text, TextRange(cursor))
}
