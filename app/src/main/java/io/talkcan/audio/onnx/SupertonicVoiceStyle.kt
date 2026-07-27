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

import java.io.File
import java.io.IOException
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Raised when a voice-style document cannot be read or its `style_ttl` /
 * `style_dp` components violate the rank-3 layout. Messages name the source,
 * component, offending path, and the expected versus observed value.
 */
internal class SupertonicStyleException(message: String) : RuntimeException(message)

/** Immutable rank-3 tensor dimensions with a precomputed, overflow-checked element count. */
internal class StyleTensorDims internal constructor(
    val dim0: Int,
    val dim1: Int,
    val dim2: Int,
    val elementCount: Int,
)

/**
 * One parsed voice-style JSON document under `voice_styles`.
 *
 * Consumes exactly `style_ttl.dims`/`style_ttl.data` and
 * `style_dp.dims`/`style_dp.data`, mirroring the reference `loadVoiceStyle`
 * for a single style path (the reference's multi-file batch dimension is the
 * desktop example's concern; each shipped style has leading dimension 1).
 * The component `type` field is ignored, as the reference loader ignores it.
 * Data is flattened row-major into primitive [FloatArray] storage ready for
 * `OnnxTensor` creation by the engine; dims and counts are validated before
 * any allocation.
 */
internal class SupertonicVoiceStyle internal constructor(
    val ttlDims: StyleTensorDims,
    val ttlData: FloatArray,
    val dpDims: StyleTensorDims,
    val dpData: FloatArray,
) {
    companion object {
        /** Parses voice-style JSON content, failing on any layout deviation. */
        fun parse(jsonText: String): SupertonicVoiceStyle = parseWithSource("voice style", jsonText)

        /** Reads and parses one voice-style file. */
        fun load(styleFile: File): SupertonicVoiceStyle {
            val text =
                try {
                    styleFile.readText()
                } catch (e: IOException) {
                    throw SupertonicStyleException("${styleFile.path}: read failed: ${e.message}")
                }
            return parseWithSource(styleFile.path, text)
        }

        private fun parseWithSource(source: String, jsonText: String): SupertonicVoiceStyle {
            val root =
                try {
                    JSONObject(jsonText)
                } catch (e: JSONException) {
                    throw SupertonicStyleException("$source: malformed JSON: ${e.message}")
                }
            val (ttlDims, ttlData) = parseComponent(source, root, SupertonicOnnxContract.STYLE_TTL)
            val (dpDims, dpData) = parseComponent(source, root, SupertonicOnnxContract.STYLE_DP)
            return SupertonicVoiceStyle(ttlDims, ttlData, dpDims, dpData)
        }

        private fun parseComponent(
            source: String,
            root: JSONObject,
            name: String,
        ): Pair<StyleTensorDims, FloatArray> {
            val component =
                root.opt(name) as? JSONObject
                    ?: throw SupertonicStyleException("$source: '$name' must be a JSON object")
            val dims =
                component.opt("dims") as? JSONArray
                    ?: throw SupertonicStyleException("$source: $name.dims must be a JSON array")
            if (dims.length() != STYLE_RANK) {
                throw SupertonicStyleException(
                    "$source: $name.dims must have rank $STYLE_RANK, found ${dims.length()}"
                )
            }
            val dim = IntArray(STYLE_RANK)
            for (i in 0 until STYLE_RANK) {
                val value = dims.opt(i)
                val number =
                    value as? Number
                        ?: throw SupertonicStyleException(
                            "$source: $name.dims[$i] must be an integer, found ${jsonKind(value)}"
                        )
                val exact =
                    styleExactLong(number)
                        ?: throw SupertonicStyleException(
                            "$source: $name.dims[$i] must be an exact integer, found $number"
                        )
                if (exact < 1L) {
                    throw SupertonicStyleException(
                        "$source: $name.dims[$i] must be positive, found $exact"
                    )
                }
                if (exact > Int.MAX_VALUE.toLong()) {
                    throw SupertonicStyleException(
                        "$source: $name.dims[$i] exceeds the 32-bit element index space: $exact"
                    )
                }
                dim[i] = exact.toInt()
            }
            if (dim[0] != 1) {
                throw SupertonicStyleException(
                    "$source: $name.dims[0] must be 1 for a single voice style, found ${dim[0]}"
                )
            }
            val total = dim[1].toLong() * dim[2].toLong()
            if (total > Int.MAX_VALUE.toLong()) {
                throw SupertonicStyleException(
                    "$source: $name element count overflows 32-bit storage: ${dim[1]} * ${dim[2]}"
                )
            }

            val data =
                component.opt("data") as? JSONArray
                    ?: throw SupertonicStyleException("$source: $name.data must be a JSON array")
            if (data.length() != dim[0]) {
                throw SupertonicStyleException(
                    "$source: $name.data must have ${dim[0]} batch entries, found ${data.length()}"
                )
            }
            val flat = FloatArray(total.toInt())
            var index = 0
            for (b in 0 until dim[0]) {
                val batch =
                    data.opt(b) as? JSONArray
                        ?: throw SupertonicStyleException(
                            "$source: $name.data[$b] must be a JSON array"
                        )
                if (batch.length() != dim[1]) {
                    throw SupertonicStyleException(
                        "$source: $name.data[$b] must have ${dim[1]} rows, found ${batch.length()}"
                    )
                }
                for (r in 0 until dim[1]) {
                    val row =
                        batch.opt(r) as? JSONArray
                            ?: throw SupertonicStyleException(
                                "$source: $name.data[$b][$r] must be a JSON array"
                            )
                    if (row.length() != dim[2]) {
                        throw SupertonicStyleException(
                            "$source: $name.data[$b][$r] must have ${dim[2]} values, " +
                                "found ${row.length()}"
                        )
                    }
                    for (c in 0 until dim[2]) {
                        val value = row.opt(c)
                        val number =
                            value as? Number
                                ?: throw SupertonicStyleException(
                                    "$source: $name.data[$b][$r][$c] must be a number, " +
                                        "found ${jsonKind(value)}"
                                )
                        val sample = number.toFloat()
                        if (!sample.isFinite()) {
                            throw SupertonicStyleException(
                                "$source: $name.data[$b][$r][$c] must be finite, found $sample"
                            )
                        }
                        flat[index++] = sample
                    }
                }
            }
            return StyleTensorDims(dim[0], dim[1], dim[2], total.toInt()) to flat
        }
    }
}

private const val STYLE_RANK = 3

/** Exact integral widening; null for fractional, non-finite, or non-integer numbers. */
private fun styleExactLong(number: Number): Long? =
    when (number) {
        is Int -> number.toLong()
        is Long -> number
        is Double -> if (number.isFinite() && number == Math.rint(number)) number.toLong() else null
        is Float ->
            if (number.isFinite() && number.toDouble() == Math.rint(number.toDouble())) {
                number.toLong()
            } else {
                null
            }
        else -> null
    }

/** Human-readable JSON value kind for bounded diagnostics. */
private fun jsonKind(value: Any?): String =
    when (value) {
        null -> "absent"
        JSONObject.NULL -> "null"
        is JSONObject -> "object"
        is JSONArray -> "array"
        is String -> "string"
        is Boolean -> "boolean"
        is Number -> "number"
        else -> value.javaClass.simpleName
    }
