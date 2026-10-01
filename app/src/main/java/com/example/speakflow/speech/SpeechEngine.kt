package com.example.speakflow.speech

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaPlayer
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
import android.speech.tts.Voice
import androidx.core.content.ContextCompat
import com.example.speakflow.R
import com.example.speakflow.model.VoiceAccent
import com.example.speakflow.model.VoiceGender
import java.util.Locale

class SpeechEngine(
    context: Context,
    private val onPromptFinished: () -> Unit,
    private val onRecognizerReady: () -> Unit,
    private val onPartialResult: (List<String>) -> Unit,
    private val onResult: (List<String>) -> Unit,
    private val onUnavailable: (String) -> Unit,
    private val onInputDeviceChanged: (String) -> Unit
) {
    private data class PendingPrompt(
        val text: String,
        val korean: Boolean,
        val accent: VoiceAccent,
        val gender: VoiceGender
    )

    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(AudioManager::class.java)
    private var ttsReady = false
    private var acceptingRecognitionResults = false
    private var recognitionStarting = false
    private var bluetoothRouteActive = false
    private val streamsMutedForRecognition = mutableSetOf<Int>()
    private var pendingListen: Runnable? = null
    private var pendingReadyTimeout: Runnable? = null
    private var pendingPrompt: PendingPrompt? = null
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
                tts.setPitch(1f)
                pendingPrompt?.also { prompt ->
                    pendingPrompt = null
                    speakNow(prompt.text, prompt.korean, prompt.accent, prompt.gender)
                }
            } else {
                pendingPrompt = null
                mainHandler.post { onUnavailable("음성 합성 엔진을 준비하지 못했습니다.") }
            }
        }
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) {
                // Bluetooth media playback can finish at the TTS engine slightly before
                // the headset has rendered its final audio frames. Leave a short tail
                // before switching the same device into communication/microphone mode.
                mainHandler.postDelayed(onPromptFinished, 300L)
            }
            @Deprecated("Deprecated in Java") override fun onError(utteranceId: String?) {
                mainHandler.post { onUnavailable("예문 음성을 재생하지 못했습니다. 휴대전화의 미디어 음량과 TTS 설정을 확인해 주세요.") }
            }
        })
        recognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle) {
                if (!acceptingRecognitionResults) return
                acceptingRecognitionResults = false
                recognitionStarting = false
                val matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                onResult(matches.orEmpty())
            }
            override fun onError(error: Int) {
                if (!acceptingRecognitionResults) return
                acceptingRecognitionResults = false
                recognitionStarting = false
                if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) onUnavailable("마이크 권한이 필요합니다.")
                else onResult(emptyList())
            }
            override fun onReadyForSpeech(params: Bundle?) {
                if (!recognitionStarting) return
                pendingReadyTimeout?.let(mainHandler::removeCallbacks)
                pendingReadyTimeout = null
                recognitionStarting = false
                acceptingRecognitionResults = true
                onRecognizerReady()
            }
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onPartialResults(partialResults: Bundle?) {
                if (!acceptingRecognitionResults) return
                onPartialResult(partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty())
            }
            override fun onSegmentResults(segmentResults: Bundle) {
                if (!acceptingRecognitionResults) return
                onPartialResult(segmentResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty())
            }
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
    }

    fun speak(text: String, korean: Boolean, accent: VoiceAccent, gender: VoiceGender) {
        cancelPendingListen()
        acceptingRecognitionResults = false
        recognizer?.cancel()
        restoreRecognitionAudio()
        restoreAudioRoute()
        if (!ttsReady) {
            pendingPrompt = PendingPrompt(text, korean, accent, gender)
            return
        }
        speakNow(text, korean, accent, gender)
    }

    private fun speakNow(text: String, korean: Boolean, accent: VoiceAccent, gender: VoiceGender) {
        val locale = if (korean) Locale.KOREA else when (accent) {
            VoiceAccent.US -> Locale.US
            VoiceAccent.UK -> Locale.UK
        }
        val languageResult = tts.setLanguage(locale)
        if (languageResult == TextToSpeech.LANG_MISSING_DATA || languageResult == TextToSpeech.LANG_NOT_SUPPORTED) {
            onUnavailable(if (korean) "한국어 TTS 음성이 설치되어 있지 않습니다." else "영어 TTS 음성이 설치되어 있지 않습니다.")
            return
        }
        selectNaturalVoice(locale, if (korean) null else gender)
        tts.setSpeechRate(if (korean) .90f else .84f)
        tts.setPitch(1f)
        val params = Bundle().apply { putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1f) }
        if (tts.speak(naturalizeForSpeech(text, korean), TextToSpeech.QUEUE_FLUSH, params, "prompt") == TextToSpeech.ERROR) {
            onUnavailable("예문 음성을 재생하지 못했습니다. 미디어 음량을 확인해 주세요.")
        }
    }

    private fun selectNaturalVoice(locale: Locale, gender: VoiceGender?) {
        val voice = tts.voices.orEmpty()
            .filter { it.locale.language == locale.language }
            .maxWithOrNull(
                compareBy<Voice> { it.locale.country == locale.country }
                    .thenBy { voiceGenderScore(it, gender) }
                    .thenBy { it.quality }
                    .thenBy { if (it.isNetworkConnectionRequired) 1 else 0 }
                    .thenBy { -it.latency }
            )
        if (voice != null) runCatching { tts.voice = voice }
    }

    private fun voiceGenderScore(voice: Voice, requested: VoiceGender?): Int {
        if (requested == null) return 0
        val descriptor = buildString {
            append(voice.name.lowercase(Locale.US))
            append(' ')
            append(voice.features.joinToString(" ").lowercase(Locale.US))
        }
        val female = listOf("female", "woman", "f01", "_f_", "-f-").any(descriptor::contains)
        val male = !female && listOf("male", "man", "m01", "_m_", "-m-").any(descriptor::contains)
        return when (requested) {
            VoiceGender.FEMALE -> if (female) 2 else if (male) -1 else 0
            VoiceGender.MALE -> if (male) 2 else if (female) -1 else 0
        }
    }

    private fun naturalizeForSpeech(text: String, korean: Boolean): String {
        val trimmed = text.trim()
        if (korean || trimmed.isEmpty() || trimmed.last() in ".?!") return trimmed
        val firstWord = trimmed.substringBefore(' ').lowercase(Locale.US).trim('"', '\'', '“', '‘')
        val questionStarters = setOf(
            "who", "what", "when", "where", "why", "how",
            "am", "is", "are", "was", "were", "do", "does", "did",
            "can", "could", "will", "would", "shall", "should", "have", "has", "had"
        )
        return trimmed + if (firstWord in questionStarters) "?" else "."
    }

    fun listen(expectedText: String) {
        val speechRecognizer = recognizer
            ?: run { onUnavailable("이 기기에서 음성 인식을 사용할 수 없습니다."); return }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "en-US")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 8)
            val expectedWords = expectedText.trim().split(Regex("\\s+")).filter(String::isNotBlank)
            val minimumLength = (expectedWords.size * 300L).coerceIn(1_500L, 7_000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, minimumLength)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2_600L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1_600L)
            if (Build.VERSION.SDK_INT >= 33) {
                val normalizedWords = expectedWords.map { it.trim('.', ',', '!', '?', ';', ':', '"', '\'', '’') }
                    .filter { it.length >= 3 }
                val phrases = buildList {
                    add(expectedText)
                    addAll(normalizedWords)
                    addAll(normalizedWords.zipWithNext { first, second -> "$first $second" })
                }.distinct().take(24)
                putStringArrayListExtra(RecognizerIntent.EXTRA_BIASING_STRINGS, ArrayList(phrases))
                putExtra(RecognizerIntent.EXTRA_ENABLE_BIASING_DEVICE_CONTEXT, true)
                putExtra(RecognizerIntent.EXTRA_ENABLE_FORMATTING, RecognizerIntent.FORMATTING_OPTIMIZE_QUALITY)
                putExtra(RecognizerIntent.EXTRA_HIDE_PARTIAL_TRAILING_PUNCTUATION, true)
            }
        }
        cancelPendingListen()
        silenceRecognitionAudio()
        acceptingRecognitionResults = false
        recognitionStarting = false
        speechRecognizer.cancel()
        // Give a cancelled/finished recognizer session time to release before a retry.
        // Late callbacks remain ignored until the new session reports ready.
        val routeDelay = maxOf(selectInputDevice(), 250L)
        pendingListen = Runnable {
            pendingListen = null
            acceptingRecognitionResults = false
            recognitionStarting = true
            runCatching { speechRecognizer.startListening(intent) }.onSuccess {
                pendingReadyTimeout = Runnable {
                    pendingReadyTimeout = null
                    if (recognitionStarting) {
                        recognitionStarting = false
                        onResult(emptyList())
                    }
                }.also { mainHandler.postDelayed(it, 5_000L) }
            }.onFailure {
                acceptingRecognitionResults = false
                recognitionStarting = false
                restoreAudioRoute()
                onUnavailable("마이크를 시작하지 못했습니다. 오디오 권한과 블루투스 연결을 확인해 주세요.")
            }
        }.also { mainHandler.postDelayed(it, routeDelay) }
    }

    private fun selectInputDevice(): Long {
        if (bluetoothRouteActive) return 0L
        onInputDeviceChanged("휴대전화 마이크")
        if (audioManager == null) return 0L
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return 0L
                val bluetooth = audioManager.availableCommunicationDevices
                    .filter { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it.type == AudioDeviceInfo.TYPE_BLE_HEADSET }
                    .maxByOrNull { if (it.type == AudioDeviceInfo.TYPE_BLE_HEADSET) 2 else 1 }
                    ?: return 0L
                audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                if (audioManager.setCommunicationDevice(bluetooth)) {
                    bluetoothRouteActive = true
                    onInputDeviceChanged("${bluetooth.productName} 마이크")
                    800L
                } else {
                    audioManager.mode = AudioManager.MODE_NORMAL
                    0L
                }
            } else {
                val bluetooth = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
                    .firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO } ?: return 0L
                audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                @Suppress("DEPRECATION")
                audioManager.startBluetoothSco()
                @Suppress("DEPRECATION")
                audioManager.isBluetoothScoOn = true
                bluetoothRouteActive = true
                onInputDeviceChanged("${bluetooth.productName} 마이크")
                    1_000L
            }
        }.getOrElse {
            bluetoothRouteActive = false
            audioManager.mode = AudioManager.MODE_NORMAL
            0L
        }
    }

    private fun restoreAudioRoute() {
        if (audioManager == null || !bluetoothRouteActive) return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                audioManager.clearCommunicationDevice()
            } else {
                @Suppress("DEPRECATION")
                audioManager.stopBluetoothSco()
                @Suppress("DEPRECATION")
                run { audioManager.isBluetoothScoOn = false }
            }
            audioManager.mode = AudioManager.MODE_NORMAL
        }
        bluetoothRouteActive = false
    }

    private fun cancelPendingListen() {
        pendingListen?.let(mainHandler::removeCallbacks)
        pendingListen = null
        pendingReadyTimeout?.let(mainHandler::removeCallbacks)
        pendingReadyTimeout = null
    }

    private fun silenceRecognitionAudio() {
        if (audioManager == null) return
        listOf(
            AudioManager.STREAM_MUSIC,
            AudioManager.STREAM_SYSTEM,
            AudioManager.STREAM_NOTIFICATION,
            AudioManager.STREAM_VOICE_CALL
        ).forEach { stream ->
            if (stream !in streamsMutedForRecognition && !audioManager.isStreamMute(stream)) {
                runCatching {
                    audioManager.adjustStreamVolume(stream, AudioManager.ADJUST_MUTE, 0)
                    streamsMutedForRecognition += stream
                }
            }
        }
    }

    private fun restoreRecognitionAudio() {
        if (audioManager == null) return
        streamsMutedForRecognition.toList().forEach { stream ->
            runCatching { audioManager.adjustStreamVolume(stream, AudioManager.ADJUST_UNMUTE, 0) }
        }
        streamsMutedForRecognition.clear()
    }

    fun playSuccessSound(onFinished: () -> Unit) {
        restoreRecognitionAudio()
        runCatching {
            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            val player = MediaPlayer.create(appContext, R.raw.result_success, attributes, 0)
                ?: error("결과음을 준비하지 못했습니다.")
            var finished = false
            fun finish() {
                if (finished) return
                finished = true
                player.release()
                onFinished()
            }
            player.setVolume(1f, 1f)
            player.setOnCompletionListener { finish() }
            player.setOnErrorListener { _, _, _ -> finish(); true }
            player.start()
        }.onFailure { mainHandler.post(onFinished) }
    }

    fun stop() { pendingPrompt = null; cancelPendingListen(); acceptingRecognitionResults = false; recognitionStarting = false; recognizer?.cancel(); restoreRecognitionAudio(); restoreAudioRoute(); tts.stop() }
    fun destroy() { pendingPrompt = null; cancelPendingListen(); acceptingRecognitionResults = false; recognitionStarting = false; recognizer?.destroy(); restoreRecognitionAudio(); restoreAudioRoute(); tts.shutdown() }
}
