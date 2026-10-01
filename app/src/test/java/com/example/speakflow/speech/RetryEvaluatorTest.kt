package com.example.speakflow.speech

import org.junit.Assert.*
import org.junit.Test

class RetryEvaluatorTest {
    @Test fun shortCorrectionCompletesPreviouslyMatchedSentence() {
        val expected = "I'm expecting an annual salary of sixty million won."
        val first = RetryEvaluator.evaluate(expected, listOf("I'm expecting an annual salary of fifty million won"), emptyList())
        // Focus on the missing word regardless of the old whole-sentence score.
        val confirmed = SpeechScorer.displayWords(expected).map { it.trim('.', ',') != "sixty" }
        val corrected = RetryEvaluator.evaluate(expected, listOf("sixty"), confirmed)
        assertTrue(corrected.matched.all { it })
        assertEquals("sixty", corrected.text)
    }
    @Test fun repeatedWordCorrectionTargetsRemainingOccurrence() {
        val result = RetryEvaluator.evaluate("go and go", listOf("go"), listOf(true, true, false))
        assertTrue(result.matched.all { it })
    }
    @Test fun emptyCallbackDoesNotEraseConfirmedWords() {
        val result = RetryEvaluator.evaluate("please call me", emptyList(), listOf(true, false, false))
        assertEquals(listOf(true, false, false), result.matched)
    }
    @Test fun newFragmentCannotBeDiscardedForOldWholeSentenceScore() {
        val result = RetryEvaluator.evaluate("Please check the receipt", listOf("receipt", "please check"), listOf(true, true, true, false))
        assertEquals("receipt", result.text)
        assertTrue(result.matched.all { it })
    }
}
