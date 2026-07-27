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
 * Raised when a Parakeet vocabulary file cannot establish a usable, dense,
 * blank-anchored token table.
 */
internal class ParakeetVocabularyException(message: String) : RuntimeException(message)

/**
 * Dense, ID-indexed Parakeet vocabulary.
 *
 * Mirrors `transcribe-rs` `decode::tokens::load_vocab`: every token is stored at
 * the index given by its `id`, so lookups are a single array access with no map
 * hashing. The table is sized to `maxId + 1`; IDs that never appear in the file
 * resolve to the empty string. The runtime [size] and [blankIndex] are the
 * authoritative values for greedy decoding and MUST NOT be substituted with the
 * static inventory constants in [ParakeetOnnxContract].
 */
internal class ParakeetVocabulary internal constructor(
    private val tokens: Array<String>,
    val blankIndex: Int,
) {
    /** Runtime vocabulary length; greedy selection never examines this many or more. */
    val size: Int get() = tokens.size

    /** Decoded token text for [id] (already U+2581-normalized), or null when out of range. */
    fun tokenOrNull(id: Int): String? = tokens.getOrNull(id)

    companion object {
        private const val BLANK_TOKEN = "<blk>"
        private const val SENTENCEPIECE_SPACE = '\u2581'

        /** Parse a complete vocabulary file body. */
        fun load(content: String): ParakeetVocabulary = load(content.lineSequence())

        /**
         * Buffered single-pass parse over vocabulary lines.
         *
         * Each line is `token id` split on a single space. Invalid lines (too few
         * fields, non-numeric or negative id) are skipped. Pairs are buffered until
         * the maximum id is known, then one dense `Array(maxId + 1)` is allocated and
         * filled exactly once, replacing U+2581 with a space per token. A missing
         * `<blk>` entry is a hard load failure.
         */
        fun load(lines: Sequence<String>): ParakeetVocabulary {
            val tokens = ArrayList<String>()
            val ids = ArrayList<Int>()
            var maxId = 0
            var blankIndex = -1

            for (rawLine in lines) {
                val parts = rawLine.trimEnd().split(' ')
                if (parts.size < 2) continue
                val id = parts[1].toIntOrNull() ?: continue
                if (id < 0) continue
                val token = parts[0]
                if (token == BLANK_TOKEN) blankIndex = id
                tokens.add(token)
                ids.add(id)
                if (id > maxId) maxId = id
            }

            if (blankIndex < 0) {
                throw ParakeetVocabularyException("Missing <blk> token in vocabulary")
            }

            val dense = Array(maxId + 1) { "" }
            for (i in tokens.indices) {
                dense[ids[i]] = tokens[i].replace(SENTENCEPIECE_SPACE, ' ')
            }
            return ParakeetVocabulary(dense, blankIndex)
        }
    }
}
