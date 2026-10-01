package com.example.speakflow.speech

object RetryEvaluator {
    data class Result(val text: String, val matched: List<Boolean>)
    fun remaining(expected: String, confirmed: List<Boolean>): String =
        SpeechScorer.displayWords(expected).filterIndexed { i, _ -> !confirmed.getOrElse(i) { false } }.joinToString(" ")

    fun evaluate(expected: String, candidates: List<String>, confirmed: List<Boolean>): Result {
        val words = SpeechScorer.displayWords(expected)
        val baseline = words.indices.map { confirmed.getOrElse(it) { false } }
        val missing = words.indices.filter { !baseline[it] }
        val target = missing.joinToString(" ") { words[it] }
        return candidates.filter(String::isNotBlank).take(8).map { text ->
            val whole = SpeechScorer.matchedWords(expected, text)
            val focused = SpeechScorer.matchedWords(target, text)
            val focusedByIndex = missing.zip(focused).toMap()
            val wholeMatches = words.indices.map { baseline[it] || whole.getOrElse(it) { false } }
            val focusedMatches = words.indices.map { baseline[it] || focusedByIndex[it] == true }
            Result(text, if (focusedMatches.count { it } > wholeMatches.count { it }) focusedMatches else wholeMatches)
        }.maxWithOrNull(compareBy<Result> { it.matched.count { match -> match } }
            .thenBy { SpeechScorer.score(expected, it.text) }) ?: Result("", baseline)
    }
}
