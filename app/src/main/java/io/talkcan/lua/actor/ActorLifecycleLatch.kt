package io.talkcan.lua.actor

import java.util.concurrent.atomic.AtomicReference

/**
 * Host-owned per-actor lifecycle latch.
 *
 * The host owns the latch and decides when to retire a failed generation and
 * construct a successor. The actor never resets its Lua state silently within
 * the same generation. Transitions are one-way; [phase] is monotonic except
 * that FAILED may be reached from any non-terminal phase.
 *
 * Thread-safe: all transitions are atomic CAS on an [AtomicReference].
 */
internal class ActorLifecycleLatch(initial: ActorLifecyclePhase = ActorLifecyclePhase.CONSTRUCTING) {

    private val state = AtomicReference(initial)

    val phase: ActorLifecyclePhase
        get() = state.get()

    val isLive: Boolean
        get() = state.get() == ActorLifecyclePhase.LIVE

    val isTerminal: Boolean
        get() = state.get().isTerminal

    val isReady: Boolean
        get() = state.get() == ActorLifecyclePhase.LIVE

    val isFailed: Boolean
        get() = state.get() == ActorLifecyclePhase.FAILED

    val isClosed: Boolean
        get() = state.get() == ActorLifecyclePhase.CLOSED

    /**
     * Transition to [target] only if the current phase is [expected].
     * Returns true if the transition succeeded.
     */
    fun transition(expected: ActorLifecyclePhase, target: ActorLifecyclePhase): Boolean =
        state.compareAndSet(expected, target)

    /**
     * Transition to [target] if the current phase is not already terminal.
     * Returns true if the transition succeeded.
     */
    fun transitionIfLive(target: ActorLifecyclePhase): Boolean {
        while (true) {
            val current = state.get()
            if (current.isTerminal) return false
            if (state.compareAndSet(current, target)) return true
        }
    }

    /**
     * Latch the actor as failed. Once failed, no further Lua entry is
     * permitted. Returns true if this call set the latch; false if the actor
     * was already terminal.
     */
    fun latchFailed(): Boolean = transitionIfLive(ActorLifecyclePhase.FAILED)

    /**
     * Transition to retiring (admission stopped, committed leases draining).
     */
    fun retire(): Boolean = transitionIfLive(ActorLifecyclePhase.RETIRING)

    /**
     * Transition to drained (committed leases released, descendants joined).
     */
    fun drain(): Boolean =
        state.compareAndSet(ActorLifecyclePhase.RETIRING, ActorLifecyclePhase.DRAINED)

    /**
     * Transition to closed from any non-CLOSED phase. One-way; returns true if
     * this call transitioned the latch, false if already CLOSED.
     */
    fun close(): Boolean {
        while (true) {
            val current = state.get()
            if (current == ActorLifecyclePhase.CLOSED) return false
            if (state.compareAndSet(current, ActorLifecyclePhase.CLOSED)) return true
        }
    }

    /**
     * Transition to staged (source loaded, entrypoint validated).
     */
    fun stage(): Boolean =
        state.compareAndSet(ActorLifecyclePhase.CONSTRUCTING, ActorLifecyclePhase.STAGED)

    /**
     * Transition to starting (predecessor closed, host authorized startup).
     */
    fun start(): Boolean =
        state.compareAndSet(ActorLifecyclePhase.STAGED, ActorLifecyclePhase.STARTING)

    /**
     * Transition to live (startup reported ready).
     */
    fun publishReady(): Boolean =
        state.compareAndSet(ActorLifecyclePhase.STARTING, ActorLifecyclePhase.LIVE)
}