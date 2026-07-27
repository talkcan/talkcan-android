/*
 * Latent-space operations adapted from Topping1/Supertonic-Voice-Mixer.
 * Copyright (c) 2025 Topping1
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
package io.talkcan.voice

import kotlin.math.PI
import kotlin.math.round
import kotlin.math.sin

internal sealed interface VoiceProfileTtlOperation {
    data object FeatureMirror : VoiceProfileTtlOperation

    data object TimeMirror : VoiceProfileTtlOperation

    data object Invert : VoiceProfileTtlOperation

    data class ScalarAdd(val value: Double) : VoiceProfileTtlOperation {
        init {
            require(value.isFinite()) { "Scalar addition value must be finite" }
        }
    }

    data class ScalarMultiply(val factor: Double) : VoiceProfileTtlOperation {
        init {
            require(factor.isFinite()) { "Scalar multiplication factor must be finite" }
        }
    }

    data object TimeDerivative : VoiceProfileTtlOperation

    data class SeededFeatureRoll(val seed: Long) : VoiceProfileTtlOperation

    data class SeededTimeRoll(val seed: Long) : VoiceProfileTtlOperation

    data class FeatureSharpen(val strength: Double = 1.5) : VoiceProfileTtlOperation {
        init {
            require(strength.isFinite()) { "Feature sharpen strength must be finite" }
        }
    }

    data class Quantize(val factor: Double = 5.0) : VoiceProfileTtlOperation {
        init {
            require(factor.isFinite() && factor > 0.0) { "Quantize factor must be finite and positive" }
        }
    }

    data class FeatureEcho(
        val delay: Int = 2,
        val decay: Double = 0.5,
    ) : VoiceProfileTtlOperation {
        init {
            require(delay in 1 until Supertonic3VoiceProfileContract.TTL_DIMENSIONS.columns) {
                "Feature echo delay must address a distinct feature column"
            }
            require(decay.isFinite()) { "Feature echo decay must be finite" }
        }
    }

    data class FeatureTremolo(val depth: Double = 0.5) : VoiceProfileTtlOperation {
        init {
            require(depth.isFinite() && depth in 0.0..1.0) {
                "Feature tremolo depth must be finite and between 0 and 1"
            }
        }
    }

    data class SeededJitter(
        val amount: Double = 0.2,
        val seed: Long,
    ) : VoiceProfileTtlOperation {
        init {
            require(amount.isFinite() && amount in 0.0..1.0) {
                "Jitter amount must be finite and between 0 and 1"
            }
        }
    }
}

internal data class VoiceProfileTtlOperationResult(
    val ttl: VoiceTensor,
    val provenance: VoiceProfileOperationRecord,
)

internal object VoiceProfileTtlOperations {
    private val dimensions = Supertonic3VoiceProfileContract.TTL_DIMENSIONS
    private val rows = dimensions.rows
    private val columns = dimensions.columns

    fun apply(
        input: VoiceTensor,
        operation: VoiceProfileTtlOperation,
    ): VoiceProfileTtlOperationResult {
        require(input.dimensions == dimensions) {
            "TTL operation requires dimensions ${dimensions.asList()}, found ${input.dimensions.asList()}"
        }
        val candidate: FloatArray
        val record: VoiceProfileOperationRecord
        when (operation) {
            VoiceProfileTtlOperation.FeatureMirror -> {
                candidate = remap(input) { row, column -> index(row, columns - 1 - column) }
                record = record(VoiceProfileOperationKind.FEATURE_MIRROR)
            }
            VoiceProfileTtlOperation.TimeMirror -> {
                candidate = remap(input) { row, column -> index(rows - 1 - row, column) }
                record = record(VoiceProfileOperationKind.TIME_MIRROR)
            }
            VoiceProfileTtlOperation.Invert -> {
                candidate = transform(input) { value -> -value }
                record = record(VoiceProfileOperationKind.INVERT)
            }
            is VoiceProfileTtlOperation.ScalarAdd -> {
                candidate = transform(input) { value -> value + operation.value }
                record = record(VoiceProfileOperationKind.SCALAR_ADD, "value" to operation.value)
            }
            is VoiceProfileTtlOperation.ScalarMultiply -> {
                candidate = transform(input) { value -> value * operation.factor }
                record = record(VoiceProfileOperationKind.SCALAR_MULTIPLY, "factor" to operation.factor)
            }
            VoiceProfileTtlOperation.TimeDerivative -> {
                candidate = gradient(input, acrossFeatures = false) { _, gradient -> gradient }
                record = record(VoiceProfileOperationKind.TIME_DERIVATIVE)
            }
            is VoiceProfileTtlOperation.SeededFeatureRoll -> {
                val shift = java.util.Random(operation.seed).nextInt(columns)
                candidate = roll(input, rowShift = 0, columnShift = shift)
                record = seededRecord(
                    VoiceProfileOperationKind.FEATURE_ROLL,
                    operation.seed,
                    "shift" to shift.toDouble(),
                )
            }
            is VoiceProfileTtlOperation.SeededTimeRoll -> {
                val shift = java.util.Random(operation.seed).nextInt(rows)
                candidate = roll(input, rowShift = shift, columnShift = 0)
                record = seededRecord(
                    VoiceProfileOperationKind.TIME_ROLL,
                    operation.seed,
                    "shift" to shift.toDouble(),
                )
            }
            is VoiceProfileTtlOperation.FeatureSharpen -> {
                candidate = gradient(input, acrossFeatures = true) { value, gradient ->
                    value + gradient * operation.strength
                }
                record = record(
                    VoiceProfileOperationKind.FEATURE_SHARPEN,
                    "strength" to operation.strength,
                )
            }
            is VoiceProfileTtlOperation.Quantize -> {
                candidate = transform(input) { value -> round(value * operation.factor) / operation.factor }
                record = record(VoiceProfileOperationKind.QUANTIZE, "factor" to operation.factor)
            }
            is VoiceProfileTtlOperation.FeatureEcho -> {
                candidate = FloatArray(dimensions.elementCount) { target ->
                    val row = target / columns
                    val column = target % columns
                    val echoColumn = Math.floorMod(column - operation.delay, columns)
                    checkedFloat(
                        input[target].toDouble() + input[index(row, echoColumn)].toDouble() * operation.decay,
                        target,
                    )
                }
                record = record(
                    VoiceProfileOperationKind.FEATURE_ECHO,
                    "delay" to operation.delay.toDouble(),
                    "decay" to operation.decay,
                )
            }
            is VoiceProfileTtlOperation.FeatureTremolo -> {
                candidate = FloatArray(dimensions.elementCount) { target ->
                    val column = target % columns
                    val phase = 2.0 * PI * column.toDouble() / (columns - 1).toDouble()
                    checkedFloat(
                        input[target].toDouble() * (1.0 + operation.depth * sin(phase)),
                        target,
                    )
                }
                record = record(
                    VoiceProfileOperationKind.FEATURE_TREMOLO,
                    "depth" to operation.depth,
                )
            }
            is VoiceProfileTtlOperation.SeededJitter -> {
                val random = java.util.Random(operation.seed)
                candidate = FloatArray(dimensions.elementCount) { target ->
                    val factor = 1.0 - operation.amount + random.nextDouble() * operation.amount * 2.0
                    checkedFloat(input[target].toDouble() * factor, target)
                }
                record = seededRecord(
                    VoiceProfileOperationKind.JITTER,
                    operation.seed,
                    "amount" to operation.amount,
                )
            }
        }
        return VoiceProfileTtlOperationResult(
            ttl = VoiceTensor.takeOwnership(dimensions, candidate),
            provenance = record,
        )
    }

    private fun transform(
        input: VoiceTensor,
        transform: (Double) -> Double,
    ): FloatArray =
        FloatArray(dimensions.elementCount) { index ->
            checkedFloat(transform(input[index].toDouble()), index)
        }

    private fun remap(
        input: VoiceTensor,
        sourceIndex: (row: Int, column: Int) -> Int,
    ): FloatArray =
        FloatArray(dimensions.elementCount) { target ->
            val row = target / columns
            val column = target % columns
            input[sourceIndex(row, column)]
        }

    private fun roll(
        input: VoiceTensor,
        rowShift: Int,
        columnShift: Int,
    ): FloatArray =
        remap(input) { row, column ->
            index(Math.floorMod(row - rowShift, rows), Math.floorMod(column - columnShift, columns))
        }

    private fun gradient(
        input: VoiceTensor,
        acrossFeatures: Boolean,
        combine: (value: Double, gradient: Double) -> Double,
    ): FloatArray =
        FloatArray(dimensions.elementCount) { target ->
            val row = target / columns
            val column = target % columns
            val coordinate = if (acrossFeatures) column else row
            val length = if (acrossFeatures) columns else rows
            val previous = if (coordinate == 0) coordinate else coordinate - 1
            val next = if (coordinate == length - 1) coordinate else coordinate + 1
            val previousIndex = if (acrossFeatures) index(row, previous) else index(previous, column)
            val nextIndex = if (acrossFeatures) index(row, next) else index(next, column)
            val divisor = if (coordinate == 0 || coordinate == length - 1) 1.0 else 2.0
            val derivative = (input[nextIndex].toDouble() - input[previousIndex].toDouble()) / divisor
            checkedFloat(combine(input[target].toDouble(), derivative), target)
        }

    private fun checkedFloat(value: Double, index: Int): Float {
        require(value.isFinite()) { "TTL operation produced a non-finite value at $index" }
        val materialized = value.toFloat()
        require(materialized.isFinite()) { "TTL operation exceeds finite Float storage at $index" }
        return materialized
    }

    private fun index(row: Int, column: Int): Int = row * columns + column

    private fun record(
        kind: VoiceProfileOperationKind,
        vararg parameters: Pair<String, Double>,
    ): VoiceProfileOperationRecord =
        VoiceProfileOperationRecord(kind = kind, parameters = linkedMapOf(*parameters))

    private fun seededRecord(
        kind: VoiceProfileOperationKind,
        seed: Long,
        vararg parameters: Pair<String, Double>,
    ): VoiceProfileOperationRecord =
        VoiceProfileOperationRecord(kind = kind, parameters = linkedMapOf(*parameters), seed = seed)
}
