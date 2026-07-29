package io.talkcan.ui

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Zone classification for the VU meter, derived from the displayed (post-perceptual-map) level.
 *
 * Used by [VuMeterEngine.state] and by the [VuMeter] Composable to pick the palette-conformant
 * color for each filled segment. Each segment keeps its zone color so the fill's top indicates
 * the current zone (per `vu-meter` spec D5).
 */
enum class VuZone {
    /** Bottom of the scale: input is too low. Rendered dim/muted (secondary text at low opacity). */
    LOW,

    /** Middle of the scale: healthy level. Rendered in Talkcan signal green. */
    GOOD,

    /** Top of the scale: input is too high / clipping. Rendered in can red. */
    CLIP,
}

/**
 * Snapshot of the VU meter's current presentation, produced by [VuMeterEngine.update].
 *
 * @property displayed Live (post-ballistics) level in the displayed domain, 0..1. This is the value the bar fill tracks; it is already perceptual-mapped (sqrt) and ballistics-smoothed.
 * @property peakHold Peak-hold marker in the displayed domain, 0..1. Holds the recent maximum for the hold window then decays.
 * @property zone Zone the [displayed] level currently falls into. Each filled segment renders in its zone color, so the fill's top indicates the current zone.
 */
data class VuMeterState(
    val displayed: Float,
    val peakHold: Float,
    val zone: VuZone,
)

/**
 * Pure-Kotlin VU meter engine: perceptual mapping + VU ballistics + zone classification.
 *
 * Pure (no Compose, no Android) so it is unit-testable on plain JVM. The [VuMeter] Composable
 * wraps this and drives it from the captured `level` signal.
 *
 * Ballistics (per `vu-meter` spec / design D2):
 * - Attack (rise): tracks input up within [ATTACK_MS] (~30ms).
 * - Release (fall): decays toward input over [RELEASE_MS] (~200ms).
 * - Peak-hold: holds the recent maximum for [PEAK_HOLD_MS] (~800ms) then decays.
 *
 * Perceptual mapping (design D3): `display = sqrt(rms)` so quiet input is visible and loud input
 * does not instantly peg the meter. Zone thresholds ([LOW_THRESHOLD], [CLIP_THRESHOLD]) are defined
 * in this displayed (post-perceptual-map) domain.
 *
 * Threading: not synchronized; intended to be driven from a single thread (the Composable's
 * effect/coroutine). The Composable applies [derivedStateOf] on top so only meaningful deltas
 * recompose.
 */
class VuMeterEngine(
    private val clockMillis: () -> Long,
) {
    private var lastDisplayed: Float = 0f
    private var peakHold: Float = 0f
    private var peakAtMillis: Long = 0L
    private var lastUpdateMillis: Long = 0L

    /**
     * Perceptual curve: maps a raw normalized RMS in 0..1 to the displayed domain in 0..1.
     *
     * `sqrt(gain * rms)` — input gain compensates for the low RMS that real speech produces
     * from the normalized capture signal (typical speaking RMS is 0.05–0.15, so without gain
     * the meter never leaves the low zone). `sqrt` then spreads quiet input visibly and keeps
     * loud input from instantly pegging the meter. Clamped to [0,1] to be robust to
     * out-of-range signals.
     */
    fun perceptualMap(rawLevel: Float): Float {
        val amplified = (rawLevel * INPUT_GAIN).coerceIn(0f, 1f)
        return sqrt(amplified)
    }

    /**
     * Classifies a displayed level into its [VuZone] using [LOW_THRESHOLD] and [CLIP_THRESHOLD].
     */
    fun zoneFor(displayed: Float): VuZone = when {
        displayed >= CLIP_THRESHOLD -> VuZone.CLIP
        displayed <= LOW_THRESHOLD -> VuZone.LOW
        else -> VuZone.GOOD
    }

    /**
     * Feeds a new raw `level` sample and returns the resulting [VuMeterState].
     *
     * @param rawLevel Raw per-chunk RMS in 0..1 from the capture service.
     * @param nowMillis Optional override of the clock (used by tests). Defaults to [clockMillis].
     */
    fun update(rawLevel: Float, nowMillis: Long = clockMillis()): VuMeterState {
        val target = perceptualMap(rawLevel)
        val dt = nowMillis - lastUpdateMillis
        lastUpdateMillis = nowMillis

        // Ballistics: rise on attack, fall on release.
        val displayed = if (lastUpdateMillis == 0L || dt <= 0L) {
            // First sample or non-monotonic clock: snap.
            target
        } else if (target >= lastDisplayed) {
            // Attack: track up within ATTACK_MS.
            val fraction = (dt.toFloat() / ATTACK_MS).coerceIn(0f, 1f)
            lastDisplayed + (target - lastDisplayed) * fraction
        } else {
            // Release: decay toward target over RELEASE_MS.
            val fraction = (dt.toFloat() / RELEASE_MS).coerceIn(0f, 1f)
            lastDisplayed + (target - lastDisplayed) * fraction
        }
        lastDisplayed = displayed

        // Peak-hold: raise immediately, hold PEAK_HOLD_MS, then decay toward the live displayed.
        if (displayed >= peakHold) {
            peakHold = displayed
            peakAtMillis = nowMillis
        } else {
            val holdElapsed = nowMillis - peakAtMillis
            if (holdElapsed > PEAK_HOLD_MS) {
                // Decay the peak marker toward the current displayed value.
                val decayFraction = ((holdElapsed - PEAK_HOLD_MS).toFloat() / PEAK_DECAY_MS)
                    .coerceIn(0f, 1f)
                peakHold = peakHold + (displayed - peakHold) * decayFraction
            }
        }

        return VuMeterState(
            displayed = displayed,
            peakHold = peakHold,
            zone = zoneFor(displayed),
        )
    }

    /**
     * Resets the engine to its idle state (called when capture stops so a future session starts
     * cleanly from zero).
     */
    fun reset() {
        lastDisplayed = 0f
        peakHold = 0f
        peakAtMillis = 0L
        lastUpdateMillis = 0L
    }

    companion object {
        // Displayed-domain zone boundaries (post-perceptual-map). Tunable on device per spec D5.
        const val LOW_THRESHOLD: Float = 0.20f
        const val CLIP_THRESHOLD: Float = 0.88f

        // Ballistics windows (ms). Tunable on device per spec D2.
        const val ATTACK_MS: Long = 30L
        const val RELEASE_MS: Long = 200L
        const val PEAK_HOLD_MS: Long = 800L
        const val PEAK_DECAY_MS: Long = 800L

        /** Segment count for the segmented horizontal bar (design Open Questions: ~20 proposed). */
        const val SEGMENT_COUNT: Int = 20

        /**
         * Input gain applied before the perceptual curve. The capture service emits RMS
         * normalized to 0..1 (divided by `Short.MAX_VALUE`); typical speaking RMS is 0.05–0.15,
         * so without gain `sqrt(rms)` stays ~0.22–0.39 and the meter rarely leaves the low/low-good
         * boundary. A gain of 6× brings normal speech into the GOOD zone and loud speech into CLIP.
         * Tunable on device per spec task 4.3.
         */
        const val INPUT_GAIN: Float = 6f
    }
}