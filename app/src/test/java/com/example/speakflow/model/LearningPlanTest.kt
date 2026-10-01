package com.example.speakflow.model

import org.junit.Assert.*
import org.junit.Test

class LearningPlanTest {
    @Test fun repeatsStayAdjacent() {
        assertEquals(listOf(0, 0, 0, 1, 1, 1), LearningPlan.build(2, PlayOrder.SEQUENTIAL, 3))
    }
    @Test fun randomOrderKeepsEveryRepeatTogether() {
        val order = LearningPlan.build(20, PlayOrder.RANDOM, 5)
        assertEquals(100, order.size)
        assertEquals((0 until 20).toSet(), order.toSet())
        assertTrue(order.chunked(5).all { it.distinct().size == 1 })
    }
    @Test fun nextKeySkipsRemainingRepeats() {
        assertEquals(3, LearningPlan.nextSentence(listOf(0, 0, 0, 1, 1, 1), 1))
        assertNull(LearningPlan.nextSentence(listOf(0, 0, 0, 1, 1, 1), 4))
    }
    @Test fun emptyDatasetCannotAdvance() {
        assertTrue(LearningPlan.build(0, PlayOrder.SEQUENTIAL, 1).isEmpty())
        assertNull(LearningPlan.nextSentence(emptyList(), 0))
    }
}
