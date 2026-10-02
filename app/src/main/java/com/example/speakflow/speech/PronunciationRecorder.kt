package com.example.speakflow.speech

import android.content.Context
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/**
 * One recording from "녹음 시작" to "녹음 종료". Only what the user says is kept: every
 * listening attempt is trimmed of silence and appended, with a short gap, to a temporary
 * PCM file. On stop the file is wrapped as .wav in Download/SpeakFlow.
 */
class PronunciationRecorder(private val context: Context) {
    private class Session(val file: File, val bytes: Long, val stamp: String)

    @Volatile var active = false
        private set
    private val lock = Any()
    private val attempt = ByteArrayOutputStream()
    private val saver = PronunciationSaver(context)
    private var file: File? = null
    private var stream: FileOutputStream? = null
    private var written = 0L
    private var stamp = ""

    fun start() {
        synchronized(lock) {
            if (active) return
            stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            val target = File(context.cacheDir, "$PREFIX$stamp$SUFFIX")
            stream = FileOutputStream(target)
            file = target
            written = 0L
            attempt.reset()
            active = true
        }
    }

    /** Called from the audio thread with every chunk the recognizer is also given. */
    fun appendAudio(buffer: ByteArray, count: Int) {
        if (!active) return
        synchronized(lock) {
            if (active && attempt.size() < MAX_ATTEMPT_BYTES) attempt.write(buffer, 0, count)
        }
    }

    fun beginAttempt() { synchronized(lock) { attempt.reset() } }

    fun endAttempt() { synchronized(lock) { if (active) flushAttempt() } }

    private fun flushAttempt() {
        val pcm = attempt.toByteArray()
        attempt.reset()
        val out = stream ?: return
        val speech = WavAudio.trimSilence(pcm) ?: return
        runCatching {
            if (written > 0) { out.write(ByteArray(WavAudio.GAP_BYTES)); written += WavAudio.GAP_BYTES }
            out.write(speech)
            written += speech.size
        }
    }

    fun stop(onDone: (String) -> Unit) {
        val session = synchronized(lock) {
            if (!active) null else {
                flushAttempt()
                active = false
                runCatching { stream?.close() }
                stream = null
                Session(file!!, written, stamp).also { file = null }
            }
        } ?: return
        if (session.bytes == 0L) {
            session.file.delete()
            onDone("녹음된 발음이 없어 파일을 만들지 않았습니다.")
            return
        }
        save(session, recovered = false, onDone)
    }

    /** Saves recordings left behind when the app was killed during a recording. */
    fun recoverInterrupted(onDone: (String) -> Unit) {
        val leftovers = context.cacheDir.listFiles { f -> f.name.startsWith(PREFIX) && f.name.endsWith(SUFFIX) }
            .orEmpty().filter { it != synchronized(lock) { file } }
        for (leftover in leftovers) {
            if (leftover.length() == 0L) { leftover.delete(); continue }
            val stampOfFile = leftover.name.removePrefix(PREFIX).removeSuffix(SUFFIX)
            save(Session(leftover, leftover.length(), stampOfFile), recovered = true, onDone)
        }
    }

    private fun save(session: Session, recovered: Boolean, onDone: (String) -> Unit) {
        thread(name = "SpeakFlow-save-recording") {
            val name = WavAudio.sessionFileName(session.stamp, recovered)
            val message = runCatching {
                saver.save(session.file, name)
                session.file.delete()
                (if (recovered) "중단된 녹음을 저장했습니다" else "녹음을 저장했습니다") +
                    " · ${WavAudio.duration(session.bytes)} · 내려받기/${PronunciationSaver.FOLDER}/$name"
            }.getOrElse { "녹음을 저장하지 못했습니다: ${it.message}" }
            onDone(message)
        }
    }

    private companion object {
        const val PREFIX = "recording-"
        const val SUFFIX = ".pcm"
        const val MAX_ATTEMPT_BYTES = WavAudio.SAMPLE_RATE * 2 * 40
    }
}
