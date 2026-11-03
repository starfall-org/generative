package org.starfall.generative

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.starfall.generative.features.home.drawer.ui.DrawerQuickShortcuts
import org.starfall.generative.shared.model.RemoteMachine
import org.starfall.generative.shared.model.ToolSettings
import org.starfall.generative.shared.theme.GenerativeTheme
import org.starfall.generative.features.home.shared.ui.LocalToolControls
import org.starfall.generative.features.home.shared.ui.ToolControls

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w420dp-h900dp-xhdpi", application = LibraryTestApplication::class)
class DrawerRemoteMachineContextMenuTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun remoteShowsOnlyOneSelectionAndContextMenuSupportsForwardToolsAndUnset() {
        val staging = RemoteMachine("machine-1", "Staging")
        val production = RemoteMachine("machine-2", "Production")
        val machines = listOf(staging, production)
        var selectedId by mutableStateOf<String?>(null)
        var forwarded: RemoteMachine? = null
        var terminal: RemoteMachine? = null
        var toolsSaved: RemoteMachine? = null

        compose.setContent {
            GenerativeTheme(themeMode = "LIGHT", amoledMode = false, dynamicColor = false) {
                CompositionLocalProvider(
                    LocalToolControls provides ToolControls(
                        machines = machines,
                        settings = ToolSettings(
                            machinesEnabled = selectedId != null,
                            enabledMachines = machines.associate { it.id to (it.id == selectedId) }
                        ),
                        selectMachine = { selectedId = it }
                    )
                ) {
                    DrawerQuickShortcuts(
                        onOpenMediaStorage = {},
                        onOpenRemoteMachines = {},
                        onOpenTerminal = { terminal = it },
                        onPortForward = { forwarded = it },
                        onSaveMachineTools = { toolsSaved = it },
                        maxExpandedHeight = 400.dp
                    )
                }
            }
        }

        compose.onNodeWithText("Remote Services").performClick()
        compose.onNodeWithText("Select server").assertIsDisplayed()
        compose.onNodeWithTag("drawer_machine_options_machine-1").assertDoesNotExist()
        compose.onNodeWithTag("drawer_machine_options_machine-2").assertDoesNotExist()
        compose.onNodeWithTag("drawer_machine_picker_trigger").performClick()
        compose.onNodeWithText("Choose a server for AI tools").assertIsDisplayed()
        compose.onNodeWithTag("drawer_machine_picker_item_machine-2").performClick()
        compose.runOnIdle { assertEquals(production.id, selectedId) }

        compose.onNodeWithTag("drawer_machine_options_machine-2").performClick()
        compose.onNodeWithText("Open terminal").assertIsDisplayed()
        compose.onNodeWithText("Port Forward").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(production, forwarded) }
        compose.onNodeWithTag("drawer_machine_options_machine-2").performClick()
        compose.onNodeWithText("Edit").assertDoesNotExist()
        compose.onNodeWithText("Tools").assertIsDisplayed().performClick()
        compose.onNodeWithText("AI tools").assertIsDisplayed()
        compose.onNodeWithText("Close").performClick()
        compose.onNodeWithTag("drawer_machine_options_machine-2").performClick()
        compose.onNodeWithText("Open terminal").performClick()
        compose.runOnIdle { assertEquals(production, terminal) }

        compose.onNodeWithTag("drawer_machine_options_machine-2").performClick()
        compose.onNodeWithText("Unset").performClick()
        compose.runOnIdle { assertNull(selectedId) }
        compose.onNodeWithTag("drawer_machine_options_machine-2").assertDoesNotExist()
        compose.onNodeWithText("Select server").assertIsDisplayed()
        compose.runOnIdle { assertNull(toolsSaved) }
    }

    @Test
    fun selectingAnotherMachineReplacesTheCurrentSelection() {
        val machines = listOf(RemoteMachine("machine-a", "Alpha"), RemoteMachine("machine-b", "Beta"))
        var selectedId by mutableStateOf<String?>("machine-a")
        compose.setContent {
            GenerativeTheme(themeMode = "LIGHT", amoledMode = false, dynamicColor = false) {
                CompositionLocalProvider(LocalToolControls provides ToolControls(
                    machines = machines,
                    settings = ToolSettings(
                        machinesEnabled = true,
                        enabledMachines = machines.associate { it.id to (it.id == selectedId) }
                    ),
                    selectMachine = { selectedId = it }
                )) {
                    DrawerQuickShortcuts(
                        onOpenMediaStorage = {},
                        onOpenRemoteMachines = {},
                        onSaveMachineTools = {},
                        maxExpandedHeight = 400.dp
                    )
                }
            }
        }
        compose.onNodeWithText("Remote Services").performClick()
        compose.onNodeWithTag("drawer_machine_options_machine-a").assertIsDisplayed()
        compose.onNodeWithTag("drawer_machine_picker_trigger").performClick()
        compose.onNodeWithTag("drawer_machine_picker_item_machine-b").performClick()
        compose.runOnIdle { assertEquals("machine-b", selectedId) }
        compose.onNodeWithTag("drawer_machine_options_machine-a").assertDoesNotExist()
        compose.onNodeWithTag("drawer_machine_options_machine-b").assertIsDisplayed()
    }
}
