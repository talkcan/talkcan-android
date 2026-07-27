package io.talkcan.service

import io.talkcan.channel.capability.AgentOperationContext
import io.talkcan.channel.capability.DeferredAudioPlaybackCapability
import io.talkcan.channel.capability.OpaqueAudioOperation
import io.talkcan.channel.capability.dispose
import io.talkcan.channel.capability.generationOf
import io.talkcan.channel.capability.retainedBytesOf
import io.talkcan.model.DelayedPlaybackFailureReason
import io.talkcan.model.DelayedPlaybackOperationId
import io.talkcan.model.DelayedPlaybackOutcome
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock






sealed interface DelayedPlaybackAudioResult {
    data object Busy : DelayedPlaybackAudioResult
    data object Completed : DelayedPlaybackAudioResult
    data object ExplicitlySkipped : DelayedPlaybackAudioResult
    data object Interrupted : DelayedPlaybackAudioResult
    data object Cancelled : DelayedPlaybackAudioResult
    data class Failed(val reason: DelayedPlaybackFailureReason) : DelayedPlaybackAudioResult
}

/**
 * In-memory, selection-aware deferred playback of opaque pre-produced audio (e.g. Debug ECHO).
 * The artifact is never persisted: raw captured audio is not durable-text-regenerable. The host
 * retries after terminal cleanup using the same half-duplex admission and current-mode routing as
 * text-based delayed playback. Selection is re-evaluated at each admission boundary; if the channel
 * is no longer selected the artifact is discarded (not retried on a different channel).
 */
class DeferredAudioPlaybackCoordinator(
    private val scope: CoroutineScope,
    private val selectedChannel: suspend () -> String?,
    private val operationIsCurrent: suspend (AgentOperationContext) -> Boolean,
    private val audio: DeferredAudioPlaybackAudioPort,
    private val onStateChanged: suspend () -> Unit = {},
    private val nowMillis: () -> Long = { System.currentTimeMillis() },
    private val limits: DeferredAudioPlaybackCoordinator.Limits = DeferredAudioPlaybackCoordinator.Limits.DEFAULT,
    private val processQuota: DeferredAudioPlaybackCoordinator.ProcessQuota =
        DeferredAudioPlaybackCoordinator.ProcessQuota.DEFAULT,
) : DeferredAudioPlaybackCapability, GenerationCapabilityResource {
    private val mutex = Mutex()
    private val pending = mutableListOf<DeferredAudioEntry>()
    private val wakeJobs = mutableMapOf<DelayedPlaybackOperationId, Job>()
    private var pumpJob: Job? = null
    @Volatile private var pumpRequested = false
    @Volatile private var closed = false
    private var activeEntry: DeferredAudioEntry? = null
    private val revokedGenerations = java.util.concurrent.ConcurrentHashMap.newKeySet<Pair<String, io.talkcan.channel.capability.RuntimeGeneration>>()

    // Per-instance quota counters (this coordinator only).
    private var instanceEntries: Int = 0
    private var instanceBytes: Long = 0L
    // Per-channel-instance quota counters, keyed by stable channel ID.
    private val channelEntries = mutableMapOf<String, Int>()
    private val channelBytes = mutableMapOf<String, Long>()
    // Per-runtime-generation quota counters; RuntimeGeneration is process-unique.
    private val generationEntries = mutableMapOf<io.talkcan.channel.capability.RuntimeGeneration, Int>()
    private val generationBytes = mutableMapOf<io.talkcan.channel.capability.RuntimeGeneration, Long>()

    private fun isRevoked(channelInstanceId: String, generation: io.talkcan.channel.capability.RuntimeGeneration): Boolean =
        revokedGenerations.contains(channelInstanceId to generation)

    private fun isAuthorized(entry: DeferredAudioEntry): Boolean =
        !closed && !isRevoked(entry.channelInstanceId, entry.quotaGeneration)
    /** Finite positive limits for this deferred playback coordinator instance. */
    data class Limits(
        val maxEntriesPerInstance: Int,
        val maxBytesPerInstance: Long,
        val maxEntriesPerGeneration: Int,
        val maxBytesPerGeneration: Long,
    ) {
        init {
            require(maxEntriesPerInstance > 0) { "maxEntriesPerInstance must be positive: $maxEntriesPerInstance" }
            require(maxBytesPerInstance > 0L) { "maxBytesPerInstance must be positive: $maxBytesPerInstance" }
            require(maxEntriesPerGeneration > 0) { "maxEntriesPerGeneration must be positive: $maxEntriesPerGeneration" }
            require(maxBytesPerGeneration > 0L) { "maxBytesPerGeneration must be positive: $maxBytesPerGeneration" }
        }

        companion object {
            val DEFAULT = Limits(
                maxEntriesPerInstance = 32,
                maxBytesPerInstance = 8L * 1024 * 1024,
                maxEntriesPerGeneration = 32,
                maxBytesPerGeneration = 8L * 1024 * 1024,
            )
        }
    }

    /** Shared process-wide deferred playback quota accountant. */
    class ProcessQuota(
        maxEntries: Int,
        maxBytes: Long,
    ) {
        init {
            require(maxEntries > 0) { "maxEntries must be positive: $maxEntries" }
            require(maxBytes > 0L) { "maxBytes must be positive: $maxBytes" }
        }

        private val maxEntries = maxEntries
        private val maxBytes = maxBytes
        private var entriesUsed: Int = 0
        private var bytesUsed: Long = 0L

        @Synchronized
        internal fun tryReserve(entries: Int, bytes: Long): Boolean {
            if (entries < 0 || bytes < 0L) return false
            val nextEntries = entriesUsed + entries
            val nextBytes = bytesUsed + bytes
            if (nextEntries < entriesUsed || nextBytes < bytesUsed) return false
            if (nextEntries > maxEntries) return false
            if (nextBytes > maxBytes) return false
            entriesUsed = nextEntries
            bytesUsed = nextBytes
            return true
        }

        @Synchronized
        internal fun forceReserve(entries: Int, bytes: Long) {
            entriesUsed += entries
            bytesUsed += bytes
        }

        @Synchronized
        internal fun release(entries: Int, bytes: Long) {
            entriesUsed -= entries
            bytesUsed -= bytes
        }

        @Synchronized
        fun liveEntries(): Int = entriesUsed

        @Synchronized
        fun retainedBytes(): Long = bytesUsed

        companion object {
            val DEFAULT = ProcessQuota(maxEntries = 256, maxBytes = 64L * 1024 * 1024)
        }
    }

    data class ChannelAccounting(val entries: Int, val bytes: Long)

    internal data class Accounting(
        val liveEntries: Int,
        val retainedBytes: Long,
        val perChannel: Map<String, ChannelAccounting>,
    )

    /** Snapshot of this coordinator's current quota accounting. */
    internal fun accounting(): Accounting = synchronized(pending) {
        Accounting(
            liveEntries = instanceEntries,
            retainedBytes = instanceBytes,
            perChannel = channelEntries.mapValues { (channel, entries) ->
                ChannelAccounting(entries = entries, bytes = channelBytes[channel] ?: 0L)
            },
        )
    }

    private val _pendingCounts = MutableStateFlow<Map<String, Int>>(emptyMap())
    /**
     * Read-only per-channel deferred opaque-audio pending counts, keyed by stable channel ID.
     * Zero entries are omitted. Updates are published atomically after queue add/remove/discard/
     * close. Busy/interrupted/cancelled/failed entries remain counted; only completion, explicit
     * skip, selection-discard, or close decrement/clear. Collecting this flow never schedules a
     * pump, so projecting counts cannot wake the same queue.
     */
    val pendingCounts: StateFlow<Map<String, Int>> = _pendingCounts.asStateFlow()

    private fun publishPendingCounts() {
        val now = nowMillis()
        _pendingCounts.value = synchronized(pending) {
            pending.asSequence()
                .filter { it.eligibleAtMillis <= now }
                .groupingBy { it.channelInstanceId }
                .eachCount()
        }
    }

    override suspend fun scheduleAudio(
        context: AgentOperationContext,
        audio: OpaqueAudioOperation,
        eligibilityDelayMillis: Long,
    ): DelayedPlaybackOutcome {
        if (!operationIsCurrent(context)) {
            dispose(audio)
            return DelayedPlaybackOutcome.Stale
        }
        if (isRevoked(context.scope.channelInstanceId, context.scope.runtimeGeneration)) {
            dispose(audio)
            return DelayedPlaybackOutcome.Stale
        }
        val operationGeneration = generationOf(audio)
        if (operationGeneration != null && isRevoked(context.scope.channelInstanceId, operationGeneration)) {
            dispose(audio)
            return DelayedPlaybackOutcome.Stale
        }
        if (operationGeneration != null && operationGeneration != context.scope.runtimeGeneration) {
            dispose(audio)
            return DelayedPlaybackOutcome.Stale
        }
        if (eligibilityDelayMillis < 0L) {
            return DelayedPlaybackOutcome.Failed(DelayedPlaybackFailureReason.HOST_FAILURE)
        }

        val channel = context.scope.channelInstanceId
        val generation = context.scope.runtimeGeneration
        val generationKey = generation
        val retainedBytes = retainedBytesOf(audio)
        if (retainedBytes < 0L) {
            return DelayedPlaybackOutcome.Busy
        }

        val now = nowMillis()
        val entry = DeferredAudioEntry(
            operationId = DelayedPlaybackOperationId(UUID.randomUUID().toString()),
            channelInstanceId = channel,
            audio = audio,
            generation = operationGeneration,
            quotaGeneration = generation,
            eligibleAtMillis = saturatingAdd(now, eligibilityDelayMillis),
            retainedBytes = retainedBytes,
        )

        // Admission preflights every scope and reserves atomically before the
        // entry is visible to the queue. No sibling can be evicted and no
        // caller-owned artifact is consumed until this succeeds.
        val admitted: Boolean? = synchronized(pending) {
            if (closed || isRevoked(channel, generation)) {
                null
            } else {
                val nextInstanceEntries = instanceEntries + 1
                val nextInstanceBytes = instanceBytes + retainedBytes
                val nextChannelEntries = (channelEntries[channel] ?: 0) + 1
                val nextChannelBytes = (channelBytes[channel] ?: 0L) + retainedBytes
                val nextGenerationEntries = (generationEntries[generationKey] ?: 0) + 1
                val nextGenerationBytes = (generationBytes[generationKey] ?: 0L) + retainedBytes

                val localWithinBounds =
                    nextInstanceEntries > instanceEntries &&
                        nextInstanceBytes >= instanceBytes &&
                        nextChannelEntries > (channelEntries[channel] ?: 0) &&
                        nextChannelEntries <= limits.maxEntriesPerInstance &&
                        nextChannelBytes >= (channelBytes[channel] ?: 0L) &&
                        nextChannelBytes <= limits.maxBytesPerInstance &&
                        nextGenerationEntries > (generationEntries[generationKey] ?: 0) &&
                        nextGenerationEntries <= limits.maxEntriesPerGeneration &&
                        nextGenerationBytes >= (generationBytes[generationKey] ?: 0L) &&
                        nextGenerationBytes <= limits.maxBytesPerGeneration
                if (!localWithinBounds || !processQuota.tryReserve(1, retainedBytes)) {
                    false
                } else {
                    instanceEntries = nextInstanceEntries
                    instanceBytes = nextInstanceBytes
                    channelEntries[channel] = nextChannelEntries
                    channelBytes[channel] = nextChannelBytes
                    generationEntries[generationKey] = nextGenerationEntries
                    generationBytes[generationKey] = nextGenerationBytes
                    pending.add(entry)
                    true
                }
            }
        }
        if (admitted == null) {
            dispose(audio)
            return DelayedPlaybackOutcome.Stale
        }
        if (!admitted) {
            return DelayedPlaybackOutcome.Busy
        }
        if (eligibilityDelayMillis > 0L) {
            scheduleEligibilityWake(entry)
        } else {
            publishPendingCounts()
            requestPump()
        }
        return DelayedPlaybackOutcome.Pending(entry.operationId)
    }

    /** Call after any host audio owner releases admission. */
    fun onAudioAvailable() {
        requestPump()
    }

    /** Call from the shared, deliberate channel-selection action. */
    fun onChannelSelected(channelInstanceId: String) {
        requestPump()
    }
    override suspend fun onGenerationTermination(
        identity: io.talkcan.channel.capability.CapabilityScopeIdentity,
        termination: io.talkcan.channel.capability.CapabilityLeaseTermination,
    ) {
        if (termination != io.talkcan.channel.capability.CapabilityLeaseTermination.REVOKED) return
        synchronized(revokedGenerations) {
            revokedGenerations += identity.channelInstanceId to identity.runtimeGeneration
        }
        val removed = synchronized(pending) {
            pending.filter {
                it.channelInstanceId == identity.channelInstanceId &&
                    it.quotaGeneration == identity.runtimeGeneration
            }.also { entries ->
                entries.forEach { pending.remove(it) }
                entries.forEach { entry ->
                    val channel = entry.channelInstanceId
                    val generationKey = entry.quotaGeneration
                    instanceEntries -= 1
                    instanceBytes -= entry.retainedBytes
                    val channelEntryCount = (channelEntries[channel] ?: 1) - 1
                    val channelByteCount = (channelBytes[channel] ?: entry.retainedBytes) - entry.retainedBytes
                    if (channelEntryCount <= 0) channelEntries.remove(channel) else channelEntries[channel] = channelEntryCount
                    if (channelByteCount <= 0L) channelBytes.remove(channel) else channelBytes[channel] = channelByteCount
                    val generationEntryCount = (generationEntries[generationKey] ?: 1) - 1
                    val generationByteCount = (generationBytes[generationKey] ?: entry.retainedBytes) - entry.retainedBytes
                    if (generationEntryCount <= 0) generationEntries.remove(generationKey) else generationEntries[generationKey] = generationEntryCount
                    if (generationByteCount <= 0L) generationBytes.remove(generationKey) else generationBytes[generationKey] = generationByteCount
                    processQuota.release(1, entry.retainedBytes)
                }
            }
        }
        removed.forEach { dispose(it.audio) }
        publishPendingCounts()
        // An in-flight predecessor may have been the pump's active entry. Wake the pump so
        // unaffected sibling/successor generations are still considered after it observes stale.
        if (removed.isNotEmpty()) requestPump()
    }

    fun close() {
        val removed = synchronized(pending) {
            closed = true
            pending.toList().also {
                pending.clear()
                wakeJobs.values.toList().also { jobs -> wakeJobs.clear(); jobs.forEach { it.cancel() } }
                it.forEach { entry -> processQuota.release(1, entry.retainedBytes) }
                clearAccounting()
            }
        }
        removed.forEach { dispose(it.audio) }
        pumpJob?.cancel()
        publishPendingCounts()
    }
    /** Remove one queued entry and release every scope exactly once. */
    private fun removeAndRelease(entry: DeferredAudioEntry): Boolean = synchronized(pending) {
        if (!isAuthorized(entry)) return@synchronized false
        if (!pending.remove(entry)) return@synchronized false
        val channel = entry.channelInstanceId
        val generationKey = entry.quotaGeneration
        instanceEntries -= 1
        instanceBytes -= entry.retainedBytes
        val channelEntryCount = (channelEntries[channel] ?: 1) - 1
        val channelByteCount = (channelBytes[channel] ?: entry.retainedBytes) - entry.retainedBytes
        if (channelEntryCount <= 0) channelEntries.remove(channel) else channelEntries[channel] = channelEntryCount
        if (channelByteCount <= 0L) channelBytes.remove(channel) else channelBytes[channel] = channelByteCount
        val generationEntryCount = (generationEntries[generationKey] ?: 1) - 1
        val generationByteCount = (generationBytes[generationKey] ?: entry.retainedBytes) - entry.retainedBytes
        if (generationEntryCount <= 0) generationEntries.remove(generationKey) else generationEntries[generationKey] = generationEntryCount
        if (generationByteCount <= 0L) generationBytes.remove(generationKey) else generationBytes[generationKey] = generationByteCount
        processQuota.release(1, entry.retainedBytes)
        true
    }

    /** Release all accounting, used by close after pending entries are detached. */
    private fun clearAccounting() {
        instanceEntries = 0
        instanceBytes = 0L
        channelEntries.clear()
        channelBytes.clear()
        generationEntries.clear()
        generationBytes.clear()
    }


    /**
     * Wake at the entry's absolute host eligibility time. The injected clock is authoritative for
     * eligibility, while coroutine delay only provides a wakeup. Rechecking the absolute deadline
     * prevents an early wake (clock adjustment) from losing the only retry and avoids real-time or
     * Lua-side sleeps.
     */
    private fun scheduleEligibilityWake(entry: DeferredAudioEntry) {
        val job = scope.launch {
            while (true) {
                val remaining = synchronized(pending) {
                    if (!pending.contains(entry)) return@launch
                    millisUntil(entry.eligibleAtMillis, nowMillis())
                }
                if (remaining <= 0L) break
                kotlinx.coroutines.delay(remaining)
            }
            synchronized(pending) { wakeJobs.remove(entry.operationId) }
            publishPendingCounts()
            requestPump()
        }
        synchronized(pending) { wakeJobs[entry.operationId] = job }
    }

    private fun saturatingAdd(base: Long, delta: Long): Long = try {
        Math.addExact(base, delta)
    } catch (_: ArithmeticException) {
        Long.MAX_VALUE
    }

    private fun millisUntil(deadline: Long, now: Long): Long = try {
        if (now >= deadline) 0L else Math.subtractExact(deadline, now)
    } catch (_: ArithmeticException) {
        Long.MAX_VALUE
    }

    private fun requestPump() {
        pumpRequested = true
        if (pumpJob?.isActive == true) return
        pumpJob = scope.launch {
            while (true) {
                pumpRequested = false
                val candidate = nextSelectedPendingEntry()
                val active = mutex.withLock {
                    if (activeEntry != null) null else candidate?.also { activeEntry = it }
                } ?: return@launch
                val completed = try {
                    deliver(active)
                } finally {
                    mutex.withLock { activeEntry = null }
                    onStateChanged()
                }
                if (!completed && !pumpRequested) return@launch
            }
        }
    }

    /**
     * Returns the FIFO head of the selected channel's pending entries iff it is eligible
     * (its host-side delay has elapsed). A not-yet-eligible head returns null so later
     * same-channel entries cannot overtake it; determinism is preserved by strict FIFO.
     */
    private suspend fun nextSelectedPendingEntry(): DeferredAudioEntry? {
        val selected = selectedChannel() ?: return null
        val now = nowMillis()
        return synchronized(pending) {
            val head = pending.firstOrNull { it.channelInstanceId == selected } ?: return@synchronized null
            if (now >= head.eligibleAtMillis) head else null
        }
    }

    private suspend fun deliver(entry: DeferredAudioEntry): Boolean {
        if (!isAuthorized(entry)) {
            if (removeAndRelease(entry)) dispose(entry.audio)
            publishPendingCounts()
            return false
        }
        // Selection is checked immediately before admission. If it changed after the candidate
        // snapshot, retain the entry at its channel FIFO head; never route it through the newly
        // selected channel and do not consume/dispose the still-authorized artifact.
        if (selectedChannel() != entry.channelInstanceId) {
            return false
        }
        if (!isAuthorized(entry)) {
            if (removeAndRelease(entry)) dispose(entry.audio)
            publishPendingCounts()
            return false
        }
        val result = try {
            audio.playOperationIfAdmitted(entry.channelInstanceId, entry.audio)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            return false
        }
        // A backend may complete after revocation/close. Do not interpret that completion as a
        // successful playback or release accounting a successor now owns.
        if (!isAuthorized(entry)) return false
        return when (result) {
            DelayedPlaybackAudioResult.Completed,
            DelayedPlaybackAudioResult.ExplicitlySkipped,
            -> {
                if (!removeAndRelease(entry)) return false
                dispose(entry.audio)
                publishPendingCounts()
                true
            }
            DelayedPlaybackAudioResult.Busy,
            DelayedPlaybackAudioResult.Interrupted,
            DelayedPlaybackAudioResult.Cancelled,
            is DelayedPlaybackAudioResult.Failed,
            -> false
        }
    }
}

private data class DeferredAudioEntry(
    val operationId: DelayedPlaybackOperationId,
    val channelInstanceId: String,
    val audio: OpaqueAudioOperation,
    val generation: io.talkcan.channel.capability.RuntimeGeneration?,
    val quotaGeneration: io.talkcan.channel.capability.RuntimeGeneration,
    val eligibleAtMillis: Long = 0L,
    val retainedBytes: Long = 0L,
)

/**
 * Host audio boundary for deferred opaque-audio playback. [playOperationIfAdmitted] MUST
 * serialize with PTT, announcements, and all other host audio work. It MUST resolve the
 * current route only after admission and before playback.
 */
interface DeferredAudioPlaybackAudioPort {
    suspend fun playOperationIfAdmitted(
        channelInstanceId: String,
        audio: OpaqueAudioOperation,
    ): DelayedPlaybackAudioResult
}
