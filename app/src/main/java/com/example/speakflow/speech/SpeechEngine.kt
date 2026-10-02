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
import android.os.SystemClock
import android.speech.tts.Voice
import androidx.core.content.ContextCompat
import com.example.speakflow.R
import com.example.speakflow.model.BluetoothChoice
import com.example.speakflow.model.VoiceAccent
import java.util.Locale
import android.bluetooth.BluetoothManager

class SpeechEngine(
    context: Context,
    private val onPromptFinished: () -> Unit,
    private val onRecognizerReady: () -> Unit,
    private val onPartialResult: (List<String>) -> Unit,
    private val onResult: (List<String>) -> Unit,
    private val onUnavailable: (String) -> Unit,
    private val onBluetoothDevicesChanged: (List<BluetoothChoice>) -> Unit,
    private val onNotice: (String) -> Unit,
    /** Every chunk of the app-owned microphone input, for the recording button. */
    private val recordingTee: (ByteArray, Int) -> Unit,
    private val isRecording: () -> Boolean,
    private val onInputDeviceChanged: (String) -> Unit
) {
    private data class PendingPrompt(
        val text: String,
        val korean: Boolean,
        val accent: VoiceAccent
    )

    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(AudioManager::class.java)
    var mirrorAudio: Boolean = false
    var outdoorAudio: Boolean = false
    private var recordingNoticeShown = false
    var phoneMic: Boolean = false
    /** Empty = automatic. Otherwise the address of the headset chosen in settings. */
    var bluetoothInputAddress: String = ""
    /**
     * Biasing strings nudge the recognizer toward the expected sentence. This is what
     * keeps recognition usable with a noisy or narrow-band (Bluetooth) microphone,
     * but it can also "correct" a mispronounced word, so STRICT turns it off.
     */
    var biasTowardExpected: Boolean = false
    var recognitionLanguage: String = "en-US"
    private var injectionFailed = false
    private var outdoorSource: OutdoorAudioSource? = null
    private var recognitionGeneration = 0L
    private var promptVersion = 0L
    private var currentPromptId: String? = null
    private var ttsReady = false
    private var acceptingRecognitionResults = false
    private var recognitionStarting = false
    private var bluetoothRouteActive = false
    private var communicationRouteActive = false
    private val streamsMutedForRecognition = mutableSetOf<Int>()
    private var pendingListen: Runnable? = null
    private var pendingReadyTimeout: Runnable? = null
    private var pendingPrompt: PendingPrompt? = null
    private var pendingSpeak: Runnable? = null
    private var promptWatchdog: Runnable? = null
    @Volatile private var promptStarted = false
    private var appliedVoiceKey: String? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var tts: TextToSpeech
    private var recognizer = if (SpeechRecognizer.isRecognitionAvailable(context)) SpeechRecognizer.createSpeechRecognizer(context) else null

    init {
        if (Build.VERSION.SDK_INT >= 29) audioManager?.setAllowedCapturePolicy(AudioAttributes.ALLOW_CAPTURE_BY_ALL)
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
                    speakNow(prompt, 0)
                }
            } else {
                pendingPrompt = null
                mainHandler.post { onUnavailable("음성 합성 엔진을 준비하지 못했습니다.") }
            }
        }
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) { if (utteranceId != null && utteranceId == currentPromptId) promptStarted = true }
            override fun onDone(utteranceId: String?) {
                // Bluetooth media playback can finish at the TTS engine slightly before
                // the headset has rendered its final audio frames. Leave a short tail
                // before switching the same device into communication/microphone mode.
                mainHandler.postDelayed({ if (utteranceId != null && utteranceId == currentPromptId) onPromptFinished() }, 300L)
            }
            @Deprecated("Deprecated in Java") override fun onError(utteranceId: String?) {
                mainHandler.post { if (utteranceId != null && utteranceId == currentPromptId) onUnavailable("예문 음성을 재생하지 못했습니다. 휴대전화의 미디어 음량과 TTS 설정을 확인해 주세요.") }
            }
        })
    }

    private fun listener(generation: Long) = object : RecognitionListener {
            override fun onResults(results: Bundle) {
                if (generation != recognitionGeneration || !acceptingRecognitionResults) return
                acceptingRecognitionResults = false
                recognitionStarting = false
                closeOutdoorInput()
                val matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                onResult(matches.orEmpty())
            }
            override fun onError(error: Int) {
                if (generation != recognitionGeneration || (!acceptingRecognitionResults && !recognitionStarting)) return
                acceptingRecognitionResults = false
                recognitionStarting = false
                if (outdoorSource != null) injectionFailed = true
                closeOutdoorInput()
                if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) onUnavailable("마이크 권한이 필요합니다.")
                else onResult(emptyList())
            }
            override fun onReadyForSpeech(params: Bundle?) {
                if (generation != recognitionGeneration || !recognitionStarting) return
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
                if (generation != recognitionGeneration || !acceptingRecognitionResults) return
                onPartialResult(partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty())
            }
            override fun onSegmentResults(segmentResults: Bundle) {
                if (generation != recognitionGeneration || !acceptingRecognitionResults) return
                onPartialResult(segmentResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty())
            }
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    fun speak(text: String, korean: Boolean, accent: VoiceAccent) {
        currentPromptId = null
        cancelPendingSpeak()
        cancelPendingListen()
        recognitionGeneration++
        recognitionStarting = false
        acceptingRecognitionResults = false
        recognizer?.cancel()
        closeOutdoorInput()
        restoreRecognitionAudio()
        val wasInCallMode = bluetoothRouteActive || communicationRouteActive
        restoreAudioRoute()
        val prompt = PendingPrompt(text, korean, accent)
        if (!ttsReady) {
            pendingPrompt = prompt
            return
        }
        if (wasInCallMode) waitForMediaRoute(prompt) else speakNow(prompt, 0)
    }

    /**
     * Right after the microphone phase the headset is still in call (SCO) mode. Speaking
     * before it has switched back to media (A2DP) either loses the first words or the
     * whole sentence, so wait until the media route is really back (bounded).
     */
    private fun waitForMediaRoute(prompt: PendingPrompt) {
        val startedAt = SystemClock.elapsedRealtime()
        lateinit var check: Runnable
        check = Runnable {
            val waited = SystemClock.elapsedRealtime() - startedAt
            val ready = if (Build.VERSION.SDK_INT >= 33) waited >= 150L && mediaRouteReady() else waited >= 500L
            if (ready || waited >= 1500L) {
                pendingSpeak = null
                speakNow(prompt, 0)
            } else mainHandler.postDelayed(check, 50L)
        }
        pendingSpeak = check
        mainHandler.postDelayed(check, 50L)
    }

    private fun mediaRouteReady(): Boolean = runCatching {
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val device = audioManager?.getAudioDevicesForAttributes(attributes)?.firstOrNull() ?: return@runCatching true
        device.type != AudioDeviceInfo.TYPE_BLUETOOTH_SCO && device.type != AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
    }.getOrDefault(true)

    private fun cancelPendingSpeak() {
        pendingSpeak?.let(mainHandler::removeCallbacks)
        pendingSpeak = null
        promptWatchdog?.let(mainHandler::removeCallbacks)
        promptWatchdog = null
    }

    private fun speakNow(prompt: PendingPrompt, attempt: Int) {
        val locale = if (prompt.korean) Locale.KOREA else when (prompt.accent) {
            VoiceAccent.US -> Locale.US
            VoiceAccent.UK -> Locale.UK
        }
        val key = locale.toLanguageTag()
        if (appliedVoiceKey != key) {
            val languageResult = tts.setLanguage(locale)
            if (languageResult == TextToSpeech.LANG_MISSING_DATA || languageResult == TextToSpeech.LANG_NOT_SUPPORTED) {
                onUnavailable(if (prompt.korean) "한국어 TTS 음성이 설치되어 있지 않습니다." else "영어 TTS 음성이 설치되어 있지 않습니다.")
                return
            }
            bestVoice(locale)?.let { runCatching { tts.voice = it } }
            appliedVoiceKey = key
        }
        tts.setSpeechRate(if (prompt.korean) .90f else .84f)
        tts.setPitch(1f)
        val params = Bundle().apply { putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1f) }
        val id = "prompt-${++promptVersion}"
        currentPromptId = id
        promptStarted = false
        // A short silent lead-in wakes a sleeping Bluetooth link so the first word is not clipped.
        val lead = activeMediaOutputAddress() != null
        var result = TextToSpeech.SUCCESS
        if (lead) {
            tts.playSilentUtterance(250L, TextToSpeech.QUEUE_FLUSH, "$id-lead")
            result = tts.speak(naturalizeForSpeech(prompt.text, prompt.korean), TextToSpeech.QUEUE_ADD, params, id)
        } else {
            result = tts.speak(naturalizeForSpeech(prompt.text, prompt.korean), TextToSpeech.QUEUE_FLUSH, params, id)
        }
        if (result == TextToSpeech.ERROR) {
            onUnavailable("예문 음성을 재생하지 못했습니다. 미디어 음량을 확인해 주세요.")
            return
        }
        // If the engine never starts the utterance, retry once instead of leaving the lesson silent.
        val watchdog = Runnable {
            if (currentPromptId != id || promptStarted) return@Runnable
            tts.stop()
            if (attempt < 1) speakNow(prompt, attempt + 1)
            else {
                onNotice("예문 음성이 재생되지 않아 다음 단계로 넘어갑니다.")
                onPromptFinished()
            }
        }
        promptWatchdog = watchdog
        mainHandler.postDelayed(watchdog, 3000L)
    }

    /** Best installed voice for the locale: offline first, then quality, then low latency. */
    private fun bestVoice(locale: Locale): Voice? = tts.voices.orEmpty()
        .filter { it.locale.language == locale.language && it.locale.country == locale.country }
        .filter { TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in it.features }
        .maxWithOrNull(compareBy<Voice> { if (it.isNetworkConnectionRequired) 0 else 1 }
            .thenBy { it.quality }.thenBy { -it.latency })

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
        if (!SpeechRecognizer.isRecognitionAvailable(appContext)) { onUnavailable("이 기기에서 음성 인식을 사용할 수 없습니다."); return }
        cancelPendingListen()
        acceptingRecognitionResults = false
        recognitionStarting = false
        recognitionGeneration++
        recognizer?.destroy()
        closeOutdoorInput()
        val speechRecognizer = SpeechRecognizer.createSpeechRecognizer(appContext)
        recognizer = speechRecognizer
        speechRecognizer.setRecognitionListener(listener(recognitionGeneration))
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, recognitionLanguage)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, recognitionLanguage)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 8)
            val expectedWords = expectedText.trim().split(Regex("\\s+")).filter(String::isNotBlank)
            val minimumLength = (expectedWords.size * 300L).coerceIn(1_500L, 7_000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, minimumLength)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 3_000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1_800L)
            if (Build.VERSION.SDK_INT >= 33 && biasTowardExpected) {
                val normalizedWords = expectedWords.map { it.trim('.', ',', '!', '?', ';', ':', '"', '\'', '’') }
                    .filter { it.length >= 3 }
                val phrases = buildList {
                    add(expectedText)
                    addAll(normalizedWords)
                    addAll(normalizedWords.zipWithNext { first, second -> "$first $second" })
                }.distinct().take(24)
                putStringArrayListExtra(RecognizerIntent.EXTRA_BIASING_STRINGS, ArrayList(phrases))
                putExtra(RecognizerIntent.EXTRA_ENABLE_BIASING_DEVICE_CONTEXT, true)
            }
            if (Build.VERSION.SDK_INT >= 33) {
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
            val recordingWanted = isRecording()
            if (!recordingWanted) recordingNoticeShown = false
            if ((outdoorAudio || recordingWanted) && !injectionFailed && Build.VERSION.SDK_INT >= 33) {
                val source = OutdoorAudioSource(recordingTee)
                runCatching {
                    val routed = if (Build.VERSION.SDK_INT >= 31) audioManager?.communicationDevice else null
                    val inputs = audioManager?.getDevices(AudioManager.GET_DEVICES_INPUTS).orEmpty()
                    val device = if (bluetoothRouteActive) inputs.firstOrNull { it.address == routed?.address && it.type in setOf(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET) }
                        else inputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
                    if (bluetoothRouteActive) check(device != null)
                    source.attach(intent, device)
                    outdoorSource = source
                    onInputDeviceChanged("${device?.productName ?: "휴대전화"} 마이크 · " + when {
                        !outdoorAudio -> "녹음 입력"
                        source.noiseSuppressed -> "야외 잡음 보정"
                        else -> "야외 입력"
                    })
                }.onFailure {
                    source.close()
                    injectionFailed = true
                    intent.removeExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE)
                }
            }
            if (recordingWanted && outdoorSource == null && !recordingNoticeShown) {
                recordingNoticeShown = true
                onNotice("이 기기의 음성 인식에서는 녹음을 사용할 수 없어 녹음 없이 진행합니다.")
            }
            runCatching { speechRecognizer.startListening(intent) }.onSuccess {
                pendingReadyTimeout = Runnable {
                    pendingReadyTimeout = null
                    if (recognitionStarting) {
                        recognitionStarting = false
                        if (outdoorSource != null) injectionFailed = true
                        closeOutdoorInput()
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
        if (bluetoothRouteActive && Build.VERSION.SDK_INT >= 31) {
            val current = audioManager?.communicationDevice
            val wanted = audioManager?.availableCommunicationDevices?.let { preferredBluetooth(it, current) }
            if (!phoneMic && current != null && wanted != null && current.id == wanted.id) return 0L
            restoreAudioRoute()
        }
        if (phoneMic) {
            restoreAudioRoute()
            selectPhoneInput()
            onInputDeviceChanged("휴대전화 마이크")
            return 250L
        }
        if (bluetoothRouteActive) return 0L
        onInputDeviceChanged("휴대전화 마이크")
        if (audioManager == null) return 0L
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return 0L
                val bluetooth = preferredBluetooth(audioManager.availableCommunicationDevices, null)
                    ?: run { selectPhoneInput(); return 250L }
                audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                if (audioManager.setCommunicationDevice(bluetooth)) {
                    bluetoothRouteActive = true
                    communicationRouteActive = true
                    onInputDeviceChanged("${bluetooth.productName} 마이크")
                    800L
                } else {
                    audioManager.mode = AudioManager.MODE_NORMAL
                    0L
                }
            } else {
                val bluetooth = preferredBluetooth(audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS).toList(), null)
                    ?: return 0L
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

    private fun selectPhoneInput() {
        if (audioManager == null || Build.VERSION.SDK_INT < 31) return
        runCatching {
            val phone = audioManager.availableCommunicationDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE }
                ?: audioManager.availableCommunicationDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
            if (phone != null) {
                audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                communicationRouteActive = audioManager.setCommunicationDevice(phone)
                if (!communicationRouteActive) audioManager.mode = AudioManager.MODE_NORMAL
            }
        }
    }

    /**
     * Which Bluetooth headset to record from. Order: the one picked in settings, the one
     * the media sound is currently playing through (so a second paired device such as a
     * car kit or speaker is not grabbed), the one already in use, then BLE over SCO.
     */
    private fun preferredBluetooth(devices: List<AudioDeviceInfo>, current: AudioDeviceInfo?): AudioDeviceInfo? {
        val headsets = devices.filter { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it.type == AudioDeviceInfo.TYPE_BLE_HEADSET }
        if (bluetoothInputAddress.isNotBlank()) headsets.firstOrNull { it.address == bluetoothInputAddress }?.let { return it }
        val usable = headsets.filter { !isWatchDevice(it) }
        val playing = activeMediaOutputAddress()
        if (!playing.isNullOrBlank()) usable.firstOrNull { it.address == playing }?.let { return it }
        if (current != null) usable.firstOrNull { it.id == current.id }?.let { return it }
        return usable.maxByOrNull { if (it.type == AudioDeviceInfo.TYPE_BLE_HEADSET) 2 else 1 }
    }

    private fun activeMediaOutputAddress(): String? {
        if (Build.VERSION.SDK_INT < 33) return null
        return runCatching {
            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            audioManager?.getAudioDevicesForAttributes(attributes)
                ?.firstOrNull {
                    it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                        it.type == AudioDeviceInfo.TYPE_BLE_HEADSET
                }?.address
        }.getOrNull()
    }

    fun refreshBluetoothDevices() {
        val audio = audioManager ?: return
        val permitted = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        val choices = if (!permitted) emptyList() else runCatching {
            val all = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) audio.availableCommunicationDevices
                else audio.getDevices(AudioManager.GET_DEVICES_INPUTS).toList()
            all.filter { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it.type == AudioDeviceInfo.TYPE_BLE_HEADSET }
                .filter { it.address.isNotBlank() }
                .distinctBy { it.address }
                .map { BluetoothChoice(it.address, it.productName.toString(), isWatchDevice(it)) }
        }.getOrDefault(emptyList())
        mainHandler.post { onBluetoothDevicesChanged(choices) }
    }

    private fun isWatchDevice(device: AudioDeviceInfo): Boolean {
        val pairedClass = runCatching {
            appContext.getSystemService(BluetoothManager::class.java)?.adapter?.bondedDevices
                ?.firstOrNull { it.address == device.address }?.bluetoothClass?.deviceClass
        }.getOrNull()
        return BluetoothInputPolicy.isWatch(device.productName.toString(), pairedClass)
    }

    private fun closeOutdoorInput() { outdoorSource?.close(); outdoorSource = null }

    private fun restoreAudioRoute() {
        if (audioManager == null || (!bluetoothRouteActive && !communicationRouteActive)) return
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
        communicationRouteActive = false
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
            if (mirrorAudio && stream in setOf(AudioManager.STREAM_MUSIC, AudioManager.STREAM_VOICE_CALL)) return@forEach
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
        stop()
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

    fun stop() { recognitionGeneration++; closeOutdoorInput(); currentPromptId = null; pendingPrompt = null; cancelPendingSpeak(); cancelPendingListen(); acceptingRecognitionResults = false; recognitionStarting = false; recognizer?.cancel(); restoreRecognitionAudio(); restoreAudioRoute(); tts.stop() }
    fun destroy() { recognitionGeneration++; closeOutdoorInput(); currentPromptId = null; pendingPrompt = null; cancelPendingSpeak(); cancelPendingListen(); acceptingRecognitionResults = false; recognitionStarting = false; recognizer?.destroy(); restoreRecognitionAudio(); restoreAudioRoute(); tts.shutdown() }
}
