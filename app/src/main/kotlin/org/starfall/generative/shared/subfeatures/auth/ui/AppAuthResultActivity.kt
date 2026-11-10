package org.starfall.generative.shared.subfeatures.auth.ui

import androidx.activity.ComponentActivity
import android.os.Bundle
import org.starfall.generative.shared.subfeatures.auth.logic.StandardAppAuthAuthorization
import org.starfall.generative.shared.ui.layout.enableTransparentSystemBars

/** AppAuth supplies either its validated response or cancellation error through the PendingIntent. */
class AppAuthResultActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableTransparentSystemBars()
        StandardAppAuthAuthorization.complete(intent)
        finish()
    }
}
