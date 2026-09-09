package com.example.speakflow.speech

import java.util.Locale

object SpeechScorer {
    fun score(expected: String, actual: String): Int {
        val left = normalize(expected)
        val right = normalize(actual)
        if (left.isEmpty() && right.isEmpty()) return 100
        if (left.isEmpty() || right.isEmpty()) return 0
        val distance = levenshtein(left, right)
        return ((1.0 - distance.toDouble() / maxOf(left.length, right.length)) * 100)
            .toInt().coerceIn(0, 100)
    }

    private fun normalize(value: String): String = value
        .lowercase(Locale.US)
        .replace(Regex("[^a-z0-9']+"), " ")
        .trim()
        .replace(Regex("\\s+"), " ")

    private fun levenshtein(a: String, b: String): Int {
        var previous = IntArray(b.length + 1) { it }
        a.forEachIndexed { i, ac ->
            val current = IntArray(b.length + 1)
            current[0] = i + 1
            b.forEachIndexed { j, bc ->
                current[j + 1] = minOf(current[j] + 1, previous[j + 1] + 1, previous[j] + if (ac == bc) 0 else 1)
            }
            previous = current
        }
        return previous[b.length]
    }
}
