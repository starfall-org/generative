package org.starfall.generative.shared.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = org.starfall.generative.LibraryTestApplication::class)
class ScreenTemplateTest {
    @get:Rule val compose = createComposeRule()

    @Test fun previewOwnsAppBarAndScrollableViewport() {
        compose.setContent {
            MaterialTheme {
                PreviewScreenTemplate(title = "Preview", onBack = {}) {
                    Text("Read only", Modifier.testTag("preview-content"))
                }
            }
        }
        compose.onNodeWithTag("preview-screen-template").assertIsDisplayed()
        compose.onNodeWithTag("preview-content").assertIsDisplayed()
    }

    @Test fun editorOwnsAppBarAndImeSafeContent() {
        compose.setContent {
            MaterialTheme {
                EditorScreenTemplate(title = "Editor", onBack = {}) { _ ->
                    Text("Form", Modifier.testTag("editor"))
                }
            }
        }
        compose.onNodeWithTag("editor-screen-template").assertIsDisplayed()
        compose.onNodeWithTag("editor").assertIsDisplayed()
    }

    @Test fun listOwnsAppBarAndEmptyPlaceholder() {
        var isEmpty by mutableStateOf(true)
        compose.setContent {
            MaterialTheme {
                ListScreenTemplate(title = "Collection", onBack = {}, isEmpty = isEmpty,
                    emptyContent = { Text("Empty") }) { _ -> Text("Item") }
            }
        }
        compose.onNodeWithTag("list-screen-template").assertIsDisplayed()
        compose.onNodeWithText("Empty").assertIsDisplayed()
        compose.runOnIdle { isEmpty = false }
        compose.onNodeWithText("Item").assertIsDisplayed()
    }

    @Test fun nonScrollablePreviewKeepsCenteredContentWithinViewport() {
        compose.setContent {
            MaterialTheme {
                PreviewScreenTemplate(title = "Loading", onBack = {}, scrollable = false) {
                    Column(Modifier.fillMaxSize().testTag("preview-bounds"),
                        verticalArrangement = Arrangement.Center) {
                        Text("Loading content", Modifier.testTag("preview-center"))
                    }
                }
            }
        }
        val area = compose.onNodeWithTag("preview-bounds").getUnclippedBoundsInRoot()
        val content = compose.onNodeWithTag("preview-center").getUnclippedBoundsInRoot()
        assertTrue(content.top > area.top)
        assertTrue(content.bottom < area.bottom)
    }

    @Test fun mainTemplateDoesNotAddSecondaryScreenShell() {
        compose.setContent { MainScreenTemplate { Text("Chat root") } }
        compose.onNodeWithText("Chat root").assertIsDisplayed()
        compose.onNodeWithTag("list-screen-template").assertDoesNotExist()
        compose.onNodeWithTag("editor-screen-template").assertDoesNotExist()
    }

    @Test fun editorActionsAndBackCallbackBelongToTheTemplate() {
        var backs = 0
        compose.setContent {
            MaterialTheme {
                EditorScreenTemplate(title = { Text("Custom title") },
                    onBack = { backs++ },
                    actions = { TextButton(onClick = {}) { Text("Action") } }) { _ ->
                    Text("Text area")
                }
            }
        }
        compose.onNodeWithText("Custom title").assertIsDisplayed()
        compose.onNodeWithText("Action").assertIsDisplayed()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.runOnIdle { check(backs == 1) }
    }
}
