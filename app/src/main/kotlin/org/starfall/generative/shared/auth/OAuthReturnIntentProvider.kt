package org.starfall.generative.shared.auth

import android.content.Context
import android.content.Intent

/** Supplies the host application's activity used when the OAuth notification is reopened. */
interface OAuthReturnIntentProvider {
    fun createOAuthReturnIntent(context: Context): Intent
}
