package org.starfall.generative.features.customization.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.starfall.generative.R
import org.starfall.generative.shared.files.ToolFiles
import org.starfall.generative.shared.ui.media.mediaMimeType
import org.starfall.generative.shared.ui.sheets.ContentSheet

/** Pick an image from the app's Media Storage without filtering or extra sheet controls. */
@Composable
internal fun MediaStoragePhotoSheet(onDismiss: () -> Unit, onSelected: (File) -> Unit) {
    val context = LocalContext.current
    var photos by remember { mutableStateOf<List<File>>(emptyList()) }
    LaunchedEffect(context) {
        photos = withContext(Dispatchers.IO) {
            ToolFiles(context).list().filter { mediaMimeType(it.name).startsWith("image/") }
        }
    }
    ContentSheet(onDismissRequest = onDismiss, fixedHeightFraction = 0.82f) {
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            if (photos.isEmpty()) {
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.user_profile_no_photos), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(photos, key = { it.absolutePath }) { file ->
                        AsyncImage(
                            model = file,
                            contentDescription = file.name,
                            modifier = Modifier.fillMaxWidth().aspectRatio(1f)
                                .clip(RoundedCornerShape(8.dp)).clickable { onSelected(file) },
                            contentScale = ContentScale.Crop
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}
