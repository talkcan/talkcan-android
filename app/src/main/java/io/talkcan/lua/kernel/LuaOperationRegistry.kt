package io.talkcan.lua.kernel

import io.talkcan.lua.LuaKernelOutcome

/**
 * Owner-thread registry of a single state's suspended host-operation coroutines
 * and their opaque operation tokens.
 *
 * Every entry is confined to the engine's owner thread behind
 * [LuaStateThreadGuard]; nothing here is thread-safe because only that thread
 * ever reads or writes it. The registry holds the Lua registry reference to each
 * suspended coroutine thread (an opaque integer, never a native pointer), the
 * mapping from each live operation token to its owning coroutine, a bounded
 * terminal-outcome cache so recent duplicate completions echo the exact cached
 * outcome, and a bounded eviction-tombstone set so duplicates whose cached
 * outcome has already been evicted resolve to a typed stale result instead of an
 * unknown-ownership one.
 *
 * Bounds: the terminal cache and the tombstone set are independently bounded;
 * evicting a terminal entry promotes its operation identity to a tombstone, and
 * evicting a tombstone forgets the identity entirely (a later completion then
 * reads as unknown). The live coroutine table is bounded so a runaway plugin
 * cannot accumulate unbounded suspended coroutines; saturation is reported to the
 * caller rather than growing without limit. [releaseAll] drops every reference
 * and clears every table on state close.
 */
internal class LuaOperationRegistry(
    private val terminalCacheCapacity: Int,
    private val tombstoneCapacity: Int,
    private val liveCapacity: Int,
) {

    /** One suspended coroutine and the operation token currently addressing it. */
    class SuspendedCoroutine(
        val coroutineId: Long,
        var threadRef: Int,
        var currentOperationId: Long,
        val schedulerContext: SchedulerContext,
    )

    /** Result of resolving an inbound operation token before any Lua entry. */
    sealed class Resolution {
        /** The token is live and may be resumed/cancelled exactly once. */
        class Live(val record: SuspendedCoroutine) : Resolution()

        /** The token already reached a terminal outcome; echo it exactly. */
        class Terminal(val outcome: LuaKernelOutcome) : Resolution()

        /** The token was real but its cached outcome was evicted, or it was
         *  superseded by a sequential yield; a completion is stale. */
        object Tombstoned : Resolution()

        /** The token was never minted by this state (or fully forgotten). */
        object Unknown : Resolution()
    }

    private val coroutines = LinkedHashMap<Long, SuspendedCoroutine>()
    private val liveOperations = LinkedHashMap<Long, Long>()
    private val terminalCache = LinkedHashMap<Long, LuaKernelOutcome>()
    private val tombstones = LinkedHashMap<Long, Unit>()

    /**
     * Resolve an inbound [operationId] to its current disposition without
     * entering Lua. Terminal duplicates and tombstones are answered from cache so
     * they never re-enter the interpreter.
     */
    fun resolve(operationId: Long): Resolution {
        terminalCache[operationId]?.let { return Resolution.Terminal(it) }
        if (tombstones.containsKey(operationId)) return Resolution.Tombstoned
        val coroutineId = liveOperations[operationId] ?: return Resolution.Unknown
        val record = coroutines[coroutineId] ?: return Resolution.Unknown
        if (record.currentOperationId != operationId) return Resolution.Unknown
        return Resolution.Live(record)
    }

    /**
     * Suspend a freshly created coroutine under a new operation token. Returns
     * false when the live table is saturated, leaving the caller responsible for
     * releasing [threadRef].
     */
    fun registerSuspension(
        coroutineId: Long,
        operationId: Long,
        threadRef: Int,
        schedulerContext: SchedulerContext,
    ): Boolean {
        if (coroutines.size >= liveCapacity && !coroutines.containsKey(coroutineId)) {
            return false
        }
        coroutines[coroutineId] =
            SuspendedCoroutine(coroutineId, threadRef, operationId, schedulerContext)
        liveOperations[operationId] = coroutineId
        return true
    }

    /**
     * Advance a still-suspended coroutine to a fresh [newOperationId] after a
     * sequential yield. The superseded token is tombstoned so a late completion
     * against it resolves to stale rather than unknown; the coroutine reference
     * is unchanged.
     */
    fun supersede(record: SuspendedCoroutine, newOperationId: Long) {
        val oldOperationId = record.currentOperationId
        if (liveOperations.remove(oldOperationId) != null) recordTombstone(oldOperationId)
        record.currentOperationId = newOperationId
        liveOperations[newOperationId] = record.coroutineId
    }

    /**
     * Record a terminal outcome for the coroutine's current operation token,
     * removing it from the live tables and caching the outcome so an exact
     * duplicate completion can be echoed. The caller releases the thread
     * reference; the registry only owns identity and bookkeeping.
     */
    fun completeTerminal(record: SuspendedCoroutine, outcome: LuaKernelOutcome) {
        val operationId = record.currentOperationId
        liveOperations.remove(operationId)
        coroutines.remove(record.coroutineId)
        cacheTerminal(operationId, outcome)
    }

    /** Snapshot live records for owner-thread terminalization. */
    fun liveRecords(): List<SuspendedCoroutine> = coroutines.values.toList()

    /** Release every coroutine reference through [releaseRef] and clear all tables. */
    fun releaseAll(releaseRef: (Int) -> Unit) {
        for (record in coroutines.values) {
            releaseRef(record.threadRef)
        }
        coroutines.clear()
        liveOperations.clear()
        terminalCache.clear()
        tombstones.clear()
    }

    private fun cacheTerminal(operationId: Long, outcome: LuaKernelOutcome) {
        terminalCache[operationId] = outcome
        while (terminalCache.size > terminalCacheCapacity) {
            val eldest = terminalCache.keys.iterator().next()
            terminalCache.remove(eldest)
            recordTombstone(eldest)
        }
    }

    private fun recordTombstone(operationId: Long) {
        tombstones[operationId] = Unit
        while (tombstones.size > tombstoneCapacity) {
            tombstones.remove(tombstones.keys.iterator().next())
        }
    }
}
