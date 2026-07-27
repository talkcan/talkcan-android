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

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.util.Random
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Injectable uniform-double source for Gaussian latent sampling.
 *
 * Production uses [RandomSupertonicUniformSource] over an unseeded
 * [java.util.Random], matching the reference `new Random()` in
 * `sampleNoisyLatent`; tests inject deterministic sequences.
 */
internal fun interface SupertonicUniformSource {
    /** Next uniform draw in `[0.0, 1.0)`. */
    fun nextDouble(): Double
}

/** Production uniform source: an unseeded [java.util.Random], no explicit seed. */
internal class RandomSupertonicUniformSource(
    private val random: Random = Random(),
) : SupertonicUniformSource {
    override fun nextDouble(): Double = random.nextDouble()
}

/**
 * Allocation-conscious Gaussian fill for the Supertonic noisy latent.
 *
 * Each element receives exactly one pinned Box-Muller draw using the cosine
 * branch only, mirroring the reference `sampleNoisyLatent` element formula:
 *
 * ```
 * n = sqrt(-2 * ln(max(1e-10, u1))) * cos(2 * pi * u2)
 * ```
 *
 * with two fresh uniform draws (`u1`, `u2`) per element; the sine branch is
 * deliberately discarded so the port consumes uniforms in the reference order.
 * The `max(1e-10, u1)` floor keeps `ln` away from zero. The fill writes
 * straight into the caller's primitive [target] array: no boxed collections,
 * no intermediate per-sample storage.
 */
internal object SupertonicGaussianLatent {
    /** Reference uniform floor applied before the logarithm. */
    const val UNIFORM_FLOOR: Double = 1e-10

    /** Overwrites every element of [target] with one pinned Box-Muller sample. */
    fun fill(target: FloatArray, source: SupertonicUniformSource) {
        for (index in target.indices) {
            val u1 = maxOf(UNIFORM_FLOOR, source.nextDouble())
            val u2 = source.nextDouble()
            target[index] = (sqrt(-2.0 * ln(u1)) * cos(2.0 * PI * u2)).toFloat()
        }
    }
}

/**
 * Reusable one-chunk vector-estimation and vocoding stage over caller-owned
 * sessions, consuming the lexical [SupertonicEncodedText] produced by
 * [SupertonicTextStage.run].
 *
 * Mirrors the reference `_infer` suffix for a single chunk (batch 1):
 * 1. sample `noisy_latent` `[1, latentChannels, latentLength]` with
 *    [SupertonicGaussianLatent] and multiply by `latent_mask`
 *    `[1, 1, latentLength]`; for one chunk the planned latent length is
 *    exactly `ceil(waveformSamples / (baseChunkSize * chunkCompressFactor))`,
 *    so every mask frame is active — the explicit per-frame formula is kept
 *    and cross-checked against [SupertonicEncodedText.latentLength];
 * 2. run the vector estimator exactly [totalSteps] times with
 *    `current_step`/`total_step` FLOAT `[1]` tensors valued
 *    `step.toFloat()`/`totalSteps.toFloat()`, replacing the latent buffer in
 *    place from `denoised_latent` after each run;
 * 3. vocode the final latent through `latent` -> `wav_tts`, then project to
 *    primitive PCM sized to [SupertonicEncodedText.waveformSamples]:
 *    `min(produced, planned)` leading samples are copied (truncation when the
 *    vocoder overproduces, zero padding when it underproduces, matching the
 *    reference `System.arraycopy(..., min(...))` into a zeroed array) and
 *    every copied sample must be finite.
 *
 * Divergences from the pinned reference, all behavior-preserving:
 * - the reference recreated `latent_mask` and a second `text_mask` tensor
 *   inside every denoising step; the estimator graph treats inputs as
 *   immutable, so one `latent_mask` tensor is created and reused, and the
 *   shared [SupertonicEncodedText.textMask] is passed directly;
 * - denoised latents are copied back into one reused primitive
 *   [FloatArray] instead of reassigning boxed `float[][][]` results;
 * - `current_step` keeps its per-step tensor (its value changes), while
 *   `total_step` is created once for the whole loop.
 *
 * Ownership, on every path (success, stage failure, or session failure):
 * 1. each iteration's `noisy_latent` and `current_step` tensors close via
 *    `use` before the next iteration, and also when the estimator run or
 *    validation throws inside the iteration;
 * 2. each estimator/vocoder [OrtSession.Result] closes via `use`, releasing
 *    the `denoised_latent` / `wav_tts` tensor it owns;
 * 3. the [OrtConstructionScope] closes `latent_mask` and `total_step` in
 *    reverse construction order after the vocoder projection settles.
 * The [SupertonicEncodedText] tensors (`text_emb`, `style_ttl`, `text_mask`)
 * are never closed here — they belong to the text stage's result and
 * construction scopes — and the injected sessions plus the process-shared
 * environment belong to task 4.4's loader. The only value escaping lexical
 * scope is the returned primitive PCM array, the stage deliverable.
 */
internal class SupertonicDenoisingStage(
    private val config: SupertonicConfig,
    private val uniformSource: SupertonicUniformSource = RandomSupertonicUniformSource(),
) {

    /**
     * Denoises the encoded chunk for exactly [totalSteps] steps and vocodes
     * the result into finite mono PCM sized to
     * [SupertonicEncodedText.waveformSamples].
     *
     * @param totalSteps positive requested denoising step count; propagated
     *   verbatim as the `total_step` tensor value and the loop bound.
     * @throws IllegalArgumentException when [totalSteps] is not positive.
     * @throws TensorShapeException on any output name/type/rank/dimension
     *   violation, latent-size overflow, plan/mask disagreement, non-finite
     *   vocoder samples, or a planned waveform count beyond the 32-bit PCM
     *   index space.
     */
    fun run(
        environment: OrtEnvironment,
        vectorEstimator: OrtSession,
        vocoder: OrtSession,
        encoded: SupertonicEncodedText,
        totalSteps: Int,
    ): FloatArray {
        require(totalSteps > 0) { "totalSteps must be positive, was $totalSteps" }

        val latentLength = encoded.latentLength
        val latentChannels = encoded.latentChannels
        val latentElements = latentChannels.toLong() * latentLength.toLong()
        if (latentElements > Int.MAX_VALUE.toLong()) {
            throw TensorShapeException(
                "latent size [1, $latentChannels, $latentLength] holds $latentElements " +
                    "elements, which exceeds the 32-bit tensor index space",
            )
        }

        val latentMaskValues = buildLatentMask(encoded)
        val latentValues = FloatArray(latentElements.toInt())
        SupertonicGaussianLatent.fill(latentValues, uniformSource)
        applyMask(latentValues, latentMaskValues, latentChannels, latentLength)

        val latentShape = longArrayOf(1L, latentChannels.toLong(), latentLength.toLong())
        val maskShape = longArrayOf(1L, 1L, latentLength.toLong())
        val stepShape = longArrayOf(1L)
        val totalStepValues = floatArrayOf(totalSteps.toFloat())

        return OrtConstructionScope().use { scope ->
            val latentMask = scope.own(OnnxTensors.floats(environment, latentMaskValues, maskShape))
            val totalStep = scope.own(OnnxTensors.floats(environment, totalStepValues, stepShape))

            for (step in 0 until totalSteps) {
                OnnxTensors.floats(environment, latentValues, latentShape).use { noisyLatent ->
                    OnnxTensors.floats(environment, floatArrayOf(step.toFloat()), stepShape)
                        .use { currentStep ->
                            vectorEstimator
                                .run(
                                    mapOf(
                                        SupertonicOnnxContract.NOISY_LATENT to noisyLatent,
                                        SupertonicOnnxContract.TEXT_EMBEDDING to encoded.textEmbedding,
                                        SupertonicOnnxContract.STYLE_TTL to encoded.styleTtl,
                                        SupertonicOnnxContract.LATENT_MASK to latentMask,
                                        SupertonicOnnxContract.TEXT_MASK to encoded.textMask,
                                        SupertonicOnnxContract.CURRENT_STEP to currentStep,
                                        SupertonicOnnxContract.TOTAL_STEP to totalStep,
                                    ),
                                )
                                .use { estimated ->
                                    replaceLatentFromEstimate(
                                        estimated,
                                        latentValues,
                                        latentChannels,
                                        latentLength,
                                        latentElements,
                                    )
                                }
                        }
                }
            }

            OnnxTensors.floats(environment, latentValues, latentShape).use { latent ->
                vocoder
                    .run(mapOf(SupertonicOnnxContract.LATENT to latent))
                    .use { vocoded -> projectWaveform(vocoded, encoded.waveformSamples) }
            }
        }
    }

    /**
     * Builds the `[1, 1, latentLength]` mask values for one chunk: frame `t`
     * is active when `t < ceil(waveformSamples / (baseChunkSize *
     * chunkCompressFactor))`. The recomputed active-frame count must agree
     * with [SupertonicEncodedText.latentLength], which was derived from the
     * same formula in [SupertonicLatentPlan]; disagreement is a planning
     * fault, rejected with a bounded diagnostic.
     */
    private fun buildLatentMask(encoded: SupertonicEncodedText): FloatArray {
        val chunkSamples =
            Math.multiplyExact(config.baseChunkSize.toLong(), config.chunkCompressFactor.toLong())
        val activeFrames = Math.addExact(encoded.waveformSamples, chunkSamples - 1L) / chunkSamples
        if (activeFrames != encoded.latentLength.toLong()) {
            throw TensorShapeException(
                "encoded latent length ${encoded.latentLength} disagrees with the recomputed " +
                    "active frame count $activeFrames for ${encoded.waveformSamples} " +
                    "waveform samples at chunk size $chunkSamples",
            )
        }
        val mask = FloatArray(encoded.latentLength)
        for (frame in mask.indices) {
            mask[frame] = if (frame.toLong() < activeFrames) 1.0f else 0.0f
        }
        return mask
    }

    /** Multiplies each `[channel, frame]` latent sample by the frame mask. */
    private fun applyMask(
        latentValues: FloatArray,
        latentMaskValues: FloatArray,
        latentChannels: Int,
        latentLength: Int,
    ) {
        for (channel in 0 until latentChannels) {
            val row = channel * latentLength
            for (frame in 0 until latentLength) {
                latentValues[row + frame] *= latentMaskValues[frame]
            }
        }
    }

    /**
     * Validates the estimator result's `denoised_latent` output name, FLOAT
     * element type, rank-3 shape `[1, latentChannels, latentLength]`, and
     * element count, then copies it over [latentValues] in place. The result
     * (and the tensor it owns) closes in the caller's `use`.
     */
    private fun replaceLatentFromEstimate(
        estimated: OrtSession.Result,
        latentValues: FloatArray,
        latentChannels: Int,
        latentLength: Int,
        latentElements: Long,
    ) {
        val denoised = requiredTensor(estimated, SupertonicOnnxContract.DENOISED_LATENT)
        requireTensorType(denoised, OnnxJavaType.FLOAT, "vector estimator output")
        val shape = denoised.info.shape
        requireRank(shape, 3, "vector estimator output")
        requireDimension(shape, 0, 1L, "vector estimator output batch")
        requireDimension(shape, 1, latentChannels.toLong(), "vector estimator output channels")
        requireDimension(shape, 2, latentLength.toLong(), "vector estimator output time")

        val buffer = denoised.floatBuffer
        if (buffer.remaining().toLong() != latentElements) {
            throw TensorShapeException(
                "vector estimator output holds ${buffer.remaining()} elements, expected " +
                    "$latentElements for shape ${shape.contentToString()}",
            )
        }
        buffer.get(latentValues)
    }

    /**
     * Validates the vocoder's `wav_tts` output name, FLOAT element type,
     * rank-2 shape `[1, produced]`, and element count, then projects it to
     * primitive PCM sized to [waveformSamples]: the first
     * `min(produced, waveformSamples)` samples are copied, any remaining
     * planned tail stays zero (reference behavior), and every copied sample
     * must be finite.
     */
    private fun projectWaveform(vocoded: OrtSession.Result, waveformSamples: Long): FloatArray {
        val waveform = requiredTensor(vocoded, SupertonicOnnxContract.WAVEFORM)
        requireTensorType(waveform, OnnxJavaType.FLOAT, "vocoder output")
        val shape = waveform.info.shape
        requireRank(shape, 2, "vocoder output")
        requireDimension(shape, 0, 1L, "vocoder output batch")
        val producedLong = shape[1]
        if (producedLong < 0L || producedLong > Int.MAX_VALUE.toLong()) {
            throw TensorShapeException(
                "vocoder output axis 1 must be a non-negative Int dimension, was $producedLong",
            )
        }
        val produced = producedLong.toInt()

        if (waveformSamples > Int.MAX_VALUE.toLong()) {
            throw TensorShapeException(
                "planned waveform sample count $waveformSamples exceeds the 32-bit PCM index space",
            )
        }
        val target = waveformSamples.toInt()

        val buffer = waveform.floatBuffer
        if (buffer.remaining() != produced) {
            throw TensorShapeException(
                "vocoder output holds ${buffer.remaining()} elements, expected $produced " +
                    "for shape ${shape.contentToString()}",
            )
        }
        val pcm = FloatArray(target)
        val copyCount = minOf(produced, target)
        buffer.get(pcm, 0, copyCount)
        for (index in 0 until copyCount) {
            val sample = pcm[index]
            if (!sample.isFinite()) {
                throw TensorShapeException(
                    "vocoder output sample at index $index must be finite, was $sample",
                )
            }
        }
        return pcm
    }
}
