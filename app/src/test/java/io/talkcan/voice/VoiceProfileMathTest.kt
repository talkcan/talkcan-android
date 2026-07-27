package io.talkcan.voice

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceProfileMathTest {
    @Test
    fun weightedMixNormalizesAndMaterializesBothTensors() {
        val first = source("first", ttl = 2f, dp = 10f)
        val second = source("second", ttl = 8f, dp = 30f)

        val draft = VoiceProfileMixer.mix(listOf(first, second), listOf(1.0, 3.0))

        assertEquals(6.5f, draft.current.ttl[0], 0.000_001f)
        assertEquals(25f, draft.current.dp[0], 0.000_001f)
        assertEquals(0.25, draft.provenance.sources[0].normalizedWeight, 1e-12)
        assertEquals(0.75, draft.provenance.sources[1].normalizedWeight, 1e-12)
        assertEquals(VoiceProfileWeightMode.MANUAL, draft.provenance.weightMode)
    }

    @Test
    fun equalAndSeededRandomWeightsAreDeterministicAndPositive() {
        val equal = ResolvedVoiceProfileWeights.equal(4)
        val randomA = ResolvedVoiceProfileWeights.random(4, seed = 7123L)
        val randomB = ResolvedVoiceProfileWeights.random(4, seed = 7123L)
        val randomC = ResolvedVoiceProfileWeights.random(4, seed = 7124L)

        equal.values.forEach { assertEquals(0.25, it, 0.0) }
        assertEquals(randomA.values, randomB.values)
        assertNotEquals(randomA.values, randomC.values)
        assertTrue(randomA.values.all { it > 0.0 && it.isFinite() })
        assertEquals(1.0, randomA.values.sum(), 1e-12)
        assertEquals(7123L, randomA.randomSeed)
    }

    @Test
    fun weightAndSourceBoundsRejectWithoutMaterializing() {
        val first = source("first", ttl = 1f, dp = 1f)
        val second = source("second", ttl = 2f, dp = 2f)

        assertThrows(IllegalArgumentException::class.java) {
            VoiceProfileMixer.mix(listOf(first), listOf(1.0))
        }
        assertThrows(IllegalArgumentException::class.java) {
            VoiceProfileMixer.mix(listOf(first, first), listOf(1.0, 1.0))
        }
        assertThrows(IllegalArgumentException::class.java) {
            VoiceProfileMixer.mix(listOf(first, second), listOf(1.0, 0.0))
        }
        assertThrows(IllegalArgumentException::class.java) {
            VoiceProfileMixer.mix(listOf(first, second), listOf(1.0, Double.NaN))
        }
        assertThrows(IllegalArgumentException::class.java) {
            VoiceProfileMixer.mix(List(17) { source("source-$it", it.toFloat(), it.toFloat()) }, List(17) { 1.0 })
        }
    }

    @Test
    fun normalizationHandlesFiniteWeightsWhoseUnscaledSumOverflows() {
        val resolved = ResolvedVoiceProfileWeights.manual(listOf(Double.MAX_VALUE, Double.MAX_VALUE))

        assertEquals(listOf(0.5, 0.5), resolved.values)
    }

    @Test
    fun derivativeMatchesNumpyEndpointAndCentralDifferences() {
        val ttl = ttlTensor { row, _ -> (row * row).toFloat() }

        val result = VoiceProfileTtlOperations.apply(ttl, VoiceProfileTtlOperation.TimeDerivative)

        assertEquals(1f, result.ttl[index(0, 0)], 0f)
        assertEquals(2f, result.ttl[index(1, 0)], 0f)
        assertEquals(40f, result.ttl[index(20, 0)], 0f)
        assertEquals(97f, result.ttl[index(49, 0)], 0f)
    }

    @Test
    fun seededRollsWrapAndRepeatExactly() {
        val ttl = ttlTensor { row, column -> (row * 1_000 + column).toFloat() }
        val featureA =
            VoiceProfileTtlOperations.apply(ttl, VoiceProfileTtlOperation.SeededFeatureRoll(seed = 42L))
        val featureB =
            VoiceProfileTtlOperations.apply(ttl, VoiceProfileTtlOperation.SeededFeatureRoll(seed = 42L))
        val time = VoiceProfileTtlOperations.apply(ttl, VoiceProfileTtlOperation.SeededTimeRoll(seed = 99L))
        val featureShift = featureA.provenance.parameters.getValue("shift").toInt()
        val timeShift = time.provenance.parameters.getValue("shift").toInt()

        assertArrayEquals(featureA.ttl.copyValues(), featureB.ttl.copyValues(), 0f)
        assertEquals(
            ttl[index(0, Math.floorMod(-featureShift, 256))],
            featureA.ttl[index(0, 0)],
            0f,
        )
        assertEquals(
            ttl[index(Math.floorMod(-timeShift, 50), 0)],
            time.ttl[index(0, 0)],
            0f,
        )
    }

    @Test
    fun seededTransformsRepeatAndDpIsRetained() {
        val draft = VoiceProfileMixer.mix(
            listOf(source("first", 1f, 3f), source("second", 2f, 5f)),
            listOf(1.0, 1.0),
        )
        val operation = VoiceProfileTtlOperation.SeededJitter(amount = 0.2, seed = 8675309L)
        val first = VoiceProfileDraftEditor.apply(draft, operation)
        val second = VoiceProfileDraftEditor.apply(draft, operation)

        assertArrayEquals(first.current.ttl.copyValues(), second.current.ttl.copyValues(), 0f)
        assertEquals(draft.baseline.dp, first.current.dp)
        assertEquals(8675309L, first.provenance.operations.last().seed)
    }

    @Test
    fun undoIsBoundedAndResetReturnsToMixedBaseline() {
        val initial = VoiceProfileMixer.mix(
            listOf(source("first", 1f, 3f), source("second", 2f, 5f)),
            listOf(1.0, 1.0),
        )
        var draft = initial
        repeat(25) {
            draft = VoiceProfileDraftEditor.apply(draft, VoiceProfileTtlOperation.ScalarAdd(0.01))
        }
        val beforeUndo = draft.current

        assertEquals(VoiceProfileLimits.MAX_UNDO_SNAPSHOTS, draft.undoSnapshots.size)
        draft = VoiceProfileDraftEditor.undo(draft)
        assertNotEquals(beforeUndo.ttl, draft.current.ttl)
        assertEquals(initial.baseline.dp, draft.current.dp)

        val reset = VoiceProfileDraftEditor.reset(draft)
        assertEquals(initial.baseline, reset.current)
        assertTrue(reset.undoSnapshots.isEmpty())
        assertFalse(reset.hasLatentEdits)
    }

    @Test
    fun invalidCandidateLeavesDraftAndUndoHistoryUnchanged() {
        val high = Float.MAX_VALUE
        val draft = VoiceProfileMixer.mix(
            listOf(source("first", high, 1f), source("second", high, 1f)),
            listOf(1.0, 1.0),
        )

        assertThrows(IllegalArgumentException::class.java) {
            VoiceProfileDraftEditor.apply(
                draft,
                VoiceProfileTtlOperation.ScalarMultiply(Double.MAX_VALUE),
            )
        }
        assertEquals(draft.baseline, draft.current)
        assertTrue(draft.undoSnapshots.isEmpty())
        assertTrue(draft.provenance.operations.isEmpty())
    }

    @Test
    fun sourceChangeAfterLatentEditRequiresExplicitDiscard() {
        val sources = listOf(source("first", 1f, 3f), source("second", 2f, 5f))
        val initial = VoiceProfileMixer.mix(sources, listOf(1.0, 1.0))
        val edited = VoiceProfileDraftEditor.apply(initial, VoiceProfileTtlOperation.Invert)
        val replacement = VoiceProfileMixer.mix(sources, listOf(3.0, 1.0))

        assertThrows(IllegalArgumentException::class.java) {
            VoiceProfileDraftEditor.replaceBaseline(edited, replacement, discardLatentEdits = false)
        }
        assertEquals(
            replacement,
            VoiceProfileDraftEditor.replaceBaseline(edited, replacement, discardLatentEdits = true),
        )
    }

    private fun source(
        id: String,
        ttl: Float,
        dp: Float,
    ): VoiceProfileMixSource =
        VoiceProfileMixSource(
            summary =
                VoiceProfileSummary(
                    id = VoiceProfileId(id),
                    displayName = id,
                    kind = VoiceProfileKind.EDITED,
                    availability = VoiceProfileAvailability.Available,
                    compatibility = VoiceProfileCompatibility.VERIFIED,
                    readOnly = false,
                ),
            tensors =
                VoiceProfileTensors(
                    ttl = VoiceTensor.copyOf(
                        Supertonic3VoiceProfileContract.TTL_DIMENSIONS,
                        FloatArray(Supertonic3VoiceProfileContract.TTL_ELEMENT_COUNT) { ttl },
                    ),
                    dp = VoiceTensor.copyOf(
                        Supertonic3VoiceProfileContract.DP_DIMENSIONS,
                        FloatArray(Supertonic3VoiceProfileContract.DP_ELEMENT_COUNT) { dp },
                    ),
                ),
        )

    private fun ttlTensor(value: (row: Int, column: Int) -> Float): VoiceTensor =
        VoiceTensor.copyOf(
            Supertonic3VoiceProfileContract.TTL_DIMENSIONS,
            FloatArray(Supertonic3VoiceProfileContract.TTL_ELEMENT_COUNT) { flat ->
                value(flat / 256, flat % 256)
            },
        )

    private fun index(row: Int, column: Int): Int = row * 256 + column
}
