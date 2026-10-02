package com.example.speakflow.speech

import android.annotation.SuppressLint
import android.content.Intent
import android.media.*
import android.media.audiofx.*
import android.os.ParcelFileDescriptor
import android.speech.RecognizerIntent
import java.io.Closeable
import kotlin.concurrent.thread

// Optional Android 13+ input for engines that accept EXTRA_AUDIO_SOURCE.
class OutdoorAudioSource(private val tee: ((ByteArray, Int) -> Unit)? = null) : Closeable {
    private var recorder: AudioRecord? = null
    private var pipe: Array<ParcelFileDescriptor>? = null
    private val effects = mutableListOf<AudioEffect>()
    @Volatile private var running = false
    var noiseSuppressed = false
        private set

    @SuppressLint("MissingPermission")
    fun attach(intent: Intent, device: AudioDeviceInfo?) {
        val min = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        check(min > 0)
        val record = AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
            .setAudioFormat(AudioFormat.Builder().setSampleRate(16000).setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build()).setBufferSizeInBytes(maxOf(min * 4, 8192)).build()
        recorder = record
        check(record.state == AudioRecord.STATE_INITIALIZED)
        if (device != null) check(record.setPreferredDevice(device))
        if (NoiseSuppressor.isAvailable()) runCatching {
            NoiseSuppressor.create(record.audioSessionId)?.let { it.enabled = true; noiseSuppressed = it.enabled; effects += it }
        }
        if (AcousticEchoCanceler.isAvailable()) runCatching {
            AcousticEchoCanceler.create(record.audioSessionId)?.let { it.enabled = true; effects += it }
        }
        val descriptors = ParcelFileDescriptor.createPipe()
        pipe = descriptors
        intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, descriptors[0])
        intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
        intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
        intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, 16000)
        record.startRecording()
        check(record.recordingState == AudioRecord.RECORDSTATE_RECORDING)
        running = true
        thread(name = "SpeakFlow-outdoor-input", isDaemon = true) {
            runCatching {
                ParcelFileDescriptor.AutoCloseOutputStream(descriptors[1]).use { output ->
                    val buffer = ByteArray(2048)
                    while (running) {
                        val count = record.read(buffer, 0, buffer.size)
                        if (count <= 0) break
                        // Copy first: if the engine stops reading, the pipe blocks on write.
                        tee?.invoke(buffer, count)
                        output.write(buffer, 0, count)
                    }
                }
            }
        }
    }
    override fun close() {
        running = false
        pipe?.forEach { runCatching { it.close() } }; pipe = null
        recorder?.let { runCatching { it.stop() }; runCatching { it.release() } }; recorder = null
        effects.forEach { runCatching { it.release() } }; effects.clear()
    }
}
