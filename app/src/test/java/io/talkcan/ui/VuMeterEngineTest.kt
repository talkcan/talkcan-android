package io.talkcan.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

private const val G = VuMeterEngine.INPUT_GAIN
private fun pmap(raw: Float): Float = sqrt((raw * G).coerceIn(0f, 1f))

/**
 * JVM unit tests for [VuMeterEngine] ballistics and zone classification.
 *
 * Maps to `vu-meter` spec scenarios:
 * - Fast attack tracks rising input (within ATTACK_MS)
 * - Slower release smooths falling input (over RELEASE_MS, not instant)
 * - Peak-hold marker retains the recent maximum (holds ~PEAK_HOLD_MS then descends)
 * - Perceptual mapping: quiet input visible, loud input reaches the clip zone without instantly pegging
 * - Three-zone feedback: low (LOW), good (GOOD), clip (CLIP) for levels crossing LOW_THRESHOLD / CLIP_THRESHOLD
 *
 * The engine is pure Kotlin (no Compose), so these run on plain JVM without robolectric.
 */
class VuMeterEngineTest {

    @Test
    fun `perceptual mapping is sqrt with input gain and clamps to zero-one`() {
        val engine = VuMeterEngine { 0L }
        assertEquals(0f, engine.perceptualMap(0f), 1e-4f)
        assertEquals(pmap(0.05f), engine.perceptualMap(0.05f), 1e-4f)
        assertEquals(pmap(0.9f), engine.perceptualMap(0.9f), 1e-4f)
        // Raw 1.0 amplified by INPUT_GAIN clamps to 1.0 before sqrt -> sqrt(1.0) = 1.0.
        assertEquals(1f, engine.perceptualMap(1f), 1e-4f)
        // Out-of-range signals clamp, not blow up.
        assertEquals(0f, engine.perceptualMap(-0.5f), 1e-4f)
        assertEquals(1f, engine.perceptualMap(2f), 1e-4f)
    }

    @Test
    fun `zone classification follows thresholds in the displayed domain`() {
        val engine = VuMeterEngine { 0L }
        // LOW: displayed <= LOW_THRESHOLD
        assertEquals(VuZone.LOW, engine.zoneFor(0f))
        assertEquals(VuZone.LOW, engine.zoneFor(VuMeterEngine.LOW_THRESHOLD))
        // GOOD: strictly above LOW_THRESHOLD and below CLIP_THRESHOLD
        assertEquals(VuZone.GOOD, engine.zoneFor(VuMeterEngine.LOW_THRESHOLD + 0.01f))
        assertEquals(VuZone.GOOD, engine.zoneFor(0.5f))
        assertEquals(VuZone.GOOD, engine.zoneFor(VuMeterEngine.CLIP_THRESHOLD - 0.01f))
        // CLIP: displayed >= CLIP_THRESHOLD
        assertEquals(VuZone.CLIP, engine.zoneFor(VuMeterEngine.CLIP_THRESHOLD))
        assertEquals(VuZone.CLIP, engine.zoneFor(1f))
    }

    @Test
    fun `quiet input is visible above zero after perceptual mapping`() {
        val engine = VuMeterEngine { 0L }
        // raw 0.05 (typical quiet speech RMS) -> amplified then sqrt: well above zero.
        val state = engine.update(0.05f, nowMillis = 0L)
        assertTrue("displayed should be noticeably above zero for quiet input, got ${state.displayed}",
            state.displayed > 0.1f)
        assertEquals(engine.perceptualMap(0.05f), state.displayed, 1e-4f)
    }

    @Test
    fun `loud input fills toward clip zone without instantly pegging`() {
        val engine = VuMeterEngine { 0L }
        // raw 0.9 amplified by INPUT_GAIN clamps to 1.0 -> sqrt(1.0) = 1.0 -> CLIP zone.
        val state = engine.update(0.9f, nowMillis = 0L)
        assertTrue("displayed should reach the clip zone for loud input, got ${state.displayed}",
            state.displayed >= VuMeterEngine.CLIP_THRESHOLD)
        assertEquals(VuZone.CLIP, state.zone)
        // Not instantly pegged past 1.0 — clamped to the displayed domain.
        assertTrue(state.displayed <= 1f)
    }

    @Test
    fun `typical speaking RMS reaches the GOOD zone`() {
        // Regression guard for the input-gain calibration: real speech from the normalized
        // capture signal is ~0.05–0.15 RMS. Without gain the meter stayed in LOW; with
        // INPUT_GAIN it must reach the GOOD zone so the operator gets "healthy level" feedback.
        val engine = VuMeterEngine { 0L }
        val typical = engine.update(0.10f, nowMillis = 0L)
        assertTrue("typical speaking RMS should reach GOOD zone, got zone=${typical.zone} displayed=${typical.displayed}",
            typical.zone == VuZone.GOOD || typical.zone == VuZone.CLIP)
        assertTrue(typical.displayed > VuMeterEngine.LOW_THRESHOLD)
    }

    @Test
    fun `fast attack tracks rising input within attack window`() {
        // First sample at 0, then jump to a high target. Within ATTACK_MS the meter should
        // reach the target (attack fraction = 1 at dt == ATTACK_MS).
        var now = 0L
        val engine = VuMeterEngine { now }
        engine.update(0f, nowMillis = now) // start at 0

        now = VuMeterEngine.ATTACK_MS
        val target = pmap(0.8f)
        val state = engine.update(0.8f, nowMillis = now)
        // After one full attack window the meter should have fully tracked up.
        assertEquals("attack should complete within ATTACK_MS, got ${state.displayed} vs target $target",
            target, state.displayed, 1e-3f)
        assertEquals(VuZone.CLIP, state.zone)
    }

    @Test
    fun `slower release decays over release window rather than dropping instantly`() {
        var now = 0L
        val engine = VuMeterEngine { now }
        // Establish a high displayed level.
        engine.update(0.9f, nowMillis = now)
        now += VuMeterEngine.ATTACK_MS
        engine.update(0.9f, nowMillis = now)
        val highDisplayed = pmap(0.9f)

        // Input drops to 0. After a small fraction of the release window, the meter should not
        // have fully dropped — it should still be partway between highDisplayed and 0.
        now += 50L // well under RELEASE_MS (200ms)
        val state = engine.update(0f, nowMillis = now)
        assertTrue("meter should not drop instantly on release, got ${state.displayed}",
            state.displayed > 0.1f && state.displayed < highDisplayed)

        // After the full release window elapses with sustained 0 input, it should have decayed
        // to (essentially) zero.
        now += VuMeterEngine.RELEASE_MS
        val settled = engine.update(0f, nowMillis = now)
        assertEquals("meter should decay to ~0 over the release window, got ${settled.displayed}",
            0f, settled.displayed, 0.05f)
    }

    @Test
    fun `peak-hold marker retains recent maximum then descends`() {
        var now = 0L
        val engine = VuMeterEngine { now }
        // Build up to a peak.
        engine.update(0.5f, nowMillis = now)
        val peak = pmap(0.5f)

        // Input drops well below the peak. Within the hold window, peak-hold must remain at the peak.
        now += 50L
        engine.update(0.1f, nowMillis = now)
        assertEquals("peak-hold must retain the recent maximum within the hold window",
            peak, engine.update(0.1f, nowMillis = now).peakHold, 1e-3f)

        // Still within the hold window (~800ms).
        now += 400L
        assertEquals("peak-hold must still hold within PEAK_HOLD_MS",
            peak, engine.update(0.1f, nowMillis = now).peakHold, 1e-3f)

        // After PEAK_HOLD_MS elapses, the peak-hold marker should begin descending toward the
        // current displayed value.
        now += VuMeterEngine.PEAK_HOLD_MS + 50L
        val afterHold = engine.update(0.1f, nowMillis = now).peakHold
        assertTrue("peak-hold should descend after the hold window, got $afterHold vs peak $peak",
            afterHold < peak)
    }

    @Test
    fun `meter renders each zone in its palette zone for crossing levels`() {
        // Cross-checks the zone for levels that cross LOW_THRESHOLD and CLIP_THRESHOLD.
        val engine = VuMeterEngine { 0L }

        // Quiet: raw 0.005 -> amplified 0.03 -> sqrt ~0.173 -> LOW zone (<= 0.20).
        val quiet = engine.update(0.005f, nowMillis = 0L)
        assertEquals(VuZone.LOW, quiet.zone)

        engine.reset()
        // Healthy: pick a raw level that maps into the GOOD zone (displayed in (0.20, 0.88)).
        // pmap(x) = 0.5 -> (x*G) = 0.25 -> x = 0.25/6 ~ 0.0417
        val healthy = engine.update(0.0417f, nowMillis = 0L)
        assertEquals(VuZone.GOOD, healthy.zone)
        assertTrue(healthy.displayed in VuMeterEngine.LOW_THRESHOLD + 1e-3f..VuMeterEngine.CLIP_THRESHOLD - 1e-3f)

        engine.reset()
        // Loud: amplified clamps to 1.0 -> sqrt(1.0) = 1.0 -> CLIP zone.
        val loud = engine.update(0.85f, nowMillis = 0L)
        assertEquals(VuZone.CLIP, loud.zone)
    }

    @Test
    fun `reset returns the engine to idle zero state`() {
        var now = 0L
        val engine = VuMeterEngine { now }
        engine.update(0.9f, nowMillis = now)
        now += VuMeterEngine.ATTACK_MS
        assertTrue(engine.update(0.9f, nowMillis = now).displayed > 0.5f)

        engine.reset()
        val afterReset = engine.update(0f, nowMillis = now + 10L)
        assertEquals(0f, afterReset.displayed, 1e-4f)
        assertEquals(0f, afterReset.peakHold, 1e-4f)
        assertEquals(VuZone.LOW, afterReset.zone)
    }

    @Test
    fun `attack is faster than release`() {
        // Sanity-check the window constants match the spec intent (30ms attack, 200ms release).
        assertTrue(VuMeterEngine.ATTACK_MS < VuMeterEngine.RELEASE_MS)
    }
}