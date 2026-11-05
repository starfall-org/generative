package org.starfall.generative

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.starfall.generative.shared.ui.dialogs.AlertDialog
import org.starfall.generative.shared.ui.dialogs.Dialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w420dp-h800dp-xhdpi", application = LibraryTestApplication::class)
class DialogFooterOrderTest {
    @get:Rule val compose = createComposeRule()

    @Test fun saveIsRightOfCloseForSaveDialogs() {
        compose.setContent {
            MaterialTheme {
                AlertDialog(onDismissRequest = {},
                    title = { Text("Edit") }, text = { Text("Details") },
                    confirmAtRight = true,
                    confirmButton = { TextButton(onClick = {}) { Text("Save") } },
                    dismissButton = { TextButton(onClick = {}) { Text("Close") } })
            }
        }
        val save = compose.onNodeWithText("Save").fetchSemanticsNode().boundsInRoot
        val close = compose.onNodeWithText("Close").fetchSemanticsNode().boundsInRoot
        assertTrue("Save must be the rightmost footer button", save.left > close.left)
    }

    @Test fun deleteIsRightOfCancelWithoutSave() {
        compose.setContent {
            MaterialTheme {
                AlertDialog(onDismissRequest = {},
                    title = { Text("Confirmation") }, text = { Text("Details") },
                    confirmButton = { TextButton(onClick = {}) { Text("Delete") } },
                    dismissButton = { TextButton(onClick = {}) { Text("Cancel") } })
            }
        }
        val delete = compose.onNodeWithText("Delete").fetchSemanticsNode().boundsInRoot
        val cancel = compose.onNodeWithText("Cancel").fetchSemanticsNode().boundsInRoot
        assertTrue("Delete must be to the right of Cancel", delete.left > cancel.left)
    }

    @Test fun customFooterPlacesDeleteBeforeCancelWhenSaveExists() {
        compose.setContent {
            MaterialTheme {
                Dialog(onDismissRequest = {}, title = { Text("Report") }, content = { Text("Details") },
                    buttons = {
                        TextButton(onClick = {}) { Text("Delete") }
                        TextButton(onClick = {}) { Text("Cancel") }
                        TextButton(onClick = {}) { Text("Save") }
                    })
            }
        }
        val delete = compose.onNodeWithText("Delete").fetchSemanticsNode().boundsInRoot
        val cancel = compose.onNodeWithText("Cancel").fetchSemanticsNode().boundsInRoot
        val save = compose.onNodeWithText("Save").fetchSemanticsNode().boundsInRoot
        assertTrue(delete.left < cancel.left)
        assertTrue(cancel.left < save.left)
    }
}
