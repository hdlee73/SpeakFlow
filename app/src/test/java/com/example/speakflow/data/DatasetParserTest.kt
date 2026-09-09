package com.example.speakflow.data

import org.junit.Assert.assertEquals
import org.junit.Test

class DatasetParserTest {
    @Test fun readsTwoRows() {
        val pairs = DatasetParser.rowsToPairs(listOf(
            listOf("안녕하세요", "기다려 볼까요?"),
            listOf("Hello", "What if we wait?")
        ))
        assertEquals("안녕하세요", pairs[0].korean)
        assertEquals("What if we wait?", pairs[1].english)
    }

    @Test fun alsoReadsTwoColumns() {
        val pairs = DatasetParser.rowsToPairs(listOf(
            listOf("한국어", "English"),
            listOf("고마워요", "Thank you"),
            listOf("또 봐요", "See you again")
        ))
        assertEquals(2, pairs.size)
    }
}
