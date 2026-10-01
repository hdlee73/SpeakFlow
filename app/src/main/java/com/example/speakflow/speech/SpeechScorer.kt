package com.example.speakflow.speech

import com.example.speakflow.model.RecognitionStrictness
import java.util.Locale

object SpeechScorer {
    data class Evaluation(val score: Int, val matchedDisplayWords: List<Boolean>)

    /** Word similarity (0-100) required before a recognized word counts in EASY mode. */
    private const val EASY_WORD_SIMILARITY = 65

    fun matchedWords(
        expected: String,
        actual: String,
        strictness: RecognitionStrictness = RecognitionStrictness.NORMAL
    ): List<Boolean> = evaluate(expected, actual, strictness).matchedDisplayWords

    fun unmatchedText(
        expected: String,
        actual: String,
        strictness: RecognitionStrictness = RecognitionStrictness.NORMAL
    ): String {
        val words = displayWords(expected)
        val matched = matchedWords(expected, actual, strictness)
        return words.filterIndexed { index, _ -> !matched.getOrElse(index) { false } }.joinToString(" ")
    }

    fun displayWords(value: String): List<String> = value.trim().split(Regex("\\s+")).filter(String::isNotBlank)

    fun score(
        expected: String,
        actual: String,
        strictness: RecognitionStrictness = RecognitionStrictness.NORMAL
    ): Int = evaluate(expected, actual, strictness).score

    fun evaluate(
        expected: String,
        actual: String,
        strictness: RecognitionStrictness = RecognitionStrictness.NORMAL
    ): Evaluation {
        val left = normalize(expected)
        val right = normalize(actual)
        val display = displayWords(expected)
        if (left.isEmpty() && right.isEmpty()) return Evaluation(100, List(display.size) { true })
        if (left.isEmpty() || right.isEmpty()) return Evaluation(0, List(display.size) { false })

        // Normalize each displayed word separately so recognizer expansions such as
        // "I'm" -> "I am" still colour the original displayed word correctly.
        val expectedTokens = display.flatMapIndexed { displayIndex, word ->
            normalize(word).split(' ').filter(String::isNotBlank).map { Token(it, displayIndex) }
        }
        val actualTokens = right.split(' ').filter(String::isNotBlank)
        val alignment = align(expectedTokens, actualTokens, strictness)
        val matchedByDisplay = display.indices.map { displayIndex ->
            val tokenIndexes = expectedTokens.indices.filter { expectedTokens[it].displayIndex == displayIndex }
            tokenIndexes.isNotEmpty() && tokenIndexes.all { alignment.matchedExpected[it] }
        }

        val coverageScore = if (expectedTokens.isEmpty()) 0 else alignment.similaritySum / expectedTokens.size
        val lengthPenalty = if (actualTokens.size <= expectedTokens.size) 100
            else (expectedTokens.size * 100 / actualTokens.size).coerceAtLeast(0)
        val alignedScore = coverageScore * lengthPenalty / 100
        val score = if (strictness == RecognitionStrictness.EASY) {
            (maxOf(similarity(left, right), alignedScore) + 8).coerceAtMost(100)
        } else alignedScore.coerceIn(0, 100)
        return Evaluation(score, matchedByDisplay)
    }

    private data class Token(val value: String, val displayIndex: Int)
    private data class Alignment(val matchedExpected: BooleanArray, val similaritySum: Int)

    /** 0 when the words must not be paired, otherwise their similarity (100 = identical). */
    private fun wordMatch(expected: String, actual: String, strictness: RecognitionStrictness): Int {
        if (expected == actual) return 100
        if (strictness != RecognitionStrictness.EASY) return 0
        val value = similarity(expected, actual)
        return if (value >= EASY_WORD_SIMILARITY) value else 0
    }

    private fun align(expected: List<Token>, actual: List<String>, strictness: RecognitionStrictness): Alignment {
        val rows = expected.size + 1
        val columns = actual.size + 1
        val scores = Array(rows) { IntArray(columns) }
        val directions = Array(rows) { ByteArray(columns) }
        for (i in 1 until rows) {
            for (j in 1 until columns) {
                val wordSimilarity = wordMatch(expected[i - 1].value, actual[j - 1], strictness)
                val match = if (wordSimilarity > 0) scores[i - 1][j - 1] + wordSimilarity else Int.MIN_VALUE
                val skipExpected = scores[i - 1][j]
                val skipActual = scores[i][j - 1]
                when {
                    match >= skipExpected && match >= skipActual -> { scores[i][j] = match; directions[i][j] = 1 }
                    skipExpected >= skipActual -> { scores[i][j] = skipExpected; directions[i][j] = 2 }
                    else -> { scores[i][j] = skipActual; directions[i][j] = 3 }
                }
            }
        }
        val matched = BooleanArray(expected.size)
        var similaritySum = 0
        var i = expected.size
        var j = actual.size
        while (i > 0 && j > 0) {
            when (directions[i][j].toInt()) {
                1 -> {
                    matched[i - 1] = true
                    similaritySum += wordMatch(expected[i - 1].value, actual[j - 1], strictness)
                    i--; j--
                }
                2 -> i--
                else -> j--
            }
        }
        return Alignment(matched, similaritySum)
    }

    private val numberWords = listOf(
        "zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten",
        "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen"
    )
    private val tensWords = listOf("", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety")

    private fun spellNumber(value: Int): String = when {
        value < 20 -> numberWords[value]
        value % 10 == 0 -> tensWords[value / 10]
        else -> "${tensWords[value / 10]} ${numberWords[value % 10]}"
    }

    /**
     * Spellings that the recognizer may legitimately produce for a correctly spoken
     * word. Both sides are mapped to the same canonical token, so this does not make
     * mispronunciations pass — it only removes formatting differences.
     */
    private val canonicalWords = mapOf(
        "okay" to "ok",
        "too" to "to", "two" to "to",
        "their" to "there",
        "alright" to "all right",
        "gonna" to "going to", "wanna" to "want to", "gotta" to "got to",
        "mr" to "mister", "mrs" to "missus", "ms" to "miss", "dr" to "doctor"
    )

    private fun normalize(value: String): String {
        var text = value
            .lowercase(Locale.US)
            .replace("’", "'")
            .replace("‘", "'")
            .replace("%", " percent")
            // "p.m." / "a.m." / "u.s." -> "pm" / "am" / "us"
            .replace(Regex("\\b([a-z])\\.([a-z])\\.?")) { "${it.groupValues[1]}${it.groupValues[2]}" }
            .replace(Regex("\\b(i'm)\\b"), "i am")
            .replace(Regex("\\b(you're)\\b"), "you are")
            .replace(Regex("\\b(it'll)\\b"), "it will")
            .replace(Regex("\\b(can't)\\b"), "cannot")
            .replace(Regex("\\b(won't)\\b"), "will not")
            .replace(Regex("n't\\b"), " not")
            .replace(Regex("'re\\b"), " are")
            .replace(Regex("'ll\\b"), " will")
            .replace(Regex("'ve\\b"), " have")
            .replace(Regex("'d\\b"), " would")
            // Thousands separators: 1,000 -> 1000
            .replace(Regex("(?<=\\d),(?=\\d{3})"), "")
            .replace(Regex("[^a-z0-9']+"), " ")
            // Apostrophes left over (possessives, "it's") are not audible.
            .replace("'", "")
            .trim()
        text = text.replace(Regex("\\b(\\d{1,2})000\\b")) { "${spellNumber(it.groupValues[1].toInt())} thousand" }
        text = text.replace(Regex("\\b(\\d{1,2})00\\b")) { "${spellNumber(it.groupValues[1].toInt())} hundred" }
        text = text.replace(Regex("\\b(\\d{1,2})\\b")) { spellNumber(it.groupValues[1].toInt()) }
        text = text.split(Regex("\\s+")).filter(String::isNotBlank)
            .joinToString(" ") { word -> britishToAmerican(canonicalWords[word] ?: word) }
        return text.replace(Regex("\\s+"), " ").trim()
    }

    /** Applied to both sides, so it only unifies spelling variants such as colour/color. */
    private fun britishToAmerican(raw: String): String {
        // colour/favourite/behaviour -> color/favorite/behavior (both sides, so "hours"
        // becoming "hors" is harmless).
        val word = if (raw.length > 4) raw.replace("our", "or") else raw
        return when {
        word.length > 5 && word.endsWith("ise") -> word.dropLast(3) + "ize"
        word.length > 6 && word.endsWith("ised") -> word.dropLast(4) + "ized"
        word.length > 7 && word.endsWith("ising") -> word.dropLast(5) + "izing"
        word.length > 4 && word.endsWith("tre") -> word.dropLast(3) + "ter"
        else -> word
        }
    }

    private fun <T> similarity(a: List<T>, b: List<T>): Int {
        if (a.isEmpty() || b.isEmpty()) return 0
        return ((1.0 - levenshtein(a, b).toDouble() / maxOf(a.size, b.size)) * 100).toInt().coerceIn(0, 100)
    }

    private fun similarity(a: String, b: String): Int = similarity(a.toList(), b.toList())

    private fun <T> levenshtein(a: List<T>, b: List<T>): Int {
        var previous = IntArray(b.size + 1) { it }
        a.forEachIndexed { i, ac ->
            val current = IntArray(b.size + 1)
            current[0] = i + 1
            b.forEachIndexed { j, bc ->
                current[j + 1] = minOf(current[j] + 1, previous[j + 1] + 1, previous[j] + if (ac == bc) 0 else 1)
            }
            previous = current
        }
        return previous[b.size]
    }
}
