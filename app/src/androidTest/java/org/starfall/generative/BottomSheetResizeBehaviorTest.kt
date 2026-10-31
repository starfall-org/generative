package org.starfall.generative

import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.test.espresso.Espresso
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.starfall.generative.shared.ui.sheets.BottomSheet

class BottomSheetResizeBehaviorTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private var fullHeight = 0f

    @Test fun shortSheetOpensAtContentHeightAndKeepsTheLastItemVisible() {
        compose.activityRule.scenario.onActivity { WindowCompat.setDecorFitsSystemWindows(it.window, false) }
        compose.setContent {
            fullHeight = with(LocalDensity.current) { LocalConfiguration.current.screenHeightDp.dp.toPx() }
            MaterialTheme {
            BottomSheet(onDismissRequest = {}) {
                Text("Last item", Modifier.fillMaxWidth().padding(16.dp))
            }
        } }
        compose.waitForIdle()
        val sheetTop = compose.onNodeWithTag("bottom-sheet-window").fetchSemanticsNode().boundsInRoot.top
        assertTrue("Short content should not default to a half-screen sheet", sheetTop > fullHeight * 0.55f)
        compose.onNodeWithText("Last item").assertIsDisplayed()
    }

    @Test fun lazyItemsUseMeasuredPeekAndCanExpandToMaximum() {
        compose.setContent {
            fullHeight = with(LocalDensity.current) { LocalConfiguration.current.screenHeightDp.dp.toPx() }
            MaterialTheme {
            BottomSheet(onDismissRequest = {}) {
                Column {
                    Text("List header", Modifier.padding(16.dp))
                    LazyColumn(Modifier.weight(1f, fill = false)) {
                        items((1..6).toList()) { Text("Item $it", Modifier.fillMaxWidth().height(56.dp)) }
                    }
                }
            }
        } }
        compose.waitForIdle()
        val defaultTop = compose.onNodeWithTag("bottom-sheet-window").fetchSemanticsNode().boundsInRoot.top
        assertTrue("Measured list height should not be forced full-screen", defaultTop > 25f)
        compose.onNodeWithText("Item 6").assertIsDisplayed()
        compose.onNodeWithTag("bottom-sheet-drag-handle").performTouchInput { swipeUp() }
        compose.waitForIdle()
        val expandedTop = compose.onNodeWithTag("bottom-sheet-window").fetchSemanticsNode().boundsInRoot.top
        assertTrue("Maximum must still be reachable", expandedTop < defaultTop)
    }

    @Test fun linkedSheetBackNavigatesWithinDialogBeforeRootDismissal() {
        var subpage by mutableStateOf(true)
        var dismissed = false
        compose.setContent {
            MaterialTheme {
                BottomSheet(onDismissRequest = { dismissed = true }, dismissOnBack = !subpage) {
                    if (subpage) BackHandler { subpage = false }
                    Text(if (subpage) "Nested sheet" else "Root sheet", Modifier.padding(16.dp))
                }
            }
        }
        compose.onNodeWithText("Nested sheet").assertIsDisplayed()
        Espresso.pressBack()
        compose.waitForIdle()
        compose.onNodeWithText("Root sheet").assertIsDisplayed()
        assertFalse(dismissed)
        Espresso.pressBack()
        compose.waitForIdle()
        assertTrue(dismissed)
    }

    @Test fun modalScrimDismissesTheNativeSheet() {
        var dismissed = false
        compose.setContent {
            MaterialTheme {
                BottomSheet(onDismissRequest = { dismissed = true }) {
                    Text("Short sheet", Modifier.padding(16.dp))
                }
            }
        }
        compose.onNodeWithTag("bottom-sheet-scrim").performClick()
        compose.runOnIdle { assertTrue(dismissed) }
    }

    @Test fun longContentOpensAtMaximumWithoutHalfHeightStop() {
        var dismissed = false
        compose.setContent {
            fullHeight = with(LocalDensity.current) { LocalConfiguration.current.screenHeightDp.dp.toPx() }
            MaterialTheme {
                BottomSheet(onDismissRequest = { dismissed = true }) {
                    Box(Modifier.fillMaxSize())
                }
            }
        }
        compose.waitForIdle()
        val sheet = compose.onNodeWithTag("bottom-sheet-window")
        val handle = compose.onNodeWithTag("bottom-sheet-drag-handle")
        val initialTop = sheet.fetchSemanticsNode().boundsInRoot.top
        assertTrue("Long content should open at maximum height", initialTop < fullHeight * 0.12f)
        handle.performTouchInput { swipeUp() }
        compose.waitForIdle()
        val afterSwipe = sheet.fetchSemanticsNode().boundsInRoot.top
        assertEquals("Long sheets have a single visible height", initialTop, afterSwipe, 5f)
        assertFalse(dismissed)
    }

    @Test fun contentHeightChangesRepositionTheNativePeek() {
        var taller by mutableStateOf(false)
        compose.setContent {
            MaterialTheme {
                BottomSheet(onDismissRequest = {}) {
                    Column {
                        Text("Short sheet", Modifier.padding(16.dp))
                        if (taller) Spacer(Modifier.height(180.dp))
                    }
                }
            }
        }
        compose.waitForIdle()
        val sheet = compose.onNodeWithTag("bottom-sheet-window")
        val shortTop = sheet.fetchSemanticsNode().boundsInRoot.top
        compose.runOnIdle { taller = true }
        compose.waitForIdle()
        val tallerTop = sheet.fetchSemanticsNode().boundsInRoot.top
        assertTrue("The peek must track measured content without fixed percentages", tallerTop < shortTop)
    }
}
