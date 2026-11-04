package org.starfall.generative

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.starfall.generative.shared.ui.RuntimeAppLocale

/** Switching the runtime locale must keep the screen and its unsent draft alive. */
@RunWith(AndroidJUnit4::class)
class RuntimeAppLocaleDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun localizedContextPreservesActivityResultRegistryOwner() {
        compose.setContent {
            RuntimeAppLocale(useSystemLanguage = false, selectedLanguage = "en") {
                rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { }
                Text("Activity result launcher available", Modifier.testTag("activity-result-ready"))
            }
        }
        compose.onNodeWithTag("activity-result-ready").assertTextEquals("Activity result launcher available")
    }

    @Test fun switchingLanguageUpdatesStringsAndContextWithoutRecreatingScreen() {
        val language = mutableStateOf("en")
        val useSystem = mutableStateOf(false)
        val systemString = InstrumentationRegistry.getInstrumentation().targetContext
            .getString(R.string.select_model)

        compose.setContent {
            RuntimeAppLocale(useSystem.value, language.value) {
                var draft by remember { mutableStateOf("unsent message") }
                Column {
                    Text(stringResource(R.string.select_model), Modifier.testTag("localized-string"))
                    Text(LocalResources.current.getString(R.string.select_model),
                        Modifier.testTag("localized-context"))
                    Text(draft, Modifier.testTag("retained-draft"))
                    Button(onClick = { draft = "edited unsent message" },
                        modifier = Modifier.testTag("change-draft")) { Text("Edit") }
                }
            }
        }
        compose.onNodeWithTag("localized-string").assertTextEquals("Select model")
        compose.onNodeWithTag("localized-context").assertTextEquals("Select model")
        compose.onNodeWithTag("change-draft").performClick()
        compose.onNodeWithTag("retained-draft").assertTextEquals("edited unsent message")

        compose.runOnIdle { language.value = "ja" }
        compose.onNodeWithTag("localized-string").assertTextEquals("モデルを選択")
        compose.onNodeWithTag("localized-context").assertTextEquals("モデルを選択")
        compose.onNodeWithTag("retained-draft").assertTextEquals("edited unsent message")

        compose.runOnIdle { useSystem.value = true }
        compose.onNodeWithTag("localized-string").assertTextEquals(systemString)
        compose.onNodeWithTag("localized-context").assertTextEquals(systemString)
        compose.onNodeWithTag("retained-draft").assertTextEquals("edited unsent message")
    }
}
