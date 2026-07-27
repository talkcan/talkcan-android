package io.talkcan.lua.actor

import java.util.concurrent.atomic.AtomicLong

/**
 * Bounded FIFO event mailbox for one actor.
 *
 * Admits events in deterministic FIFO order and rejects new events with a
 * typed [ActorMailboxResult.Busy] result when no capacity remains. Does not
 * allocate an unbounded waiter queue and does not start the rejected event
 * later. A not-ready actor refuses ordinary events with
 * [ActorMailboxResult.NotReady] and does not queue them. After retirement or
 * closure, all events are rejected with [ActorMailboxResult.Closed].
 * Thread-safe: admission and drain use a single monitor on the queue.
 */
internal class ActorMailbox(
    private val capacity: Int,
) {
    init {
        require(capacity > 0) { "Mailbox capacity must be positive" }
    }

    private val lock = Any()
    private val queue: ArrayDeque<ActorEventEnvelope> = ArrayDeque(capacity)
    private var closed = false
    private var retired = false
    private var accepting = false
    private val depth = AtomicLong(0L)

    /**
     * Admit one event into the mailbox.
     *
     * Returns [ActorMailboxResult.Admitted] if the event was enqueued in FIFO
     * order, [ActorMailboxResult.Busy] if the mailbox is full, and the event
     * is never started later, [ActorMailboxResult.NotReady] if the actor is not
     * yet ready to accept ordinary events, or [ActorMailboxResult.Closed] if
     * the mailbox has been retired or closed.
     */
    fun admit(envelope: ActorEventEnvelope): ActorMailboxResult {
        synchronized(lock) {
            if (closed || retired) return ActorMailboxResult.Closed
            if (!accepting) return ActorMailboxResult.NotReady
            if (queue.size >= capacity) return ActorMailboxResult.Busy
            queue.addLast(envelope)
            depth.incrementAndGet()
            return ActorMailboxResult.Admitted
        }
    }

    /**
     * Open the mailbox for admission. Called when the actor reaches [ActorLifecyclePhase.LIVE].
     */
    fun open() {
        synchronized(lock) {
            accepting = true
        }
    }

    /**
     * Stop admission immediately. Queued events remain for the scheduler to
     * drain; new events are rejected as [ActorMailboxResult.Closed].
     */
    fun stopAdmission() {
        synchronized(lock) {
            accepting = false
        }
    }

    /**
     * Retire the mailbox: admission stops and all subsequent admissions
     * return [ActorMailboxResult.Closed] even if the mailbox was previously
     * accepting. Queued events are invalidated.
     */
    fun retire() {
        synchronized(lock) {
            retired = true
            accepting = false
        }
    }

    /**
     * Invalidate all queued events. Called during retirement to prevent
     * queued work from starting its Lua slice.
     */
    fun invalidateQueued(): List<ActorEventEnvelope> {
        synchronized(lock) {
            accepting = false
            val drained = queue.toList()
            queue.clear()
            return drained
        }
    }

    /**
     * Poll the next event in FIFO order. Returns null if the queue is empty.
     */
    fun poll(): ActorEventEnvelope? {
        synchronized(lock) {
            if (queue.isEmpty()) return null
            return queue.removeFirst()
        }
    }

    /**
     * Close the mailbox terminally. All subsequent admissions are rejected as
     * [ActorMailboxResult.Closed].
     */
    fun close() {
        synchronized(lock) {
            closed = true
            accepting = false
            queue.clear()
        }
    }

    val isClosed: Boolean
        get() = synchronized(lock) { closed }

    val size: Int
        get() = synchronized(lock) { queue.size }

    /**
     * Whether the mailbox is accepting ordinary events.
     */
    val isAccepting: Boolean
        get() = synchronized(lock) { accepting && !closed }

    /**
     * Total events ever admitted (for diagnostics).
     */
    fun totalAdmitted(): Long = depth.get()
}

/**
 * Typed mailbox admission result.
 */
internal sealed class ActorMailboxResult {
    data object Admitted : ActorMailboxResult()
    data object Busy : ActorMailboxResult()
    data object NotReady : ActorMailboxResult()
    data object Closed : ActorMailboxResult()
    /** Envelope belongs to a foreign scope/generation; never enqueued. */
    data object InvalidOwner : ActorMailboxResult()
}