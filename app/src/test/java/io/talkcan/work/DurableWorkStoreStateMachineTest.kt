package io.talkcan.work

import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Task 11.10: store state-machine conformance. */
class DurableWorkStoreStateMachineTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val counter = AtomicInteger()
    private val partition = WorkQueuePartition(WorkRepositoryId(1L), WorkInstanceId("instance-1"), WorkQueueId("turns"))

    private fun newStore(bounds: DurableWorkBounds = DurableWorkBounds()): DurableWorkStore =
        DurableWorkStore(folder.root, bounds) { "work-${counter.getAndIncrement()}" }

    private fun <T> WorkStoreResult<T>.success(): T = when (this) {
        is WorkStoreResult.Success -> value
        is WorkStoreResult.Failure -> throw AssertionError("Expected success, was Failure($failure)")
    }

    private fun WorkStoreResult<*>.failure(): WorkStoreFailure = when (this) {
        is WorkStoreResult.Success -> throw AssertionError("Expected failure, was Success($value)")
        is WorkStoreResult.Failure -> failure
    }

    private fun text(value: String): WorkValue = WorkValue.Text(value)

    private fun submit(
        store: DurableWorkStore,
        payload: WorkValue = text("turn"),
        at: Long = 1L,
        queue: WorkQueuePartition = partition,
    ): WorkRecord = store.submit(queue, payload, at).success()

    private fun claim(store: DurableWorkStore, holder: String = "worker", at: Long = 1L): WorkClaim =
        checkNotNull(store.claimNext(partition, holder, at).success()) { "Expected a claimable item" }

    private fun partitionFile(): File = folder.root
        .resolve(partition.repositoryId.value.toString())
        .resolve(partition.instanceId.value)
        .resolve("${partition.queueId.value}.work.json")

    @Test
    fun submissionsAssignFifoSequencesInCommitOrder() {
        val store = newStore()
        store.load().success()
        val first = submit(store, text("one"))
        val second = submit(store, text("two"))
        val third = submit(store, text("three"))
        assertEquals(listOf(0L, 1L, 2L), listOf(first.sequence, second.sequence, third.sequence))
        val snapshot = store.snapshot(partition).success()
        assertEquals(
            listOf("one", "two", "three"),
            snapshot.works.map { (it.payload as WorkValue.Text).value },
        )
        assertTrue(snapshot.works.all { it.state == WorkState.QUEUED })
    }

    @Test
    fun independentQueuesAndInstancesProgressIndependently() {
        val store = newStore()
        store.load().success()
        val otherInstance = WorkQueuePartition(WorkRepositoryId(2L), WorkInstanceId("instance-2"), WorkQueueId("turns"))
        val otherQueue = WorkQueuePartition(WorkRepositoryId(1L), WorkInstanceId("instance-1"), WorkQueueId("tools"))
        val mine = submit(store, text("mine"))
        val theirs = submit(store, text("theirs"), queue = otherInstance)
        submit(store, text("tools"), queue = otherQueue)

        val myClaim = store.claimNext(partition, "worker-a", 2L).success()
        val theirClaim = store.claimNext(otherInstance, "worker-b", 2L).success()
        assertEquals(mine.id, checkNotNull(myClaim).work.id)
        assertEquals(theirs.id, checkNotNull(theirClaim).work.id)

        // Terminating my job never reorders or discards the sibling instance.
        store.completeWork(mine.id, "worker-a", 3L, playbackHandedOff = true).success()
        val theirSnapshot = store.snapshot(otherInstance).success()
        assertEquals(WorkState.CLAIMED, theirSnapshot.works.single().state)
        assertEquals(theirs.id, theirSnapshot.works.single().id)
        assertEquals(1, store.projection(otherQueue).queuedCount)
    }

    @Test
    fun oneActiveClaimPerQueueBlocksSecondClaim() {
        val store = newStore()
        store.load().success()
        val first = submit(store, text("one"))
        val second = submit(store, text("two"))

        val claim = claim(store)
        assertEquals(first.id, claim.work.id)
        assertNull(store.claimNext(partition, "worker-2", 2L).success())

        store.completeWork(first.id, "worker", 3L, playbackHandedOff = true).success()
        val next = claim(store, at = 4L)
        assertEquals(second.id, next.work.id)
    }

    @Test
    fun claimOnFreshPartitionCreatesNoQueueRecord() {
        val store = newStore()
        store.load().success()
        assertNull(store.claimNext(partition, "worker", 1L).success())
        assertTrue(store.partitions().isEmpty())
        assertTrue(folder.root.listFiles()?.isEmpty() ?: true)
    }

    @Test
    fun committedEffectReplaysStoredResultForSameKeyAndFingerprint() {
        val store = newStore()
        store.load().success()
        val work = submit(store)
        val claim = claim(store)

        val started = store.beginEffect(work.id, "worker", "completion:1", "fp-1").success()
        assertTrue(started is WorkEffectBegin.Started)
        val committed = store.commitEffect(
            work.id,
            "worker",
            "completion:1",
            WorkEffectResult.Success(text("normalized-response")),
        ).success()
        assertEquals(WorkEffectState.COMMITTED, committed.state)

        val replay = store.beginEffect(claim.work.id, "worker", "completion:1", "fp-1").success()
        assertTrue(replay is WorkEffectBegin.Replay)
        assertEquals(
            WorkEffectResult.Success(text("normalized-response")),
            (replay as WorkEffectBegin.Replay).result,
        )
    }

    @Test
    fun duplicateAndIncompatibleEffectKeysAreRejected() {
        val store = newStore()
        store.load().success()
        val work = submit(store)
        claim(store)

        val started = store.beginEffect(work.id, "worker", "tool:1", "fp-a").success()
        assertTrue(started is WorkEffectBegin.Started)
        // Nested/concurrent begin under a started key is a conflict.
        assertTrue(store.beginEffect(work.id, "worker", "tool:1", "fp-a").failure() is WorkStoreFailure.Conflict)

        store.commitEffect(work.id, "worker", "tool:1", WorkEffectResult.Success(text("r1"))).success()
        // Same key with an incompatible fingerprint is a conflict.
        assertTrue(store.beginEffect(work.id, "worker", "tool:1", "fp-b").failure() is WorkStoreFailure.Conflict)
        // A distinct key is admitted.
        assertTrue(store.beginEffect(work.id, "worker", "tool:2", "fp-a").success() is WorkEffectBegin.Started)
    }

    @Test
    fun commitEffectIsIdempotentForIdenticalResultAndConflictsOtherwise() {
        val store = newStore()
        store.load().success()
        val work = submit(store)
        claim(store)
        store.beginEffect(work.id, "worker", "k", "fp").success()

        val first = store.commitEffect(work.id, "worker", "k", WorkEffectResult.Success(text("v"))).success()
        val again = store.commitEffect(work.id, "worker", "k", WorkEffectResult.Success(text("v"))).success()
        assertEquals(first, again)
        assertTrue(
            store.commitEffect(work.id, "worker", "k", WorkEffectResult.Error(text("other")))
                .failure() is WorkStoreFailure.Conflict,
        )
    }

    @Test
    fun effectCallsRequireClaimedWorkAndMatchingHolder() {
        val store = newStore()
        store.load().success()
        val work = submit(store)
        // Unclaimed work cannot begin effects.
        assertTrue(store.beginEffect(work.id, "worker", "k", "fp").failure() is WorkStoreFailure.Stale)
        claim(store, holder = "worker-a")
        assertTrue(store.beginEffect(work.id, "worker-b", "k", "fp").failure() is WorkStoreFailure.Stale)
        assertTrue(store.commitEffect(work.id, "worker-b", "k", WorkEffectResult.Success(text("v")))
            .failure() is WorkStoreFailure.Stale)
    }

    @Test
    fun terminalCompletionPurgesBodiesAndRetainsTombstone() {
        val store = newStore()
        store.load().success()
        val work = submit(store, text("sensitive user text"))
        claim(store)
        store.beginEffect(work.id, "worker", "completion:1", "fp-1").success()
        store.commitEffect(work.id, "worker", "completion:1", WorkEffectResult.Success(text("provider body"))).success()
        store.beginEffect(work.id, "worker", "tool:1", "fp-2").success()
        store.commitEffect(work.id, "worker", "tool:1", WorkEffectResult.Error(text("boom"))).success()

        val tombstone = store.completeWork(work.id, "worker", 5L, playbackHandedOff = true).success()
        assertEquals(WorkTerminalClass.COMPLETED, tombstone.terminalClass)
        assertEquals(2, tombstone.effectCount)
        assertTrue(tombstone.playbackHandedOff)
        assertEquals(0L, tombstone.sequence)
        assertEquals(WorkEpoch(0L), tombstone.epoch)

        // Payload and effect bodies are purged.
        assertTrue(store.payload(work.id).failure() is WorkStoreFailure.NotFound)
        assertTrue(store.effects(work.id).failure() is WorkStoreFailure.NotFound)
        val snapshot = store.snapshot(partition).success()
        assertTrue(snapshot.works.isEmpty())
        assertTrue(snapshot.effects.isEmpty())
        assertEquals(listOf(tombstone), snapshot.tombstones)
    }

    @Test
    fun exactlyOneTerminalOutcomePerWork() {
        val store = newStore()
        store.load().success()
        val work = submit(store)
        claim(store)

        val first = store.completeWork(work.id, "worker", 5L, playbackHandedOff = true).success()
        // Repeating an identical terminal request is idempotent.
        assertEquals(first, store.completeWork(work.id, "worker", 9L, playbackHandedOff = true).success())
        assertEquals(first, store.markTerminal(work.id, WorkTerminalClass.COMPLETED, null, 9L).success())
        // A different outcome never creates another terminal record.
        assertTrue(store.failWork(work.id, "worker", "other", 9L, playbackHandedOff = true)
            .failure() is WorkStoreFailure.Conflict)
        assertTrue(store.markTerminal(work.id, WorkTerminalClass.CANCELLED, null, 9L)
            .failure() is WorkStoreFailure.Conflict)
        assertEquals(1, store.tombstones(partition).success().size)
    }

    @Test
    fun tombstonesAreBoundedPerQueue() {
        val store = newStore(DurableWorkBounds(maxTombstonesPerQueue = 3))
        store.load().success()
        repeat(5) { index ->
            val work = submit(store, text("t$index"), at = index.toLong())
            claim(store, at = index.toLong())
            store.completeWork(work.id, "worker", index.toLong() + 10, playbackHandedOff = false).success()
        }
        val tombstones = store.tombstones(partition).success()
        assertEquals(listOf(2L, 3L, 4L), tombstones.map { it.sequence })
    }

    @Test
    fun releaseClaimWithoutStartedEffectsReturnsToFifoPosition() {
        val store = newStore()
        store.load().success()
        submit(store, text("one"))
        val second = submit(store, text("two"))

        val claim = claim(store)
        assertEquals(0L, claim.work.sequence)
        val released = store.releaseClaim(claim.work.id, "worker", 2L).success()
        assertTrue(released is WorkClaimRelease.Released)
        assertEquals(WorkState.QUEUED, (released as WorkClaimRelease.Released).work.state)

        // FIFO position preserved: the released item is claimed before the later one.
        val again = claim(store, at = 3L)
        assertEquals(0L, again.work.sequence)
        store.completeWork(again.work.id, "worker", 4L, playbackHandedOff = false).success()
        assertEquals(second.id, claim(store, at = 5L).work.id)
    }

    @Test
    fun releaseClaimWithStartedEffectBecomesIndeterminate() {
        val store = newStore()
        store.load().success()
        val work = submit(store)
        claim(store)
        store.beginEffect(work.id, "worker", "completion:1", "fp").success()

        val release = store.releaseClaim(work.id, "worker", 2L).success()
        assertTrue(release is WorkClaimRelease.Indeterminate)
        val tombstone = (release as WorkClaimRelease.Indeterminate).tombstone
        assertEquals(WorkTerminalClass.INDETERMINATE, tombstone.terminalClass)
        assertTrue(store.payload(work.id).failure() is WorkStoreFailure.NotFound)
        assertTrue(store.effects(work.id).failure() is WorkStoreFailure.NotFound)
        assertNull(store.claimNext(partition, "worker", 3L).success())
    }

    @Test
    fun releaseClaimRejectsForeignHolder() {
        val store = newStore()
        store.load().success()
        val work = submit(store)
        claim(store, holder = "worker-a")
        assertTrue(store.releaseClaim(work.id, "worker-b", 2L).failure() is WorkStoreFailure.Stale)
        val snapshot = store.snapshot(partition).success()
        assertEquals("worker-a", snapshot.works.single().leaseHolder)
    }

    @Test
    fun payloadReadsAreDetachedAndStable() {
        val store = newStore()
        store.load().success()
        val payload = WorkValue.Map(
            listOf(
                WorkMapEntry("text", text("value")),
                WorkMapEntry("number", WorkValue.Integer(3L)),
            ),
        )
        val work = submit(store, payload)
        assertEquals(payload, store.payload(work.id).success())
        assertEquals(payload, store.payload(work.id).success())
    }

    @Test
    fun projectionExposesOnlyGenericMetadata() {
        val store = newStore()
        store.load().success()
        submit(store)
        submit(store)
        val claim = claim(store)

        val projection = store.projection(partition)
        assertEquals(WorkQueueAvailability.AVAILABLE, projection.availability)
        assertEquals(1, projection.queuedCount)
        assertTrue(projection.activePresent)
        assertNull(projection.lastTerminalClass)

        store.completeWork(claim.work.id, "worker", 3L, playbackHandedOff = true).success()
        val after = store.projection(partition)
        assertEquals(1, after.queuedCount)
        assertTrue(!after.activePresent)
        assertEquals(WorkTerminalClass.COMPLETED, after.lastTerminalClass)
    }

    @Test
    fun normalizedValuesRoundTripThroughStrictEncoding() {
        val store = newStore()
        store.load().success()
        val payload = WorkValue.Map(
            listOf(
                WorkMapEntry("null", WorkValue.Null),
                WorkMapEntry("bool", WorkValue.Bool(true)),
                WorkMapEntry("int", WorkValue.Integer(-42L)),
                WorkMapEntry("real", WorkValue.Real(1.5)),
                WorkMapEntry("text", text("unicode ✓")),
                WorkMapEntry("list", WorkValue.List(listOf(WorkValue.Integer(1L), WorkValue.Null))),
                WorkMapEntry("map", WorkValue.Map(listOf(WorkMapEntry("inner", WorkValue.Null)))),
            ),
        )
        val work = submit(store, payload)

        val reopened = newStore()
        reopened.load().success()
        assertEquals(payload, reopened.payload(work.id).success())
    }

    @Test
    fun capacityRejectionHappensBeforeMutation() {
        val bounds = DurableWorkBounds(
            maxQueuesPerInstance = 2,
            maxNonterminalItemsPerQueue = 2,
            maxPayloadBytes = 128,
            maxPayloadDepth = 3,
            maxPayloadEntries = 4,
            maxTextBytes = 64,
            maxEffectsPerWork = 1,
            maxEffectKeyBytes = 8,
            maxEffectFingerprintBytes = 8,
            maxEffectResultBytes = 64,
            maxTombstonesPerQueue = 8,
            maxStorageBytesPerQueue = 64 * 1024,
            maxRecoveryItemsPerQueue = 16,
            maxReasonTagBytes = 8,
            maxFingerprintBytes = 32,
        )
        val store = newStore(bounds)
        store.load().success()

        // Item quota: the third nonterminal item is rejected before mutation.
        submit(store, text("one"))
        submit(store, text("two"))
        assertTrue(store.submit(partition, text("three"), 1L).failure() is WorkStoreFailure.Busy)
        assertEquals(2, store.snapshot(partition).success().works.size)
        assertEquals(2L, store.queueRecord(partition).success().nextSequence)

        // Payload byte and shape quotas.
        assertTrue(store.submit(partition, text("x".repeat(65)), 1L).failure() is WorkStoreFailure.InvalidValue)
        assertTrue(
            store.submit(partition, WorkValue.List(listOf(text("x".repeat(64)), text("y".repeat(64)))), 1L)
                .failure() is WorkStoreFailure.TooLarge,
        )
        val nested = WorkValue.Map(
            listOf(
                WorkMapEntry(
                    "a",
                    WorkValue.Map(
                        listOf(
                            WorkMapEntry("b", WorkValue.Map(listOf(WorkMapEntry("c", WorkValue.Map(listOf(WorkMapEntry("d", WorkValue.Null))))))),
                        ),
                    ),
                ),
            ),
        )
        assertTrue(store.submit(partition, nested, 1L).failure() is WorkStoreFailure.InvalidValue)
        assertTrue(
            store.submit(partition, WorkValue.List(List(5) { WorkValue.Integer(it.toLong()) }), 1L)
                .failure() is WorkStoreFailure.InvalidValue,
        )
        assertTrue(
            store.submit(
                partition,
                WorkValue.Map(listOf(WorkMapEntry("k", WorkValue.Null), WorkMapEntry("k", WorkValue.Null))),
                1L,
            ).failure() is WorkStoreFailure.InvalidValue,
        )
        assertTrue(store.submit(partition, WorkValue.Real(Double.NaN), 1L).failure() is WorkStoreFailure.InvalidValue)
        assertEquals(2, store.snapshot(partition).success().works.size)

        // Queue declaration quota per instance.
        val secondQueue = WorkQueuePartition(WorkRepositoryId(1L), WorkInstanceId("instance-1"), WorkQueueId("tools"))
        val thirdQueue = WorkQueuePartition(WorkRepositoryId(1L), WorkInstanceId("instance-1"), WorkQueueId("extra"))
        store.submit(secondQueue, text("ok"), 1L).success()
        assertTrue(store.submit(thirdQueue, text("no"), 1L).failure() is WorkStoreFailure.Busy)
        assertEquals(2, store.partitions().size)

        // Effect quotas.
        val work = claim(store).work
        assertTrue(store.beginEffect(work.id, "worker", "key-over-8b", "fp").failure() is WorkStoreFailure.TooLarge)
        assertTrue(store.beginEffect(work.id, "worker", "k", "fp-over-8b").failure() is WorkStoreFailure.TooLarge)
        assertTrue(store.beginEffect(work.id, "worker", "k1", "fp").success() is WorkEffectBegin.Started)
        assertTrue(store.beginEffect(work.id, "worker", "k2", "fp").failure() is WorkStoreFailure.Busy)
        assertTrue(
            store.commitEffect(work.id, "worker", "k1", WorkEffectResult.Success(text("x".repeat(64))))
                .failure() is WorkStoreFailure.TooLarge,
        )
        assertTrue(
            store.commitEffect(work.id, "worker", "k1", WorkEffectResult.Success(WorkValue.Real(Double.POSITIVE_INFINITY)))
                .failure() is WorkStoreFailure.InvalidValue,
        )

        // Terminal reason tag quotas.
        assertTrue(store.failWork(work.id, "worker", "tag-over-8b", 1L, false).failure() is WorkStoreFailure.TooLarge)
        assertTrue(store.failWork(work.id, "worker", "", 1L, false).failure() is WorkStoreFailure.InvalidValue)
    }

    @Test
    fun storageQuotaRejectsBeforePersisting() {
        val loose = DurableWorkBounds(maxPayloadBytes = 4096, maxTextBytes = 4096)
        val store = newStore(loose)
        store.load().success()
        submit(store, text("x".repeat(800)))
        submit(store, text("y".repeat(800)))
        val persistedBytes = partitionFile().length()

        // A successor generation with a quota just above the current document admits the
        // existing items but rejects any growth before mutation.
        val tight = DurableWorkBounds(
            maxStorageBytesPerQueue = persistedBytes.toInt() + 16,
            maxPayloadBytes = 4096,
            maxTextBytes = 4096,
        )
        val bounded = DurableWorkStore(folder.root, tight) { "work-${counter.getAndIncrement()}" }
        bounded.load().success()
        assertEquals(2, bounded.snapshot(partition).success().works.size)
        val rejected = bounded.submit(partition, text("z".repeat(800)), 1L).failure()
        assertTrue(rejected is WorkStoreFailure.TooLarge)
        assertEquals(2, bounded.snapshot(partition).success().works.size)
        assertEquals(persistedBytes, partitionFile().length())
    }
}
