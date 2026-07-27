package io.talkcan.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class SttAudioTest {
    @Test
    fun normalizePcm16MapsFullScaleToMinusOneToOne() {
        val input = shortArrayOf(0, 1, -1, 32_767, -32_768)
        val out = SttAudio.normalizePcm16(input)
        assertEquals(0.0f, out[0], 0.0f)
        assertTrue(out[1] > 0.0f && out[1] < 1.0f / 32_768.0f + 1e-9f)
        assertTrue(out[2] < 0.0f && out[2] > -1.0f / 32_768.0f - 1e-9f)
        assertEquals(32_767.0f / 32_768.0f, out[3], 1e-6f)
        assertEquals(-1.0f, out[4], 0.0f)
    }

    @Test
    fun normalizePcm16PreservesLength() {
        val input = ShortArray(123) { it.toShort() }
        val out = SttAudio.normalizePcm16(input)
        assertEquals(input.size, out.size)
    }

    @Test
    fun resampleIdentityWhenRatesMatch() {
        val input = FloatArray(10) { it.toFloat() }
        val out = SttAudio.resample(input, 16_000, 16_000)
        assertTrue(out === input)
    }

    @Test
    fun resampleUpsamplesEightToSixteenKHzDoublesLength() {
        val input = FloatArray(100) { it.toFloat() }
        val out = SttAudio.resample(input, 8_000, 16_000)
        assertEquals(200, out.size)
        // Linear interpolation between adjacent samples: out[2*i] ~= input[i]
        for (i in 0 until 100) {
            assertEquals(input[i], out[2 * i], 0.01f)
        }
    }

    @Test
    fun resampleDownsamplesSixteenToEightKHzHalvesLength() {
        val input = FloatArray(200) { it.toFloat() }
        val out = SttAudio.resample(input, 16_000, 8_000)
        assertEquals(100, out.size)
    }

    @Test
    fun toParakeetInputCombinesNormalizationAndResampling() {
        val recording = RecordedPcm(ShortArray(50) { (it * 100).toShort() }, 8_000)
        val out = SttAudio.toParakeetInput(recording, 16_000)
        assertEquals(100, out.size)
        // Samples must be within normalized range.
        for (v in out) {
            assertTrue(abs(v) <= 1.0f)
        }
    }

    @Test
    fun toParakeetInputPreservesSamplesWhenAlreadySixteenKhz() {
        val recording = RecordedPcm(ShortArray(16) { 0 }, 16_000)
        val out = SttAudio.toParakeetInput(recording, 16_000)
        assertEquals(16, out.size)
        for (v in out) {
            assertEquals(0.0f, v, 0.0f)
        }
    }
}