package com.example.speakflow.speech

import com.example.speakflow.model.RecognitionStrictness

object RetryEvaluator {
    data class Result(val text: String, val matched: List<Boolean>)
    fun remaining(expected: String, confirmed: List<Boolean>): String =
        SpeechScorer.displayWords(expected).filterIndexed { i, _ -> !confirmed.getOrElse(i) { false } }.joinToString(" ")

    /**
     * EASY looks through every alternative the recognizer offered and keeps the one
     * closest to the expected sentence. That is what made scoring feel generous:
     * a low-ranked alternative often contains the expected words even when the
     * speaker said something else. NORMAL/STRICT only trust the recognizer's top
     * hypothesis, which is what was actually heard.
     */
    fun evaluate(
        expected: String,
        candidates: List<String>,
        confirmed: List<Boolean>,
        strictness: RecognitionStrictness = RecognitionStrictness.NORMAL
    ): Result {
        val words = SpeechScorer.displayWords(expected)
        // STRICT never carries words over from an earlier fragment: the whole
        // sentence has to come out of a single utterance.
        val baseline = if (strictness == RecognitionStrictness.STRICT) List(words.size) { false }
            else words.indices.map { confirmed.getOrElse(it) { false } }
        val missing = words.indices.filter { !baseline[it] }
        val target = missing.joinToString(" ") { words[it] }
        val candidateLimit = if (strictness == RecognitionStrictness.EASY) 8 else 1
        return candidates.filter(String::isNotBlank).take(candidateLimit).map { text ->
            val whole = SpeechScorer.matchedWords(expected, text, strictness)
            val wholeMatches = words.indices.map { baseline[it] || whole.getOrElse(it) { false } }
            if (strictness == RecognitionStrictness.STRICT) return@map Result(text, wholeMatches)
            val focused = SpeechScorer.matchedWords(target, text, strictness)
            val focusedByIndex = missing.zip(focused).toMap()
            val focusedMatches = words.indices.map { baseline[it] || focusedByIndex[it] == true }
            Result(text, if (focusedMatches.count { it } > wholeMatches.count { it }) focusedMatches else wholeMatches)
        }.maxWithOrNull(compareBy<Result> { it.matched.count { match -> match } }
            .thenBy { SpeechScorer.score(expected, it.text, strictness) }) ?: Result("", baseline)
    }
}
