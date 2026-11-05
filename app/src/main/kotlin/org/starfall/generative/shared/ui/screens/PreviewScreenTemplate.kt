package org.starfall.generative.shared.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.starfall.generative.R
import org.starfall.generative.shared.ui.icons.Icons
import org.starfall.generative.shared.ui.icons.SharedIcon
import org.starfall.generative.shared.ui.layout.edgeToEdgeTopAppBarColors
import org.starfall.generative.shared.ui.layout.scrollableScaffoldPadding

/** Read-only secondary screen with a real AppBar and a scrollable, inset-safe viewport. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PreviewScreenTemplate(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    scrollable: Boolean = true,
    horizontalPadding: Dp = 16.dp,
    topPadding: Dp = 8.dp,
    bottomPadding: Dp = 16.dp,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit
) {
    BackHandler(enabled = LocalScreenTransitionActive.current, onBack = onBack)
    Scaffold(
        modifier = modifier.testTag("preview-screen-template"),
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                colors = edgeToEdgeTopAppBarColors(),
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        SharedIcon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.common_back))
                    }
                },
                actions = actions
            )
        }
    ) { scaffoldPadding ->
        val scrollState = rememberScrollState()
        Column(
            modifier = Modifier.fillMaxSize()
                .then(if (scrollable) Modifier.verticalScroll(scrollState) else Modifier)
                .imePadding().navigationBarsPadding()
                .padding(scrollableScaffoldPadding(
                    scaffoldPadding, horizontal = horizontalPadding,
                    top = topPadding, bottom = bottomPadding
                )).testTag("preview-screen-content"),
            content = content
        )
    }
}
