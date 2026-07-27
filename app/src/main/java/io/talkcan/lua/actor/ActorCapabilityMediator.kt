package io.talkcan.lua.actor

import io.talkcan.channel.capability.CapabilityAcquisition
import io.talkcan.channel.capability.CapabilityKey
import io.talkcan.channel.capability.CapabilityOperationResult
import io.talkcan.channel.capability.ChannelCapabilityPort
import io.talkcan.channel.capability.RevocableChannelCapabilityScope

/**
 * Capability mediation seam between the Lua actor and the host's
 * [RevocableChannelCapabilityScope].
 *
 * When Lua needs a host effect it yields an opaque operation token; the host
 * resolves it through the existing [RevocableChannelCapabilityScope]; the
 * completion is delivered back as an actor continuation. The actor does not
 * reimplement, wrap, or bypass host capabilities.
 *
 * Any blocking runtime or host operation yields before execution. Synchronous
 * native host functions are permitted only for deliberately tiny bounded
 * fixed-size work; filesystem, network, model, Android, or other external work
 * always yields an opaque operation token before execution and the Lua entry
 * thread is never blocked.
 *
 * The actor does not expose platform/Kotlin job/SDK objects to Lua. Capability
 * completions cross the opaque bridge boundary as normalized outcomes and
 * opaque handles.
 *
 * Generation-bound capability leases are revoked before the predecessor
 * closes during replacement. A late completion bearing the predecessor's
 * generation is classified as stale without entering the successor's Lua
 * state. Use-after-revocation returns Closed/Cancelled without effect.
 */
internal class ActorCapabilityMediator(
    private val capabilityScope: RevocableChannelCapabilityScope,
) {

    /**
     * Whether the underlying capability scope has been revoked or closed.
     */
    val isClosed: Boolean
        get() = capabilityScope.isClosed

    /**
     * Acquire a capability lease through the revocable scope. The actor
     * yields before this call; the host resolves the acquisition. The
     * completion is delivered back as an actor continuation.
     */
    suspend fun <T : ChannelCapabilityPort> acquire(
        key: CapabilityKey<T>,
    ): CapabilityAcquisition<T> {
        return capabilityScope.acquire(key)
    }

    /**
     * Execute one capability operation through a revocable lease. The
     * operation result is delivered back as a normalized outcome. If the
     * scope is revoked during the operation, the result is Closed or
     * Cancelled without effect.
     */
    suspend fun <T : ChannelCapabilityPort, R> useCapability(
        key: CapabilityKey<T>,
        operation: suspend (T) -> CapabilityOperationResult<R>,
    ): CapabilityOperationResult<R> {
        val acquisition = capabilityScope.acquire(key)
        return when (acquisition) {
            is CapabilityAcquisition.Available -> {
                acquisition.lease.use(operation)
            }
            is CapabilityAcquisition.Closed -> CapabilityOperationResult.Closed
            is CapabilityAcquisition.Cancelled -> CapabilityOperationResult.Cancelled
            is CapabilityAcquisition.Recoverable -> CapabilityOperationResult.Unavailable(acquisition.reason)
            is CapabilityAcquisition.Unavailable -> CapabilityOperationResult.Unavailable(acquisition.reason)
            is CapabilityAcquisition.Denied -> CapabilityOperationResult.Denied(acquisition.reason)
            is CapabilityAcquisition.Failed -> CapabilityOperationResult.Failed(acquisition.reason)
        }
    }

    /**
     * Revoke all generation-bound capability leases. Called during
     * retirement or close before the predecessor closes. A late completion
     * after revocation returns Closed/Cancelled without effect.
     */
    suspend fun revoke() {
        capabilityScope.revoke()
    }
}