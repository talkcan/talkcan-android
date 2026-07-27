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
 * Raised when `tts.json` cannot be read or does not contain the four engine
 * scalars as exact positive integers. Messages name the offending JSON path
 * and the observed value; malformed schema never permissively defaults.
 */
internal class SupertonicConfigException(message: String) : RuntimeException(message)

/**
 * Runtime configuration consumed from the model directory's `tts.json`.
 *
 * Mirrors the reference `Helper.loadCfgs` fields exactly: `ae.sample_rate`,
 * `ae.base_chunk_size`, `ttl.chunk_compress_factor`, and `ttl.latent_dim`.
 * These are runtime values read from the downloaded model set, not the static
 * [SupertonicOnnxContract] constants; the engine derives shape and sample
 * math from them.
 */
internal class SupertonicConfig internal constructor(
    val sampleRate: Int,
    val baseChunkSize: Int,
    val chunkCompressFactor: Int,
    val latentDim: Int,
) {
    companion object {
        /** Parses `tts.json` content with [org.json], failing on any schema deviation. */
        fun parse(jsonText: String): SupertonicConfig {
            val root =
                try {
                    JSONObject(jsonText)
                } catch (e: JSONException) {
                    throw SupertonicConfigException(
                        "${SupertonicOnnxContract.CONFIG_FILE}: malformed JSON: ${e.message}"
                    )
                }
            val ae = requireSection(root, "ae")
            val ttl = requireSection(root, "ttl")
            return SupertonicConfig(
                sampleRate = requirePositiveInt(ae, "ae.sample_rate"),
                baseChunkSize = requirePositiveInt(ae, "ae.base_chunk_size"),
                chunkCompressFactor = requirePositiveInt(ttl, "ttl.chunk_compress_factor"),
                latentDim = requirePositiveInt(ttl, "ttl.latent_dim"),
            )
        }

        /** Reads and parses `<modelDir>/tts.json`. */
        fun load(modelDir: File): SupertonicConfig {
            val file = File(modelDir, SupertonicOnnxContract.CONFIG_FILE)
            val text =
                try {
                    file.readText()
                } catch (e: IOException) {
                    throw SupertonicConfigException("${file.path}: read failed: ${e.message}")
                }
            return parse(text)
        }

        private fun requireSection(root: JSONObject, field: String): JSONObject {
            val value =
                root.opt(field)
                    ?: throw SupertonicConfigException(
                        "${SupertonicOnnxContract.CONFIG_FILE}: missing required object '$field'"
                    )
            return value as? JSONObject
                ?: throw SupertonicConfigException(
                    "${SupertonicOnnxContract.CONFIG_FILE}: '$field' must be a JSON object, " +
                        "found ${jsonTypeName(value)}"
                )
        }

        private fun requirePositiveInt(section: JSONObject, path: String): Int {
            val field = path.substringAfterLast('.')
            val value =
                section.opt(field)
                    ?: throw SupertonicConfigException(
                        "${SupertonicOnnxContract.CONFIG_FILE}: missing required integer '$path'"
                    )
            val number =
                value as? Number
                    ?: throw SupertonicConfigException(
                        "${SupertonicOnnxContract.CONFIG_FILE}: '$path' must be an integer, " +
                            "found ${jsonTypeName(value)}"
                    )
            val exact =
                exactLong(number)
                    ?: throw SupertonicConfigException(
                        "${SupertonicOnnxContract.CONFIG_FILE}: '$path' must be an exact integer, " +
                            "found $number"
                    )
            if (exact !in 1L..Int.MAX_VALUE.toLong()) {
                throw SupertonicConfigException(
                    "${SupertonicOnnxContract.CONFIG_FILE}: '$path' must be a positive 32-bit " +
                        "integer, found $exact"
                )
            }
            return exact.toInt()
        }
    }
}

/** Exact integral widening; null for fractional, non-finite, or non-integer numbers. */
private fun exactLong(number: Number): Long? =
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
private fun jsonTypeName(value: Any): String =
    when (value) {
        JSONObject.NULL -> "null"
        is JSONObject -> "object"
        is JSONArray -> "array"
        is String -> "string"
        is Boolean -> "boolean"
        is Number -> "number"
        else -> value.javaClass.simpleName
    }
