package com.example.speakflow

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.speakflow.data.DatasetParser
import com.example.speakflow.data.DatasetStore
import com.example.speakflow.model.*
import com.example.speakflow.speech.SpeechScorer
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

class LearningViewModel(application: Application) : AndroidViewModel(application) {
    private val prefs = application.getSharedPreferences("learning", 0)
    private val datasetStore = DatasetStore(application)
    private val _state = MutableStateFlow(LearningUiState(settings = loadSettingsWithMigration(), savedDatasets = datasetStore.list()))
    val state: StateFlow<LearningUiState> = _state.asStateFlow()
    private var timerJob: Job? = null
    private var deadline = 0L

    init {
        val migrated = runCatching { datasetStore.migrateLegacy() }.getOrNull()
        val datasets = datasetStore.list()
        _state.update { it.copy(savedDatasets = datasets) }
        val selectedId = prefs.getString("active_dataset_id", null)
        (datasets.firstOrNull { it.id == selectedId } ?: migrated ?: datasets.firstOrNull())?.let(::selectDataset)
    }

    fun importDataset(uri: Uri) {
        viewModelScope.launch {
            runCatching {
                val resolver = getApplication<Application>().contentResolver
                val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                    if (it.moveToFirst()) it.getString(0) else null
                } ?: "dataset.xlsx"
                runCatching { resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                val (saved, items) = datasetStore.import(uri, name)
                prefs.edit().putString("active_dataset_id", saved.id).apply()
                _state.update { it.copy(savedDatasets = datasetStore.list()) }
                resetWith(items, saved.name, saved.id)
            }.onFailure { error ->
                _state.update { it.copy(message = error.message ?: "파일을 읽지 못했습니다.") }
            }
        }
    }

    fun selectDataset(dataset: SavedDataset) {
        viewModelScope.launch {
            runCatching { datasetStore.load(dataset) }
                .onSuccess {
                    prefs.edit().putString("active_dataset_id", dataset.id).apply()
                    resetWith(it, dataset.name, dataset.id)
                }
                .onFailure { _state.update { state -> state.copy(message = "저장된 데이터셋을 다시 불러오지 못했습니다.") } }
        }
    }

    private fun resetWith(items: List<SentencePair>, name: String, id: String) {
        val order = buildOrder(items.size, _state.value.settings.order)
        _state.update { it.copy(items = items, order = order, position = 0, phase = LessonPhase.IDLE, datasetName = name, activeDatasetId = id, message = null) }
    }

    fun updateSettings(settings: LearningSettings) {
        prefs.edit()
            .putString("mode", settings.mode.name)
            .putString("order", settings.order.name)
            .putInt("timeout", settings.timeoutSeconds)
            .putInt("pass_score", settings.passScore)
            .apply()
        timerJob?.cancel()
        _state.update {
            it.copy(settings = settings, order = buildOrder(it.items.size, settings.order), position = 0,
                phase = LessonPhase.IDLE, heardText = "", score = null, remainingSeconds = 0)
        }
    }

    fun startSpeaking() {
        if (_state.value.current == null) return
        timerJob?.cancel()
        _state.update { it.copy(phase = LessonPhase.SPEAKING, heardText = "", liveText = "", score = null, message = null) }
    }

    fun onPromptFinished() {
        deadline = System.currentTimeMillis() + _state.value.settings.timeoutSeconds * 1_000L
        _state.update { it.copy(phase = LessonPhase.LISTENING, remainingSeconds = it.settings.timeoutSeconds) }
        startTimer()
    }

    fun onRecognition(candidates: List<String>) {
        val expected = _state.value.current?.english ?: return
        timerJob?.cancel()
        val best = candidates.filter(String::isNotBlank)
            .map { it to SpeechScorer.score(expected, it) }
            .maxByOrNull { it.second }
        val text = best?.first.orEmpty()
        val score = best?.second ?: 0
        if (score >= _state.value.settings.passScore) {
            _state.update { it.copy(phase = LessonPhase.CORRECT, heardText = text, liveText = text, score = score) }
        } else {
            _state.update { it.copy(phase = LessonPhase.RETRYING, heardText = text, liveText = text, score = score, remainingSeconds = 0) }
        }
    }

    fun onPartialRecognition(candidates: List<String>) {
        // The first hypothesis is the recognizer's current best result. Avoid scoring
        // every alternative on each partial callback so the UI can repaint immediately.
        val latest = candidates.firstOrNull(String::isNotBlank).orEmpty()
        if (latest.isNotBlank() && latest != _state.value.liveText) {
            _state.update { it.copy(liveText = latest) }
        }
    }

    fun retryListening() {
        deadline = System.currentTimeMillis() + _state.value.settings.timeoutSeconds * 1_000L
        _state.update { it.copy(phase = LessonPhase.LISTENING, remainingSeconds = it.settings.timeoutSeconds) }
        startTimer()
    }

    fun onRecognitionUnavailable(message: String) {
        _state.update { it.copy(message = message, phase = LessonPhase.PAUSED) }
        timerJob?.cancel()
    }

    fun togglePause() {
        when (_state.value.phase) {
            LessonPhase.PAUSED -> startSpeaking()
            LessonPhase.IDLE, LessonPhase.COMPLETE -> restart()
            else -> { timerJob?.cancel(); _state.update { it.copy(phase = LessonPhase.PAUSED) } }
        }
    }

    fun previous() {
        timerJob?.cancel()
        _state.update { it.copy(position = (it.position - 1).coerceAtLeast(0), phase = LessonPhase.SPEAKING, heardText = "", score = null) }
    }

    fun next() {
        timerJob?.cancel()
        _state.update {
            if (it.position >= it.order.lastIndex) it.copy(phase = LessonPhase.COMPLETE, remainingSeconds = 0)
            else it.copy(position = it.position + 1, phase = LessonPhase.SPEAKING, heardText = "", liveText = "", score = null, remainingSeconds = 0)
        }
    }

    fun restart() {
        timerJob?.cancel()
        _state.update {
            if (it.items.isEmpty()) it.copy(message = "먼저 엑셀 데이터셋을 불러오세요.")
            else it.copy(order = buildOrder(it.items.size, it.settings.order), position = 0, phase = LessonPhase.SPEAKING,
                heardText = "", liveText = "", score = null, remainingSeconds = 0)
        }
    }

    fun clearMessage() = _state.update { it.copy(message = null) }

    private fun startTimer() {
        timerJob?.cancel()
        timerJob = viewModelScope.launch {
            while (true) {
                val remaining = ((deadline - System.currentTimeMillis() + 999) / 1_000).toInt()
                if (remaining <= 0) break
                _state.update { it.copy(remainingSeconds = remaining) }
                delay(250)
            }
            _state.update { it.copy(phase = LessonPhase.TIMED_OUT, remainingSeconds = 0) }
        }
    }

    private fun loadSettingsWithMigration(): LearningSettings {
        if (!prefs.getBoolean("recognition_v2", false)) {
            prefs.edit().putInt("timeout", 20).putInt("pass_score", 68).putBoolean("recognition_v2", true).apply()
        }
        return LearningSettings(
        mode = runCatching { LearningMode.valueOf(prefs.getString("mode", null) ?: "SHADOWING") }.getOrDefault(LearningMode.SHADOWING),
        order = runCatching { PlayOrder.valueOf(prefs.getString("order", null) ?: "SEQUENTIAL") }.getOrDefault(PlayOrder.SEQUENTIAL),
        timeoutSeconds = prefs.getInt("timeout", 20),
        passScore = prefs.getInt("pass_score", 68)
        )
    }

    private fun buildOrder(size: Int, order: PlayOrder): List<Int> =
        (0 until size).toList().let { if (order == PlayOrder.RANDOM) it.shuffled() else it }
}
