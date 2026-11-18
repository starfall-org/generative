package org.starfall.generative.shared.ui.providers

import org.starfall.generative.shared.ui.menus.ContextMenuAction
import org.starfall.generative.shared.ui.menus.ContextDropdownMenu
import org.starfall.generative.shared.ui.LoadingIndicator
import org.starfall.generative.shared.ui.ErrorContainer

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
import org.starfall.generative.shared.ui.icons.SharedIcon
import org.starfall.generative.shared.ui.icons.Icons
import org.starfall.generative.shared.ui.layout.nestedContainerBorder
import org.starfall.generative.shared.ui.layout.nestedContainerColor

@Composable
internal fun OAuthAccountCard(
    signedIn: Boolean,
    identity: String?,
    signingIn: Boolean,
    signingOut: Boolean,
    canSignIn: Boolean,
    canSignOut: Boolean,
    canCancelSignIn: Boolean,
    error: String?,
    onSignIn: (() -> Unit)? = null,
    onCancelSignIn: (() -> Unit)? = null,
    onSignOut: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    supportingText: String? = null,
    pendingContent: @Composable ColumnScope.() -> Unit = {}
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
                        text = supportingText ?: when {
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
                if (signedIn && (onSignIn != null || onSignOut != null)) {
                    Box {
                        IconButton(onClick = { menuExpanded = true }, enabled = !busy) {
                            SharedIcon(Icons.Outlined.MoreVert, stringResource(R.string.oauth_account_actions))
                        }
                        if (menuExpanded && !busy) ContextDropdownMenu(
                            expanded = true,
                            actions = buildList {
                                if (onSignIn != null) add(ContextMenuAction(
                                    stringResource(R.string.oauth_sign_in_again),
                                    onSignIn,
                                    Icons.Outlined.OpenInBrowser,
                                    enabled = canSignIn
                                ))
                                if (onSignOut != null) add(ContextMenuAction(
                                    stringResource(R.string.oauth_sign_out),
                                    onSignOut,
                                    Icons.AutoMirrored.Outlined.Logout,
                                    enabled = canSignOut,
                                    destructive = true
                                ))
                            },
                            onDismissRequest = { menuExpanded = false }
                        )
                    }
                }
            }
            if (!signedIn && !busy && onSignIn != null) {
                Button(onClick = onSignIn, enabled = canSignIn, modifier = Modifier.fillMaxWidth()) {
                    SharedIcon(Icons.Outlined.OpenInBrowser, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.oauth_sign_in))
                }
            }
            if (signingIn) {
                pendingContent()
                if (onCancelSignIn != null) {
                    OutlinedButton(onClick = onCancelSignIn, enabled = canCancelSignIn, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.common_cancel))
                    }
                }
            }
            error?.let {
                ErrorContainer(message = it, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}
