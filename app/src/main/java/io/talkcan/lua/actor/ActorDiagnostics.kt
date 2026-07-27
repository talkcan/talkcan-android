package io.talkcan.lua.actor

import java.util.concurrent.atomic.AtomicLong

/**
 * Internal diagnostics observations feeding host-owned observability.
 *
 * Records instruction counts and latency. Policy values are evidence-derived,
 * not persisted as plugin-facing limits, and do not appear in the public
 * configuration schema. These observations are NOT a public API. No
 * per-state allocator accounting is recorded: the LuaJava binding exposes no
 * per-state allocator hook.
 */
internal class ActorDiagnostics {

    private val instructionCount = AtomicLong(0L)
    private val totalLatencyNanos = AtomicLong(0L)
    private val operationCount = AtomicLong(0L)

    /**
     * Record executed instructions.
     */
    fun recordInstructions(count: Long) {
        instructionCount.addAndGet(count)
    }

    /**
     * Record one operation latency in nanoseconds.
     */
    fun recordLatency(elapsedNanos: Long) {
        totalLatencyNanos.addAndGet(elapsedNanos)
        operationCount.incrementAndGet()
    }

    /**
     * Snapshot diagnostics observations.
     */
    fun snapshot(): ActorDiagnosticsSnapshot = ActorDiagnosticsSnapshot(
        instructionCount = instructionCount.get(),
        totalLatencyNanos = totalLatencyNanos.get(),
        operationCount = operationCount.get(),
    )

    /**
     * Reset diagnostics. Called when a fresh generation is constructed.
     */
    fun reset() {
        instructionCount.set(0L)
        totalLatencyNanos.set(0L)
        operationCount.set(0L)
    }
}

/**
 * Immutable diagnostics snapshot.
 */
internal data class ActorDiagnosticsSnapshot(
    val instructionCount: Long,
    val totalLatencyNanos: Long,
    val operationCount: Long,
)