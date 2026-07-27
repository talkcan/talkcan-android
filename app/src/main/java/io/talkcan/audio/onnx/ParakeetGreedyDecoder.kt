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
 * Outcome of one decoder/joint selection within a single encoder time step.
 *
 * @param token selected token id (bounded by the runtime vocabulary size).
 * @param emit true when [token] is non-blank: the caller accepts the token and
 *   advances recurrent state to the freshly produced state.
 * @param advanceTime true when the caller must move to the next encoder time step
 *   and reset the per-step emission counter: either a blank was selected or the
 *   [ParakeetGreedyDecoder.MAX_TOKENS_PER_STEP] bound was reached.
 */
internal data class GreedyDecision(
    val token: Int,
    val emit: Boolean,
    val advanceTime: Boolean,
)

/**
 * Bounded greedy token selection for the Parakeet recurrent decoder.
 *
 * Mirrors `transcribe-rs` `ParakeetModel::decode_sequence`: each [decide] call runs
 * argmax over at most the runtime vocabulary slice of the logits (never the full
 * 8198-entry tensor), compares against the runtime [blankIndex], and updates the
 * per-sequence emission counter so that at most [MAX_TOKENS_PER_STEP] tokens are
 * emitted before the encoder time step advances. Constructed per engine from the
 * loaded vocabulary's runtime [vocabSize]/[blankIndex]; the static inventory
 * constants in [ParakeetOnnxContract] are validation evidence only.
 */
internal class ParakeetGreedyDecoder(
    val vocabSize: Int,
    val blankIndex: Int,
) {
    init {
        require(vocabSize > 0) { "vocabulary size must be positive, was $vocabSize" }
        require(blankIndex in 0 until vocabSize) {
            "blank index $blankIndex outside vocabulary size $vocabSize"
        }
    }

    private var emittedTokens = 0

    /** Reset the emission counter at the start of each encoded sequence. */
    fun beginSequence() {
        emittedTokens = 0
    }

    /**
     * Select the next token from the first [logitCount] logits and return the
     * acceptance/advance decision. Argmax examines `min(logitCount, vocabSize)`
     * entries, so extra logit padding is ignored. Selection is NaN-safe (a NaN
     * score never displaces the current best) and prefers the first index on exact
     * ties; on real, finite model logits the maximum is unique, matching the pinned
     * reference output.
     */
    fun decide(logits: FloatArray, logitCount: Int = logits.size): GreedyDecision {
        require(logitCount in 0..logits.size) {
            "logit count $logitCount outside logit buffer of size ${logits.size}"
        }
        val token = argmax(logits, minOf(logitCount, vocabSize))
        val emit = token != blankIndex
        if (emit) emittedTokens++
        val advanceTime = !emit || emittedTokens >= MAX_TOKENS_PER_STEP
        if (advanceTime) emittedTokens = 0
        return GreedyDecision(token, emit, advanceTime)
    }

    private fun argmax(logits: FloatArray, limit: Int): Int {
        if (limit <= 0) return blankIndex
        var bestIndex = 0
        var bestScore = logits[0]
        var i = 1
        while (i < limit) {
            val score = logits[i]
            if (score > bestScore) {
                bestScore = score
                bestIndex = i
            }
            i++
        }
        return bestIndex
    }

    companion object {
        /** Maximum tokens emitted per encoder time step (`MAX_TOKENS_PER_STEP`). */
        const val MAX_TOKENS_PER_STEP: Int = 10
    }
}
