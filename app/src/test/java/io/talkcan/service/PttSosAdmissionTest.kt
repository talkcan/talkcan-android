package io.talkcan.service

import io.talkcan.audio.AcquiredPlaybackRoute
import io.talkcan.audio.ActivePcmPlayback
import io.talkcan.audio.AudioRouteEndpoint
import io.talkcan.audio.PlaybackCompletion
import io.talkcan.audio.PlaybackRouteAcquisition
import io.talkcan.audio.PlaybackRouteStrategy
import io.talkcan.audio.RecordedPcm
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PTT/SOS integration tests at the host audio admission boundary.
 *
 * [PttDispatcher] and [PttAudioSessionManager] require Android audio plumbing and are not
 * unit-testable without a full capture service. These tests verify the half-duplex
 * admission invariants that the dispatcher relies on:
 *
 * 1. A PTT press during active playback is rejected before any auto-transition or input
 *    session reservation could occur (the coordinator's synchronous [reserveCapture] is
 *    the gate).
 * 2. A PTT release that pairs with a rejected press is inert (consumes the latch without
 *    touching a session).
 * 3. An SOS press during active playback is consumed by the playback (skip + pause) and
 *    does not dispatch to the channel.
 * 4. An SOS press while the host is idle dispatches to the channel.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PttSosAdmissionTest {

    @Test
    fun pttPressDuringPlaybackIsRejectedBeforeAutoTransition() = runTest {
        val coordinator = HostAudioCoordinator()
        val playback = ControllablePlayback()
        val strategy = FakeRouteStrategy(FakeAcquiredRoute(playback))

        val playJob = async {
            coordinator.play(RecordedPcm(ShortArray(1), 16_000)) { strategy }
        }
        playback.started.await()

        // The PTT ingress calls reserveCapture() synchronously before autoTransitionFor().
        // During active playback this must return RejectedByPlayback.
        val admission = coordinator.reserveCapture()
        assertTrue(admission is HostCaptureAdmission.RejectedByPlayback)

        // The coordinator did NOT grant a capture lease; there is no owner change.
        // A second reserve is also rejected (playback still owns).
        val secondAdmission = coordinator.reserveCapture()
        assertTrue(secondAdmission is HostCaptureAdmission.RejectedByPlayback)

        // The rejected press latched a pending-release flag.
        assertTrue(coordinator.consumeRejectedPttRelease())

        playback.complete(PlaybackCompletion.Completed)
        playJob.await()
    }

    @Test
    fun pttReleasePairedWithRejectedPressIsInertAndDoesNotTouchSessionState() = runTest {
        val coordinator = HostAudioCoordinator()
        val playback = ControllablePlayback()
        val strategy = FakeRouteStrategy(FakeAcquiredRoute(playback))

        val playJob = async {
            coordinator.play(RecordedPcm(ShortArray(1), 16_000)) { strategy }
        }
        playback.started.await()

        // PTT press rejected during playback.
        assertTrue(coordinator.reserveCapture() is HostCaptureAdmission.RejectedByPlayback)

        // The release for this rejected press consumes the latch and is inert:
        // it does not release a capture lease (none was granted), and the playback
        // continues uninterrupted.
        assertTrue(coordinator.consumeRejectedPttRelease())

        // The playback is still the owner.
        assertFalse(playback.skipRequested)

        // A second release for the same rejected press finds no latch.
        assertFalse(coordinator.consumeRejectedPttRelease())

        playback.complete(PlaybackCompletion.Completed)
        playJob.await()
    }

    @Test
    fun pttReleaseWhenNoSessionOrLatchIsInert() {
        val coordinator = HostAudioCoordinator()

        // No active session, no rejected-press latch: consume returns false.
        assertFalse(coordinator.consumeRejectedPttRelease())

        // No capture lease: release with a fabricated lease returns false.
        val foreignLease = HostCaptureLease(HostAudioOperationId("never-granted"))
        assertFalse(coordinator.releaseCapture(foreignLease))
    }

    @Test
    fun activeSosDuringPlaybackSkipsPlaybackAndIsConsumedByPlayback() = runTest {
        val coordinator = HostAudioCoordinator()
        val playback = ControllablePlayback()
        val strategy = PlaybackRouteStrategy { PlaybackRouteAcquisition.Acquired(FakeAcquiredRoute(playback)) }

        val playJob = async {
            coordinator.play(RecordedPcm(ShortArray(1), 16_000)) { strategy }
        }
        playback.started.await()

        // SOS during playback: the host audio boundary consumes the SOS and skips playback.
        val disposition = coordinator.consumeSosDuringPlayback()
        assertEquals(HostSosDisposition.ConsumedByPlayback, disposition)

        // The playback was asked to skip — no channel dispatch should occur.
        assertTrue(playback.skipRequested)

        // The skip resolves the play as ExplicitlySkipped.
        assertEquals(HostPlaybackResult.ExplicitlySkipped, playJob.await())
    }

    @Test
    fun idleSosWhenNoPlaybackOwnsDispatchesToChannel() = runTest {
        val coordinator = HostAudioCoordinator()

        // With no active playback, SOS dispatches to the channel.
        assertEquals(HostSosDisposition.DispatchToChannel, coordinator.consumeSosDuringPlayback())
    }

    @Test
    fun idleSosWhenCaptureLeaseOwnsDispatchesToChannel() = runTest {
        val coordinator = HostAudioCoordinator()
        val lease = coordinator.reserveCapture() as HostCaptureAdmission.Granted

        // Capture owns admission, not playback. SOS still dispatches to the channel
        // (the SOS does not interrupt capture).
        assertEquals(HostSosDisposition.DispatchToChannel, coordinator.consumeSosDuringPlayback())

        // The capture lease is unaffected.
        assertTrue(coordinator.releaseCapture(lease.lease))
    }

    @Test
    fun pttPressAfterPlaybackCompletesIsAdmitted() = runTest {
        val coordinator = HostAudioCoordinator()
        val playback = ControllablePlayback()
        val strategy = PlaybackRouteStrategy { PlaybackRouteAcquisition.Acquired(FakeAcquiredRoute(playback)) }

        val playJob = async {
            coordinator.play(RecordedPcm(ShortArray(1), 16_000)) { strategy }
        }
        playback.started.await()
        playback.complete(PlaybackCompletion.Completed)
        playJob.await()

        // After playback completes, a PTT press must be admitted.
        val admission = coordinator.reserveCapture()
        assertTrue(admission is HostCaptureAdmission.Granted)
    }

    // ------------------------------------------------------------------
    // Fakes
    // ------------------------------------------------------------------

    internal class FakeRouteStrategy(
        private val route: AcquiredPlaybackRoute,
    ) : PlaybackRouteStrategy {
        override suspend fun acquire(): PlaybackRouteAcquisition = PlaybackRouteAcquisition.Acquired(route)
    }

    internal class FakeAcquiredRoute(
        private val playback: ActivePcmPlayback,
    ) : AcquiredPlaybackRoute {
        override val endpoint: AudioRouteEndpoint = AudioRouteEndpoint.Unspecified
        var released = false
            private set

        override suspend fun start(recording: RecordedPcm): ActivePcmPlayback = playback
        override suspend fun release() { released = true }
    }

    internal class ControllablePlayback : ActivePcmPlayback {
        val started = CompletableDeferred<Unit>()
        private val completion = CompletableDeferred<PlaybackCompletion>()
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

        override fun rejectPttWithTone(): Boolean = true

        override fun skip(): Boolean {
            if (!completion.isActive) return false
            if (skipRequested) return false
            skipRequested = true
            started.complete(Unit)
            completion.complete(PlaybackCompletion.ExplicitlySkipped)
            return true
        }
    }
}