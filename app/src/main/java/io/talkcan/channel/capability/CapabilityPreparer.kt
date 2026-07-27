package io.talkcan.channel.capability

import io.talkcan.dependency.PackageCapability

/**
 * 4.1: Generic mapping from a public package capability identifier to the host
 * capability key that owns its preparation.
 *
 * The mapping is keyed strictly by public capability ID. It contains no
 * package-name, repository, label, or implementation-ID branches: any package
 * that declares a mapped public capability is prepared through the same host
 * key. A capability with no host preparation mapping returns `null`.
 */
internal object PublicCapabilityPreparation {
    fun hostKey(publicCapabilityId: String): CapabilityKey<*>? = when (publicCapabilityId) {
        PackageCapability.KEYBOARD_OUTPUT -> CapabilityKey.TextOutput
        else -> null
    }
}

/**
 * 4.4: One bounded preparation request. It is bound to the public capability,
 * the channel instance, the runtime generation (via [identity]), the current
 * input [attempt] token, and a finite host [timeoutMillis]. The PREPARE_INPUT
 * invocation gate that runs the request is supplied by the caller.
 */
internal data class CapabilityPreparationRequest(
    val publicCapabilityId: String,
    val identity: CapabilityScopeIdentity,
    val attempt: Long,
    val timeoutMillis: Long,
) {
    init {
        require(publicCapabilityId.isNotBlank()) { "Public capability ID must not be blank" }
        require(timeoutMillis > 0) { "Preparation timeout must be positive" }
    }
}

/**
 * 4.6: Terminal preparation outcomes. A preparation completes exactly once; a
 * later physical completion for a terminated attempt is suppressed by the
 * caller's attempt/generation gate rather than represented here.
 */
internal sealed interface CapabilityPreparationOutcome {
    /** The host proved the capability is prepared and available right now. */
    data object Prepared : CapabilityPreparationOutcome

    /** The capability cannot be prepared into an available state right now. */
    data class Unavailable(val reason: CapabilityUnavailableReason) : CapabilityPreparationOutcome

    /** The owning attempt or generation cancelled the preparation. */
    data object Cancelled : CapabilityPreparationOutcome

    /** The owning scope/generation closed before preparation completed. */
    data object Closed : CapabilityPreparationOutcome

    /** Preparation ran but the host proved it failed. */
    data class Failed(val reason: CapabilityFailureReason) : CapabilityPreparationOutcome
}

/**
 * 4.2/4.3: A host-owned preparer for one public capability.
 *
 * A preparer performs only host-owned preparation work; it never receives a
 * preparation callable or transport object from Lua and never performs a
 * physical output effect. Construction and capability declaration do not invoke
 * a preparer (4.8).
 */
internal fun interface CapabilityPreparer {
    suspend fun prepare(
        scope: ChannelCapabilityScope,
        request: CapabilityPreparationRequest,
    ): CapabilityPreparationOutcome
}

/**
 * 4.3/4.5: Generic host-capability preparer.
 *
 * Acquires the mapped host capability under [CapabilityAcquisitionPolicy.PrepareRecoverable],
 * which joins any compatible in-flight host preparation (for `keyboard.output`
 * this is the shared Sleepwalker preparation) instead of starting a duplicate
 * connection. On proof of availability the lease is released immediately: later
 * operations acquire their own leases, so preparation never transfers shared
 * service ownership to a runtime (design D4).
 */
internal class HostCapabilityPreparer(
    private val key: CapabilityKey<*>,
) : CapabilityPreparer {
    override suspend fun prepare(
        scope: ChannelCapabilityScope,
        request: CapabilityPreparationRequest,
    ): CapabilityPreparationOutcome = when (
        val acquisition = scope.acquire(key, CapabilityAcquisitionPolicy.PrepareRecoverable(request.timeoutMillis))
    ) {
        is CapabilityAcquisition.Available -> {
            // Proof only: release the short-lived preparation lease so the shared
            // service stays host-owned. Release is idempotent and cannot fail the
            // preparation that already proved availability.
            acquisition.lease.release()
            CapabilityPreparationOutcome.Prepared
        }
        is CapabilityAcquisition.Recoverable -> CapabilityPreparationOutcome.Unavailable(acquisition.reason)
        is CapabilityAcquisition.Unavailable -> CapabilityPreparationOutcome.Unavailable(acquisition.reason)
        is CapabilityAcquisition.Denied ->
            CapabilityPreparationOutcome.Unavailable(CapabilityUnavailableReason.POLICY_REFUSED)
        CapabilityAcquisition.Closed -> CapabilityPreparationOutcome.Closed
        CapabilityAcquisition.Cancelled -> CapabilityPreparationOutcome.Cancelled
        is CapabilityAcquisition.Failed -> CapabilityPreparationOutcome.Failed(acquisition.reason)
    }
}

/**
 * 4.2: A bounded registry mapping public capability identifiers to host-owned
 * preparers.
 *
 * The registry is immutable after construction and keyed only by public
 * capability ID. Registration is pure: building a registry or declaring a
 * capability performs no preparation and no platform effect (4.8). The number
 * of registered preparers is bounded by [MAX_PREPARERS].
 */
internal class CapabilityPreparerRegistry private constructor(
    private val preparers: Map<String, CapabilityPreparer>,
) {
    /** The preparer registered for [publicCapabilityId], or null when non-preparable. */
    fun preparerFor(publicCapabilityId: String): CapabilityPreparer? = preparers[publicCapabilityId]

    /** True iff a preparer is registered for [publicCapabilityId]. */
    fun isPreparable(publicCapabilityId: String): Boolean = publicCapabilityId in preparers

    /** The bounded set of public capability IDs that have a registered preparer. */
    val preparableCapabilityIds: Set<String> get() = preparers.keys

    class Builder {
        private val preparers = LinkedHashMap<String, CapabilityPreparer>()

        fun register(publicCapabilityId: String, preparer: CapabilityPreparer): Builder {
            require(publicCapabilityId.isNotBlank()) { "Public capability ID must not be blank" }
            require(preparers.size < MAX_PREPARERS) {
                "Capability preparer registry exceeds bound of $MAX_PREPARERS"
            }
            require(preparers.put(publicCapabilityId, preparer) == null) {
                "Duplicate capability preparer for '$publicCapabilityId'"
            }
            return this
        }

        fun build(): CapabilityPreparerRegistry = CapabilityPreparerRegistry(preparers.toMap())
    }

    companion object {
        const val MAX_PREPARERS: Int = 8

        /** An empty registry: no public capability is preparable. */
        fun empty(): CapabilityPreparerRegistry = CapabilityPreparerRegistry(emptyMap())

        /**
         * 4.3: The production registry registering the host keyboard-output
         * preparer for `keyboard.output` through the shared Sleepwalker
         * preparation facility.
         */
        fun default(): CapabilityPreparerRegistry = Builder()
            .register(PackageCapability.KEYBOARD_OUTPUT, HostCapabilityPreparer(CapabilityKey.TextOutput))
            .build()
    }
}
