package org.starfall.generative.shared.subfeatures.auth.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.starfall.generative.R
import org.starfall.generative.shared.subfeatures.auth.logic.DeviceCodeSignInState
import org.starfall.generative.shared.model.api.ModelApiType

/** The account's one-time code remains visible after returning from the browser. */
@Composable
internal fun DeviceCodeChallengeText(providerType: ModelApiType) {
    val challenge by DeviceCodeSignInState.challenge.collectAsState()
    val current = challenge?.takeIf { it.providerType == providerType } ?: return
    val context = LocalContext.current
    val clipLabel = stringResource(R.string.device_code_clip_label)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("${stringResource(R.string.device_code_login_hint)} ${current.verificationUrl}",
            style = MaterialTheme.typography.bodySmall)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(current.userCode, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = {
                (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText(clipLabel, current.userCode))
            }) { Text(stringResource(R.string.common_copy)) }
        }
    }
}
