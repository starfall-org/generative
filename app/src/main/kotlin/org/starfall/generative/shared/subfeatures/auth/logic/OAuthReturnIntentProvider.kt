package org.starfall.generative.shared.subfeatures.auth.logic

import android.content.Context
import android.content.Intent

/** Supplies the host application's activity used when the OAuth notification is reopened. */
interface OAuthReturnIntentProvider {
    fun createOAuthReturnIntent(context: Context): Intent
}
