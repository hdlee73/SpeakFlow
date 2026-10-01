package com.example.speakflow.model

object LearningPlan {
    fun build(size: Int, order: PlayOrder, repeat: Int): List<Int> =
        (0 until size).toList().let { if (order == PlayOrder.RANDOM) it.shuffled() else it }
            .flatMap { index -> List(repeat.coerceIn(1, 5)) { index } }

    fun nextSentence(order: List<Int>, position: Int): Int? =
        ((position + 1)..order.lastIndex).firstOrNull { order[it] != order.getOrNull(position) }
}
