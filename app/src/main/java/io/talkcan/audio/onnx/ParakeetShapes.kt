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
 * Raised when an ONNX tensor shape violates the Parakeet contract. Messages name
 * the offending tensor, the expected rank/dimension, and the observed shape.
 */
internal class TensorShapeException(message: String) : RuntimeException(message)

/**
 * Interpreted decoder recurrent-state layout. The decoder/joint graph exposes its
 * `input_states_*` metadata as rank-3 `[layers, sequence/batch, channels]` with a
 * dynamic middle axis; the zero-initialized state for one batch pins that axis to 1.
 */
internal class DecoderStateLayout internal constructor(
    val layers: Int,
    val channels: Int,
) {
    /** Shape of the zero-initialized recurrent state for a single batch item. */
    fun initialShape(): LongArray = longArrayOf(layers.toLong(), 1L, channels.toLong())
}

/**
 * Interpreted encoder output layout after the `[0, 2, 1]` transpose, i.e.
 * `[batch, time, features]`. [batch] is `-1` when read from session metadata
 * (dynamic) and concrete at inference time.
 */
internal class EncoderOutputLayout internal constructor(
    val batch: Int,
    val timeSteps: Int,
    val features: Int,
)

/**
 * Strict, allocation-free interpretation of the tensor shapes the Parakeet decoder
 * depends on. All checks throw [TensorShapeException] with the expected and observed
 * shape rather than silently coercing.
 */
internal object ParakeetShapes {
    /** Dynamic axis marker used by ONNX Runtime session metadata. */
    const val DYNAMIC_AXIS: Int = -1

    /**
     * Interpret decoder `input_states_*` metadata. Requires rank 3 with positive
     * static `layers` (axis 0) and `channels` (axis 2); the dynamic middle axis is
     * ignored because the initial state pins it to 1 (see [DecoderStateLayout.initialShape]).
     */
    fun decoderState(metadataShape: LongArray): DecoderStateLayout {
        requireRank(metadataShape, 3, "decoder recurrent state")
        val layers = requirePositive(metadataShape[0], "decoder state layers")
        val channels = requirePositive(metadataShape[2], "decoder state channels")
        return DecoderStateLayout(layers, channels)
    }

    /**
     * Interpret a runtime (post-transpose) encoder output shape `[batch, time, features]`.
     * Batch may be dynamic; time and features must be positive static dimensions.
     */
    fun encoderOutput(shape: LongArray): EncoderOutputLayout {
        requireRank(shape, 3, "encoder output")
        val batch = requirePositiveOrDynamic(shape[0], "encoder output batch")
        val timeSteps = requirePositive(shape[1], "encoder output time steps")
        val features = requirePositive(shape[2], "encoder output features")
        return EncoderOutputLayout(batch, timeSteps, features)
    }

    /**
     * Interpret a decoder logits shape, collapsing the leading batch/sequence axes
     * (each must be singleton or dynamic) to the trailing vocabulary dimension, per
     * the reference `remove_axis(0)` before argmax. Returns that logit length.
     */
    fun logitsLength(shape: LongArray): Int {
        if (shape.isEmpty()) {
            throw TensorShapeException("decoder logits must have at least one dimension, was rank 0")
        }
        val last = shape.size - 1
        val length = requirePositive(shape[last], "decoder logits vocabulary dimension")
        for (axis in 0 until last) {
            val dim = shape[axis]
            if (dim != 1L && dim != DYNAMIC_AXIS.toLong()) {
                throw TensorShapeException(
                    "decoder logits leading axis $axis must be singleton or dynamic (1 or -1), " +
                        "was $dim in ${shape.contentToString()}",
                )
            }
        }
        return length
    }

    private fun requireRank(shape: LongArray, rank: Int, what: String) {
        if (shape.size != rank) {
            throw TensorShapeException(
                "$what must be rank $rank, was rank ${shape.size} ${shape.contentToString()}",
            )
        }
    }

    private fun requirePositive(dim: Long, what: String): Int {
        if (dim <= 0L) {
            throw TensorShapeException("$what must be a positive static dimension, was $dim")
        }
        if (dim > Int.MAX_VALUE.toLong()) {
            throw TensorShapeException("$what exceeds the Int dimension range: $dim")
        }
        return dim.toInt()
    }

    private fun requirePositiveOrDynamic(dim: Long, what: String): Int =
        if (dim == DYNAMIC_AXIS.toLong()) DYNAMIC_AXIS else requirePositive(dim, what)
}
