package org.starfall.generative.shared.ui.code

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import org.starfall.generative.shared.helpers.code.codeTokens

private val darkCodeColors = listOf(
    0xFF8B949E, 0xFFA5D6FF, 0xFF79C0FF, 0xFFFF7B72, 0xFFFFA657,
    0xFFD2A8FF, 0xFF7EE787, 0xFF79C0FF, 0xFFFF7B72
)

private val lightCodeColors = listOf(
    0xFF6E7781, 0xFF0A3069, 0xFF0550AE, 0xFFCF222E, 0xFF953800,
    0xFF8250DF, 0xFF116329, 0xFF0550AE, 0xFFCF222E
)

/** Maps lexer token ranges to the app's themed code presentation. */
internal fun highlightedCode(language: String, source: String, dark: Boolean): AnnotatedString {
    val colors = if (dark) darkCodeColors else lightCodeColors
    return AnnotatedString.Builder(source).apply {
        codeTokens(language, source).forEach { token ->
            addStyle(SpanStyle(color = Color(colors[token.kind.ordinal])), token.start, token.end)
        }
    }.toAnnotatedString()
}
