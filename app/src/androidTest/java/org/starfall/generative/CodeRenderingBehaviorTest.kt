package org.starfall.generative

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.starfall.generative.database.preferences.AppPreferences
import org.starfall.generative.database.preferences.WordWrapMode
import org.starfall.generative.features.settings.ui.CodeRenderingPreferences
import org.starfall.generative.shared.ui.code.LocalCodeRenderingPreferences
import org.starfall.generative.features.home.chat.ui.markdown.RenderCodeBlock

class CodeRenderingBehaviorTest {
    @get:Rule val compose = createComposeRule()

    @Test fun toolbarFollowsGlobalSettingsAndWrapToggleStaysLocal() {
        var preferences by mutableStateOf(AppPreferences())
        compose.setContent { MaterialTheme {
            CompositionLocalProvider(LocalCodeRenderingPreferences provides preferences) {
                RenderCodeBlock("html", "<p>Hello</p>", 0, "<p>Hello</p>".length, false)
            }
        } }
        compose.onNodeWithContentDescription("Preview code").assertExists()
        compose.onNodeWithContentDescription("Enable wrapping").performClick()
        compose.onNodeWithContentDescription("Disable wrapping").assertExists()
        assertEquals(WordWrapMode.OFF, preferences.wordWrapMode)
        compose.runOnIdle { preferences = preferences.copy(wordWrapMode = WordWrapMode.VIEWPORT) }
        compose.onNodeWithContentDescription("Disable wrapping").assertDoesNotExist()
        compose.onNodeWithContentDescription("Enable wrapping").assertDoesNotExist()
        compose.runOnIdle { preferences = preferences.copy(codePreviewEnabled = false) }
        compose.onNodeWithContentDescription("Preview code").assertDoesNotExist()
    }

    @Test fun columnWrapChangesLayoutAndBoundedFitsViewport() {
        var preferences by mutableStateOf(AppPreferences())
        val code = "0123456789".repeat(12)
        compose.setContent { MaterialTheme {
            CompositionLocalProvider(LocalCodeRenderingPreferences provides preferences) {
                Column(Modifier.width(300.dp)) { RenderCodeBlock("text", code, 0, code.length, false) }
            }
        } }
        fun lineCount(): Int {
            val layouts = mutableListOf<TextLayoutResult>()
            compose.onNodeWithText(code).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            return layouts.single().lineCount
        }
        assertEquals(1, lineCount())
        compose.runOnIdle { preferences = preferences.copy(wordWrapMode = WordWrapMode.VIEWPORT) }
        val viewportLines = lineCount()
        assertTrue(viewportLines > 1)
        compose.runOnIdle { preferences = preferences.copy(wordWrapMode = WordWrapMode.COLUMN, wordWrapColumn = 10) }
        assertTrue(lineCount() > viewportLines)
        compose.runOnIdle { preferences = preferences.copy(wordWrapMode = WordWrapMode.BOUNDED, wordWrapColumn = 1000) }
        assertEquals(viewportLines, lineCount())
    }

    @Test fun columnFieldAppearsForColumnAndBoundedAndRejectsZero() {
        var preferences by mutableStateOf(AppPreferences())
        compose.setContent { MaterialTheme {
            Column {
                CodeRenderingPreferences(preferences, { preferences = preferences.copy(wordWrapMode = it) },
                    { preferences = preferences.copy(wordWrapColumn = it) },
                    { preferences = preferences.copy(codePreviewEnabled = it) },
                    { preferences = preferences.copy(messageFontSize = it) },
                    { preferences = preferences.copy(messageFontFamily = it) })
            }
        } }
        compose.onNodeWithTag("word-wrap-column").assertDoesNotExist()
        compose.onNodeWithTag("word-wrap-mode").performClick()
        compose.onNodeWithText("Wrap by column").performClick()
        compose.onNodeWithTag("word-wrap-column-open").performClick()
        compose.onNodeWithTag("word-wrap-column").performTextReplacement("0")
        compose.onNodeWithText("Save").assertIsNotEnabled()
        assertEquals(80, preferences.wordWrapColumn)
        compose.onNodeWithTag("word-wrap-column").performTextReplacement("120")
        assertEquals(80, preferences.wordWrapColumn)
        compose.onNodeWithText("Close").performClick()
        assertEquals(80, preferences.wordWrapColumn)
        compose.onNodeWithTag("word-wrap-column-open").performClick()
        compose.onNodeWithTag("word-wrap-column").performTextReplacement("120")
        compose.onNodeWithText("Save").performClick()
        assertEquals(120, preferences.wordWrapColumn)
        compose.onNodeWithTag("word-wrap-mode").performClick()
        compose.onNodeWithText("Bounded").performClick()
        compose.onNodeWithTag("word-wrap-column-open").assertExists()
        compose.onNodeWithTag("word-wrap-mode").performClick()
        compose.onNodeWithText("Wrap by viewport").performClick()
        compose.onNodeWithTag("word-wrap-column-open").assertDoesNotExist()
    }
}
