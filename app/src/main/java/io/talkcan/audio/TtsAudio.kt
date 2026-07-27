package io.talkcan.audio

import kotlin.math.roundToInt

/**
 * Audio conversion helpers for the TTS playback path.
 *
 * Supertonic outputs 44.1 kHz mono `f32` PCM. The headset SCO route runs at 8
 * or 16 kHz PCM16. Convert `f32` to PCM16, then resample 44.1 kHz → target SCO
 * rate using linear interpolation before writing to [PcmOutput].
 */
object TtsAudio {
    /**
     * Convert `f32` samples in `[-1.0, 1.0]` to signed PCM16. Values outside the
     * unit range are clamped to `[-1.0, 1.0]`, scaled by 32768, rounded to the
     * nearest integer, and clamped to the signed 16-bit range `[-32768, 32767]`
     * so that `+1.0` maps to `32767` and `-1.0` maps to `-32768`.
     */
    fun f32ToPcm16(samples: FloatArray): ShortArray {
        val out = ShortArray(samples.size)
        for (i in samples.indices) {
            val v = samples[i].coerceIn(-1.0f, 1.0f)
            out[i] = (v * 32768.0f).roundToInt().coerceIn(-32768, 32767).toShort()
        }
        return out
    }

    /**
     * Linear resampling of `f32` samples from [inputRate] to [outputRate].
     * Uses linear interpolation between adjacent samples; adequate for a
     * diagnostic test surface and cheap enough for on-device use.
     *
     * If [inputRate] == [outputRate] the input is returned unchanged. If the
     * input is empty, an empty array is returned.
     */
    fun resample(input: FloatArray, inputRate: Int, outputRate: Int): FloatArray {
        if (inputRate == outputRate || input.isEmpty()) return input
        val ratio = outputRate.toDouble() / inputRate.toDouble()
        val outLen = (input.size * ratio).toInt().coerceAtLeast(1)
        val out = FloatArray(outLen)
        for (i in 0 until outLen) {
            val srcPos = i / ratio
            val srcIdx = srcPos.toInt()
            val frac = srcPos - srcIdx
            val left = input[srcIdx.coerceAtMost(input.size - 1)]
            val right = input[(srcIdx + 1).coerceAtMost(input.size - 1)]
            out[i] = (left + (right - left) * frac).toFloat()
        }
        return out
    }

    /**
     * Convenience: convert Supertonic 44.1 kHz `f32` output to PCM16 at the SCO
     * output [targetRate] (typically 8_000 or 16_000), returning a
     * [RecordedPcm] suitable for [PcmOutput.play]. Empty input yields an empty
     * [RecordedPcm].
     */
    fun toScoPlayback(samples: FloatArray, targetRate: Int): RecordedPcm {
        if (samples.isEmpty()) return RecordedPcm(ShortArray(0), targetRate)
        val resampled = resample(samples, SUPERTONIC_SAMPLE_RATE, targetRate)
        return RecordedPcm(f32ToPcm16(resampled), targetRate)
    }

    /**
     * Normalize a mono `f32` [samples] array at [inputRate] Hz to non-empty
     * 16 kHz mono PCM16 for the navigation playback path: resample via
     * [resample] to [NAVIGATION_TTS_TARGET_RATE], then convert to PCM16 via
     * [f32ToPcm16]. Returns `null` when the input or resulting PCM is empty
     * (empty-PCM rejection), never an empty [RecordedPcm].
     */
    fun toNavigationPcm(samples: FloatArray, inputRate: Int): RecordedPcm? {
        if (samples.isEmpty()) return null
        val resampled = resample(samples, inputRate, NAVIGATION_TTS_TARGET_RATE)
        if (resampled.isEmpty()) return null
        val pcm16 = f32ToPcm16(resampled)
        if (pcm16.isEmpty()) return null
        return RecordedPcm(pcm16, NAVIGATION_TTS_TARGET_RATE)
    }

    const val SUPERTONIC_SAMPLE_RATE: Int = 44_100

    /** Target output sample rate for normalized navigation TTS PCM. */
    const val NAVIGATION_TTS_TARGET_RATE: Int = 16_000
}