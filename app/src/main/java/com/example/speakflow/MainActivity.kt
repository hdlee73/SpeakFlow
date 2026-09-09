package com.example.speakflow

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.core.content.ContextCompat
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
        speech = SpeechEngine(
            context = this,
            onPromptFinished = viewModel::onPromptFinished,
            onResult = viewModel::onRecognition,
            onUnavailable = viewModel::onRecognitionUnavailable
        )
        setContent {
            val state by viewModel.state.collectAsStateWithLifecycle()
            var settingsOpen by rememberSaveable { mutableStateOf(false) }
            val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                uri?.let(viewModel::importDataset)
            }
            val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
                if (granted) viewModel.restart() else viewModel.onRecognitionUnavailable("마이크 권한이 거부되었습니다.")
            }

            LaunchedEffect(state.phase, state.position) {
                when (state.phase) {
                    LessonPhase.SPEAKING -> state.current?.let {
                        val korean = state.settings.mode == LearningMode.TRANSLATION
                        speech.speak(if (korean) it.korean else it.english, korean)
                    }
                    LessonPhase.LISTENING -> {
                        if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                            speech.listen()
                        } else micPermission.launch(Manifest.permission.RECORD_AUDIO)
                    }
                    LessonPhase.PAUSED, LessonPhase.COMPLETE, LessonPhase.IDLE -> speech.stop()
                    else -> Unit
                }
            }

            SpeakFlowApp(
                state = state,
                settingsOpen = settingsOpen,
                onSettingsOpen = { settingsOpen = true },
                onSettingsClose = { settingsOpen = false },
                onSettingsSave = { viewModel.updateSettings(it); settingsOpen = false },
                onImport = { filePicker.launch(arrayOf("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "text/csv", "application/vnd.ms-excel")) },
                onPlayPause = viewModel::togglePause,
                onRestart = viewModel::restart,
                onPrevious = viewModel::previous,
                onNext = viewModel::next,
                onReplay = viewModel::startSpeaking,
                onMessageDismiss = viewModel::clearMessage
            )
        }
    }

    override fun onDestroy() {
        speech.destroy()
        super.onDestroy()
    }
}
