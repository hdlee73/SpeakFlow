package com.example.speakflow.model

// Store the concrete order so a random lesson resumes without reshuffling.
data class LearningCheckpoint(
    val datasetIds: List<String>,
    val order: List<Int>,
    val position: Int,
    val repeatCount: Int,
    val playOrder: PlayOrder,
    val completed: Boolean
) {
    fun isValid(itemCount: Int, settings: LearningSettings): Boolean {
        if (datasetIds.isEmpty() || itemCount <= 0 || repeatCount != settings.repeatCount || playOrder != settings.order) return false
        if (order.size != itemCount * repeatCount || position !in order.indices) return false
        if (order.any { it !in 0 until itemCount }) return false
        val chunks = order.chunked(repeatCount)
        if (chunks.any { it.distinct().size != 1 } || chunks.map { it.first() }.toSet().size != itemCount) return false
        if (playOrder == PlayOrder.SEQUENTIAL && chunks.map { it.first() } != (0 until itemCount).toList()) return false
        return !completed || order[position] == order.last()
    }
}
