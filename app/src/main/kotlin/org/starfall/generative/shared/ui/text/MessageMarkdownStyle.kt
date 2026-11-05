package org.starfall.generative.shared.ui.text

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.MarkdownTypography
import org.starfall.generative.database.preferences.MessageFontFamily
import org.starfall.generative.shared.ui.code.LocalCodeRenderingPreferences

internal fun MessageFontFamily.toComposeFontFamily(): FontFamily = when (this) {
    MessageFontFamily.DEFAULT -> FontFamily.Default
    MessageFontFamily.SANS_SERIF -> FontFamily.SansSerif
    MessageFontFamily.SERIF -> FontFamily.Serif
    MessageFontFamily.MONOSPACE -> FontFamily.Monospace
    MessageFontFamily.CURSIVE -> FontFamily.Cursive
}

/** Markdown typography that follows the user's message font size and family, shared by chat and previews. */
@Composable
internal fun messageMarkdownTypography(): MarkdownTypography {
    val type = MaterialTheme.typography
    // Headings and links use the neutral text colour; the theme primary never tints markdown text.
    val textColor = MaterialTheme.colorScheme.onSurface
    val preferences = LocalCodeRenderingPreferences.current
    val bodySize = preferences.effectiveMessageFontSize.sp
    val messageFont = preferences.messageFontFamily.toComposeFontFamily()
    return markdownTypography(
        h1 = type.headlineSmall.copy(fontFamily = messageFont, fontWeight = FontWeight.Bold, fontSize = bodySize * 1.375f, color = textColor),
        h2 = type.titleLarge.copy(fontFamily = messageFont, fontWeight = FontWeight.Bold, fontSize = bodySize * 1.125f),
        h3 = type.titleMedium.copy(fontFamily = messageFont, fontWeight = FontWeight.SemiBold, fontSize = bodySize),
        h4 = type.titleSmall.copy(fontFamily = messageFont, fontWeight = FontWeight.SemiBold, fontSize = bodySize * .90625f),
        h5 = type.bodyLarge.copy(fontFamily = messageFont, fontWeight = FontWeight.Bold, fontSize = bodySize),
        h6 = type.bodyLarge.copy(fontFamily = messageFont, fontWeight = FontWeight.Bold, fontSize = bodySize),
        paragraph = type.bodyLarge.copy(fontFamily = messageFont, fontSize = bodySize, lineHeight = bodySize * 1.375f),
        textLink = TextLinkStyles(SpanStyle(color = textColor))
    )
}
