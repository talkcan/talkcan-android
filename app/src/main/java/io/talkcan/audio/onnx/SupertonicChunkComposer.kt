/*
 * Portions adapted from the official Supertonic Java reference, revision
 * dff55dc00064c398736080c78195f577527832ae.
 * Copyright (c) 2025 Supertone Inc.
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
 * Pre-sized multi-chunk PCM composer replacing the reference `TextToSpeech.call`
 * boxed `ArrayList<Float>` accumulation.
 *
 * Concatenates per-chunk primitive PCM in order, inserting
 * [INTER_CHUNK_SILENCE_SECONDS] of zeros between adjacent chunks (never before
 * the first), mirroring the reference `(int) (silenceDuration * sampleRate)`
 * truncation for the silence length. The total sample count is computed with
 * checked 64-bit arithmetic before any audio is copied, so absurd inputs fail
 * with a bounded [TensorShapeException] instead of wrapping; the composed
 * [FloatArray] is allocated exactly once and each chunk is copied into it
 * exactly once. Inter-chunk gaps are zero by construction.
 *
 * Task 4.4 runs the text and denoising stages once per ordered chunk, feeds
 * the resulting per-chunk arrays here, and projects the composed primitive
 * array through `SynthesisOutcome`; the composer holds no ONNX state.
 */
internal object SupertonicChunkComposer {
    /** Existing inter-chunk silence carried over from the production synthesis path. */
    const val INTER_CHUNK_SILENCE_SECONDS: Float = 0.3f

    /**
     * Composes [chunks] into one primitive PCM array at [sampleRate] with
     * [silenceSeconds] of zeros between adjacent chunks.
     *
     * An empty [chunks] list yields an empty array; a single chunk is
     * returned as a copy with no silence. The input chunk arrays are never
     * mutated or retained.
     *
     * @throws IllegalArgumentException when [sampleRate] is not positive or
     *   [silenceSeconds] is not finite and non-negative.
     * @throws TensorShapeException when the silence sample count or the
     *   composed total overflows the 32-bit PCM index space.
     */
    fun compose(
        sampleRate: Int,
        chunks: List<FloatArray>,
        silenceSeconds: Float = INTER_CHUNK_SILENCE_SECONDS,
    ): FloatArray {
        require(sampleRate > 0) { "sampleRate must be positive, was $sampleRate" }
        require(silenceSeconds.isFinite() && silenceSeconds >= 0f) {
            "silenceSeconds must be finite and non-negative, was $silenceSeconds"
        }
        if (chunks.isEmpty()) return FloatArray(0)

        val silenceProduct = silenceSeconds * sampleRate
        if (!silenceProduct.isFinite() || silenceProduct < 0f) {
            throw TensorShapeException(
                "silence $silenceSeconds s at sample rate $sampleRate implies a non-finite " +
                    "silence sample count",
            )
        }
        if (silenceProduct >= Int.MAX_VALUE.toFloat()) {
            throw TensorShapeException(
                "silence $silenceSeconds s at sample rate $sampleRate exceeds the checked " +
                    "silence sample bound",
            )
        }
        val silenceSamples = silenceProduct.toInt()

        var totalSamples = 0L
        for (chunk in chunks) {
            totalSamples = Math.addExact(totalSamples, chunk.size.toLong())
        }
        if (chunks.size > 1) {
            totalSamples =
                Math.addExact(totalSamples, silenceSamples.toLong() * (chunks.size - 1).toLong())
        }
        if (totalSamples > Int.MAX_VALUE.toLong()) {
            throw TensorShapeException(
                "composed PCM length $totalSamples across ${chunks.size} chunks exceeds the " +
                    "32-bit PCM index space",
            )
        }

        val composed = FloatArray(totalSamples.toInt())
        var offset = 0
        for (index in chunks.indices) {
            if (index > 0) {
                offset += silenceSamples
            }
            val chunk = chunks[index]
            chunk.copyInto(composed, destinationOffset = offset)
            offset += chunk.size
        }
        return composed
    }
}
