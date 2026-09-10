package com.example.speakflow.speech

import java.util.Locale

object SpeechScorer {
    fun matchedWords(expected: String, actual: String): List<Boolean> {
        val expectedWords = displayWords(expected)
        val spokenWords = normalize(actual).split(' ').filter(String::isNotBlank)
        val normalizedExpected = expectedWords.map(::normalize)
        val matched = MutableList(expectedWords.size) { false }
        var searchFrom = 0
        spokenWords.forEach { spoken ->
            var bestIndex = -1
            var bestScore = 0
            for (index in searchFrom until normalizedExpected.size) {
                val candidateScore = similarity(normalizedExpected[index], spoken)
                if (candidateScore > bestScore) {
                    bestScore = candidateScore
                    bestIndex = index
                }
                if (candidateScore == 100) break
            }
            if (bestIndex >= 0 && bestScore >= 72) {
                matched[bestIndex] = true
                searchFrom = bestIndex + 1
            }
        }
        return matched
    }

    fun displayWords(value: String): List<String> = value.trim().split(Regex("\\s+")).filter(String::isNotBlank)

    fun score(expected: String, actual: String): Int {
        val left = normalize(expected)
        val right = normalize(actual)
        if (left.isEmpty() && right.isEmpty()) return 100
        if (left.isEmpty() || right.isEmpty()) return 0
        val characterScore = similarity(left, right)
        val expectedWords = left.split(' ').filter(String::isNotBlank)
        val actualWords = right.split(' ').filter(String::isNotBlank)
        val wordScore = similarity(expectedWords, actualWords)
        val overlap = expectedWords.toSet().intersect(actualWords.toSet()).size
        val coverageScore = if (expectedWords.isEmpty()) 0 else overlap * 100 / expectedWords.toSet().size
        return (maxOf(characterScore, wordScore, coverageScore) + 6).coerceAtMost(100)
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
