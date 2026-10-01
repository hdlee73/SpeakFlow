package com.example.speakflow.speech

import com.example.speakflow.model.RecognitionStrictness
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StrictnessTest {
    private val sentence = "My missing keys turned up in my jacket pocket."

    @Test fun similarButDifferentWordsNoLongerPassByDefault() {
        // pocket/packet and turned/turn were accepted by the old 65% similarity rule.
        val matched = SpeechScorer.matchedWords(sentence, "my missing keys turn up in my jacket packet")
        assertEquals(listOf(true, true, true, false, true, true, true, true, false), matched)
    }

    @Test fun easyModeKeepsTheOldGenerousMatching() {
        val matched = SpeechScorer.matchedWords(sentence, "my missing keys turn up in my jacket packet", RecognitionStrictness.EASY)
        assertTrue(matched.all { it })
    }

    @Test fun formattingDifferencesStillCount() {
        assertTrue(SpeechScorer.matchedWords("They usually don't open until 4 pm.", "they usually do not open until four p.m.").all { it })
        assertTrue(SpeechScorer.matchedWords("I'm expecting sixty million won.", "I'm expecting 60 million won").all { it })
        assertTrue(SpeechScorer.matchedWords("What's your favourite colour?", "what's your favorite color").all { it })
        assertTrue(SpeechScorer.matchedWords("It costs 1,000 dollars.", "it costs one thousand dollars").all { it })
        assertTrue(SpeechScorer.matchedWords("Okay, I'd love to.", "OK I would love to").all { it })
    }

    @Test fun onlyTopRecognizerCandidateIsTrustedByDefault() {
        val result = RetryEvaluator.evaluate(sentence,
            listOf("my missing keys turn up in my jacket packet", sentence), emptyList())
        assertFalse(result.matched.all { it })
        val easy = RetryEvaluator.evaluate(sentence,
            listOf("my missing keys turn up in my jacket packet", sentence), emptyList(), RecognitionStrictness.EASY)
        assertTrue(easy.matched.all { it })
    }

    @Test fun strictModeDoesNotAccumulateFragments() {
        val confirmed = listOf(true, true, true, false, true, true, true, true, true)
        val normal = RetryEvaluator.evaluate(sentence, listOf("turned"), confirmed)
        assertTrue(normal.matched.all { it })
        val strict = RetryEvaluator.evaluate(sentence, listOf("turned"), confirmed, RecognitionStrictness.STRICT)
        assertEquals(1, strict.matched.count { it })
        val whole = RetryEvaluator.evaluate(sentence, listOf("my missing keys turned up in my jacket pocket"), confirmed, RecognitionStrictness.STRICT)
        assertTrue(whole.matched.all { it })
    }
}
