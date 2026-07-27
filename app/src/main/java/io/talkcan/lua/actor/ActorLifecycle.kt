package io.talkcan.lua.actor

/**
 * Actor lifecycle phases bounded by the generation's invocation gate and the
 * registry's entry lifecycle.
 *
 * ```text
 * constructing → staged → starting → live (ready) → retiring → drained → closed
 *                                ↘ failed (failure latch)
 * ```
 *
 * - **Constructing:** the provider constructs the runtime and creates an
 *   independent state; no runtime is ready.
 * - **Staged:** source is loaded and its entrypoint is validated, but a
 *   replacement has not yet received authorization to run startup or effects.
 * - **Starting:** the predecessor is closed and the host has authorized
 *   bounded protected startup.
 * - **Live (ready):** startup reported ready through the host-owned latch; the
 *   actor accepts events and generation-scoped background work.
 * - **Failed (failure latch):** a startup, lifecycle-critical, instruction,
 *   memory, ownership-integrity, or other generation-fatal outcome sets the
 *   terminal host-owned latch. Recovery requires a fresh generation.
 * - **Retiring:** replacement or removal stopped admission; committed leases
 *   are draining.
 * - **Drained:** committed leases released and descendants were cancelled or
 *   joined.
 * - **Closed:** terminal close ran exactly once; late effects are suppressed.
 */
internal enum class ActorLifecyclePhase {
    CONSTRUCTING,
    STAGED,
    STARTING,
    LIVE,
    FAILED,
    RETIRING,
    DRAINED,
    CLOSED,
}

/**
 * Whether the actor phase admits ordinary mailbox events.
 *
 * Before readiness (CONSTRUCTING, STAGED, STARTING) the actor refuses ordinary
 * events with a typed not-ready result and does not queue them. Only LIVE
 * admits ordinary events and generation-scoped background work. After
 * retirement or closure no events are admitted.
 */
internal val ActorLifecyclePhase.admitsEvents: Boolean
    get() = this == ActorLifecyclePhase.LIVE

/**
 * Whether the actor phase is terminal (no further Lua entry).
 */
internal val ActorLifecyclePhase.isTerminal: Boolean
    get() = this == ActorLifecyclePhase.CLOSED || this == ActorLifecyclePhase.FAILED

/**
 * Whether the actor phase permits protected startup entry execution.
 */
internal val ActorLifecyclePhase.permitsStartup: Boolean
    get() = this == ActorLifecyclePhase.STARTING

/**
 * Whether the actor phase permits state creation and source loading/staging
 * without effect authorization.
 */
internal val ActorLifecyclePhase.permitsStaging: Boolean
    get() = this == ActorLifecyclePhase.CONSTRUCTING || this == ActorLifecyclePhase.STAGED