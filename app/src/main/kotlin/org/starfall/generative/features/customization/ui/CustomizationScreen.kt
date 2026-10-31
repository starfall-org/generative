package org.starfall.generative.features.customization.ui

import org.starfall.generative.shared.ui.inputs.ControlledSlider
import org.starfall.generative.shared.ui.layout.scrollableScaffoldPadding
import org.starfall.generative.shared.ui.layout.edgeToEdgeTopAppBarColors
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.starfall.generative.R
import org.starfall.generative.shared.icons.AppImages
import org.starfall.generative.shared.icons.IconStore
import org.starfall.generative.shared.storage.preferences.UserProfile
import org.starfall.generative.shared.storage.preferences.AppPreferences
import org.starfall.generative.shared.theme.CustomPalette
import org.starfall.generative.shared.storage.preferences.UserProfileImageSlot
import org.starfall.generative.shared.ui.FeaturesLogo
import org.starfall.generative.shared.ui.icons.SharedIcon
import org.starfall.generative.shared.ui.icons.Icons
import org.starfall.generative.shared.ui.icons.FluentUi
import org.starfall.generative.shared.ui.layout.prominentContainerColor
import org.starfall.generative.shared.ui.background.ProfileBackground
import org.starfall.generative.shared.ui.dialogs.AlertDialog
import org.starfall.generative.shared.ui.dialogs.Dialog
import org.starfall.generative.shared.ui.menus.DropdownMenu
import org.starfall.generative.shared.ui.menus.DropdownMenuItem

/** App-wide customization: colors, component opacity, profile image and backgrounds. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomizationScreen(
    profile: UserProfile,
    onChange: ((UserProfile) -> UserProfile) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    appearance: AppPreferences = AppPreferences(),
    customizationEnabled: Boolean = true,
    onCustomizationEnabledChange: (Boolean) -> Unit = {},
    onDynamicColorChange: (Boolean) -> Unit = {},
    onColorSchemeChange: (String) -> Unit = {},
    onCustomPaletteChange: (CustomPalette) -> Unit = {},
    onItemTransparencyChange: (Float) -> Unit = {},
    onInputAreaTransparencyChange: (Float) -> Unit = {},
    onInputAreaBlurChange: (Float) -> Unit = {}
) {
    val context = LocalContext.current
    val localizedResources = LocalResources.current
    val scope = rememberCoroutineScope()
    var target by remember { mutableStateOf<UserProfileImageSlot?>(null) }
    var menuFor by remember { mutableStateOf<UserProfileImageSlot?>(null) }
    val transparencyDrafts = remember { mutableStateMapOf<UserProfileImageSlot, Float>() }
    LaunchedEffect(profile) {
        transparencyDrafts.toMap().forEach { (slot, draft) ->
            if (slot.imageTransparency(profile) == draft) transparencyDrafts.remove(slot)
        }
    }
    var showStorage by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var backgroundDialog by remember { mutableStateOf<UserProfileImageSlot?>(null) }
    var colorsDialog by remember { mutableStateOf(false) }
    var inputDialog by remember { mutableStateOf(false) }
    LaunchedEffect(customizationEnabled) {
        if (!customizationEnabled) {
            if (target != UserProfileImageSlot.PHOTO) {
                showStorage = false
                target = null
            }
            if (menuFor != UserProfileImageSlot.PHOTO) menuFor = null
            backgroundDialog = null
            colorsDialog = false
            inputDialog = false
            error = null
        }
    }

    fun storeImage(slot: UserProfileImageSlot, path: String?) {
        if (!customizationEnabled && slot != UserProfileImageSlot.PHOTO) return
        val previous = slot.get(profile)
        val updated = slot.put(profile, path)
        onChange { slot.put(it, path) }
        scope.launch(Dispatchers.IO) { UserProfileImages.cleanup(context, previous, updated) }
    }

    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        val slot = target
        if (uri != null && slot != null) scope.launch {
            busy = true
            try {
                val image = UserProfileImages.importGallery(context, uri)
                storeImage(slot, image)
            } catch (e: Exception) { error = e.localizedMessage } finally { busy = false }
        }
    }
    fun openMenu(slot: UserProfileImageSlot) { menuFor = slot }
    fun chooseStorage(slot: UserProfileImageSlot) {
        target = slot; menuFor = null; showStorage = true
    }

    Scaffold(
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                colors = edgeToEdgeTopAppBarColors(),
                title = { Text(stringResource(R.string.customization_title), fontWeight = FontWeight.SemiBold) },
                navigationIcon = { IconButton(onClick = onBack) {
                    SharedIcon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.common_back))
                } },
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().testTag("customization-list"),
            contentPadding = scrollableScaffoldPadding(padding, horizontal = 16.dp, top = 12.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item(key = "photo") {
                Surface(shape = RoundedCornerShape(28.dp), color = prominentContainerColor()) {
                    Column(
                        Modifier.fillMaxWidth().padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(Modifier.size(156.dp), contentAlignment = Alignment.Center) {
                            Box(Modifier.fillMaxSize().clip(RoundedCornerShape(30.dp))) {
                                ProfileImage(profile.photo, Modifier.fillMaxSize(), fallback = true)
                            }
                            Box(Modifier.align(Alignment.BottomEnd)) {
                                FilledTonalIconButton(
                                    onClick = { openMenu(UserProfileImageSlot.PHOTO) },
                                    modifier = Modifier.size(42.dp)
                                ) { SharedIcon(Icons.Default.MoreVert, stringResource(R.string.user_profile_photo_options)) }
                                SlotMenu(
                                    expanded = menuFor == UserProfileImageSlot.PHOTO,
                                    hasImage = profile.photo != null,
                                    onDismiss = { menuFor = null },
                                    onGallery = {
                                        target = UserProfileImageSlot.PHOTO
                                        menuFor = null
                                        gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                                    },
                                    onStorage = { chooseStorage(UserProfileImageSlot.PHOTO) },
                                    onRemove = { menuFor = null; storeImage(UserProfileImageSlot.PHOTO, null) }
                                )
                            }
                        }
                        Text(stringResource(R.string.user_profile_photo_title), style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            item(key = "enable") {
                Surface(shape = RoundedCornerShape(18.dp), color = prominentContainerColor()) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(stringResource(R.string.customization_enable),
                            style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        Switch(
                            checked = customizationEnabled,
                            onCheckedChange = onCustomizationEnabledChange,
                            modifier = Modifier.testTag("customization-enabled-switch")
                                .semantics { contentDescription = localizedResources.getString(R.string.customization_enable) }
                        )
                    }
                }
            }
            item(key = "backgrounds-heading") {
                Text(stringResource(R.string.customization_backgrounds_title),
                    modifier = Modifier.padding(top = 10.dp, start = 4.dp)
                        .alpha(if (customizationEnabled) 1f else 0.4f),
                    style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            }
            item(key = "backgrounds") {
                CustomizationEnabledSection(customizationEnabled, showDisabledTag = true) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(
                            Triple(UserProfileImageSlot.CHAT, R.string.user_profile_background_chat, profile.chatBackground),
                            Triple(UserProfileImageSlot.DRAWER, R.string.user_profile_background_drawer, profile.drawerBackground),
                            Triple(UserProfileImageSlot.MENU, R.string.user_profile_background_menu, profile.menuBackground),
                            Triple(UserProfileImageSlot.SHEET, R.string.user_profile_background_bottom_sheet, profile.sheetBackground),
                            Triple(UserProfileImageSlot.DIALOG, R.string.user_profile_background_dialog, profile.dialogBackground)
                        ).forEach { (slot, title, image) ->
                            CustomizationActionRow(
                                title = stringResource(title),
                                summary = "${((transparencyDrafts[slot] ?: slot.imageTransparency(profile)) * 100).toInt()}%",
                                tag = "background-setting-${slot.name.lowercase()}",
                                onClick = { if (customizationEnabled) backgroundDialog = slot },
                                leading = {
                                    ProfileImage(image, Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)))
                                }
                            )
                        }
                    }
                }
            }
            item(key = "colors") {
                CustomizationEnabledSection(customizationEnabled) {
                    CustomizationActionRow(
                        title = stringResource(R.string.settings_color),
                        summary = if (appearance.useDynamicColor) stringResource(R.string.settings_dynamic_color)
                            else if (appearance.colorSchemeName == "CUSTOM") stringResource(R.string.settings_customize_palette)
                            else appearance.colorSchemeName.lowercase().replaceFirstChar { it.uppercase() },
                        tag = "customization-colors-setting",
                        onClick = { if (customizationEnabled) colorsDialog = true },
                        icon = FluentUi.Regular.Circle
                    )
                }
            }
            item(key = "input-area") {
                CustomizationEnabledSection(customizationEnabled) {
                    CustomizationActionRow(
                        title = stringResource(R.string.customization_input_title),
                        summary = "${(appearance.inputAreaTransparency * 100).toInt()}% · ${appearance.inputAreaBlur.toInt()} dp",
                        tag = "customization-input-setting",
                        onClick = { if (customizationEnabled) inputDialog = true },
                        icon = Icons.Outlined.Keyboard
                    )
                }
            }
        }
    }
    val slot = backgroundDialog
    if (customizationEnabled && slot != null && !showStorage) {
        val label = stringResource(when (slot) {
            UserProfileImageSlot.CHAT -> R.string.user_profile_background_chat
            UserProfileImageSlot.DRAWER -> R.string.user_profile_background_drawer
            UserProfileImageSlot.MENU -> R.string.user_profile_background_menu
            UserProfileImageSlot.SHEET -> R.string.user_profile_background_bottom_sheet
            UserProfileImageSlot.DIALOG -> R.string.user_profile_background_dialog
            UserProfileImageSlot.PHOTO -> R.string.user_profile_photo_title
        })
        val transparency = transparencyDrafts[slot] ?: slot.imageTransparency(profile)
        Dialog(
            onDismissRequest = { backgroundDialog = null; menuFor = null },
            title = { Text(label) },
            scrollableContent = true,
            content = {
                Column(Modifier.fillMaxWidth().testTag("background-dialog-${slot.name.lowercase()}"),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.fillMaxWidth()) {
                        ProfileBackdropPreview(
                            profile, slot, Modifier.fillMaxWidth().testTag("profile-preview-active"), transparency
                        )
                        Box(Modifier.align(Alignment.TopEnd).padding(8.dp)) {
                            FilledTonalIconButton(
                                onClick = { openMenu(slot) },
                                modifier = Modifier.testTag("background-preview-options")
                            ) {
                                SharedIcon(Icons.Default.MoreVert,
                                    stringResource(R.string.user_profile_background_options, label))
                            }
                            SlotMenu(
                                expanded = menuFor == slot,
                                hasImage = slot.get(profile) != null,
                                onDismiss = { menuFor = null },
                                onGallery = {
                                    target = slot; menuFor = null
                                    gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                                },
                                onStorage = { chooseStorage(slot) },
                                onRemove = { menuFor = null; storeImage(slot, null) }
                            )
                        }
                    }
                    Text(stringResource(R.string.user_profile_background_transparency),
                        style = MaterialTheme.typography.titleSmall)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ControlledSlider(
                            value = transparency,
                            onValueChange = { transparencyDrafts[slot] = it },
                            onValueChangeFinished = {
                                val value = transparencyDrafts[slot] ?: slot.imageTransparency(profile)
                                onChange { slot.withImageTransparency(it, value) }
                            },
                            modifier = Modifier.weight(1f)
                                .semantics { contentDescription = "$label: ${localizedResources.getString(R.string.user_profile_background_transparency)}" }
                                .testTag("profile-transparency-${slot.name.lowercase()}")
                        )
                        Text("${(transparency * 100).toInt()}%", style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.width(44.dp),
                            textAlign = androidx.compose.ui.text.style.TextAlign.End)
                        IconButton(onClick = {
                            transparencyDrafts[slot] = 0.5f
                            onChange { slot.withImageTransparency(it, 0.5f) }
                        }) {
                            SharedIcon(Icons.Default.Refresh,
                                stringResource(R.string.user_profile_background_transparency_reset))
                        }
                    }
                }
            }
        )
    }
    if (customizationEnabled && colorsDialog) {
        Dialog(onDismissRequest = { colorsDialog = false },
            title = { Text(stringResource(R.string.settings_color)) }, scrollableContent = true,
            content = {
                CustomizationColors(
                    appearance = appearance,
                    onDynamicColorChange = onDynamicColorChange,
                    onColorSchemeChange = onColorSchemeChange,
                    onCustomPaletteChange = onCustomPaletteChange,
                    onItemTransparencyChange = onItemTransparencyChange
                )
            }
        )
    }
    if (customizationEnabled && inputDialog) {
        Dialog(onDismissRequest = { inputDialog = false },
            title = { Text(stringResource(R.string.customization_input_title)) },
            scrollableContent = true,
            content = { InputAreaSettings(appearance, onInputAreaTransparencyChange, onInputAreaBlurChange) }
        )
    }
    if ((customizationEnabled || target == UserProfileImageSlot.PHOTO) && showStorage) MediaStoragePhotoSheet(
        onDismiss = { showStorage = false },
        onSelected = { file ->
            val slot = target ?: return@MediaStoragePhotoSheet
            showStorage = false
            scope.launch {
                busy = true
                try { storeImage(slot, UserProfileImages.importStorage(context, file)) }
                catch (e: Exception) { error = e.localizedMessage }
                finally { busy = false }
            }
        }
    )
    if (busy) Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.3f)),
        contentAlignment = Alignment.Center) { CircularProgressIndicator() }
    error?.let { message -> AlertDialog(
        onDismissRequest = { error = null }, title = { Text(stringResource(R.string.user_profile_image_error)) },
        text = { Text(message) },
        confirmButton = { TextButton(onClick = { error = null }) { Text(stringResource(R.string.common_ok)) } }
    ) }
}

@Composable
private fun ProfileBackdropPreview(
    profile: UserProfile,
    slot: UserProfileImageSlot,
    modifier: Modifier = Modifier,
    transparency: Float = slot.imageTransparency(profile)
) {
    Surface(
        modifier.height(170.dp).semantics { stateDescription = "${(transparency * 100).toInt()}%" },
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            ProfileBackground(slot.get(profile), Modifier.matchParentSize(), transparency)
            if (slot.get(profile) == null) SharedIcon(Icons.Outlined.Image, null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun CustomizationEnabledSection(
    enabled: Boolean,
    showDisabledTag: Boolean = false,
    content: @Composable () -> Unit
) {
    Box(Modifier.fillMaxWidth().alpha(if (enabled) 1f else 0.4f)) {
        content()
        if (!enabled) {
            Box(Modifier.matchParentSize()
                .then(if (showDisabledTag) Modifier.testTag("customization-disabled-overlay") else Modifier)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {})
        }
    }
}

@Composable
private fun CustomizationActionRow(
    title: String,
    summary: String,
    tag: String,
    onClick: () -> Unit,
    leading: (@Composable () -> Unit)? = null,
    icon: ImageVector? = null
) {
    Surface(onClick = onClick, shape = RoundedCornerShape(18.dp),
        color = prominentContainerColor(),
        modifier = Modifier.fillMaxWidth().testTag(tag)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically) {
            if (leading != null) leading()
            else if (icon != null) {
                Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .testTag("$tag-icon"), contentAlignment = Alignment.Center) {
                    SharedIcon(icon, null, Modifier.size(24.dp),
                        tint = MaterialTheme.colorScheme.primary)
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                Text(summary, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun SlotMenu(expanded: Boolean, hasImage: Boolean, onDismiss: () -> Unit,
                     onGallery: () -> Unit, onStorage: () -> Unit, onRemove: () -> Unit) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(text = { Text(stringResource(R.string.user_profile_photos_gallery)) }, onClick = onGallery,
            leadingIcon = { SharedIcon(Icons.Outlined.Image, null) })
        DropdownMenuItem(text = { Text(stringResource(R.string.settings_storage)) }, onClick = onStorage,
            leadingIcon = { SharedIcon(Icons.Outlined.Folder, null) })
        DropdownMenuItem(text = { Text(stringResource(R.string.common_remove)) }, enabled = hasImage, onClick = onRemove,
            leadingIcon = { SharedIcon(Icons.Outlined.Delete, null) })
    }
}

@Composable
private fun ProfileImage(image: String?, modifier: Modifier, fallback: Boolean = false) {
    if (image == null) {
        if (fallback) FeaturesLogo(null, modifier)
        else Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
            SharedIcon(Icons.Outlined.Image, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    } else {
        val context = LocalContext.current
        val model = remember(image) { IconStore(context).imageData(image) ?: AppImages.source(image) }
        AsyncImage(model = model, contentDescription = null, modifier = modifier,
            contentScale = ContentScale.Crop)
    }
}
