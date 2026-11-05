package org.starfall.generative

import org.junit.Assert.*
import org.junit.Test
import org.starfall.generative.shared.helpers.speech.speechChunks
import org.starfall.generative.shared.helpers.speech.speechText

class SpeechChunksTest {
    @Test fun symbolsAndColonsAreNotSentToSpeechButAccentsAndSentenceBoundariesRemain() {
        assertEquals("Lưu ý Giá 12.5, rất tốt!", speechText("Lưu ý: Giá 12.5, rất tốt! ✅ ✨"))
        assertEquals("Résumé café tiếng Việt rõ ràng", speechText("Résumé: café ✅ — tiếng Việt ✨ **rõ ràng**"))
        assertEquals("", speechText(": ★ ✅ !!!"))
        assertEquals("你好。世界！", speechText("你好。世界！"))
    }

    @Test fun defaultIsTwentyWordsAndLongSentencesSplitAtTheWordTarget() {
        val words = (1..43).map { "word$it" }
        val chunks = speechChunks(words.joinToString(" "))
        assertEquals(listOf(20, 20, 3), chunks.map { it.split(' ').size })
        assertEquals(words.joinToString(" "), chunks.joinToString(" "))
        assertThrows(IllegalArgumentException::class.java) { speechChunks("Text.", maxWordsPerChunk = 0) }
    }

    @Test fun roundsDownOrUpToTheNearestSentenceEnding() {
        val chunks = speechChunks(
            "one two three four. five six seven eight nine ten? eleven twelve thirteen fourteen fifteen sixteen.",
            maxWordsPerChunk = 5
        )
        assertEquals(
            listOf("one two three four.", "five six seven eight nine ten?", "eleven twelve thirteen fourteen fifteen sixteen."),
            chunks
        )
        // When both directions are equally close, prefer the earlier sentence ending.
        assertEquals(listOf("one two three four.", "five six."), speechChunks(
            "one two three four. five six.", maxWordsPerChunk = 5
        ))
    }

    @Test fun exclamationAndQuestionMarksAreBoundariesButDecimalsAreNot() {
        assertEquals(listOf("Value 12.5 is good.", "Next question? Done!"),
            speechChunks("Value 12.5 is good.\nNext question? Done!", maxWordsPerChunk = 3))
        assertEquals(listOf("One two three.", "Four five!", "Six seven?", "Eight nine。", "Ten eleven！"),
            speechChunks("One two three. Four five! Six seven? Eight nine。 Ten eleven！", maxWordsPerChunk = 3))
        assertEquals(listOf("alpha beta", "gamma delta", "epsilon zeta"),
            speechChunks("alpha\nbeta\ngamma\ndelta\nepsilon\nzeta", maxWordsPerChunk = 2))
    }

    @Test fun longChunksRespectCharacterLimitAndUnicodeBoundaries() {
        val text = ("Tiếng Việt rất rõ ràng. ").repeat(400).trim()
        val chunks = speechChunks(text, maxWordsPerChunk = 1_000, maxCharacters = 200)
        assertTrue(chunks.all { it.length <= 200 })
        assertEquals(text, chunks.joinToString(" "))
        val surrogateText = "a" + "\uD835\uDC00".repeat(20)
        val pieces = speechChunks(surrogateText, maxCharacters = 10)
        assertEquals(surrogateText, pieces.joinToString(""))
        assertTrue(pieces.none { Character.isLowSurrogate(it.first()) || Character.isHighSurrogate(it.last()) })
        assertTrue(speechChunks(" ").isEmpty())
    }
}
