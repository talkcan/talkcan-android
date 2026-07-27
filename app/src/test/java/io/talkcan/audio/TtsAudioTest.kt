package io.talkcan.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TtsAudioTest {
    @Test
    fun f32ToPcm16MapsFullScale() {
        val input = floatArrayOf(0.0f, 1.0f, -1.0f, 0.5f, -0.5f)
        val out = TtsAudio.f32ToPcm16(input)
        assertEquals(0, out[0].toInt())
        assertEquals(32767, out[1].toInt())
        assertEquals(-32768, out[2].toInt())
        assertEquals(16384, out[3].toInt())
        assertEquals(-16384, out[4].toInt())
    }

    @Test
    fun f32ToPcm16ClampsOutOfRangeValues() {
        val input = floatArrayOf(2.0f, -2.0f, 100.0f)
        val out = TtsAudio.f32ToPcm16(input)
        assertEquals(32767, out[0].toInt())
        assertEquals(-32768, out[1].toInt())
        assertEquals(32767, out[2].toInt())
    }

    @Test
    fun f32ToPcm16PreservesLength() {
        val input = FloatArray(123) { 0.0f }
        val out = TtsAudio.f32ToPcm16(input)
        assertEquals(input.size, out.size)
    }

    @Test
    fun resampleIdentityWhenRatesMatch() {
        val input = FloatArray(10) { it.toFloat() }
        val out = TtsAudio.resample(input, 44_100, 44_100)
        assertTrue(out === input)
    }

    @Test
    fun resampleEmptyInputReturnsEmpty() {
        val input = FloatArray(0)
        val out = TtsAudio.resample(input, 44_100, 16_000)
        assertTrue(out.isEmpty())
    }

    @Test
    fun resampleDownsamplesFortyFourOneToSixteenKhz() {
        val input = FloatArray(44_100) { 1.0f }
        val out = TtsAudio.resample(input, 44_100, 16_000)
        assertEquals(16_000, out.size)
    }

    @Test
    fun resampleDownsamplesFortyFourOneToEightKhz() {
        val input = FloatArray(44_100) { 1.0f }
        val out = TtsAudio.resample(input, 44_100, 8_000)
        assertEquals(8_000, out.size)
    }

    @Test
    fun toScoPlaybackConvertsAndResamples() {
        val samples = FloatArray(44_100) { 0.5f }
        val playback = TtsAudio.toScoPlayback(samples, 16_000)
        assertEquals(16_000, playback.sampleRate)
        assertEquals(16_000, playback.samples.size)
        assertTrue(playback.samples.isNotEmpty())
        for (s in playback.samples) {
            assertTrue(s.toInt() in -16384..16384)
        }
    }

    @Test
    fun toScoPlaybackEmptyInputReturnsEmpty() {
        val playback = TtsAudio.toScoPlayback(FloatArray(0), 16_000)
        assertTrue(playback.isEmpty)
    }
}
