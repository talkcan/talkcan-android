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
 * Projects emitted Parakeet token IDs into decoded text.
 *
 * Mirrors `transcribe-rs` `ParakeetModel::decode_tokens`: token strings are joined
 * directly (the vocabulary is already U+2581-normalized at load time), then the
 * pinned whitespace cleanup is applied in a single regex pass. Out-of-range token
 * IDs are dropped, matching the reference `filter_map`.
 */
internal object ParakeetTranscriptDecoder {
    /**
     * Pinned Parakeet cleanup `\A\s|\s\B|(\s)\b`: leading whitespace and non-boundary
     * internal spaces are removed, while a boundary whitespace (capture group 1) is
     * preserved as a single space.
     */
    private val DECODE_SPACE = Regex("""\A\s|\s\B|(\s)\b""")

    /** Rough per-token character budget used only to pre-size the join buffer. */
    private const val APPROX_CHARS_PER_TOKEN = 4

    /**
     * Decode the first [count] entries of [tokenIds] against [vocab].
     *
     * One [StringBuilder] (pre-sized) performs the concatenation and the regex
     * replacement allocates only the final string; no intermediate token list is
     * materialized.
     */
    fun decode(tokenIds: IntArray, count: Int, vocab: ParakeetVocabulary): String {
        require(count in 0..tokenIds.size) {
            "token count $count outside token id buffer of size ${tokenIds.size}"
        }
        val joined = buildString(count * APPROX_CHARS_PER_TOKEN) {
            for (i in 0 until count) {
                val token = vocab.tokenOrNull(tokenIds[i]) ?: continue
                append(token)
            }
        }
        return DECODE_SPACE.replace(joined) { match ->
            if (match.groups[1] != null) " " else ""
        }
    }
}
