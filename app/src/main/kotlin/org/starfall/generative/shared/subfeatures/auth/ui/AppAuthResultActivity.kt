package org.starfall.generative.shared.subfeatures.auth.ui

import android.app.Activity
import android.os.Bundle
import org.starfall.generative.shared.subfeatures.auth.logic.StandardAppAuthAuthorization
import org.starfall.generative.shared.ui.layout.enableTransparentSystemBars

/** AppAuth supplies either its validated response or cancellation error through the PendingIntent. */
class AppAuthResultActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableTransparentSystemBars()
        StandardAppAuthAuthorization.complete(intent)
        finish()
    }
}
