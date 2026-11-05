package org.starfall.generative.shared.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import org.starfall.generative.R
import org.starfall.generative.shared.ui.icons.Icons
import org.starfall.generative.shared.ui.icons.SharedIcon
import org.starfall.generative.shared.ui.layout.edgeToEdgeTopAppBarColors

/** Full editor/settings screen: navigation, save/actions, IME-safe body and scaffold insets. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreenTemplate(
    title: @Composable () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    handleSystemBack: Boolean = true,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable BoxScope.(PaddingValues) -> Unit
) {
    BackHandler(enabled = handleSystemBack && LocalScreenTransitionActive.current, onBack = onBack)
    Scaffold(
        modifier = modifier.testTag("editor-screen-template"),
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                colors = edgeToEdgeTopAppBarColors(),
                title = title,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        SharedIcon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.common_back))
                    }
                },
                actions = actions
            )
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().imePadding().navigationBarsPadding().testTag("editor-screen-content")) {
            content(padding)
        }
    }
}

@Composable
fun EditorScreenTemplate(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    handleSystemBack: Boolean = true,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable BoxScope.(PaddingValues) -> Unit
) = EditorScreenTemplate(
    title = { Text(title) }, onBack = onBack, modifier = modifier,
    handleSystemBack = handleSystemBack, actions = actions, content = content
)
