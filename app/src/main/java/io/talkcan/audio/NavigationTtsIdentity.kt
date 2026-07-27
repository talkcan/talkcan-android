package io.talkcan.audio

import kotlinx.coroutines.CompletableDeferred
import java.io.File
import java.util.concurrent.atomic.AtomicLong

// ---------------------------------------------------------------------------
// Engine epoch and utterance identity
// ---------------------------------------------------------------------------

/**
 * A monotonically increasing identifier for a `TextToSpeech` instance epoch.
 * Incremented each time a new instance is constructed (bootstrap, recovery).
 * Callbacks from an old-epoch (shut-down) instance are rejected.
 */
@JvmInline
internal value class EngineEpoch private constructor(val value: Long) {
    companion object {
        private val counter = AtomicLong(0L)
        fun next(): EngineEpoch = EngineEpoch(counter.incrementAndGet())
        val initial: EngineEpoch get() = EngineEpoch(0L)
    }
}

/**
 * A monotonically increasing navigation generation id. Each new navigation
 * request advances this. A callback whose generation no longer matches the
 * current authoritative [NavigationTtsEngine.navigationGeneration] is
 * rejected (stale navigation callback rejection).
 */
@JvmInline
internal value class NavigationGeneration private constructor(val value: Long) {
    companion object {
        private val counter = AtomicLong(0L)
        fun fresh(): NavigationGeneration = NavigationGeneration(counter.incrementAndGet())
    }
}

/**
 * Unique utterance id encoding a monotonically increasing generation number.
 * Each `synthesizeToFile` call gets a unique utterance id.
 */
@JvmInline
internal value class UtteranceId(val value: String) {
    companion object {
        private val counter = AtomicLong(0L)

        /**
         * Create a unique utterance id for a navigation synthesis.
         * Encodes the engine epoch and navigation generation for stale-callback
         * rejection (D4 two-dimension ownership model).
         */
        fun forNavigation(epoch: EngineEpoch, gen: NavigationGeneration): UtteranceId {
            val n = counter.incrementAndGet()
            return UtteranceId("nav-${epoch.value}-${gen.value}-$n")
        }

        /**
         * Create a unique utterance id for a probe (bootstrap or recovery).
         * Tagged with the epoch and an attempt-specific counter, not with
         * any navigation generation — probe callbacks are independent of
         * `navigationGeneration` (D4 dimension 2).
         */
        fun forProbe(epoch: EngineEpoch, attempt: Long): UtteranceId {
            val n = counter.incrementAndGet()
            return UtteranceId("probe-${epoch.value}-$attempt-$n")
        }
    }
}

internal enum class CallbackTerminal {
    Done,
    Error,
    Rejected,
}

/**
 * Tracks a pending synthesis operation for callback routing:
 * - [epoch] must match the current live instance epoch (dimension 1).
 * - [generation] is non-null for navigation synthesis (gated by
 *   `navigationGeneration`, dimension 2); null for probes (delivered
 *   regardless of `navigationGeneration`).
 * - [file] is the transient cache file, deleted on any terminal path.
 * - [result] distinguishes successful completion, engine error, and rejection.
 */
internal data class PendingOperation(
    val epoch: EngineEpoch,
    val utteranceId: UtteranceId,
    val generation: NavigationGeneration?,
    val file: File,
    val result: CompletableDeferred<CallbackTerminal>,
)

/**
 * Identifies a bootstrap or recovery attempt. Probe callbacks are owned by
 * their attempt token, independent of [NavigationTtsEngine.navigationGeneration].
 * Service teardown / bootstrap-attempt discard invalidates the current attempt,
 * causing all pending callbacks for that token to be rejected.
 */
@JvmInline
internal value class AttemptToken private constructor(val value: Long) {
    companion object {
        private val counter = AtomicLong(0L)
        fun fresh(): AttemptToken = AttemptToken(counter.incrementAndGet())
    }
}
