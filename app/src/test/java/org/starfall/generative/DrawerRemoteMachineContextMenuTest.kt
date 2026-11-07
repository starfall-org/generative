package org.starfall.generative

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.starfall.generative.features.home.drawer.ui.DrawerQuickShortcuts
import org.starfall.generative.shared.model.RemoteMachine
import org.starfall.generative.shared.model.ToolSettings
import org.starfall.generative.shared.model.withSelectedRemoteMachine
import org.starfall.generative.shared.model.withRemoteMachineEnabled
import org.starfall.generative.shared.model.remoteMachineToolEnabled
import org.starfall.generative.shared.model.REMOTE_TOOL_EXECUTE
import org.starfall.generative.shared.ui.theme.GenerativeTheme
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
        var settings by mutableStateOf(ToolSettings(selectedMachineId = ""))
        var forwarded: RemoteMachine? = null
        var terminal: RemoteMachine? = null
        var toolsSaved: RemoteMachine? = null

        compose.setContent {
            GenerativeTheme(themeMode = "LIGHT", amoledMode = false, dynamicColor = false) {
                CompositionLocalProvider(
                    LocalToolControls provides ToolControls(
                        machines = machines,
                        settings = settings,
                        selectMachine = { settings = settings.withSelectedRemoteMachine(it, machines.map { machine -> machine.id }) },
                        setMachine = { id, enabled ->
                            settings = settings.withRemoteMachineEnabled(id, enabled, machines.map { machine -> machine.id })
                        },
                        setServerside = { machine, enabled ->
                            settings = settings.copy(serversideMachines = settings.serversideMachines + (machine.id to enabled))
                        }
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
        compose.onNodeWithTag("drawer_machine_picker_item_machine-2").assertIsDisplayed()
        compose.onNodeWithTag("drawer_machine_picker_item_machine-2").performClick()
        compose.runOnIdle {
            assertEquals(production.id, settings.selectedMachineId)
            assertTrue("Selection must not enable a server", settings.enabledMachines.values.none { it })
        }

        compose.onNodeWithTag("drawer_machine_options_machine-2").performClick()
        compose.onNodeWithTag("drawer_machine_switch_machine-2").assertIsOff().performClick()
        compose.onNodeWithTag("drawer_machine_switch_machine-2").assertIsOn()
        compose.onNodeWithTag("drawer_machine_switch_machine-2").performClick()
        compose.onNodeWithTag("drawer_machine_switch_machine-2").assertIsOff()
        compose.onNodeWithTag("drawer_machine_switch_machine-2").performClick()
        compose.onNodeWithTag("drawer_machine_switch_machine-2").assertIsOn()
        compose.runOnIdle {
            assertEquals(setOf(production.id), settings.enabledMachines.filterValues { it }.keys)
            assertEquals(production.id, settings.selectedMachineId)
        }
        compose.onNodeWithText("Serverside CLI").assertIsDisplayed()
        compose.onNodeWithTag("drawer_serverside_switch_machine-2").assertIsOff().performClick()
        compose.onNodeWithTag("drawer_serverside_switch_machine-2").assertIsOn()
        compose.runOnIdle {
            assertEquals(true, settings.serversideMachines[production.id])
            assertTrue(remoteMachineToolEnabled(settings, production, REMOTE_TOOL_EXECUTE))
        }
        compose.onNodeWithText("Serverside CLI").performClick()
        compose.onNodeWithTag("drawer_machine_options_machine-2").performClick()
        compose.onNodeWithTag("drawer_serverside_switch_machine-2").assertIsOff()
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
        compose.onNodeWithText("Unset").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals("", settings.selectedMachineId)
            assertTrue("Unset must persistently disable tools", settings.enabledMachines.values.none { it })
            assertFalse(settings.machinesEnabled)
            assertFalse(remoteMachineToolEnabled(settings, production, REMOTE_TOOL_EXECUTE))
        }
        compose.onNodeWithTag("drawer_machine_options_machine-2").assertDoesNotExist()
        compose.onNodeWithText("Select server").assertIsDisplayed()
        compose.onNodeWithTag("drawer_machine_picker_trigger").performClick()
        compose.onNodeWithTag("drawer_machine_picker_item_machine-2").performClick()
        compose.runOnIdle {
            assertEquals("machine-2", settings.selectedMachineId)
            assertTrue("Previously enabled server must restore its switch",
                remoteMachineToolEnabled(settings, production, REMOTE_TOOL_EXECUTE))
            assertFalse(remoteMachineToolEnabled(settings, staging, REMOTE_TOOL_EXECUTE))
        }
        compose.onNodeWithTag("drawer_machine_options_machine-2").performClick()
        compose.onNodeWithTag("drawer_machine_switch_machine-2").assertIsOn().performClick()
        compose.onNodeWithTag("drawer_machine_switch_machine-2").assertIsOff()
        compose.runOnIdle { assertEquals(false, settings.rememberedEnabledMachines["machine-2"]) }
        compose.runOnIdle { assertNull(toolsSaved) }
    }

    @Test
    fun selectingAnotherMachineReplacesTheCurrentSelection() {
        val machines = listOf(RemoteMachine("machine-a", "Alpha"), RemoteMachine("machine-b", "Beta"))
        var settings by mutableStateOf(ToolSettings(
            selectedMachineId = "machine-a",
            machinesEnabled = true,
            enabledMachines = mapOf("machine-a" to true, "machine-b" to false)
        ))
        compose.setContent {
            GenerativeTheme(themeMode = "LIGHT", amoledMode = false, dynamicColor = false) {
                CompositionLocalProvider(LocalToolControls provides ToolControls(
                    machines = machines,
                    settings = settings,
                    selectMachine = { settings = settings.withSelectedRemoteMachine(it, machines.map { machine -> machine.id }) },
                    setMachine = { id, enabled ->
                        settings = settings.withRemoteMachineEnabled(id, enabled, machines.map { machine -> machine.id })
                    }
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
        compose.runOnIdle {
            assertEquals("machine-b", settings.selectedMachineId)
            assertTrue("Switching server must disable A", settings.enabledMachines.values.none { it })
            assertFalse(settings.machinesEnabled)
            assertFalse(remoteMachineToolEnabled(settings, machines[0], REMOTE_TOOL_EXECUTE))
        }
        compose.onNodeWithTag("drawer_machine_options_machine-a").assertDoesNotExist()
        compose.onNodeWithTag("drawer_machine_options_machine-b").assertIsDisplayed()
        compose.onNodeWithTag("drawer_machine_options_machine-b").performClick()
        compose.onNodeWithTag("drawer_machine_switch_machine-b").assertIsOff()
        compose.onNodeWithText("Open terminal").performClick()
        compose.onNodeWithTag("drawer_machine_picker_trigger").performClick()
        compose.onNodeWithTag("drawer_machine_picker_item_machine-a").performClick()
        compose.runOnIdle {
            assertTrue("A must recover its previously enabled state",
                remoteMachineToolEnabled(settings, machines[0], REMOTE_TOOL_EXECUTE))
            assertFalse(remoteMachineToolEnabled(settings, machines[1], REMOTE_TOOL_EXECUTE))
        }
        compose.onNodeWithTag("drawer_machine_options_machine-a").performClick()
        compose.onNodeWithTag("drawer_machine_switch_machine-a").assertIsOn()
    }
}
