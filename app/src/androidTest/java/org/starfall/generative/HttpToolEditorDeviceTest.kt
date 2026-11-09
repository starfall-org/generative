package org.starfall.generative

import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.text.TextRange
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.starfall.generative.features.httptools.ui.HttpToolEditor
import org.starfall.generative.shared.model.CustomHttpTool
import org.starfall.generative.shared.ui.RuntimeAppLocale

/** Exercise the actual Compose editor on Android, including Android's ICU template regex. */
@RunWith(AndroidJUnit4::class)
class HttpToolEditorDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun obj(text: String) = Json.parseToJsonElement(text).jsonObject

    @Suppress("DEPRECATION") // Window flags also support the app's API 26 test devices.
    private fun showEditor(
        tool: CustomHttpTool = CustomHttpTool(name = "weather", url = "https://example.test"),
        onSave: suspend (CustomHttpTool) -> Unit = {},
        onBack: () -> Unit = {}
    ) {
        compose.runOnUiThread {
            // Keep the test host visible even when the device starts locked or has a short timeout.
            compose.activity.window.addFlags(
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        compose.setContent {
            RuntimeAppLocale(useSystemLanguage = false, selectedLanguage = "en") {
                MaterialTheme { HttpToolEditor(tool, onSave, onBack) }
            }
        }
    }

    @Test fun addsParameterAndInsertsTokenAtQueryCursor() {
        var saved: CustomHttpTool? = null
        var closed = false
        showEditor(onSave = { saved = it }, onBack = { closed = true })

        compose.onNodeWithContentDescription("Add parameter").performScrollTo().performClick()
        compose.onNodeWithText("Parameter name").performTextInput("city")
        compose.onNodeWithText("Save").performClick()
        compose.onNodeWithTag("http_query").performScrollTo().performTextReplacement("""{"q": }""")
        compose.onNodeWithTag("http_query").performTextInputSelection(TextRange(6))
        compose.onNodeWithTag("http_query_insert").performScrollTo().performClick()
        compose.onNodeWithText("Insert").performClick()
        compose.onNodeWithContentDescription("Save").performClick()

        compose.runOnIdle {
            assertTrue(closed)
            assertEquals(JsonPrimitive("string"), saved!!.parameters["properties"]!!.jsonObject["city"]!!.jsonObject["type"])
            assertEquals(obj("""{"q":"{{city}}"}"""), saved!!.query)
        }
    }

    @Test fun renamingParameterUpdatesQueryHeadersAndBodyWithoutLosingSchemaConstraints() {
        val original = CustomHttpTool(name = "weather", method = "POST", url = "https://example.test",
            parameters = obj("""{"type":"object","properties":{"city":{"type":"string","minLength":1}},"required":["city"]}"""),
            query = obj("""{"q":"{{ city }}"}"""), headers = obj("""{"X-City":"prefix {{city}}"}"""),
            body = """{"city":"{{city}}"}""")
        var saved: CustomHttpTool? = null
        showEditor(original, onSave = { saved = it })

        compose.onNodeWithText("city", substring = false).performScrollTo().performClick()
        compose.onNodeWithText("Parameter name").performTextReplacement("location")
        compose.onNodeWithText("Save").performClick()
        compose.onNodeWithContentDescription("Save").performClick()

        compose.runOnIdle {
            val edited = saved!!
            assertEquals(original.parameters["properties"]!!.jsonObject["city"],
                edited.parameters["properties"]!!.jsonObject["location"])
            assertFalse("city" in edited.parameters["properties"]!!.jsonObject)
            assertEquals(buildJsonArray { add("location") }, edited.parameters["required"])
            assertEquals(obj("""{"q":"{{location}}"}"""), edited.query)
            assertEquals(obj("""{"X-City":"prefix {{location}}"}"""), edited.headers)
            assertEquals(obj("""{"city":"{{location}}"}"""), obj(edited.body))
        }
    }

    @Test fun deletedParameterLeavesVisibleTemplateErrorAndBlocksSaving() {
        var saves = 0
        var backs = 0
        showEditor(CustomHttpTool(name = "weather", url = "https://example.test",
            parameters = obj("""{"type":"object","properties":{"city":{"type":"string"}}}"""),
            query = obj("""{"q":"{{city}}"}""")), onSave = { saves++ }, onBack = { backs++ })

        compose.onNodeWithContentDescription("Delete").performScrollTo().performClick()
        compose.onNodeWithText("Template references a parameter that is not declared. Insert an existing parameter or update the template.")
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("Save").performClick()

        compose.runOnIdle {
            assertEquals(0, saves)
            assertEquals(0, backs)
        }
    }

    @Test fun invalidJsonBlocksSaveAndCanBeCorrected() {
        var saved: CustomHttpTool? = null
        var backs = 0
        showEditor(CustomHttpTool(name = "weather", method = "POST", url = "https://example.test"),
            onSave = { saved = it }, onBack = { backs++ })

        compose.onNodeWithTag("http_body").performScrollTo().performTextReplacement("{broken")
        compose.onNodeWithText("Enter valid JSON. Put parameter tokens inside double quotes.")
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("Save").performClick()
        compose.runOnIdle { assertNull(saved); assertEquals(0, backs) }

        compose.onNodeWithTag("http_body").performScrollTo().performTextReplacement("""{"count":3}""")
        compose.onNodeWithContentDescription("Save").performClick()
        compose.runOnIdle {
            assertEquals(obj("""{"count":3}"""), obj(saved!!.body))
            assertEquals(1, backs)
        }
    }

    @Test fun duplicateHeaderNamesAreRejectedCaseInsensitively() {
        var saves = 0
        showEditor(onSave = { saves++ })
        compose.onNodeWithTag("http_headers").performScrollTo()
            .performTextReplacement("""{"X-Key":"first","x-key":"second"}""")
        compose.onNodeWithText("Enter a JSON object with valid, unique header names and simple values. Header names ignore letter case.")
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("Save").performClick()
        compose.runOnIdle { assertEquals(0, saves) }
    }

    @Test fun previewRefreshesWhenSampleArgumentsChange() {
        showEditor(CustomHttpTool(name = "weather", url = "https://example.test",
            parameters = obj("""{"type":"object","properties":{"city":{"type":"string"}},"required":["city"]}"""),
            query = obj("""{"q":"{{city}}"}""")))
        compose.onNodeWithText("Preview request").performScrollTo().performClick()
        compose.onNodeWithTag("http_sample").performScrollTo().performTextReplacement("""{"city":"Hanoi"}""")
        compose.onNodeWithTag("http_preview").performScrollTo().assertTextContains("q=Hanoi", substring = true)
        compose.onNodeWithTag("http_sample").performScrollTo().performTextReplacement("""{"city":"Hue"}""")
        compose.onNodeWithTag("http_preview").performScrollTo().assertTextContains("q=Hue", substring = true)
    }

    @Test fun addingResponseFieldPreservesExistingNestedMappings() {
        val nested = obj("""{"metadata":{"total":"/meta/count"}}""")
        var saved: CustomHttpTool? = null
        showEditor(CustomHttpTool(name = "fetch", url = "https://example.test", responseMapping = nested),
            onSave = { saved = it })
        compose.onNodeWithContentDescription("Add response field").performScrollTo().performClick()
        compose.onNodeWithText("Field").performTextInput("items")
        compose.onNodeWithText("JSON Pointer", substring = false).performTextInput("/data/items")
        compose.onNodeWithText("Save").performClick()
        compose.onNodeWithContentDescription("Save").performClick()
        compose.runOnIdle {
            assertEquals(nested["metadata"], saved!!.responseMapping["metadata"])
            assertEquals(JsonPrimitive("/data/items"), saved!!.responseMapping["items"])
        }
    }

    @Test fun saveWaitsForPersistenceAndFailureAllowsRetry() {
        val completion = CompletableDeferred<Unit>()
        var attempts = 0
        var backs = 0
        showEditor(onSave = {
            attempts++
            if (attempts == 1) {
                completion.await()
                error("Simulated storage failure")
            }
        }, onBack = { backs++ })
        compose.onNodeWithContentDescription("Save").performClick().assertIsNotEnabled()
        compose.runOnIdle { assertEquals(1, attempts); assertEquals(0, backs) }

        completion.complete(Unit)
        compose.onNodeWithText("Could not save the change. Please try again.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("Save").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(2, attempts); assertEquals(1, backs) }
    }

    @Test fun backDiscardsDraftWithoutSaving() {
        var saves = 0
        var backs = 0
        showEditor(onSave = { saves++ }, onBack = { backs++ })
        compose.onNodeWithTag("http_query").performScrollTo().performTextReplacement("""{"q":"edited"}""")
        compose.onNodeWithContentDescription("Back").performClick()
        compose.runOnIdle { assertEquals(0, saves); assertEquals(1, backs) }
    }
}
