package org.starfall.generative.features.about.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.pm.PackageInfoCompat
import org.starfall.generative.R
import org.starfall.generative.features.about.logic.GitHubReleaseInfo
import org.starfall.generative.features.about.logic.compareReleaseVersions
import org.starfall.generative.features.about.logic.fetchLatestGitHubRelease
import org.starfall.generative.shared.ui.icons.Icons
import org.starfall.generative.shared.ui.icons.SharedIcon
import org.starfall.generative.shared.ui.LoadingIndicator
import org.starfall.generative.shared.ui.layout.SettingsCard
import org.starfall.generative.shared.ui.layout.prominentContainerBorder
import org.starfall.generative.shared.ui.layout.prominentContainerColor

@Composable
fun AboutScreen(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
    scrollable: Boolean = true
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()
    val packageInfo = remember(context) { context.packageManager.getPackageInfo(context.packageName, 0) }
    val versionName = packageInfo.versionName.orEmpty()
    val versionCode = PackageInfoCompat.getLongVersionCode(packageInfo)

    Column(
        modifier = modifier.fillMaxSize().then(if (scrollable) Modifier.verticalScroll(scrollState) else Modifier)
            .padding(contentPadding).padding(horizontal = 16.dp, vertical = 8.dp).padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        AboutCard {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(60.dp)) {
                    Box(contentAlignment = Alignment.Center) {
                        Text("◇", color = MaterialTheme.colorScheme.onPrimaryContainer, style = MaterialTheme.typography.headlineMedium)
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                    Text(stringResource(R.string.settings_version, versionName, versionCode), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        UpdateSection()
        AboutNavigationItem(stringResource(R.string.settings_website), "starfall-org.github.io/generative", Icons.Outlined.Language) {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://starfall-org.github.io/generative/")))
        }
        AboutNavigationItem(stringResource(R.string.settings_source_code), "starfall-org/generative", Icons.Outlined.Code) {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/starfall-org/generative")))
        }
        AboutCard {
            Text(stringResource(R.string.settings_license), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
            Text(stringResource(R.string.settings_license_name), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun UpdateSection() {
    val context = LocalContext.current
    val localizedResources = LocalResources.current
    val currentVersion = remember(context) { context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty() }
    var refreshKey by remember { mutableIntStateOf(0) }
    var isChecking by remember { mutableStateOf(false) }
    var hasChecked by remember { mutableStateOf(false) }
    var latestRelease by remember { mutableStateOf<GitHubReleaseInfo?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(refreshKey) {
        if (refreshKey == 0) return@LaunchedEffect
        isChecking = true
        error = null
        runCatching { fetchLatestGitHubRelease() }
            .onSuccess {
                latestRelease = it
                hasChecked = true
            }
            .onFailure { error = it.localizedMessage ?: localizedResources.getString(R.string.settings_update_error) }
        isChecking = false
    }

    AboutCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.settings_software_update), modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
            androidx.compose.material3.IconButton(onClick = { refreshKey++ }, enabled = !isChecking) {
                Text("⟳", style = MaterialTheme.typography.titleLarge)
            }
        }
        val latest = latestRelease
        when {
            isChecking -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                LoadingIndicator(modifier = Modifier.size(18.dp))
                Text(stringResource(R.string.settings_checking_releases))
            }
            error != null -> {
                Text(error.orEmpty(), color = MaterialTheme.colorScheme.error)
                TextButton(onClick = { refreshKey++ }) { Text(stringResource(R.string.settings_try_again)) }
            }
            latest != null && compareReleaseVersions(currentVersion, latest.tagName) < 0 -> ReleaseEntry(latest)
            hasChecked -> Text(stringResource(R.string.settings_up_to_date, currentVersion),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            else -> {
                Text(stringResource(R.string.settings_find_latest_release), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FilledTonalButton(onClick = { refreshKey++ }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) { Text(stringResource(R.string.settings_check_for_updates)) }
            }
        }
    }
}

@Composable
private fun ReleaseEntry(latest: GitHubReleaseInfo) {
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(latest.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(stringResource(R.string.settings_update_available, latest.tagName), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (latest.body.isNotBlank()) {
            Box(
                modifier = Modifier.fillMaxWidth()
                    .heightIn(max = 240.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(latest.body, style = MaterialTheme.typography.bodySmall)
            }
        }
        if (latest.apkAssets.isNotEmpty()) {
            latest.apkAssets.forEach { asset ->
                OutlinedButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(asset.downloadUrl))) }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
                    Text("↓")
                    Spacer(Modifier.size(8.dp))
                    Text(asset.name, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        } else {
            Button(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(latest.pageUrl))) }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
                Text("↗")
                Spacer(Modifier.size(8.dp))
                Text(stringResource(R.string.settings_open_github_release))
            }
        }
    }
}

@Composable
private fun AboutCard(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    SettingsCard(content = content)
}

@Composable
private fun AboutNavigationItem(title: String, subtitle: String, icon: ImageVector, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = prominentContainerColor(),
        border = prominentContainerBorder()
    ) {
        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            SharedIcon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("↗", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
