package io.talkcan.work

import kotlinx.coroutines.CompletableDeferred

/**
 * Coroutine work coordinator for the durable work store.
 *
 * `receive` suspends on a per-partition waiter instead of polling or retaining an operating
 * system thread; the store's mutation hook wakes waiters exactly when claimability can have
 * changed, and waiter departure propagates the wake to the next FIFO waiter so no claimable item
 * is stranded.  The coordinator publishes only generic queue metadata: identity, phase, counts,
 * and terminal class — never payloads, effect keys/results, or ledger internals.
 */
class DurableWorkCoordinator(
    private val store: DurableWorkStore,
    private val maxConcurrentReceivesPerQueue: Int = 4,
) : DurableWorkStoreListener {
    private val lock = Any()
    private val waiters = HashMap<WorkQueuePartition, ArrayDeque<Waiter>>()
    private val closedPartitions = HashSet<WorkQueuePartition>()
    private var closedAll = false

    init {
        require(maxConcurrentReceivesPerQueue > 0) { "maxConcurrentReceivesPerQueue must be positive" }
        store.listener = this
    }

    override fun onQueueMutated(partition: WorkQueuePartition) {
        wakeHead(partition)
    }

    /**
     * Waits for and claims the earliest safe nonterminal item in FIFO order without polling.
     * Returns [WorkReceiveOutcome.Closed] when the partition or the whole coordinator is closed,
     * [WorkStoreFailure.Busy] when too many receives already wait on the partition, and
     * [WorkStoreFailure.Corrupted] when the partition is isolated.
     */
    suspend fun receive(
        partition: WorkQueuePartition,
        holderToken: String,
        atMillis: Long,
    ): WorkStoreResult<WorkReceiveOutcome> {
        while (true) {
            val waiter = synchronized(lock) {
                if (closedAll || partition in closedPartitions) {
                    return WorkStoreResult.Success(WorkReceiveOutcome.Closed)
                }
                val deque = waiters.getOrPut(partition) { ArrayDeque() }
                if (deque.size >= maxConcurrentReceivesPerQueue) {
                    return WorkStoreResult.Failure(WorkStoreFailure.Busy)
                }
                Waiter().also { deque.addLast(it) }
            }
            try {
                when (val claim = store.claimNext(partition, holderToken, atMillis)) {
                    is WorkStoreResult.Failure -> return claim
                    is WorkStoreResult.Success -> {
                        val value = claim.value
                        if (value != null) {
                            return WorkStoreResult.Success(WorkReceiveOutcome.Claimed(value))
                        }
                        if (store.projection(partition).availability == WorkQueueAvailability.CORRUPTED) {
                            return WorkStoreResult.Failure(
                                WorkStoreFailure.Corrupted("partition=${partition.queueId.value}"),
                            )
                        }
                    }
                }
                // Suspend without polling: the store hook or a departing waiter resumes us.
                waiter.signal.await()
            } finally {
                unregister(partition, waiter)
            }
        }
    }

    /** Closes one partition's generation: every waiting receive wakes and returns Closed. */
    fun close(partition: WorkQueuePartition) {
        synchronized(lock) {
            closedPartitions += partition
            waiters[partition]?.forEach { it.signal.complete(Unit) }
        }
    }

    /** Closes every partition; later receives return Closed immediately. */
    fun closeAll() {
        synchronized(lock) {
            closedAll = true
            waiters.values.forEach { deque -> deque.forEach { it.signal.complete(Unit) } }
        }
    }

    fun isClosed(partition: WorkQueuePartition): Boolean = synchronized(lock) {
        closedAll || partition in closedPartitions
    }

    fun waitingCount(partition: WorkQueuePartition): Int = synchronized(lock) {
        waiters[partition]?.size ?: 0
    }

    /** Generic metadata-only projection for channel snapshots. */
    fun projection(partition: WorkQueuePartition): WorkQueueProjection = store.projection(partition)

    /** Generic metadata-only projection for every queue of one instance. */
    fun instanceProjection(instanceId: WorkInstanceId): List<WorkQueueProjection> =
        store.instanceProjection(instanceId)

    private fun wakeHead(partition: WorkQueuePartition) {
        synchronized(lock) {
            waiters[partition]?.firstOrNull()?.signal?.complete(Unit)
        }
    }

    private fun unregister(partition: WorkQueuePartition, waiter: Waiter) {
        val hasNext = synchronized(lock) {
            val deque = waiters[partition]
            deque?.remove(waiter)
            if (deque.isNullOrEmpty()) {
                waiters.remove(partition)
                false
            } else {
                true
            }
        }
        if (hasNext) {
            // Propagate: the next FIFO waiter must re-check claimability.
            wakeHead(partition)
        }
    }

    private class Waiter {
        val signal = CompletableDeferred<Unit>()
    }
}

/** Outcome of a coordinator receive. */
sealed interface WorkReceiveOutcome {
    /** One job claimed in FIFO order; the claim carries the detached opaque payload. */
    data class Claimed(val claim: WorkClaim) : WorkReceiveOutcome

    /** The partition or coordinator is closed for this generation. */
    data object Closed : WorkReceiveOutcome
}
