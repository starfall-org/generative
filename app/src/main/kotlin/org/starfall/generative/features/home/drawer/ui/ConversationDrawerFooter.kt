package org.starfall.generative.features.home.drawer.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.starfall.generative.R
import org.starfall.generative.shared.ui.FeaturesLogo
import org.starfall.generative.shared.ui.icons.Icons
import org.starfall.generative.shared.ui.icons.SharedIcon
import org.starfall.generative.shared.ui.layout.appearanceContainerBorder
import org.starfall.generative.shared.ui.layout.appearanceContainerColor

private val DrawerActionSize = 52.dp

@Composable
internal fun ConversationDrawerFooter(
    featuresLogoImage: String?,
    onOpenMenu: () -> Unit,
    onNewChat: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        OutlinedButton(
            onClick = onOpenMenu,
            contentPadding = PaddingValues(horizontal = 12.dp),
            shape = RoundedCornerShape(28.dp),
            modifier = Modifier.weight(1f, fill = false).heightIn(min = DrawerActionSize)
                .testTag("conversation_drawer_menu_button")
        ) {
            FeaturesLogo(featuresLogoImage, Modifier.size(28.dp).clip(CircleShape))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.sidebar_menu), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(8.dp))
        val border = appearanceContainerBorder()
        OutlinedIconButton(
            onClick = onNewChat,
            modifier = Modifier.size(DrawerActionSize).testTag("conversation_drawer_new_chat"),
            border = border,
            colors = IconButtonDefaults.outlinedIconButtonColors(
                containerColor = appearanceContainerColor(MaterialTheme.colorScheme.primaryContainer),
                contentColor = if (border == null) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.primary
            )
        ) {
            SharedIcon(Icons.Outlined.Compose, stringResource(R.string.sidebar_new_chat))
        }
    }
}
