package org.starfall.generative.features.home.chat.ui

import org.starfall.generative.shared.ui.menus.ContextMenuAction
import org.starfall.generative.shared.ui.menus.ContextDropdownMenu
import org.starfall.generative.R
import org.starfall.generative.shared.ui.text.GlyphHitRegistry
import org.starfall.generative.shared.ui.text.LocalGlyphHitRegistry
import org.starfall.generative.shared.ui.text.LocalSelectionBlocked
import org.starfall.generative.shared.ui.text.GatedSelectionContainer
import org.starfall.generative.shared.ui.text.glyphSelectionGate
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.starfall.generative.shared.model.StoredMessage
import org.starfall.generative.shared.ui.icons.SharedIcon
import org.starfall.generative.shared.ui.icons.Icons
import org.starfall.generative.features.home.chat.ui.markdown.MarkdownRenderer
import org.starfall.generative.features.home.chat.logic.markdown.MarkdownRenderCacheIdentity
import org.starfall.generative.shared.ui.layout.prominentContainerBorder
import org.starfall.generative.shared.ui.layout.prominentContainerColor

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun UserMessageCard(
    message: StoredMessage,
    onEdit: (String) -> Unit,
    onDelete: (String) -> Unit,
    onSwitchVersion: (Int) -> Unit,
    selectedImageAttachments: List<String> = emptyList(),
    onToggleChatImage: ((String) -> Unit)? = null,
    onResend: (() -> Unit)? = null,
    onResendFromMenu: (() -> Unit)? = null,
    resendEnabled: Boolean = true,
    modifier: Modifier = Modifier,
    conversationId: String? = null,
    cacheEnabled: Boolean = true
) {
    val context = LocalContext.current
    val localizedResources = LocalResources.current
    val renderCacheIdentity = remember(conversationId, message.id, cacheEnabled) {
        conversationId?.takeIf { cacheEnabled && !message.isQueued }?.let { MarkdownRenderCacheIdentity(it, message.id) }
    }
    val foreground = LocalChatForeground.current
    var showMenu by remember(foreground) { mutableStateOf(false) }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Column(
            modifier = Modifier.align(Alignment.CenterEnd).widthIn(max = maxWidth * 0.85f),
            horizontalAlignment = Alignment.End
        ) {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = prominentContainerColor(),
                border = prominentContainerBorder(),
                modifier = Modifier
                    .clip(RoundedCornerShape(24.dp))
                    .combinedClickable(
                        onClick = { if (message.isQueued) showMenu = true },
                        onLongClick = { showMenu = true }
                    )
            ) {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    if (message.files.isNotEmpty()) {
                        AttachmentStrip(
                            references = message.files,
                            removable = false,
                            modifier = Modifier.padding(bottom = 8.dp),
                            compact = false,
                            selectedImageAttachments = selectedImageAttachments,
                            onToggleChatImage = onToggleChatImage
                        )
                    }

                    // Text Content
                    // Same renderer (markdown, LaTeX, code, tables, font and size) as AI messages.
                    val messageText: @Composable () -> Unit = {
                        MarkdownRenderer(
                            content = message.content,
                            fillMaxWidth = false,
                            trimLeadingBlockSpace = true,
                            cacheIdentity = renderCacheIdentity
                        )
                    }
                    if (message.isQueued) {
                        messageText()
                    } else {
                        // Selection only starts from a long press that lands on the text itself; a press on blank
                        // space inside the bubble is left to the message menu instead of selecting a nearby word.
                        val glyphRegistry = remember { GlyphHitRegistry() }
                        var selectionBlocked by remember { mutableStateOf(false) }
                        val latestText by rememberUpdatedState(messageText)
                        val movableText = remember { movableContentOf { latestText() } }
                        CompositionLocalProvider(
                            LocalGlyphHitRegistry provides glyphRegistry,
                            LocalSelectionBlocked provides selectionBlocked
                        ) {
                            Box(
                                Modifier
                                    .onGloballyPositioned { glyphRegistry.owner = it }
                                    .glyphSelectionGate(glyphRegistry) { selectionBlocked = it }
                            ) {
                                GatedSelectionContainer { movableText() }
                            }
                        }
                    }

                    // Version switcher if multiple versions exist
                    if (!message.isQueued && message.versions.size > 1) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.End,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            IconButton(
                                onClick = {
                                    if (message.activeVersionIndex > 0) {
                                        onSwitchVersion(message.activeVersionIndex - 1)
                                    }
                                },
                                enabled = message.activeVersionIndex > 0,
                                modifier = Modifier.size(24.dp)
                            ) {
                                SharedIcon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.message_previous_version), modifier = Modifier.size(14.dp))
                            }
                            Text(
                                text = "${message.activeVersionIndex + 1}/${message.versions.size}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                                modifier = Modifier.padding(horizontal = 4.dp)
                            )
                            IconButton(
                                onClick = {
                                    if (message.activeVersionIndex < message.versions.size - 1) {
                                        onSwitchVersion(message.activeVersionIndex + 1)
                                    }
                                },
                                enabled = message.activeVersionIndex < message.versions.size - 1,
                                modifier = Modifier.size(24.dp)
                            ) {
                                SharedIcon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = stringResource(R.string.message_next_version), modifier = Modifier.size(14.dp))
                            }
                        }
                    }

                    // Dropdown Context Menu
                    if (foreground && showMenu) ContextDropdownMenu(
                        expanded = true,
                        actions = buildList {
                            if (!message.isQueued) add(ContextMenuAction(
                                stringResource(R.string.common_copy),
                                onClick = {
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    clipboard.setPrimaryClip(ClipData.newPlainText(localizedResources.getString(R.string.common_copy), message.content))
                                    Toast.makeText(context, localizedResources.getString(R.string.common_copied_to_clipboard), Toast.LENGTH_SHORT).show()
                                },
                                icon = Icons.Outlined.ContentCopy
                            ))
                            if (onResendFromMenu != null) add(ContextMenuAction(
                                stringResource(R.string.resend_message),
                                onClick = onResendFromMenu,
                                icon = Icons.AutoMirrored.Filled.Send,
                                enabled = resendEnabled
                            ))
                            add(ContextMenuAction(stringResource(R.string.common_edit),
                                onClick = { onEdit(message.id) }, icon = Icons.Outlined.Edit))
                            add(ContextMenuAction(
                                stringResource(if (message.isQueued) R.string.common_cancel else R.string.common_delete),
                                onClick = { onDelete(message.id) },
                                icon = if (message.isQueued) Icons.Outlined.Close else Icons.Outlined.Delete,
                                destructive = true
                            ))
                        },
                        onDismissRequest = { showMenu = false }
                    )
                }
            }
            if (onResend != null) {
                IconButton(onClick = onResend, enabled = resendEnabled, modifier = Modifier.size(40.dp)) {
                    SharedIcon(Icons.AutoMirrored.Filled.Send,
                        contentDescription = stringResource(org.starfall.generative.R.string.resend_message),
                        modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}
