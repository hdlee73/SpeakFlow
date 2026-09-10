package com.example.speakflow.data

import android.content.Context
import android.net.Uri
import com.example.speakflow.model.SavedDataset
import com.example.speakflow.model.SentencePair
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

class DatasetStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("learning", 0)
    private val directory = File(context.filesDir, "datasets").apply { mkdirs() }

    fun list(): List<SavedDataset> = runCatching {
        val array = JSONArray(prefs.getString("datasets_index", "[]"))
        buildList {
            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                val saved = SavedDataset(item.getString("id"), item.getString("name"), item.getString("file"), item.getInt("count"))
                if (File(directory, saved.fileName).exists()) add(saved)
            }
        }
    }.getOrDefault(emptyList())

    fun import(uri: Uri, displayName: String): Pair<SavedDataset, List<SentencePair>> {
        val extension = displayName.substringAfterLast('.', "xlsx").lowercase().takeIf { it in setOf("xlsx", "csv") } ?: "xlsx"
        val id = UUID.randomUUID().toString()
        val file = File(directory, "$id.$extension")
        context.contentResolver.openInputStream(uri)!!.use { source -> file.outputStream().use(source::copyTo) }
        val items = file.inputStream().use { DatasetParser.parse(it, displayName) }
        val saved = SavedDataset(id, displayName, file.name, items.size)
        saveIndex(list() + saved)
        return saved to items
    }

    fun load(dataset: SavedDataset): List<SentencePair> =
        File(directory, dataset.fileName).inputStream().use { DatasetParser.parse(it, dataset.name) }

    fun migrateLegacy(): SavedDataset? {
        if (list().isNotEmpty()) return null
        val legacy = File(context.filesDir, "dataset")
        if (!legacy.exists()) return null
        val name = prefs.getString("dataset_name", "dataset.xlsx") ?: "dataset.xlsx"
        val extension = name.substringAfterLast('.', "xlsx").lowercase()
        val id = UUID.randomUUID().toString()
        val target = File(directory, "$id.$extension")
        legacy.copyTo(target, overwrite = true)
        val count = target.inputStream().use { DatasetParser.parse(it, name) }.size
        return SavedDataset(id, name, target.name, count).also { saveIndex(listOf(it)) }
    }

    private fun saveIndex(items: List<SavedDataset>) {
        val array = JSONArray()
        items.forEach { item ->
            array.put(JSONObject().put("id", item.id).put("name", item.name).put("file", item.fileName).put("count", item.sentenceCount))
        }
        prefs.edit().putString("datasets_index", array.toString()).apply()
    }
}
