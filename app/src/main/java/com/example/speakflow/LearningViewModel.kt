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
import com.example.speakflow.speech.PronunciationRecorder
import com.example.speakflow.speech.SpeechScorer
import com.example.speakflow.speech.RetryEvaluator
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import android.os.SystemClock
import com.example.speakflow.data.LearningStatsStore
import com.example.speakflow.data.LearningCheckpointStore
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged

class LearningViewModel(application: Application) : AndroidViewModel(application) {
    private companion object {
        const val SUCCESS_RESULT_DISPLAY_MILLIS = 3_000L
    }
    private val prefs = application.getSharedPreferences("learning", 0)
    private val datasetStore = DatasetStore(application)
    private val checkpointStore = LearningCheckpointStore(application)
    private var activeDatasetIds = emptyList<String>()
    private val statsStore = LearningStatsStore(application)
    private val recorder = PronunciationRecorder(application)
    private var attemptRecorded = false
    private val _state = MutableStateFlow(LearningUiState(settings = loadSettingsWithMigration(), savedDatasets = datasetStore.list(), statistics = statsStore.list()))
    val state: StateFlow<LearningUiState> = _state.asStateFlow()
    private var timerJob: Job? = null
    private var advanceJob: Job? = null
    private var deadline = 0L

    init {
        val migrated = runCatching { datasetStore.migrateLegacy() }.getOrNull()
        datasetStore.ensureDefault()
        val datasets = datasetStore.list()
        _state.update { it.copy(savedDatasets = datasets) }
        val selectedId = prefs.getString("active_dataset_id", null)
        val checkpoint = checkpointStore.load()
        val restored = checkpoint?.let { saved ->
            runCatching {
                val selected = saved.datasetIds.map { id -> datasets.first { it.id == id } }
                val items = selected.flatMap { datasetStore.load(it) }
                if (!saved.isValid(items.size, _state.value.settings)) return@runCatching false
                activeDatasetIds = saved.datasetIds
                _state.update { it.copy(items = items, order = saved.order, position = saved.position,
                    datasetName = selected.joinToString(" → ") { dataset -> dataset.name },
                    activeDatasetId = selected.singleOrNull()?.id ?: "playlist",
                    phase = if (saved.completed) LessonPhase.COMPLETE else LessonPhase.PAUSED,
                    message = if (saved.completed) "지난 학습을 완료했습니다. 처음부터 버튼으로 다시 시작할 수 있어요."
                        else "마지막 학습 위치를 불러왔습니다. 재생 버튼을 누르면 이어집니다.") }
                true
            }.getOrDefault(false)
        } ?: false
        if (!restored) (datasets.firstOrNull { it.id == selectedId } ?: migrated ?: datasets.firstOrNull())?.let(::selectDataset)
        viewModelScope.launch {
            state.map { current ->
                if (current.order.isEmpty() || activeDatasetIds.isEmpty()) null else LearningCheckpoint(
                    activeDatasetIds, current.order, current.position, current.settings.repeatCount,
                    current.settings.order, current.phase == LessonPhase.COMPLETE)
            }.distinctUntilChanged().collect { checkpointStore.save(it) }
        }
        viewModelScope.launch {
            var last = SystemClock.elapsedRealtime()
            var ticks = 0
            while (true) {
                delay(1_000)
                val now = SystemClock.elapsedRealtime()
                val active = _state.value.phase in setOf(LessonPhase.SPEAKING, LessonPhase.LISTENING)
                if (active) _state.update { it.copy(statistics = statsStore.add(seconds = ((now - last) / 1_000).coerceIn(0, 2))) }
                last = now
                if (++ticks % 5 == 0) statsStore.save()
            }
        }
        viewModelScope.launch {
            // One listening attempt = one chunk of the recording.
            var previous = _state.value.phase
            state.map { it.phase }.distinctUntilChanged().collect { phase ->
                if (phase == LessonPhase.LISTENING && previous != LessonPhase.LISTENING) recorder.beginAttempt()
                else if (phase != LessonPhase.LISTENING && previous == LessonPhase.LISTENING) recorder.endAttempt()
                previous = phase
            }
        }
        recorder.recoverInterrupted(::showMessage)
    }

    fun isRecording(): Boolean = recorder.active
    fun onAudioChunk(buffer: ByteArray, count: Int) = recorder.appendAudio(buffer, count)

    fun toggleRecording() {
        if (_state.value.recordingStartedAt == null) {
            recorder.start()
            if (_state.value.phase == LessonPhase.LISTENING) recorder.beginAttempt()
            _state.update { it.copy(recordingStartedAt = SystemClock.elapsedRealtime()) }
        } else {
            _state.update { it.copy(recordingStartedAt = null) }
            recorder.stop(::showMessage)
        }
    }

    override fun onCleared() {
        recorder.stop { }
        super.onCleared()
    }

    private fun recordAttempt(correct: Boolean) {
        if (attemptRecorded) return
        attemptRecorded = true
        _state.update { it.copy(statistics = statsStore.add(attempt = true, correct = correct)) }
        statsStore.save()
    }

    fun pauseForBackground() {
        timerJob?.cancel()
        advanceJob?.cancel()
        if (_state.value.phase !in setOf(LessonPhase.IDLE, LessonPhase.COMPLETE))
            _state.update { it.copy(phase = LessonPhase.PAUSED) }
        saveCheckpointNow()
        checkpointStore.flush()
        statsStore.save()
    }

    fun saveProgress() = saveCheckpointNow()

    private fun saveCheckpointNow() {
        val current = _state.value
        checkpointStore.save(if (current.order.isEmpty() || activeDatasetIds.isEmpty()) null else LearningCheckpoint(
            activeDatasetIds, current.order, current.position, current.settings.repeatCount,
            current.settings.order, current.phase == LessonPhase.COMPLETE))
    }

    fun selectDatasets(ids: List<String>) {
        if (ids.isEmpty()) return
        viewModelScope.launch {
            runCatching {
                val selected = ids.mapNotNull { id -> datasetStore.list().firstOrNull { it.id == id } }
                val items = selected.flatMap { datasetStore.load(it) }
                resetWith(items, selected.joinToString(" → ") { it.name.removeSuffix(".csv").removeSuffix(".xlsx") }, "playlist", selected.map { it.id })
            }.onFailure { _state.update { state -> state.copy(message = "데이터셋을 불러오지 못했습니다: ${it.message}") } }
        }
    }

    fun importDatasets(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            val resolver = getApplication<Application>().contentResolver
            val imported = mutableListOf<Pair<SavedDataset, List<SentencePair>>>()
            var lastError: String? = null
            var failures = 0
            for (uri in uris) {
                runCatching {
                    val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                        if (it.moveToFirst()) it.getString(0) else null
                    } ?: "dataset.xlsx"
                    runCatching { resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                    datasetStore.import(uri, name)
                }.onSuccess { imported += it }
                    .onFailure { failures++; lastError = it.message }
            }
            if (imported.isEmpty()) {
                _state.update { it.copy(message = lastError ?: "파일을 읽지 못했습니다.") }
                return@launch
            }
            _state.update { it.copy(savedDatasets = datasetStore.list()) }
            if (imported.size == 1) {
                val (saved, items) = imported.first()
                prefs.edit().putString("active_dataset_id", saved.id).apply()
                resetWith(items, saved.name, saved.id)
                if (failures > 0) showMessage("${failures}개 파일은 읽지 못했습니다.")
            } else {
                resetWith(
                    imported.flatMap { it.second },
                    imported.joinToString(" → ") { it.first.name.removeSuffix(".csv").removeSuffix(".xlsx") },
                    "playlist",
                    imported.map { it.first.id }
                )
                showMessage("${imported.size}개 데이터셋을 불러와 이어서 학습합니다." + if (failures > 0) " (${failures}개 실패)" else "")
            }
        }
    }

    fun selectDataset(dataset: SavedDataset) {
        if (_state.value.activeDatasetId == dataset.id && _state.value.items.isNotEmpty()) {
            pauseForBackground()
            return
        }
        viewModelScope.launch {
            runCatching { datasetStore.load(dataset) }
                .onSuccess {
                    prefs.edit().putString("active_dataset_id", dataset.id).apply()
                    resetWith(it, dataset.name, dataset.id)
                }
                .onFailure { _state.update { state -> state.copy(message = "저장된 데이터셋을 다시 불러오지 못했습니다.") } }
        }
    }

    fun showMessage(text: String) = _state.update { it.copy(message = text) }
    fun onBluetoothDevicesChanged(devices: List<BluetoothChoice>) = _state.update { it.copy(bluetoothDevices = devices) }

    fun editDataset(dataset: SavedDataset) {
        pauseForBackground()
        runCatching { datasetStore.load(dataset) }
            .onSuccess { rows -> _state.update { it.copy(editingDataset = dataset, editingItems = rows) } }
            .onFailure { _state.update { state -> state.copy(message = it.message) } }
    }
    fun closeEditor() = _state.update { it.copy(editingDataset = null, editingItems = emptyList()) }
    fun saveSentence(index: Int, korean: String, english: String) {
        val state = _state.value
        val dataset = state.editingDataset ?: return
        if (english.isBlank() || index !in state.editingItems.indices) return
        runCatching {
            val rows = state.editingItems.toMutableList().apply { this[index] = SentencePair(korean.trim(), english.trim()) }
            datasetStore.saveEdits(dataset, rows)
            val activeItems = if (dataset.id in activeDatasetIds) activeDatasetIds.flatMap { id -> datasetStore.load(datasetStore.list().first { it.id == id }) } else state.items
            _state.update { it.copy(editingItems = rows, items = activeItems, phase = LessonPhase.PAUSED,
                heardText = "", liveText = "", retryText = null, matchedWords = emptyList(), message = "문장을 저장했습니다.") }
            saveCheckpointNow()
        }.onFailure { _state.update { state -> state.copy(message = it.message ?: "문장을 저장하지 못했습니다.") } }
    }

    fun deleteDataset(dataset: SavedDataset) {
        timerJob?.cancel()
        advanceJob?.cancel()
        datasetStore.delete(dataset)
        val remaining = datasetStore.list()
        if (dataset.id in activeDatasetIds) {
            prefs.edit().remove("active_dataset_id").apply()
            val replacement = remaining.firstOrNull()
            if (replacement != null) {
                _state.update { it.copy(savedDatasets = remaining, message = "데이터셋을 삭제했습니다.") }
                selectDataset(replacement)
            } else {
                activeDatasetIds = emptyList()
                checkpointStore.save(null)
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

    private fun resetWith(items: List<SentencePair>, name: String, id: String, ids: List<String> = listOf(id)) {
        timerJob?.cancel()
        advanceJob?.cancel()
        attemptRecorded = false
        activeDatasetIds = ids
        val order = buildOrder(items.size, _state.value.settings.order)
        _state.update { it.copy(items = items, order = order, position = 0, phase = LessonPhase.IDLE, datasetName = name, activeDatasetId = id,
            heardText = "", liveText = "", retryText = null, matchedWords = emptyList(), score = null, allWordsMatched = false, remainingSeconds = 0, message = null) }
        saveCheckpointNow()
    }

    fun updateSettings(settings: LearningSettings) {
        prefs.edit()
            .putString("mode", settings.mode.name)
            .putString("order", settings.order.name)
            .putInt("repeat_count", settings.repeatCount)
            .putBoolean("auto_advance_sentence", settings.autoAdvanceSentence)
            .putBoolean("mirror_audio", settings.mirrorAudio)
            .putInt("timeout", 20)
            .putInt("pass_score", settings.passScore)
            .putString("voice_accent", settings.voiceAccent.name)
            .putBoolean("outdoor_audio", settings.outdoorAudio)
            .putBoolean("phone_mic", settings.phoneMic)
            .putString("strictness", settings.strictness.name)
            .putString("bluetooth_input", settings.bluetoothInputAddress)
            .apply()
        timerJob?.cancel()
        advanceJob?.cancel()
        _state.update {
            val rebuild = settings.order != it.settings.order || settings.repeatCount != it.settings.repeatCount
            it.copy(settings = settings.copy(timeoutSeconds = 20),
                order = if (rebuild) buildOrder(it.items.size, settings.order, settings.repeatCount) else it.order,
                position = if (rebuild) 0 else it.position,
                phase = if (it.phase == LessonPhase.COMPLETE && !rebuild) LessonPhase.COMPLETE else LessonPhase.PAUSED, heardText = "", liveText = "", retryText = null, matchedWords = emptyList(), score = null, allWordsMatched = false, remainingSeconds = 0)
        }
        saveCheckpointNow()
    }

    fun startSpeaking() {
        if (_state.value.current == null) return
        attemptRecorded = false
        timerJob?.cancel()
        advanceJob?.cancel()
        _state.update { it.copy(phase = LessonPhase.SPEAKING, promptRequestId = it.promptRequestId + 1, heardText = "", liveText = "", retryText = null, matchedWords = emptyList(), score = null, allWordsMatched = false, feedbackSuccess = null, message = null) }
        saveCheckpointNow()
    }

    fun onPromptFinished() {
        if (_state.value.phase != LessonPhase.SPEAKING) return
        deadline = SystemClock.elapsedRealtime() + 20_000L
        _state.update { it.copy(
            phase = LessonPhase.LISTENING,
            remainingSeconds = it.settings.timeoutSeconds,
            listenRequestId = it.listenRequestId + 1
        ) }
        startTimer()
    }

    fun onRecognizerReady() {
        if (_state.value.phase != LessonPhase.LISTENING) return
        if (deadline == 0L) {
            deadline = SystemClock.elapsedRealtime() + _state.value.settings.timeoutSeconds * 1_000L
        }
        startTimer()
    }

    fun onRecognition(candidates: List<String>) {
        // cancel() can deliver a late recognizer callback while TTS is already playing.
        // It belongs to the previous session and must not turn the new countdown into 0.
        if (_state.value.phase != LessonPhase.LISTENING) return
        val expected = _state.value.current?.english ?: return
        val remaining = if (deadline == 0L) _state.value.settings.timeoutSeconds
            else ((deadline - SystemClock.elapsedRealtime() + 999) / 1_000).toInt().coerceAtLeast(0)
        val strictness = _state.value.settings.strictness
        val result = RetryEvaluator.evaluate(expected, candidates, _state.value.matchedWords, strictness)
        val text = result.text.ifBlank { _state.value.heardText }
        // An empty callback (recognizer error/no match) must not erase what was shown.
        val matchedWords = if (result.text.isBlank() && _state.value.matchedWords.size == result.matched.size)
            _state.value.matchedWords else result.matched
        val score = if (matchedWords.all { it }) 100 else matchedWords.count { it } * 100 / matchedWords.size.coerceAtLeast(1)
        val allWordsMatched = matchedWords.isNotEmpty() && matchedWords.all { it }
        if (allWordsMatched) {
            recordAttempt(true)
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
                retryText = RetryEvaluator.remaining(expected, matchedWords)
                    .takeIf { matchedWords.any { it } && strictness != RecognitionStrictness.STRICT },
                remainingSeconds = remaining,
                listenRequestId = it.listenRequestId + 1
            ) }
        } else if (_state.value.phase == LessonPhase.LISTENING) {
            recordAttempt(false)
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
        val latest = candidates.firstOrNull(String::isNotBlank).orEmpty()
        if (latest.isBlank()) return
        val expected = _state.value.current?.english ?: return
        val preview = RetryEvaluator.evaluate(expected, listOf(latest), _state.value.matchedWords, _state.value.settings.strictness)
        if (preview.matched.isNotEmpty() && preview.matched.all { it }) {
            onRecognition(listOf(latest))
        } else {
            // NORMAL/EASY: a word the recognizer already produced exactly stays confirmed
            // (preview.matched always includes the confirmed ones). Otherwise it turns blue
            // while speaking and back to grey when a later hypothesis, a no-match error or
            // the next recognizer session replaces the live text.
            // STRICT: each utterance stands alone, so preview.matched only covers this one.
            _state.update { it.copy(liveText = latest, matchedWords = preview.matched) }
        }
    }

    fun retryListening() {
        attemptRecorded = false
        advanceJob?.cancel()
        timerJob?.cancel()
        deadline = SystemClock.elapsedRealtime() + 20_000L
        _state.update {
            val preservePartialMatches = !it.retryText.isNullOrBlank() && !it.allWordsMatched &&
                it.settings.strictness != RecognitionStrictness.STRICT
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
        startTimer()
    }

    fun onFeedbackFinished(sequence: Long, success: Boolean) {
        if (_state.value.feedbackSequence != sequence) return
        if (success && _state.value.phase == LessonPhase.CORRECT) {
            advanceJob?.cancel()
            advanceJob = viewModelScope.launch {
                delay(SUCCESS_RESULT_DISPLAY_MILLIS)
                if (_state.value.phase == LessonPhase.CORRECT && _state.value.feedbackSequence == sequence) advanceRepetition()
            }
        } else if (!success && _state.value.phase == LessonPhase.RETRYING && _state.value.settings.autoAdvanceSentence) {
            advanceJob?.cancel()
            advanceJob = viewModelScope.launch {
                delay(500)
                if (_state.value.phase == LessonPhase.RETRYING && _state.value.feedbackSequence == sequence) advanceRepetition()
            }
        }
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
        attemptRecorded = false
        timerJob?.cancel()
        advanceJob?.cancel()
        _state.update { it.copy(position = ((it.position / it.settings.repeatCount - 1) * it.settings.repeatCount).coerceAtLeast(0), phase = LessonPhase.SPEAKING, heardText = "", liveText = "", retryText = null, matchedWords = emptyList(), score = null, allWordsMatched = false, feedbackSuccess = null) }
        saveCheckpointNow()
    }

    private fun advanceRepetition() {
        attemptRecorded = false
        timerJob?.cancel()
        _state.update {
            if (it.position >= it.order.lastIndex) it.copy(phase = LessonPhase.COMPLETE, feedbackSuccess = null, remainingSeconds = 0)
            else it.copy(position = it.position + 1, phase = LessonPhase.SPEAKING, heardText = "", liveText = "",
                retryText = null, matchedWords = emptyList(), score = null, allWordsMatched = false, feedbackSuccess = null, remainingSeconds = 0)
        }
        saveCheckpointNow()
    }

    fun next() {
        if (_state.value.current == null) return
        attemptRecorded = false
        nextSentence()
    }

    private fun nextSentence() {
        timerJob?.cancel()
        advanceJob?.cancel()
        _state.update { state ->
            val nextPosition = LearningPlan.nextSentence(state.order, state.position)
            if (nextPosition == null) state.copy(phase = LessonPhase.COMPLETE, feedbackSuccess = null, remainingSeconds = 0)
            else state.copy(position = nextPosition, phase = LessonPhase.SPEAKING, heardText = "", liveText = "", retryText = null, matchedWords = emptyList(), score = null,
                allWordsMatched = false, feedbackSuccess = null, remainingSeconds = 0)
        }
        saveCheckpointNow()
    }

    fun restart() {
        attemptRecorded = false
        timerJob?.cancel()
        advanceJob?.cancel()
        _state.update {
            if (it.items.isEmpty()) it.copy(message = "먼저 엑셀 데이터셋을 불러오세요.")
            else it.copy(order = buildOrder(it.items.size, it.settings.order), position = 0, phase = LessonPhase.SPEAKING,
                heardText = "", liveText = "", retryText = null, matchedWords = emptyList(), score = null, allWordsMatched = false, feedbackSuccess = null, remainingSeconds = 0)
        }
        saveCheckpointNow()
    }

    fun clearMessage() = _state.update { it.copy(message = null) }

    private fun startTimer() {
        timerJob?.cancel()
        timerJob = viewModelScope.launch {
            while (true) {
                val remaining = ((deadline - SystemClock.elapsedRealtime() + 999) / 1_000).toInt()
                if (remaining <= 0) break
                _state.update { it.copy(remainingSeconds = remaining) }
                delay(250)
            }
            if (_state.value.phase == LessonPhase.LISTENING) {
                val snapshot = _state.value
                val liveMatches = timeoutMatches(snapshot)
                recordAttempt(liveMatches.isNotEmpty() && liveMatches.all { it })
            }
            _state.update {
                if (it.phase != LessonPhase.LISTENING) it else {
                    val recognized = it.liveText
                    val expected = it.current?.english.orEmpty()
                    val matchedWords = timeoutMatches(it)
                    val complete = matchedWords.isNotEmpty() && matchedWords.all { matched -> matched }
                    it.copy(
                        phase = if (complete) LessonPhase.CORRECT else LessonPhase.RETRYING,
                        heardText = recognized,
                        retryText = if (complete) null else SpeechScorer.displayWords(expected)
                            .filterIndexed { index, _ -> !matchedWords.getOrElse(index) { false } }
                            .joinToString(" ")
                            .ifBlank { expected },
                        matchedWords = matchedWords,
                        score = SpeechScorer.score(expected, recognized, it.settings.strictness),
                        allWordsMatched = complete,
                        feedbackSuccess = complete,
                        feedbackSequence = it.feedbackSequence + 1,
                        remainingSeconds = 0
                    )
                }
            }
        }
    }

    private fun timeoutMatches(snapshot: LearningUiState): List<Boolean> {
        val expected = snapshot.current?.english.orEmpty()
        val strictness = snapshot.settings.strictness
        val live = RetryEvaluator.evaluate(expected, listOf(snapshot.liveText), snapshot.matchedWords, strictness).matched
        // In STRICT mode the last utterance stands on its own, but keep whatever the
        // final recognizer result already confirmed for that same utterance.
        return if (strictness == RecognitionStrictness.STRICT && live.count { it } < snapshot.matchedWords.count { it })
            snapshot.matchedWords else live
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
        timeoutSeconds = 20,
        mirrorAudio = prefs.getBoolean("mirror_audio", false),
        passScore = prefs.getInt("pass_score", 65),
        outdoorAudio = prefs.getBoolean("outdoor_audio", false),
        phoneMic = prefs.getBoolean("phone_mic", false),
        voiceAccent = runCatching { VoiceAccent.valueOf(prefs.getString("voice_accent", null) ?: "US") }.getOrDefault(VoiceAccent.US),
        strictness = runCatching { RecognitionStrictness.valueOf(prefs.getString("strictness", null) ?: "NORMAL") }.getOrDefault(RecognitionStrictness.NORMAL),
        bluetoothInputAddress = prefs.getString("bluetooth_input", "") ?: ""
        )
    }

    private fun buildOrder(size: Int, order: PlayOrder, repeat: Int = _state.value.settings.repeatCount): List<Int> =
        LearningPlan.build(size, order, repeat)
}
