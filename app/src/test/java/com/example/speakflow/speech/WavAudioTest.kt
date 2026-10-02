package com.example.speakflow.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class WavAudioTest {
    private fun pcm(seconds: Double, amplitude: Int, frequency: Double = 440.0): ByteArray {
        val samples = (seconds * WavAudio.SAMPLE_RATE).toInt()
        val out = ByteArray(samples * 2)
        for (i in 0 until samples) {
            val value = if (amplitude == 0) 0 else (amplitude * sin(2 * PI * frequency * i / WavAudio.SAMPLE_RATE)).toInt()
            out[i * 2] = (value and 0xFF).toByte()
            out[i * 2 + 1] = (value shr 8).toByte()
        }
        return out
    }

    @Test fun speechIsKeptWithPaddingAndSilenceIsCut() {
        val trimmed = WavAudio.trimSilence(pcm(1.0, 0) + pcm(1.0, 8000) + pcm(2.0, 0))!!
        // 1 s of speech + 0.3 s before + 0.3 s after, in 16-bit samples.
        assertEquals((1.6 * WavAudio.SAMPLE_RATE).toInt() * 2, trimmed.size)
    }

    @Test fun silenceOrFaintNoiseIsNotSaved() {
        assertEquals(null, WavAudio.trimSilence(pcm(3.0, 0)))
        assertEquals(null, WavAudio.trimSilence(pcm(3.0, 100)))
    }

    @Test fun wavHeaderDescribesTheData() {
        val data = pcm(0.5, 5000)
        val wav = WavAudio.toWav(data)
        assertEquals(44 + data.size, wav.size)
        assertEquals("RIFF", String(wav, 0, 4, Charsets.US_ASCII))
        assertEquals("WAVE", String(wav, 8, 4, Charsets.US_ASCII))
        assertEquals("data", String(wav, 36, 4, Charsets.US_ASCII))
        val sampleRate = (wav[24].toInt() and 0xFF) or ((wav[25].toInt() and 0xFF) shl 8)
        assertEquals(WavAudio.SAMPLE_RATE, sampleRate)
    }

    @Test fun fileNameSortsByTimeAndDescribesTheAttempt() {
        assertEquals("20261002-111530_007_ok_he-turned-up-late-without-letting-anyone.wav",
            WavAudio.fileName("20261002-111530", 7, true, "He turned up late without letting anyone know."))
        assertEquals("20261002-111530_012_retry.wav", WavAudio.fileName("20261002-111530", 12, false, "???"))
        assertTrue(WavAudio.fileName("t", 1, true, "a/b\\c: d").matches(Regex("t_001_ok_[a-z0-9-]+\\.wav")))
    }
}
