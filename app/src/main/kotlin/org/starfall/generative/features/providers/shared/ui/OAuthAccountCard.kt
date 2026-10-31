package org.starfall.generative.features.providers.shared.ui

import org.starfall.generative.shared.ui.menus.DropdownMenu
import org.starfall.generative.shared.ui.LoadingIndicator

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.starfall.generative.R
import org.starfall.generative.shared.model.api.ModelApiType
import org.starfall.generative.shared.ui.auth.DeviceCodeChallengeText
import org.starfall.generative.shared.ui.icons.SharedIcon
import org.starfall.generative.shared.ui.icons.Icons
import org.starfall.generative.shared.ui.menus.DropdownMenuItem
import org.starfall.generative.shared.ui.layout.nestedContainerBorder
import org.starfall.generative.shared.ui.layout.nestedContainerColor

@Composable
internal fun OAuthAccountCard(
    providerType: ModelApiType,
    signedIn: Boolean,
    identity: String?,
    signingIn: Boolean,
    signingOut: Boolean,
    canSignIn: Boolean,
    canSignOut: Boolean,
    canCancelSignIn: Boolean,
    error: String?,
    onSignIn: () -> Unit,
    onCancelSignIn: () -> Unit,
    onSignOut: () -> Unit,
    modifier: Modifier = Modifier
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val busy = signingIn || signingOut
    val status = stringResource(when {
        signingOut -> R.string.oauth_signing_out
        signingIn -> R.string.oauth_signing_in
        signedIn -> R.string.oauth_signed_in
        else -> R.string.oauth_signed_out
    })
    val accent = if (signedIn && !busy) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.onSurfaceVariant

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = nestedContainerColor(),
        border = nestedContainerBorder()
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = CircleShape,
                    color = if (signedIn && !busy) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surfaceContainerHighest
                ) {
                    Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                        if (busy) {
                            LoadingIndicator(Modifier.size(20.dp))
                        } else {
                            SharedIcon(
                                if (signedIn) Icons.Outlined.CheckCircle else Icons.Outlined.AccountCircle,
                                contentDescription = null,
                                tint = accent,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(status, style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                    Text(
                        text = when {
                            signingOut -> stringResource(R.string.oauth_removing_account)
                            signingIn -> stringResource(R.string.oauth_browser_pending)
                            signedIn -> identity?.takeIf { it.isNotBlank() }
                                ?: stringResource(R.string.oauth_account_connected)
                            else -> stringResource(R.string.oauth_browser_hint)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (signedIn) {
                    Box {
                        IconButton(onClick = { menuExpanded = true }, enabled = !busy) {
                            SharedIcon(Icons.Outlined.MoreVert, stringResource(R.string.oauth_account_actions))
                        }
                        DropdownMenu(menuExpanded && !busy, onDismissRequest = { menuExpanded = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.oauth_sign_in_again)) },
                                leadingIcon = { SharedIcon(Icons.Outlined.OpenInBrowser, null) },
                                enabled = canSignIn,
                                onClick = { menuExpanded = false; onSignIn() }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.oauth_sign_out),
                                    color = MaterialTheme.colorScheme.error) },
                                leadingIcon = { SharedIcon(Icons.AutoMirrored.Outlined.Logout, null,
                                    tint = MaterialTheme.colorScheme.error) },
                                enabled = canSignOut,
                                onClick = { menuExpanded = false; onSignOut() }
                            )
                        }
                    }
                }
            }
            if (!signedIn && !busy) {
                Button(onClick = onSignIn, enabled = canSignIn, modifier = Modifier.fillMaxWidth()) {
                    SharedIcon(Icons.Outlined.OpenInBrowser, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.oauth_sign_in))
                }
            }
            if (signingIn) {
                DeviceCodeChallengeText(providerType)
                OutlinedButton(onClick = onCancelSignIn, enabled = canCancelSignIn, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
            error?.let {
                Text(it, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error)
            }
        }
    }
}
