package org.starfall.generative.shared.ui.code

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.starfall.generative.R
import org.starfall.generative.shared.ui.preview.CodePreviewActivity
import org.starfall.generative.shared.helpers.code.canPreviewCode
import org.starfall.generative.shared.model.preferences.WordWrapMode
import org.starfall.generative.shared.ui.theme.LocalAmoledMode
import org.starfall.generative.shared.ui.icons.SharedIcon
import org.starfall.generative.shared.ui.icons.Icons
import org.starfall.generative.shared.ui.LoadingIndicator

/** Header shared by every code surface: language badge, wrap toggle, preview, download and copy. */
@Composable
internal fun CodeBlockHeader(
    language: String,
    isStreaming: Boolean,
    originalCode: String,
    sessionWrap: Boolean,
    onToggleWrap: () -> Unit,
    onDownload: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val localizedResources = LocalResources.current
    val preferences = LocalCodeRenderingPreferences.current
    var copied by remember(language, originalCode) { mutableStateOf(false) }
    LaunchedEffect(copied) { if (copied) { delay(2000); copied = false } }
    Column(Modifier.fillMaxWidth()) {
    Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerHigh)
        .padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f))
                .padding(horizontal = 6.dp, vertical = 2.dp)) {
                Text(language.ifBlank { stringResource(R.string.code_language_fallback) }, style = MaterialTheme.typography.labelSmall.copy(
                    fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold), color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            if (isStreaming) {
                Spacer(Modifier.width(8.dp))
                LoadingIndicator(Modifier.size(10.dp), color = MaterialTheme.colorScheme.primary)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (preferences.wordWrapMode == WordWrapMode.OFF) {
                IconButton(onClick = onToggleWrap, modifier = Modifier.size(28.dp)) {
                    SharedIcon(Icons.Outlined.WrapText, stringResource(if (sessionWrap) R.string.code_wrap_disable else R.string.code_wrap_enable),
                        tint = if (sessionWrap) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, modifier = Modifier.size(16.dp))
                }
            }
            if (preferences.codePreviewEnabled && canPreviewCode(language)) {
                IconButton(onClick = { CodePreviewActivity.open(context, language, originalCode) }, modifier = Modifier.size(28.dp)) {
                    SharedIcon(Icons.Outlined.Visibility, stringResource(R.string.preview_code),
                        tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(16.dp))
                }
            }
            if (onDownload != null) {
                IconButton(onClick = onDownload, modifier = Modifier.size(28.dp)) {
                    SharedIcon(Icons.Outlined.Download, stringResource(R.string.file_download),
                        tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(16.dp))
                }
            }
            IconButton(onClick = {
                // Very large texts exceed the clipboard binder limit; fail softly instead of crashing.
                val result = runCatching {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText(localizedResources.getString(R.string.code_copy), originalCode))
                }
                if (result.isSuccess) copied = true
                Toast.makeText(context, localizedResources.getString(if (result.isSuccess) R.string.code_copied else R.string.code_too_large_to_copy), Toast.LENGTH_SHORT).show()
            }, modifier = Modifier.size(28.dp)) {
                SharedIcon(if (copied) Icons.Outlined.Check else Icons.Outlined.ContentCopy, stringResource(R.string.code_copy),
                    tint = if (copied) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, modifier = Modifier.size(16.dp))
            }
        }
    }
    if (LocalAmoledMode.current) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
    }
}
