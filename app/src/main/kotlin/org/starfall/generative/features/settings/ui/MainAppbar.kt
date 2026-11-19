package org.starfall.generative.features.settings.ui

import org.starfall.generative.shared.ui.layout.fadingAppBarSurface
import org.starfall.generative.shared.ui.layout.edgeToEdgeTopAppBarColors
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import org.starfall.generative.R
import org.starfall.generative.shared.ui.icons.SharedIcon
import org.starfall.generative.shared.ui.icons.Icons

/** Shared top app bar for the settings feature screens (from the former SettingsScreen Scaffold). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainAppbar(title: String, onBack: () -> Unit) {
    TopAppBar(
        modifier = fadingAppBarSurface(),
        colors = edgeToEdgeTopAppBarColors(),
        title = {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold)
            )
        },
        navigationIcon = {
            IconButton(onClick = onBack) {
                SharedIcon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
            }
        }
    )
}
