package org.starfall.generative.shared.ui.providers

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import org.starfall.generative.R
import org.starfall.generative.shared.subfeatures.icons.ui.EntityIcon
import org.starfall.generative.shared.subfeatures.icons.ui.providerInitials
import org.starfall.generative.shared.ui.lists.ItemCard
import org.starfall.generative.shared.ui.icons.SharedIcon
import org.starfall.generative.shared.ui.icons.Icons

internal val ProviderListCardHeight = 88.dp
internal val ProviderGridCardHeight = 152.dp

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

/** Provider tiles use the same dimensions and gutters in either collection. */
internal fun Modifier.providerCardCell(isGrid: Boolean): Modifier =
    fillMaxWidth().height((if (isGrid) ProviderGridCardHeight else ProviderListCardHeight) + 12.dp).padding(6.dp)

@Composable
internal fun ProviderDisplayCard(name: String, detail: String, isGrid: Boolean,
    endpoint: String? = null, icon: String? = null, isHidden: Boolean = false,
    modifier: Modifier = Modifier, onClick: (() -> Unit)? = null,
    actions: @Composable () -> Unit = {}) {
    ItemCard(
        isGrid = isGrid,
        onClick = onClick,
        modifier = modifier
            .heightIn(min = if (isGrid) ProviderGridCardHeight else ProviderListCardHeight),
            icon = {
                EntityIcon(icon, Modifier.size(42.dp), text = providerInitials(name),
                    matchName = name, grayscale = isHidden)
            },
            actions = actions,
            content = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                        Text(
                            text = name,
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = if (isHidden) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.fillMaxWidth()
                        )
                            Text(
                                text = detail,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (isHidden) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                    if (endpoint != null) Text(
                        text = endpoint,
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                        color = MaterialTheme.colorScheme.outline.copy(alpha = if (isHidden) 0.7f else 1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        )
}
