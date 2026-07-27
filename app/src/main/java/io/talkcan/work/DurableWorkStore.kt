package io.talkcan.work

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/**
 * Host-owned, crash-safe, provider-neutral durable work store.
 *
 * Each queue partition (repository, channel instance, declared queue) is one independently
 * persisted JSON document and one transaction boundary; the work epoch lives inside that
 * partition.  Payloads and effect results are opaque normalized [WorkValue]s that the store never
 * interprets.  Terminal completion purges payload and effect bodies and retains only bounded
 * [WorkTombstone]s.  A corrupted partition document is isolated and fails closed without merging
 * or interpreting partial data; sibling partitions remain operational.
 */
class DurableWorkStore(
    private val directory: File,
    private val bounds: DurableWorkBounds = DurableWorkBounds(),
    private val idGenerator: () -> String = { UUID.randomUUID().toString() },
) {
    private val lock = Any()
    private val partitions = LinkedHashMap<WorkQueuePartition, QueueState>()
    private val corruptedFiles = LinkedHashSet<String>()

    /** Change hook used by the coroutine coordinator; invoked outside the store lock. */
    @Volatile
    var listener: DurableWorkStoreListener? = null

    /**
     * Strictly decodes every partition document under [directory].  Documents that fail strict
     * decoding or post-decode validation are recorded as corrupted and isolated; they are never
     * merged with healthy data.
     */
    fun load(): WorkStoreResult<WorkStoreLoadReport> = synchronized(lock) {
        partitions.clear()
        corruptedFiles.clear()
        if (directory.exists()) {
            for (file in workFiles(directory)) {
                val relative = file.relativeTo(directory).path
                val state = try {
                    decodeQueueState(file.readText()).also {
                        validateQueueState(it)
                        require(it.tombstones.size <= bounds.maxTombstonesPerQueue) { "Tombstone quota exceeded" }
                        require(it.works.size <= bounds.maxRecoveryItemsPerQueue) { "Recovery item quota exceeded" }
                    }
                } catch (error: Exception) {
                    corruptedFiles += relative
                    continue
                }
                partitions[state.record.partition] = state
            }
        }
        WorkStoreResult.Success(
            WorkStoreLoadReport(
                partitions = partitions.keys.toList(),
                corrupted = corruptedFiles.toList(),
            ),
        )
    }

    fun partitions(): List<WorkQueuePartition> = synchronized(lock) { partitions.keys.toList() }

    fun queueRecord(partition: WorkQueuePartition): WorkStoreResult<QueueRecord> = synchronized(lock) {
        when (val state = requireState(partition)) {
            is WorkStoreResult.Failure -> state
            is WorkStoreResult.Success -> WorkStoreResult.Success(state.value.record)
        }
    }

    fun snapshot(partition: WorkQueuePartition): WorkStoreResult<WorkQueueSnapshot> = synchronized(lock) {
        when (val state = requireState(partition)) {
            is WorkStoreResult.Failure -> state
            is WorkStoreResult.Success -> WorkStoreResult.Success(
                WorkQueueSnapshot(
                    record = state.value.record,
                    works = state.value.works.values.map { it.copy() }.sortedBy { it.sequence },
                    effects = state.value.effects.values.map { it.copy() },
                    tombstones = state.value.tombstones.map { it.copy() },
                ),
            )
        }
    }

    // -----------------------------------------------------------------------
    // Submission (11.3)
    // -----------------------------------------------------------------------

    /**
     * Durably commits one FIFO item before returning success.  Validates the complete payload and
     * every applicable quota before any mutation; a failed submission creates no partially visible
     * item and leaves existing items and sibling queues unchanged.
     */
    fun submit(
        partition: WorkQueuePartition,
        payload: WorkValue,
        atMillis: Long,
    ): WorkStoreResult<WorkRecord> {
        val mutated = transact(partition) { state ->
            validateValue(payload, "payload")?.let {
                return@transact WorkStoreResult.Failure(WorkStoreFailure.InvalidValue("payload:$it"))
            }
            if (encodeValue(payload).toString().utf8Size() > bounds.maxPayloadBytes) {
                return@transact WorkStoreResult.Failure(WorkStoreFailure.TooLarge("payload"))
            }
            if (state.works.size >= bounds.maxNonterminalItemsPerQueue) {
                return@transact WorkStoreResult.Failure(WorkStoreFailure.Busy)
            }
            if (instanceQueueCount(partition.instanceId) - 1 >= bounds.maxQueuesPerInstance) {
                return@transact WorkStoreResult.Failure(WorkStoreFailure.Busy)
            }
            val record = state.record
            val work = WorkRecord(
                id = WorkId(idGenerator()),
                partition = partition,
                sequence = record.nextSequence,
                epoch = record.epoch,
                payload = payload,
                state = WorkState.QUEUED,
                leaseHolder = null,
                submittedAtMillis = atMillis,
                claimedAtMillis = null,
            )
            state.works[work.id] = work
            state.record = record.copy(nextSequence = record.nextSequence + 1L)
            WorkStoreResult.Success(work)
        }
        return mutated
    }

    // -----------------------------------------------------------------------
    // Claims (11.4)
    // -----------------------------------------------------------------------

    /**
     * Claims the earliest safe nonterminal item in FIFO order.  Returns `null` when the queue is
     * empty or another job is already active; at most one item is active per partition.
     */
    fun claimNext(
        partition: WorkQueuePartition,
        holderToken: String,
        atMillis: Long,
    ): WorkStoreResult<WorkClaim?> {
        requireToken(holderToken)
        return when (val result = transact(partition, createIfMissing = false, notifyOnChange = false) { state ->
            if (state.works.values.any { it.state == WorkState.CLAIMED }) {
                return@transact WorkStoreResult.Success(null)
            }
            val head = state.works.values
                .filter { it.state == WorkState.QUEUED && it.epoch == state.record.epoch }
                .minByOrNull { it.sequence }
                ?: return@transact WorkStoreResult.Success(null)
            val claimed = head.copy(state = WorkState.CLAIMED, leaseHolder = holderToken, claimedAtMillis = atMillis)
            state.works[claimed.id] = claimed
            WorkStoreResult.Success(WorkClaim(claimed, claimed.payload))
        }) {
            is WorkStoreResult.Failure ->
                if (result.failure is WorkStoreFailure.NotFound) WorkStoreResult.Success(null) else result
            is WorkStoreResult.Success -> result
        }
    }

    /**
     * Safely releases a claim held by [holderToken].  A claim with no started effect returns to
     * QUEUED in its original FIFO position; a claim with a started-but-uncommitted effect is
     * terminally indeterminate because the external effect may have begun.
     */
    fun releaseClaim(
        workId: WorkId,
        holderToken: String,
        atMillis: Long,
    ): WorkStoreResult<WorkClaimRelease> {
        requireToken(holderToken)
        return transactForWork(workId) { state, work ->
            if (work.state != WorkState.CLAIMED) {
                return@transactForWork WorkStoreResult.Failure(
                    WorkStoreFailure.Conflict("work=${workId.value} phase=${work.state}"),
                )
            }
            if (work.leaseHolder != holderToken) {
                return@transactForWork WorkStoreResult.Failure(
                    WorkStoreFailure.Stale("work=${workId.value}"),
                )
            }
            if (state.effects.values.any { it.workId == workId && it.state == WorkEffectState.STARTED }) {
                when (val terminal = terminalizeInternal(state, work, WorkTerminalClass.INDETERMINATE, "release_after_start", atMillis, playbackHandedOff = false)) {
                    is WorkStoreResult.Failure -> terminal
                    is WorkStoreResult.Success -> WorkStoreResult.Success(WorkClaimRelease.Indeterminate(terminal.value))
                }
            } else {
                val released = work.copy(state = WorkState.QUEUED, leaseHolder = null, claimedAtMillis = null)
                state.works[workId] = released
                WorkStoreResult.Success(WorkClaimRelease.Released(released))
            }
        }
    }

    /** Returns a detached copy of the committed payload; Lua/host mutation cannot alter the record. */
    fun payload(workId: WorkId): WorkStoreResult<WorkValue> = synchronized(lock) {
        val state = stateForWork(workId) ?: return@synchronized WorkStoreResult.Failure(
            WorkStoreFailure.NotFound("work=${workId.value}"),
        )
        val work = state.works[workId] ?: return@synchronized WorkStoreResult.Failure(
            WorkStoreFailure.NotFound("work=${workId.value}"),
        )
        WorkStoreResult.Success(work.payload)
    }

    // -----------------------------------------------------------------------
    // Effects (11.5)
    // -----------------------------------------------------------------------

    /**
     * Atomically reserves and marks one effect started before the caller invokes its function.
     * A committed same-key effect with the same fingerprint replays its stored result; a different
     * fingerprint, a started/ineterminate duplicate, or a nested/concurrent begin is rejected.
     */
    fun beginEffect(
        workId: WorkId,
        holderToken: String,
        key: String,
        argumentFingerprint: String,
    ): WorkStoreResult<WorkEffectBegin> {
        requireToken(holderToken)
        if (key.isEmpty()) {
            return WorkStoreResult.Failure(WorkStoreFailure.InvalidValue("effectKey"))
        }
        if (key.utf8Size() > bounds.maxEffectKeyBytes) {
            return WorkStoreResult.Failure(WorkStoreFailure.TooLarge("effectKey"))
        }
        if (argumentFingerprint.utf8Size() > bounds.maxEffectFingerprintBytes) {
            return WorkStoreResult.Failure(WorkStoreFailure.TooLarge("effectFingerprint"))
        }
        return transactForWork(workId) { state, work ->
            if (work.state != WorkState.CLAIMED || work.leaseHolder != holderToken) {
                return@transactForWork WorkStoreResult.Failure(WorkStoreFailure.Stale("work=${workId.value}"))
            }
            val existing = state.effects[workId to key]
            if (existing != null) {
                return@transactForWork when {
                    existing.state == WorkEffectState.COMMITTED && existing.argumentFingerprint == argumentFingerprint ->
                        WorkStoreResult.Success(WorkEffectBegin.Replay(checkNotNull(existing.result)))
                    else -> WorkStoreResult.Failure(
                        WorkStoreFailure.Conflict("work=${workId.value} effect=$key state=${existing.state}"),
                    )
                }
            }
            if (state.effects.values.count { it.workId == workId } >= bounds.maxEffectsPerWork) {
                return@transactForWork WorkStoreResult.Failure(WorkStoreFailure.Busy)
            }
            val effect = EffectRecord(
                workId = workId,
                key = key,
                argumentFingerprint = argumentFingerprint,
                state = WorkEffectState.STARTED,
                result = null,
            )
            state.effects[workId to key] = effect
            WorkStoreResult.Success(WorkEffectBegin.Started(effect))
        }
    }

    /**
     * Commits exactly one terminal result for a started effect before the caller returns it.
     * Committing an identical result again is idempotent; a different result is a conflict.
     */
    fun commitEffect(
        workId: WorkId,
        holderToken: String,
        key: String,
        result: WorkEffectResult,
    ): WorkStoreResult<EffectRecord> {
        requireToken(holderToken)
        val resultValue = when (result) {
            is WorkEffectResult.Success -> result.value
            is WorkEffectResult.Error -> result.value
        }
        validateValue(resultValue, "effectResult")
            ?.let { return WorkStoreResult.Failure(WorkStoreFailure.InvalidValue("effectResult:$it")) }
        if (encodeValue(resultValue).toString().utf8Size() > bounds.maxEffectResultBytes) {
            return WorkStoreResult.Failure(WorkStoreFailure.TooLarge("effectResult"))
        }
        return transactForWork(workId) { state, work ->
            if (work.state != WorkState.CLAIMED || work.leaseHolder != holderToken) {
                return@transactForWork WorkStoreResult.Failure(WorkStoreFailure.Stale("work=${workId.value}"))
            }
            val existing = state.effects[workId to key]
                ?: return@transactForWork WorkStoreResult.Failure(
                    WorkStoreFailure.NotFound("work=${workId.value} effect=$key"),
                )
            when (existing.state) {
                WorkEffectState.STARTED -> {
                    val committed = existing.copy(state = WorkEffectState.COMMITTED, result = result)
                    state.effects[workId to key] = committed
                    WorkStoreResult.Success(committed)
                }
                WorkEffectState.COMMITTED -> if (existing.result == result) {
                    WorkStoreResult.Success(existing)
                } else {
                    WorkStoreResult.Failure(WorkStoreFailure.Conflict("work=${workId.value} effect=$key"))
                }
                WorkEffectState.INDETERMINATE -> WorkStoreResult.Failure(
                    WorkStoreFailure.Conflict("work=${workId.value} effect=$key state=INDETERMINATE"),
                )
            }
        }
    }

    fun effects(workId: WorkId): WorkStoreResult<List<EffectRecord>> = synchronized(lock) {
        val state = stateForWork(workId) ?: return@synchronized WorkStoreResult.Failure(
            WorkStoreFailure.NotFound("work=${workId.value}"),
        )
        WorkStoreResult.Success(state.effects.values.filter { it.workId == workId }.map { it.copy() })
    }

    // -----------------------------------------------------------------------
    // Terminal transitions (11.6)
    // -----------------------------------------------------------------------

    /** Exactly-once terminal completion; purges payload and effect bodies and retains a tombstone. */
    fun completeWork(
        workId: WorkId,
        holderToken: String,
        atMillis: Long,
        playbackHandedOff: Boolean,
    ): WorkStoreResult<WorkTombstone> {
        requireToken(holderToken)
        return terminalizeById(workId, WorkTerminalClass.COMPLETED, null, atMillis, playbackHandedOff, holderToken)
    }

    /** Exactly-once terminal failure with a bounded normalized reason tag; bodies are purged. */
    fun failWork(
        workId: WorkId,
        holderToken: String,
        reasonTag: String,
        atMillis: Long,
        playbackHandedOff: Boolean,
    ): WorkStoreResult<WorkTombstone> {
        requireToken(holderToken)
        validateReasonTag(reasonTag)?.let { return it }
        return terminalizeById(workId, WorkTerminalClass.FAILED, reasonTag, atMillis, playbackHandedOff, holderToken)
    }

    /**
     * Host-driven terminalization used by reconciliation, epoch retirement, and shutdown.
     * Exactly one terminal outcome per work: repeating an identical terminal request is
     * idempotent; a different outcome is a conflict.
     */
    fun markTerminal(
        workId: WorkId,
        terminalClass: WorkTerminalClass,
        reasonTag: String?,
        atMillis: Long,
    ): WorkStoreResult<WorkTombstone> {
        reasonTag?.let { validateReasonTag(it)?.let { failure -> return failure } }
        return terminalizeById(workId, terminalClass, reasonTag, atMillis, playbackHandedOff = false, requiredHolder = null)
    }

    fun tombstones(partition: WorkQueuePartition): WorkStoreResult<List<WorkTombstone>> = synchronized(lock) {
        when (val state = requireState(partition)) {
            is WorkStoreResult.Failure -> state
            is WorkStoreResult.Success -> WorkStoreResult.Success(state.value.tombstones.map { it.copy() })
        }
    }

    // -----------------------------------------------------------------------
    // Epochs (11.7)
    // -----------------------------------------------------------------------

    /**
     * Preserves the epoch for an unchanged restart: the supplied binding fingerprint must match
     * the stored one (or initialize it), and eligible claims are safely reclaimed in place.
     */
    fun preserveEpoch(
        partition: WorkQueuePartition,
        bindingFingerprint: String,
        atMillis: Long,
    ): WorkStoreResult<WorkEpoch> {
        if (bindingFingerprint.utf8Size() > bounds.maxFingerprintBytes) {
            return WorkStoreResult.Failure(WorkStoreFailure.TooLarge("revisionFingerprint"))
        }
        return when (val result = transact(partition, createIfMissing = false) { state ->
            val stored = state.record.revisionFingerprint
            if (stored != null && stored != bindingFingerprint) {
                return@transact WorkStoreResult.Failure(
                    WorkStoreFailure.Stale("partition=${partition.queueId.value} binding"),
                )
            }
            if (stored == null) {
                state.record = state.record.copy(revisionFingerprint = bindingFingerprint)
            }
            reclaimClaimsInternal(state, atMillis)
            WorkStoreResult.Success(state.record.epoch)
        }) {
            is WorkStoreResult.Failure ->
                if (result.failure is WorkStoreFailure.NotFound) WorkStoreResult.Success(WorkEpoch(0L)) else result
            is WorkStoreResult.Success -> result
        }
    }

    /**
     * Retires the epoch for one partition: queued or claimed work with no started effect becomes
     * cancelled, started uncommitted work becomes indeterminate, bodies purge to tombstones, and
     * the successor epoch starts a fresh FIFO sequence under the new binding fingerprint.
     */
    fun retireEpoch(
        partition: WorkQueuePartition,
        cause: WorkEpochResetCause,
        bindingFingerprint: String,
        atMillis: Long,
    ): WorkStoreResult<WorkEpochRetirement> {
        if (bindingFingerprint.utf8Size() > bounds.maxFingerprintBytes) {
            return WorkStoreResult.Failure(WorkStoreFailure.TooLarge("revisionFingerprint"))
        }
        return when (val result = transact(partition, createIfMissing = false) { state ->
            WorkStoreResult.Success(retireInternal(state, cause, bindingFingerprint, atMillis))
        }) {
            is WorkStoreResult.Failure -> if (result.failure is WorkStoreFailure.NotFound) {
                WorkStoreResult.Success(
                    WorkEpochRetirement(partition, cause, WorkEpoch(0L), emptyList(), emptyList()),
                )
            } else {
                result
            }
            is WorkStoreResult.Success -> result
        }
    }

    /** Retires every queue partition of one channel instance, for example on configuration change. */
    fun retireInstance(
        instanceId: WorkInstanceId,
        cause: WorkEpochResetCause,
        bindingFingerprint: String,
        atMillis: Long,
    ): WorkStoreResult<List<WorkEpochRetirement>> {
        if (bindingFingerprint.utf8Size() > bounds.maxFingerprintBytes) {
            return WorkStoreResult.Failure(WorkStoreFailure.TooLarge("revisionFingerprint"))
        }
        val targets = synchronized(lock) {
            partitions.keys.filter { it.instanceId == instanceId }
        }
        val retired = mutableListOf<WorkEpochRetirement>()
        for (partition in targets) {
            when (val result = transact(partition) { state ->
                WorkStoreResult.Success(retireInternal(state, cause, bindingFingerprint, atMillis))
            }) {
                is WorkStoreResult.Failure -> return result
                is WorkStoreResult.Success -> retired += result.value
            }
        }
        return WorkStoreResult.Success(retired)
    }

    /**
     * Orderly shutdown: claims without a started effect become safely reclaimable queued work
     * under the preserved epoch; claims with a started uncommitted effect become indeterminate.
     */
    fun prepareForShutdown(atMillis: Long): WorkStoreResult<WorkRecoveryPlan> {
        val targets = synchronized(lock) { partitions.keys.toList() }
        val reclaimed = LinkedHashMap<WorkQueuePartition, MutableList<WorkId>>()
        val indeterminate = LinkedHashMap<WorkQueuePartition, MutableList<WorkId>>()
        for (partition in targets) {
            when (val result = transact(partition) { state ->
                val outcome = reclaimClaimsInternal(state, atMillis)
                WorkStoreResult.Success(outcome)
            }) {
                is WorkStoreResult.Failure -> return result
                is WorkStoreResult.Success -> {
                    if (result.value.reclaimed.isNotEmpty()) reclaimed[partition] = result.value.reclaimed.toMutableList()
                    if (result.value.indeterminate.isNotEmpty()) indeterminate[partition] = result.value.indeterminate.toMutableList()
                }
            }
        }
        return WorkStoreResult.Success(WorkRecoveryPlan(reclaimed, indeterminate, corruptedSnapshot()))
    }

    // -----------------------------------------------------------------------
    // Restart reconciliation (11.4, 11.8, 11.12)
    // -----------------------------------------------------------------------

    /**
     * Makes process-death recovery deterministic without ever replaying a started effect.
     * Claimed work with no started effect becomes claimable again in its original FIFO position;
     * claimed work with a started-but-uncommitted effect becomes terminally indeterminate.
     * Committed effects remain memoized for linear policy replay.
     */
    fun reconcileAfterRestart(atMillis: Long): WorkStoreResult<WorkRecoveryPlan> {
        val targets = synchronized(lock) { partitions.keys.toList() }
        val reclaimed = LinkedHashMap<WorkQueuePartition, MutableList<WorkId>>()
        val indeterminate = LinkedHashMap<WorkQueuePartition, MutableList<WorkId>>()
        for (partition in targets) {
            when (val result = transact(partition) { state ->
                if (state.works.size > bounds.maxRecoveryItemsPerQueue) {
                    return@transact WorkStoreResult.Failure(
                        WorkStoreFailure.Corrupted("partition=${partition.queueId.value} recovery_items"),
                    )
                }
                WorkStoreResult.Success(reclaimClaimsInternal(state, atMillis))
            }) {
                is WorkStoreResult.Failure -> {
                    markPartitionCorrupted(partition)
                }
                is WorkStoreResult.Success -> {
                    if (result.value.reclaimed.isNotEmpty()) reclaimed[partition] = result.value.reclaimed.toMutableList()
                    if (result.value.indeterminate.isNotEmpty()) indeterminate[partition] = result.value.indeterminate.toMutableList()
                }
            }
        }
        return WorkStoreResult.Success(WorkRecoveryPlan(reclaimed, indeterminate, corruptedSnapshot()))
    }

    // -----------------------------------------------------------------------
    // Deletion
    // -----------------------------------------------------------------------

    /** Deletes every durable queue partition of one instance; durable work is not history. */
    fun deleteInstance(instanceId: WorkInstanceId): WorkStoreResult<Int> {
        val targets = synchronized(lock) {
            partitions.keys.filter { it.instanceId == instanceId }
        }
        var deleted = 0
        for (partition in targets) {
            when (val result = deletePartition(partition)) {
                is WorkStoreResult.Failure -> return result
                is WorkStoreResult.Success -> deleted += 1
            }
        }
        val swept = synchronized(lock) {
            var removed = 0
            if (directory.exists()) {
                val instanceName = sanitize(instanceId.value)
                directory.listFiles()?.filter { it.isDirectory }?.forEach { repositoryDir ->
                    val instanceDir = File(repositoryDir, instanceName)
                    if (instanceDir.exists()) {
                        instanceDir.deleteRecursively()
                    }
                }
                val matching = corruptedFiles.filter { relative ->
                    val segments = relative.split(File.separatorChar)
                    segments.size >= 2 && segments[1] == instanceName
                }
                removed = matching.size
                corruptedFiles.removeAll(matching.toSet())
            }
            removed
        }
        targets.forEach { notify(it) }
        return WorkStoreResult.Success(deleted + swept)
    }

    fun deletePartition(partition: WorkQueuePartition): WorkStoreResult<Unit> {
        val result = synchronized(lock) {
            partitions.remove(partition)
            corruptedFiles.remove(relativePathFor(partition))
            val file = partitionFile(partition)
            try {
                if (file.exists()) file.delete()
                WorkStoreResult.Success(Unit)
            } catch (error: SecurityException) {
                WorkStoreResult.Failure(WorkStoreFailure.Storage(error.message ?: "delete"))
            }
        }
        notify(partition)
        return result
    }

    // -----------------------------------------------------------------------
    // Projection (11.9)
    // -----------------------------------------------------------------------

    /** Metadata-only projection; never exposes payloads, effect keys/results, or ledger internals. */
    fun projection(partition: WorkQueuePartition): WorkQueueProjection = synchronized(lock) {
        val state = partitions[partition]
        if (state == null) {
            val corrupted = corruptedFiles.contains(relativePathFor(partition))
            return@synchronized WorkQueueProjection(
                partition = partition,
                epoch = WorkEpoch(0L),
                availability = if (corrupted) WorkQueueAvailability.CORRUPTED else WorkQueueAvailability.AVAILABLE,
                queuedCount = 0,
                activePresent = false,
                lastTerminalClass = null,
            )
        }
        WorkQueueProjection(
            partition = partition,
            epoch = state.record.epoch,
            availability = WorkQueueAvailability.AVAILABLE,
            queuedCount = state.works.values.count { it.state == WorkState.QUEUED },
            activePresent = state.works.values.any { it.state == WorkState.CLAIMED },
            lastTerminalClass = state.tombstones.lastOrNull()?.terminalClass,
        )
    }

    fun instanceProjection(instanceId: WorkInstanceId): List<WorkQueueProjection> = synchronized(lock) {
        partitions.keys.filter { it.instanceId == instanceId }.map { projection(it) }
    }

    // -----------------------------------------------------------------------
    // Internals
    // -----------------------------------------------------------------------

    private fun corruptedSnapshot(): List<String> = synchronized(lock) { corruptedFiles.toList() }

    private fun markPartitionCorrupted(partition: WorkQueuePartition) {
        synchronized(lock) {
            partitions.remove(partition)
            corruptedFiles += relativePathFor(partition)
        }
        notify(partition)
    }

    private fun requireState(partition: WorkQueuePartition): WorkStoreResult<QueueState> {
        val state = partitions[partition] ?: return WorkStoreResult.Failure(
            WorkStoreFailure.NotFound("partition=${partition.queueId.value}"),
        )
        return WorkStoreResult.Success(state)
    }

    private fun stateForWork(workId: WorkId): QueueState? =
        partitions.values.firstOrNull { it.works.containsKey(workId) }

    private fun instanceQueueCount(instanceId: WorkInstanceId): Int =
        partitions.keys.count { it.instanceId == instanceId }

    private fun requireToken(holderToken: String) {
        require(holderToken.isNotBlank()) { "Holder token must not be blank" }
    }

    private fun validateReasonTag(reasonTag: String): WorkStoreResult.Failure? {
        if (reasonTag.isEmpty()) return WorkStoreResult.Failure(WorkStoreFailure.InvalidValue("reasonTag"))
        if (reasonTag.utf8Size() > bounds.maxReasonTagBytes) {
            return WorkStoreResult.Failure(WorkStoreFailure.TooLarge("reasonTag"))
        }
        return null
    }


    /** Validates normalized value shape and structural quotas; returns an error detail or null. */
    private fun validateValue(value: WorkValue, field: String): String? {
        var entries = 0
        fun visit(node: WorkValue, depth: Int): String? {
            if (depth > bounds.maxPayloadDepth) return "depth"
            return when (node) {
                WorkValue.Null, is WorkValue.Bool, is WorkValue.Integer -> null
                is WorkValue.Real -> if (!node.value.isFinite()) return "non_finite" else null
                is WorkValue.Text -> if (node.value.utf8Size() > bounds.maxTextBytes) return "text_bytes" else null
                is WorkValue.List -> {
                    for (child in node.values) {
                        if (++entries > bounds.maxPayloadEntries) return "entries"
                        visit(child, depth + 1)?.let { return it }
                    }
                    null
                }
                is WorkValue.Map -> {
                    val seen = HashSet<String>(node.entries.size)
                    for (entry in node.entries) {
                        if (++entries > bounds.maxPayloadEntries) return "entries"
                        if (!seen.add(entry.key)) return "duplicate_key"
                        if (entry.key.utf8Size() > bounds.maxTextBytes) return "key_bytes"
                        visit(entry.value, depth + 1)?.let { return it }
                    }
                    null
                }
            }
        }
        return visit(value, 0)
    }

    /**
     * Runs one partition transaction: mutate in-memory state, strictly re-validate, encode,
     * enforce the storage quota before writing, then persist atomically (temp + fsync + atomic
     * move) before returning success.  A failure leaves memory and disk unchanged.
     */
    private fun <T> transact(
        partition: WorkQueuePartition,
        createIfMissing: Boolean = true,
        notifyOnChange: Boolean = true,
        action: (QueueState) -> WorkStoreResult<T>,
    ): WorkStoreResult<T> {
        val outcome = synchronized(lock) {
            if (corruptedFiles.contains(relativePathFor(partition))) {
                return@synchronized WorkStoreResult.Failure(
                    WorkStoreFailure.Corrupted("partition=${partition.queueId.value}"),
                )
            }
            val existing = partitions[partition]
            val created = existing == null
            if (existing == null && !createIfMissing) {
                return@synchronized WorkStoreResult.Failure(
                    WorkStoreFailure.NotFound("partition=${partition.queueId.value}"),
                )
            }
            val state = existing ?: QueueState(QueueRecord(partition, WorkEpoch(0L), 0L, null)).also {
                partitions[partition] = it
            }
            val backup = state.copyForRollback()
            val result = try {
                action(state)
            } catch (error: IllegalArgumentException) {
                WorkStoreResult.Failure(WorkStoreFailure.InvalidValue(error.message ?: "invalid"))
            }
            when (result) {
                is WorkStoreResult.Failure -> {
                    state.restoreFrom(backup)
                    if (created) partitions.remove(partition)
                    result
                }
                is WorkStoreResult.Success -> {
                    val validationError = try {
                        validateQueueState(state)
                        null
                    } catch (error: Exception) {
                        error.message ?: "invalid queue state"
                    }
                    if (validationError != null) {
                        state.restoreFrom(backup)
                        if (created) partitions.remove(partition)
                        return@synchronized WorkStoreResult.Failure(WorkStoreFailure.Conflict(validationError))
                    }
                    val encoded = try {
                        encodeQueueState(state)
                    } catch (error: Exception) {
                        state.restoreFrom(backup)
                        if (created) partitions.remove(partition)
                        return@synchronized WorkStoreResult.Failure(WorkStoreFailure.InvalidValue("encoding"))
                    }
                    val bytes = encoded.toByteArray(Charsets.UTF_8)
                    if (bytes.size > bounds.maxStorageBytesPerQueue) {
                        state.restoreFrom(backup)
                        if (created) partitions.remove(partition)
                        return@synchronized WorkStoreResult.Failure(WorkStoreFailure.TooLarge("queueStorage"))
                    }
                    when (val persisted = persist(partition, bytes)) {
                        is WorkStoreResult.Failure -> {
                            state.restoreFrom(backup)
                            if (created) partitions.remove(partition)
                            persisted
                        }
                        is WorkStoreResult.Success -> result
                    }
                }
            }
        }
        if (notifyOnChange) notify(partition)
        return outcome
    }

    private fun <T> transactForWork(
        workId: WorkId,
        action: (QueueState, WorkRecord) -> WorkStoreResult<T>,
    ): WorkStoreResult<T> {
        val partition = synchronized(lock) { stateForWork(workId)?.record?.partition }
            ?: return WorkStoreResult.Failure(WorkStoreFailure.NotFound("work=${workId.value}"))
        return transact(partition) { state ->
            val work = state.works[workId]
                ?: return@transact WorkStoreResult.Failure(WorkStoreFailure.NotFound("work=${workId.value}"))
            action(state, work)
        }
    }

    /**
     * Exactly-once terminalization by work ID that survives terminal purge: a live work is purged
     * to a tombstone; an already-terminal work replays its identical tombstone or rejects a
     * different outcome without creating another one.
     */
    private fun terminalizeById(
        workId: WorkId,
        terminalClass: WorkTerminalClass,
        reasonTag: String?,
        atMillis: Long,
        playbackHandedOff: Boolean,
        requiredHolder: String?,
    ): WorkStoreResult<WorkTombstone> {
        val partition = synchronized(lock) {
            stateForWork(workId)?.record?.partition
                ?: partitions.values.firstOrNull { state -> state.tombstones.any { it.workId == workId } }?.record?.partition
        } ?: return WorkStoreResult.Failure(WorkStoreFailure.NotFound("work=${workId.value}"))
        return transact(partition) { state ->
            val work = state.works[workId]
            if (work != null) {
                if (requiredHolder != null && (work.state != WorkState.CLAIMED || work.leaseHolder != requiredHolder)) {
                    return@transact WorkStoreResult.Failure(WorkStoreFailure.Stale("work=${workId.value}"))
                }
                terminalizeInternal(state, work, terminalClass, reasonTag, atMillis, playbackHandedOff)
            } else {
                val existing = state.tombstones.firstOrNull { it.workId == workId }
                    ?: return@transact WorkStoreResult.Failure(WorkStoreFailure.NotFound("work=${workId.value}"))
                if (existing.terminalClass == terminalClass && existing.reasonTag == reasonTag) {
                    WorkStoreResult.Success(existing)
                } else {
                    WorkStoreResult.Failure(
                        WorkStoreFailure.Conflict("work=${workId.value} terminal=${existing.terminalClass}"),
                    )
                }
            }
        }
    }

    private fun terminalizeInternal(
        state: QueueState,
        work: WorkRecord,
        terminalClass: WorkTerminalClass,
        reasonTag: String?,
        atMillis: Long,
        playbackHandedOff: Boolean,
    ): WorkStoreResult<WorkTombstone> {
        val effectCount = state.effects.values.count { it.workId == work.id }
        // Purge payload, effect bodies, and the nonterminal record; retain only the tombstone.
        state.works.remove(work.id)
        state.effects.entries.removeAll { it.key.first == work.id }
        val tombstone = WorkTombstone(
            workId = work.id,
            sequence = work.sequence,
            epoch = work.epoch,
            terminalClass = terminalClass,
            reasonTag = reasonTag,
            submittedAtMillis = work.submittedAtMillis,
            terminatedAtMillis = atMillis,
            effectCount = effectCount,
            playbackHandedOff = playbackHandedOff,
        )
        state.tombstones.add(tombstone)
        while (state.tombstones.size > bounds.maxTombstonesPerQueue) {
            state.tombstones.removeFirst()
        }
        return WorkStoreResult.Success(tombstone)
    }

    private data class ReclaimOutcome(val reclaimed: List<WorkId>, val indeterminate: List<WorkId>)

    private fun reclaimClaimsInternal(state: QueueState, atMillis: Long): ReclaimOutcome {
        val reclaimed = mutableListOf<WorkId>()
        val indeterminate = mutableListOf<WorkId>()
        for (work in state.works.values.filter { it.state == WorkState.CLAIMED }) {
            if (state.effects.values.any { it.workId == work.id && it.state == WorkEffectState.STARTED }) {
                terminalizeInternal(state, work, WorkTerminalClass.INDETERMINATE, "interrupted_after_start", atMillis, playbackHandedOff = false)
                indeterminate += work.id
            } else {
                state.works[work.id] = work.copy(state = WorkState.QUEUED, leaseHolder = null, claimedAtMillis = null)
                reclaimed += work.id
            }
        }
        return ReclaimOutcome(reclaimed, indeterminate)
    }

    private fun retireInternal(
        state: QueueState,
        cause: WorkEpochResetCause,
        bindingFingerprint: String,
        atMillis: Long,
    ): WorkEpochRetirement {
        val cancelled = mutableListOf<WorkId>()
        val indeterminate = mutableListOf<WorkId>()
        for (work in state.works.values.toList()) {
            if (state.effects.values.any { it.workId == work.id && it.state == WorkEffectState.STARTED }) {
                terminalizeInternal(state, work, WorkTerminalClass.INDETERMINATE, "epoch_retired", atMillis, playbackHandedOff = false)
                indeterminate += work.id
            } else {
                terminalizeInternal(state, work, WorkTerminalClass.CANCELLED, "epoch_retired", atMillis, playbackHandedOff = false)
                cancelled += work.id
            }
        }
        val newEpoch = WorkEpoch(state.record.epoch.value + 1L)
        state.record = state.record.copy(
            epoch = newEpoch,
            nextSequence = 0L,
            revisionFingerprint = bindingFingerprint,
        )
        return WorkEpochRetirement(state.record.partition, cause, newEpoch, cancelled, indeterminate)
    }

    private fun persist(partition: WorkQueuePartition, bytes: ByteArray): WorkStoreResult<Unit> {
        val file = partitionFile(partition)
        val parent = file.absoluteFile.parentFile
        val temporary = File(parent, "${file.name}.tmp")
        return try {
            parent.mkdirs()
            FileOutputStream(temporary).use { output ->
                output.write(bytes)
                output.fd.sync()
            }
            Files.move(
                temporary.toPath(),
                file.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
            WorkStoreResult.Success(Unit)
        } catch (error: IOException) {
            if (temporary.exists()) temporary.delete()
            WorkStoreResult.Failure(WorkStoreFailure.Storage(error.message ?: "persist"))
        }
    }

    private fun partitionFile(partition: WorkQueuePartition): File {
        val repositoryDir = File(directory, partition.repositoryId.value.toString())
        val instanceDir = File(repositoryDir, sanitize(partition.instanceId.value))
        return File(instanceDir, "${sanitize(partition.queueId.value)}.work.json")
    }

    private fun relativePathFor(partition: WorkQueuePartition): String =
        partitionFile(partition).relativeTo(directory).path

    private fun notify(partition: WorkQueuePartition) {
        listener?.onQueueMutated(partition)
    }

    private fun workFiles(root: File): List<File> {
        val found = ArrayList<File>()
        for (file in root.walkTopDown()) {
            if (file.isFile && file.name.endsWith(".work.json")) {
                found += file
            }
        }
        found.sortBy { file -> file.path }
        return found
    }

    companion object {
        const val SCHEMA_VERSION = 1

        private val SAFE_NAME = Regex("[^A-Za-z0-9._-]")

        private fun sanitize(name: String): String =
            SAFE_NAME.replace(name, "_").ifEmpty { "_" }
    }
}

/** Change hook contract consumed by [DurableWorkCoordinator] for polling-free wakeups. */
fun interface DurableWorkStoreListener {
    fun onQueueMutated(partition: WorkQueuePartition)
}

/** Report returned by [DurableWorkStore.load]. */
data class WorkStoreLoadReport(
    val partitions: List<WorkQueuePartition>,
    val corrupted: List<String>,
)

/** In-memory partition state for tests and diagnostics. */
data class WorkQueueSnapshot(
    val record: QueueRecord,
    val works: List<WorkRecord>,
    val effects: List<EffectRecord>,
    val tombstones: List<WorkTombstone>,
)

/** Mutable per-partition store state; never escapes the store lock. */
internal class QueueState(
    var record: QueueRecord,
    val works: LinkedHashMap<WorkId, WorkRecord> = LinkedHashMap(),
    val effects: LinkedHashMap<Pair<WorkId, String>, EffectRecord> = LinkedHashMap(),
    val tombstones: ArrayDeque<WorkTombstone> = ArrayDeque(),
) {
    fun copyForRollback(): QueueState {
        val copy = QueueState(record)
        copy.works.putAll(works)
        copy.effects.putAll(effects)
        copy.tombstones.addAll(tombstones)
        return copy
    }

    fun restoreFrom(backup: QueueState) {
        record = backup.record
        works.clear()
        works.putAll(backup.works)
        effects.clear()
        effects.putAll(backup.effects)
        tombstones.clear()
        tombstones.addAll(backup.tombstones)
    }
}

// ---------------------------------------------------------------------------
// Strict encoding / decoding (11.2)
// ---------------------------------------------------------------------------

internal fun encodeQueueState(state: QueueState): String = JSONObject().apply {
    val partition = state.record.partition
    put("schemaVersion", DurableWorkStore.SCHEMA_VERSION)
    put("repositoryId", partition.repositoryId.value)
    put("instanceId", partition.instanceId.value)
    put("queueId", partition.queueId.value)
    put("epoch", state.record.epoch.value)
    put("nextSequence", state.record.nextSequence)
    state.record.revisionFingerprint?.let { put("revisionFingerprint", it) }
    put("works", JSONArray().also { array ->
        state.works.values.sortedBy { it.sequence }.forEach { array.put(encodeWork(it)) }
    })
    put("effects", JSONArray().also { array ->
        state.effects.values.forEach { array.put(encodeEffect(it)) }
    })
    put("tombstones", JSONArray().also { array ->
        state.tombstones.forEach { array.put(encodeTombstone(it)) }
    })
}.toString(2)

internal fun decodeQueueState(encoded: String): QueueState {
    val root = JSONObject(encoded)
    val schemaVersion = root.getInt("schemaVersion")
    require(schemaVersion == DurableWorkStore.SCHEMA_VERSION) { "Unsupported work schema $schemaVersion" }
    val partition = WorkQueuePartition(
        repositoryId = WorkRepositoryId(root.getLong("repositoryId")),
        instanceId = WorkInstanceId(root.getString("instanceId")),
        queueId = WorkQueueId(root.getString("queueId")),
    )
    val state = QueueState(
        QueueRecord(
            partition = partition,
            epoch = WorkEpoch(root.getLong("epoch")),
            nextSequence = root.getLong("nextSequence"),
            revisionFingerprint = if (root.has("revisionFingerprint")) root.getString("revisionFingerprint") else null,
        ),
    )
    val works = root.getJSONArray("works")
    for (index in 0 until works.length()) {
        val work = decodeWork(works.getJSONObject(index), partition)
        require(!state.works.containsKey(work.id)) { "Duplicate work ID ${work.id.value}" }
        state.works[work.id] = work
    }
    val effects = root.getJSONArray("effects")
    for (index in 0 until effects.length()) {
        val effect = decodeEffect(effects.getJSONObject(index))
        require(state.works.containsKey(effect.workId)) { "Effect references unknown work ${effect.workId.value}" }
        require(!state.effects.containsKey(effect.workId to effect.key)) { "Duplicate effect key ${effect.key}" }
        state.effects[effect.workId to effect.key] = effect
    }
    val tombstones = root.getJSONArray("tombstones")
    for (index in 0 until tombstones.length()) {
        state.tombstones.add(decodeTombstone(tombstones.getJSONObject(index)))
    }
    return state
}

internal fun validateQueueState(state: QueueState) {
    val sequences = state.works.values.map { it.sequence }
    require(sequences.toSet().size == sequences.size) { "Duplicate FIFO sequence" }
    for (work in state.works.values) {
        require(work.partition == state.record.partition) { "Work partition mismatch" }
        when (work.state) {
            WorkState.QUEUED -> require(work.leaseHolder == null && work.claimedAtMillis == null) { "Queued work must be unclaimed" }
            WorkState.CLAIMED -> require(!work.leaseHolder.isNullOrBlank()) { "Claimed work requires a lease holder" }
        }
    }
    for (effect in state.effects.values) {
        when (effect.state) {
            WorkEffectState.STARTED -> require(effect.result == null) { "Started effect has no result" }
            WorkEffectState.COMMITTED -> require(effect.result != null) { "Committed effect requires a result" }
            WorkEffectState.INDETERMINATE -> Unit
        }
    }
}

private fun encodeWork(work: WorkRecord) = JSONObject().apply {
    put("id", work.id.value)
    put("sequence", work.sequence)
    put("epoch", work.epoch.value)
    put("payload", encodeValue(work.payload))
    put("state", work.state.name)
    work.leaseHolder?.let { put("leaseHolder", it) }
    put("submittedAtMillis", work.submittedAtMillis)
    work.claimedAtMillis?.let { put("claimedAtMillis", it) }
}

private fun decodeWork(json: JSONObject, partition: WorkQueuePartition): WorkRecord {
    val state = WorkState.valueOf(json.getString("state"))
    return WorkRecord(
        id = WorkId(json.getString("id")),
        partition = partition,
        sequence = json.getLong("sequence"),
        epoch = WorkEpoch(json.getLong("epoch")),
        payload = decodeValue(json.getJSONObject("payload")),
        state = state,
        leaseHolder = if (json.has("leaseHolder")) json.getString("leaseHolder") else null,
        submittedAtMillis = json.getLong("submittedAtMillis"),
        claimedAtMillis = if (json.has("claimedAtMillis")) json.getLong("claimedAtMillis") else null,
    )
}

private fun encodeEffect(effect: EffectRecord) = JSONObject().apply {
    put("workId", effect.workId.value)
    put("key", effect.key)
    put("argumentFingerprint", effect.argumentFingerprint)
    put("state", effect.state.name)
    effect.result?.let { put("result", encodeEffectResult(it)) }
}

private fun decodeEffect(json: JSONObject): EffectRecord {
    val state = WorkEffectState.valueOf(json.getString("state"))
    return EffectRecord(
        workId = WorkId(json.getString("workId")),
        key = json.getString("key"),
        argumentFingerprint = json.getString("argumentFingerprint"),
        state = state,
        result = if (json.has("result")) decodeEffectResult(json.getJSONObject("result")) else null,
    )
}

private fun encodeEffectResult(result: WorkEffectResult) = JSONObject().apply {
    when (result) {
        is WorkEffectResult.Success -> {
            put("kind", "success")
            put("value", encodeValue(result.value))
        }
        is WorkEffectResult.Error -> {
            put("kind", "error")
            put("value", encodeValue(result.value))
        }
    }
}

private fun decodeEffectResult(json: JSONObject): WorkEffectResult {
    val value = decodeValue(json.getJSONObject("value"))
    return when (val kind = json.getString("kind")) {
        "success" -> WorkEffectResult.Success(value)
        "error" -> WorkEffectResult.Error(value)
        else -> throw IllegalArgumentException("Unknown effect result kind $kind")
    }
}

private fun encodeTombstone(tombstone: WorkTombstone) = JSONObject().apply {
    put("workId", tombstone.workId.value)
    put("sequence", tombstone.sequence)
    put("epoch", tombstone.epoch.value)
    put("terminalClass", tombstone.terminalClass.name)
    tombstone.reasonTag?.let { put("reasonTag", it) }
    put("submittedAtMillis", tombstone.submittedAtMillis)
    put("terminatedAtMillis", tombstone.terminatedAtMillis)
    put("effectCount", tombstone.effectCount)
    put("playbackHandedOff", tombstone.playbackHandedOff)
}

private fun decodeTombstone(json: JSONObject) = WorkTombstone(
    workId = WorkId(json.getString("workId")),
    sequence = json.getLong("sequence"),
    epoch = WorkEpoch(json.getLong("epoch")),
    terminalClass = WorkTerminalClass.valueOf(json.getString("terminalClass")),
    reasonTag = if (json.has("reasonTag")) json.getString("reasonTag") else null,
    submittedAtMillis = json.getLong("submittedAtMillis"),
    terminatedAtMillis = json.getLong("terminatedAtMillis"),
    effectCount = json.getInt("effectCount"),
    playbackHandedOff = json.getBoolean("playbackHandedOff"),
)

/** Strict normalized value encoding with exact type tags and ordered map entries. */
internal fun encodeValue(value: WorkValue): JSONObject = JSONObject().apply {
    when (value) {
        WorkValue.Null -> put("t", "null")
        is WorkValue.Bool -> {
            put("t", "bool")
            put("v", value.value)
        }
        is WorkValue.Integer -> {
            put("t", "int")
            put("v", value.value)
        }
        is WorkValue.Real -> {
            require(value.value.isFinite()) { "Non-finite real value" }
            put("t", "real")
            put("v", value.value)
        }
        is WorkValue.Text -> {
            put("t", "text")
            put("v", value.value)
        }
        is WorkValue.List -> {
            put("t", "list")
            put("v", JSONArray().also { array -> value.values.forEach { array.put(encodeValue(it)) } })
        }
        is WorkValue.Map -> {
            put("t", "map")
            put("v", JSONArray().also { array ->
                value.entries.forEach { entry ->
                    array.put(JSONArray().put(entry.key).put(encodeValue(entry.value)))
                }
            })
        }
    }
}

/** Strict normalized value decoding; unknown tags or shapes fail the enclosing document. */
internal fun decodeValue(json: JSONObject): WorkValue = when (val tag = json.getString("t")) {
    "null" -> WorkValue.Null
    "bool" -> WorkValue.Bool(json.getBoolean("v"))
    "int" -> WorkValue.Integer(json.getLong("v"))
    "real" -> WorkValue.Real(json.getDouble("v")).also {
        require(it.value.isFinite()) { "Non-finite real value" }
    }
    "text" -> WorkValue.Text(json.getString("v"))
    "list" -> WorkValue.List(
        json.getJSONArray("v").let { array -> List(array.length()) { decodeValue(array.getJSONObject(it)) } },
    )
    "map" -> {
        val array = json.getJSONArray("v")
        val entries = ArrayList<WorkMapEntry>(array.length())
        val seen = HashSet<String>(array.length())
        for (index in 0 until array.length()) {
            val pair = array.getJSONArray(index)
            require(pair.length() == 2) { "Malformed map entry" }
            val key = pair.getString(0)
            require(seen.add(key)) { "Duplicate map key" }
            entries.add(WorkMapEntry(key, decodeValue(pair.getJSONObject(1))))
        }
        WorkValue.Map(entries)
    }
    else -> throw IllegalArgumentException("Unknown value tag $tag")
}
