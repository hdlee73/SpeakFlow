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
    private companion object {
        const val INCORRECT_RESULT_DISPLAY_MILLIS = 500L
        const val SUCCESS_RESULT_DISPLAY_MILLIS = 3_000L
    }
    private val prefs = application.getSharedPreferences("learning", 0)
    private val datasetStore = DatasetStore(application)
    private val _state = MutableStateFlow(LearningUiState(settings = loadSettingsWithMigration(), savedDatasets = datasetStore.list()))
    val state: StateFlow<LearningUiState> = _state.asStateFlow()
    private var timerJob: Job? = null
    private var advanceJob: Job? = null
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

    fun deleteDataset(dataset: SavedDataset) {
        timerJob?.cancel()
        advanceJob?.cancel()
        datasetStore.delete(dataset)
        val remaining = datasetStore.list()
        if (_state.value.activeDatasetId == dataset.id) {
            prefs.edit().remove("active_dataset_id").apply()
            val replacement = remaining.firstOrNull()
            if (replacement != null) {
                _state.update { it.copy(savedDatasets = remaining, message = "데이터셋을 삭제했습니다.") }
                selectDataset(replacement)
            } else {
                _state.update {
                    it.copy(
                        items = emptyList(), order = emptyList(), position = 0,
                        phase = LessonPhase.IDLE, datasetName = null, activeDatasetId = null,
                        savedDatasets = emptyList(), heardText = "", liveText = "", retryText = null, matchedWords = emptyList(), score = null,
                        allWordsMatched = false,
                        remainingSeconds = 0, message = "데이터셋을 삭제했습니다."
                    )
                }
            }
        } else {
            _state.update { it.copy(savedDatasets = remaining, message = "데이터셋을 삭제했습니다.") }
        }
    }

    private fun resetWith(items: List<SentencePair>, name: String, id: String) {
        timerJob?.cancel()
        advanceJob?.cancel()
        val order = buildOrder(items.size, _state.value.settings.order)
        _state.update { it.copy(items = items, order = order, position = 0, phase = LessonPhase.IDLE, datasetName = name, activeDatasetId = id,
            heardText = "", liveText = "", retryText = null, matchedWords = emptyList(), score = null, allWordsMatched = false, remainingSeconds = 0, message = null) }
    }

    fun updateSettings(settings: LearningSettings) {
        prefs.edit()
            .putString("mode", settings.mode.name)
            .putString("order", settings.order.name)
            .putInt("repeat_count", settings.repeatCount)
            .putBoolean("auto_advance_sentence", settings.autoAdvanceSentence)
            .putInt("timeout", settings.timeoutSeconds)
            .putInt("pass_score", settings.passScore)
            .putString("voice_accent", settings.voiceAccent.name)
            .putString("voice_gender", settings.voiceGender.name)
            .apply()
        timerJob?.cancel()
        advanceJob?.cancel()
        _state.update {
            it.copy(settings = settings, order = buildOrder(it.items.size, settings.order), position = 0,
                phase = LessonPhase.IDLE, heardText = "", liveText = "", retryText = null, matchedWords = emptyList(), score = null, allWordsMatched = false, remainingSeconds = 0)
        }
    }

    fun startSpeaking() {
        if (_state.value.current == null) return
        timerJob?.cancel()
        advanceJob?.cancel()
        _state.update { it.copy(phase = LessonPhase.SPEAKING, heardText = "", liveText = "", retryText = null, matchedWords = emptyList(), score = null, allWordsMatched = false, feedbackSuccess = null, message = null) }
    }

    fun onPromptFinished() {
        if (_state.value.phase != LessonPhase.SPEAKING) return
        deadline = 0L
        _state.update { it.copy(
            phase = LessonPhase.LISTENING,
            remainingSeconds = it.settings.timeoutSeconds,
            listenRequestId = it.listenRequestId + 1
        ) }
    }

    fun onRecognizerReady() {
        if (_state.value.phase != LessonPhase.LISTENING) return
        if (deadline == 0L) {
            deadline = System.currentTimeMillis() + _state.value.settings.timeoutSeconds * 1_000L
        }
        startTimer()
    }

    fun onRecognition(candidates: List<String>) {
        // cancel() can deliver a late recognizer callback while TTS is already playing.
        // It belongs to the previous session and must not turn the new countdown into 0.
        if (_state.value.phase != LessonPhase.LISTENING) return
        val expected = _state.value.current?.english ?: return
        val remaining = if (deadline == 0L) _state.value.settings.timeoutSeconds
            else ((deadline - System.currentTimeMillis() + 999) / 1_000).toInt().coerceAtLeast(0)
        val recognized = candidates.filter(String::isNotBlank)
        val previous = _state.value.heardText
        val stitched = previous.takeIf(String::isNotBlank)?.let {
            recognized.map { "$previous $it" }
        }.orEmpty()
        val best = (recognized + stitched + listOf(previous).filter(String::isNotBlank)).distinct()
            .map { it to SpeechScorer.score(expected, it) }
            .maxByOrNull { it.second }
        val text = best?.first.orEmpty()
        val score = best?.second ?: 0
        val currentMatches = SpeechScorer.matchedWords(expected, text)
        val matchedWords = currentMatches.indices.map { index ->
            _state.value.matchedWords.getOrElse(index) { false } || currentMatches[index]
        }
        val allWordsMatched = matchedWords.isNotEmpty() && matchedWords.all { it }
        if (allWordsMatched) {
            timerJob?.cancel()
            _state.update { state -> state.copy(
                phase = LessonPhase.CORRECT,
                heardText = text,
                liveText = state.current?.english.orEmpty(),
                retryText = null,
                matchedWords = matchedWords,
                score = score,
                allWordsMatched = true,
                feedbackSuccess = true,
                feedbackSequence = state.feedbackSequence + 1
            ) }
        } else if (remaining > 0) {
            // A finalized recognizer result is only a fragment of the attempt while
            // time remains. Keep every word matched so far and continue listening.
            // Incorrect feedback is emitted only by the timer after the deadline.
            _state.update { it.copy(
                phase = LessonPhase.LISTENING,
                heardText = text,
                liveText = text,
                matchedWords = matchedWords,
                score = score,
                allWordsMatched = false,
                remainingSeconds = remaining,
                listenRequestId = it.listenRequestId + 1
            ) }
        } else if (_state.value.phase == LessonPhase.LISTENING) {
            val retryText = SpeechScorer.displayWords(expected)
                .filterIndexed { index, _ -> !matchedWords.getOrElse(index) { false } }
                .joinToString(" ")
                .ifBlank { expected }
            _state.update { it.copy(
                phase = LessonPhase.RETRYING,
                heardText = text,
                liveText = text,
                retryText = retryText,
                matchedWords = matchedWords,
                score = score,
                allWordsMatched = false,
                feedbackSuccess = false,
                feedbackSequence = it.feedbackSequence + 1,
                remainingSeconds = remaining.coerceAtLeast(0)
            ) }
        }
    }

    fun onPartialRecognition(candidates: List<String>) {
        if (_state.value.phase != LessonPhase.LISTENING) return
        // The first hypothesis is the recognizer's current best result. Avoid scoring
        // every alternative on each partial callback so the UI can repaint immediately.
        val latest = candidates.firstOrNull(String::isNotBlank).orEmpty()
        if (latest.isNotBlank()) {
            val combined = listOf(_state.value.heardText, latest).filter(String::isNotBlank).joinToString(" ")
            if (combined != _state.value.liveText) _state.update { it.copy(liveText = combined) }
        }
    }

    fun retryListening() {
        advanceJob?.cancel()
        timerJob?.cancel()
        deadline = 0L
        _state.update {
            val preservePartialMatches = !it.retryText.isNullOrBlank() && !it.allWordsMatched
            it.copy(
                phase = LessonPhase.LISTENING,
                heardText = "",
                liveText = "",
                retryText = if (preservePartialMatches) it.retryText else null,
                matchedWords = if (preservePartialMatches) it.matchedWords else emptyList(),
                score = null,
                allWordsMatched = false,
                feedbackSuccess = null,
                remainingSeconds = it.settings.timeoutSeconds,
                listenRequestId = it.listenRequestId + 1
            )
        }
    }

    fun onFeedbackFinished(sequence: Long, success: Boolean) {
        if (_state.value.feedbackSequence != sequence) return
        if (success && _state.value.phase == LessonPhase.CORRECT) {
            advanceJob?.cancel()
            advanceJob = viewModelScope.launch {
                delay(SUCCESS_RESULT_DISPLAY_MILLIS)
                if (_state.value.phase == LessonPhase.CORRECT && _state.value.feedbackSequence == sequence) nextSentence()
            }
        } else if (!success && _state.value.phase == LessonPhase.RETRYING) {
            advanceJob?.cancel()
            advanceJob = viewModelScope.launch {
                delay(INCORRECT_RESULT_DISPLAY_MILLIS)
                if (_state.value.phase == LessonPhase.RETRYING && _state.value.feedbackSequence == sequence) {
                    retryIncorrectPart()
                }
            }
        }
    }

    private fun retryIncorrectPart() {
        val remaining = ((deadline - System.currentTimeMillis() + 999) / 1_000).toInt()
        if (remaining <= 0 || _state.value.retryText.isNullOrBlank()) return
        _state.update { it.copy(
            phase = LessonPhase.LISTENING,
            heardText = "",
            liveText = "",
            score = null,
            allWordsMatched = false,
            feedbackSuccess = null,
            remainingSeconds = remaining,
            listenRequestId = it.listenRequestId + 1
        ) }
    }

    fun onRecognitionUnavailable(message: String) {
        _state.update { it.copy(message = message, phase = LessonPhase.PAUSED) }
        timerJob?.cancel()
    }

    fun onMicrophoneChanged(label: String) = _state.update { it.copy(microphoneLabel = label) }

    fun togglePause() {
        when (_state.value.phase) {
            LessonPhase.PAUSED -> startSpeaking()
            LessonPhase.IDLE, LessonPhase.COMPLETE -> restart()
            else -> { timerJob?.cancel(); advanceJob?.cancel(); _state.update { it.copy(phase = LessonPhase.PAUSED) } }
        }
    }

    fun previous() {
        timerJob?.cancel()
        advanceJob?.cancel()
        _state.update { it.copy(position = (it.position - 1).coerceAtLeast(0), phase = LessonPhase.SPEAKING, heardText = "", liveText = "", retryText = null, matchedWords = emptyList(), score = null, allWordsMatched = false, feedbackSuccess = null) }
    }

    fun next() {
        timerJob?.cancel()
        advanceJob?.cancel()
        _state.update {
            if (it.position >= it.order.lastIndex) it.copy(phase = LessonPhase.COMPLETE, remainingSeconds = 0)
            else it.copy(position = it.position + 1, phase = LessonPhase.SPEAKING, heardText = "", liveText = "", retryText = null, matchedWords = emptyList(), score = null, allWordsMatched = false, feedbackSuccess = null, remainingSeconds = 0)
        }
    }

    private fun nextSentence() {
        timerJob?.cancel()
        advanceJob?.cancel()
        _state.update { state ->
            val currentItemIndex = state.order.getOrNull(state.position)
            val nextPosition = ((state.position + 1)..state.order.lastIndex)
                .firstOrNull { state.order.getOrNull(it) != currentItemIndex }
            if (nextPosition == null) state.copy(phase = LessonPhase.COMPLETE, feedbackSuccess = null, remainingSeconds = 0)
            else state.copy(position = nextPosition, phase = LessonPhase.SPEAKING, heardText = "", liveText = "", retryText = null, matchedWords = emptyList(), score = null,
                allWordsMatched = false, feedbackSuccess = null, remainingSeconds = 0)
        }
    }

    fun restart() {
        timerJob?.cancel()
        advanceJob?.cancel()
        _state.update {
            if (it.items.isEmpty()) it.copy(message = "먼저 엑셀 데이터셋을 불러오세요.")
            else it.copy(order = buildOrder(it.items.size, it.settings.order), position = 0, phase = LessonPhase.SPEAKING,
                heardText = "", liveText = "", retryText = null, matchedWords = emptyList(), score = null, allWordsMatched = false, feedbackSuccess = null, remainingSeconds = 0)
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
            _state.update {
                if (it.phase != LessonPhase.LISTENING) it else {
                    val recognized = it.liveText
                    val expected = it.current?.english.orEmpty()
                    val currentMatches = SpeechScorer.matchedWords(expected, recognized)
                    val matchedWords = currentMatches.indices.map { index ->
                        it.matchedWords.getOrElse(index) { false } || currentMatches[index]
                    }
                    it.copy(
                        phase = LessonPhase.RETRYING,
                        heardText = recognized,
                        retryText = SpeechScorer.displayWords(expected)
                            .filterIndexed { index, _ -> !matchedWords.getOrElse(index) { false } }
                            .joinToString(" ")
                            .ifBlank { expected },
                        matchedWords = matchedWords,
                        score = SpeechScorer.score(expected, recognized),
                        allWordsMatched = false,
                        feedbackSuccess = false,
                        feedbackSequence = it.feedbackSequence + 1,
                        remainingSeconds = 0
                    )
                }
            }
        }
    }

    private fun loadSettingsWithMigration(): LearningSettings {
        if (!prefs.getBoolean("recognition_v2", false)) {
            prefs.edit().putInt("timeout", 20).putInt("pass_score", 68).putBoolean("recognition_v2", true).apply()
        }
        if (!prefs.getBoolean("recognition_v3", false)) {
            val currentPassScore = prefs.getInt("pass_score", 68)
            prefs.edit()
                .putInt("pass_score", if (currentPassScore == 68) 65 else currentPassScore)
                .putBoolean("recognition_v3", true)
                .apply()
        }
        return LearningSettings(
        mode = runCatching { LearningMode.valueOf(prefs.getString("mode", null) ?: "SHADOWING") }.getOrDefault(LearningMode.SHADOWING),
        order = runCatching { PlayOrder.valueOf(prefs.getString("order", null) ?: "SEQUENTIAL") }.getOrDefault(PlayOrder.SEQUENTIAL),
        repeatCount = prefs.getInt("repeat_count", 1).coerceIn(1, 5),
        autoAdvanceSentence = prefs.getBoolean("auto_advance_sentence", false),
        timeoutSeconds = prefs.getInt("timeout", 20),
        passScore = prefs.getInt("pass_score", 65),
        voiceAccent = runCatching { VoiceAccent.valueOf(prefs.getString("voice_accent", null) ?: "US") }.getOrDefault(VoiceAccent.US),
        voiceGender = runCatching { VoiceGender.valueOf(prefs.getString("voice_gender", null) ?: "FEMALE") }.getOrDefault(VoiceGender.FEMALE)
        )
    }

    private fun buildOrder(size: Int, order: PlayOrder): List<Int> =
        (0 until size).toList()
            .let { if (order == PlayOrder.RANDOM) it.shuffled() else it }
}
