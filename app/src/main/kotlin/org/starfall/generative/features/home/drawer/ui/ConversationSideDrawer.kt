@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package org.starfall.generative.features.home.drawer.ui

import org.starfall.generative.shared.ui.lists.FadingLazyColumn
import androidx.compose.runtime.saveable.rememberSaveable

import org.starfall.generative.shared.ui.menus.ContextMenuAction
import org.starfall.generative.shared.ui.menus.ContextDropdownMenu
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import org.starfall.generative.R
import org.starfall.generative.extensions.launcher.Launcher
import java.util.UUID
import org.starfall.generative.shared.model.ChatFolder
import org.starfall.generative.shared.model.ConversationListItem
import org.starfall.generative.shared.model.RemoteMachine
import org.starfall.generative.shared.model.SidebarOrganization
import org.starfall.generative.shared.model.provider.ModelProvider
import org.starfall.generative.shared.ui.dialogs.AlertDialog
import org.starfall.generative.shared.ui.icons.SharedIcon
import org.starfall.generative.shared.ui.icons.Icons
import org.starfall.generative.shared.ui.inputs.OutlinedTextField
import org.starfall.generative.shared.ui.layout.fadingScrollEdges
import org.starfall.generative.shared.ui.layout.windowHeightIn
import org.starfall.generative.features.home.drawer.logic.lastModelIcon
import org.starfall.generative.shared.ui.layout.prominentContainerBorder
import org.starfall.generative.shared.ui.layout.prominentContainerColor
import org.starfall.generative.shared.ui.layout.modalContainerColor

private val DrawerEdgeOverlap = 24.dp

@Composable
private fun SidebarLabel(text: String) { Text(text, Modifier.padding(start = 8.dp, top = 10.dp, bottom = 6.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
@Composable
fun ConversationSideDrawer(
    conversations: List<ConversationListItem>,
    currentConversationId: String?,
    isOpen: Boolean,
    generatingConversationIds: Set<String> = emptySet(),
    organization: SidebarOrganization,
    onUpdateOrganization: ((SidebarOrganization) -> SidebarOrganization) -> Unit,
    onDeleteConversations: (Set<String>) -> Unit,
    onSelectConversation: (ConversationListItem) -> Unit,
    onNewChat: () -> Unit,
    onRenameConversation: (String, String) -> Unit,
    onDeleteConversation: (String) -> Unit,
    onNavigateToSettings: () -> Unit,
    featuresLogoImage: String? = null,
    backgroundImage: String? = null,
    backgroundTransparency: Float = 0.5f,
    onCloseDrawer: () -> Unit,
    onOpenMediaStorage: () -> Unit = {},
    onOpenRemoteMachines: () -> Unit = {},
    serverProfiles: List<org.starfall.generative.database.repositories.ServerConnectionProfile> = emptyList(),
    remoteEnabled: Boolean = false,
    remoteConnectionId: String? = null,
    runtimeSelecting: Boolean = false,
    remoteError: Boolean = false,
    onReconnectServer: () -> Unit = {},
    onSelectRuntime: (String?) -> Unit = {},
    onOpenServerConnections: () -> Unit = {},
    onOpenTerminal: (RemoteMachine) -> Unit = {},
    onPortForward: (RemoteMachine) -> Unit = {},
    onSaveMachineTools: (RemoteMachine) -> Unit = {},
    modifier: Modifier = Modifier,
    providers: List<ModelProvider> = emptyList(),
    onSuggestConversationTitle: (suspend (String) -> Result<String>)? = null,
    onOpenProject: (() -> Unit)? = null
) {
    val providersById = remember(providers) { providers.associateBy { it.id } }
    var search by rememberSaveable { mutableStateOf("") }
    var searchExpanded by rememberSaveable { mutableStateOf(false) }
    var remoteExpanded by rememberSaveable { mutableStateOf(false) }
    val searchFocusRequester = remember { FocusRequester() }
    var selecting by remember { mutableStateOf(false) }
    // Long-press opens a chat's context menu; selection mode is entered explicitly via Select.
    var contextChatId by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var deleting by remember { mutableStateOf(emptySet<String>()) }
    var renaming by remember { mutableStateOf<ConversationListItem?>(null) }
    var grouping by remember { mutableStateOf(false) }
    var creatingFolder by remember { mutableStateOf(false) }
    var folderName by remember { mutableStateOf("") }
    var editingFolder by remember { mutableStateOf<ChatFolder?>(null) }
    var deletingFolder by remember { mutableStateOf<ChatFolder?>(null) }
    val folderIds = organization.folders.map { it.id }.toSet()
    var folderOrder by remember(organization.folders) { mutableStateOf(organization.folders) }
    var folderDragStart by remember { mutableStateOf<List<ChatFolder>?>(null) }
    val folderDrag = Launcher.rememberState<String>()
    val folderListState = rememberLazyListState()
    val matches = remember(conversations, search, organization.folders, organization.chatFolders) {
        val matchingFolders = organization.folders.filter { search.isNotBlank() && it.name.contains(search, true) }.map { it.id }.toSet()
        conversations.filter { search.isBlank() || it.title.contains(search, true) || organization.chatFolders[it.id] in matchingFolders }
    }
    val matchingIds = matches.map { it.id }.toSet()
    fun stopSelecting() { selecting = false; selected = emptySet(); contextChatId = null }
    fun dismissContextMenu() { contextChatId = null }
    fun onChatClick(id: String, chat: ConversationListItem) {
        if (selecting) {
            selected = if (id in selected) selected - id else selected + id
            if (selected.isEmpty()) stopSelecting()
        } else {
            onSelectConversation(chat)
            onCloseDrawer()
        }
    }
    fun onChatLongClick(id: String) {
        if (!selecting) contextChatId = id
    }
    fun selectChat(id: String) {
        contextChatId = null
        selecting = true
        selected = setOf(id)
    }
    fun moveToFolder(folderId: String?) {
        val ids = selected
        onUpdateOrganization { it.moveChats(ids, folderId) }
        grouping = false
        stopSelecting()
    }
    LaunchedEffect(conversations) {
        selected = selected.intersect(conversations.map { it.id }.toSet())
        if (contextChatId != null && conversations.none { it.id == contextChatId }) stopSelecting()
    }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    var searchFocused by remember { mutableStateOf(false) }
    fun unfocusSearch() {
        // Keep the active filter when focus leaves or the drawer closes. Only the
        // search field's clear button should reset the query.
        searchFocused = false
        searchExpanded = false
        focusManager.clearFocus(force = true)
        keyboard?.hide()
    }
    LaunchedEffect(searchExpanded) {
        if (searchExpanded) {
            withFrameNanos { }
            searchFocusRequester.requestFocus()
        }
    }
    // Closing the keyboard (back gesture, IME close button) also leaves the search field.
    val imeVisible = WindowInsets.isImeVisible
    var imeWasVisible by remember { mutableStateOf(false) }
    LaunchedEffect(imeVisible) {
        if (imeVisible) imeWasVisible = true
        else if (imeWasVisible) { imeWasVisible = false; if (searchExpanded) unfocusSearch() }
    }
    LaunchedEffect(isOpen) {
        if (isOpen) {
            // ModalNavigationDrawer can restore focus to the first text field while its sheet opens.
            // Clear that restored focus after the opening frame; tapping Search afterwards still
            // focuses it normally.
            withFrameNanos { }
            unfocusSearch()
        } else {
            stopSelecting()
            unfocusSearch()
        }
    }
    BackHandler(enabled = selecting && isOpen) { stopSelecting() }
    ModalDrawerSheet(
        modifier = modifier.fillMaxWidth().testTag("conversation_side_drawer"),
        drawerShape = RectangleShape,
        drawerContainerColor = modalContainerColor(MaterialTheme.colorScheme.surface),
        // Inset the interactive content only; do not inset the drawer surface/photo.
        windowInsets = WindowInsets(0, 0, 0, 0)
    ) {
        BoxWithConstraints(
            modifier = Modifier.fillMaxSize()
        ) {
            val halfDrawerHeight = maxHeight * 0.5f
            org.starfall.generative.shared.ui.background.ProfileBackground(backgroundImage, transparency = backgroundTransparency)
            Column(
                Modifier.fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .imePadding()
                    // A tap on any non-interactive part of the drawer leaves the search field.
                    .pointerInput(selecting) {
                        detectTapGestures(
                            onTap = { unfocusSearch() },
                            onLongPress = {
                                unfocusSearch()
                                if (!selecting) {
                                    contextChatId = null
                                    selecting = true
                                    selected = emptySet()
                                }
                            }
                        )
                    },
                // Keep the scroll fades inside the pinned header and footer.
                verticalArrangement = Arrangement.spacedBy(-DrawerEdgeOverlap)
            ) {
            Column(Modifier.zIndex(1f)) {
                ConversationDrawerAppBar(
                    remoteExpanded = remoteExpanded,
                    onToggleRemote = { remoteExpanded = !remoteExpanded },
                    searchExpanded = searchExpanded,
                    onOpenSearch = { searchExpanded = true },
                    onCloseSearch = ::unfocusSearch,
                    query = search,
                    onQueryChange = { search = it },
                    searchModifier = Modifier.focusRequester(searchFocusRequester).onFocusChanged {
                        if (!it.isFocused && searchFocused && searchExpanded) unfocusSearch()
                        searchFocused = it.isFocused
                    }
                ) {
                    DrawerRemoteControls(
                        onOpenRemoteMachines = onOpenRemoteMachines,
                        serverProfiles = serverProfiles,
                        remoteEnabled = remoteEnabled,
                        remoteError = remoteError,
                        remoteConnectionId = remoteConnectionId,
                        runtimeSelecting = runtimeSelecting,
                        onReconnectServer = onReconnectServer,
                        onSelectRuntime = onSelectRuntime,
                        onOpenServerConnections = onOpenServerConnections,
                        onOpenTerminal = onOpenTerminal,
                        onPortForward = onPortForward,
                        onSaveMachineTools = onSaveMachineTools
                    )
                }
                if (selecting) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 8.dp)
                            .testTag("conversation_drawer_selection_bar"),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = ::stopSelecting) { SharedIcon(Icons.Default.Close, stringResource(R.string.sidebar_exit_selection)) }
                        Text("${selected.size}", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                        val allSelected = matchingIds.isNotEmpty() && selected.containsAll(matchingIds)
                        Checkbox(
                            checked = allSelected,
                            onCheckedChange = { checked ->
                                selected = if (checked) selected + matchingIds else selected - matchingIds
                            },
                            enabled = matchingIds.isNotEmpty()
                        )
                        IconButton(enabled = onOpenProject == null || selected.isEmpty(), onClick = {
                            folderName = ""
                            if (onOpenProject != null) {
                                stopSelecting()
                                onOpenProject()
                            } else if (selected.isEmpty()) creatingFolder = true else grouping = true
                        }) {
                            SharedIcon(
                                if (onOpenProject != null) Icons.Outlined.FolderOpen else Icons.Outlined.CreateNewFolder,
                                stringResource(if (onOpenProject != null) R.string.sidebar_open_project
                                    else if (selected.isEmpty()) R.string.sidebar_create_folder else R.string.sidebar_move_selected_to_folder)
                            )
                        }
                        IconButton(enabled = selected.isNotEmpty(), onClick = { deleting = selected }) { SharedIcon(Icons.Outlined.Delete, stringResource(R.string.common_delete), tint = MaterialTheme.colorScheme.error) }
                    }
                }
            }
            val folderShape = RoundedCornerShape(12.dp)
            with(Launcher) {
                Host(folderDrag, modifier = Modifier.weight(1f).fillMaxWidth(), preview = { drag ->
                    folderOrder.firstOrNull { it.id == drag.item.data }?.let { folder ->
                        Surface(modifier = Modifier.fillMaxSize(), shape = folderShape, color = prominentContainerColor(),
                            border = prominentContainerBorder()) {
                            Row(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                SharedIcon(Icons.Outlined.Folder, null, modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(folder.name, maxLines = 1)
                            }
                        }
                    }
                }) {
                    LazyColumn(
                        state = folderListState,
                        modifier = Modifier.fillMaxSize().autoScroll(folderDrag, folderListState)
                            .testTag("conversation_drawer_list")
                            .fadingScrollEdges(folderListState, depth = DrawerEdgeOverlap),
                        // Keep the first and last items clear of the controls at rest.
                        contentPadding = PaddingValues(
                            horizontal = 12.dp,
                            vertical = 12.dp + DrawerEdgeOverlap
                        ),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                item(key = "quick-shortcuts") {
                    Column(Modifier.fillMaxWidth()) {
                        DrawerQuickShortcuts(
                            onOpenMediaStorage = {
                                unfocusSearch()
                                onOpenMediaStorage()
                            },
                            modifier = Modifier.testTag("conversation_drawer_quick_shortcuts"),
                            maxExpandedHeight = halfDrawerHeight
                        )
                        HorizontalDivider(
                            modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                        )
                    }
                }
                val folders = folderOrder.sortedByDescending { it.pinned }
                if (folders.isNotEmpty()) item(key = "folders-label") { SidebarLabel(stringResource(R.string.sidebar_folders)) }
                folders.forEach { folder ->
                    val chats = matches.filter { organization.chatFolders[it.id] == folder.id }
                        .sortedWith(compareByDescending<ConversationListItem> { it.id in organization.pinnedChatIds }.thenByDescending { it.updatedAt })
                    if (search.isBlank() || chats.isNotEmpty() || folder.name.contains(search, true)) {
                        item(key = "folder:${folder.id}") {
                            var menu by remember { mutableStateOf(false) }
                            Surface(
                                modifier = Modifier.animateItem()
                                    .dragSource(folderDrag, folder.id, folder.id,
                                        enabled = search.isBlank() && !selecting,
                                        onDragStart = { folderDragStart = folderOrder },
                                        onDragEnd = { outcome ->
                                            val original = folderDragStart
                                            if (outcome.accepted && original != null && original != folderOrder) {
                                                val ids = folderOrder.map { it.id }
                                                onUpdateOrganization { current ->
                                                    val byId = current.folders.associateBy { it.id }
                                                    current.copy(folders = ids.mapNotNull(byId::get) +
                                                        current.folders.filterNot { it.id in ids })
                                                }
                                            } else if (!outcome.accepted) {
                                                original?.let { folderOrder = it }
                                            }
                                            folderDragStart = null
                                        })
                                    .dropTarget(folderDrag, "folder-slot:${folder.id}",
                                        enabled = search.isBlank() && !selecting,
                                        accepts = { candidate ->
                                            candidate.key != folder.id &&
                                                folderOrder.firstOrNull { it.id == candidate.data }?.pinned == folder.pinned
                                        },
                                        onDragEnter = { drag ->
                                            val from = folderOrder.indexOfFirst { it.id == drag.item.data }
                                            val to = folderOrder.indexOfFirst { it.id == folder.id }
                                            if (from >= 0 && to >= 0 && from != to) {
                                                folderOrder = folderOrder.toMutableList().apply { add(to, removeAt(from)) }
                                            }
                                        },
                                        onDrop = { true })
                                    .dropTarget(folderDrag, "folder-self:${folder.id}",
                                        enabled = search.isBlank() && !selecting,
                                        priority = 5,
                                        accepts = { it.key == folder.id },
                                        onDrop = { folderDragStart != folderOrder }),
                                shape = folderShape,
                                color = prominentContainerColor(),
                                border = prominentContainerBorder()
                            ) {
                                Row(Modifier.fillMaxWidth().combinedClickable(onClick = {
                                    onUpdateOrganization { state -> state.copy(folders = state.folders.map { if (it.id == folder.id) it.copy(expanded = !it.expanded) else it }) }
                                }).padding(start = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                    SharedIcon(if (folder.expanded || search.isNotBlank()) Icons.Default.ExpandMore else Icons.Default.ChevronRight, stringResource(R.string.sidebar_toggle_folder))
                                    SharedIcon(Icons.Outlined.Folder, null, modifier = Modifier.size(20.dp))
                                    Text(folder.name, Modifier.weight(1f).padding(8.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text("${chats.size}", style = MaterialTheme.typography.labelSmall)
                                    if (folder.pinned) SharedIcon(Icons.Default.PushPin, stringResource(R.string.sidebar_pinned_folder), Modifier.size(14.dp))
                                    Box {
                                        IconButton(onClick = { menu = true }) { SharedIcon(Icons.Default.MoreVert, stringResource(R.string.sidebar_folder_options)) }
                                        if (menu) ContextDropdownMenu(
                                            expanded = true,
                                            actions = listOf(
                                                ContextMenuAction(
                                                    stringResource(if (folder.pinned) R.string.sidebar_unpin else R.string.sidebar_pin),
                                                    onClick = { onUpdateOrganization { state -> state.copy(folders = state.folders.map { if (it.id == folder.id) it.copy(pinned = !it.pinned) else it }) } },
                                                    icon = Icons.Outlined.PushPin
                                                ),
                                                ContextMenuAction(stringResource(R.string.sidebar_rename),
                                                    onClick = { editingFolder = folder; folderName = folder.name }, icon = Icons.Outlined.Edit),
                                                ContextMenuAction(stringResource(R.string.sidebar_remove_folder),
                                                    onClick = { deletingFolder = folder }, icon = Icons.Outlined.Delete, destructive = true)
                                            ),
                                            onDismissRequest = { menu = false }
                                        )
                                    }
                                }
                            }
                        }
                        if (folder.expanded || search.isNotBlank()) {
                            items(chats, key = { "chat:${it.id}" }) { chat ->
                                ConversationItem(chat, currentConversationId, generatingConversationIds, selecting, chat.id in selected, chat.id in organization.pinnedChatIds,
                                    modelIcon = chat.lastModelIcon(providersById),
                                    onClick = { onChatClick(chat.id, chat) },
                                    onLongClick = { onChatLongClick(chat.id) },
                                    showContextMenu = contextChatId == chat.id, onContextMenuDismiss = ::dismissContextMenu,
                                    onSelect = { selectChat(chat.id) },
                                    onPin = { onUpdateOrganization { it.copy(pinnedChatIds = if (chat.id in it.pinnedChatIds) it.pinnedChatIds - chat.id else it.pinnedChatIds + chat.id) } },
                                    onRename = { renaming = chat }, onDelete = { deleting = setOf(chat.id) },
                                    onMove = { selecting = true; selected = setOf(chat.id); folderName = ""; grouping = true }, modifier = Modifier.padding(start = 14.dp))
                            }
                        }
                    }
                }
                val history = matches.filter { organization.chatFolders[it.id] !in folderIds }
                    .sortedWith(compareByDescending<ConversationListItem> { it.id in organization.pinnedChatIds }.thenByDescending { it.updatedAt })
                items(history, key = { "chat:${it.id}" }) { chat ->
                    ConversationItem(chat, currentConversationId, generatingConversationIds, selecting, chat.id in selected, chat.id in organization.pinnedChatIds,
                        modelIcon = chat.lastModelIcon(providersById),
                        onClick = { onChatClick(chat.id, chat) },
                        onLongClick = { onChatLongClick(chat.id) },
                        showContextMenu = contextChatId == chat.id, onContextMenuDismiss = ::dismissContextMenu,
                        onSelect = { selectChat(chat.id) },
                        onPin = { onUpdateOrganization { it.copy(pinnedChatIds = if (chat.id in it.pinnedChatIds) it.pinnedChatIds - chat.id else it.pinnedChatIds + chat.id) } },
                        onRename = { renaming = chat }, onDelete = { deleting = setOf(chat.id) },
                        onMove = { selecting = true; selected = setOf(chat.id); folderName = ""; grouping = true })
                }
                if (matches.isEmpty()) item { Text(if (search.isBlank()) stringResource(R.string.no_conversations_yet) else stringResource(R.string.sidebar_no_matching_conversations), Modifier.padding(12.dp), color = MaterialTheme.colorScheme.outline) }
                    }
                }
            }
            Box(Modifier.zIndex(1f)) {
                ConversationDrawerFooter(
                    featuresLogoImage = featuresLogoImage,
                    onOpenMenu = onNavigateToSettings,
                    onNewChat = { stopSelecting(); onNewChat(); onCloseDrawer() }
                )
            }
            }
        }
    }
    if (creatingFolder) AlertDialog(
        onDismissRequest = { creatingFolder = false },
        title = { Text(stringResource(R.string.sidebar_create_folder)) },
        text = { OutlinedTextField(folderName, { folderName = it }, label = { Text(stringResource(R.string.common_folder_name)) }, singleLine = true) },
        confirmButton = {
            TextButton(enabled = folderName.isNotBlank(), onClick = {
                val name = folderName.trim()
                if (name.isNotEmpty()) onUpdateOrganization { it.copy(folders = it.folders + ChatFolder(UUID.randomUUID().toString(), name)) }
                creatingFolder = false
            }) { Text(stringResource(R.string.sidebar_create_action)) }
        },
        dismissButton = { TextButton(onClick = { creatingFolder = false }) { Text(stringResource(R.string.common_close)) } }
    )
    if (grouping) AlertDialog(
        onDismissRequest = { grouping = false }, title = { Text(stringResource(R.string.sidebar_move_to_folder)) },
        scrollableContent = false,
        text = {
            FadingLazyColumn(Modifier.windowHeightIn(maxFraction = 0.5f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(organization.folders, key = { it.id }) { folder -> TextButton(onClick = { moveToFolder(folder.id) }, modifier = Modifier.fillMaxWidth()) { SharedIcon(Icons.Outlined.Folder, null); Spacer(Modifier.width(8.dp)); Text(folder.name) } }
                item { TextButton(onClick = { moveToFolder(null) }) { Text(stringResource(R.string.sidebar_move_to_history)) } }
                item { OutlinedTextField(folderName, { folderName = it }, label = { Text(stringResource(R.string.sidebar_new_folder_name)) }, singleLine = true) }
            }
        },
        confirmButton = { TextButton(enabled = folderName.isNotBlank(), onClick = {
            val name = folderName.trim(); val ids = selected; val id = UUID.randomUUID().toString()
            onUpdateOrganization { it.copy(folders = it.folders + ChatFolder(id, name)).moveChats(ids, id) }
            grouping = false; stopSelecting()
        }) { Text(stringResource(R.string.sidebar_create_folder)) } }, dismissButton = { TextButton(onClick = { grouping = false }) { Text(stringResource(R.string.common_close)) } }
    )
    if (deleting.isNotEmpty()) AlertDialog(onDismissRequest = { deleting = emptySet() },
        title = { Text(stringResource(R.string.dialog_delete_conversations_title)) },
        text = { Text(stringResource(R.string.sidebar_delete_chats_message, deleting.size)) },
        confirmButton = { TextButton(onClick = { val ids = deleting; deleting = emptySet(); if (ids.size == 1) onDeleteConversation(ids.first()) else onDeleteConversations(ids); stopSelecting() }) { Text(stringResource(R.string.common_delete), color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { deleting = emptySet() }) { Text(stringResource(R.string.common_close)) } })
    renaming?.let { chat ->
        key(chat.id) {
            RenameConversationDialog(
                chat = chat,
                onSuggestTitle = onSuggestConversationTitle,
                onSave = { onRenameConversation(chat.id, it); renaming = null },
                onDismiss = { renaming = null }
            )
        }
    }
    if (editingFolder != null) AlertDialog(onDismissRequest = { editingFolder = null }, title = { Text(stringResource(R.string.sidebar_rename_folder)) },
        text = { OutlinedTextField(folderName, { folderName = it }, singleLine = true) },
        confirmAtRight = true,
        confirmButton = { TextButton(enabled = folderName.isNotBlank(), onClick = { val id = editingFolder!!.id; val name = folderName.trim(); onUpdateOrganization { state -> state.copy(folders = state.folders.map { if (it.id == id) it.copy(name = name) else it }) }; editingFolder = null }) { Text(stringResource(R.string.common_save)) } }, dismissButton = { TextButton(onClick = { editingFolder = null }) { Text(stringResource(R.string.common_close)) } })
    if (deletingFolder != null) AlertDialog(onDismissRequest = { deletingFolder = null }, title = { Text(stringResource(R.string.dialog_remove_folder_title)) }, text = { Text(stringResource(R.string.sidebar_remove_folder_message)) },
        confirmButton = { TextButton(onClick = { val id = deletingFolder!!.id; onUpdateOrganization { it.removeFolder(id) }; deletingFolder = null }) { Text(stringResource(R.string.common_remove)) } }, dismissButton = { TextButton(onClick = { deletingFolder = null }) { Text(stringResource(R.string.common_close)) } })
}
