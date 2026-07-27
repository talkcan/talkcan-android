package io.talkcan.lua.actor

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Cooperative ready/suspended coroutine scheduler.
 *
 * At most one Lua coroutine executes at a time. A ready queue holds eligible
 * coroutines; a suspended set holds coroutines waiting on host-operation
 * tokens. The scheduler runs in the generation-owned child scope on the
 * bounded runtime worker dispatcher. Every dequeue validates the
 * generation's live state and every Lua entry uses the kernel's per-state
 * serialization lock.
 *
 * The scheduler does not preempt; the instruction hook interrupts only
 * pure-Lua loops, not suspended coroutines waiting on host operations. A
 * continuation does not occupy the host invocation FIFO merely because it
 * belongs to the same generation.
 *
 * The scheduler is a mechanism owned by the runtime. It does not create
 * threads and does not own Android lifecycle.
 */
internal class ActorScheduler {

    private val readyQueue = ConcurrentLinkedQueue<ActorCoroutine>()
    private val running = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    // Serializes only scheduler state transitions; never held across Lua.
    private val stateLock = Any()
    // Completed while idle; reset atomically with an entry claim and completed
    // by release(). Awaiters suspend rather than polling a worker thread.
    private var idleSignal = CompletableDeferred<Unit>().apply { complete(Unit) }

    /**
     * Whether a coroutine is currently entered in Lua.
     */
    val isEntered: Boolean
        get() = running.get()

    val readyDepth: Int
        get() = readyQueue.size

    /**
     * Enqueue one coroutine as ready. It will be entered after earlier ready
     * work has yielded or completed.
     */
    fun ready(coroutine: ActorCoroutine) {
        synchronized(stateLock) {
            if (!closed.get()) {
                readyQueue.add(coroutine)
            }
        }
    }

    /**
     * Attempt to enter one coroutine. Returns the coroutine to execute, or
     * null if another coroutine is already entered or the scheduler is
     * closed.
     *
     * The caller must call [release] when the Lua slice yields or completes.
     */
    fun tryEnter(): ActorCoroutine? = synchronized(stateLock) {
        // Claim and dequeue are atomic with close(). If close wins first, no
        // slice is admitted; if entry wins, close preserves the active claim
        // for release() and quiescence to observe.
        if (closed.get() || running.get()) return@synchronized null
        val next = readyQueue.poll() ?: return@synchronized null
        running.set(true)
        idleSignal = CompletableDeferred()
        next
    }

    /**
     * Attempt to enter one *exact* coroutine, claiming the single Lua entry
     * slot and dequeueing precisely [coroutine] — not whatever sits at the
     * head of the ready queue. Returns true if this caller claimed the slot
     * and removed [coroutine]; false if another coroutine is already entered,
     * the scheduler is closed, or [coroutine] is not enqueued.
     *
     * The caller must call [release] when the slice yields or completes.
     * Claim/removal is atomic with close: a close that wins first leaves no
     * claim; an entry that wins keeps its claim until release.
     */
    fun tryEnterExact(coroutine: ActorCoroutine): Boolean = synchronized(stateLock) {
        if (closed.get() || running.get()) return@synchronized false
        if (!readyQueue.remove(coroutine)) return@synchronized false
        running.set(true)
        idleSignal = CompletableDeferred()
        true
    }


    /**
     * Release the Lua entry after a slice yields or completes. The next ready
     * coroutine may then be entered. Only this method clears an active claim;
     * [close] deliberately leaves one intact.
     */
    fun release() {
        synchronized(stateLock) {
            running.set(false)
            idleSignal.complete(Unit)
        }
    }

    /**
     * Close the scheduler. No further coroutines may be entered.
     */
    fun close() {
        synchronized(stateLock) {
            // Mark closed and drop queued work. Do NOT clear [running]: an
            // already-entered slice still owns the Lua entry and must be the
            // sole one to call [release]. Clearing [running] here would let a
            // concurrent enter admit a second slice against a closed scheduler.
            closed.set(true)
            readyQueue.clear()
        }
    }

    /**
     * Wait for an entered slice to release the single Lua entry, bounded by
     * [timeoutMillis]. Completion is signal-driven: [release] completes the
     * current idle signal, so virtual coroutine time deterministically drives
     * the timeout without polling or nano-time arithmetic.
     */
    suspend fun awaitIdle(timeoutMillis: Long): Boolean {
        require(timeoutMillis > 0) { "Timeout must be positive" }
        return withTimeoutOrNull(timeoutMillis) {
            awaitIdleSignal()
            true
        } ?: false
    }

    /** Await the current entry claim without imposing a second deadline. */
    internal suspend fun awaitIdleSignal() {
        val signal = synchronized(stateLock) { idleSignal }
        signal.await()
    }

    val isClosed: Boolean
        get() = closed.get()
}

/**
 * One cooperative coroutine in the actor scheduler.
 *
 * Carries the owning operation token so the scheduler can resume it under the
 * generation's live-state gate and per-state serialization lock. A
 * continuation carries the operation token's generation, task, and operation
 * authorization.
 */
internal class ActorCoroutine(
    val ownerTask: ActorTaskIdentity,
    val owningOperation: ActorOperationIdentity?,
    val slice: suspend (ActorCoroutine) -> ActorCoroutineResult,
)

/**
 * Result of one coroutine Lua slice.
 */
internal sealed class ActorCoroutineResult {
    data class Completed(val value: String?) : ActorCoroutineResult()
    data class Yielded(val operation: ActorOperationIdentity) : ActorCoroutineResult()
    data class Failed(val diagnostic: String, val fatal: Boolean) : ActorCoroutineResult()
}