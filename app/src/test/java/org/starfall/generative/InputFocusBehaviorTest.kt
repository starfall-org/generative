package org.starfall.generative

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.starfall.generative.shared.ui.inputs.HeaderNameField
import org.starfall.generative.shared.ui.inputs.InputFocusHost
import org.starfall.generative.shared.ui.inputs.OutlinedTextField
import org.starfall.generative.shared.ui.layout.AppSwipeTabs

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = LibraryTestApplication::class)
class InputFocusBehaviorTest {
    @get:Rule val compose = createComposeRule()

    @Test fun outsidePressDismissesFocusWithoutConsumingButtonOrFieldTaps() {
        var value by mutableStateOf("Draft")
        var clicks = 0
        compose.setContent {
            MaterialTheme {
                InputFocusHost(Modifier.fillMaxSize()) {
                    Column {
                        OutlinedTextField(value, { value = it }, Modifier.testTag("field"))
                        HeaderNameField("Name", {}, "Name", Modifier.testTag("name"))
                        Button(onClick = { clicks++ }, modifier = Modifier.testTag("action")) { Text("Action") }
                        Box(Modifier.fillMaxSize().testTag("blank"))
                    }
                }
            }
        }
        compose.onNodeWithTag("field").performClick().assertIsFocused()
        compose.onNodeWithTag("field").performTextInput("!")
        compose.onNodeWithTag("field").assertIsFocused()
        compose.onNodeWithTag("name").performClick().assertIsFocused()
        compose.onNodeWithTag("field").assertIsNotFocused()
        compose.onNodeWithTag("blank").performTouchInput { click() }
        compose.onNodeWithTag("name").assertIsNotFocused()
        compose.onNodeWithTag("field").assertIsNotFocused()
        compose.onNodeWithTag("field").performClick()
        compose.onNodeWithTag("action").performTouchInput { click() }
        compose.onNodeWithTag("field").assertIsNotFocused()
        compose.runOnIdle { assertEquals(1, clicks) }
    }

    @Test fun dialogUsesItsOwnFocusHost() {
        compose.setContent {
            MaterialTheme {
                InputFocusHost(Modifier.fillMaxSize()) {
                    org.starfall.generative.shared.ui.dialogs.Dialog(
                        onDismissRequest = {},
                        title = { Text("Editor") },
                        content = { OutlinedTextField("Draft", {}, Modifier.testTag("dialog-field")) }
                    )
                }
            }
        }
        compose.onNodeWithTag("dialog-field").performClick().assertIsFocused()
        compose.onNodeWithTag("app-dialog-title").performTouchInput { click() }
        compose.onNodeWithTag("dialog-field").assertIsNotFocused()
    }

    @Test fun unregisteredInputKeepsItsOwnOutsidePressPolicy() {
        compose.setContent {
            MaterialTheme {
                InputFocusHost(Modifier.fillMaxSize()) {
                    Column {
                        BasicTextField("Chat", {}, Modifier.testTag("custom-input"))
                        Box(Modifier.fillMaxSize().testTag("blank"))
                    }
                }
            }
        }
        compose.onNodeWithTag("custom-input").performClick().assertIsFocused()
        compose.onNodeWithTag("blank").performTouchInput { click() }
        compose.onNodeWithTag("custom-input").assertIsFocused()
    }

    @Test fun tabClicksSwipesAndProgrammaticChangesClearHeaderFocus() {
        lateinit var pager: PagerState
        compose.setContent {
            MaterialTheme {
                InputFocusHost(Modifier.fillMaxSize()) {
                    Column {
                        HeaderNameField("Name", {}, "Name", Modifier.testTag("name"))
                        pager = rememberPagerState(pageCount = { 2 })
                        AppSwipeTabs(listOf("Connection", "Tools"), pager,
                            modifier = Modifier.height(400.dp), tabTag = { "tab-$it" }) {
                            Box(Modifier.fillMaxSize())
                        }
                    }
                }
            }
        }
        compose.onNodeWithTag("name").performClick().assertIsFocused()
        compose.onNodeWithTag("tab-1").performClick()
        compose.onNodeWithTag("name").assertIsNotFocused()
        compose.onNodeWithTag("name").performClick()
        compose.onNodeWithTag("app-swipe-pager").performTouchInput { swipeRight() }
        compose.onNodeWithTag("tab-0").assertIsSelected()
        compose.onNodeWithTag("name").assertIsNotFocused()
        compose.onNodeWithTag("name").performClick()
        compose.runOnIdle { pager.requestScrollToPage(1) }
        compose.onNodeWithTag("tab-1").assertIsSelected()
        compose.onNodeWithTag("name").assertIsNotFocused()
    }
}
