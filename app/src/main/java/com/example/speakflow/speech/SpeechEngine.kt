package com.example.speakflow.speech

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.media.AudioAttributes
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

class SpeechEngine(
    context: Context,
    private val onPromptFinished: () -> Unit,
    private val onPartialResult: (List<String>) -> Unit,
    private val onResult: (List<String>) -> Unit,
    private val onUnavailable: (String) -> Unit
) {
    private var ttsReady = false
    private var pendingPrompt: Pair<String, Boolean>? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var tts: TextToSpeech
    private val recognizer = if (SpeechRecognizer.isRecognitionAvailable(context)) SpeechRecognizer.createSpeechRecognizer(context) else null

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            if (ttsReady) {
                tts.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                tts.setSpeechRate(.92f)
                pendingPrompt?.also { (text, korean) ->
                    pendingPrompt = null
                    speakNow(text, korean)
                }
            } else {
                pendingPrompt = null
                mainHandler.post { onUnavailable("음성 합성 엔진을 준비하지 못했습니다.") }
            }
        }
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) { mainHandler.post(onPromptFinished) }
            @Deprecated("Deprecated in Java") override fun onError(utteranceId: String?) {
                mainHandler.post { onUnavailable("예문 음성을 재생하지 못했습니다. 휴대전화의 미디어 음량과 TTS 설정을 확인해 주세요.") }
            }
        })
        recognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle) {
                val matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                onResult(matches.orEmpty())
            }
            override fun onError(error: Int) {
                if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) onUnavailable("마이크 권한이 필요합니다.")
                else onResult(emptyList())
            }
            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onPartialResults(partialResults: Bundle?) {
                onPartialResult(partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty())
            }
            override fun onSegmentResults(segmentResults: Bundle) {
                onPartialResult(segmentResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty())
            }
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
    }

    fun speak(text: String, korean: Boolean) {
        recognizer?.cancel()
        if (!ttsReady) {
            pendingPrompt = text to korean
            return
        }
        speakNow(text, korean)
    }

    private fun speakNow(text: String, korean: Boolean) {
        val languageResult = tts.setLanguage(if (korean) Locale.KOREA else Locale.US)
        if (languageResult == TextToSpeech.LANG_MISSING_DATA || languageResult == TextToSpeech.LANG_NOT_SUPPORTED) {
            onUnavailable(if (korean) "한국어 TTS 음성이 설치되어 있지 않습니다." else "영어 TTS 음성이 설치되어 있지 않습니다.")
            return
        }
        val params = Bundle().apply { putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1f) }
        if (tts.speak(text, TextToSpeech.QUEUE_FLUSH, params, "prompt") == TextToSpeech.ERROR) {
            onUnavailable("예문 음성을 재생하지 못했습니다. 미디어 음량을 확인해 주세요.")
        }
    }

    fun listen(expectedText: String) {
        if (recognizer == null) { onUnavailable("이 기기에서 음성 인식을 사용할 수 없습니다."); return }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "en-US")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 300L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 550L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 300L)
            if (Build.VERSION.SDK_INT >= 33) {
                putStringArrayListExtra(RecognizerIntent.EXTRA_BIASING_STRINGS, arrayListOf(expectedText))
            }
        }
        recognizer.startListening(intent)
    }

    fun stop() { pendingPrompt = null; recognizer?.cancel(); tts.stop() }
    fun destroy() { pendingPrompt = null; recognizer?.destroy(); tts.shutdown() }
}
