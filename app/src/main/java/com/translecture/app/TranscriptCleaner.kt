package com.translecture.app

import java.util.Locale

internal object TranscriptCleaner {
    private val wordPattern = Regex("[\\p{L}\\p{N}]+")

    fun displayText(previousText: String, nextText: String, suppressRepeats: Boolean): String =
        if (suppressRepeats) removeObviousRepeat(previousText, nextText) else nextText.trim()

    fun removeObviousRepeat(previousText: String, nextText: String): String {
        val previousWords = wordPattern.findAll(previousText)
            .map { it.value.lowercase(Locale.ROOT) }
            .toList()
            .takeLast(MAX_CONTEXT_WORDS)
        val nextMatches = wordPattern.findAll(nextText).toList()
        val nextWords = nextMatches.map { it.value.lowercase(Locale.ROOT) }
        if (previousWords.isEmpty() || nextWords.isEmpty()) return nextText.trim()

        val maxOverlap = minOf(previousWords.size, nextWords.size)
        val overlap = (maxOverlap downTo 1).firstOrNull { count ->
            previousWords.takeLast(count) == nextWords.take(count)
        } ?: return nextText.trim()

        if (overlap == nextWords.size) return ""
        if (overlap < MIN_PARTIAL_OVERLAP_WORDS ||
            overlap * 100 < nextWords.size * MIN_PARTIAL_OVERLAP_PERCENT
        ) {
            return nextText.trim()
        }

        val afterRepeatedText = nextMatches[overlap - 1].range.last + 1
        return nextText.substring(afterRepeatedText)
            .trimStart { character ->
                character.isWhitespace() || character in LEADING_PUNCTUATION
            }
    }

    private const val MAX_CONTEXT_WORDS = 24
    private const val MIN_PARTIAL_OVERLAP_WORDS = 3
    private const val MIN_PARTIAL_OVERLAP_PERCENT = 60
    private const val LEADING_PUNCTUATION = ",.;:!?—-…"
}
