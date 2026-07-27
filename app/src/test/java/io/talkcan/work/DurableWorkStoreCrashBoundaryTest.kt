package io.talkcan.work

import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Task 11.11: crash-boundary persistence conformance. */
class DurableWorkStoreCrashBoundaryTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val counter = AtomicInteger()
    private val partition = WorkQueuePartition(WorkRepositoryId(7L), WorkInstanceId("instance-1"), WorkQueueId("turns"))
    private val sibling = WorkQueuePartition(WorkRepositoryId(7L), WorkInstanceId("instance-1"), WorkQueueId("tools"))

    private fun newStore(bounds: DurableWorkBounds = DurableWorkBounds()): DurableWorkStore =
        DurableWorkStore(folder.root, bounds) { "work-${counter.getAndIncrement()}" }.also {
            it.load().success()
        }

    /** Simulates a fresh process over the same durable directory. */
    private fun restart(bounds: DurableWorkBounds = DurableWorkBounds()): DurableWorkStore = newStore(bounds)

    private fun <T> WorkStoreResult<T>.success(): T = when (this) {
        is WorkStoreResult.Success -> value
        is WorkStoreResult.Failure -> throw AssertionError("Expected success, was Failure($failure)")
    }

    private fun WorkStoreResult<*>.failure(): WorkStoreFailure = when (this) {
        is WorkStoreResult.Success -> throw AssertionError("Expected failure, was Success($value)")
        is WorkStoreResult.Failure -> failure
    }

    private fun text(value: String): WorkValue = WorkValue.Text(value)

    private fun partitionFile(target: WorkQueuePartition = partition): File = folder.root
        .resolve(target.repositoryId.value.toString())
        .resolve(target.instanceId.value)
        .resolve("${target.queueId.value}.work.json")

    @Test
    fun preSubmitCrashLeavesEmptyStore() {
        newStore()
        val restarted = restart()
        assertTrue(restarted.partitions().isEmpty())
        assertNull(restarted.claimNext(partition, "worker", 1L).success())
        assertEquals(0, restarted.projection(partition).queuedCount)
    }

    @Test
    fun postSubmitCrashRetainsQueuedWorkInOrder() {
        val store = newStore()
        store.submit(partition, text("one"), 1L).success()
        store.submit(partition, text("two"), 2L).success()

        val restarted = restart()
        val snapshot = restarted.snapshot(partition).success()
        assertEquals(
            listOf("one" to 0L, "two" to 1L),
            snapshot.works.map { (it.payload as WorkValue.Text).value to it.sequence },
        )
        assertTrue(snapshot.works.all { it.state == WorkState.QUEUED })
    }

    @Test
    fun postClaimCrashReclaimsSafelyInFifoPosition() {
        val store = newStore()
        val first = store.submit(partition, text("one"), 1L).success()
        val second = store.submit(partition, text("two"), 2L).success()
        store.claimNext(partition, "worker", 3L).success()

        val restarted = restart()
        val plan = restarted.reconcileAfterRestart(10L).success()
        assertEquals(listOf(first.id), plan.reclaimed[partition])
        assertTrue(plan.indeterminate.isEmpty())

        // FIFO position is preserved: the reclaimed item is claimed before the later one.
        val claim = checkNotNull(restarted.claimNext(partition, "worker-2", 11L).success())
        assertEquals(first.id, claim.work.id)
        assertEquals(0L, claim.work.sequence)
        restarted.completeWork(first.id, "worker-2", 12L, playbackHandedOff = true).success()
        assertEquals(second.id, checkNotNull(restarted.claimNext(partition, "worker-2", 13L).success()).work.id)
    }

    @Test
    fun preEffectCrashReclaimsClaimedWork() {
        val store = newStore()
        val work = store.submit(partition, text("turn"), 1L).success()
        store.claimNext(partition, "worker", 2L).success()

        val restarted = restart()
        val plan = restarted.reconcileAfterRestart(10L).success()
        assertEquals(listOf(work.id), plan.reclaimed[partition])
        assertTrue(checkNotNull(restarted.claimNext(partition, "worker-2", 11L).success()).work.id == work.id)
    }

    @Test
    fun postEffectStartCrashTerminalizesIndeterminateWithoutReplay() {
        val store = newStore()
        val work = store.submit(partition, text("turn"), 1L).success()
        store.claimNext(partition, "worker", 2L).success()
        store.beginEffect(work.id, "worker", "completion:1", "fp-1").success()

        val restarted = restart()
        val plan = restarted.reconcileAfterRestart(10L).success()
        assertTrue(plan.reclaimed.isEmpty())
        assertEquals(listOf(work.id), plan.indeterminate[partition])

        val tombstones = restarted.tombstones(partition).success()
        assertEquals(1, tombstones.size)
        assertEquals(WorkTerminalClass.INDETERMINATE, tombstones.single().terminalClass)

        // The started effect is never replayed: work and effects are gone.
        assertTrue(restarted.beginEffect(work.id, "worker-2", "completion:1", "fp-1").failure() is WorkStoreFailure.NotFound)
        assertTrue(restarted.effects(work.id).failure() is WorkStoreFailure.NotFound)
        assertNull(restarted.claimNext(partition, "worker-2", 11L).success())
    }

    @Test
    fun postEffectCommitCrashMemoizesResultForReplay() {
        val store = newStore()
        val work = store.submit(partition, text("turn"), 1L).success()
        store.claimNext(partition, "worker", 2L).success()
        store.beginEffect(work.id, "worker", "completion:1", "fp-1").success()
        store.commitEffect(work.id, "worker", "completion:1", WorkEffectResult.Success(text("stored-response"))).success()

        val restarted = restart()
        val plan = restarted.reconcileAfterRestart(10L).success()
        assertEquals(listOf(work.id), plan.reclaimed[partition])

        // Linear policy replay: the committed key returns its stored result without invocation.
        restarted.claimNext(partition, "worker-2", 11L).success()
        val replay = restarted.beginEffect(work.id, "worker-2", "completion:1", "fp-1").success()
        assertTrue(replay is WorkEffectBegin.Replay)
        assertEquals(WorkEffectResult.Success(text("stored-response")), (replay as WorkEffectBegin.Replay).result)
        // Committing the identical result again stays idempotent.
        val committed = restarted.commitEffect(work.id, "worker-2", "completion:1", WorkEffectResult.Success(text("stored-response"))).success()
        assertEquals(WorkEffectState.COMMITTED, committed.state)
    }

    @Test
    fun preTerminalCrashKeepsCommittedMemoization() {
        val store = newStore()
        val work = store.submit(partition, text("turn"), 1L).success()
        store.claimNext(partition, "worker", 2L).success()
        store.beginEffect(work.id, "worker", "completion:1", "fp-1").success()
        store.commitEffect(work.id, "worker", "completion:1", WorkEffectResult.Success(text("response"))).success()
        store.beginEffect(work.id, "worker", "tool:1", "fp-2").success()
        store.commitEffect(work.id, "worker", "tool:1", WorkEffectResult.Success(text("typed"))).success()
        // Crash before Job:complete persists.

        val restarted = restart()
        restarted.reconcileAfterRestart(10L).success()
        restarted.claimNext(partition, "worker-2", 11L).success()

        val response = restarted.beginEffect(work.id, "worker-2", "completion:1", "fp-1").success()
        val tool = restarted.beginEffect(work.id, "worker-2", "tool:1", "fp-2").success()
        assertEquals(WorkEffectResult.Success(text("response")), (response as WorkEffectBegin.Replay).result)
        assertEquals(WorkEffectResult.Success(text("typed")), (tool as WorkEffectBegin.Replay).result)
    }

    @Test
    fun postTerminalCrashRetainsOnlyTombstone() {
        val store = newStore()
        val work = store.submit(partition, text("turn"), 1L).success()
        store.claimNext(partition, "worker", 2L).success()
        store.beginEffect(work.id, "worker", "completion:1", "fp-1").success()
        store.commitEffect(work.id, "worker", "completion:1", WorkEffectResult.Success(text("body"))).success()
        store.completeWork(work.id, "worker", 3L, playbackHandedOff = true).success()

        val restarted = restart()
        val snapshot = restarted.snapshot(partition).success()
        assertTrue(snapshot.works.isEmpty())
        assertTrue(snapshot.effects.isEmpty())
        val tombstone = snapshot.tombstones.single()
        assertEquals(work.id, tombstone.workId)
        assertEquals(WorkTerminalClass.COMPLETED, tombstone.terminalClass)
        assertEquals(1, tombstone.effectCount)
        assertTrue(tombstone.playbackHandedOff)
        assertTrue(restarted.payload(work.id).failure() is WorkStoreFailure.NotFound)
    }

    @Test
    fun corruptPartitionFileIsolatedFromSiblings() {
        val store = newStore()
        store.submit(partition, text("victim"), 1L).success()
        store.submit(sibling, text("survivor"), 2L).success()

        partitionFile().writeText("{ not valid json")
        val restarted = restart()

        val report = DurableWorkStore(folder.root) { "x" }.load().success()
        assertTrue(report.corrupted.any { it.endsWith("turns.work.json") })
        assertTrue(report.partitions.contains(sibling))
        assertTrue(!report.partitions.contains(partition))

        // The corrupted partition fails closed; the sibling keeps working.
        assertTrue(restarted.submit(partition, text("retry"), 3L).failure() is WorkStoreFailure.Corrupted)
        assertEquals(WorkQueueAvailability.CORRUPTED, restarted.projection(partition).availability)
        restarted.submit(sibling, text("another"), 4L).success()
        assertEquals(2, restarted.snapshot(sibling).success().works.size)
    }

    @Test
    fun leftoverTemporaryFileIsIgnored() {
        val store = newStore()
        store.submit(partition, text("committed"), 1L).success()
        // A crash mid-persist leaves the atomic temp file behind.
        File(partitionFile().parentFile, "${partitionFile().name}.tmp").writeText("{ partial write")

        val restarted = restart()
        val snapshot = restarted.snapshot(partition).success()
        assertEquals(1, snapshot.works.size)
        assertEquals("committed", (snapshot.works.single().payload as WorkValue.Text).value)
    }

    @Test
    fun deleteRepairsCorruptedPartition() {
        val store = newStore()
        store.submit(partition, text("doomed"), 1L).success()
        partitionFile().writeText("garbage")

        val restarted = restart()
        assertTrue(restarted.submit(partition, text("retry"), 2L).failure() is WorkStoreFailure.Corrupted)

        restarted.deletePartition(partition).success()
        val work = restarted.submit(partition, text("fresh"), 3L).success()
        assertEquals(0L, work.sequence)
        assertEquals(WorkQueueAvailability.AVAILABLE, restarted.projection(partition).availability)
    }

    @Test
    fun recoveryOverQuotaFailsClosedForPartition() {
        val loose = DurableWorkBounds(maxNonterminalItemsPerQueue = 8)
        val store = newStore(loose)
        repeat(4) { index -> store.submit(partition, text("t$index"), index.toLong()).success() }

        val strict = restart(DurableWorkBounds(maxRecoveryItemsPerQueue = 2))
        val plan = strict.reconcileAfterRestart(10L).success()
        assertTrue(plan.corrupted.any { it.endsWith("turns.work.json") })
        assertTrue(strict.submit(partition, text("retry"), 11L).failure() is WorkStoreFailure.Corrupted)
        assertEquals(WorkQueueAvailability.CORRUPTED, strict.projection(partition).availability)
    }
}
