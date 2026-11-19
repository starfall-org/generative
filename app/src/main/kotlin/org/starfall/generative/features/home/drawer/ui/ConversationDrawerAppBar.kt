package org.starfall.generative.features.home.drawer.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.starfall.generative.R
import org.starfall.generative.shared.ui.icons.FluentUi
import org.starfall.generative.shared.ui.icons.Icons
import org.starfall.generative.shared.ui.icons.SharedIcon
import org.starfall.generative.shared.ui.inputs.OutlinedTextField

@Composable
internal fun ConversationDrawerAppBar(
    remoteExpanded: Boolean,
    onToggleRemote: () -> Unit,
    searchExpanded: Boolean,
    onOpenSearch: () -> Unit,
    onCloseSearch: () -> Unit,
    query: String,
    onQueryChange: (String) -> Unit,
    searchModifier: Modifier,
    remoteContent: @Composable () -> Unit
) {
    Column(Modifier.fillMaxWidth().testTag("conversation_drawer_app_bar")) {
        Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp).heightIn(min = 56.dp)) {
            androidx.compose.animation.AnimatedVisibility(
                visible = searchExpanded,
                modifier = Modifier.align(Alignment.CenterEnd),
                enter = expandHorizontally(expandFrom = Alignment.End),
                exit = ExitTransition.None
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.search_history), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    leadingIcon = { SharedIcon(Icons.Default.Search, null) },
                    trailingIcon = {
                        IconButton(onClick = { if (query.isEmpty()) onCloseSearch() else onQueryChange("") }) {
                            SharedIcon(
                                Icons.Default.Close,
                                stringResource(if (query.isEmpty()) R.string.common_close else R.string.sidebar_clear_search)
                            )
                        }
                    },
                    shape = RoundedCornerShape(28.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
                    ),
                    modifier = searchModifier.fillMaxWidth().testTag("conversation_drawer_search")
                )
            }
            if (!searchExpanded) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Row(
                        Modifier.weight(1f)
                            .heightIn(min = 56.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .testTag("conversation_drawer_app_bar_toggle")
                            .clickable(
                                role = Role.Button,
                                onClickLabel = stringResource(if (remoteExpanded) R.string.collapse else R.string.expand),
                                onClick = onToggleRemote
                            )
                            .padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            stringResource(R.string.app_name),
                            style = MaterialTheme.typography.titleLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.width(8.dp))
                        SharedIcon(
                            if (remoteExpanded) FluentUi.Regular.ChevronUp else FluentUi.Regular.ChevronDown,
                            null,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    IconButton(
                        onClick = onOpenSearch,
                        modifier = Modifier.size(52.dp).testTag("conversation_drawer_search_button")
                    ) {
                        SharedIcon(
                            Icons.Default.Search,
                            stringResource(R.string.sidebar_search),
                            tint = if (query.isNotBlank()) MaterialTheme.colorScheme.primary else LocalContentColor.current
                        )
                    }
                }
            }
        }
        AnimatedVisibility(
            visible = remoteExpanded && !searchExpanded,
            enter = expandVertically(),
            exit = if (searchExpanded) ExitTransition.None else shrinkVertically()
        ) {
            remoteContent()
        }
    }
}
