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

    fun ensureDefault() {
        ensurePhrasalDefault()
        if (prefs.getBoolean("daily_500_installed", false)) return
        val file = File(directory, "daily_500.csv")
        context.assets.open("daily_500.csv").use { input -> file.outputStream().use(input::copyTo) }
        val count = file.inputStream().use { DatasetParser.parse(it, file.name) }.size
        saveIndex(list() + SavedDataset("daily_500", "생활 영어 패턴 500.csv", file.name, count))
        prefs.edit().putBoolean("daily_500_installed", true).apply()
    }

    private fun ensurePhrasalDefault() {
        if (prefs.getBoolean("phrasal_200_installed", false)) return
        val file = File(directory, "phrasal_200.csv")
        context.assets.open("phrasal_200.csv").use { input -> file.outputStream().use(input::copyTo) }
        val count = file.inputStream().use { DatasetParser.parse(it, file.name) }.size
        saveIndex(list() + SavedDataset("phrasal_200", "실생활 구동사 200.csv", file.name, count))
        prefs.edit().putBoolean("phrasal_200_installed", true).apply()
    }

    fun saveEdits(dataset: SavedDataset, items: List<SentencePair>) {
        require(items.size == dataset.sentenceCount && items.all { it.english.isNotBlank() })
        val json = JSONArray()
        items.forEach { json.put(JSONObject().put("korean", it.korean).put("english", it.english)) }
        val target = File(directory, "${dataset.id}.edited.json")
        val temporary = File(directory, "${dataset.id}.edited.tmp")
        temporary.writeText(json.toString(), Charsets.UTF_8)
        check(temporary.renameTo(target)) { "수정 내용을 저장하지 못했습니다." }
    }

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

    fun load(dataset: SavedDataset): List<SentencePair> {
        val edited = File(directory, "${dataset.id}.edited.json")
        if (edited.exists()) {
            val json = JSONArray(edited.readText(Charsets.UTF_8))
            return (0 until json.length()).map { i -> json.getJSONObject(i).let { SentencePair(it.getString("korean"), it.getString("english")) } }
        }
        return File(directory, dataset.fileName).inputStream().use { DatasetParser.parse(it, dataset.name) }
    }

    fun delete(dataset: SavedDataset) {
        File(directory, dataset.fileName).delete()
        File(directory, "${dataset.id}.edited.json").delete()
        saveIndex(list().filterNot { it.id == dataset.id })
    }

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
