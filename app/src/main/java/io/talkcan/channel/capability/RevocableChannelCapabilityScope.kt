package io.talkcan.channel.capability

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException

/**
 * Host-owned capability scope for one channel instance and one runtime generation.
 * The host creates a fresh scope for every generation; no global capability lookup is
 * available to providers or runtimes.
 *
 * For staged successor semantics, [initiallyAuthorized] may be false, preventing
 * capability acquisition until [authorize] is called. This allows a successor scope
 * to be constructed and validated effect-free while a predecessor drains, then
 * authorized only after the predecessor has fully closed.
 */
class RevocableChannelCapabilityScope(
    override val identity: CapabilityScopeIdentity,
    declaredCapabilities: Set<ChannelCapability>,
    private val host: ChannelCapabilityHost,
    private val diagnostics: CapabilityDiagnosticSink = CapabilityDiagnosticSink { },
    initiallyAuthorized: Boolean = true,
) : ChannelCapabilityScope {
    override val declaredCapabilities: Set<ChannelCapability> = declaredCapabilities.toSet()
    private val state = AtomicReference(
        if (initiallyAuthorized) ScopeState.OPEN else ScopeState.PENDING
    )
    private val leases = ConcurrentHashMap.newKeySet<ManagedLease<*>>()

    override val isClosed: Boolean
        get() = state.get() === ScopeState.REVOKED

    /** Returns true if this scope is authorized for capability acquisition. */
    val isAuthorized: Boolean
        get() = state.get() === ScopeState.OPEN

    /**
     * One-way authorization with monotonic linearization. Transitions this scope
     * from [ScopeState.PENDING] to [ScopeState.OPEN]; atomically refuses after
     * [revoke] has linearized the scope to [ScopeState.REVOKED].
     *
     * Linearization point: the successful CAS of [state] from PENDING to OPEN.
     * Returns true iff this call performed that transition.
     * Returns false if already authorized (OPEN) or already revoked (REVOKED);
     * in particular, revoke linearizes before authorize can observe REVOKED,
     * so authorize never returns true once revoke is concurrent or completed.
     */
    fun authorize(): Boolean = state.compareAndSet(ScopeState.PENDING, ScopeState.OPEN)

    override suspend fun availability(key: CapabilityKey<*>): CapabilityAvailabilityResult {
        if (!isDeclared(key)) {
            record(key.capability, CapabilityDiagnosticPhase.AVAILABILITY, CapabilityDiagnosticOutcome.DENIED)
            return CapabilityAvailabilityResult.Denied(CapabilityDeniedReason.UNDECLARED)
        }
        if (state.get() !== ScopeState.OPEN) {
            record(key.capability, CapabilityDiagnosticPhase.AVAILABILITY, CapabilityDiagnosticOutcome.CLOSED)
            return CapabilityAvailabilityResult.Closed
        }
        return try {
            val availability = host.availability(identity, key)
            record(key.capability, CapabilityDiagnosticPhase.AVAILABILITY, availability.outcome())
            CapabilityAvailabilityResult.State(availability)
        } catch (_: CancellationException) {
            record(key.capability, CapabilityDiagnosticPhase.AVAILABILITY, CapabilityDiagnosticOutcome.CANCELLED)
            CapabilityAvailabilityResult.Cancelled
        } catch (_: Exception) {
            record(key.capability, CapabilityDiagnosticPhase.AVAILABILITY, CapabilityDiagnosticOutcome.FAILED)
            CapabilityAvailabilityResult.Failed(CapabilityFailureReason.HOST_FAILURE)
        }
    }

    override suspend fun <T : ChannelCapabilityPort> acquire(
        key: CapabilityKey<T>,
        policy: CapabilityAcquisitionPolicy,
    ): CapabilityAcquisition<T> {
        if (!isDeclared(key)) {
            record(key.capability, CapabilityDiagnosticPhase.ACQUISITION, CapabilityDiagnosticOutcome.DENIED)
            return CapabilityAcquisition.Denied(CapabilityDeniedReason.UNDECLARED)
        }
        if (state.get() !== ScopeState.OPEN) {
            record(key.capability, CapabilityDiagnosticPhase.ACQUISITION, CapabilityDiagnosticOutcome.CLOSED)
            return CapabilityAcquisition.Closed
        }

        val initial = acquireFromHost(key, preparationTimeoutMillis = null)
        if (initial !is HostedCapabilityAcquisition.Recoverable) {
            return bind(key, initial, CapabilityDiagnosticPhase.ACQUISITION)
        }

        record(key.capability, CapabilityDiagnosticPhase.ACQUISITION, CapabilityDiagnosticOutcome.RECOVERABLE)
        val preparation = policy as? CapabilityAcquisitionPolicy.PrepareRecoverable
            ?: return CapabilityAcquisition.Recoverable(initial.reason)
        if (state.get() !== ScopeState.OPEN) {
            record(key.capability, CapabilityDiagnosticPhase.PREPARATION, CapabilityDiagnosticOutcome.CLOSED)
            return CapabilityAcquisition.Closed
        }

        return bind(
            key,
            acquireFromHost(key, preparation.timeoutMillis),
            CapabilityDiagnosticPhase.PREPARATION,
        )
    }

    /**
     * Invalidates every lease in this generation. The first caller owns each lease's
     * cleanup; concurrent retirement, timeout, and shutdown callers only observe the
     * already-terminal state.
     */
    suspend fun revoke(): CapabilityScopeTerminationResult {
        // A single exchange linearizes either PENDING→REVOKED or OPEN→REVOKED.
        // Using separate CAS attempts would allow authorize() to move PENDING→OPEN
        // between them, causing revoke() to miss both states and leave the scope open.
        if (state.getAndSet(ScopeState.REVOKED) === ScopeState.REVOKED) {
            return CapabilityScopeTerminationResult.AlreadyClosed
        }
        // Notify host-owned resources even when no lease is currently retained. Deferred
        // playback entries outlive the short capability lease used for scheduling, so lease
        // cleanup alone cannot revoke those queued entries. The host callback is idempotent and
        // runs after the scope's terminal state is visible to concurrent callers.
        runCatching {
            host.onGenerationTermination(identity, CapabilityLeaseTermination.REVOKED)
        }

        var cleanupFailed = false
        for (lease in leases.toList()) {
            if (lease.revoke() is CapabilityReleaseResult.CleanupFailed) cleanupFailed = true
        }
        return if (cleanupFailed) {
            CapabilityScopeTerminationResult.CleanupFailed(CapabilityFailureReason.CLEANUP_FAILED)
        } else {
            CapabilityScopeTerminationResult.Revoked
        }
    }

    private suspend fun <T : ChannelCapabilityPort> acquireFromHost(
        key: CapabilityKey<T>,
        preparationTimeoutMillis: Long?,
    ): HostedCapabilityAcquisition<T> {
        return try {
            if (preparationTimeoutMillis == null) {
                host.acquire(identity, key)
            } else {
                host.prepareAndAcquire(identity, key, preparationTimeoutMillis)
            }
        } catch (_: CancellationException) {
            HostedCapabilityAcquisition.Cancelled
        } catch (error: Exception) {
            android.util.Log.e(
                "TalkcanCapability",
                "acquireFromHost threw for ${key.capability}: ${error::class.simpleName}: ${error.message}",
                error,
            )
            HostedCapabilityAcquisition.Failed(CapabilityFailureReason.HOST_FAILURE)
        }
    }

    private suspend fun <T : ChannelCapabilityPort> bind(
        key: CapabilityKey<T>,
        hosted: HostedCapabilityAcquisition<T>,
        phase: CapabilityDiagnosticPhase,
    ): CapabilityAcquisition<T> {
        return when (hosted) {
            is HostedCapabilityAcquisition.Available -> {
                val lease = ManagedLease(key.capability, hosted.port, hosted.cleanup)
                if (state.get() === ScopeState.REVOKED) {
                    lease.revoke()
                    record(key.capability, phase, CapabilityDiagnosticOutcome.CLOSED)
                    CapabilityAcquisition.Closed
                } else {
                    leases += lease
                    if (state.get() === ScopeState.REVOKED) {
                        lease.revoke()
                        record(key.capability, phase, CapabilityDiagnosticOutcome.CLOSED)
                        CapabilityAcquisition.Closed
                    } else {
                        record(key.capability, phase, CapabilityDiagnosticOutcome.AVAILABLE)
                        CapabilityAcquisition.Available(lease)
                    }
                }
            }
            is HostedCapabilityAcquisition.Recoverable -> {
                record(key.capability, phase, CapabilityDiagnosticOutcome.RECOVERABLE)
                CapabilityAcquisition.Recoverable(hosted.reason)
            }
            is HostedCapabilityAcquisition.Unavailable -> {
                record(key.capability, phase, CapabilityDiagnosticOutcome.UNAVAILABLE)
                CapabilityAcquisition.Unavailable(hosted.reason)
            }
            HostedCapabilityAcquisition.Cancelled -> {
                record(key.capability, phase, CapabilityDiagnosticOutcome.CANCELLED)
                CapabilityAcquisition.Cancelled
            }
            is HostedCapabilityAcquisition.Failed -> {
                record(key.capability, phase, CapabilityDiagnosticOutcome.FAILED)
                CapabilityAcquisition.Failed(hosted.reason)
            }
        }
    }

    private fun isDeclared(key: CapabilityKey<*>): Boolean = key.capability in declaredCapabilities

    private fun record(
        capability: ChannelCapability,
        phase: CapabilityDiagnosticPhase,
        outcome: CapabilityDiagnosticOutcome,
    ) {
        // Diagnostics must never change capability behavior or expose an exception message.
        runCatching { diagnostics.record(CapabilityDiagnostic(identity, capability, phase, outcome)) }
    }

    private fun CapabilityAvailability.outcome(): CapabilityDiagnosticOutcome = when (this) {
        CapabilityAvailability.Available -> CapabilityDiagnosticOutcome.AVAILABLE
        CapabilityAvailability.Recoverable -> CapabilityDiagnosticOutcome.RECOVERABLE
        is CapabilityAvailability.Unavailable -> CapabilityDiagnosticOutcome.UNAVAILABLE
    }

    private inner class ManagedLease<T : ChannelCapabilityPort>(
        override val capability: ChannelCapability,
        private val port: T,
        private val cleanup: suspend (CapabilityLeaseTermination) -> Unit,
    ) : CapabilityLease<T> {
        private val leaseState = AtomicReference(CapabilityLeaseState.ACTIVE)

        override val identity: CapabilityScopeIdentity
            get() = this@RevocableChannelCapabilityScope.identity

        override val state: CapabilityLeaseState
            get() = leaseState.get()

        override suspend fun <R> use(
            operation: suspend (T) -> CapabilityOperationResult<R>,
        ): CapabilityOperationResult<R> {
            if (leaseState.get() != CapabilityLeaseState.ACTIVE || this@RevocableChannelCapabilityScope.state.get() === ScopeState.REVOKED) {
                record(capability, CapabilityDiagnosticPhase.OPERATION, CapabilityDiagnosticOutcome.CLOSED)
                return CapabilityOperationResult.Closed
            }
            return try {
                val result = operation(port)
                if (leaseState.get() != CapabilityLeaseState.ACTIVE || this@RevocableChannelCapabilityScope.state.get() === ScopeState.REVOKED) {
                    record(capability, CapabilityDiagnosticPhase.OPERATION, CapabilityDiagnosticOutcome.CLOSED)
                    CapabilityOperationResult.Closed
                } else {
                    record(capability, CapabilityDiagnosticPhase.OPERATION, result.outcome())
                    result
                }
            } catch (_: CancellationException) {
                record(capability, CapabilityDiagnosticPhase.OPERATION, CapabilityDiagnosticOutcome.CANCELLED)
                CapabilityOperationResult.Cancelled
            } catch (_: Exception) {
                record(capability, CapabilityDiagnosticPhase.OPERATION, CapabilityDiagnosticOutcome.FAILED)
                CapabilityOperationResult.Failed(CapabilityFailureReason.HOST_FAILURE)
            }
        }

        override suspend fun release(): CapabilityReleaseResult = terminate(
            expected = CapabilityLeaseState.ACTIVE,
            terminal = CapabilityLeaseState.RELEASED,
            termination = CapabilityLeaseTermination.RELEASED,
            phase = CapabilityDiagnosticPhase.RELEASE,
        )

        suspend fun revoke(): CapabilityReleaseResult = terminate(
            expected = CapabilityLeaseState.ACTIVE,
            terminal = CapabilityLeaseState.REVOKED,
            termination = CapabilityLeaseTermination.REVOKED,
            phase = CapabilityDiagnosticPhase.REVOCATION,
        )

        private suspend fun terminate(
            expected: CapabilityLeaseState,
            terminal: CapabilityLeaseState,
            termination: CapabilityLeaseTermination,
            phase: CapabilityDiagnosticPhase,
        ): CapabilityReleaseResult {
            if (!leaseState.compareAndSet(expected, terminal)) return CapabilityReleaseResult.AlreadyTerminated
            leases -= this
            return try {
                cleanup(termination)
                record(capability, phase, CapabilityDiagnosticOutcome.RELEASED)
                CapabilityReleaseResult.Released
            } catch (_: CancellationException) {
                record(capability, CapabilityDiagnosticPhase.CLEANUP, CapabilityDiagnosticOutcome.CANCELLED)
                CapabilityReleaseResult.CleanupFailed(CapabilityFailureReason.CLEANUP_FAILED)
            } catch (_: Exception) {
                record(capability, CapabilityDiagnosticPhase.CLEANUP, CapabilityDiagnosticOutcome.FAILED)
                CapabilityReleaseResult.CleanupFailed(CapabilityFailureReason.CLEANUP_FAILED)
            }
        }

        private fun <R> CapabilityOperationResult<R>.outcome(): CapabilityDiagnosticOutcome = when (this) {
            is CapabilityOperationResult.Success -> CapabilityDiagnosticOutcome.AVAILABLE
            is CapabilityOperationResult.Unavailable -> CapabilityDiagnosticOutcome.UNAVAILABLE
            is CapabilityOperationResult.Denied -> CapabilityDiagnosticOutcome.DENIED
            CapabilityOperationResult.Closed -> CapabilityDiagnosticOutcome.CLOSED
            CapabilityOperationResult.Cancelled -> CapabilityDiagnosticOutcome.CANCELLED
            is CapabilityOperationResult.Failed -> CapabilityDiagnosticOutcome.FAILED
        }
    }
}

sealed interface CapabilityScopeTerminationResult {
    data object Revoked : CapabilityScopeTerminationResult
    data object AlreadyClosed : CapabilityScopeTerminationResult
    data class CleanupFailed(val reason: CapabilityFailureReason) : CapabilityScopeTerminationResult
}

/**
 * Monotonic authorization state for [RevocableChannelCapabilityScope].
 * Transitions are one-way: PENDING → OPEN, PENDING → REVOKED, OPEN → REVOKED.
 * REVOKED is terminal; no transition leaves it. All transitions are performed by a
 * single atomic CAS on [RevocableChannelCapabilityScope.state], so authorization
 * and revocation share exactly one linearization point rather than two independent
 * booleans that can interleave.
 */
private enum class ScopeState { PENDING, OPEN, REVOKED }
