package org.starfall.generative.shared.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = org.starfall.generative.LibraryTestApplication::class)
class ScreenTemplateTest {
    @get:Rule val compose = createComposeRule()

    @Test fun previewContentUsesTheSecondaryScreenShell() {
        compose.setContent {
            MaterialTheme {
                ScreenTemplate(title = "Preview", onBack = {}) {
                    ContentPreview {
                        Text("Read only", Modifier.testTag("preview-content"))
                    }
                }
            }
        }

        compose.onNodeWithTag("screen-template").assertIsDisplayed()
        compose.onNodeWithTag("preview-content").assertIsDisplayed()
    }

    @Test fun visualEditorCanBeAttachedToTheShell() {
        compose.setContent {
            MaterialTheme {
                ScreenTemplate(title = "visual", onBack = {}) {
                    ContentVisualEditor { Text("Form", Modifier.testTag("visual")) }
                }
            }
        }
        compose.onNodeWithTag("visual").assertIsDisplayed()
    }

    @Test fun textEditorCanBeAttachedToTheShell() {
        compose.setContent {
            MaterialTheme {
                ScreenTemplate(title = "text", onBack = {}) {
                    ContentTextEditor { Text("Editor", Modifier.testTag("text")) }
                }
            }
        }
        compose.onNodeWithTag("text").assertIsDisplayed()
    }

    @Test fun listCanBeAttachedToTheShell() {
        compose.setContent {
            MaterialTheme {
                ScreenTemplate(title = "list", onBack = {}) {
                    ContentList { Text("Item", Modifier.testTag("list")) }
                }
            }
        }
        compose.onNodeWithTag("list").assertIsDisplayed()
    }

    @Test fun screenTemplateRejectsMissingOrMultipleContentKinds() {
        assertThrows(IllegalStateException::class.java) {
            compose.setContent {
                MaterialTheme {
                    ScreenTemplate(title = "Missing", onBack = {}) { }
                }
            }
            compose.waitForIdle()
        }

        assertThrows(IllegalStateException::class.java) {
            compose.setContent {
                MaterialTheme {
                    ScreenTemplate(title = "Multiple", onBack = {}) {
                        ContentPreview { Text("Preview") }
                        ContentList { Box(Modifier.testTag("second")) }
                    }
                }
            }
            compose.waitForIdle()
        }
    }

    @Test fun recompositionOfOneContentListDoesNotCountAsTwo() {
        var isEmpty by mutableStateOf(true)
        compose.setContent {
            MaterialTheme {
                ScreenTemplate(title = "Collection", onBack = {}) {
                    ContentList(isEmpty = isEmpty, emptyContent = { Text("Empty") }) {
                        Text("Item")
                    }
                }
            }
        }
        compose.onNodeWithText("Empty").assertIsDisplayed()
        compose.runOnIdle { isEmpty = false }
        compose.onNodeWithText("Item").assertIsDisplayed()
    }

    @Test fun twoContentListsStillViolateTheContract() {
        assertThrows(IllegalStateException::class.java) {
            compose.setContent {
                MaterialTheme {
                    ScreenTemplate(title = "Duplicate", onBack = {}) {
                        ContentList { Text("First") }
                        ContentList { Text("Second") }
                    }
                }
            }
            compose.waitForIdle()
        }
    }

    @Test fun mainScreenTemplateDoesNotAddASecondaryScreenShell() {
        compose.setContent {
            MainScreenTemplate { Text("Chat root") }
        }

        compose.onNodeWithText("Chat root").assertIsDisplayed()
        compose.onNodeWithTag("screen-template").assertDoesNotExist()
    }
    @Test fun appBarSlotsAndBackCallbackBelongToTheShell() {
        var backCalls = 0
        compose.setContent {
            MaterialTheme {
                ScreenTemplate(
                    title = { Text("Custom title") },
                    onBack = { backCalls++ },
                    actions = { TextButton(onClick = {}) { Text("Action") } }
                ) {
                    ContentTextEditor {
                        Text("Text area", Modifier.testTag("text-editor-content"))
                    }
                }
            }
        }

        compose.onNodeWithText("Custom title").assertIsDisplayed()
        compose.onNodeWithText("Action").assertIsDisplayed()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.runOnIdle { check(backCalls == 1) }
    }
}
