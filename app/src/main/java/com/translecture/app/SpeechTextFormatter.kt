package com.translecture.app

import java.util.Locale

internal object SpeechTextFormatter {
    private data class NumberWord(val value: Int, val order: Int)

    private val numberWords = buildMap {
        addNumberWords(1, 1, "один", "одна", "одно", "одну", "одного", "одной")
        addNumberWords(2, 1, "два", "две", "двух", "двум", "двумя")
        addNumberWords(3, 1, "три", "трёх", "трех", "трем", "тремя")
        addNumberWords(4, 1, "четыре", "четырёх", "четырех", "четырем", "четырьмя")
        addNumberWords(5, 1, "пять", "пяти", "пятью")
        addNumberWords(6, 1, "шесть", "шести", "шестью")
        addNumberWords(7, 1, "семь", "семи", "семью")
        addNumberWords(8, 1, "восемь", "восьми", "восемью")
        addNumberWords(9, 1, "девять", "девяти", "девятью")
        addNumberWords(0, 1, "ноль", "нуль")
        addNumberWords(10, 1, "десять", "десяти")
        addNumberWords(11, 1, "одиннадцать", "одиннадцати")
        addNumberWords(12, 1, "двенадцать", "двенадцати")
        addNumberWords(13, 1, "тринадцать", "тринадцати")
        addNumberWords(14, 1, "четырнадцать", "четырнадцати")
        addNumberWords(15, 1, "пятнадцать", "пятнадцати")
        addNumberWords(16, 1, "шестнадцать", "шестнадцати")
        addNumberWords(17, 1, "семнадцать", "семнадцати")
        addNumberWords(18, 1, "восемнадцать", "восемнадцати")
        addNumberWords(19, 1, "девятнадцать", "девятнадцати")
        addNumberWords(20, 2, "двадцать", "двадцати")
        addNumberWords(30, 2, "тридцать", "тридцати")
        addNumberWords(40, 2, "сорок")
        addNumberWords(50, 2, "пятьдесят", "пятидесяти")
        addNumberWords(60, 2, "шестьдесят", "шестидесяти")
        addNumberWords(70, 2, "семьдесят", "семидесяти")
        addNumberWords(80, 2, "восемьдесят", "восьмидесяти")
        addNumberWords(90, 2, "девяносто", "девяноста")
        addNumberWords(100, 3, "сто")
        addNumberWords(200, 3, "двести")
        addNumberWords(300, 3, "триста")
        addNumberWords(400, 3, "четыреста")
        addNumberWords(500, 3, "пятьсот")
        addNumberWords(600, 3, "шестьсот")
        addNumberWords(700, 3, "семьсот")
        addNumberWords(800, 3, "восемьсот")
        addNumberWords(900, 3, "девятьсот")
        listOf("тысяча", "тысячи", "тысяч").forEach { put(it, NumberWord(1, THOUSAND_ORDER)) }
    }

    private val abbreviationPatterns = listOf(
        abbreviationPattern("СССР", "эс эс эс эр", "ссср"),
        abbreviationPattern("США", "эс ша а", "сша"),
        abbreviationPattern("МГУ", "эм гэ у", "мгу"),
        abbreviationPattern("МВД", "эм вэ дэ", "мвд"),
        abbreviationPattern("НДС", "эн дэ эс", "ндс"),
        abbreviationPattern("ДНК", "дэ эн ка", "днк"),
        abbreviationPattern("ФСБ", "эф эс бэ", "фсб"),
        abbreviationPattern("ГОСТ", "гэ о эс тэ", "гост"),
        abbreviationPattern("ВВП", "вэ вэ пэ", "ввп"),
        abbreviationPattern("РФ", "эр эф", "рф"),
        abbreviationPattern("API", "эй пи ай", "api")
    )

    private val wordPattern = Regex("[\\p{L}]+")

    fun format(text: String): String {
        var formattedText = text
        abbreviationPatterns.forEach { (pattern, replacement) ->
            formattedText = pattern.replace(formattedText, replacement)
        }

        val words = wordPattern.findAll(formattedText).toList()
        val result = StringBuilder(formattedText.length)
        var textPosition = 0
        var wordIndex = 0

        while (wordIndex < words.size) {
            val firstWord = words[wordIndex]
            if (numberWords[firstWord.value.toNumberKey()] == null) {
                result.append(formattedText, textPosition, firstWord.range.last + 1)
                textPosition = firstWord.range.last + 1
                wordIndex++
                continue
            }

            var candidateEnd = wordIndex
            while (candidateEnd + 1 < words.size) {
                val nextWord = words[candidateEnd + 1]
                val separator = formattedText.substring(
                    words[candidateEnd].range.last + 1,
                    nextWord.range.first
                )
                if (!separator.all(Char::isWhitespace) ||
                    numberWords[nextWord.value.toNumberKey()] == null
                ) {
                    break
                }
                candidateEnd++
            }

            val parsedNumber = (candidateEnd downTo wordIndex).firstNotNullOfOrNull { endIndex ->
                parseNumber(words.subList(wordIndex, endIndex + 1).map { it.value.toNumberKey() })
                    ?.let { endIndex to it }
            }

            if (parsedNumber == null) {
                result.append(formattedText, textPosition, firstWord.range.last + 1)
                textPosition = firstWord.range.last + 1
                wordIndex++
            } else {
                val (endIndex, number) = parsedNumber
                result.append(formattedText, textPosition, firstWord.range.first)
                result.append(number)
                textPosition = words[endIndex].range.last + 1
                wordIndex = endIndex + 1
            }
        }

        result.append(formattedText, textPosition, formattedText.length)
        return result.toString()
    }

    private fun parseNumber(words: List<String>): Long? {
        var total = 0L
        var group = 0L
        var previousOrder = Int.MAX_VALUE
        var usedThousand = false
        var sawZero = false

        for (word in words) {
            val numberWord = numberWords[word] ?: return null
            if (numberWord.order == THOUSAND_ORDER) {
                if (usedThousand || sawZero) return null
                total += (group.takeIf { it > 0 } ?: 1L) * 1_000L
                group = 0
                previousOrder = Int.MAX_VALUE
                usedThousand = true
                continue
            }

            if (numberWord.value == 0) {
                if (words.size != 1) return null
                sawZero = true
            } else if (numberWord.order >= previousOrder || sawZero) {
                return null
            }

            group += numberWord.value
            previousOrder = numberWord.order
        }

        return (total + group).takeIf { it <= MAX_NUMBER }
    }

    private fun MutableMap<String, NumberWord>.addNumberWords(
        value: Int,
        order: Int,
        vararg words: String
    ) {
        words.forEach { put(it, NumberWord(value, order)) }
    }

    private fun abbreviationPattern(
        replacement: String,
        spokenForm: String,
        writtenForm: String
    ): Pair<Regex, String> {
        val spokenPattern = spokenForm.split(' ').joinToString("(?:\\s+|[-–—])")
        val pattern = Regex(
            "(?<![\\p{L}\\p{N}])(?:$spokenPattern|$writtenForm)(?![\\p{L}\\p{N}])",
            RegexOption.IGNORE_CASE
        )
        return pattern to replacement
    }

    private fun String.toNumberKey(): String = lowercase(Locale.ROOT).replace('ё', 'е')

    private const val THOUSAND_ORDER = 4
    private const val MAX_NUMBER = 999_999L
}
