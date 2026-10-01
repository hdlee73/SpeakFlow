package com.example.speakflow.data

import android.content.Context
import com.example.speakflow.model.DailyLearning
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

class LearningStatsStore(context: Context) {
    private val prefs = context.getSharedPreferences("learning_stats", 0)
    private val days = linkedMapOf<String, DailyLearning>()
    init {
        runCatching {
            val array = JSONArray(prefs.getString("days", "[]"))
            for (i in 0 until array.length()) {
                val row = array.getJSONObject(i)
                val date = row.getString("date")
                days[date] = DailyLearning(date, row.optInt("attempts"), row.optInt("correct"), row.optLong("seconds"))
            }
        }
    }
    fun add(seconds: Long = 0, attempt: Boolean = false, correct: Boolean = false): List<DailyLearning> {
        val key = LocalDate.now().toString()
        val old = days[key] ?: DailyLearning(key)
        days[key] = old.copy(seconds = old.seconds + seconds,
            attempts = old.attempts + if (attempt) 1 else 0,
            correct = old.correct + if (correct) 1 else 0)
        return list()
    }
    fun list() = days.values.sortedByDescending { it.date }
    fun save() {
        val array = JSONArray()
        list().forEach { array.put(JSONObject().put("date", it.date).put("attempts", it.attempts)
            .put("correct", it.correct).put("seconds", it.seconds)) }
        prefs.edit().putString("days", array.toString()).apply()
    }
}
