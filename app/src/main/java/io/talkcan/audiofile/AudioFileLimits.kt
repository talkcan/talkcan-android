package io.talkcan.audiofile

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * 6.8: Finite, host-configured admission and transfer bounds for the audio-file capability.
 *
 * These govern how many operations may run, how much may transfer across a generation and
 * the whole process, and how long one operation may run (mirroring the mounted-storage ledger).
 * All are strict positive policy numbers, not Lua compatibility promises; the host may tighten them.
 */
public data class AudioFileLimits(
    val maxActiveOperations: Int = 32,
    val maxGenerationTransferBytes: Long = 256L shl 20,
    val maxProcessTransferBytes: Long = 4L shl 30,
    val operationDeadlineMs: Long = 30_000,
) {
    init {
        require(maxActiveOperations > 0) { "maxActiveOperations must be positive" }
        require(maxGenerationTransferBytes > 0) { "maxGenerationTransferBytes must be positive" }
        require(maxProcessTransferBytes > 0) { "maxProcessTransferBytes must be positive" }
        require(operationDeadlineMs > 0) { "operationDeadlineMs must be positive" }
    }
}

/**
 * 6.8: Why an admitted audio-file operation reached its terminal gate. Every operation terminates
 * through exactly one of these causes; the cause is recorded on the reservation so late completions
 * are distinguishable from the first terminal.
 */
internal enum class AudioTerminalCause {
    SUCCESS,
    PROVIDER_FAILURE,
    TIMEOUT,
    CANCELLED,
    REVOKED,
    CLOSED,
}

/**
 * 6.8: The outcome of an admission attempt: an [Admitted] reservation that MUST be terminated
 * exactly once, or a [Rejected] normalized failure (active-slot exhaustion →
 * [AudioFileErrorCode.E_BUSY]; transfer-budget exhaustion → [AudioFileErrorCode.E_TOO_LARGE]).
 */
internal sealed interface AudioAdmission {
    data class Admitted(val reservation: AudioOperationReservation) : AudioAdmission
    data class Rejected(val error: AudioFileError) : AudioAdmission
}

/**
 * 6.8: One admitted operation's reservation of an active slot and a transfer byte budget. Released
 * through the exact-once [terminate] gate: the first terminal cause wins and releases the slot and
 * reconciles the reserved bytes against the actual transfer; every later call is a discarded late
 * completion that changes nothing and double-releases nothing.
 */
internal class AudioOperationReservation(
    private val ledger: AudioFileLedger,
    private val reservedBytes: Long,
) {
    private val terminated = AtomicBoolean(false)
    private val causeRef = AtomicReference<AudioTerminalCause?>(null)

    val cause: AudioTerminalCause? get() = causeRef.get()
    val isTerminated: Boolean get() = terminated.get()

    fun terminate(cause: AudioTerminalCause, actualBytes: Long = 0L): Boolean {
        if (!terminated.compareAndSet(false, true)) return false
        causeRef.set(cause)
        ledger.release(reservedBytes, actualBytes)
        return true
    }
}

/**
 * 6.8: Thread-safe admission and transfer-accounting ledger for one [AudioFileAdapter].
 *
 * Tracks concurrent active operations and cumulative generation and process transfer bytes.
 * Admission atomically reserves an active slot and a byte budget; termination releases the slot and
 * reconciles the budget exactly once through the [AudioOperationReservation] gate. Generation
 * transfer bytes reset when the owning generation advances; process bytes never reset.
 */
internal class AudioFileLedger(private val limits: AudioFileLimits) {

    private val activeOps = AtomicInteger(0)
    private val generationBytes = AtomicLong(0)
    private val processBytes = AtomicLong(0)
    private val byteLock = Any()

    val activeCount: Int get() = activeOps.get()
    val generationTransferBytes: Long get() = generationBytes.get()
    val processTransferBytes: Long get() = processBytes.get()

    fun tryAdmit(reserveBytes: Long): AudioAdmission {
        while (true) {
            val current = activeOps.get()
            if (current >= limits.maxActiveOperations) {
                return AudioAdmission.Rejected(
                    AudioFileError(AudioFileErrorCode.E_BUSY, "too many active operations"),
                )
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
                return AudioAdmission.Rejected(
                    AudioFileError(AudioFileErrorCode.E_TOO_LARGE, "transfer budget exhausted"),
                )
            }
            generationBytes.set(nextGeneration)
            processBytes.set(nextProcess)
        }
        return AudioAdmission.Admitted(AudioOperationReservation(this, reserveBytes))
    }

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

    fun resetGeneration() {
        synchronized(byteLock) {
            generationBytes.set(0)
        }
    }
}
