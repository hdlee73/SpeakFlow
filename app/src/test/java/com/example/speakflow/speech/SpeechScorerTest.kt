package com.example.speakflow.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechScorerTest {
    @Test fun exactSentencePasses() = assertEquals(100, SpeechScorer.score("I'm ready.", "I'm ready"))
    @Test fun closeSentenceScoresHigherThanWrongSentence() {
        assertTrue(SpeechScorer.score("What if we wait for more time?", "What if we wait more time") >
            SpeechScorer.score("What if we wait for more time?", "Tomorrow is Monday"))
    }
}
