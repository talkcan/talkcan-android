package io.talkcan.storage

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * 4.12: Why an admitted operation reached its terminal gate. Every operation
 * terminates through exactly one of these causes; the cause is recorded on the
 * reservation so late completions can be distinguished from the first terminal.
 */
public enum class TerminalCause {
    /** The operation produced a typed success that was published. */
    SUCCESS,

    /** The backend reported a normalized provider failure. */
    PROVIDER_FAILURE,

    /** The operation exceeded its finite per-operation deadline. */
    TIMEOUT,

    /** Cooperative cancellation was observed. */
    CANCELLED,

    /** The lease was revoked while the operation was in flight. */
    REVOKED,

    /** The registry/generation closed while the operation was in flight. */
    CLOSED,
}

/**
 * 4.12: Finite, host-configured admission and lifecycle bounds applied on top of
 * the per-operation content ceilings in [VfsPolicy].
 *
 * [VfsPolicy] bounds a single operation's path, name, page, and text/response
 * bytes; these bounds govern how many operations may run, how much may transfer
 * across a generation and the whole process, how far a listing may paginate, and
 * how long one operation may run. All are strict positive policy numbers, not
 * Lua compatibility promises; the host may tighten them.
 */
public data class VfsLimits(
    /** Maximum operations admitted concurrently. */
    val maxActiveOperations: Int = 64,

    /** Maximum UTF-8 bytes transferred within one generation before exhaustion. */
    val maxGenerationTransferBytes: Long = 256L shl 20,

    /** Maximum UTF-8 bytes transferred process-wide before exhaustion. */
    val maxProcessTransferBytes: Long = 4L shl 30,

    /** Maximum pages one listing session may serve before exhaustion. */
    val maxListPagesPerSession: Int = 10_000,

    /** Maximum live (paused, await-next-page) listing cursors at once. */
    val maxLiveListCursors: Int = 1_024,

    /** Finite wall-clock deadline for one operation, in milliseconds. */
    val operationDeadlineMs: Long = 30_000,
) {
    init {
        require(maxActiveOperations > 0) { "maxActiveOperations must be positive" }
        require(maxGenerationTransferBytes > 0) { "maxGenerationTransferBytes must be positive" }
        require(maxProcessTransferBytes > 0) { "maxProcessTransferBytes must be positive" }
        require(maxListPagesPerSession > 0) { "maxListPagesPerSession must be positive" }
        require(maxLiveListCursors > 0) { "maxLiveListCursors must be positive" }
        require(operationDeadlineMs > 0) { "operationDeadlineMs must be positive" }
    }
}

/**
 * 4.12: The outcome of an admission attempt: an [Admitted] reservation that MUST
 * be terminated exactly once, or a [Rejected] normalized failure (active-slot
 * exhaustion → [FilesystemErrorCode.E_BUSY]; byte-budget exhaustion →
 * [FilesystemErrorCode.E_TOO_LARGE]).
 */
internal sealed interface Admission {
    data class Admitted(val reservation: OperationReservation) : Admission
    data class Rejected(val error: FilesystemError) : Admission
}

/**
 * 4.12: One admitted operation's reservation of an active slot and a byte budget.
 *
 * The reservation is released through the exact-once [terminate] gate. The first
 * terminal cause wins and releases the active slot and reconciles the reserved
 * bytes against the actual transfer; every later call is a discarded late
 * completion that changes nothing and double-releases nothing.
 */
internal class OperationReservation(
    private val ledger: OperationLedger,
    private val reservedBytes: Long,
) {
    private val terminated = AtomicBoolean(false)
    private val causeRef = AtomicReference<TerminalCause?>(null)

    /** The terminal cause, or null while the operation is still in flight. */
    val cause: TerminalCause? get() = causeRef.get()

    /** True once any terminal cause has been recorded. */
    val isTerminated: Boolean get() = terminated.get()

    /**
     * Exact-once terminal. Records [cause], releases the active slot, and
     * reconciles the reserved bytes against [actualBytes] (the bytes actually
     * transferred on success; zero on every failure path). Returns true only for
     * the first terminal call; a late completion returns false and is discarded
     * without releasing anything again.
     */
    fun terminate(cause: TerminalCause, actualBytes: Long = 0): Boolean {
        if (!terminated.compareAndSet(false, true)) return false
        causeRef.set(cause)
        ledger.release(reservedBytes, actualBytes)
        return true
    }
}

/**
 * 4.12: Thread-safe admission and accounting ledger for one [MountedFilesystem].
 *
 * Tracks concurrent active operations, cumulative generation and process transfer
 * bytes, and live listing cursors. Admission atomically reserves an active slot
 * and a byte budget; termination releases the slot and reconciles the budget
 * exactly once through the [OperationReservation] gate. Generation transfer bytes
 * reset when the owning generation advances; process bytes never reset.
 */
internal class OperationLedger(private val limits: VfsLimits) {

    private val activeOps = AtomicInteger(0)
    private val generationBytes = AtomicLong(0)
    private val processBytes = AtomicLong(0)
    private val liveCursors = AtomicInteger(0)
    private val byteLock = Any()

    val activeCount: Int get() = activeOps.get()
    val generationTransferBytes: Long get() = generationBytes.get()
    val processTransferBytes: Long get() = processBytes.get()
    val liveCursorCount: Int get() = liveCursors.get()

    /**
     * Admits one operation reserving [reserveBytes]. Fails closed: active-slot
     * exhaustion → [FilesystemErrorCode.E_BUSY]; generation or process byte-budget
     * exhaustion → [FilesystemErrorCode.E_TOO_LARGE]. On success the returned
     * reservation holds the slot and budget until terminated.
     */
    fun tryAdmit(reserveBytes: Long): Admission {
        while (true) {
            val current = activeOps.get()
            if (current >= limits.maxActiveOperations) {
                return Admission.Rejected(FilesystemError(FilesystemErrorCode.E_BUSY, "too many active operations"))
            }
            if (activeOps.compareAndSet(current, current + 1)) break
        }
        synchronized(byteLock) {
            val nextGeneration = generationBytes.get() + reserveBytes
            val nextProcess = processBytes.get() + reserveBytes
            if (nextGeneration > limits.maxGenerationTransferBytes ||
                nextProcess > limits.maxProcessTransferBytes
            ) {
                activeOps.decrementAndGet()
                return Admission.Rejected(
                    FilesystemError(FilesystemErrorCode.E_TOO_LARGE, "transfer budget exhausted"),
                )
            }
            generationBytes.set(nextGeneration)
            processBytes.set(nextProcess)
        }
        return Admission.Admitted(OperationReservation(this, reserveBytes))
    }

    /** Releases one active slot and reconciles reserved bytes to actual (exact-once, driven by the reservation). */
    fun release(reservedBytes: Long, actualBytes: Long) {
        val delta = actualBytes - reservedBytes
        if (delta != 0L) {
            synchronized(byteLock) {
                generationBytes.addAndGet(delta)
                processBytes.addAndGet(delta)
            }
        }
        activeOps.decrementAndGet()
    }

    /** Reserves a live listing cursor slot; false when the cursor budget is exhausted. */
    fun tryPublishCursor(): Boolean {
        while (true) {
            val current = liveCursors.get()
            if (current >= limits.maxLiveListCursors) return false
            if (liveCursors.compareAndSet(current, current + 1)) return true
        }
    }

    /** Releases one live listing cursor slot (idempotent floor at zero). */
    fun cursorReleased() {
        while (true) {
            val current = liveCursors.get()
            if (current <= 0) return
            if (liveCursors.compareAndSet(current, current - 1)) return
        }
    }

    /** Resets the per-generation transfer budget (called when the generation advances). */
    fun resetGeneration() {
        synchronized(byteLock) {
            generationBytes.set(0)
        }
    }

    /** Drops all live cursor accounting (called on close, when every session is invalidated). */
    fun resetCursors() {
        liveCursors.set(0)
    }
}
