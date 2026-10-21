package org.starfall.generative.shared.ui.icons

import coil3.compose.AsyncImage
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.starfall.generative.R
import org.starfall.generative.shared.icons.IconStore
import org.starfall.generative.shared.ui.dialogs.FaviconDialog

internal fun providerInitials(name: String): String = name.trim()
    .split(Regex("\\s+"))
    .filter { it.isNotBlank() }
    .take(2)
    .mapNotNull { it.firstOrNull()?.uppercaseChar()?.toString() }
    .joinToString("")
    .ifBlank { "?" }

@Composable
fun EntityIcon(
    image: String?,
    modifier: Modifier = Modifier.size(42.dp),
    text: String? = null,
    fallback: Any? = null,
    matchName: String? = null,
    /** Tried only if [matchName] finds no logo (e.g. the model id behind a display name). */
    fallbackMatchName: String? = null,
    model: Boolean = false,
    useDarkVariant: Boolean? = null,
    backgroundColor: Color? = null,
    /** Drawn instead of [fallback]/[text] once the lookup finished without finding an image. */
    fallbackContent: (@Composable BoxScope.() -> Unit)? = null
) {
    val context = LocalContext.current
    val revision by IconStore.lookupRevision.collectAsState()
    val dark = useDarkVariant ?: (MaterialTheme.colorScheme.background.luminance() < 0.5f)
    val store = remember(context) { IconStore(context) }
    // Seed from the in-memory cache so a screen that is re-entered paints its logos on the first
    // frame instead of flashing the fallback while the lookup runs again.
    val cached = remember(context, image, matchName, fallbackMatchName, model, dark) { store.peekIcon(image, matchName, model, dark, fallbackMatchName = fallbackMatchName) }
    var data by remember(context, image, matchName, fallbackMatchName, model, dark) { mutableStateOf(cached?.data) }
    var resolved by remember(context, image, matchName, fallbackMatchName, model, dark) { mutableStateOf(cached != null) }
    var explicitFailed by remember(image, matchName, fallbackMatchName, dark, revision) { mutableStateOf(false) }
    LaunchedEffect(image, matchName, fallbackMatchName, model, revision, context, dark, explicitFailed) {
        val hit = store.peekIcon(image, matchName, model, dark, explicitFailed, fallbackMatchName)
        data = if (hit != null) hit.data else withContext(Dispatchers.IO) {
            store.lookupIcon(image, matchName, model, dark, explicitFailed, fallbackMatchName)
        }
        resolved = true
    }
    Surface(modifier = modifier, shape = RoundedCornerShape(12.dp),
        color = backgroundColor ?: MaterialTheme.colorScheme.surfaceContainerHighest) {
        Box(contentAlignment = Alignment.Center) {
            if (data == null) {
                if (fallbackContent != null) {
                    // Wait for the first lookup so a folder logo never flashes the substitute first.
                    if (resolved) fallbackContent()
                } else when (val fallbackIcon = fallback) {
                    is ImageVector -> FluentIcon(
                        imageVector = fallbackIcon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.fillMaxSize().padding(9.dp)
                    )
                    is Int -> FluentIcon(
                        imageVector = fallbackIcon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.fillMaxSize().padding(9.dp)
                    )
                    else -> Text(
                        text?.takeIf { it.isNotBlank() } ?: "?",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            } else AsyncImage(data, contentDescription = null,
                modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop,
                onError = { if (image != null && !explicitFailed) explicitFailed = true })
        }
    }
}

@Composable
fun IconPickerRow(
    image: String?,
    onChange: (String?) -> Unit,
    text: String? = null,
    fallback: Any? = null,
    onBusyChange: (Boolean) -> Unit = {},
    matchName: String? = null,
    fallbackMatchName: String? = null,
    model: Boolean = false,
    faviconBaseUrl: String = "",
    defaultImage: String? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember(context) { IconStore(context) }
    val revision by IconStore.lookupRevision.collectAsState()
    var showDialog by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val currentBusy by rememberUpdatedState(onBusyChange)
    val currentChange by rememberUpdatedState(onChange)
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            importing = true
            currentBusy(true)
            error = null
            try {
                currentChange(withContext(Dispatchers.IO) { store.importImage(uri, shared = false) })
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error = failure.message ?: context.getString(R.string.icon_import_failed)
            } finally {
                importing = false
                currentBusy(false)
            }
        }
    }
    DisposableEffect(Unit) { onDispose { currentBusy(false) } }
    if (showDialog) FaviconDialog(
        baseUrl = faviconBaseUrl,
        onSave = { onChange(it) },
        onBusyChange = { importing = it; onBusyChange(it) },
        onDismiss = { showDialog = false }
    )
    val fileName = remember(image, revision) { store.fileName(image) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        // Tapping the logo uploads an image from the device.
        Box(Modifier.size(56.dp).clickable(enabled = !importing) {
            imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }) {
            EntityIcon(
                image = image ?: defaultImage,
                modifier = Modifier.fillMaxSize(),
                text = text,
                fallback = fallback,
                matchName = matchName,
                fallbackMatchName = fallbackMatchName,
                model = model
            )
            Surface(
                modifier = Modifier.align(Alignment.BottomEnd).padding(2.dp),
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = MaterialTheme.colorScheme.onSurface
            ) {
                FluentIcon(
                    Icons.Outlined.AddPhotoAlternate,
                    contentDescription = context.getString(R.string.entity_icon),
                    modifier = Modifier.padding(3.dp).size(14.dp)
                )
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            OutlinedButton(enabled = !importing, onClick = { showDialog = true }) {
                Text(context.getString(R.string.favicon_get_logo))
            }
            if (image != null && fileName != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        fileName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
        if (image != null) {
            IconButton(enabled = !importing, onClick = { error = null; onChange(null) }) {
                FluentIcon(Icons.Outlined.Close, contentDescription = context.getString(R.string.common_remove))
            }
        }
    }
}
