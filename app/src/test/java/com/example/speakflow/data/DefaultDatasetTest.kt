package com.example.speakflow.data

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class DefaultDatasetTest {
    @Test fun defaultContains500UniqueBilingualSentences() {
        val pairs = File("src/main/assets/daily_500.csv").inputStream().use { DatasetParser.parseCsv(it) }
        assertEquals(500, pairs.size)
        assertEquals(500, pairs.map { it.english }.distinct().size)
        assertTrue(pairs.all { it.korean.any { c -> c in '\uAC00'..'\uD7A3' } && it.english.isNotBlank() })
    }
    @Test fun phrasalDatasetContains200UniqueBilingualSentences() {
        val pairs = File("src/main/assets/phrasal_200.csv").inputStream().use { DatasetParser.parseCsv(it) }
        assertEquals(200, pairs.size)
        assertEquals(200, pairs.map { it.english }.distinct().size)
        assertTrue(pairs.all { it.korean.isNotBlank() })
    }
}
