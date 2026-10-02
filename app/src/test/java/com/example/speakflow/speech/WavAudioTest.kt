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

    @Test fun headerMatchesWavLengthAndSessionNameIsTimestamped() {
        assertEquals(44, WavAudio.header(1000).size)
        assertEquals("SpeakFlow_20261002-111530.wav", WavAudio.sessionFileName("20261002-111530"))
        assertEquals("SpeakFlow_20261002-111530_recovered.wav", WavAudio.sessionFileName("20261002-111530", true))
        assertEquals("1:05", WavAudio.duration(65L * WavAudio.SAMPLE_RATE * 2))
        assertEquals(19_200, WavAudio.GAP_BYTES)
    }
}
