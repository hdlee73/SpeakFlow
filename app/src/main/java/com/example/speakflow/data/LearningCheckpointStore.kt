package com.example.speakflow.data

import android.content.Context
import com.example.speakflow.model.LearningCheckpoint
import com.example.speakflow.model.PlayOrder
import org.json.JSONArray
import org.json.JSONObject

class LearningCheckpointStore(context: Context) {
    private val prefs = context.getSharedPreferences("learning_progress", 0)
    fun load(): LearningCheckpoint? = runCatching {
        val json = JSONObject(prefs.getString("checkpoint", null) ?: return null)
        val ids = json.getJSONArray("datasets")
        val order = json.getJSONArray("order")
        LearningCheckpoint(
            (0 until ids.length()).map { ids.getString(it) },
            (0 until order.length()).map { order.getInt(it) },
            json.getInt("position"), json.getInt("repeat"),
            PlayOrder.valueOf(json.getString("play_order")), json.optBoolean("completed")
        )
    }.getOrNull()

    fun save(checkpoint: LearningCheckpoint?) {
        if (checkpoint == null) { prefs.edit().remove("checkpoint").apply(); return }
        val json = JSONObject().put("datasets", JSONArray(checkpoint.datasetIds))
            .put("order", JSONArray(checkpoint.order)).put("position", checkpoint.position)
            .put("repeat", checkpoint.repeatCount).put("play_order", checkpoint.playOrder.name)
            .put("completed", checkpoint.completed)
        prefs.edit().putString("checkpoint", json.toString()).commit()
    }

    // Flush pending apply() writes before the activity leaves the foreground.
    fun flush() { prefs.edit().commit() }
}
