package org.starfall.generative.shared.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.RowScope
import org.starfall.generative.R
import org.starfall.generative.shared.ui.icons.Icons
import org.starfall.generative.shared.ui.icons.SharedIcon
import org.starfall.generative.shared.ui.layout.edgeToEdgeTopAppBarColors
import org.starfall.generative.shared.ui.layout.scrollableScaffoldPadding

/** Prevents a screen from accidentally mixing its typed content with another content kind. */
@DslMarker
annotation class ScreenTemplateDsl

/**
 * The root-screen marker. Chat owns its app bar and content, so this deliberately adds no
 * layout, navigation, or content contract of its own.
 */
@Composable
fun MainScreenTemplate(content: @Composable () -> Unit) {
    content()
}

/**
 * A secondary screen shell with one typed content area.
 *
 * The content functions are methods on [ScreenTemplateScope] rather than top-level composables.
 * This keeps the four screen content kinds meaningful and makes it impossible to put one inside
 * [MainScreenTemplate] or use one as an independent screen wrapper.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScreenTemplate(
    title: @Composable () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable ScreenTemplateScope.() -> Unit
) {
    BackHandler(enabled = LocalScreenTransitionActive.current, onBack = onBack)
    Scaffold(
        modifier = modifier.testTag("screen-template"),
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                colors = edgeToEdgeTopAppBarColors(),
                title = title,
                navigationIcon = {
                    androidx.compose.material3.IconButton(onClick = onBack) {
                        SharedIcon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = androidx.compose.ui.res.stringResource(R.string.common_back)
                        )
                    }
                },
                actions = actions
            )
        }
    ) { scaffoldPadding ->
        val scope = ScreenTemplateScope(scaffoldPadding)
        Box(Modifier.fillMaxSize().testTag("screen-template-content")) {
            scope.content()
            scope.requireExactlyOneContent()
        }
    }
}

/** String-title convenience overload for ordinary screens. */
@Composable
fun ScreenTemplate(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable ScreenTemplateScope.() -> Unit
) = ScreenTemplate(
    title = { Text(title) },
    onBack = onBack,
    modifier = modifier,
    actions = actions,
    content = content
)

/** Scope available only while defining the one content area of a [ScreenTemplate]. */
@ScreenTemplateDsl
class ScreenTemplateScope internal constructor(
    /** The padding reserved for the screen app bar and system-safe content area. */
    val scaffoldPadding: PaddingValues
) {
    private var contentKind: ContentKind? = null

    private fun claim(kind: ContentKind) {
        check(contentKind == null) {
            "ScreenTemplate requires exactly one content type; found ${contentKind!!.label} and ${kind.label}."
        }
        contentKind = kind
    }

    internal fun requireExactlyOneContent() {
        check(contentKind != null) {
            "ScreenTemplate requires exactly one of ContentPreview, ContentVisualEditor, " +
                "ContentTextEditor, or ContentList."
        }
    }

    /** Read-only, vertically scrollable content. */
    @Composable
    fun ContentPreview(
        modifier: Modifier = Modifier,
        contentPadding: PaddingValues? = null,
        content: @Composable ColumnScope.() -> Unit
    ) {
        claim(ContentKind.PREVIEW)
        val resolvedPadding = contentPadding ?: defaultContentPadding()
        Column(
            modifier = Modifier
                .fillMaxSize()
                .then(modifier)
                .verticalScroll(rememberScrollState())
                .imePadding()
                .navigationBarsPadding()
                .padding(resolvedPadding),
            content = content
        )
    }

    /** IME-safe form content; feature-owned forms choose a Column or lazy layout for scrolling. */
    @Composable
    fun ContentVisualEditor(
        modifier: Modifier = Modifier,
        contentPadding: PaddingValues? = null,
        content: @Composable BoxScope.(PaddingValues) -> Unit
    ) {
        claim(ContentKind.VISUAL_EDITOR)
        val resolvedPadding = contentPadding ?: defaultContentPadding()
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(modifier)
                .imePadding()
                .navigationBarsPadding()
        ) {
            content(resolvedPadding)
        }
    }

    /** Full-content editor area with IME and navigation-bar handling. */
    @Composable
    fun ContentTextEditor(
        modifier: Modifier = Modifier,
        contentPadding: PaddingValues? = null,
        content: @Composable BoxScope.() -> Unit
    ) {
        claim(ContentKind.TEXT_EDITOR)
        val resolvedPadding = contentPadding ?: defaultContentPadding()
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(modifier)
                .imePadding()
                .navigationBarsPadding()
                .padding(resolvedPadding),
            content = content
        )
    }

    /** Collection content with centralized safe-area padding and optional state placeholders. */
    @Composable
    fun ContentList(
        modifier: Modifier = Modifier,
        contentPadding: PaddingValues? = null,
        isLoading: Boolean = false,
        isEmpty: Boolean = false,
        loadingContent: @Composable BoxScope.() -> Unit = {},
        emptyContent: @Composable BoxScope.() -> Unit = {},
        content: @Composable BoxScope.(PaddingValues) -> Unit
    ) {
        claim(ContentKind.LIST)
        val resolvedPadding = contentPadding ?: defaultContentPadding()
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(modifier)
                .imePadding()
                .navigationBarsPadding()
        ) {
            when {
                isLoading -> loadingContent()
                isEmpty -> emptyContent()
                else -> content(resolvedPadding)
            }
        }
    }

    @Composable
    private fun defaultContentPadding(): PaddingValues = scrollableScaffoldPadding(
        scaffoldPadding,
        horizontal = 16.dp,
        top = 8.dp,
        bottom = 16.dp
    )

    private enum class ContentKind(val label: String) {
        PREVIEW("ContentPreview"),
        VISUAL_EDITOR("ContentVisualEditor"),
        TEXT_EDITOR("ContentTextEditor"),
        LIST("ContentList")
    }
}
