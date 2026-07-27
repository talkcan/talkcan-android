package io.talkcan.work

import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Task 11.12: epoch and recovery conformance. */
class DurableWorkEpochRecoveryTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val counter = AtomicInteger()
    private val partition = WorkQueuePartition(WorkRepositoryId(9L), WorkInstanceId("instance-1"), WorkQueueId("turns"))
    private val toolsQueue = WorkQueuePartition(WorkRepositoryId(9L), WorkInstanceId("instance-1"), WorkQueueId("tools"))
    private val otherPackage = WorkQueuePartition(WorkRepositoryId(11L), WorkInstanceId("instance-2"), WorkQueueId("turns"))

    private fun newStore(bounds: DurableWorkBounds = DurableWorkBounds()): DurableWorkStore =
        DurableWorkStore(folder.root, bounds) { "work-${counter.getAndIncrement()}" }.also {
            it.load().success()
        }

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

    private fun partitionFile(target: WorkQueuePartition): File = folder.root
        .resolve(target.repositoryId.value.toString())
        .resolve(target.instanceId.value)
        .resolve("${target.queueId.value}.work.json")

    @Test
    fun unchangedRestartPreservesEpochAndReclaimsSafeWork() {
        val store = newStore()
        assertEquals(WorkEpoch(0L), store.preserveEpoch(partition, "binding-1", 1L).success())
        val first = store.submit(partition, text("one"), 2L).success()
        val second = store.submit(partition, text("two"), 3L).success()
        store.claimNext(partition, "worker", 4L).success()
        store.beginEffect(first.id, "worker", "completion:1", "fp-1").success()
        store.commitEffect(first.id, "worker", "completion:1", WorkEffectResult.Success(text("response"))).success()

        val restarted = restart()
        val plan = restarted.reconcileAfterRestart(11L).success()
        // Claimed work with only committed effects is safe to reclaim.
        assertEquals(listOf(first.id), plan.reclaimed[partition])
        assertTrue(plan.indeterminate.isEmpty())
        // Identical binding: epoch preserved.
        assertEquals(WorkEpoch(0L), restarted.preserveEpoch(partition, "binding-1", 10L).success())

        val claim = checkNotNull(restarted.claimNext(partition, "worker-2", 12L).success())
        assertEquals(first.id, claim.work.id)
        // Committed memoization enables linear replay; the successor then reaches the queued item.
        val replay = restarted.beginEffect(first.id, "worker-2", "completion:1", "fp-1").success()
        assertEquals(WorkEffectResult.Success(text("response")), (replay as WorkEffectBegin.Replay).result)
        restarted.completeWork(first.id, "worker-2", 13L, playbackHandedOff = true).success()
        assertEquals(second.id, checkNotNull(restarted.claimNext(partition, "worker-2", 14L).success()).work.id)
    }

    @Test
    fun preserveEpochRejectsChangedBinding() {
        val store = newStore()
        store.submit(partition, text("old"), 1L).success()
        store.preserveEpoch(partition, "binding-1", 2L).success()

        val restarted = restart()
        assertTrue(restarted.preserveEpoch(partition, "binding-2", 10L).failure() is WorkStoreFailure.Stale)
        // The stale partition state is untouched.
        assertEquals(1, restarted.snapshot(partition).success().works.size)
    }

    @Test
    fun preserveEpochOnUnknownPartitionCreatesNoRecord() {
        val store = newStore()
        assertEquals(WorkEpoch(0L), store.preserveEpoch(partition, "binding-1", 1L).success())
        assertTrue(store.partitions().isEmpty())
        assertTrue(folder.root.listFiles()?.isEmpty() ?: true)
    }

    @Test
    fun ambiguousStartedEffectIsNeverReplayedAfterRestart() {
        val store = newStore()
        store.preserveEpoch(partition, "binding-1", 1L).success()
        val work = store.submit(partition, text("turn"), 2L).success()
        store.claimNext(partition, "worker", 3L).success()
        store.beginEffect(work.id, "worker", "completion:1", "fp-1").success()
        // Crash: effect STARTED but never committed.

        val restarted = restart()
        val plan = restarted.reconcileAfterRestart(11L).success()
        assertEquals(listOf(work.id), plan.indeterminate[partition])
        assertTrue(plan.reclaimed.isEmpty())
        // Identical binding: epoch preserved.
        assertEquals(WorkEpoch(0L), restarted.preserveEpoch(partition, "binding-1", 10L).success())

        // The request function is never called by recovery: no claim, no begin, no effects.
        assertNull(restarted.claimNext(partition, "worker-2", 12L).success())
        assertTrue(restarted.beginEffect(work.id, "worker-2", "completion:1", "fp-1").failure() is WorkStoreFailure.NotFound)
        val tombstone = restarted.tombstones(partition).success().single()
        assertEquals(WorkTerminalClass.INDETERMINATE, tombstone.terminalClass)
    }

    @Test
    fun intentionalReplacementCancelsQueuedAndIndeterminatesStarted() {
        val store = newStore()
        store.preserveEpoch(partition, "binding-1", 1L).success()
        val started = store.submit(partition, text("started"), 2L).success()
        val queued = store.submit(partition, text("queued"), 3L).success()
        store.claimNext(partition, "worker", 4L).success()
        store.beginEffect(started.id, "worker", "completion:1", "fp-1").success()

        val retirement = store.retireEpoch(partition, WorkEpochResetCause.CONFIGURATION_REPLACED, "binding-2", 10L).success()
        assertEquals(WorkEpoch(1L), retirement.newEpoch)
        assertEquals(listOf(queued.id), retirement.cancelled)
        assertEquals(listOf(started.id), retirement.indeterminate)

        val tombstones = store.tombstones(partition).success().associateBy { it.workId }
        assertEquals(WorkTerminalClass.CANCELLED, tombstones.getValue(queued.id).terminalClass)
        assertEquals(WorkTerminalClass.INDETERMINATE, tombstones.getValue(started.id).terminalClass)

        // The successor epoch starts a fresh FIFO sequence under the new binding.
        val fresh = store.submit(partition, text("fresh"), 11L).success()
        assertEquals(0L, fresh.sequence)
        assertEquals(WorkEpoch(1L), fresh.epoch)
        assertEquals(fresh.id, checkNotNull(store.claimNext(partition, "worker-2", 12L).success()).work.id)
        // Preserve now requires the new binding.
        assertEquals(WorkEpoch(1L), store.preserveEpoch(partition, "binding-2", 13L).success())
        assertTrue(store.preserveEpoch(partition, "binding-1", 14L).failure() is WorkStoreFailure.Stale)
    }

    @Test
    fun retireInstanceCoversEveryQueueOfInstance() {
        val store = newStore()
        val turnsWork = store.submit(partition, text("turn"), 1L).success()
        val toolsWork = store.submit(toolsQueue, text("tool"), 2L).success()

        val retirements = store.retireInstance(WorkInstanceId("instance-1"), WorkEpochResetCause.SOS, "binding-2", 10L).success()
        assertEquals(2, retirements.size)
        assertTrue(retirements.all { it.newEpoch == WorkEpoch(1L) })

        assertEquals(WorkTerminalClass.CANCELLED, store.tombstones(partition).success().single().terminalClass)
        assertEquals(WorkTerminalClass.CANCELLED, store.tombstones(toolsQueue).success().single().terminalClass)
        assertNull(store.claimNext(partition, "worker", 11L).success())
        assertNull(store.claimNext(toolsQueue, "worker", 11L).success())
        assertTrue(turnsWork.id != toolsWork.id)
    }

    @Test
    fun packageIsolationAcrossRepositoriesAndInstances() {
        val store = newStore()
        store.submit(partition, text("mine"), 1L).success()
        val theirs = store.submit(otherPackage, text("theirs"), 2L).success()

        // Retiring one package never touches another.
        store.retireEpoch(partition, WorkEpochResetCause.PACKAGE_REMOVED, "binding-2", 10L).success()
        val theirSnapshot = store.snapshot(otherPackage).success()
        assertEquals(1, theirSnapshot.works.size)
        assertEquals(WorkEpoch(0L), theirSnapshot.record.epoch)
        assertEquals(theirs.id, theirSnapshot.works.single().id)

        // Corrupting one partition never touches another.
        partitionFile(partition).writeText("garbage")
        val restarted = restart()
        assertTrue(restarted.submit(partition, text("retry"), 11L).failure() is WorkStoreFailure.Corrupted)
        restarted.submit(otherPackage, text("still-here"), 12L).success()
        assertEquals(2, restarted.snapshot(otherPackage).success().works.size)

        // Deleting one instance never touches another.
        restarted.deleteInstance(WorkInstanceId("instance-1")).success()
        assertTrue(!partitionFile(partition).exists())
        assertEquals(2, restarted.snapshot(otherPackage).success().works.size)
    }

    @Test
    fun shutdownPreparationReclaimsSafelyAndIndeterminatesAmbiguous() {
        val store = newStore()
        val ambiguous = store.submit(partition, text("ambiguous"), 1L).success()
        val queued = store.submit(partition, text("queued"), 2L).success()
        val safeClaim = store.submit(otherPackage, text("safe"), 3L).success()
        store.claimNext(partition, "worker-a", 4L).success()
        store.beginEffect(ambiguous.id, "worker-a", "completion:1", "fp-1").success()
        store.claimNext(otherPackage, "worker-b", 5L).success()

        val plan = store.prepareForShutdown(10L).success()
        assertEquals(listOf(safeClaim.id), plan.reclaimed[otherPackage])
        assertEquals(listOf(ambiguous.id), plan.indeterminate[partition])

        // Durable state after orderly shutdown: safe claims queued, ambiguous indeterminate.
        val restarted = restart()
        val partitionSnapshot = restarted.snapshot(partition).success()
        assertEquals(listOf(queued.id), partitionSnapshot.works.map { it.id })
        assertEquals(WorkTerminalClass.INDETERMINATE, partitionSnapshot.tombstones.single().terminalClass)
        val otherSnapshot = restarted.snapshot(otherPackage).success()
        assertEquals(WorkState.QUEUED, otherSnapshot.works.single().state)
    }

    @Test
    fun corruptionFailsClosedWithoutMergingPartialData() {
        val store = newStore()
        store.submit(partition, text("committed"), 1L).success()
        store.submit(otherPackage, text("healthy"), 2L).success()
        // Half-written document: structurally plausible JSON with missing members.
        partitionFile(partition).writeText("""{"schemaVersion":1,"repositoryId":9,"instanceId":"instance-1"""")

        val restarted = restart()
        // No partial interpretation: the partition is unavailable, not half-loaded.
        assertTrue(restarted.snapshot(partition).failure() is WorkStoreFailure.NotFound)
        assertTrue(restarted.claimNext(partition, "worker", 10L).failure() is WorkStoreFailure.Corrupted)
        assertEquals(WorkQueueAvailability.CORRUPTED, restarted.projection(partition).availability)

        // The healthy package queue is fully decoded and operational.
        val healthy = restarted.snapshot(otherPackage).success()
        assertEquals("healthy", (healthy.works.single().payload as WorkValue.Text).value)
        restarted.submit(otherPackage, text("more"), 11L).success()
    }

    @Test
    fun tombstonesRemainBoundedAcrossRetirements() {
        val store = newStore(DurableWorkBounds(maxTombstonesPerQueue = 2))
        repeat(3) { index -> store.submit(partition, text("t$index"), index.toLong()).success() }

        store.retireEpoch(partition, WorkEpochResetCause.EXPLICIT_RESET, "binding-2", 10L).success()
        val tombstones = store.tombstones(partition).success()
        assertEquals(listOf(1L, 2L), tombstones.map { it.sequence })
        assertTrue(tombstones.all { it.terminalClass == WorkTerminalClass.CANCELLED })
    }

    @Test
    fun instanceDeletionRemovesOnlyThatInstance() {
        val store = newStore()
        store.submit(partition, text("gone"), 1L).success()
        store.submit(otherPackage, text("kept"), 2L).success()

        assertEquals(1, store.deleteInstance(WorkInstanceId("instance-1")).success())
        assertTrue(!partitionFile(partition).exists())
        assertTrue(store.partitions() == listOf(otherPackage))
        assertEquals(1, store.snapshot(otherPackage).success().works.size)
    }
}
