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

/**
 * Output-shape plan derived from one speed-adjusted chunk duration and the
 * runtime `tts.json` scalars, mirroring the reference `sampleNoisyLatent`
 * size math without sampling:
 * - waveform samples truncate `duration * sample_rate`, matching the
 *   reference `as usize` / `(int)` conversion;
 * - latent length is `ceil(waveformSamples / (base_chunk_size *
 *   chunk_compress_factor))`, the reference `(len + chunk - 1) / chunk`;
 * - expanded latent channels are `latent_dim * chunk_compress_factor`.
 *
 * Every product and sum uses checked arithmetic (`Math.multiplyExact`,
 * `Math.addExact`, explicit `Long`/`Int` bounds) so absurd durations fail
 * with a bounded diagnostic instead of wrapping.
 */
internal class SupertonicLatentPlan private constructor(
    /** Speed-adjusted chunk duration in seconds, finite and non-negative. */
    val durationSeconds: Float,
    /** Truncated PCM sample count at [SupertonicConfig.sampleRate]. */
    val waveformSamples: Long,
    /** Latent time frames: `ceil(waveformSamples / (baseChunkSize * chunkCompressFactor))`. */
    val latentLength: Int,
    /** Expanded latent channels: `latentDim * chunkCompressFactor`. */
    val latentChannels: Int,
) {
    companion object {
        fun forDuration(config: SupertonicConfig, durationSeconds: Float): SupertonicLatentPlan {
            if (!durationSeconds.isFinite() || durationSeconds < 0f) {
                throw TensorShapeException(
                    "speed-adjusted duration must be finite and non-negative, was $durationSeconds"
                )
            }
            val sampleCount = durationSeconds * config.sampleRate
            if (!sampleCount.isFinite() || sampleCount < 0f) {
                throw TensorShapeException(
                    "speed-adjusted duration $durationSeconds at sample rate " +
                        "${config.sampleRate} implies a non-finite waveform sample count"
                )
            }
            if (sampleCount >= Long.MAX_VALUE.toFloat()) {
                throw TensorShapeException(
                    "speed-adjusted duration $durationSeconds at sample rate " +
                        "${config.sampleRate} exceeds the checked waveform sample bound"
                )
            }
            val waveformSamples = sampleCount.toLong()

            val chunkSamples =
                Math.multiplyExact(config.baseChunkSize.toLong(), config.chunkCompressFactor.toLong())
            val latentLengthLong = Math.addExact(waveformSamples, chunkSamples - 1L) / chunkSamples
            if (latentLengthLong > Int.MAX_VALUE.toLong()) {
                throw TensorShapeException(
                    "waveform sample count $waveformSamples implies latent length " +
                        "$latentLengthLong, which exceeds the 32-bit tensor index space"
                )
            }
            val latentChannels = Math.multiplyExact(config.latentDim, config.chunkCompressFactor)
            return SupertonicLatentPlan(
                durationSeconds,
                waveformSamples,
                latentLengthLong.toInt(),
                latentChannels,
            )
        }
    }
}

/**
 * Lexical view of one completed duration-prediction and text-encoding run,
 * handed to the [SupertonicTextStage.run] callback and valid only for its
 * dynamic extent.
 *
 * [textEmbedding] is owned by the text-encoder [OrtSession.Result]; the
 * shared inputs [textIds], [textMask], and [styleTtl] are owned by the
 * stage's construction scope. The stage closes all of them when the callback
 * returns or throws, so the callback must consume shapes and buffers in
 * place: never close these tensors and never retain them past the callback.
 * The scalar shape fields are the exact values the denoising loop needs to
 * size `noisy_latent` `[1, latentChannels, latentLength]` and `latent_mask`
 * `[1, 1, latentLength]` without re-deriving them.
 */
internal class SupertonicEncodedText internal constructor(
    /** Text-encoder output `text_emb`, rank 3, owned by the encoder result. */
    val textEmbedding: OnnxTensor,
    /** Shared `style_ttl` input tensor, owned by the stage scope. */
    val styleTtl: OnnxTensor,
    /** Shared `text_mask` input tensor `[1, 1, textLength]`, owned by the stage scope. */
    val textMask: OnnxTensor,
    /** Shared `text_ids` input tensor `[1, textLength]`, owned by the stage scope. */
    val textIds: OnnxTensor,
    /** Speed-adjusted chunk duration in seconds, finite and non-negative. */
    val durationSeconds: Float,
    /** Truncated PCM sample count at the configured sample rate. */
    val waveformSamples: Long,
    /** Latent time frames for the denoising loop. */
    val latentLength: Int,
    /** Expanded latent channels for the denoising loop. */
    val latentChannels: Int,
    /** Code-point length `n` shared by `text_ids`, `text_mask`, and the embedding time axis. */
    val textLength: Int,
    /** Observed channel axis of [textEmbedding], validated positive. */
    val textEmbeddingChannels: Int,
)

/**
 * Reusable duration-prediction and text-encoding stage over caller-owned
 * sessions.
 *
 * Mirrors the reference `_infer` prefix: the shared `text_ids`/`text_mask`
 * tensors plus the voice-style tensors feed the duration predictor, whose
 * `duration` output is copied, validated finite and non-negative, and
 * divided by the request speed; the same shared tensors plus `style_ttl`
 * then feed the text encoder once. The encoder result and every input
 * tensor stay inside the lexical scopes that create them; the encoder
 * output is exposed to [block] only through [SupertonicEncodedText].
 *
 * Ownership, in close order on every path (success, stage failure, or
 * [block] failure):
 * 1. the text-encoder [OrtSession.Result] closes via `use` after [block]
 *    settles, releasing `text_emb` it owns;
 * 2. the [OrtConstructionScope] then closes `style_ttl`, `style_dp`,
 *    `text_mask`, and `text_ids` in reverse construction order.
 * The injected sessions and the process-shared environment are never closed
 * here; their lifetimes belong to the caller (task 4.4's loader).
 */
internal class SupertonicTextStage(private val config: SupertonicConfig) {

    /**
     * Runs the duration predictor, derives the [SupertonicLatentPlan], runs
     * the text encoder, and hands [block] the encoded view. Returns whatever
     * [block] returns; [block] failures propagate after every temporary
     * ONNX value has closed.
     *
     * @param speed finite, strictly positive playback-rate divisor.
     * @throws TensorShapeException on any output name/type/rank/dimension
     *   violation, non-finite or negative duration, or checked-size overflow.
     */
    fun <R> run(
        environment: OrtEnvironment,
        durationPredictor: OrtSession,
        textEncoder: OrtSession,
        text: PreparedSupertonicText,
        style: SupertonicVoiceStyle,
        speed: Float,
        block: (SupertonicEncodedText) -> R,
    ): R {
        require(speed.isFinite() && speed > 0f) { "speed must be finite and positive, was $speed" }
        val textLength = text.length.toLong()

        return OrtConstructionScope().use { scope ->
            val textIds = scope.own(OnnxTensors.longs(environment, text.ids, longArrayOf(1L, textLength)))
            val textMask =
                scope.own(OnnxTensors.floats(environment, text.mask, longArrayOf(1L, 1L, textLength)))
            val styleDp =
                scope.own(OnnxTensors.floats(environment, style.dpData, styleShape(style.dpDims)))

            val durationSeconds = predictDuration(durationPredictor, textIds, styleDp, textMask, speed)
            val plan = SupertonicLatentPlan.forDuration(config, durationSeconds)

            val styleTtl =
                scope.own(OnnxTensors.floats(environment, style.ttlData, styleShape(style.ttlDims)))
            textEncoder
                .run(
                    mapOf(
                        SupertonicOnnxContract.TEXT_IDS to textIds,
                        SupertonicOnnxContract.STYLE_TTL to styleTtl,
                        SupertonicOnnxContract.TEXT_MASK to textMask,
                    ),
                )
                .use { encoded ->
                    val textEmbedding = requiredTensor(encoded, SupertonicOnnxContract.TEXT_EMBEDDING)
                    requireTensorType(textEmbedding, OnnxJavaType.FLOAT, "text encoder embedding")
                    val shape = textEmbedding.info.shape
                    requireRank(shape, 3, "text encoder embedding")
                    requireDimension(shape, 0, 1L, "text encoder embedding batch")
                    val embeddingChannels =
                        requirePositiveDimension(shape, 1, "text encoder embedding channels")
                    requireDimension(shape, 2, textLength, "text encoder embedding time")

                    block(
                        SupertonicEncodedText(
                            textEmbedding = textEmbedding,
                            styleTtl = styleTtl,
                            textMask = textMask,
                            textIds = textIds,
                            durationSeconds = plan.durationSeconds,
                            waveformSamples = plan.waveformSamples,
                            latentLength = plan.latentLength,
                            latentChannels = plan.latentChannels,
                            textLength = text.length,
                            textEmbeddingChannels = embeddingChannels,
                        ),
                    )
                }
        }
    }

    /**
     * Runs the duration predictor over shared text/style inputs, reads its
     * single-batch `duration` output from rank-1 primitive storage, validates
     * it as finite and non-negative, and divides it by [speed]. Rejects a
     * speed-adjusted value that becomes non-finite and returns the chunk
     * duration in seconds.
     */
    private fun predictDuration(
        session: OrtSession,
        textIds: OnnxTensor,
        styleDp: OnnxTensor,
        textMask: OnnxTensor,
        speed: Float,
    ): Float {
        session
            .run(
                mapOf(
                    SupertonicOnnxContract.TEXT_IDS to textIds,
                    SupertonicOnnxContract.STYLE_DP to styleDp,
                    SupertonicOnnxContract.TEXT_MASK to textMask,
                ),
            )
            .use { predicted ->
                val duration = requiredTensor(predicted, SupertonicOnnxContract.DURATION)
                requireTensorType(duration, OnnxJavaType.FLOAT, "duration predictor output")
                val shape = duration.info.shape
                requireRank(shape, 1, "duration predictor output")
                requireDimension(shape, 0, 1L, "duration predictor output batch")

                val buffer = duration.floatBuffer
                if (buffer.remaining() != 1) {
                    throw TensorShapeException(
                        "duration predictor output holds ${buffer.remaining()} elements, " +
                            "expected 1 for shape ${shape.contentToString()}",
                    )
                }
                val value = buffer.get()
                if (!value.isFinite() || value < 0f) {
                    throw TensorShapeException(
                        "duration predictor output must be finite and non-negative, was $value",
                    )
                }
                val adjusted = value / speed
                if (!adjusted.isFinite()) {
                    throw TensorShapeException(
                        "speed-adjusted duration must be finite, was $adjusted for speed $speed",
                    )
                }
                return adjusted
            }
    }
}

private fun styleShape(dims: StyleTensorDims): LongArray =
    longArrayOf(dims.dim0.toLong(), dims.dim1.toLong(), dims.dim2.toLong())

