package org.starfall.generative

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.starfall.generative.shared.ui.icons.Icons
import org.starfall.generative.shared.ui.menus.ContextDropdownMenu
import org.starfall.generative.shared.ui.menus.ContextMenuAction

class ContextDropdownMenuTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun enabledActionDismissesBeforeCallingHandler() {
        val events = mutableListOf<String>()
        compose.setContent {
            var expanded by remember { mutableStateOf(false) }
            MaterialTheme {
                Box {
                    IconButton(onClick = { expanded = true }) { Text("Open") }
                    ContextDropdownMenu(
                        expanded = expanded,
                        actions = listOf(ContextMenuAction(
                            label = "Edit",
                            icon = Icons.Outlined.Edit,
                            onClick = { events += "edit" }
                        )),
                        onDismissRequest = {
                            events += "dismiss"
                            expanded = false
                        }
                    )
                }
            }
        }
        compose.onNodeWithText("Open").performClick()
        compose.onNodeWithText("Edit").performClick()
        compose.waitForIdle()
        assertEquals(listOf("dismiss", "edit"), events)
        compose.onNodeWithText("Edit").assertDoesNotExist()
    }

    @Test
    fun disabledDestructiveActionCannotBeSelected() {
        val events = mutableListOf<String>()
        compose.setContent {
            MaterialTheme {
                Box {
                    ContextDropdownMenu(
                        expanded = true,
                        actions = listOf(
                            ContextMenuAction("Remove", { events += "remove" },
                                icon = Icons.Outlined.Delete, enabled = false, destructive = true)
                        ),
                        onDismissRequest = { events += "dismiss" }
                    )
                }
            }
        }
        compose.onNodeWithText("Remove").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(emptyList<String>(), events) }
    }
}
