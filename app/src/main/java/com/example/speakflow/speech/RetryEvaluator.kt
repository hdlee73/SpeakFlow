package com.example.speakflow.speech

import com.example.speakflow.model.RecognitionStrictness

object RetryEvaluator {
    data class Result(val text: String, val matched: List<Boolean>)
    fun remaining(expected: String, confirmed: List<Boolean>): String =
        SpeechScorer.displayWords(expected).filterIndexed { i, _ -> !confirmed.getOrElse(i) { false } }.joinToString(" ")

    /**
     * How many of the recognizer's ranked alternatives are considered. In noise
     * (especially through a Bluetooth headset mic) the top hypothesis is often
     * garbage while the next ones contain what was said, so NORMAL looks at the top
     * three. EASY looks at all eight; STRICT trusts only the top hypothesis, which
     * is what the recognizer actually believes it heard.
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
        val candidateLimit = when (strictness) {
            RecognitionStrictness.EASY -> 8
            RecognitionStrictness.NORMAL -> 3
            RecognitionStrictness.STRICT -> 1
        }
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
