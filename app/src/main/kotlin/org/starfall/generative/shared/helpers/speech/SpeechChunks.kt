package org.starfall.generative.shared.helpers.speech

import kotlin.math.abs

private val speechWord = Regex("\\S+")
private val sentenceEnding = Regex("""[.!?。！？]+["'”’»)\]]*$""")

/**
 * Target a number of words per API request, rounding to the closest sentence ending
 * within half the target length. Long sentences still split at the target size.
 * Apply the provider's character limit separately.
 */
internal fun speechChunks(text: String, maxWordsPerChunk: Int = 20, maxCharacters: Int = 4_000): List<String> {
    require(maxWordsPerChunk > 0)
    require(maxCharacters >= 2)
    val words = speechWord.findAll(text).map { it.value }.toList()
    if (words.isEmpty()) return emptyList()

    val groups = buildList {
        var start = 0
        val roundingRange = (maxWordsPerChunk / 2).coerceAtLeast(1)
        while (start < words.size) {
            val target = start + maxWordsPerChunk.coerceAtMost(words.size - start)
            val end = if (target == words.size) target else {
                ((target - roundingRange).coerceAtLeast(start + 1)..
                    (target + roundingRange).coerceAtMost(words.size))
                    .filter { sentenceEnding.containsMatchIn(words[it - 1]) }
                    .minWithOrNull(compareBy<Int> { abs(it - target) }.thenBy { it })
                    ?: target
            }
            add(words.subList(start, end).joinToString(" "))
            start = end
        }
    }

    return groups.flatMap { group ->
        buildList {
            var remaining = group
            while (remaining.length > maxCharacters) {
                var split = remaining.lastIndexOf(' ', maxCharacters).takeIf { it > maxCharacters / 2 } ?: maxCharacters
                if (Character.isHighSurrogate(remaining[split - 1]) && Character.isLowSurrogate(remaining[split])) split--
                add(remaining.substring(0, split).trim())
                remaining = remaining.substring(split).trimStart()
            }
            if (remaining.isNotEmpty()) add(remaining)
        }
    }
}
