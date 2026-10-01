package com.example.speakflow.model

import org.junit.Assert.*
import org.junit.Test

class LearningCheckpointTest {
    private val settings = LearningSettings(repeatCount = 3)
    private fun checkpoint(position: Int = 4) = LearningCheckpoint(
        listOf("dataset-one", "dataset-two"), listOf(0, 0, 0, 1, 1, 1, 2, 2, 2),
        position, 3, PlayOrder.SEQUENTIAL, false)

    @Test fun preservesSecondRepeatAcrossMultipleDatasets() {
        val saved = checkpoint()
        assertTrue(saved.isValid(3, settings))
        val restored = LearningUiState(items = List(3) { SentencePair("문장 $it", "Sentence $it") },
            order = saved.order, position = saved.position, settings = settings, phase = LessonPhase.PAUSED)
        assertEquals("Sentence 1", restored.current?.english)
        assertEquals(2, restored.repeatNumber)
        assertEquals(listOf("dataset-one", "dataset-two"), saved.datasetIds)
    }
    @Test fun randomOrderIsRestoredExactly() {
        val saved = checkpoint().copy(order = listOf(2, 2, 2, 0, 0, 0, 1, 1, 1), playOrder = PlayOrder.RANDOM)
        assertTrue(saved.isValid(3, settings.copy(order = PlayOrder.RANDOM)))
        assertEquals(0, saved.order[saved.position])
    }
    @Test fun rejectsMissingOrChangedDatasetContents() {
        assertFalse(checkpoint().isValid(2, settings))
        assertFalse(checkpoint().copy(order = listOf(0, 0, 0, 1, 1, 1, 9, 9, 9)).isValid(3, settings))
        assertFalse(checkpoint().copy(order = List(9) { 0 }).isValid(3, settings))
    }
    @Test fun rejectsInvalidCursorAndIncompatibleSettings() {
        assertFalse(checkpoint(-1).isValid(3, settings))
        assertFalse(checkpoint(9).isValid(3, settings))
        assertFalse(checkpoint().isValid(3, settings.copy(repeatCount = 1)))
        assertFalse(checkpoint().isValid(3, settings.copy(order = PlayOrder.RANDOM)))
    }
    @Test fun completedLessonDoesNotResetOnReopen() {
        assertTrue(checkpoint(8).copy(completed = true).isValid(3, settings))
        // A next-button press can finish on the first repeat of the final sentence.
        assertTrue(checkpoint(6).copy(completed = true).isValid(3, settings))
        assertFalse(checkpoint(1).copy(completed = true).isValid(3, settings))
    }
}
