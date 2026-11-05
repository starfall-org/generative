package org.starfall.generative

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.starfall.generative.shared.ui.screens.PreviewScreenTemplate
import org.starfall.generative.shared.ui.screens.ListScreenTemplate
import org.starfall.generative.shared.ui.screens.EditorScreenTemplate
import org.starfall.generative.shared.ui.screens.ScreenTransitionDirection
import org.starfall.generative.shared.ui.screens.ScreenTransitionEffect

/** Runs against Android Compose layout, IME and saveable-state infrastructure. */
class ScreenTemplateDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun previewScrollsAndAppBarInvokesCallerBack() {
        var backs = 0
        compose.setContent {
            MaterialTheme {
                PreviewScreenTemplate(title = "Preview", onBack = { backs++ }) {
                        repeat(45) { index -> Text("Preview row $index") }
                }
            }
        }

        compose.onNodeWithText("Preview row 44").performScrollTo().assertIsDisplayed()
        val backLabel = InstrumentationRegistry.getInstrumentation().targetContext
            .getString(R.string.common_back)
        compose.onNodeWithContentDescription(backLabel).performClick()
        compose.runOnIdle { check(backs == 1) }
    }

    @Test fun collectionPlaceholderReactsToState() {
        var hasItems by mutableStateOf(false)
        compose.setContent {
            MaterialTheme {
                ListScreenTemplate(title = "Collection", onBack = {}, isEmpty = !hasItems,
                    emptyContent = { Text("Nothing yet") }) { _ ->
                        Text("First item")
                }
            }
        }
        compose.onNodeWithText("Nothing yet").assertIsDisplayed()
        compose.runOnIdle { hasItems = true }
        compose.onNodeWithText("First item").assertIsDisplayed()
    }

    @Test fun textEditorAcceptsInput() {
        compose.setContent {
            var text by remember { mutableStateOf("") }
            MaterialTheme {
                EditorScreenTemplate(title = "Editor", onBack = {}) { _ ->
                        BasicTextField(
                            value = text,
                            onValueChange = { text = it },
                            modifier = Modifier.testTag("template-text-editor")
                        )
                }
            }
        }
        compose.onNodeWithTag("template-text-editor").performTextInput("Typed on device")
        compose.onNodeWithTag("template-text-editor").assertTextEquals("Typed on device")
    }

    @Test fun transitionRestoresSaveableScreenStateWhenReturning() {
        var page by mutableStateOf("A")
        compose.setContent {
            MaterialTheme {
                ScreenTransitionEffect(page, ScreenTransitionDirection.FORWARD, contentKey = { it }) { current ->
                    var count by rememberSaveable { mutableIntStateOf(0) }
                    Column {
                        Text("$current count $count")
                        Button(onClick = { count++ }) { Text("Increment") }
                    }
                }
            }
        }
        compose.onNodeWithText("Increment").performClick()
        compose.onNodeWithText("A count 1").assertIsDisplayed()
        compose.runOnIdle { page = "B" }
        compose.waitForIdle()
        compose.onNodeWithText("B count 0").assertIsDisplayed()
        compose.runOnIdle { page = "A" }
        compose.waitForIdle()
        compose.onNodeWithText("A count 1").assertIsDisplayed()
    }
}
