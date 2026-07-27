package io.talkcan.service

import io.talkcan.audio.AcquiredPlaybackRoute
import io.talkcan.audio.ActivePcmPlayback
import io.talkcan.audio.AudioRouteEndpoint
import io.talkcan.audio.PlaybackCompletion
import io.talkcan.audio.PlaybackRouteAcquisition
import io.talkcan.audio.PlaybackRouteStrategy
import io.talkcan.audio.RecordedPcm
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Behavior tests for the process-wide half-duplex audio admission owner.
 *
 * These tests exercise the admission races, terminal ownership, and cue-debounce
 * propagation without any Android audio object: [PlaybackRouteStrategy] and
 * [ActivePcmPlayback] are faked so the coordinator's state machine is the only
 * subject.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HostAudioCoordinatorTest {

    @Test
    fun reserveCaptureFromIdleGrantsLeaseAndOwensAdmission() {
        val coordinator = HostAudioCoordinator()

        val admission = coordinator.reserveCapture()

        assertTrue(admission is HostCaptureAdmission.Granted)
        val lease = (admission as HostCaptureAdmission.Granted).lease
        assertNotNull(lease.operationId)
        assertTrue(lease.operationId.value.isNotBlank())

        // A second reservation while the first capture lease is outstanding must be Busy.
        val second = coordinator.reserveCapture()
        assertEquals(HostCaptureAdmission.Busy, second)
    }

    @Test
    fun reserveCaptureDuringActivePlaybackIsRejectedAndLatchesRejectedPressPendingRelease() = runTest {
        val coordinator = HostAudioCoordinator()
        val playback = ControllablePlayback()
        val strategy = FakeRouteStrategy(FakeAcquiredRoute(playback))

        val playJob = async {
            coordinator.play(RecordedPcm(ShortArray(1), 16_000)) { strategy }
        }
        playback.started.await()

        // A PTT press during active playback must be rejected.
        val admission = coordinator.reserveCapture()
        assertTrue(admission is HostCaptureAdmission.RejectedByPlayback)

        // The coordinator latches a pending release for the rejected press.
        assertTrue(coordinator.consumeRejectedPttRelease())

        // A second consume returns false: the latch is single-shot.
        assertFalse(coordinator.consumeRejectedPttRelease())

        // Complete playback to clean up.
        playback.complete(PlaybackCompletion.Completed)
        playJob.await()
    }

    @Test
    fun commitCaptureWithMatchingLeaseSucceedsButMismatchedLeaseFails() {
        val coordinator = HostAudioCoordinator()

        val lease = coordinator.reserveCapture() as HostCaptureAdmission.Granted
        assertTrue(coordinator.commitCapture(lease.lease))
        // Idempotent commit is allowed.
        assertTrue(coordinator.commitCapture(lease.lease))

        // A fabricated lease must not commit.
        val foreignLease = HostCaptureLease(HostAudioOperationId("foreign"))
        assertFalse(coordinator.commitCapture(foreignLease))
    }

    @Test
    fun releaseCaptureClearsOwnershipAndAllowsSubsequentPlayback() = runTest {
        val coordinator = HostAudioCoordinator()

        val lease = coordinator.reserveCapture() as HostCaptureAdmission.Granted
        assertTrue(coordinator.releaseCapture(lease.lease))

        // After release, playback must be admitted.
        val playback = ControllablePlayback()
        val strategy = FakeRouteStrategy(FakeAcquiredRoute(playback))
        val deferred = async {
            coordinator.play(RecordedPcm(ShortArray(1), 16_000)) { strategy }
        }
        playback.started.await()
        playback.complete(PlaybackCompletion.Completed)
        assertEquals(HostPlaybackResult.Completed, deferred.await())
    }

    @Test
    fun releaseCaptureWithMismatchedLeaseIsIgnoredAndKeepsOwnership() {
        val coordinator = HostAudioCoordinator()

        val lease = coordinator.reserveCapture() as HostCaptureAdmission.Granted
        val foreignLease = HostCaptureLease(HostAudioOperationId("foreign"))
        assertFalse(coordinator.releaseCapture(foreignLease))

        // The original lease still owns; a new reserve must still be Busy.
        assertEquals(HostCaptureAdmission.Busy, coordinator.reserveCapture())
    }

    @Test
    fun playFromIdleAcquiresRouteAndReturnsCompletionResult() = runTest {
        val coordinator = HostAudioCoordinator()
        val playback = ControllablePlayback()
        val route = FakeAcquiredRoute(playback)
        val strategy = FakeRouteStrategy(route)

        val deferred = async {
            coordinator.play(RecordedPcm(ShortArray(1), 16_000)) { strategy }
        }
        playback.started.await()
        playback.complete(PlaybackCompletion.Completed)

        assertEquals(HostPlaybackResult.Completed, deferred.await())
        assertTrue(route.released)
        assertEquals(1, route.releaseCount)
    }

    @Test
    fun playWhileCaptureOwnsAdmissionReturnsBusyWithoutAcquiringRoute() = runTest {
        val coordinator = HostAudioCoordinator()
        coordinator.reserveCapture()
        val route = FakeAcquiredRoute(ControllablePlayback())
        val strategy = FakeRouteStrategy(route)

        val result = coordinator.play(RecordedPcm(ShortArray(1), 16_000)) { strategy }
        assertEquals(HostPlaybackResult.Busy, result)
        assertFalse(strategy.acquired)
        assertEquals(0, route.releaseCount)
    }

    @Test
    fun playWhenRouteAcquisitionIsUnavailableReturnsUnavailableAndReleasesReservation() = runTest {
        val coordinator = HostAudioCoordinator()
        val strategy = FakeRouteStrategy(failAcquire = PlaybackRouteAcquisition.Unavailable("no device"))

        val result = coordinator.play(RecordedPcm(ShortArray(1), 16_000)) { strategy }
        assertTrue(result is HostPlaybackResult.Unavailable)
        assertEquals("no device", (result as HostPlaybackResult.Unavailable).reason)

        // After the failed attempt, admission must be free again.
        assertTrue(coordinator.reserveCapture() is HostCaptureAdmission.Granted)
    }

    @Test
    fun playWhenRouteAcquisitionFailsReturnsFailedAndReleasesReservation() = runTest {
        val coordinator = HostAudioCoordinator()
        val strategy = FakeRouteStrategy(failAcquire = PlaybackRouteAcquisition.Failed("route error"))

        val result = coordinator.play(RecordedPcm(ShortArray(1), 16_000)) { strategy }
        assertTrue(result is HostPlaybackResult.Failed)

        // Admission is free again.
        assertTrue(coordinator.reserveCapture() is HostCaptureAdmission.Granted)
    }

    @Test
    fun playWhenRouteStartThrowsReturnsFailedAndReleasesRoute() = runTest {
        val coordinator = HostAudioCoordinator()
        val route = FakeAcquiredRoute(ControllablePlayback(), throwOnStart = true)
        val strategy = FakeRouteStrategy(route)

        val result = coordinator.play(RecordedPcm(ShortArray(1), 16_000)) { strategy }
        assertTrue(result is HostPlaybackResult.Failed)
        assertTrue(route.released)
        assertEquals(1, route.releaseCount)
    }

    @Test
    fun cancellationDuringRouteAcquisitionClearsReservationBeforeRethrowing() = runTest {
        val coordinator = HostAudioCoordinator()
        val strategy = SuspendingAcquireRouteStrategy()
        val play = async {
            coordinator.play(RecordedPcm(ShortArray(1), 16_000)) { strategy }
        }
        strategy.acquireStarted.await()

        // Reservation happens before route acquisition, so PTT remains rejected until cleanup.
        assertTrue(coordinator.reserveCapture() is HostCaptureAdmission.RejectedByPlayback)

        play.cancel()

        val cancellation = runCatching { play.await() }.exceptionOrNull()
        assertTrue(cancellation is CancellationException)
        assertTrue(coordinator.reserveCapture() is HostCaptureAdmission.Granted)
    }

    @Test
    fun cancellationDuringRouteStartReleasesRouteBeforeRethrowingAndAdmittingCapture() = runTest {
        val coordinator = HostAudioCoordinator()
        val route = StartBlockingRoute()
        val play = async {
            coordinator.play(RecordedPcm(ShortArray(1), 16_000)) { FakeRouteStrategy(route) }
        }
        route.startEntered.await()

        play.cancel()
        route.releaseStarted.await()

        // NonCancellable route cleanup is still in progress, so cancellation has not surfaced
        // and the playback reservation remains the active owner.
        assertFalse(play.isCompleted)
        assertTrue(coordinator.reserveCapture() is HostCaptureAdmission.RejectedByPlayback)

        route.allowRelease.complete(Unit)

        val cancellation = runCatching { play.await() }.exceptionOrNull()
        assertTrue(cancellation is CancellationException)
        assertTrue(route.released)
        assertEquals(1, route.releaseCount)
        assertTrue(coordinator.reserveCapture() is HostCaptureAdmission.Granted)
    }

    @Test
    fun cancellationDuringPlaybackWaitsForPhysicalCleanupThenRouteReleaseBeforeRethrowing() = runTest {
        val coordinator = HostAudioCoordinator()
        val playback = CleanupBlockingPlayback()
        val route = BlockingReleaseRoute(playback)
        val play = async {
            coordinator.play(RecordedPcm(ShortArray(1), 16_000)) { FakeRouteStrategy(route) }
        }
        playback.started.await()

        play.cancel()
        playback.skipObserved.await()

        // The pump has been asked to stop, but it has not physically cleaned up yet. Neither
        // route release nor capture admission may get ahead of that terminal cleanup.
        assertFalse(playback.physicalCleanupComplete)
        assertFalse(route.releaseStarted.isCompleted)
        assertTrue(coordinator.reserveCapture() is HostCaptureAdmission.RejectedByPlayback)

        playback.completeAfterPhysicalCleanup(PlaybackCompletion.ExplicitlySkipped)
        route.releaseStarted.await()

        assertTrue(playback.awaitReturned.isCompleted)
        assertTrue(playback.physicalCleanupComplete)
        assertFalse(play.isCompleted)
        assertTrue(coordinator.reserveCapture() is HostCaptureAdmission.RejectedByPlayback)

        route.allowRelease.complete(Unit)

        val cancellation = runCatching { play.await() }.exceptionOrNull()
        assertTrue(cancellation is CancellationException)
        assertTrue(route.released)
        assertEquals(1, route.releaseCount)
        assertTrue(coordinator.reserveCapture() is HostCaptureAdmission.Granted)
    }

    @Test
    fun failedPlaybackReleasesRouteOnlyAfterItsPhysicalCleanupAndThenFreesAdmission() = runTest {
        val coordinator = HostAudioCoordinator()
        val playback = CleanupBlockingPlayback()
        val route = BlockingReleaseRoute(playback)
        val play = async {
            coordinator.play(RecordedPcm(ShortArray(1), 16_000)) { FakeRouteStrategy(route) }
        }
        playback.started.await()

        playback.completeAfterPhysicalCleanup(PlaybackCompletion.Failed("track write failed"))
        route.releaseStarted.await()

        // A failed pump is still an owning playback until its cleanup and route release finish.
        assertTrue(playback.awaitReturned.isCompleted)
        assertTrue(playback.physicalCleanupComplete)
        assertTrue(coordinator.reserveCapture() is HostCaptureAdmission.RejectedByPlayback)

        route.allowRelease.complete(Unit)

        assertEquals(HostPlaybackResult.Failed("track write failed"), play.await())
        assertTrue(route.released)
        assertEquals(1, route.releaseCount)
        assertTrue(coordinator.reserveCapture() is HostCaptureAdmission.Granted)
    }

    @Test
    fun playbackThatIsExplicitlySkippedReturnsExplicitlySkipped() = runTest {
        val coordinator = HostAudioCoordinator()
        val playback = ControllablePlayback()
        val route = FakeAcquiredRoute(playback)
        val strategy = FakeRouteStrategy(route)

        val deferred = async {
            coordinator.play(RecordedPcm(ShortArray(1), 16_000)) { strategy }
        }
        playback.started.await()
        playback.complete(PlaybackCompletion.ExplicitlySkipped)

        assertEquals(HostPlaybackResult.ExplicitlySkipped, deferred.await())
        assertTrue(route.released)
    }

    @Test
    fun playbackThatIsInterruptedReturnsInterrupted() = runTest {
        val coordinator = HostAudioCoordinator()
        val playback = ControllablePlayback()
        val route = FakeAcquiredRoute(playback)
        val strategy = FakeRouteStrategy(route)

        val deferred = async {
            coordinator.play(RecordedPcm(ShortArray(1), 16_000)) { strategy }
        }
        playback.started.await()
        playback.complete(PlaybackCompletion.Interrupted)

        assertEquals(HostPlaybackResult.Interrupted, deferred.await())
        assertTrue(route.released)
    }

    @Test
    fun consumeSosDuringPlaybackSkipsActivePlaybackAndReturnsConsumedByPlayback() = runTest {
        val coordinator = HostAudioCoordinator()
        val playback = ControllablePlayback()
        val route = FakeAcquiredRoute(playback)
        val strategy = FakeRouteStrategy(route)

        val deferred = async {
            coordinator.play(RecordedPcm(ShortArray(1), 16_000)) { strategy }
        }
        playback.started.await()

        val disposition = coordinator.consumeSosDuringPlayback()
        assertEquals(HostSosDisposition.ConsumedByPlayback, disposition)
        assertTrue(playback.skipRequested)

        // The skip propagates as ExplicitlySkipped through the coordinator.
        assertEquals(HostPlaybackResult.ExplicitlySkipped, deferred.await())
    }

    @Test
    fun consumeSosDuringPlaybackWhenIdleDispatchesToChannel() = runTest {
        val coordinator = HostAudioCoordinator()
        assertEquals(HostSosDisposition.DispatchToChannel, coordinator.consumeSosDuringPlayback())
    }

    @Test
    fun consumeSosDuringPlaybackWhenCaptureOwnsDispatchesToChannel() = runTest {
        val coordinator = HostAudioCoordinator()
        coordinator.reserveCapture()
        assertEquals(HostSosDisposition.DispatchToChannel, coordinator.consumeSosDuringPlayback())
    }

    @Test
    fun consumeSosDuringPlaybackIsIdempotentBeforeSkipCompletes() = runTest {
        val coordinator = HostAudioCoordinator()
        val playback = ControllablePlayback()
        val route = FakeAcquiredRoute(playback)
        val strategy = FakeRouteStrategy(route)

        val deferred = async {
            coordinator.play(RecordedPcm(ShortArray(1), 16_000)) { strategy }
        }
        playback.started.await()

        // First SOS is consumed by playback.
        assertEquals(HostSosDisposition.ConsumedByPlayback, coordinator.consumeSosDuringPlayback())

        // A second SOS before the skip completes still returns ConsumedByPlayback:
        // the playback owner has not released yet (the skip completion has not propagated).
        assertEquals(HostSosDisposition.ConsumedByPlayback, coordinator.consumeSosDuringPlayback())

        // The skip from the first SOS already completed the playback.
        deferred.await()
    }

    @Test
    fun closeReleasesActivePlaybackAndRejectsFutureAdmission() = runTest {
        val coordinator = HostAudioCoordinator()
        val playback = ControllablePlayback()
        val route = FakeAcquiredRoute(playback)
        val strategy = FakeRouteStrategy(route)

        val deferred = async {
            coordinator.play(RecordedPcm(ShortArray(1), 16_000)) { strategy }
        }
        playback.started.await()

        coordinator.close()
        assertTrue(playback.skipRequested)

        // After close, both capture and playback are rejected.
        assertEquals(HostCaptureAdmission.Closed, coordinator.reserveCapture())
        assertEquals(HostPlaybackResult.Closed, coordinator.play(RecordedPcm(ShortArray(1), 16_000)) { strategy })

        // The play that was interrupted by close resolves.
        // The play that was interrupted by close resolves as ExplicitlySkipped:
        // close() skips the playback, and the pump reports the skip completion.
        assertEquals(HostPlaybackResult.ExplicitlySkipped, deferred.await())
    }

    @Test
    fun pttRejectedDuringPlaybackTriggersToneExactlyOnce() = runTest {
        val coordinator = HostAudioCoordinator()
        val playback = ControllablePlayback()
        val route = FakeAcquiredRoute(playback)
        val strategy = FakeRouteStrategy(route)

        val deferred = async {
            coordinator.play(RecordedPcm(ShortArray(1), 16_000)) { strategy }
        }
        playback.started.await()

        // First rejected PTT press triggers the tone.
        val first = coordinator.reserveCapture()
        assertTrue(first is HostCaptureAdmission.RejectedByPlayback)
        assertTrue(playback.toneRejected)

        // A second PTT press while the first tone is still latched does not queue a second tone.
        playback.toneRejected = false
        val second = coordinator.reserveCapture()
        assertTrue(second is HostCaptureAdmission.RejectedByPlayback)
        assertFalse(playback.toneRejected)

        playback.complete(PlaybackCompletion.Completed)
        deferred.await()
    }

    @Test
    fun closeWhileCaptureLeaseOutstandingRejectsFutureReservations() = runTest {
        val coordinator = HostAudioCoordinator()
        coordinator.reserveCapture()

        coordinator.close()

        assertEquals(HostCaptureAdmission.Closed, coordinator.reserveCapture())
    }

    @Test
    fun preemptPreviewPlaybackDuringPreviewAdmitsCaptureAndReleasesRoute() = runTest {
        val coordinator = HostAudioCoordinator()
        val playback = ControllablePlayback()
        val route = FakeAcquiredRoute(playback)

        val playJob = async {
            coordinator.play(
                RecordedPcm(ShortArray(1), 16_000),
                kind = HostPlaybackKind.PREVIEW,
            ) { FakeRouteStrategy(route) }
        }
        playback.started.await()

        // The explicit preemption boundary synchronously releases preview-owned admission.
        assertTrue(coordinator.preemptPreviewPlayback())
        assertTrue(playback.skipRequested)

        // Operational capture is now admitted deterministically instead of being rejected.
        assertTrue(coordinator.reserveCapture() is HostCaptureAdmission.Granted)

        // The preempted preview play is skipped and releases its route exactly once.
        assertEquals(HostPlaybackResult.ExplicitlySkipped, playJob.await())
        assertTrue(route.released)
        assertEquals(1, route.releaseCount)
    }

    @Test
    fun preemptPreviewPlaybackNeverTouchesOperationalPlayback() = runTest {
        val coordinator = HostAudioCoordinator()
        val playback = ControllablePlayback()
        val route = FakeAcquiredRoute(playback)

        val playJob = async {
            coordinator.play(RecordedPcm(ShortArray(1), 16_000)) { FakeRouteStrategy(route) }
        }
        playback.started.await()

        // Default-kind operational playback keeps the existing rejection behavior.
        assertFalse(coordinator.preemptPreviewPlayback())
        assertFalse(playback.skipRequested)
        assertTrue(coordinator.reserveCapture() is HostCaptureAdmission.RejectedByPlayback)

        playback.complete(PlaybackCompletion.Completed)
        assertEquals(HostPlaybackResult.Completed, playJob.await())
    }

    @Test
    fun onAcquiredObservesRouteBeforePlaybackStarts() = runTest {
        val coordinator = HostAudioCoordinator()
        val playback = ControllablePlayback()
        val route = FakeAcquiredRoute(playback)
        val observed = mutableListOf<AcquiredPlaybackRoute>()

        val playJob = async {
            coordinator.play(
                RecordedPcm(ShortArray(1), 16_000),
                onAcquired = { observed += it },
            ) { FakeRouteStrategy(route) }
        }
        playback.started.await()

        assertEquals(listOf<AcquiredPlaybackRoute>(route), observed)

        playback.complete(PlaybackCompletion.Completed)
        assertEquals(HostPlaybackResult.Completed, playJob.await())
    }

    // ------------------------------------------------------------------
    // Fakes
    // ------------------------------------------------------------------

    internal class FakeRouteStrategy(
        private val route: AcquiredPlaybackRoute? = null,
        private val failAcquire: PlaybackRouteAcquisition? = null,
    ) : PlaybackRouteStrategy {
        var acquired = false
            private set

        override suspend fun acquire(): PlaybackRouteAcquisition {
            acquired = true
            failAcquire?.let { return it }
            return PlaybackRouteAcquisition.Acquired(route!!)
        }
    }

    internal class FakeAcquiredRoute(
        private val playback: ActivePcmPlayback,
        private val throwOnStart: Boolean = false,
    ) : AcquiredPlaybackRoute {
        override val endpoint: AudioRouteEndpoint = AudioRouteEndpoint.Unspecified

        var released = false
            private set
        var releaseCount = 0
            private set

        override suspend fun start(recording: RecordedPcm): ActivePcmPlayback {
            if (throwOnStart) throw IllegalStateException("start failed")
            return playback
        }

        override suspend fun release() {
            released = true
            releaseCount += 1
        }
    }

    /** Holds route release open to prove cancellation cannot escape its NonCancellable cleanup. */
    internal class BlockingReleaseRoute(
        private val playback: ActivePcmPlayback,
    ) : AcquiredPlaybackRoute {
        override val endpoint: AudioRouteEndpoint = AudioRouteEndpoint.Unspecified

        val releaseStarted = CompletableDeferred<Unit>()
        val allowRelease = CompletableDeferred<Unit>()
        var released = false
            private set
        var releaseCount = 0
            private set

        override suspend fun start(recording: RecordedPcm): ActivePcmPlayback = playback

        override suspend fun release() {
            releaseStarted.complete(Unit)
            allowRelease.await()
            released = true
            releaseCount += 1
        }
    }

    /** Acquires a route but suspends before returning a playback, exposing the start window. */
    internal class StartBlockingRoute : AcquiredPlaybackRoute {
        override val endpoint: AudioRouteEndpoint = AudioRouteEndpoint.Unspecified

        val startEntered = CompletableDeferred<Unit>()
        val releaseStarted = CompletableDeferred<Unit>()
        val allowRelease = CompletableDeferred<Unit>()
        var released = false
            private set
        var releaseCount = 0
            private set

        override suspend fun start(recording: RecordedPcm): ActivePcmPlayback {
            startEntered.complete(Unit)
            return CompletableDeferred<ActivePcmPlayback>().await()
        }

        override suspend fun release() {
            releaseStarted.complete(Unit)
            allowRelease.await()
            released = true
            releaseCount += 1
        }
    }

    /** Reserves playback and then suspends while the coordinator is awaiting route acquisition. */
    internal class SuspendingAcquireRouteStrategy : PlaybackRouteStrategy {
        val acquireStarted = CompletableDeferred<Unit>()

        override suspend fun acquire(): PlaybackRouteAcquisition {
            acquireStarted.complete(Unit)
            return CompletableDeferred<PlaybackRouteAcquisition>().await()
        }
    }

    /**
     * Models a pump that cannot publish completion until its physical AudioTrack cleanup is done.
     * Tests control that terminal boundary directly rather than relying on wall-clock scheduling.
     */
    internal class CleanupBlockingPlayback : ActivePcmPlayback {
        val started = CompletableDeferred<Unit>()
        val skipObserved = CompletableDeferred<Unit>()
        val awaitReturned = CompletableDeferred<Unit>()
        private val completion = CompletableDeferred<PlaybackCompletion>()

        var physicalCleanupComplete = false
            private set

        fun completeAfterPhysicalCleanup(result: PlaybackCompletion) {
            physicalCleanupComplete = true
            completion.complete(result)
        }

        override suspend fun awaitCompletion(): PlaybackCompletion {
            started.complete(Unit)
            return completion.await().also { awaitReturned.complete(Unit) }
        }

        override fun rejectPttWithTone(): Boolean = completion.isActive

        override fun skip(): Boolean {
            if (!completion.isActive) return false
            skipObserved.complete(Unit)
            return true
        }
    }

    /**
     * A controllable [ActivePcmPlayback] whose completion is driven by the test
     * or by [skip]. [skip] completes the deferred with [PlaybackCompletion.ExplicitlySkipped],
     * mirroring the real pump-loop behavior where a skip request causes the stream
     * to terminate as explicitly skipped.
     */
    internal class ControllablePlayback : ActivePcmPlayback {
        val started = CompletableDeferred<Unit>()
        private val completion = CompletableDeferred<PlaybackCompletion>()
        private val toneSlot = java.util.concurrent.atomic.AtomicBoolean(false)

        var toneRejected = false
        var skipRequested = false
            private set

        fun complete(result: PlaybackCompletion) {
            started.complete(Unit)
            completion.complete(result)
        }

        override suspend fun awaitCompletion(): PlaybackCompletion {
            started.complete(Unit)
            return completion.await()
        }

        override fun rejectPttWithTone(): Boolean {
            // Single-slot debounce: at most one tone is latched at a time, mirroring
            // ActiveStreamPcmPlayback.toneSlot.
            if (!completion.isActive) return false
            val latched = toneSlot.compareAndSet(false, true)
            if (latched) toneRejected = true
            return latched
        }

        override fun skip(): Boolean {
            if (!completion.isActive) return false
            if (skipRequested) return false
            skipRequested = true
            // Skip wins; cancel any latched tone (mirrors ActiveStreamPcmPlayback).
            toneSlot.set(false)
            // Mirror the real pump: skip terminates the stream as explicitly skipped.
            started.complete(Unit)
            completion.complete(PlaybackCompletion.ExplicitlySkipped)
            return true
        }
    }
}