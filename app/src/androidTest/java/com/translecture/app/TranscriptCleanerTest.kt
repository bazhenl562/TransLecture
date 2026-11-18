package com.translecture.app

import org.junit.Assert.assertEquals
import org.junit.Test

class TranscriptCleanerTest {
    @Test
    fun keepsRepeatedTextWhenSuppressionIsDisabled() {
        assertEquals(
            "скорость равна пути",
            TranscriptCleaner.displayText(
                previousText = "Скорость равна пути.",
                nextText = "скорость равна пути",
                suppressRepeats = false
            )
        )
    }

    @Test
    fun removesAnExactRepeatedPhraseIgnoringCaseAndPunctuation() {
        assertEquals(
            "",
            TranscriptCleaner.removeObviousRepeat(
                previousText = "[00:04] Скорость равна пути.",
                nextText = "скорость равна пути"
            )
        )
    }

    @Test
    fun removesLongOverlapButKeepsTheNewWords() {
        assertEquals(
            "и ускорение",
            TranscriptCleaner.removeObviousRepeat(
                previousText = "Скорость равна пути делённому на время",
                nextText = "пути делённому на время и ускорение"
            )
        )
    }

    @Test
    fun keepsShortOverlapThatCouldBeAValidContinuation() {
        assertEquals(
            "закончилась после чего",
            TranscriptCleaner.removeObviousRepeat(
                previousText = "Предыдущая мысль закончилась",
                nextText = "закончилась после чего"
            )
        )
    }
}
