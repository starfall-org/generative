package org.starfall.generative.shared.subfeatures.modelpicker.ui

import org.starfall.generative.shared.subfeatures.modelpicker.logic.ModelPickerTab

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Horizontal row of folder tabs above the picker list. */
@Composable
internal fun ModelPickerTabRow(
    tabs: List<ModelPickerTab>,
    selectedIndex: Int,
    onSelectTab: (ModelPickerTab) -> Unit
) {
    PrimaryScrollableTabRow(
        selectedTabIndex = selectedIndex,
        edgePadding = 12.dp,
        containerColor = Color.Transparent,
        divider = {}
    ) {
        tabs.forEachIndexed { index, tab ->
            Tab(
                selected = selectedIndex == index,
                onClick = { onSelectTab(tab) },
                modifier = Modifier.testTag("model-picker-tab_${tab.key}"),
                text = {
                    Text(tab.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            )
        }
    }
}
