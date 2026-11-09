package org.starfall.generative

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.starfall.generative.features.httptools.ui.HttpToolEditor
import org.starfall.generative.shared.model.CustomHttpTool
import kotlinx.serialization.json.*
import androidx.compose.ui.text.TextRange

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w420dp-h1000dp-xhdpi")
class CustomHttpToolEditorUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun addsNamedParameterAndInsertsItDirectlyIntoQueryTemplate() {
        var saved: CustomHttpTool? = null
        var closed = false
        compose.setContent {
            MaterialTheme {
                HttpToolEditor(tool = CustomHttpTool(name = "weather", url = "https://example.test"),
                    onSave = { saved = it }, onBack = { closed = true })
            }
        }
        compose.onNodeWithContentDescription("Add parameter").performScrollTo().performClick()
        compose.onNodeWithText("Parameter name").performTextInput("city")
        compose.onNodeWithText("Save").performClick()
        compose.onNodeWithTag("http_query").performScrollTo().performTextReplacement("""{"q": }""")
        compose.onNodeWithTag("http_query").performTextInputSelection(TextRange(6))
        compose.onNodeWithTag("http_query_insert").performScrollTo().performClick()
        compose.onNodeWithText("Insert").performClick()
        compose.onNodeWithContentDescription("Save").performClick()
        compose.waitForIdle()
        assertTrue(closed)
        assertEquals(JsonPrimitive("string"), saved!!.parameters["properties"]!!.jsonObject["city"]!!.jsonObject["type"])
        assertEquals(JsonPrimitive("{{city}}"), saved!!.query["q"])
    }
    @Test fun addsResponseFieldWithoutReplacingExistingNestedMappings() {
        var saved: CustomHttpTool? = null
        val nested = Json.parseToJsonElement("""{"metadata":{"total":"/meta/count"}}""").jsonObject
        compose.setContent {
            MaterialTheme {
                HttpToolEditor(tool = CustomHttpTool(name = "fetch", url = "https://example.test", responseMapping = nested),
                    onSave = { saved = it }, onBack = {})
            }
        }
        compose.onNodeWithContentDescription("Add response field").performScrollTo().performClick()
        compose.onNodeWithText("Field").performTextInput("items")
        compose.onNodeWithText("JSON Pointer", substring = false).performTextInput("/data/items")
        compose.onNodeWithText("Save").performClick()
        compose.onNodeWithContentDescription("Save").performClick()
        compose.waitForIdle()
        assertEquals(nested["metadata"], saved!!.responseMapping["metadata"])
        assertEquals(JsonPrimitive("/data/items"), saved!!.responseMapping["items"])
    }

    @Test fun previewsResolvedRequestAndRefreshesWhenSampleChanges() {
        val parameters = Json.parseToJsonElement("""{"type":"object","properties":{"city":{"type":"string"}},"required":["city"]}""").jsonObject
        compose.setContent {
            MaterialTheme {
                HttpToolEditor(tool = CustomHttpTool(name = "weather", url = "https://example.test", parameters = parameters,
                    query = Json.parseToJsonElement("""{"q":"{{city}}"}""").jsonObject), onSave = {}, onBack = {})
            }
        }
        compose.onNodeWithText("Preview request").performScrollTo().performClick()
        compose.onNodeWithTag("http_sample").performScrollTo().performTextReplacement("""{"city":"Hanoi"}""")
        compose.onNodeWithTag("http_preview").performScrollTo().assertTextContains("q=Hanoi", substring = true)
        compose.onNodeWithTag("http_sample").performScrollTo().performTextReplacement("""{"city":"Hue"}""")
        compose.onNodeWithTag("http_preview").performScrollTo().assertTextContains("q=Hue", substring = true)
    }

    @Test fun showsInvalidJsonInlineAndPreventsSaving() {
        var saved = false
        compose.setContent {
            MaterialTheme {
                HttpToolEditor(tool = CustomHttpTool(name = "weather", method = "POST", url = "https://example.test"),
                    onSave = { saved = true }, onBack = {})
            }
        }
        compose.onNodeWithTag("http_body").performScrollTo().performTextReplacement("{broken")
        compose.onNodeWithText("Enter valid JSON. Put parameter tokens inside double quotes.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("Save").performClick()
        compose.runOnIdle { assertFalse(saved) }
    }

}
