package com.example.speakflow.speech

import java.util.Locale

object SpeechScorer {
    data class Evaluation(val score: Int, val matchedDisplayWords: List<Boolean>)

    fun matchedWords(expected: String, actual: String): List<Boolean> {
        return evaluate(expected, actual).matchedDisplayWords
    }

    fun displayWords(value: String): List<String> = value.trim().split(Regex("\\s+")).filter(String::isNotBlank)

    fun score(expected: String, actual: String): Int = evaluate(expected, actual).score

    fun evaluate(expected: String, actual: String): Evaluation {
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
        val alignment = align(expectedTokens, actualTokens)
        val matchedByDisplay = display.indices.map { displayIndex ->
            val tokenIndexes = expectedTokens.indices.filter { expectedTokens[it].displayIndex == displayIndex }
            tokenIndexes.isNotEmpty() && tokenIndexes.all { alignment.matchedExpected[it] }
        }

        val characterScore = similarity(left, right)
        val coverageScore = if (expectedTokens.isEmpty()) 0 else alignment.similaritySum / expectedTokens.size
        val lengthPenalty = if (actualTokens.size <= expectedTokens.size) 100
            else (expectedTokens.size * 100 / actualTokens.size).coerceAtLeast(0)
        val alignedScore = coverageScore * lengthPenalty / 100
        val score = (maxOf(characterScore, alignedScore) + 8).coerceAtMost(100)
        return Evaluation(score, matchedByDisplay)
    }

    private data class Token(val value: String, val displayIndex: Int)
    private data class Alignment(val matchedExpected: BooleanArray, val similaritySum: Int)

    private fun align(expected: List<Token>, actual: List<String>): Alignment {
        val rows = expected.size + 1
        val columns = actual.size + 1
        val scores = Array(rows) { IntArray(columns) }
        val directions = Array(rows) { ByteArray(columns) }
        for (i in 1 until rows) {
            for (j in 1 until columns) {
                val wordSimilarity = similarity(expected[i - 1].value, actual[j - 1])
                val match = if (wordSimilarity >= 65) scores[i - 1][j - 1] + wordSimilarity else Int.MIN_VALUE
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
                    similaritySum += similarity(expected[i - 1].value, actual[j - 1])
                    i--; j--
                }
                2 -> i--
                else -> j--
            }
        }
        return Alignment(matched, similaritySum)
    }

    private fun normalize(value: String): String = value
        .lowercase(Locale.US)
        .replace("’", "'")
        .replace(Regex("\\b(i'm)\\b"), "i am")
        .replace(Regex("\\b(you're)\\b"), "you are")
        .replace(Regex("\\b(it'll)\\b"), "it will")
        .replace(Regex("\\b(can't)\\b"), "cannot")
        .replace(Regex("\\b(won't)\\b"), "will not")
        .replace(Regex("n't\\b"), " not")
        .replace(Regex("'re\\b"), " are")
        .replace(Regex("'ll\\b"), " will")
        .replace(Regex("'ve\\b"), " have")
        .replace(Regex("[^a-z0-9']+"), " ")
        .trim()
        .replace(Regex("\\s+"), " ")

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
