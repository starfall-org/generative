package org.starfall.generative

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.starfall.generative.shared.ui.sheets.BottomSheet
import org.starfall.generative.shared.ui.sheets.SlideSheetContent

class BottomSheetContentCollapseDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun fullPageToCompactPageCollapsesAndCanExpandAgainWithoutReplacingSheet() {
        var compact by mutableStateOf(false)
        var dismissed = false
        compose.setContent {
            MaterialTheme {
                BottomSheet(
                    onDismissRequest = { dismissed = true },
                    expandOnOpen = !compact,
                    containerColor = Color.Magenta
                ) {
                    SlideSheetContent(
                        page = if (compact) "compact" else "full",
                        onBack = { compact = false },
                        label = "Resize in place"
                    ) { page ->
                        if (page == "compact") {
                            Text("Compact page", Modifier.fillMaxWidth().height(120.dp))
                        } else {
                            Column {
                                Text("Full page", Modifier.fillMaxWidth())
                                Spacer(Modifier.height(950.dp))
                                androidx.compose.material3.TextButton(onClick = { compact = true }) {
                                    Text("Open compact")
                                }
                            }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        val sheet = compose.onNodeWithTag("bottom-sheet-window")
        val full = sheet.fetchSemanticsNode()
        val fullTop = coloredSheetTop()
        compose.runOnIdle { compact = true }
        compose.waitForIdle()
        compose.onNodeWithText("Compact page").assertExists()
        val collapsed = sheet.fetchSemanticsNode()
        val collapsedTop = coloredSheetTop()
        assertEquals("Changing linked pages must preserve the same sheet", full.id, collapsed.id)
        val minChange = with(compose.density) { 100.dp.toPx() }
        assertTrue("Expanded sheet must shrink (color top $fullTop -> $collapsedTop; bounds ${full.boundsInRoot.top} -> ${collapsed.boundsInRoot.top})", collapsedTop > fullTop + minChange)
        assertFalse("Shrinking must not dismiss the dialog", dismissed)

        compose.runOnIdle { compact = false }
        compose.waitForIdle()
        val expandedAgain = sheet.fetchSemanticsNode()
        val expandedAgainTop = coloredSheetTop()
        assertEquals("The same sheet must expand again", full.id, expandedAgain.id)
        assertTrue("Going back to a tall page expands the sheet ($collapsedTop -> $expandedAgainTop)", expandedAgainTop < collapsedTop - minChange)
    }

    @Test
    fun manualExpansionThenSmallerPageReturnsToContentSizedAnchor() {
        var compact by mutableStateOf(false)
        compose.setContent {
            MaterialTheme {
                BottomSheet(onDismissRequest = {}, containerColor = Color.Magenta) {
                    SlideSheetContent(
                        page = if (compact) "compact" else "medium",
                        onBack = { compact = false },
                        label = "Shrink expanded sheet"
                    ) { page ->
                        if (page == "compact") Text("Shorter page", Modifier.fillMaxWidth().height(90.dp))
                        else Spacer(Modifier.fillMaxWidth().height(370.dp))
                    }
                }
            }
        }
        compose.waitForIdle()
        val sheet = compose.onNodeWithTag("bottom-sheet-window")
        val initial = sheet.fetchSemanticsNode()
        val initialTop = coloredSheetTop()
        compose.onNodeWithTag("bottom-sheet-drag-handle").performTouchInput { swipeUp() }
        compose.waitForIdle()
        val expanded = sheet.fetchSemanticsNode()
        val expandedTop = coloredSheetTop()
        assertTrue("Manual swipe expands the native sheet (color top $initialTop -> $expandedTop; bounds ${initial.boundsInRoot.top} -> ${expanded.boundsInRoot.top})", expandedTop < initialTop)

        compose.runOnIdle { compact = true }
        compose.waitForIdle()
        val short = sheet.fetchSemanticsNode()
        val shortTop = coloredSheetTop()
        assertEquals(initial.id, short.id)
        val minChange = with(compose.density) { 100.dp.toPx() }
        assertTrue("After page change a manually expanded sheet must collapse ($expandedTop -> $shortTop)", shortTop > expandedTop + minChange)
    }

    /** Samples the sheet's actual pixels rather than relying on cached semantics bounds. */
    private fun coloredSheetTop(): Int {
        val pixels = compose.onNodeWithTag("app-bottom-sheet").captureToImage().toPixelMap()
        val center = pixels.width / 2
        return (0 until pixels.height).firstOrNull { y ->
            val color = pixels[center, y]
            color.red > 0.65f && color.blue > 0.65f && color.green < 0.35f
        } ?: error("Magenta test sheet was not visible")
    }
}
