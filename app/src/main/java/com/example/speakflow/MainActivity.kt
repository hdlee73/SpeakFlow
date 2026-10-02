package com.example.speakflow

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.speakflow.model.LearningMode
import com.example.speakflow.model.LessonPhase
import com.example.speakflow.speech.SpeechEngine
import com.example.speakflow.ui.SpeakFlowApp

class MainActivity : ComponentActivity() {
    private val viewModel: LearningViewModel by viewModels()
    private lateinit var speech: SpeechEngine

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        hideStatusBar()
        speech = SpeechEngine(
            context = this,
            onPromptFinished = viewModel::onPromptFinished,
            onRecognizerReady = viewModel::onRecognizerReady,
            onPartialResult = viewModel::onPartialRecognition,
            onResult = viewModel::onRecognition,
            onUnavailable = viewModel::onRecognitionUnavailable,
            onBluetoothDevicesChanged = viewModel::onBluetoothDevicesChanged,
            onNotice = viewModel::showMessage,
            recordingTee = viewModel::onAudioChunk,
            isRecording = viewModel::isRecording,
            onInputDeviceChanged = viewModel::onMicrophoneChanged
        )
        setContent {
            val state by viewModel.state.collectAsStateWithLifecycle()
            var settingsOpen by rememberSaveable { mutableStateOf(false) }
            var datasetsOpen by rememberSaveable { mutableStateOf(false) }
            var bluetoothPermissionRequested by rememberSaveable { mutableStateOf(false) }
            SideEffect { hasOpenDialog = settingsOpen || datasetsOpen || state.editingDataset != null }
            val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
                viewModel.importDatasets(uris)
            }
            val audioPermissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
                val microphoneGranted = grants[Manifest.permission.RECORD_AUDIO]
                    ?: (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
                if (microphoneGranted) viewModel.retryListening()
                else viewModel.onRecognitionUnavailable("마이크 권한이 거부되었습니다.")
            }

            LaunchedEffect(state.phase, state.position, state.listenRequestId, state.promptRequestId) {
                speech.mirrorAudio = state.settings.mirrorAudio
                speech.phoneMic = state.settings.phoneMic
                speech.bluetoothInputAddress = state.settings.bluetoothInputAddress
                speech.outdoorAudio = state.settings.outdoorAudio
                speech.biasTowardExpected = state.settings.strictness != com.example.speakflow.model.RecognitionStrictness.STRICT
                speech.recognitionLanguage = if (state.settings.voiceAccent == com.example.speakflow.model.VoiceAccent.UK) "en-GB" else "en-US"
                when (state.phase) {
                    LessonPhase.SPEAKING -> state.current?.let {
                        val korean = state.settings.mode == LearningMode.TRANSLATION && it.korean.isNotBlank()
                        speech.speak(
                            text = if (korean) it.korean else it.english,
                            korean = korean,
                            accent = state.settings.voiceAccent
                        )
                    }
                    LessonPhase.LISTENING -> {
                        val requiredPermissions = buildList {
                            if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                                add(Manifest.permission.RECORD_AUDIO)
                            }
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                                ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED &&
                                !bluetoothPermissionRequested
                            ) {
                                add(Manifest.permission.BLUETOOTH_CONNECT)
                            }
                        }
                        if (requiredPermissions.isEmpty()) {
                            val expected = state.current?.english.orEmpty()
                            speech.listen(
                                if (state.settings.strictness == com.example.speakflow.model.RecognitionStrictness.STRICT) expected
                                else com.example.speakflow.speech.RetryEvaluator.remaining(expected, state.matchedWords)
                            )
                        } else {
                            if (Manifest.permission.BLUETOOTH_CONNECT in requiredPermissions) bluetoothPermissionRequested = true
                            audioPermissions.launch(requiredPermissions.toTypedArray())
                        }
                    }
                    LessonPhase.RETRYING, LessonPhase.PAUSED, LessonPhase.COMPLETE, LessonPhase.IDLE, LessonPhase.TIMED_OUT -> speech.stop()
                    else -> Unit
                }
            }

            LaunchedEffect(state.feedbackSequence) {
                val success = state.feedbackSuccess ?: return@LaunchedEffect
                val sequence = state.feedbackSequence
                if (sequence > 0L) {
                    if (success) {
                        speech.playSuccessSound {
                            viewModel.onFeedbackFinished(sequence, true)
                        }
                    } else {
                        // Incorrect/unfinished attempts remain completely silent.
                        viewModel.onFeedbackFinished(sequence, false)
                    }
                }
            }

            SpeakFlowApp(
                state = state,
                settingsOpen = settingsOpen,
                datasetsOpen = datasetsOpen,
                onSettingsOpen = { viewModel.pauseForBackground(); speech.refreshBluetoothDevices(); settingsOpen = true },
                onSettingsClose = { settingsOpen = false },
                onSettingsSave = { viewModel.updateSettings(it); settingsOpen = false },
                onDatasetsOpen = { viewModel.pauseForBackground(); datasetsOpen = true },
                onDatasetsClose = { datasetsOpen = false },
                onDatasetSelect = { viewModel.selectDataset(it); datasetsOpen = false },
                onDatasetDelete = viewModel::deleteDataset,
                onDatasetEdit = { datasetsOpen = false; viewModel.editDataset(it) },
                onEditorClose = viewModel::closeEditor,
                onSentenceSave = viewModel::saveSentence,
                onOpenUpdate = { startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://github.com/hdlee73/SpeakFlow/releases/latest"))) },
                onDatasetSequence = { viewModel.selectDatasets(it); datasetsOpen = false },
                onImport = { datasetsOpen = false; filePicker.launch(arrayOf("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "text/csv", "application/vnd.ms-excel")) },
                onPlayPause = viewModel::togglePause,
                onRestart = viewModel::restart,
                onPrevious = viewModel::previous,
                onNext = viewModel::next,
                onReplay = viewModel::startSpeaking,
                onRetry = viewModel::retryListening,
                onToggleRecording = viewModel::toggleRecording,
                onMessageDismiss = viewModel::clearMessage
            )
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode in setOf(KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER) &&
            !hasOpenDialog) {
            if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) viewModel.next()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private var hasOpenDialog = false

    override fun onPause() {
        viewModel.saveProgress()
        super.onPause()
    }

    override fun onStop() {
        viewModel.pauseForBackground()
        speech.stop()
        super.onStop()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideStatusBar()
    }

    private fun hideStatusBar() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.statusBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onDestroy() {
        speech.destroy()
        super.onDestroy()
    }
}
