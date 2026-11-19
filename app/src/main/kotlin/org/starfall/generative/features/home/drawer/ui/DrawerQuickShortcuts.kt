package org.starfall.generative.features.home.drawer.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.starfall.generative.R
import org.starfall.generative.shared.ui.icons.SharedIcon
import org.starfall.generative.shared.ui.icons.FluentUi
import org.starfall.generative.shared.ui.layout.ComponentGroup
import org.starfall.generative.shared.ui.layout.ComponentGroupSlot
import org.starfall.generative.shared.ui.layout.prominentContainerBorder
import org.starfall.generative.shared.ui.layout.prominentContainerColor

@Composable
internal fun DrawerQuickShortcuts(
    onOpenMediaStorage: () -> Unit,
    modifier: Modifier = Modifier,
    maxExpandedHeight: Dp = 280.dp
) {
    var instructionsExpanded by rememberSaveable { mutableStateOf(false) }
    ComponentGroup(
        modifier = modifier,
        items = listOf(
            ComponentGroupSlot("instructions") { itemModifier, shape ->
                DrawerInstructionsSection(
                    expanded = instructionsExpanded,
                    onToggle = { instructionsExpanded = !instructionsExpanded },
                    maxExpandedHeight = maxExpandedHeight,
                    modifier = itemModifier,
                    shape = shape
                )
            },
            ComponentGroupSlot("media") { itemModifier, shape ->
                Surface(
                    modifier = itemModifier.testTag("drawer_media_card"),
                    shape = shape,
                    color = prominentContainerColor(),
                    border = prominentContainerBorder()
                ) {
                    // Media row — opens the media grid sheet
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(shape)
                            .clickable(onClick = onOpenMediaStorage)
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier.size(42.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                SharedIcon(
                                    imageVector = FluentUi.Regular.Image,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.drawer_media_short_title),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(Modifier.heightIn(min = 2.dp))
                            Text(
                                text = stringResource(R.string.settings_storage_description),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        SharedIcon(
                            imageVector = FluentUi.Regular.ChevronRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        )
    )
}
