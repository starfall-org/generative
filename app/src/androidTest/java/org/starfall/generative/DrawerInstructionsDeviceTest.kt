package org.starfall.generative

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.starfall.generative.features.home.drawer.ui.DrawerQuickShortcuts
import org.starfall.generative.features.home.shared.ui.InstructionControls
import org.starfall.generative.features.home.shared.ui.LocalInstructionControls
import org.starfall.generative.shared.model.InstructionLibrary
import org.starfall.generative.shared.model.SavedInstruction

class DrawerInstructionsDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun instructionsAppearDirectlyBelowRemoteAndUseTheSameExpandableContainer() {
        val instruction = SavedInstruction("instruction-id", "System instruction", "Answer clearly")
        var library by mutableStateOf(InstructionLibrary(listOf(instruction), setOf(instruction.id)))
        var openedSettings = false
        var editedInstructionId: String? = null
        compose.setContent {
            TestTheme {
                CompositionLocalProvider(LocalInstructionControls provides InstructionControls(
                    library = library,
                    setInstructionEnabled = { id, enabled ->
                        library = library.copy(selectedIds = if (enabled) library.selectedIds + id
                            else library.selectedIds - id)
                    },
                    openInstructions = { openedSettings = true },
                    editInstruction = { editedInstructionId = it }
                )) {
                    DrawerQuickShortcuts(
                        onOpenMediaStorage = {},
                        onOpenRemoteMachines = {},
                        onSaveMachineTools = {}
                    )
                }
            }
        }

        val remote = compose.onNodeWithTag("drawer_remote_header").fetchSemanticsNode().boundsInRoot
        val instructions = compose.onNodeWithTag("drawer_instructions_header").fetchSemanticsNode().boundsInRoot
        val media = compose.onNodeWithTag("drawer_media_card").fetchSemanticsNode().boundsInRoot
        assertTrue("Instructions must be directly below Remote Services", instructions.top >= remote.bottom)
        assertTrue("Media must be the last item in the group", media.top >= instructions.bottom)
        compose.onNodeWithTag("drawer_instructions_list").assertDoesNotExist()
        compose.onNodeWithTag("drawer_instructions_header").performClick()
        compose.onNodeWithTag("drawer_instructions_list").assertIsDisplayed()
        compose.onNodeWithText("Answer clearly").assertDoesNotExist()
        compose.onNodeWithText("System instruction").performClick()
        compose.runOnIdle { assertTrue(library.selectedIds.isEmpty()) }
        compose.onNodeWithText("0 of 1 enabled").assertIsDisplayed()
        compose.onNodeWithText("System instruction").performTouchInput { longClick() }
        compose.onNodeWithContentDescription("Open Chat Options > Instructions").performClick()
        compose.runOnIdle {
            assertEquals("instruction-id", editedInstructionId)
            assertTrue(openedSettings)
        }
        compose.onNodeWithTag("drawer_remote_header").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("drawer_instructions_list").assertDoesNotExist()
        compose.onNodeWithTag("drawer_machine_picker_trigger").assertIsDisplayed()
    }
}
