package org.starfall.generative.features.home.chat.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.starfall.generative.R

/** The empty chat greeting fades in whenever this retained chat becomes visible. */
@Composable
internal fun ChatWelcome(isTemporary: Boolean, foreground: Boolean, modifier: Modifier = Modifier) {
    val visibility = remember { MutableTransitionState(false) }
    LaunchedEffect(foreground) { visibility.targetState = foreground }
    Box(modifier, contentAlignment = Alignment.Center) {
        AnimatedVisibility(
            visibleState = visibility,
            enter = fadeIn(tween(480)),
            exit = fadeOut(tween(120))
        ) {
            Column(
                modifier = Modifier.padding(24.dp).testTag("chat-welcome"),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Image(
                    painter = painterResource(R.drawable.generative_logo),
                    contentDescription = null,
                    modifier = Modifier.size(108.dp).testTag("chat-welcome-logo")
                )
                Text(
                    text = if (isTemporary) stringResource(R.string.chat_temporary_title)
                        else stringResource(R.string.app_name),
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center
                )
                Text(
                    text = if (isTemporary) stringResource(R.string.chat_temporary_description)
                        else stringResource(R.string.chat_welcome_tagline),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}
