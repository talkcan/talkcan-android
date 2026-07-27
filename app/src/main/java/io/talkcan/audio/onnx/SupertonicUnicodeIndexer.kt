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
 * Raised when `unicode_indexer.json` cannot be read or does not contain
 * exactly [SupertonicOnnxContract.UNICODE_INDEXER_SIZE] exact integer
 * entries. Messages name the asset and the offending entry or count;
 * malformed schema never permissively defaults.
 */
internal class SupertonicIndexerException(message: String) : RuntimeException(message)

/**
 * Code-point-to-token-id table parsed once from `unicode_indexer.json`.
 *
 * Mirrors the reference `UnicodeProcessor` indexer: a top-level JSON array
 * of exact integers, one entry per covered code point, loaded into primitive
 * [LongArray] storage. The shipped asset holds exactly
 * [SupertonicOnnxContract.UNICODE_INDEXER_SIZE] entries; any other length is
 * rejected because the model's token vocabulary is pinned to that range.
 * Code points outside the covered range map to `-1`, matching the reference
 * `indexer` fallback.
 */
internal class SupertonicUnicodeIndexer private constructor(private val codePointIds: LongArray) {
    /** Number of covered code points; [SupertonicOnnxContract.UNICODE_INDEXER_SIZE] for shipped assets. */
    val size: Int
        get() = codePointIds.size

    /** Model token id for [codePoint]; `-1` when the code point is outside [size]. */
    fun idFor(codePoint: Int): Long =
        if (codePoint in 0 until codePointIds.size) codePointIds[codePoint] else -1L

    companion object {
        /** Parses `unicode_indexer.json` content with [org.json], failing on any schema deviation. */
        fun parse(jsonText: String): SupertonicUnicodeIndexer {
            val root =
                try {
                    JSONArray(jsonText)
                } catch (e: JSONException) {
                    throw SupertonicIndexerException(
                        "${SupertonicOnnxContract.UNICODE_INDEXER_FILE}: malformed JSON: ${e.message}"
                    )
                }
            if (root.length() != SupertonicOnnxContract.UNICODE_INDEXER_SIZE) {
                throw SupertonicIndexerException(
                    "${SupertonicOnnxContract.UNICODE_INDEXER_FILE}: must hold exactly " +
                        "${SupertonicOnnxContract.UNICODE_INDEXER_SIZE} code-point entries, " +
                        "found ${root.length()}"
                )
            }
            val ids = LongArray(SupertonicOnnxContract.UNICODE_INDEXER_SIZE)
            for (i in ids.indices) {
                val value = root.opt(i)
                val number =
                    value as? Number
                        ?: throw SupertonicIndexerException(
                            "${SupertonicOnnxContract.UNICODE_INDEXER_FILE}: entry $i must be an " +
                                "integer, found ${indexerJsonKind(value)}"
                        )
                ids[i] =
                    indexerExactLong(number)
                        ?: throw SupertonicIndexerException(
                            "${SupertonicOnnxContract.UNICODE_INDEXER_FILE}: entry $i must be an " +
                                "exact integer, found $number"
                        )
            }
            return SupertonicUnicodeIndexer(ids)
        }

        /** Reads and parses one `unicode_indexer.json` file. */
        fun load(indexerFile: File): SupertonicUnicodeIndexer {
            val text =
                try {
                    indexerFile.readText()
                } catch (e: IOException) {
                    throw SupertonicIndexerException("${indexerFile.path}: read failed: ${e.message}")
                }
            return parse(text)
        }
    }
}

/**
 * One preprocessed text ready for the duration predictor and text encoder.
 *
 * Built through [SupertonicText.preprocessText], which wraps the text in
 * `<lang>…</lang>` tags: the language is carried inside the processed text,
 * never as a separate model tensor. Token ids iterate the processed text by
 * Unicode code point (surrogate-pair aware), each mapped through
 * [SupertonicUnicodeIndexer]. The mask is the exact single-text reduction of
 * the reference `length_to_mask`: with one text, `max_len` equals the text
 * length, so every position of the flattened `[1, 1, n]` mask is `1.0f`.
 * Both arrays are primitive storage shaped for direct `OnnxTensor` creation.
 */
internal class PreparedSupertonicText internal constructor(
    val ids: LongArray,
    val mask: FloatArray,
) {
    init {
        require(ids.size == mask.size) {
            "text ids and mask must share one length, found ${ids.size} and ${mask.size}"
        }
        require(ids.isNotEmpty()) { "prepared text must contain at least one code point" }
    }

    /** Code-point count `n`; the shared text-axis length of every stage tensor. */
    val length: Int
        get() = ids.size

    companion object {
        /**
         * Normalizes and language-tags [text] for [language], then maps its
         * code points through [indexer] into token ids plus the all-`1.0f`
         * single-text mask.
         *
         * @throws SupertonicLanguageException if [language] is unsupported.
         */
        fun prepare(
            indexer: SupertonicUnicodeIndexer,
            text: String,
            language: String,
        ): PreparedSupertonicText {
            val processed = SupertonicText.preprocessText(text, language)
            val length = processed.codePointCount(0, processed.length)
            require(length > 0) { "preprocessed text must contain at least one code point" }

            val ids = LongArray(length)
            val mask = FloatArray(length)
            var charIndex = 0
            var codePointIndex = 0
            while (charIndex < processed.length) {
                val codePoint = Character.codePointAt(processed, charIndex)
                ids[codePointIndex] = indexer.idFor(codePoint)
                mask[codePointIndex] = 1.0f
                codePointIndex++
                charIndex += Character.charCount(codePoint)
            }
            return PreparedSupertonicText(ids, mask)
        }
    }
}

/** Exact integral widening; null for fractional, non-finite, or non-integer numbers. */
private fun indexerExactLong(number: Number): Long? =
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
private fun indexerJsonKind(value: Any?): String =
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
