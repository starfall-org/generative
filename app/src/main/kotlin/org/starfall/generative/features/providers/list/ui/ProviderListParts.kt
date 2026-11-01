package org.starfall.generative.features.providers.list.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.starfall.generative.R
import org.starfall.generative.shared.ui.icons.SharedIcon
import org.starfall.generative.shared.ui.icons.Icons

/** Placeholder for an empty list, or a search without results. */
@Composable
internal fun ProviderEmptyState(hasSearchQuery: Boolean, backdropBlur: Dp) {
    Box(modifier = Modifier.fillMaxSize().blur(backdropBlur), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            SharedIcon(
                imageVector = Icons.Outlined.Hub,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(64.dp)
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = stringResource(if (hasSearchQuery) R.string.no_provider_search_results else R.string.provider_empty_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.outline
            )
        }
    }
}
