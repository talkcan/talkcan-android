/*
 * Portions adapted from transcribe-rs 0.3.11, revision
 * 343768c100d566b135fbb7a2441e61fa8aa177f2.
 * Copyright (c) 2025 Ilya Stupakov.
 *
 * MIT License
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package io.talkcan.audio.onnx

/**
 * Parakeet waveform preparation for the 16 kHz mono input contract.
 *
 * Mirrors `transcribe-rs` `audio::prepend_silence` and the hard-coded
 * `ParakeetModel::DEFAULT_LEADING_SILENCE_MS`: inference prepends 250 ms of zeros
 * so the recurrent decoder has context before the first utterance.
 */
internal object ParakeetAudio {
    /** 16 kHz sampling → 16 samples per millisecond of mono audio. */
    const val SAMPLES_PER_MS: Int = 16

    /** Parakeet's pinned leading-silence duration. */
    const val DEFAULT_LEADING_SILENCE_MS: Int = 250

    /**
     * Return [samples] prefixed with [silenceMs] milliseconds of zeros (250 ms by
     * default). Allocates exactly one [FloatArray] of the final length and copies
     * the input once; the leading region is zero by construction.
     */
    fun prependLeadingSilence(
        samples: FloatArray,
        silenceMs: Int = DEFAULT_LEADING_SILENCE_MS,
    ): FloatArray {
        require(silenceMs >= 0) { "silence must be non-negative, was $silenceMs ms" }
        val silenceSamples = silenceMs * SAMPLES_PER_MS
        val padded = FloatArray(silenceSamples + samples.size)
        samples.copyInto(padded, destinationOffset = silenceSamples)
        return padded
    }
}
