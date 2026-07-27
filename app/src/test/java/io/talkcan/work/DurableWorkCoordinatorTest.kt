package io.talkcan.work

import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Task 11.9: coroutine coordinator conformance — polling-free wakeups and generic metadata. */
@OptIn(ExperimentalCoroutinesApi::class)
class DurableWorkCoordinatorTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val counter = AtomicInteger()
    private val partition = WorkQueuePartition(WorkRepositoryId(13L), WorkInstanceId("instance-1"), WorkQueueId("turns"))

    private fun newStore(): DurableWorkStore =
        DurableWorkStore(folder.root, DurableWorkBounds()) { "work-${counter.getAndIncrement()}" }.also {
            it.load().success()
        }

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
    fun receiveSuspendsWithoutPollingUntilSubmission() = runTest {
        val store = newStore()
        val coordinator = DurableWorkCoordinator(store)

        val pending = async { coordinator.receive(partition, "worker", 1L) }
        advanceUntilIdle()
        assertFalse(pending.isCompleted)
        assertEquals(1, coordinator.waitingCount(partition))
        // No delay-based polling: virtual time never advanced.
        assertEquals(0L, testScheduler.currentTime)

        store.submit(partition, text("turn"), 2L).success()
        advanceUntilIdle()
        assertTrue(pending.isCompleted)
        val outcome = pending.await().success()
        assertTrue(outcome is WorkReceiveOutcome.Claimed)
        assertEquals(text("turn"), (outcome as WorkReceiveOutcome.Claimed).claim.payload)
        assertEquals(0L, testScheduler.currentTime)
        assertEquals(0, coordinator.waitingCount(partition))
    }

    @Test
    fun waitersClaimInFifoOrderWithOneActiveJob() = runTest {
        val store = newStore()
        val coordinator = DurableWorkCoordinator(store)

        val receiverA = async { coordinator.receive(partition, "worker-a", 1L) }
        val receiverB = async { coordinator.receive(partition, "worker-b", 1L) }
        advanceUntilIdle()
        assertEquals(2, coordinator.waitingCount(partition))

        store.submit(partition, text("first"), 2L).success()
        advanceUntilIdle()
        assertTrue(receiverA.isCompleted)
        assertFalse(receiverB.isCompleted)
        val claimA = receiverA.await().success() as WorkReceiveOutcome.Claimed
        assertEquals(0L, claimA.claim.work.sequence)

        // One active job per queue: the second submission stays queued until the job terminates.
        store.submit(partition, text("second"), 3L).success()
        advanceUntilIdle()
        assertFalse(receiverB.isCompleted)

        store.completeWork(claimA.claim.work.id, "worker-a", 4L, playbackHandedOff = true).success()
        advanceUntilIdle()
        assertTrue(receiverB.isCompleted)
        val claimB = receiverB.await().success() as WorkReceiveOutcome.Claimed
        assertEquals(1L, claimB.claim.work.sequence)
        assertEquals(text("second"), claimB.claim.payload)
    }

    @Test
    fun closeWakesWaitersWithClosedOutcome() = runTest {
        val store = newStore()
        val coordinator = DurableWorkCoordinator(store)

        val pending = async { coordinator.receive(partition, "worker", 1L) }
        advanceUntilIdle()
        assertFalse(pending.isCompleted)

        coordinator.close(partition)
        advanceUntilIdle()
        assertTrue(pending.isCompleted)
        assertEquals(WorkReceiveOutcome.Closed, pending.await().success())
        // Later receives on the closed partition return Closed immediately.
        assertEquals(WorkReceiveOutcome.Closed, coordinator.receive(partition, "worker", 2L).success())
    }

    @Test
    fun closeAllClosesPendingAndFutureReceives() = runTest {
        val store = newStore()
        val coordinator = DurableWorkCoordinator(store)

        val pending = async { coordinator.receive(partition, "worker", 1L) }
        advanceUntilIdle()
        coordinator.closeAll()
        advanceUntilIdle()
        assertEquals(WorkReceiveOutcome.Closed, pending.await().success())
        assertEquals(WorkReceiveOutcome.Closed, coordinator.receive(partition, "worker", 2L).success())
    }

    @Test
    fun boundedConcurrentReceivesRejectExcessWaiters() = runTest {
        val store = newStore()
        val coordinator = DurableWorkCoordinator(store, maxConcurrentReceivesPerQueue = 2)

        val first = async { coordinator.receive(partition, "worker-a", 1L) }
        val second = async { coordinator.receive(partition, "worker-b", 1L) }
        advanceUntilIdle()
        assertEquals(2, coordinator.waitingCount(partition))

        val third = async { coordinator.receive(partition, "worker-c", 1L) }
        advanceUntilIdle()
        assertTrue(third.isCompleted)
        assertTrue(third.await().failure() is WorkStoreFailure.Busy)
        assertFalse(first.isCompleted)
        assertFalse(second.isCompleted)
        coordinator.closeAll()
        advanceUntilIdle()
        assertEquals(WorkReceiveOutcome.Closed, first.await().success())
        assertEquals(WorkReceiveOutcome.Closed, second.await().success())
    }

    @Test
    fun safeClaimReleaseWakesWaitingReceiver() = runTest {
        val store = newStore()
        val coordinator = DurableWorkCoordinator(store)

        store.submit(partition, text("turn"), 1L).success()
        val claim = checkNotNull(store.claimNext(partition, "worker-a", 2L).success())

        val pending = async { coordinator.receive(partition, "worker-b", 3L) }
        advanceUntilIdle()
        assertFalse(pending.isCompleted)

        // Release without started effects: the same work becomes claimable and wakes the waiter.
        store.releaseClaim(claim.work.id, "worker-a", 4L).success()
        advanceUntilIdle()
        assertTrue(pending.isCompleted)
        val reclaimed = pending.await().success() as WorkReceiveOutcome.Claimed
        assertEquals(claim.work.id, reclaimed.claim.work.id)
    }

    @Test
    fun cancelledWaiterDoesNotStrandQueue() = runTest {
        val store = newStore()
        val coordinator = DurableWorkCoordinator(store)

        val pending = async { coordinator.receive(partition, "worker-a", 1L) }
        advanceUntilIdle()
        pending.cancel()
        advanceUntilIdle()
        assertEquals(0, coordinator.waitingCount(partition))

        store.submit(partition, text("turn"), 2L).success()
        val next = async { coordinator.receive(partition, "worker-b", 3L) }
        val outcome = next.await().success() as WorkReceiveOutcome.Claimed
        assertEquals(0L, outcome.claim.work.sequence)
    }

    @Test
    fun corruptedPartitionFailsReceiveClosed() = runTest {
        val store = newStore()
        store.submit(partition, text("doomed"), 1L).success()
        partitionFile().writeText("{ corrupt")

        val reopened = newStore()
        val coordinator = DurableWorkCoordinator(reopened)
        val pending = async { coordinator.receive(partition, "worker", 2L) }
        advanceUntilIdle()
        assertTrue(pending.isCompleted)
        assertTrue(pending.await().failure() is WorkStoreFailure.Corrupted)
        assertEquals(0, coordinator.waitingCount(partition))
    }

    @Test
    fun instanceProjectionExposesGenericMetadataOnly() = runTest {
        val store = newStore()
        val coordinator = DurableWorkCoordinator(store)
        store.submit(partition, text("one"), 1L).success()
        val second = store.submit(partition, text("two"), 2L).success()
        store.claimNext(partition, "worker", 3L).success()
        // Terminal authority requires the active claim; a queued item cannot be completed.
        assertTrue(
            store.completeWork(second.id, "worker", 4L, playbackHandedOff = true).failure() is WorkStoreFailure.Stale,
        )

        val projections = coordinator.instanceProjection(WorkInstanceId("instance-1"))
        assertEquals(1, projections.size)
        val projection = projections.single()
        assertEquals(partition, projection.partition)
        assertEquals(WorkQueueAvailability.AVAILABLE, projection.availability)
        assertEquals(1, projection.queuedCount)
        assertTrue(projection.activePresent)
        assertTrue(projection.lastTerminalClass == null)
    }
}
