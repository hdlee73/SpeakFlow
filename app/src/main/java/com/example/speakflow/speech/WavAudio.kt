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

    fun toWav(pcm: ByteArray, sampleRate: Int = SAMPLE_RATE): ByteArray {
        val ascii = Charsets.US_ASCII
        return ByteBuffer.allocate(44 + pcm.size).order(ByteOrder.LITTLE_ENDIAN)
            .put("RIFF".toByteArray(ascii)).putInt(36 + pcm.size).put("WAVE".toByteArray(ascii))
            .put("fmt ".toByteArray(ascii)).putInt(16).putShort(1.toShort()).putShort(1.toShort())
            .putInt(sampleRate).putInt(sampleRate * 2).putShort(2.toShort()).putShort(16.toShort())
            .put("data".toByteArray(ascii)).putInt(pcm.size).put(pcm)
            .array()
    }

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

    /** e.g. 20261002-111530_007_ok_he-turned-up-late-without-letting-anyone.wav */
    fun fileName(timestamp: String, number: Int, success: Boolean, english: String): String {
        val slug = english.lowercase(Locale.US).replace(Regex("[^a-z0-9]+"), "-").trim('-').take(40).trim('-')
        return String.format(Locale.US, "%s_%03d_%s%s.wav", timestamp, number, if (success) "ok" else "retry",
            if (slug.isEmpty()) "" else "_$slug")
    }
}
