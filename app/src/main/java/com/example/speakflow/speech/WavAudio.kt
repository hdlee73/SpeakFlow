package com.example.speakflow.speech

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Pure helpers for turning captured 16-bit mono PCM into a tidy .wav file. */
object WavAudio {
    const val SAMPLE_RATE = 16_000

    fun header(dataSize: Int, sampleRate: Int = SAMPLE_RATE): ByteArray {
        val ascii = Charsets.US_ASCII
        return ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            .put("RIFF".toByteArray(ascii)).putInt(36 + dataSize).put("WAVE".toByteArray(ascii))
            .put("fmt ".toByteArray(ascii)).putInt(16).putShort(1.toShort()).putShort(1.toShort())
            .putInt(sampleRate).putInt(sampleRate * 2).putShort(2.toShort()).putShort(16.toShort())
            .put("data".toByteArray(ascii)).putInt(dataSize)
            .array()
    }

    fun toWav(pcm: ByteArray, sampleRate: Int = SAMPLE_RATE): ByteArray = header(pcm.size, sampleRate) + pcm

    /** Silence inserted between two attempts so they are easy to tell apart when played back. */
    const val GAP_BYTES = SAMPLE_RATE * 2 * 6 / 10

    /**
     * Cuts the waiting time before and after speech (a timed-out attempt is 20 seconds of
     * mostly silence) and keeps 0.3 s of padding. Returns null when nothing was said.
     */
    fun trimSilence(pcm: ByteArray, sampleRate: Int = SAMPLE_RATE): ByteArray? {
        val frameSize = sampleRate / 50
        val frames = pcm.size / 2 / frameSize
        if (frames < 3) return null
        val level = DoubleArray(frames) { frame ->
            var sum = 0.0
            for (i in 0 until frameSize) {
                val index = (frame * frameSize + i) * 2
                val value = (pcm[index].toInt() and 0xFF) or (pcm[index + 1].toInt() shl 8)
                sum += value.toDouble() * value
            }
            sqrt(sum / frameSize)
        }
        val floor = level.sorted()[frames / 10]
        val threshold = max(250.0, floor * 3.5)
        val voiced = level.indices.filter { level[it] > threshold }
        if (voiced.size < 3) return null
        val padding = 15
        val from = max(0, voiced.first() - padding)
        val to = min(frames, voiced.last() + 1 + padding)
        return pcm.copyOfRange(from * frameSize * 2, to * frameSize * 2)
    }

    /** e.g. SpeakFlow_20261002-111530.wav (the time the recording was started) */
    fun sessionFileName(timestamp: String, recovered: Boolean = false): String =
        "SpeakFlow_$timestamp${if (recovered) "_recovered" else ""}.wav"

    fun duration(pcmBytes: Long): String {
        val seconds = pcmBytes / (SAMPLE_RATE * 2)
        return String.format(Locale.US, "%d:%02d", seconds / 60, seconds % 60)
    }
}
