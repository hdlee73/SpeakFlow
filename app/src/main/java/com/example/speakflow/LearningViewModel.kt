package com.example.speakflow

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.speakflow.data.DatasetParser
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
    private val savedDataset = File(application.filesDir, "dataset")
    private val _state = MutableStateFlow(LearningUiState(settings = loadSettings()))
    val state: StateFlow<LearningUiState> = _state.asStateFlow()
    private var timerJob: Job? = null
    private var deadline = 0L

    init {
        if (savedDataset.exists()) loadSavedDataset()
    }

    fun importDataset(uri: Uri) {
        viewModelScope.launch {
            runCatching {
                val resolver = getApplication<Application>().contentResolver
                val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                    if (it.moveToFirst()) it.getString(0) else null
                } ?: "dataset.xlsx"
                runCatching { resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                resolver.openInputStream(uri)!!.use { source -> savedDataset.outputStream().use(source::copyTo) }
                prefs.edit().putString("dataset_name", name).apply()
                val items = savedDataset.inputStream().use { DatasetParser.parse(it, name) }
                resetWith(items, name)
            }.onFailure { error ->
                _state.update { it.copy(message = error.message ?: "파일을 읽지 못했습니다.") }
            }
        }
    }

    private fun loadSavedDataset() {
        viewModelScope.launch {
            val name = prefs.getString("dataset_name", "dataset.xlsx")!!
            runCatching { savedDataset.inputStream().use { DatasetParser.parse(it, name) } }
                .onSuccess { resetWith(it, name) }
                .onFailure { _state.update { state -> state.copy(message = "저장된 데이터셋을 다시 불러오지 못했습니다.") } }
        }
    }

    private fun resetWith(items: List<SentencePair>, name: String) {
        val order = buildOrder(items.size, _state.value.settings.order)
        _state.update { it.copy(items = items, order = order, position = 0, phase = LessonPhase.IDLE, datasetName = name, message = null) }
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
        _state.update { it.copy(phase = LessonPhase.SPEAKING, heardText = "", score = null, message = null) }
    }

    fun onPromptFinished() {
        deadline = System.currentTimeMillis() + _state.value.settings.timeoutSeconds * 1_000L
        _state.update { it.copy(phase = LessonPhase.LISTENING, remainingSeconds = it.settings.timeoutSeconds) }
        startTimer()
    }

    fun onRecognition(text: String) {
        val expected = _state.value.current?.english ?: return
        val score = SpeechScorer.score(expected, text)
        if (score >= _state.value.settings.passScore) {
            timerJob?.cancel()
            _state.update { it.copy(phase = LessonPhase.CORRECT, heardText = text, score = score) }
            viewModelScope.launch { delay(1_100); next() }
        } else {
            val remains = ((deadline - System.currentTimeMillis()) / 1_000).toInt().coerceAtLeast(0)
            _state.update { it.copy(phase = LessonPhase.RETRYING, heardText = text, score = score, remainingSeconds = remains) }
            if (remains > 0) viewModelScope.launch { delay(650); _state.update { state -> state.copy(phase = LessonPhase.LISTENING) } }
        }
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
            else it.copy(position = it.position + 1, phase = LessonPhase.SPEAKING, heardText = "", score = null, remainingSeconds = 0)
        }
    }

    fun restart() {
        timerJob?.cancel()
        _state.update {
            if (it.items.isEmpty()) it.copy(message = "먼저 엑셀 데이터셋을 불러오세요.")
            else it.copy(order = buildOrder(it.items.size, it.settings.order), position = 0, phase = LessonPhase.SPEAKING,
                heardText = "", score = null, remainingSeconds = 0)
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
            delay(900)
            next()
        }
    }

    private fun loadSettings() = LearningSettings(
        mode = runCatching { LearningMode.valueOf(prefs.getString("mode", null) ?: "SHADOWING") }.getOrDefault(LearningMode.SHADOWING),
        order = runCatching { PlayOrder.valueOf(prefs.getString("order", null) ?: "SEQUENTIAL") }.getOrDefault(PlayOrder.SEQUENTIAL),
        timeoutSeconds = prefs.getInt("timeout", 10),
        passScore = prefs.getInt("pass_score", 78)
    )

    private fun buildOrder(size: Int, order: PlayOrder): List<Int> =
        (0 until size).toList().let { if (order == PlayOrder.RANDOM) it.shuffled() else it }
}
