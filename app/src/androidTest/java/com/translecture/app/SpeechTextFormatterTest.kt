package com.translecture.app

import org.junit.Assert.assertEquals
import org.junit.Test

class SpeechTextFormatterTest {
    @Test
    fun formatsRussianCardinalNumbersAsDigits() {
        assertEquals(
            "В лекции было 1256 примеров и 23 задания.",
            SpeechTextFormatter.format(
                "В лекции было одна тысяча двести пятьдесят шесть примеров и двадцать три задания."
            )
        )
    }

    @Test
    fun formatsCommonSpokenAndWrittenAbbreviations() {
        assertEquals(
            "РФ, МГУ, ДНК и СССР.",
            SpeechTextFormatter.format("эр эф, эм гэ у, дэ эн ка и эс эс эс эр.")
        )
        assertEquals("ГОСТ", SpeechTextFormatter.format("гост"))
    }

    @Test
    fun leavesWordsContainingNumberSyllablesUnchanged() {
        assertEquals(
            "Однако трижды, без изменений.",
            SpeechTextFormatter.format("Однако трижды, без изменений.")
        )
    }
}
