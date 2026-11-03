package org.starfall.generative.shared.ui.inputs

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import org.starfall.generative.R
import org.starfall.generative.shared.ui.icons.Icons
import org.starfall.generative.shared.ui.icons.SharedIcon
import org.starfall.generative.shared.ui.text.OutlinedTextField

/**
 * Search control that owns its collapsed button, editor focus, IME actions and width transition.
 * The query remains controlled by the caller so filtering and data loading stay feature-owned.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ExpandableSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    onSearch: (() -> Unit)? = null,
    onClear: (() -> Unit)? = null,
    clearOnCollapse: Boolean = false,
    collapseOnFocusLoss: Boolean = true,
    collapseOnImeHidden: Boolean = true,
    capitalization: KeyboardCapitalization = KeyboardCapitalization.Sentences,
    onBoundsChange: (Rect?) -> Unit = {},
    testTag: String = "expandable-search",
    buttonTestTag: String = "$testTag-button"
) {
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val imeVisible = WindowInsets.isImeVisible
    var wasFocused by remember { mutableStateOf(false) }
    var imeWasVisible by remember { mutableStateOf(false) }

    fun collapse() {
        imeWasVisible = false
        focusManager.clearFocus(force = true)
        keyboard?.hide()
        if (clearOnCollapse && value.isNotEmpty()) onValueChange("")
        onExpandedChange(false)
    }

    LaunchedEffect(expanded) {
        if (expanded) focusRequester.requestFocus()
        else { imeWasVisible = false; onBoundsChange(null) }
    }
    LaunchedEffect(imeVisible) {
        if (imeVisible) imeWasVisible = true
        else if (collapseOnImeHidden && imeWasVisible && expanded) {
            imeWasVisible = false
            collapse()
        }
    }

    BoxWithConstraints(modifier.clipToBounds().testTag("$testTag-container")) {
        val targetWidth = if (expanded) maxWidth else 40.dp
        val searchWidth by animateDpAsState(targetWidth, label = "expandableSearchWidth")
        Box(Modifier.width(searchWidth), contentAlignment = Alignment.CenterStart) {
            if (expanded || searchWidth > 41.dp) {
                OutlinedTextField(
                    value = value,
                    onValueChange = onValueChange,
                    leadingIcon = { SharedIcon(Icons.Outlined.Search, contentDescription = null) },
                    trailingIcon = {
                        if (value.isNotEmpty()) {
                            IconButton(onClick = { onClear?.invoke() ?: onValueChange("") }) {
                                SharedIcon(Icons.Default.Close, stringResource(R.string.common_clear))
                            }
                        } else {
                            IconButton(onClick = ::collapse) {
                                SharedIcon(Icons.Default.Close, stringResource(R.string.common_close))
                            }
                        }
                    },
                    placeholder = { androidx.compose.material3.Text(placeholder, maxLines = 1) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        capitalization = capitalization,
                        imeAction = if (onSearch == null) ImeAction.Default else ImeAction.Search
                    ),
                    keyboardActions = KeyboardActions(onSearch = {
                        onSearch?.invoke()
                        collapse()
                    }),
                    shape = RoundedCornerShape(28.dp),
                    modifier = Modifier.fillMaxWidth()
                        .focusRequester(focusRequester)
                        .onGloballyPositioned { onBoundsChange(it.boundsInRoot()) }
                        .onFocusChanged { focus ->
                            if (focus.isFocused) wasFocused = true
                            else if (collapseOnFocusLoss && wasFocused && expanded) {
                                wasFocused = false
                                collapse()
                            }
                        }
                        .testTag(testTag)
                )
            } else {
                FilledTonalIconButton(
                    onClick = { onExpandedChange(true) },
                    modifier = Modifier.size(40.dp).testTag(buttonTestTag)
                ) {
                    SharedIcon(
                        Icons.Outlined.Search,
                        contentDescription = placeholder,
                        tint = if (value.isNotEmpty()) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }
        }
    }
}
