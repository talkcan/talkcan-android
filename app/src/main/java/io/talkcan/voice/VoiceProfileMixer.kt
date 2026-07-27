/*
 * Weighted voice-style mixing adapted from Topping1/Supertonic-Voice-Mixer.
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

/** Available compatible source and its already materialized Supertonic-3 tensors. */
internal data class VoiceProfileMixSource(
    val summary: VoiceProfileSummary,
    val tensors: VoiceProfileTensors,
) {
    init {
        require(summary.selectable) { "Voice profile ${summary.id.value} is not selectable" }
    }
}

internal class ResolvedVoiceProfileWeights private constructor(
    val mode: VoiceProfileWeightMode,
    values: List<Double>,
    val randomSeed: Long?,
) {
    val values: List<Double> = values.toList()

    init {
        require(this.values.isNotEmpty()) { "Resolved voice profile weights must not be empty" }
        require(this.values.all { it.isFinite() && it > 0.0 }) {
            "Resolved voice profile weights must be finite and strictly positive"
        }
        require((mode == VoiceProfileWeightMode.RANDOM) == (randomSeed != null)) {
            if (mode == VoiceProfileWeightMode.RANDOM) {
                "Random voice profile weights require an explicit seed"
            } else {
                "$mode voice profile weights do not accept a seed"
            }
        }
    }

    companion object {
        fun equal(sourceCount: Int): ResolvedVoiceProfileWeights {
            requireValidSourceCount(sourceCount)
            return ResolvedVoiceProfileWeights(
                mode = VoiceProfileWeightMode.EQUAL,
                values = List(sourceCount) { 1.0 / sourceCount.toDouble() },
                randomSeed = null,
            )
        }

        fun manual(rawWeights: List<Double>): ResolvedVoiceProfileWeights {
            requireValidSourceCount(rawWeights.size)
            return ResolvedVoiceProfileWeights(
                mode = VoiceProfileWeightMode.MANUAL,
                values = VoiceProfileMixer.normalizeStrictlyPositiveWeights(rawWeights),
                randomSeed = null,
            )
        }

        fun random(sourceCount: Int, seed: Long): ResolvedVoiceProfileWeights {
            requireValidSourceCount(sourceCount)
            val random = java.util.Random(seed)
            val rawWeights = List(sourceCount) { 1.0 - random.nextDouble() }
            return ResolvedVoiceProfileWeights(
                mode = VoiceProfileWeightMode.RANDOM,
                values = VoiceProfileMixer.normalizeStrictlyPositiveWeights(rawWeights),
                randomSeed = seed,
            )
        }

        private fun requireValidSourceCount(sourceCount: Int) {
            require(sourceCount in VoiceProfileLimits.MIN_MIX_SOURCES..VoiceProfileLimits.MAX_MIX_SOURCES) {
                "A mix requires ${VoiceProfileLimits.MIN_MIX_SOURCES}-${VoiceProfileLimits.MAX_MIX_SOURCES} sources"
            }
        }
    }
}

internal object VoiceProfileMixer {
    /**
     * Materializes a complete convex mixture. Raw weights are normalized in [Double], each
     * tensor element is accumulated in [Double], then materialized once as [Float].
     */
    fun mix(
        sources: List<VoiceProfileMixSource>,
        rawWeights: List<Double>,
    ): VoiceProfileDraft = mix(sources, ResolvedVoiceProfileWeights.manual(rawWeights))

    fun mix(
        sources: List<VoiceProfileMixSource>,
        weighting: ResolvedVoiceProfileWeights,
    ): VoiceProfileDraft {
        require(sources.size in VoiceProfileLimits.MIN_MIX_SOURCES..VoiceProfileLimits.MAX_MIX_SOURCES) {
            "A mix requires ${VoiceProfileLimits.MIN_MIX_SOURCES}-${VoiceProfileLimits.MAX_MIX_SOURCES} sources"
        }
        require(sources.map { it.summary.id }.toSet().size == sources.size) {
            "Voice profile mix source IDs must be distinct"
        }
        require(weighting.values.size == sources.size) {
            "Expected ${sources.size} weights, found ${weighting.values.size}"
        }
        val normalizedWeights = weighting.values
        val tensors = mixTensors(sources, normalizedWeights)
        val provenance =
            VoiceProfileProvenance(
                sources =
                    sources.mapIndexed { index, source ->
                        VoiceProfileSourceProvenance(
                            id = source.summary.id,
                            displayName = source.summary.displayName,
                            normalizedWeight = normalizedWeights[index],
                        )
                    },
                weightMode = weighting.mode,
                randomSeed = weighting.randomSeed,
            )
        return VoiceProfileDraft(
            baseline = tensors,
            current = tensors,
            provenance = provenance,
        )
    }

    internal fun normalizeStrictlyPositiveWeights(rawWeights: List<Double>): List<Double> {
        require(rawWeights.isNotEmpty()) { "At least one weight is required" }
        require(rawWeights.all { it.isFinite() && it > 0.0 }) {
            "Every effective voice profile weight must be finite and strictly positive"
        }
        val scale = rawWeights.maxOrNull()!!
        val scaledSum = rawWeights.sumOf { it / scale }
        require(scaledSum.isFinite() && scaledSum > 0.0) {
            "Voice profile weight sum must be finite and positive"
        }
        val normalized = List(rawWeights.size) { index -> (rawWeights[index] / scale) / scaledSum }
        require(normalized.all { it.isFinite() && it > 0.0 }) {
            "Normalized voice profile weights must remain finite and strictly positive"
        }
        return normalized
    }

    private fun mixTensors(
        sources: List<VoiceProfileMixSource>,
        weights: List<Double>,
    ): VoiceProfileTensors {
        val ttl = accumulate(sources, weights, ttl = true)
        val dp = accumulate(sources, weights, ttl = false)
        return VoiceProfileTensors(
            ttl = VoiceTensor.takeOwnership(Supertonic3VoiceProfileContract.TTL_DIMENSIONS, ttl),
            dp = VoiceTensor.takeOwnership(Supertonic3VoiceProfileContract.DP_DIMENSIONS, dp),
        )
    }

    private fun accumulate(
        sources: List<VoiceProfileMixSource>,
        weights: List<Double>,
        ttl: Boolean,
    ): FloatArray {
        val size =
            if (ttl) {
                Supertonic3VoiceProfileContract.TTL_ELEMENT_COUNT
            } else {
                Supertonic3VoiceProfileContract.DP_ELEMENT_COUNT
            }
        val accumulated = DoubleArray(size)
        sources.forEachIndexed { sourceIndex, source ->
            val tensor = if (ttl) source.tensors.ttl else source.tensors.dp
            val weight = weights[sourceIndex]
            tensor.forEachIndexed { index, value ->
                accumulated[index] += value.toDouble() * weight
            }
        }
        return FloatArray(size) { index ->
            val value = accumulated[index]
            require(value.isFinite()) { "Mixed voice profile tensor contains a non-finite value at $index" }
            val materialized = value.toFloat()
            require(materialized.isFinite()) {
                "Mixed voice profile tensor exceeds finite Float storage at $index"
            }
            materialized
        }
    }
}
