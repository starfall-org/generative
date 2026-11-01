package org.starfall.generative.shared.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import coil3.compose.AsyncImage
import org.starfall.generative.R
import org.starfall.generative.shared.icons.IconStore
import org.starfall.generative.shared.theme.LocalDarkMode

@Composable
fun FeaturesLogo(
    image: String?,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val revision by IconStore.revision.collectAsState()
    val data by produceState<Any?>(initialValue = null, image, revision, context) {
        value = withContext(Dispatchers.IO) {
            image?.let { IconStore(context).imageData(it, dark = false)
                ?: java.io.File(it).takeIf { file -> file.isFile } }
        }
    }
    val dark = LocalDarkMode.current
    Box(
        modifier = modifier.background(if (dark) Color(0xFF424242) else Color.Transparent)
    ) {
        if (data != null) {
            AsyncImage(
                model = data,
                contentDescription = stringResource(R.string.app_name),
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Image(
                painter = painterResource(R.drawable.generative_logo_light),
                contentDescription = stringResource(R.string.app_name),
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}
