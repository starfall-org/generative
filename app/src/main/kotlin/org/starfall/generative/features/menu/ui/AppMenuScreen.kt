package org.starfall.generative.features.menu.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import org.starfall.generative.shared.ui.icons.SharedIcon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.util.concurrent.CancellationException
import org.starfall.generative.R
import org.starfall.generative.shared.icons.IconStore
import org.starfall.generative.shared.ui.FeaturesLogo
import org.starfall.generative.shared.ui.layout.ComponentGroup
import org.starfall.generative.shared.ui.layout.ComponentGroupSlot
import org.starfall.generative.shared.ui.layout.componentGroupItemShape
import org.starfall.generative.shared.ui.layout.appearanceContainerColor
import org.starfall.generative.shared.ui.layout.appearanceContainerBorder
import org.starfall.generative.shared.ui.layout.prominentContainerColor
import org.starfall.generative.shared.ui.layout.prominentContainerBorder
import org.starfall.generative.shared.ui.icons.FluentUi
import org.starfall.generative.shared.ui.icons.Icons

// Compatibility aliases retained for the existing AppMenu shape tests and callers.
internal val GroupInnerCorner = 6.dp
internal val GroupTopShape get() = componentGroupItemShape(0, 2)
internal val GroupMiddleShape = RoundedCornerShape(GroupInnerCorner)
internal val GroupSingleShape get() = componentGroupItemShape(0, 1)
internal val GroupBottomShape get() = componentGroupItemShape(1, 2)

/** App menu screen. Deliberately has no page title; the themed app logo is its header. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AppMenuScreen(
    modifier: Modifier = Modifier,
    logoImage: String? = null,
    onLogoImageChange: (String?) -> Unit = {},
    onNavigateToCustomization: () -> Unit = {},
    onNavigateToChatOptions: () -> Unit = {},
    onNavigateToToolsEditor: () -> Unit = {},
    onNavigateToProviders: () -> Unit = {},
    onNavigateToMcp: () -> Unit = {},
    onNavigateToSpeech: () -> Unit = {},
    onNavigateToRemoteMachines: () -> Unit = {},
    onNavigateToStorage: () -> Unit = {},
    onNavigateToAppSettings: () -> Unit = {},
    onNavigateToIcons: () -> Unit = {},
    onNavigateToAppData: () -> Unit = {},
    onNavigateToAbout: () -> Unit = {},
    onNavigateToChat: () -> Unit = {}
) {
    val safeDrawing = WindowInsets.safeDrawing.asPaddingValues()
    val layoutDirection = LocalLayoutDirection.current
    val currentNavigateToChat by rememberUpdatedState(onNavigateToChat)

    // Background is owned once by AppNavigation for every destination in the Menu
    // family. Only this screen's content participates in nested navigation slides.
    Box(
        modifier.fillMaxSize().testTag("app-menu-root").pointerInput(Unit) {
            val backSwipeDistance = 72.dp.toPx()
            var horizontalDrag = 0f
            detectHorizontalDragGestures(
                onDragStart = { horizontalDrag = 0f },
                onHorizontalDrag = { change, amount ->
                    horizontalDrag += amount
                    change.consume()
                },
                onDragEnd = {
                    if (horizontalDrag >= backSwipeDistance) currentNavigateToChat()
                    horizontalDrag = 0f
                },
                onDragCancel = { horizontalDrag = 0f }
            )
        }
    ) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        // Draw the list itself behind transparent system bars, but keep interactive content inside
        // the safe area. This is the same edge-to-edge arrangement used by ChatScreen.
        contentPadding = PaddingValues(
            start = 16.dp + if (layoutDirection == androidx.compose.ui.unit.LayoutDirection.Ltr) {
                safeDrawing.calculateLeftPadding(layoutDirection)
            } else {
                safeDrawing.calculateRightPadding(layoutDirection)
            },
            top = 16.dp + safeDrawing.calculateTopPadding(),
            end = 16.dp + if (layoutDirection == androidx.compose.ui.unit.LayoutDirection.Ltr) {
                safeDrawing.calculateRightPadding(layoutDirection)
            } else {
                safeDrawing.calculateLeftPadding(layoutDirection)
            },
            bottom = 24.dp + safeDrawing.calculateBottomPadding()
        ),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        item(key = "app-logo") {
            Box(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier.size(84.dp).clip(CircleShape)
                        .combinedClickable(
                            onClick = onNavigateToCustomization,
                            onLongClick = onNavigateToCustomization
                        )
                        .testTag("app-menu-customization-logo"),
                    contentAlignment = Alignment.Center
                ) {
                    FeaturesLogo(logoImage, Modifier.size(84.dp))
                }
            }
        }
        item(key = "configuration-group") {
            ComponentGroup(
                modifier = Modifier.testTag("app-menu-group-configuration"),
                items = listOf(
                    ComponentGroupSlot("chat-options") { modifier, shape ->
                        FeatureButton(stringResource(R.string.chat_configuration), FluentUi.Regular.Chat,
                            onNavigateToChatOptions, modifier, shape, "chat-options")
                    },
                    ComponentGroupSlot("providers") { modifier, shape ->
                        FeatureButton(stringResource(R.string.model_providers), FluentUi.Regular.Cloud,
                            onNavigateToProviders, modifier, shape, "providers")
                    },
                    ComponentGroupSlot("speech") { modifier, shape ->
                        FeatureButton(stringResource(R.string.speech_synthesis_services), FluentUi.Regular.PersonVoice,
                            onNavigateToSpeech, modifier, shape, "speech")
                    }
                )
            )
        }
        item(key = "tools-group") {
            ComponentGroup(
                modifier = Modifier.testTag("app-menu-group-tools"),
                items = listOf(
                    ComponentGroupSlot("feature-tools") { modifier, shape ->
                        FeatureButton(stringResource(R.string.built_in_tools), FluentUi.Regular.WrenchScrewdriver,
                            onNavigateToToolsEditor, modifier, shape, "feature-tools")
                    },
                    ComponentGroupSlot("mcp") { modifier, shape ->
                        FeatureButton(stringResource(R.string.mcp), FluentUi.Regular.PuzzlePiece,
                            onNavigateToMcp, modifier, shape, "mcp")
                    }
                )
            )
        }
        item(key = "remote-group") {
            ComponentGroup(
                modifier = Modifier.testTag("app-menu-group-remote"),
                items = listOf(ComponentGroupSlot("remote") { modifier, shape ->
                    FeatureButton(stringResource(R.string.remote_ssh), FluentUi.Regular.Desktop,
                        onNavigateToRemoteMachines, modifier, shape, "remote")
                })
            )
        }
        item(key = "media-group") {
            ComponentGroup(
                modifier = Modifier.testTag("app-menu-group-media"),
                items = listOf(
                    ComponentGroupSlot("media") { modifier, shape ->
                        FeatureButton(stringResource(R.string.media_management), FluentUi.Regular.FolderOpen,
                            onNavigateToStorage, modifier, shape, "media")
                    },
                    ComponentGroupSlot("icons") { modifier, shape ->
                        FeatureButton(stringResource(R.string.icons_management), FluentUi.Regular.Image,
                            onNavigateToIcons, modifier, shape, "icons")
                    }
                )
            )
        }
        item(key = "settings-group") {
            ComponentGroup(
                modifier = Modifier.testTag("app-menu-group-settings"),
                items = listOf(ComponentGroupSlot("settings") { modifier, shape ->
                    FeatureButton(stringResource(R.string.app_menu_settings), FluentUi.Regular.Settings,
                        onNavigateToAppSettings, modifier, shape, "settings")
                })
            )
        }
        item(key = "data-group") {
            ComponentGroup(
                modifier = Modifier.testTag("app-menu-group-data"),
                items = listOf(
                    ComponentGroupSlot("data") { modifier, shape ->
                        FeatureButton(stringResource(R.string.app_menu_data), FluentUi.Regular.History,
                            onNavigateToAppData, modifier, shape, "data")
                    },
                    ComponentGroupSlot("about") { modifier, shape ->
                        FeatureButton(stringResource(R.string.app_menu_about), FluentUi.Regular.Info,
                            onNavigateToAbout, modifier, shape, "about")
                    }
                )
            )
        }
    }
    IconButton(
        onClick = onNavigateToChat,
        modifier = Modifier.align(Alignment.TopStart)
            .padding(
                start = 16.dp + safeDrawing.calculateStartPadding(layoutDirection),
                top = 16.dp + safeDrawing.calculateTopPadding()
            )
            .size(48.dp)
            .testTag("app-menu-back")
    ) {
        SharedIcon(
            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = stringResource(R.string.common_back),
            modifier = Modifier.size(24.dp)
        )
    }
    }
}

@Composable
private fun FeatureButton(
    title: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier,
    shape: Shape,
    tag: String
) {
    Surface(
        onClick = onClick,
        modifier = modifier.testTag("app-menu-$tag"),
        shape = shape,
        color = prominentContainerColor(),
        border = prominentContainerBorder(1.5.dp)
    ) {
        Row(
            modifier = Modifier.padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = appearanceContainerColor(MaterialTheme.colorScheme.primaryContainer),
                border = appearanceContainerBorder(),
                modifier = Modifier.size(46.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    SharedIcon(imageVector = icon, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(24.dp))
                }
            }
            Text(
                title,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}
