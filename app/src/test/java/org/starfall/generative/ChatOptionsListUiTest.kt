package org.starfall.generative

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.starfall.generative.features.chatoptions.ui.ChatOptionsScreen
import org.starfall.generative.shared.model.InstructionLibrary
import org.starfall.generative.shared.model.SavedInstruction
import org.starfall.generative.shared.model.ToolSettings
import org.starfall.generative.shared.ui.theme.GenerativeTheme

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w360dp-h800dp-xhdpi", application = LibraryTestApplication::class)
class ChatOptionsListUiTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun gridLayoutKeepsInstructionDeleteAndSelectionInSync() {
        val instructions = listOf(
            SavedInstruction("first", "First instruction", "Be concise."),
            SavedInstruction("second", "Second instruction", "Show examples.")
        )
        var updated: InstructionLibrary? = null
        compose.setContent {
            GenerativeTheme(darkTheme = false, amoledMode = false) {
                ChatOptionsScreen(
                    providers = emptyList(),
                    settings = ToolSettings(),
                    initialTab = "instructions",
                    instructionLibrary = InstructionLibrary(instructions, setOf("first")),
                    onInstructionLibraryChange = { updated = it },
                    onSave = { _, _ -> },
                    onBack = {}
                )
            }
        }

        compose.onNodeWithContentDescription("Switch to Grid View").performClick()
        compose.onNodeWithContentDescription("Switch to List View").assertIsDisplayed()
        val first = compose.onNodeWithText("First instruction").fetchSemanticsNode().boundsInRoot
        val second = compose.onNodeWithText("Second instruction").fetchSemanticsNode().boundsInRoot
        assertTrue("Grid cards should appear side by side", second.left > first.left)
        assertEquals(first.top, second.top, 1f)

        compose.onAllNodesWithContentDescription("More options")[0].performClick()
        compose.onNodeWithText("Delete").performClick()
        compose.onNodeWithText("Delete").performClick()
        compose.runOnIdle {
            assertEquals(listOf("second"), updated?.instructions?.map { it.id })
            assertEquals(emptySet<String>(), updated?.selectedIds)
        }
    }
}
