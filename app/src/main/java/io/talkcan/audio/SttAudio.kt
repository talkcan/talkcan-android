package io.talkcan.audio

/**
 * Audio normalization helpers for the STT path.
 *
 * The native Parakeet engine accepts normalized 16 kHz mono `f32` samples in
 * `[-1.0, 1.0]`. The recorder produces signed PCM16 at 8 kHz or 16 kHz, so
 * we normalize and resample before inference.
 */
object SttAudio {
    /**
     * Normalize signed PCM16 samples ( Shorts in `[-32768, 32767]` ) to `f32`
     * samples in `[-1.0, 1.0]`. Values are divided by 32768.0 so that the full
     * negative range maps cleanly; the positive max (32767) maps to slightly
     * less than 1.0, which is the standard convention.
     */
    fun normalizePcm16(samples: ShortArray): FloatArray {
        val out = FloatArray(samples.size)
        for (i in samples.indices) {
            out[i] = samples[i].toFloat() / 32768.0f
        }
        return out
    }

    /**
     * Linear resampling of `f32` samples from [inputRate] to [outputRate]
     * (typically 16_000). Uses linear interpolation between adjacent samples;
     * adequate for speech-band audio and cheap enough for on-device use.
     *
     * If [inputRate] == [outputRate] the input is returned unchanged.
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
     * Convenience: normalize then resample to 16 kHz for Parakeet.
     */
    fun toParakeetInput(recording: RecordedPcm, targetRate: Int = 16_000): FloatArray {
        val normalized = normalizePcm16(recording.samples)
        return resample(normalized, recording.sampleRate, targetRate)
    }
}