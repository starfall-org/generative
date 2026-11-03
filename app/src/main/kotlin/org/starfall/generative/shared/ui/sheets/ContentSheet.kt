package org.starfall.generative.shared.ui.sheets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Shared template for sheets whose body is owned by a feature rather than by a list or menu.
 *
 * The header is deliberately optional. A sheet with no title or actions starts its content
 * immediately below the drag handle, so callers never need to reserve an empty header row.
 */
@Composable
fun ContentSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    title: (@Composable () -> Unit)? = null,
    headerActions: (@Composable RowScope.() -> Unit)? = null,
    contentBehindNavigationBar: Boolean = false,
    fixedHeightFraction: Float? = null,
    expandOnOpen: Boolean = false,
    content: @Composable ColumnScope.() -> Unit
) {
    BottomSheet(
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        contentBehindNavigationBar = contentBehindNavigationBar,
        fixedHeightFraction = fixedHeightFraction,
        expandOnOpen = expandOnOpen
    ) {
        if (title != null || headerActions != null) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                title?.let {
                    androidx.compose.foundation.layout.Box(
                        Modifier.weight(1f),
                        contentAlignment = Alignment.CenterStart
                    ) { it() }
                }
                if (title == null) Spacer(Modifier.weight(1f))
                headerActions?.invoke(this)
            }
        }
        content()
    }
}

/** Convenient title slot for the common text-only sheet header. */
@Composable
fun ContentSheetTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier,
        style = MaterialTheme.typography.titleLarge,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
}
