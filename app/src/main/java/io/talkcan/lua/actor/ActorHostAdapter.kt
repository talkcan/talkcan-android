package io.talkcan.lua.actor

/**
 * Generation gate interface injected into the actor by the host.
 *
 * The actor admits host events through this injected gate, returns
 * immediately on yield, resumes continuations through the actor scheduler,
 * and mediates yielded host operations through existing
 * [io.talkcan.channel.capability.RevocableChannelCapabilityScope]
 * lease use without exposing platform/Kotlin job/SDK objects to Lua.
 *
 * The gate interface deliberately exposes only generation-liveness checks and
 * continuation admission — NOT a new host-admitted callback path. A
 * continuation runs under the generation's live-state gate but does not
 * re-enter the host invocation FIFO.
 */
internal interface ActorGenerationGate {

    /**
     * Whether the owning generation is still live. Continuations and commits
     * check this before entering Lua or publishing an effect.
     */
    fun isLive(): Boolean

    /**
     * Atomically gate a host-owned publication/effect at the generation
     * boundary. The action must be brief and host-owned; it must not call
     * provider code or suspend.
     */
    fun <T> commitIfLive(action: () -> T): ActorGateCommit<T>

    /**
     * Run one continuation under the generation's live-state gate, bypassing
     * the admission FIFO queue. The continuation runs on the worker
     * dispatcher under the per-state serialization lock, NOT as a new
     * host-admitted callback. Returns [ActorGateResult.Closed] if the
     * generation is not live.
     */
    suspend fun <T> runContinuation(action: suspend () -> T): ActorGateResult<T>

    /**
     * Whether admission of new host callbacks has been stopped.
     */
    fun isAdmissionStopped(): Boolean
}

/**
 * Result of a [ActorGenerationGate.commitIfLive] call.
 */
internal sealed class ActorGateCommit<out T> {
    data class Success<T>(val value: T) : ActorGateCommit<T>()
    data object Closed : ActorGateCommit<Nothing>()
}

/**
 * Result of a [ActorGenerationGate.runContinuation] call.
 */
internal sealed class ActorGateResult<out T> {
    data class Success<T>(val value: T) : ActorGateResult<T>()
    data object Closed : ActorGateResult<Nothing>()
    data object Cancelled : ActorGateResult<Nothing>()
    data class TimedOut(val deadlineMillis: Long) : ActorGateResult<Nothing>()

    /**
     * The owning operation was already completed by a prior terminal: no
     * second continuation slice ran. Types the duplicate-completion outcome
     * without a false success.
     */
    data object AlreadyCompleted : ActorGateResult<Nothing>()
}