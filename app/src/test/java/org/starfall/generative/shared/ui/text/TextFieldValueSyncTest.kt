package org.starfall.generative.shared.ui.text
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.starfall.generative.shared.ui.inputs.withExternalText

class TextFieldValueSyncTest {
    @Test
    fun parentEchoKeepsImeComposition() {
        val composing = TextFieldValue(
            text = "translate",
            selection = TextRange(9),
            composition = TextRange(0, 9)
        )

        val synced = composing.withExternalText("translate")

        assertSame(composing, synced)
        assertEquals(TextRange(0, 9), synced.composition)
    }

    @Test
    fun genuineExternalChangeClearsCompositionAndClampsSelection() {
        val composing = TextFieldValue(
            text = "translate",
            selection = TextRange(9),
            composition = TextRange(0, 9)
        )

        val synced = composing.withExternalText("new")

        assertEquals("new", synced.text)
        assertEquals(TextRange(3), synced.selection)
        assertNull(synced.composition)
    }
}
