package io.talkcan.channel.capability

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Authorized execution-owner kinds for keyboard output. The set is bounded and carries no content;
 * it identifies which host-managed context authorized an operation (input, yielded SOS, a
 * runtime-managed task, or the built-in Keyboard path).
 */
enum class OutputExecutionOwnerKind {
    INPUT,
    SOS,
    MANAGED_TASK,
    BUILT_IN,
}

/**
 * Opaque execution-owner identity. It names the owner kind and an opaque owner handle only. It never
 * carries text, semantic key, profile, compiled operation, acknowledgement, address, or transport
 * data, so it is safe to retain in queue labels and diagnostics.
 */
data class OutputExecutionOwner(
    val kind: OutputExecutionOwnerKind,
    val opaqueId: String,
) {
    init {
        require(opaqueId.isNotBlank()) { "Execution owner ID must not be blank" }
    }
}

/**
 * Content-free attribution attached to every admitted output operation.
 *
 * [generation] is null for the built-in String-keyed port, which has no runtime generation at the
 * service boundary; per-generation bounds are then simply not applied to that operation while
 * per-instance and process bounds still hold. Lua scopes always supply a non-null generation so all
 * three bound dimensions apply.
 */
data class OutputOperationAttribution(
    val channelInstanceId: String,
    val generation: RuntimeGeneration?,
    val owner: OutputExecutionOwner,
) {
    init {
        require(channelInstanceId.isNotBlank()) { "Channel instance ID must not be blank" }
    }
}

/**
 * Positive, validated bounds for the host-wide output admission scheduler. They are centralized in
 * this owning subsystem and align with the host's existing centralized limits: per-operation text is
 * bounded by `PackageConfigurationLimits.MAX_STRING_VALUE_BYTES` (16384) and queue depths by the
 * `RuntimeInvocationPolicy` per-generation queue capacity (16).
 */
data class OutputAdmissionBounds(
    val maxActivePerInstance: Int = 1,
    val maxActivePerGeneration: Int = 1,
    val maxActivePerProcess: Int = 1,
    val maxQueuedPerInstance: Int = 16,
    val maxQueuedPerGeneration: Int = 16,
    val maxQueuedPerProcess: Int = 16,
    val maxInFlightPerProcess: Int = 32,
    val maxPayloadBytesPerInstance: Long = 16_384L,
    val maxPayloadBytesPerGeneration: Long = 16_384L,
    val maxPayloadBytesPerProcess: Long = 65_536L,
) {
    init {
        require(maxActivePerInstance > 0) { "maxActivePerInstance must be positive" }
        require(maxActivePerGeneration > 0) { "maxActivePerGeneration must be positive" }
        require(maxActivePerProcess > 0) { "maxActivePerProcess must be positive" }
        require(maxQueuedPerInstance > 0) { "maxQueuedPerInstance must be positive" }
        require(maxQueuedPerGeneration > 0) { "maxQueuedPerGeneration must be positive" }
        require(maxQueuedPerProcess > 0) { "maxQueuedPerProcess must be positive" }
        require(maxInFlightPerProcess > 0) { "maxInFlightPerProcess must be positive" }
        require(maxPayloadBytesPerInstance > 0) { "maxPayloadBytesPerInstance must be positive" }
        require(maxPayloadBytesPerGeneration > 0) { "maxPayloadBytesPerGeneration must be positive" }
        require(maxPayloadBytesPerProcess > 0) { "maxPayloadBytesPerProcess must be positive" }
    }

    companion object {
        val DEFAULT = OutputAdmissionBounds()
    }
}

/**
 * Normalized admission diagnostic phase. Every phase describes queue/admission lifecycle only; no
 * phase carries content.
 */
enum class OutputAdmissionPhase {
    QUEUED,
    ACTIVE,
    TERMINAL,
    REVOKED,
    CANCELLED,
    BUSY,
    REJECTED,
    CLOSED,
}

/**
 * Normalized admission diagnostic. It carries attribution, phase, bounded payload length, and FIFO
 * sequence only. There is deliberately no field for text, semantic key, profile, compiled operation,
 * acknowledgement, address, or device identity, so content cannot cross this boundary.
 */
data class OutputAdmissionDiagnostic(
    val attribution: OutputOperationAttribution,
    val phase: OutputAdmissionPhase,
    val payloadBytes: Long,
    val sequence: Long,
)

fun interface OutputAdmissionDiagnosticSink {
    fun record(diagnostic: OutputAdmissionDiagnostic)
}

/**
 * Outcome of an admission attempt. [Admitted] wraps the physical effect's terminal value; every other
 * case proves the physical effect did NOT begin.
 */
sealed interface AdmissionResult<out T> {
    /** The effect ran to a terminal value. */
    data class Admitted<T>(val value: T) : AdmissionResult<T>

    /** Transient capacity exhaustion; a later attempt may succeed. Effect-not-begun. */
    data object Busy : AdmissionResult<Nothing>

    /** The single operation can never fit the configured bounds. Effect-not-begun. */
    data object Rejected : AdmissionResult<Nothing>

    /** The scheduler is shut down. Effect-not-begun. */
    data object Closed : AdmissionResult<Nothing>

    /** The owning scope or generation was revoked while the operation was queued. Effect-not-begun. */
    data object Revoked : AdmissionResult<Nothing>
}

/**
 * One host-wide bounded FIFO admission scheduler shared by every keyboard-output client (built-in and
 * Lua). It admits operations ahead of the shared Sleepwalker physical serialization, enforcing
 * positive per-instance, per-generation, and process bounds for active operations, queued operations,
 * retained payload bytes, and in-flight waiters before any compilation, connection, arm, or output.
 *
 * Ordering is deterministic global FIFO: the queue head blocks until an active slot frees, so
 * selection never reorders the queue and one producer cannot allocate an unbounded queue or waiter
 * set. Queued revocation/cancellation/shutdown proves the effect did not begin; once an operation is
 * active its terminal outcome is owned by the physical effect and cleanup is idempotent so exactly one
 * outcome wins and sibling operations remain untouched.
 */
class OutputAdmissionScheduler(
    private val bounds: OutputAdmissionBounds = OutputAdmissionBounds.DEFAULT,
    private val diagnostics: OutputAdmissionDiagnosticSink = OutputAdmissionDiagnosticSink { },
) {
    private val mutex = Mutex()
    private var closed = false
    private var nextSequence = 0L

    private val queue = ArrayDeque<Entry>()
    private val activeEntries = ArrayDeque<Entry>()
    private var activeProcess = 0
    private var queuedProcess = 0
    private var inFlightProcess = 0
    private var payloadProcess = 0L

    private val queuedByInstance = HashMap<String, Int>()
    private val queuedByGeneration = HashMap<Long, Int>()
    private val activeByInstance = HashMap<String, Int>()
    private val activeByGeneration = HashMap<Long, Int>()
    private val payloadByInstance = HashMap<String, Long>()
    private val payloadByGeneration = HashMap<Long, Long>()

    /**
     * Admits one operation, suspending in deterministic FIFO order until an active slot is available,
     * then runs [effect] exactly once. The returned [AdmissionResult] is terminal: [AdmissionResult.Admitted]
     * wraps the effect value, and every other case proves [effect] was never invoked.
     */
    suspend fun <T> admit(
        attribution: OutputOperationAttribution,
        payloadBytes: Long,
        effect: suspend () -> T,
    ): AdmissionResult<T> {
        require(payloadBytes >= 0) { "Payload bytes must not be negative" }

        // A single operation that can never fit any applicable payload bound is rejected permanently
        // rather than queued behind space that can never free.
        val generation = attribution.generation?.value
        if (payloadBytes > bounds.maxPayloadBytesPerInstance ||
            payloadBytes > bounds.maxPayloadBytesPerProcess ||
            (generation != null && payloadBytes > bounds.maxPayloadBytesPerGeneration)
        ) {
            emit(attribution, OutputAdmissionPhase.REJECTED, payloadBytes, sequence = -1L)
            return AdmissionResult.Rejected
        }

        val gate = CompletableDeferred<AdmissionDecision>()
        when (val reserved = mutex.withLock { reserveLocked(attribution, payloadBytes, gate) }) {
            ReserveOutcome.Closed -> {
                emit(attribution, OutputAdmissionPhase.CLOSED, payloadBytes, sequence = -1L)
                return AdmissionResult.Closed
            }
            ReserveOutcome.Busy -> {
                emit(attribution, OutputAdmissionPhase.BUSY, payloadBytes, sequence = -1L)
                return AdmissionResult.Busy
            }
            is ReserveOutcome.Reserved -> {
                val sequence = reserved.sequence
                val decision = try {
                    gate.await()
                } catch (cancelled: CancellationException) {
                    // Caller cancelled while queued, or exactly as the slot was granted. Remove the
                    // entry wherever it currently sits so the slot is released and the effect never runs.
                    val removal = withContext(NonCancellable) { mutex.withLock { removeLocked(sequence) } }
                    if (removal != Removal.ABSENT) {
                        emit(attribution, OutputAdmissionPhase.CANCELLED, payloadBytes, sequence)
                    }
                    throw cancelled
                }
                when (decision) {
                    AdmissionDecision.REVOKED -> return AdmissionResult.Revoked
                    AdmissionDecision.CLOSED -> return AdmissionResult.Closed
                    AdmissionDecision.START -> Unit
                }
                val result = try {
                    effect()
                } finally {
                    withContext(NonCancellable) { mutex.withLock { completeActiveLocked(sequence, attribution, payloadBytes) } }
                }
                return AdmissionResult.Admitted(result)
            }
        }
    }

    /** Revokes every queued (not-yet-active) operation for one instance and generation, effect-not-begun. */
    suspend fun revokeScope(identity: CapabilityScopeIdentity) {
        revokeMatching {
            it.channelInstanceId == identity.channelInstanceId && it.generation == identity.runtimeGeneration
        }
    }

    /** Revokes every queued (not-yet-active) operation belonging to one generation, effect-not-begun. */
    suspend fun revokeGeneration(generation: RuntimeGeneration) {
        revokeMatching { it.generation == generation }
    }

    /**
     * Stops admitting new operations and terminalizes every queued operation as [AdmissionResult.Closed].
     * Active operations are left to finish their own terminal cleanup. Idempotent.
     */
    suspend fun shutdown() {
        val gates = mutex.withLock {
            if (closed) {
                emptyList()
            } else {
                closed = true
                val pending = queue.map { it.gate }
                for (entry in queue) {
                    emit(entry.attribution, OutputAdmissionPhase.CLOSED, entry.payloadBytes, entry.sequence)
                    releaseQueuedCounts(entry)
                }
                queue.clear()
                pending
            }
        }
        for (gate in gates) gate.complete(AdmissionDecision.CLOSED)
    }

    /** Current queued entry count process-wide. Content-free observability for tests and diagnostics. */
    suspend fun queuedCount(): Int = mutex.withLock { queuedProcess }

    /** Current active entry count process-wide. Content-free observability for tests and diagnostics. */
    suspend fun activeCount(): Int = mutex.withLock { activeProcess }

    private fun reserveLocked(
        attribution: OutputOperationAttribution,
        payloadBytes: Long,
        gate: CompletableDeferred<AdmissionDecision>,
    ): ReserveOutcome {
        if (closed) return ReserveOutcome.Closed
        val generation = attribution.generation?.value
        val instance = attribution.channelInstanceId

        if ((queuedByInstance[instance] ?: 0) >= bounds.maxQueuedPerInstance) return ReserveOutcome.Busy
        if (generation != null && (queuedByGeneration[generation] ?: 0) >= bounds.maxQueuedPerGeneration) {
            return ReserveOutcome.Busy
        }
        if (queuedProcess >= bounds.maxQueuedPerProcess) return ReserveOutcome.Busy
        if (inFlightProcess >= bounds.maxInFlightPerProcess) return ReserveOutcome.Busy
        if ((payloadByInstance[instance] ?: 0L) + payloadBytes > bounds.maxPayloadBytesPerInstance) {
            return ReserveOutcome.Busy
        }
        if (generation != null &&
            (payloadByGeneration[generation] ?: 0L) + payloadBytes > bounds.maxPayloadBytesPerGeneration
        ) {
            return ReserveOutcome.Busy
        }
        if (payloadProcess + payloadBytes > bounds.maxPayloadBytesPerProcess) return ReserveOutcome.Busy

        val sequence = nextSequence++
        val entry = Entry(sequence, attribution, payloadBytes, gate)
        queue.addLast(entry)
        queuedByInstance.increment(instance)
        if (generation != null) queuedByGeneration.increment(generation)
        queuedProcess += 1
        inFlightProcess += 1
        payloadByInstance[instance] = (payloadByInstance[instance] ?: 0L) + payloadBytes
        if (generation != null) payloadByGeneration[generation] = (payloadByGeneration[generation] ?: 0L) + payloadBytes
        payloadProcess += payloadBytes
        emit(attribution, OutputAdmissionPhase.QUEUED, payloadBytes, sequence)
        pumpLocked()
        return ReserveOutcome.Reserved(sequence)
    }

    private fun pumpLocked() {
        while (activeProcess < bounds.maxActivePerProcess) {
            val head = queue.firstOrNull() ?: return
            val instance = head.attribution.channelInstanceId
            val generation = head.attribution.generation?.value
            if ((activeByInstance[instance] ?: 0) >= bounds.maxActivePerInstance) return
            if (generation != null && (activeByGeneration[generation] ?: 0) >= bounds.maxActivePerGeneration) return
            queue.removeFirst()
            activeEntries.addLast(head)
            queuedByInstance.decrement(instance)
            if (generation != null) queuedByGeneration.decrement(generation)
            queuedProcess -= 1
            activeProcess += 1
            activeByInstance.increment(instance)
            if (generation != null) activeByGeneration.increment(generation)
            emit(head.attribution, OutputAdmissionPhase.ACTIVE, head.payloadBytes, head.sequence)
            head.gate.complete(AdmissionDecision.START)
        }
    }

    private fun completeActiveLocked(
        sequence: Long,
        attribution: OutputOperationAttribution,
        payloadBytes: Long,
    ) {
        val index = activeEntries.indexOfFirst { it.sequence == sequence }
        if (index < 0) return
        val entry = activeEntries.removeAt(index)
        releaseActiveCounts(entry)
        emit(attribution, OutputAdmissionPhase.TERMINAL, payloadBytes, sequence)
        pumpLocked()
    }

    private fun removeLocked(sequence: Long): Removal {
        val queuedIndex = queue.indexOfFirst { it.sequence == sequence }
        if (queuedIndex >= 0) {
            val entry = queue.removeAt(queuedIndex)
            releaseQueuedCounts(entry)
            pumpLocked()
            return Removal.QUEUED
        }
        val activeIndex = activeEntries.indexOfFirst { it.sequence == sequence }
        if (activeIndex >= 0) {
            val entry = activeEntries.removeAt(activeIndex)
            releaseActiveCounts(entry)
            pumpLocked()
            return Removal.ACTIVE
        }
        return Removal.ABSENT
    }

    private suspend fun revokeMatching(predicate: (OutputOperationAttribution) -> Boolean) {
        val gates = mutex.withLock {
            val matches = queue.filter { predicate(it.attribution) }
            if (matches.isEmpty()) return@withLock emptyList()
            val revoked = matches.map { it.gate }
            for (entry in matches) {
                queue.remove(entry)
                releaseQueuedCounts(entry)
                emit(entry.attribution, OutputAdmissionPhase.REVOKED, entry.payloadBytes, entry.sequence)
            }
            pumpLocked()
            revoked
        }
        for (gate in gates) gate.complete(AdmissionDecision.REVOKED)
    }

    private fun releaseQueuedCounts(entry: Entry) {
        val instance = entry.attribution.channelInstanceId
        val generation = entry.attribution.generation?.value
        queuedByInstance.decrement(instance)
        if (generation != null) queuedByGeneration.decrement(generation)
        queuedProcess -= 1
        inFlightProcess -= 1
        payloadByInstance.decrementBytes(instance, entry.payloadBytes)
        if (generation != null) payloadByGeneration.decrementBytes(generation, entry.payloadBytes)
        payloadProcess -= entry.payloadBytes
    }

    private fun releaseActiveCounts(entry: Entry) {
        val instance = entry.attribution.channelInstanceId
        val generation = entry.attribution.generation?.value
        activeProcess -= 1
        activeByInstance.decrement(instance)
        if (generation != null) activeByGeneration.decrement(generation)
        inFlightProcess -= 1
        payloadByInstance.decrementBytes(instance, entry.payloadBytes)
        if (generation != null) payloadByGeneration.decrementBytes(generation, entry.payloadBytes)
        payloadProcess -= entry.payloadBytes
    }

    private fun emit(
        attribution: OutputOperationAttribution,
        phase: OutputAdmissionPhase,
        payloadBytes: Long,
        sequence: Long,
    ) {
        diagnostics.record(OutputAdmissionDiagnostic(attribution, phase, payloadBytes, sequence))
    }

    private fun HashMap<String, Int>.increment(key: String) {
        this[key] = (this[key] ?: 0) + 1
    }

    private fun HashMap<Long, Int>.increment(key: Long) {
        this[key] = (this[key] ?: 0) + 1
    }

    private fun HashMap<String, Int>.decrement(key: String) {
        val next = (this[key] ?: 0) - 1
        if (next <= 0) remove(key) else this[key] = next
    }

    private fun HashMap<Long, Int>.decrement(key: Long) {
        val next = (this[key] ?: 0) - 1
        if (next <= 0) remove(key) else this[key] = next
    }

    private fun HashMap<String, Long>.decrementBytes(key: String, bytes: Long) {
        val next = (this[key] ?: 0L) - bytes
        if (next <= 0L) remove(key) else this[key] = next
    }

    private fun HashMap<Long, Long>.decrementBytes(key: Long, bytes: Long) {
        val next = (this[key] ?: 0L) - bytes
        if (next <= 0L) remove(key) else this[key] = next
    }

    private enum class AdmissionDecision { START, REVOKED, CLOSED }

    private sealed interface ReserveOutcome {
        data class Reserved(val sequence: Long) : ReserveOutcome
        data object Busy : ReserveOutcome
        data object Closed : ReserveOutcome
    }

    private enum class Removal { QUEUED, ACTIVE, ABSENT }

    private class Entry(
        val sequence: Long,
        val attribution: OutputOperationAttribution,
        val payloadBytes: Long,
        val gate: CompletableDeferred<AdmissionDecision>,
    )
}
