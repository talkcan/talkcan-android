package io.talkcan.lua.kernel

import io.talkcan.lua.HostOperationKind
import io.talkcan.lua.HostOperationClaim

/**
 * One typed host-operation request surfaced by a state's trusted bootstrap when
 * Lua code performs a host effect (http, fs, keyboard output, secret read, work,
 * audio). The engine thread posts these into its bounded [HostOperationOutbox];
 * the app claims them exactly once through `claimHostOperation`. Only the typed
 * [kind] and a bounded workvalue-shaped payload cross the boundary — never a Lua
 * registry index or native pointer.
 */
internal class HostOperationRequest(
    val claim: HostOperationClaim.Admitted,
) {
    val requestId: Long get() = claim.requestId
    val kind: HostOperationKind get() = claim.kind
}

/**
 * Bounded typed registry of a single state's pending host-operation requests.
 * Bounded so a runaway plugin cannot accumulate unbounded outstanding requests;
 * a saturated outbox rejects further offers. Keyed by opaque request identity so
 * claims are exactly-once random access rather than FIFO.
 */
internal class HostOperationOutbox(private val capacity: Int) {

    private val lock = Any()
    private val pending = LinkedHashMap<Long, HostOperationRequest>()

    /** Engine-side: register a request; false when the outbox is saturated. */
    fun offer(request: HostOperationRequest): Boolean = synchronized(lock) {
        if (pending.size >= capacity) {
            false
        } else {
            pending[request.requestId] = request
            true
        }
    }

    /** Non-consuming lookup by request identity. */
    fun peek(requestId: Long): HostOperationRequest? = synchronized(lock) { pending[requestId] }

    /** Exactly-once claim; returns and removes the request, or null when absent. */
    fun claim(requestId: Long): HostOperationRequest? = synchronized(lock) { pending.remove(requestId) }

    fun size(): Int = synchronized(lock) { pending.size }

    /** Drop every pending request; used on state close before native release. */
    fun clear() {
        synchronized(lock) { pending.clear() }
    }
}
