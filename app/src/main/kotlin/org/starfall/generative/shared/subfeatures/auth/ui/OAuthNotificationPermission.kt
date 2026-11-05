package org.starfall.generative.shared.subfeatures.auth.ui
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

/** Ask on sign-in, then continue even if the user declines notification visibility. */
@Composable
internal fun rememberOAuthStart(onStart: () -> Unit): () -> Unit {
    val context = LocalContext.current
    val latestStart by rememberUpdatedState(onStart)
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        latestStart()
    }
    return {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            permission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            latestStart()
        }
    }
}
