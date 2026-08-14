package io.talkcan.service

import io.talkcan.audio.AudioRouteEndpoint
import io.talkcan.audio.CaptureServiceFakes
import io.talkcan.audio.CaptureSource
import io.talkcan.audio.ChannelAudioInputSession
import io.talkcan.audio.ChannelInputAcceptance
import io.talkcan.audio.ChannelInputResult
import io.talkcan.audio.ChannelInputTarget
import io.talkcan.audio.CapturePolicy
import io.talkcan.audio.CaptureSourceId
import io.talkcan.audio.OpenedCaptureSource
import io.talkcan.audio.PcmOutput
import io.talkcan.audio.RecordedPcm
import io.talkcan.audio.ResolvedAudioRoute
import io.talkcan.audio.RouteGate
import io.talkcan.audio.RouteGateResult
import io.talkcan.audio.ScoRoute
import io.talkcan.audio.TelecomCapturePcmOutput
import io.talkcan.audio.ResponsePlayer
import io.talkcan.channel.capability.AudioOperationArtifact
import io.talkcan.channel.capability.RuntimeGeneration
import io.talkcan.model.InputMode
import io.talkcan.model.PttSource
import io.talkcan.model.ScoState
import io.mockk.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import io.talkcan.audio.CaptureCompletion
import io.talkcan.audio.CaptureService
import io.talkcan.audio.CaptureSession
import io.talkcan.audio.CaptureStartResult
import io.talkcan.audio.CaptureStartupEvidence
import io.talkcan.service.CommittedTargetLeaseOwner
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PttAudioSessionManagerTest {
    @Test
    fun singleActiveSessionSpansRsmPhoneAndCarSources() = runTest {
        val fixture = Fixture(this)

        assertTrue(fixture.manager.start(PttSource.Rsm, "echo", InputMode.Work))
        runCurrent()
        assertFalse(fixture.manager.start(PttSource.Phone, "echo", InputMode.OnAPinch))

        fixture.manager.release(PttSource.Rsm)
        advanceUntilIdle()

        assertTrue(fixture.manager.start(PttSource.Phone, "echo", InputMode.OnAPinch))
        runCurrent()
        assertFalse(fixture.manager.start(PttSource.CarTelecom, "echo", InputMode.OnTheRoad))

        fixture.manager.release(PttSource.Phone)
        advanceUntilIdle()

        assertTrue(fixture.manager.start(PttSource.CarTelecom, "echo", InputMode.OnTheRoad))
        runCurrent()
        fixture.manager.release(PttSource.CarTelecom)
        advanceUntilIdle()

        assertEquals(listOf("started", "released", "started", "released", "started", "released"), fixture.router.events)
    }

    @Test
    fun serviceTeardownReleasesActiveRouteExactlyOnce() = runTest {
        val fixture = Fixture(this)
        val route = fixture.route(InputMode.Work)

        assertTrue(fixture.manager.start(PttSource.Rsm, "echo", InputMode.Work))
        runCurrent()
        fixture.manager.shutdownAndAwait("teardown")
        advanceUntilIdle()

        assertEquals(1, route.output.releaseRouteCount)
        assertEquals(listOf("started", "cancelled:teardown"), fixture.router.events)
        assertEquals(null, fixture.manager.activeSession)
    }

    @Test
    fun matchingSourceCancellationAcceptsPendingAndActiveSessions() = runTest {
        val pendingFixture = Fixture(this)
        assertTrue(pendingFixture.manager.reservePending(PttSource.Rsm, "echo", InputMode.Work))

        val pendingOutcome = pendingFixture.manager.cancelBySource(
            source = PttSource.Rsm,
            eligibility = PttAudioSessionManager.CancellationEligibility.PendingOrActive,
            reason = "rsm serial session ended",
        )

        assertEquals(PttAudioSessionManager.CancellationDisposition.Accepted, pendingOutcome.disposition)
        assertEquals(PttSource.Rsm, pendingOutcome.sessionSource)
        assertEquals(PttAudioSessionManager.CancellationSessionPhase.Pending, pendingOutcome.sessionPhase)
        advanceUntilIdle()
        assertEquals(null, pendingFixture.manager.activeSession)

        val activeFixture = Fixture(this)
        val route = activeFixture.route(InputMode.OnAPinch)
        assertTrue(activeFixture.manager.start(PttSource.Phone, "echo", InputMode.OnAPinch))
        runCurrent()

        val activeOutcome = activeFixture.manager.cancelBySource(
            source = PttSource.Phone,
            eligibility = PttAudioSessionManager.CancellationEligibility.PendingOrActive,
            reason = "phone cancelled",
        )

        assertEquals(PttAudioSessionManager.CancellationDisposition.Accepted, activeOutcome.disposition)
        assertEquals(PttSource.Phone, activeOutcome.sessionSource)
        assertEquals(PttAudioSessionManager.CancellationSessionPhase.Active, activeOutcome.sessionPhase)
        advanceUntilIdle()
        assertEquals(listOf("started", "cancelled:phone cancelled"), activeFixture.router.events)
        assertEquals(1, route.output.releaseRouteCount)
        assertEquals(null, activeFixture.manager.activeSession)
    }

    @Test
    fun rsmAndCarCancellationCannotClaimPhoneCaptureAndPhoneCleanupIsExactlyOnce() = runTest {
        val events = mutableListOf<String>()
        val capture = TerminalCaptureSession(events)
        val target = TerminalTarget(events)
        val fixture = TerminalFixture(
            scope = this,
            target = target,
            capture = capture,
            output = TerminalOutput(events),
            events = events,
        )
        assertTrue(fixture.manager.start(PttSource.Phone, "journal", InputMode.OnAPinch))
        runCurrent()
        val phoneSession = fixture.manager.activeSession
        events.clear()

        listOf(PttSource.Rsm, PttSource.CarTelecom).forEach { requestingSource ->
            val outcome = fixture.manager.cancelBySource(
                source = requestingSource,
                eligibility = PttAudioSessionManager.CancellationEligibility.PendingOrActive,
                reason = "unrelated lifecycle",
            )
            assertEquals(PttAudioSessionManager.CancellationDisposition.SourceMismatch, outcome.disposition)
            assertEquals(PttSource.Phone, outcome.sessionSource)
            assertEquals(phoneSession, fixture.manager.activeSession)
        }
        assertTrue(events.isEmpty())

        assertEquals(
            PttAudioSessionManager.CancellationDisposition.Accepted,
            fixture.manager.cancelBySource(
                source = PttSource.Phone,
                eligibility = PttAudioSessionManager.CancellationEligibility.PendingOrActive,
                reason = "phone cancelled",
            ).disposition,
        )
        advanceUntilIdle()

        assertEquals(
            listOf("capture-cancel", "target-cancelled", "route-release", "lease-release", "completion"),
            events,
        )
        assertEquals(1, target.cancelCount)
        assertEquals(1, fixture.output.releaseCount)
        assertEquals(1, target.leaseReleaseCount)
        assertEquals(null, fixture.manager.activeSession)
    }

    @Test
    fun pendingOnlyCancellationRejectsAnActiveCaptureWithoutTerminalEffects() = runTest {
        val events = mutableListOf<String>()
        val target = TerminalTarget(events)
        val fixture = TerminalFixture(
            scope = this,
            target = target,
            capture = TerminalCaptureSession(events),
            output = TerminalOutput(events),
            events = events,
        )
        assertTrue(fixture.manager.start(PttSource.CarTelecom, "journal", InputMode.OnTheRoad))
        runCurrent()
        events.clear()

        val rejected = fixture.manager.cancelBySource(
            source = PttSource.CarTelecom,
            eligibility = PttAudioSessionManager.CancellationEligibility.PendingOnly,
            reason = "car setup failed",
        )

        assertEquals(PttAudioSessionManager.CancellationDisposition.NotPending, rejected.disposition)
        assertEquals(PttAudioSessionManager.CancellationSessionPhase.Active, rejected.sessionPhase)
        assertEquals(PttSource.CarTelecom, fixture.manager.activeSession?.source)
        assertTrue(events.isEmpty())

        fixture.manager.cancelBySource(
            source = PttSource.CarTelecom,
            eligibility = PttAudioSessionManager.CancellationEligibility.PendingOrActive,
            reason = "telecom connection ended",
        )
        advanceUntilIdle()
        assertEquals(1, target.cancelCount)
        assertEquals(1, fixture.output.releaseCount)
    }

    @Test
    fun repeatedCancellationDuringAbortRecursionIsAlreadyTerminalAndDoesNotRepeatEffects() = runTest {
        val events = mutableListOf<String>()
        val cancellationGate = CompletableDeferred<Unit>()
        val target = TerminalTarget(events)
        val fixture = TerminalFixture(
            scope = this,
            target = target,
            capture = TerminalCaptureSession(events),
            output = TerminalOutput(events),
            events = events,
            cancellationGate = cancellationGate,
        )
        assertTrue(fixture.manager.start(PttSource.CarTelecom, "journal", InputMode.OnTheRoad))
        runCurrent()
        events.clear()

        val first = fixture.manager.cancelBySource(
            source = PttSource.CarTelecom,
            eligibility = PttAudioSessionManager.CancellationEligibility.PendingOrActive,
            reason = "telecom route timeout",
        )
        runCurrent()
        val recursiveAbort = fixture.manager.cancelBySource(
            source = PttSource.CarTelecom,
            eligibility = PttAudioSessionManager.CancellationEligibility.PendingOrActive,
            reason = "telecom abort callback",
        )

        assertEquals(PttAudioSessionManager.CancellationDisposition.Accepted, first.disposition)
        assertEquals(PttAudioSessionManager.CancellationDisposition.AlreadyTerminal, recursiveAbort.disposition)
        assertEquals(listOf("capture-cancel"), events)

        cancellationGate.complete(Unit)
        advanceUntilIdle()

        assertEquals(
            listOf("capture-cancel", "target-cancelled", "route-release", "lease-release", "completion"),
            events,
        )
        assertEquals(1, target.cancelCount)
        assertEquals(1, fixture.output.releaseCount)
        assertEquals(1, target.leaseReleaseCount)
    }

    @Test
    fun serviceTeardownCancelsEachSourceRegardlessOfOwnership() = runTest {
        listOf(
            PttSource.Rsm to InputMode.Work,
            PttSource.Phone to InputMode.OnAPinch,
            PttSource.CarTelecom to InputMode.OnTheRoad,
        ).forEach { (source, mode) ->
            val fixture = Fixture(this)
            val route = fixture.route(mode)
            assertTrue(fixture.manager.start(source, "echo", mode))
            runCurrent()

            val outcome = fixture.manager.shutdownAndAwait("service teardown")

            assertEquals(PttAudioSessionManager.CancellationDisposition.Accepted, outcome.disposition)
            assertEquals(null, outcome.requestedSource)
            assertEquals(source, outcome.sessionSource)
            assertEquals(listOf("started", "cancelled:service teardown"), fixture.router.events)
            assertEquals(1, route.output.releaseRouteCount)
            assertEquals(null, fixture.manager.activeSession)
        }
    }
    @Test
    fun normalCarReleaseNotifiesCompletionAfterCleanupWhenConnectionEndedArrivesEarly() = runTest {
        val fixture = Fixture(this)
        val route = fixture.route(InputMode.OnTheRoad)
        val terminalGate = CompletableDeferred<Unit>()
        val terminalRecordings = mutableListOf<RecordedPcm>()
        var cancellationCount = 0
        val target = object : ChannelInputTarget {
            override val capturePolicy = CapturePolicy(60_000L)
            override suspend fun onInputStarted(session: ChannelAudioInputSession) = Unit

            override suspend fun onInputReleased(recording: RecordedPcm): ChannelInputResult {
                terminalRecordings += recording
                terminalGate.await()
                return ChannelInputResult.None
            }

            override suspend fun onInputCancelled(reason: String) {
                cancellationCount += 1
            }

            override suspend fun onInputFailed(reason: String) = Unit
        }
        fixture.router.acceptance = ChannelInputAcceptance.Accepted(target)

        assertTrue(fixture.manager.start(PttSource.CarTelecom, "journal", InputMode.OnTheRoad))
        runCurrent()
        assertEquals(
            PttAudioSessionManager.SessionPhase.PttHeld,
            fixture.manager.activeSession?.phase,
        )

        fixture.manager.release(PttSource.CarTelecom)
        runCurrent()
        fixture.manager.cancelBySource(
            PttSource.CarTelecom,
            PttAudioSessionManager.CancellationEligibility.PendingOrActive,
            "connection-ended",
        )
        runCurrent()
        assertEquals(
            PttAudioSessionManager.SessionPhase.TerminalWork,
            fixture.manager.activeSession?.phase,
        )

        assertEquals(1, terminalRecordings.size)
        assertEquals(listOf<Short>(1, 2, 3), terminalRecordings.single().samples.toList())
        assertEquals(0, cancellationCount)
        assertEquals(0, route.output.releaseRouteCount)
        assertTrue(fixture.terminalCompletionObservations.isEmpty())

        terminalGate.complete(Unit)
        advanceUntilIdle()

        assertEquals(1, terminalRecordings.size)
        assertEquals(0, cancellationCount)
        assertEquals(1, route.output.releaseRouteCount)
        assertEquals(null, fixture.manager.activeSession)
        val completion = fixture.terminalCompletionObservations.single()
        assertEquals(InputMode.OnTheRoad, completion.mode)
        assertEquals(null, completion.activeSession)
        assertEquals(1, completion.routeReleaseCount)
    }

    @Test
    fun forceCancelDuringResolvedRouteGateReleasesTelecomOutputExactlyOnce() = runTest {
        val fixture = Fixture(this)
        val route = fixture.route(InputMode.OnTheRoad)
        val gate = CompletableDeferred<RouteGateResult>()
        route.routeGate = RouteGate("car-readiness") { gate.await() }

        assertTrue(fixture.manager.start(PttSource.CarTelecom, "journal", InputMode.OnTheRoad))
        runCurrent()
        fixture.manager.cancelBySource(
            PttSource.CarTelecom,
            PttAudioSessionManager.CancellationEligibility.PendingOrActive,
            "connection-ended",
        )
        gate.complete(RouteGateResult.Success("car route ready"))
        advanceUntilIdle()

        assertEquals(1, route.output.releaseRouteCount)
        assertTrue(fixture.router.events.isEmpty())
        assertEquals(null, fixture.manager.activeSession)
    }

    @Test
    fun forceCancelDuringRecorderPreflightReleasesTelecomOutputExactlyOnce() = runTest {
        val fixture = Fixture(this)
        val route = fixture.route(InputMode.OnTheRoad)
        val source = DeferredOpenCaptureSource(shortArrayOf(11, 12))
        route.source = source

        assertTrue(fixture.manager.start(PttSource.CarTelecom, "journal", InputMode.OnTheRoad))
        runCurrent()
        assertTrue(source.openStarted.isCompleted)

        fixture.manager.cancelBySource(
            PttSource.CarTelecom,
            PttAudioSessionManager.CancellationEligibility.PendingOrActive,
            "connection-ended",
        )
        source.allowOpen.complete(Unit)
        advanceUntilIdle()

        assertEquals(1, route.output.releaseRouteCount)
        assertEquals(listOf("cancelled:connection-ended"), fixture.router.events)
        assertEquals(null, fixture.manager.activeSession)
    }

    @Test
    fun forceCancelDuringReadyBeepReleasesTelecomOutputExactlyOnce() = runTest {
        val fixture = Fixture(this)
        val route = fixture.route(InputMode.OnTheRoad)
        val beepStarted = CompletableDeferred<Unit>()
        val allowBeep = CompletableDeferred<Unit>()
        route.output.readyBeepStarted = beepStarted
        route.output.readyBeepGate = allowBeep

        assertTrue(fixture.manager.start(PttSource.CarTelecom, "journal", InputMode.OnTheRoad))
        runCurrent()
        assertTrue(beepStarted.isCompleted)

        fixture.manager.cancelBySource(
            PttSource.CarTelecom,
            PttAudioSessionManager.CancellationEligibility.PendingOrActive,
            "connection-ended",
        )
        allowBeep.complete(Unit)
        advanceUntilIdle()

        assertEquals(1, route.output.releaseRouteCount)
        assertEquals(listOf("cancelled:connection-ended"), fixture.router.events)
        assertEquals(null, fixture.manager.activeSession)
    }

    @Test
    fun shortCarPressDuringRecorderPreflightReleasesTelecomOutputOnceWithoutVisiblePcm() = runTest {
        val fixture = Fixture(this)
        val route = fixture.route(InputMode.OnTheRoad)
        val source = DeferredOpenCaptureSource(shortArrayOf(21, 22))
        route.source = source

        assertTrue(fixture.manager.start(PttSource.CarTelecom, "journal", InputMode.OnTheRoad))
        runCurrent()
        fixture.manager.release(PttSource.CarTelecom)
        source.allowOpen.complete(Unit)
        advanceUntilIdle()

        assertEquals(1, route.output.releaseRouteCount)
        assertTrue(fixture.router.events.none { it == "started" || it == "released" })
        assertTrue(fixture.router.liveFrames.isEmpty())
        assertTrue(fixture.router.recordings.isEmpty())
    }

    @Test
    fun shortCarPressDuringReadyBeepReleasesTelecomOutputOnceWithoutVisiblePcm() = runTest {
        val fixture = Fixture(this)
        val route = fixture.route(InputMode.OnTheRoad)
        val beepStarted = CompletableDeferred<Unit>()
        val allowBeep = CompletableDeferred<Unit>()
        route.output.readyBeepStarted = beepStarted
        route.output.readyBeepGate = allowBeep

        assertTrue(fixture.manager.start(PttSource.CarTelecom, "journal", InputMode.OnTheRoad))
        runCurrent()
        assertTrue(beepStarted.isCompleted)

        fixture.manager.release(PttSource.CarTelecom)
        allowBeep.complete(Unit)
        advanceUntilIdle()

        assertEquals(1, route.output.releaseRouteCount)
        assertTrue(fixture.router.events.none { it == "started" || it == "released" })
        assertTrue(fixture.router.liveFrames.isEmpty())
        assertTrue(fixture.router.recordings.isEmpty())
    }



    @Test
    fun wrongSourceReleaseDoesNotClearActiveSession() = runTest {
        val fixture = Fixture(this)
        val route = fixture.route(InputMode.Work)

        assertTrue(fixture.manager.start(PttSource.Rsm, "echo", InputMode.Work))
        runCurrent()
        fixture.manager.release(PttSource.Phone)
        runCurrent()

        assertEquals(PttSource.Rsm, fixture.manager.activeSession?.source)
        assertEquals(0, route.output.releaseRouteCount)

        fixture.manager.release(PttSource.Rsm)
        advanceUntilIdle()
        assertEquals(1, route.output.releaseRouteCount)
    }

    @Test
    fun staleOldSourceReleaseCannotClearNewerSession() = runTest {
        val fixture = Fixture(this)
        val oldRoute = fixture.route(InputMode.Work)
        val newRoute = fixture.route(InputMode.OnAPinch)

        assertTrue(fixture.manager.start(PttSource.Rsm, "echo", InputMode.Work))
        runCurrent()
        fixture.manager.release(PttSource.Rsm)
        advanceUntilIdle()
        assertEquals(1, oldRoute.output.releaseRouteCount)

        assertTrue(fixture.manager.start(PttSource.Phone, "echo", InputMode.OnAPinch))
        runCurrent()
        fixture.manager.release(PttSource.Rsm)
        runCurrent()

        assertEquals(PttSource.Phone, fixture.manager.activeSession?.source)
        assertEquals(0, newRoute.output.releaseRouteCount)

        fixture.manager.release(PttSource.Phone)
        advanceUntilIdle()
    }

    @Test
    fun channelReceivesInputSessionAndTerminalPcmWithoutRouteObjects() = runTest {
        val fixture = Fixture(this, pcm = shortArrayOf(10, 20, 30))

        assertTrue(fixture.manager.start(PttSource.Phone, "echo", InputMode.OnAPinch))
        runCurrent()
        fixture.manager.release(PttSource.Phone)
        advanceUntilIdle()

        assertEquals(16_000, fixture.router.startedSampleRates.single())
        assertEquals(listOf<Short>(10, 20, 30), fixture.router.recordings.single().samples.toList())
    }

    @Test
    fun releaseDuringSetupAfterAcquireReleasesScoExactlyOnce() = runTest {
        val fixture = Fixture(this)
        val route = fixture.route(InputMode.Work)
        val acquireGate = kotlinx.coroutines.CompletableDeferred<Unit>()
        route.sco.acquireGate = acquireGate

        assertTrue(fixture.manager.start(PttSource.Rsm, "echo", InputMode.Work))
        runCurrent()
        fixture.manager.release(PttSource.Rsm)
        acquireGate.complete(Unit)
        advanceUntilIdle()

        assertEquals(0, route.sco.releaseCount)
        assertEquals(1, route.output.releaseRouteCount)
        assertEquals(null, fixture.manager.activeSession)
    }

    @Test
    fun pendingTelecomReservationBlocksPhoneAndRsmUntilCarStartsOrCancels() = runTest {
        val fixture = Fixture(this)

        assertTrue(fixture.manager.reservePending(PttSource.CarTelecom, "echo", InputMode.OnTheRoad))
        assertFalse(fixture.manager.start(PttSource.Phone, "echo", InputMode.OnAPinch))
        assertFalse(fixture.manager.start(PttSource.Rsm, "echo", InputMode.Work))

        assertTrue(fixture.manager.start(PttSource.CarTelecom, "echo", InputMode.OnTheRoad))
        runCurrent()
        fixture.manager.cancelBySource(
            PttSource.CarTelecom,
            PttAudioSessionManager.CancellationEligibility.PendingOrActive,
            "timeout",
        )
        advanceUntilIdle()

        assertEquals(null, fixture.manager.activeSession)
    }

    @Test
    fun routeGateTimeoutFailsClosedWithoutStartingCapture() = runTest {
        val fixture = Fixture(this)
        val route = fixture.route(InputMode.OnTheRoad)
        val source = CaptureServiceFakes.singleShotSource(shortArrayOf(9))
        route.source = source
        route.routeGate = RouteGate("release-work-before-car") {
            RouteGateResult.Timeout("Timed out waiting for target RSM route release")
        }

        assertTrue(fixture.manager.start(PttSource.CarTelecom, "echo", InputMode.OnTheRoad))
        advanceUntilIdle()

        assertEquals(emptyList<String>(), fixture.router.events)
        assertEquals(0, route.output.readyBeepCount)
        assertEquals(1, route.output.errorBeepCount)
        assertEquals("timeout must not be treated as capture readiness", 0, source.openCount)
        assertEquals(1, route.output.releaseRouteCount)
        assertEquals(null, fixture.manager.activeSession)
    }

    @Test
    fun routeGateCancellationReachesChannelOnlyAsCancellation() = runTest {
        val fixture = Fixture(this)
        val route = fixture.route(InputMode.OnTheRoad)
        val source = CaptureServiceFakes.singleShotSource(shortArrayOf(9))
        route.source = source
        route.routeGate = RouteGate("release-work-before-car") {
            RouteGateResult.Cancellation("stale Work route release cancelled")
        }

        assertTrue(fixture.manager.start(PttSource.CarTelecom, "echo", InputMode.OnTheRoad))
        advanceUntilIdle()

        assertEquals(emptyList<String>(), fixture.router.events)
        assertEquals(0, source.openCount)
        assertEquals(1, route.output.releaseRouteCount)
        assertEquals(0, route.output.readyBeepCount)
        assertEquals(1, route.output.errorBeepCount)
    }

    @Test
    fun captureStartsOnlyAfterRouteGateAndRecorderOpenBothSucceed() = runTest {
        val fixture = Fixture(this)
        val route = fixture.route(InputMode.OnTheRoad)
        val gate = CompletableDeferred<RouteGateResult>()
        val source = DeferredOpenCaptureSource(shortArrayOf(7, 8))
        route.routeGate = RouteGate("car-readiness") { gate.await() }
        route.source = source

        assertTrue(fixture.manager.start(PttSource.CarTelecom, "echo", InputMode.OnTheRoad))
        runCurrent()
        assertEquals(emptyList<String>(), fixture.router.events)
        assertEquals(0, source.openCount)

        gate.complete(RouteGateResult.Success("car route ready"))
        runCurrent()
        assertEquals(emptyList<String>(), fixture.router.events)
        assertEquals(0, source.openCount)
        assertTrue(source.openStarted.isCompleted)

        source.allowOpen.complete(Unit)
        runCurrent()

        assertEquals(listOf("started"), fixture.router.events)
        assertEquals(1, source.openCount)
        assertEquals(0, route.output.releaseRouteCount)
        assertEquals(
            listOf("prepare:echo", "ready:Car", "started:echo"),
            fixture.timeline,
        )
        fixture.manager.release(PttSource.CarTelecom)
        advanceUntilIdle()
    }

    @Test
    fun pendingKeyboardRecoveryExcludesOtherPttUntilAcceptedThenStartsOnce() = runTest {
        val fixture = Fixture(this)
        val route = fixture.route(InputMode.OnTheRoad)
        val source = CaptureServiceFakes.singleShotSource(shortArrayOf(9))
        fixture.router.preparationGate = CompletableDeferred()
        fixture.router.preparationStarted = CompletableDeferred()
        route.source = source

        assertTrue(fixture.manager.start(PttSource.Phone, "keyboard", InputMode.OnTheRoad))
        runCurrent()

        assertTrue(fixture.router.preparationStarted?.isCompleted == true)
        assertEquals(PttAudioSessionManager.SessionPhase.PttHeld, fixture.manager.activeSession?.phase)
        assertEquals(1, fixture.router.prepareCallCount)
        assertEquals(0, source.openCount)
        assertEquals(0, route.output.readyBeepCount)
        assertEquals(0, route.output.errorBeepCount)
        assertFalse(fixture.manager.start(PttSource.Phone, "keyboard", InputMode.OnTheRoad))
        assertFalse(fixture.manager.start(PttSource.Rsm, "journal", InputMode.Work))
        assertEquals(1, fixture.router.prepareCallCount)

        fixture.router.preparationGate?.complete(Unit)
        runCurrent()

        assertEquals(
            listOf("prepare:keyboard", "ready:Car", "started:keyboard"),
            fixture.timeline,
        )
        assertEquals(1, source.openCount)
        assertEquals(1, route.output.readyBeepCount)
        assertEquals(0, route.output.errorBeepCount)

        fixture.manager.release(PttSource.Phone)
        advanceUntilIdle()
    }

    @Test
    fun recoveryRefusalOrTimeoutPlaysProblemBeepWithoutCaptureOrReadyBeep() = runTest {
        for (reason in listOf("Sleepwalker connection failed", "Sleepwalker connection timed out")) {
            val fixture = Fixture(this)
            val route = fixture.route(InputMode.OnTheRoad)
            val source = CaptureServiceFakes.singleShotSource(shortArrayOf(9))
            fixture.router.preparationGate = CompletableDeferred()
            fixture.router.preparationStarted = CompletableDeferred()
            fixture.router.acceptance = ChannelInputAcceptance.Refused(reason)
            route.source = source

            assertTrue(fixture.manager.start(PttSource.Phone, "keyboard", InputMode.OnTheRoad))
            runCurrent()

            assertTrue(fixture.router.preparationStarted?.isCompleted == true)
            assertEquals(0, source.openCount)
            assertEquals(0, route.output.readyBeepCount)
            assertEquals(0, route.output.errorBeepCount)

            fixture.router.preparationGate?.complete(Unit)
            advanceUntilIdle()

            assertEquals(listOf("prepare:keyboard", "problem:Car"), fixture.timeline)
            assertTrue(fixture.router.events.isEmpty())
            assertEquals(0, source.openCount)
            assertEquals(0, route.output.readyBeepCount)
            assertEquals(1, route.output.errorBeepCount)
            assertEquals(1, route.output.releaseRouteCount)
            assertEquals(null, fixture.manager.activeSession)
        }
    }

    @Test
    fun releaseDuringKeyboardRecoveryCannotReviveCaptureAfterAcceptance() = runTest {
        val fixture = Fixture(this)
        val route = fixture.route(InputMode.OnTheRoad)
        val source = CaptureServiceFakes.singleShotSource(shortArrayOf(9))
        fixture.router.preparationGate = CompletableDeferred()
        fixture.router.preparationStarted = CompletableDeferred()
        route.source = source

        assertTrue(fixture.manager.start(PttSource.Phone, "keyboard", InputMode.OnTheRoad))
        runCurrent()
        assertTrue(fixture.router.preparationStarted?.isCompleted == true)

        fixture.manager.release(PttSource.Phone)
        assertEquals(PttAudioSessionManager.SessionPhase.TerminalWork, fixture.manager.activeSession?.phase)
        assertFalse(fixture.manager.start(PttSource.Rsm, "journal", InputMode.Work))

        fixture.router.preparationGate?.complete(Unit)
        advanceUntilIdle()

        assertTrue(fixture.router.events.isEmpty())
        assertEquals(0, source.openCount)
        assertEquals(0, route.output.readyBeepCount)
        assertEquals(0, route.output.errorBeepCount)
        assertEquals(1, route.output.releaseRouteCount)
        assertEquals(1, fixture.terminalCompletionObservations.size)
        assertEquals(null, fixture.manager.activeSession)
    }

    @Test
    fun cancellationDuringKeyboardRecoveryCannotReviveCaptureAfterAcceptance() = runTest {
        val fixture = Fixture(this)
        val route = fixture.route(InputMode.OnTheRoad)
        val source = CaptureServiceFakes.singleShotSource(shortArrayOf(9))
        fixture.router.preparationGate = CompletableDeferred()
        fixture.router.preparationStarted = CompletableDeferred()
        route.source = source

        assertTrue(fixture.manager.start(PttSource.Phone, "keyboard", InputMode.OnTheRoad))
        runCurrent()
        assertTrue(fixture.router.preparationStarted?.isCompleted == true)

        assertEquals(
            PttAudioSessionManager.CancellationDisposition.Accepted,
            fixture.manager.cancelBySource(
                PttSource.Phone,
                PttAudioSessionManager.CancellationEligibility.PendingOnly,
                "source lost",
            ).disposition,
        )
        assertEquals(
            PttAudioSessionManager.CancellationDisposition.AlreadyTerminal,
            fixture.manager.cancelBySource(
                PttSource.Phone,
                PttAudioSessionManager.CancellationEligibility.PendingOnly,
                "source lost",
            ).disposition,
        )
        fixture.manager.release(PttSource.Phone)
        assertEquals(PttAudioSessionManager.SessionPhase.TerminalWork, fixture.manager.activeSession?.phase)

        fixture.router.preparationGate?.complete(Unit)
        advanceUntilIdle()

        assertTrue(fixture.router.events.isEmpty())
        assertEquals(0, source.openCount)
        assertEquals(0, route.output.readyBeepCount)
        assertEquals(0, route.output.errorBeepCount)
        assertEquals(1, route.output.releaseRouteCount)
        assertEquals(1, fixture.terminalCompletionObservations.size)
        assertEquals(null, fixture.manager.activeSession)
    }

    @Test
    fun channelRefusalPlaysProblemBeepWithoutReadyBeepOrCapture() = runTest {
        val fixture = Fixture(this)
        val route = fixture.route(InputMode.OnTheRoad)
        val source = CaptureServiceFakes.singleShotSource(shortArrayOf(9))
        route.source = source
        fixture.router.acceptance = ChannelInputAcceptance.Refused("Journal base directory unavailable")

        assertTrue(fixture.manager.start(PttSource.CarTelecom, "journal", InputMode.OnTheRoad))
        advanceUntilIdle()

        assertEquals(emptyList<String>(), fixture.router.events)
        assertEquals(0, source.openCount)
        assertEquals(0, route.output.readyBeepCount)
        assertEquals(1, route.output.errorBeepCount)
        assertEquals(1, route.output.releaseRouteCount)
        assertEquals(null, fixture.manager.activeSession)
    }

    @Test
    fun committedCarRoutePlaysReadyBeforeChannelVisibleStart() = runTest {
        val fixture = Fixture(this)
        val route = fixture.route(InputMode.OnTheRoad)

        assertTrue(fixture.manager.start(PttSource.CarTelecom, "journal", InputMode.OnTheRoad))
        runCurrent()

        assertEquals(
            listOf("prepare:journal", "ready:Car", "started:journal"),
            fixture.timeline,
        )
        assertEquals(1, route.output.readyBeepCount)
        assertEquals(0, route.output.errorBeepCount)

        fixture.manager.release(PttSource.CarTelecom)
        advanceUntilIdle()
    }

    @Test
    fun terminalReleaseUsesOriginallyCommittedTarget() = runTest {
        val fixture = Fixture(this)
        val targetEvents = mutableListOf<String>()
        val originalTarget = object : ChannelInputTarget {
            override val capturePolicy = CapturePolicy(60_000L)
            override suspend fun onInputStarted(session: ChannelAudioInputSession) {
                targetEvents += "original-started"
            }

            override suspend fun onInputReleased(recording: RecordedPcm): ChannelInputResult {
                targetEvents += "original-released"
                return ChannelInputResult.None
            }

            override suspend fun onInputCancelled(reason: String) {
                targetEvents += "original-cancelled"
            }

            override suspend fun onInputFailed(reason: String) {
                targetEvents += "original-failed"
            }
        }
        val replacementTarget = object : ChannelInputTarget {
            override val capturePolicy = CapturePolicy(60_000L)
            override suspend fun onInputStarted(session: ChannelAudioInputSession) {
                targetEvents += "replacement-started"
            }

            override suspend fun onInputReleased(recording: RecordedPcm): ChannelInputResult {
                targetEvents += "replacement-released"
                return ChannelInputResult.None
            }

            override suspend fun onInputCancelled(reason: String) {
                targetEvents += "replacement-cancelled"
            }

            override suspend fun onInputFailed(reason: String) {
                targetEvents += "replacement-failed"
            }
        }
        fixture.router.acceptance = ChannelInputAcceptance.Accepted(originalTarget)

        assertTrue(fixture.manager.start(PttSource.Phone, "debug", InputMode.OnAPinch))
        runCurrent()
        fixture.router.acceptance = ChannelInputAcceptance.Accepted(replacementTarget)
        fixture.manager.release(PttSource.Phone)
        advanceUntilIdle()

        assertEquals(listOf("original-started", "original-released"), targetEvents)
    }

    @Test
    fun postReadyLivePcmReachesCommittedTarget() = runTest {
        val fixture = Fixture(this)
        val source = GatedFrameCaptureSource(shortArrayOf(42, 43))
        fixture.route(InputMode.OnAPinch).source = source

        assertTrue(fixture.manager.start(PttSource.Phone, "debug", InputMode.OnAPinch))
        runCurrent()
        assertEquals(listOf("prepare:debug", "ready:Local", "started:debug"), fixture.timeline)

        source.allowFrames = true
        advanceTimeBy(2)
        runCurrent()

        assertEquals(listOf<Short>(42, 43), fixture.router.liveFrames.single().toList())

        fixture.manager.release(PttSource.Phone)
        advanceUntilIdle()
    }

    @Test
    fun captureStartupFailureAfterRouteReadinessReleasesRouteExactlyOnce() = runTest {
        val fixture = Fixture(this)
        val route = fixture.route(InputMode.OnTheRoad)
        route.routeGate = RouteGate("car-readiness") {
            RouteGateResult.Success("car route ready")
        }
        route.source = CaptureServiceFakes.failingSource()

        assertTrue(fixture.manager.start(PttSource.CarTelecom, "echo", InputMode.OnTheRoad))
        advanceUntilIdle()

        assertEquals(listOf("failed:Recording failed"), fixture.router.events)
        assertEquals(1, route.output.releaseRouteCount)
        assertEquals(null, fixture.manager.activeSession)
        assertEquals(0, route.output.readyBeepCount)
        assertEquals(1, route.output.errorBeepCount)
    }

    @Test
    fun warmWorkRouteCanBeReusedByConsecutiveWorkPtt() = runTest {
        val fixture = Fixture(this)
        val workRoute = fixture.route(InputMode.Work)

        assertTrue(fixture.manager.start(PttSource.Rsm, "echo", InputMode.Work))
        runCurrent()
        fixture.manager.release(PttSource.Rsm)
        advanceUntilIdle()

        assertTrue(fixture.manager.start(PttSource.Rsm, "echo", InputMode.Work))
        runCurrent()
        fixture.manager.release(PttSource.Rsm)
        advanceUntilIdle()

        assertEquals(listOf("started", "released", "started", "released"), fixture.router.events)
        assertEquals(2, workRoute.sco.acquireCount)
        assertEquals(2, workRoute.output.releaseRouteCount)
        assertEquals(0, fixture.route(InputMode.OnTheRoad).sco.acquireCount)
        assertEquals(0, fixture.route(InputMode.OnAPinch).sco.acquireCount)
    }

    @Test
    fun staleWorkRouteGateBlocksCarUntilObservedRelease() = runTest {
        val fixture = Fixture(this)
        val carRoute = fixture.route(InputMode.OnTheRoad)
        var workReleased = false
        carRoute.routeGate = RouteGate("release-work-before-car") {
            if (workReleased) {
                RouteGateResult.Success("target RSM released")
            } else {
                RouteGateResult.Timeout("Timed out waiting for target RSM route release")
            }
        }

        assertTrue(fixture.manager.start(PttSource.Rsm, "echo", InputMode.Work))
        runCurrent()
        fixture.manager.release(PttSource.Rsm)
        advanceUntilIdle()

        assertTrue(fixture.manager.start(PttSource.CarTelecom, "echo", InputMode.OnTheRoad))
        advanceUntilIdle()
        assertEquals(
            listOf("started", "released"),
            fixture.router.events,
        )
        assertEquals(0, carRoute.sco.acquireCount)
        assertEquals(1, carRoute.output.releaseRouteCount)

        workReleased = true
        assertTrue(fixture.manager.start(PttSource.CarTelecom, "echo", InputMode.OnTheRoad))
        runCurrent()

        assertEquals(
            listOf(
                "started",
                "released",
                "started",
            ),
            fixture.router.events,
        )
        assertEquals(1, carRoute.sco.acquireCount)
        fixture.manager.release(PttSource.CarTelecom)
        advanceUntilIdle()
    }

    @Test
    fun phoneCaptureAfterWarmWorkUsesLocalRouteWithoutChannelRoutePayload() = runTest {
        val fixture = Fixture(this)
        val localSource = ReadyAfterBeepCaptureSource(
            pcm = shortArrayOf(4, 5, 6),
            output = fixture.route(InputMode.OnAPinch).output,
            sourceId = CaptureSourceId.Mic,
        )
        fixture.route(InputMode.OnAPinch).source = localSource

        assertTrue(fixture.manager.start(PttSource.Rsm, "echo", InputMode.Work))
        runCurrent()
        fixture.manager.release(PttSource.Rsm)
        advanceUntilIdle()

        assertTrue(fixture.manager.start(PttSource.Phone, "echo", InputMode.OnAPinch))
        runCurrent()
        fixture.manager.release(PttSource.Phone)
        advanceUntilIdle()

        assertEquals(listOf("started", "released", "started", "released"), fixture.router.events)
        assertEquals(1, localSource.openCount)
        assertEquals(listOf<Short>(4, 5, 6), fixture.router.recordings.last().samples.toList())
        assertEquals(0, fixture.route(InputMode.OnTheRoad).sco.acquireCount)
    }
    @Test
    fun activeCarPlaybackReleasesTelecomRouteOnceBeforeMediaPlayback() = runTest {
        val events = mutableListOf<String>()
        val captureOutput = RecordingOutput(mutableListOf(), AudioRouteEndpoint.Car)
        val telecomOutput = TelecomCapturePcmOutput(
            captureOutput = captureOutput,
            mediaResponsePlayer = object : ResponsePlayer {
                override suspend fun play(recording: RecordedPcm) {
                    events += "media-playback"
                }
            },
            releaseCaptureRoute = { events += "telecom-release" },
            awaitTelecomDisconnected = { events += "disconnect-wait" },
        )
        val target = object : ChannelInputTarget {
            override val capturePolicy = CapturePolicy(60_000L)
            override suspend fun onInputStarted(session: ChannelAudioInputSession) = Unit

            override suspend fun onInputReleased(recording: RecordedPcm): ChannelInputResult {
                events += "channel-release"
                return ChannelInputResult.Playback(recording)
            }

            override fun onInputPlaybackCompleted() {
                events += "channel-playback-complete"
            }

            override suspend fun onInputCancelled(reason: String) {
                events += "channel-cancelled"
            }

            override suspend fun onInputFailed(reason: String) {
                events += "channel-failed"
            }
        }
        val manager = PttAudioSessionManager(
            scope = this,
            captureService = CaptureServiceFakes.newService(this),
            channelRouter = object : ChannelRouter {
                override suspend fun prepareInput(channelId: String): ChannelInputAcceptance =
                    ChannelInputAcceptance.Accepted(target)
            },
            resolvePttAudioRoute = {
                ResolvedAudioRoute(
                    sco = RecordingScoRoute(AudioRouteEndpoint.Car),
                    output = telecomOutput,
                    source = ReadyAfterBeepCaptureSource(
                        pcm = shortArrayOf(31, 32),
                        output = captureOutput,
                        sourceId = CaptureSourceId.VoiceCommunication,
                    ),
                    endpoint = AudioRouteEndpoint.Car,
                )
            },
            onTerminalCompleted = { events += "manager-terminal" },
        )

        assertTrue(manager.start(PttSource.CarTelecom, "echo", InputMode.OnTheRoad))
        runCurrent()
        assertEquals(PttAudioSessionManager.SessionPhase.PttHeld, manager.activeSession?.phase)

        manager.release(PttSource.CarTelecom)
        advanceUntilIdle()

        assertEquals(
            listOf(
                "channel-release",
                "telecom-release",
                "disconnect-wait",
                "media-playback",
                "channel-playback-complete",
                "manager-terminal",
            ),
            events,
        )
        assertEquals(null, manager.activeSession)
    }

    @Test
    fun normalReleaseKeepsFirstClaimWhenTargetRouteLeaseAndCompletionThrow() = runTest {
        val events = mutableListOf<String>()
        val releaseGate = CompletableDeferred<Unit>()
        val target = TerminalTarget(
            events = events,
            releaseGate = releaseGate,
            releaseFailure = IllegalStateException("target release failed"),
            leaseFailure = IllegalStateException("lease release failed"),
        )
        val output = TerminalOutput(events, releaseFailure = IllegalStateException("route release failed"))
        var completionCount = 0
        val fixture = TerminalFixture(
            scope = this,
            target = target,
            capture = TerminalCaptureSession(events),
            output = output,
            events = events,
            onTerminalCompleted = {
                completionCount += 1
                throw IllegalStateException("completion observer failed")
            },
        )

        assertTrue(fixture.manager.start(PttSource.Phone, "journal", InputMode.OnAPinch))
        runCurrent()
        events.clear()

        fixture.manager.release(PttSource.Phone)
        runCurrent()
        fixture.manager.cancelBySource(
            PttSource.Phone,
            PttAudioSessionManager.CancellationEligibility.PendingOrActive,
            "connection ended",
        )
        fixture.manager.release(PttSource.Phone)
        releaseGate.complete(Unit)
        advanceUntilIdle()

        assertEquals(
            listOf("capture-stop", "target-release", "route-release", "lease-release", "completion"),
            events,
        )
        assertEquals(1, target.releaseCount)
        assertEquals(0, target.cancelCount)
        assertEquals(1, output.releaseCount)
        assertEquals(1, target.leaseReleaseCount)
        assertEquals(1, completionCount)
        assertEquals(null, fixture.manager.activeSession)
        assertEquals(
            listOf("target release", "route release", "committed target lease release"),
            fixture.completions.single().failures.map { it.phase },
        )
    }

    @Test
    fun captureStopFailureStillNotifiesFailureAndRunsRemainingTerminalEffectsOnce() = runTest {
        val events = mutableListOf<String>()
        val target = TerminalTarget(events)
        val fixture = TerminalFixture(
            scope = this,
            target = target,
            capture = TerminalCaptureSession(events, stopFailure = IllegalStateException("capture stop failed")),
            output = TerminalOutput(events),
            events = events,
        )

        assertTrue(fixture.manager.start(PttSource.Phone, "journal", InputMode.OnAPinch))
        runCurrent()
        events.clear()
        fixture.manager.release(PttSource.Phone)
        advanceUntilIdle()

        assertEquals(
            listOf("capture-stop", "target-failed", "route-release", "lease-release", "completion"),
            events,
        )
        assertEquals(1, target.failureCount)
        assertEquals(1, target.leaseReleaseCount)
        assertEquals(1, fixture.output.releaseCount)
        assertEquals(1, fixture.completions.size)
        assertEquals(listOf("capture stop"), fixture.completions.single().failures.map { it.phase })
        assertEquals(null, fixture.manager.activeSession)
    }

    @Test
    fun playbackCompletionFailureDoesNotSkipRouteLeaseOrCompletion() = runTest {
        val events = mutableListOf<String>()
        val target = TerminalTarget(
            events = events,
            releasedResult = ChannelInputResult.Playback(RecordedPcm(shortArrayOf(9), 16_000)),
            playbackCompletionFailure = IllegalStateException("playback completion failed"),
        )
        val fixture = TerminalFixture(
            scope = this,
            target = target,
            capture = TerminalCaptureSession(events),
            output = TerminalOutput(events),
            events = events,
        )

        assertTrue(fixture.manager.start(PttSource.Phone, "journal", InputMode.OnAPinch))
        runCurrent()
        events.clear()
        fixture.manager.release(PttSource.Phone)
        advanceUntilIdle()

        assertEquals(
            listOf(
                "capture-stop",
                "target-release",
                "playback",
                "target-playback-complete",
                "route-release",
                "lease-release",
                "completion",
            ),
            events,
        )
        assertEquals(1, target.playbackCompletionCount)
        assertEquals(1, fixture.output.releaseCount)
        assertEquals(1, target.leaseReleaseCount)
        assertEquals(
            listOf("playback completion"),
            fixture.completions.single().failures.map { it.phase },
        )
    }

    @Test
    fun playbackOperationDeliversHostArtifactPcmOnceThenCompletesTarget() = runTest {
        val events = mutableListOf<String>()
        val expectedPcm = RecordedPcm(shortArrayOf(29, -31, 37), 16_000)
        val target = TerminalTarget(
            events = events,
            releasedResult = ChannelInputResult.PlaybackOperation(
                AudioOperationArtifact(expectedPcm, operationId = "deferred-playback", generation = RuntimeGeneration(0)),
            ),
        )
        val output = TerminalOutput(events)
        val fixture = TerminalFixture(
            scope = this,
            target = target,
            capture = TerminalCaptureSession(events),
            output = output,
            events = events,
        )

        assertTrue(fixture.manager.start(PttSource.Phone, "debug", InputMode.OnAPinch))
        runCurrent()
        events.clear()
        fixture.manager.release(PttSource.Phone)
        advanceUntilIdle()

        assertEquals(
            listOf(
                "capture-stop",
                "target-release",
                "playback",
                "target-playback-complete",
                "route-release",
                "lease-release",
                "completion",
            ),
            events,
        )
        assertEquals(1, output.played.size)
        assertEquals(expectedPcm.sampleRate, output.played.single().sampleRate)
        assertEquals(expectedPcm.samples.toList(), output.played.single().samples.toList())
        assertEquals(1, target.playbackCompletionCount)
        assertEquals(1, output.releaseCount)
        assertEquals(1, target.leaseReleaseCount)
        assertEquals(1, fixture.completions.size)
        assertTrue(fixture.completions.single().failures.isEmpty())
    }

    @Test
    fun maxDurationCompletionClaimsNormalReleaseWithoutPttRelease() = runTest {
        val events = mutableListOf<String>()
        val target = TerminalTarget(events)
        val capture = TerminalCaptureSession(events)
        val fixture = TerminalFixture(
            scope = this,
            target = target,
            capture = capture,
            output = TerminalOutput(events),
            events = events,
        )

        assertTrue(fixture.manager.start(PttSource.Phone, "journal", InputMode.OnAPinch))
        runCurrent()
        events.clear()

        capture.completion.complete(
            CaptureCompletion.MaxDuration(RecordedPcm(shortArrayOf(7, 8), 16_000)),
        )
        advanceUntilIdle()

        assertEquals(
            listOf("capture-stop", "target-release", "route-release", "lease-release", "completion"),
            events,
        )
        assertEquals(1, target.releaseCount)
        assertEquals(null, fixture.manager.activeSession)
        assertTrue(fixture.completions.single().failures.isEmpty())
    }

    @Test
    fun terminalCleanupSurvivesCallerScopeCancellation() = runTest {
        val events = mutableListOf<String>()
        val serviceJob = SupervisorJob()
        val releaseGate = CompletableDeferred<Unit>()
        val target = TerminalTarget(events, releaseGate = releaseGate)
        val fixture = TerminalFixture(
            scope = CoroutineScope(coroutineContext + serviceJob),
            target = target,
            capture = TerminalCaptureSession(events),
            output = TerminalOutput(events),
            events = events,
        )

        assertTrue(fixture.manager.start(PttSource.Phone, "journal", InputMode.OnAPinch))
        runCurrent()
        events.clear()
        fixture.manager.release(PttSource.Phone)
        runCurrent()
        serviceJob.cancel()
        releaseGate.complete(Unit)
        advanceUntilIdle()

        assertEquals(
            listOf("capture-stop", "target-release", "route-release", "lease-release", "completion"),
            events,
        )
        assertEquals(1, fixture.completions.size)
        assertEquals(null, fixture.manager.activeSession)
    }

    @Test
    fun slowInputReleaseReturnsItsEffectBeforeRouteLeaseAndCompletionCleanup() = runTest {
        val events = mutableListOf<String>()
        val target = object : ChannelInputTarget, CommittedTargetLeaseOwner {
            override val capturePolicy = CapturePolicy(60_000L)
            override suspend fun onInputStarted(session: ChannelAudioInputSession) = Unit

            override suspend fun onInputReleased(recording: RecordedPcm): ChannelInputResult {
                events += "target-release-started"
                delay(6_000)
                events += "target-effect-returned"
                return ChannelInputResult.None
            }

            override suspend fun onInputCancelled(reason: String) = Unit

            override suspend fun onInputFailed(reason: String) = Unit

            override suspend fun releaseCommittedTargetLease() {
                events += "lease-release"
            }
        }
        val fixture = TerminalFixture(
            scope = this,
            target = target,
            capture = TerminalCaptureSession(events),
            output = TerminalOutput(events),
            events = events,
            targetReleaseTimeoutMillis = 7_000,
        )

        assertTrue(fixture.manager.start(PttSource.Phone, "keyboard", InputMode.OnAPinch))
        runCurrent()
        events.clear()
        fixture.manager.release(PttSource.Phone)
        runCurrent()

        assertEquals(listOf("capture-stop", "target-release-started"), events)
        advanceTimeBy(5_001)
        runCurrent()
        assertEquals(listOf("capture-stop", "target-release-started"), events)
        assertEquals(0, fixture.output.releaseCount)
        assertTrue(fixture.completions.isEmpty())

        advanceTimeBy(999)
        advanceUntilIdle()
        assertEquals(
            listOf(
                "capture-stop",
                "target-release-started",
                "target-effect-returned",
                "route-release",
                "lease-release",
                "completion",
            ),
            events,
        )
        assertTrue(fixture.completions.single().failures.isEmpty())
        assertEquals(null, fixture.manager.activeSession)
    }

    @Test
    fun targetReleaseDeadlineTimesOutThenReleasesRouteLeaseAndPublishesOnce() = runTest {
        val events = mutableListOf<String>()
        val target = TerminalTarget(events, releaseGate = CompletableDeferred())
        val fixture = TerminalFixture(
            scope = this,
            target = target,
            capture = TerminalCaptureSession(events),
            output = TerminalOutput(events),
            events = events,
            targetReleaseTimeoutMillis = 7_000,
        )

        assertTrue(fixture.manager.start(PttSource.Phone, "journal", InputMode.OnAPinch))
        runCurrent()
        events.clear()
        fixture.manager.release(PttSource.Phone)
        runCurrent()
        advanceTimeBy(6_999)
        runCurrent()
        assertEquals(listOf("capture-stop", "target-release"), events)
        assertEquals(0, fixture.output.releaseCount)
        assertEquals(0, target.leaseReleaseCount)
        assertTrue(fixture.completions.isEmpty())
        advanceTimeBy(1)
        advanceUntilIdle()

        assertEquals(
            listOf("capture-stop", "target-release", "route-release", "lease-release", "completion"),
            events,
        )
        assertEquals(1, target.releaseCount)
        assertEquals(1, fixture.output.releaseCount)
        assertEquals(1, target.leaseReleaseCount)
        assertEquals(1, fixture.completions.size)
        assertEquals(
            listOf("target release"),
            fixture.completions.single().failures.map { it.phase },
        )
        assertEquals(null, fixture.manager.activeSession)
    }

    @Test
    fun shutdownAwaitsClaimedCleanupInsteadOfStartingAnotherTerminalSequence() = runTest {
        val events = mutableListOf<String>()
        val cancellationGate = CompletableDeferred<Unit>()
        val target = TerminalTarget(events)
        val fixture = TerminalFixture(
            scope = this,
            target = target,
            capture = TerminalCaptureSession(events),
            output = TerminalOutput(events),
            events = events,
            cancellationGate = cancellationGate,
        )

        assertTrue(fixture.manager.start(PttSource.Phone, "journal", InputMode.OnAPinch))
        runCurrent()
        events.clear()
        val shutdown = async { fixture.manager.shutdownAndAwait("service teardown") }
        runCurrent()
        assertFalse(shutdown.isCompleted)
        assertEquals(listOf("capture-cancel"), events)

        fixture.manager.cancelBySource(
            PttSource.Phone,
            PttAudioSessionManager.CancellationEligibility.PendingOrActive,
            "late callback",
        )
        fixture.manager.release(PttSource.Phone)
        cancellationGate.complete(Unit)
        advanceUntilIdle()

        assertTrue(shutdown.isCompleted)
        assertEquals(
            listOf("capture-cancel", "target-cancelled", "route-release", "lease-release", "completion"),
            events,
        )
        assertEquals(1, target.cancelCount)
        assertEquals(1, fixture.output.releaseCount)
        assertEquals(1, target.leaseReleaseCount)
        assertEquals(1, fixture.completions.size)
        assertEquals(null, fixture.manager.activeSession)
    }

    private data class TerminalCompletionObservation(
        val activeSession: PttAudioSessionManager.SessionSnapshot?,
        val routeReleaseCount: Int,
        val mode: InputMode,
    )

    private class Fixture(
        scope: kotlinx.coroutines.test.TestScope,
        pcm: ShortArray = shortArrayOf(1, 2, 3),
    ) {
        val timeline = mutableListOf<String>()
        val terminalCompletionObservations = mutableListOf<TerminalCompletionObservation>()
        val router = RecordingRouter(scope, timeline)
        private val captureService = CaptureServiceFakes.newService(scope)
        private val routes = InputMode.entries.associateWith { mode ->
            val output = RecordingOutput(timeline, endpointFor(mode))
            TestRoute(
                sco = RecordingScoRoute(endpointFor(mode)),
                output = output,
                source = ReadyAfterBeepCaptureSource(
                    pcm = pcm,
                    output = output,
                    sourceId = if (mode == InputMode.OnAPinch) CaptureSourceId.Mic else CaptureSourceId.VoiceCommunication,
                ),
            )
        }
        lateinit var manager: PttAudioSessionManager
            private set

        init {
            manager = PttAudioSessionManager(
                scope = scope,
                captureService = captureService,
                channelRouter = router,
                resolvePttAudioRoute = { mode -> route(mode).resolved },
                onTerminalCompleted = { completion ->
                    terminalCompletionObservations += TerminalCompletionObservation(
                        activeSession = manager.activeSession,
                        mode = completion.mode,
                        routeReleaseCount = route(completion.mode).output.releaseRouteCount,
                    )
                },
            )
        }

        fun route(mode: InputMode): TestRoute = routes.getValue(mode)
    }

    private class RecordingRouter(
        private val scope: kotlinx.coroutines.test.TestScope,
        private val timeline: MutableList<String>,
    ) : ChannelRouter {
        val events = mutableListOf<String>()
        val startedSampleRates = mutableListOf<Int>()
        val recordings = mutableListOf<RecordedPcm>()
        val liveFrames = mutableListOf<ShortArray>()
        private val liveJobs = mutableListOf<Job>()
        var acceptance: ChannelInputAcceptance? = null
        var includeChannelIds: Boolean = false
        var preparationGate: CompletableDeferred<Unit>? = null
        var preparationStarted: CompletableDeferred<Unit>? = null
        var prepareCallCount: Int = 0

        override suspend fun prepareInput(channelId: String): ChannelInputAcceptance {
            prepareCallCount += 1
            timeline += "prepare:$channelId"
            preparationStarted?.complete(Unit)
            preparationGate?.await()
            return acceptance ?: ChannelInputAcceptance.Accepted(RecordingTarget(channelId))
        }

        private inner class RecordingTarget(
            private val channelId: String,
        ) : ChannelInputTarget {
            override val capturePolicy = CapturePolicy(60_000L)
            override suspend fun onInputStarted(session: ChannelAudioInputSession) {
                events += event("started", channelId)
                timeline += "started:$channelId"
                startedSampleRates += session.sampleRate
                liveJobs += scope.launch {
                    session.frames.collect { chunk -> liveFrames += chunk }
                }
            }

            override suspend fun onInputReleased(recording: RecordedPcm): ChannelInputResult {
                events += event("released", channelId)
                timeline += "released:$channelId"
                recordings += recording
                liveJobs.forEach { it.cancel() }
                liveJobs.clear()
                return ChannelInputResult.None
            }

            override suspend fun onInputCancelled(reason: String) {
                events += event("cancelled:$reason", channelId)
                liveJobs.forEach { it.cancel() }
                liveJobs.clear()
            }

            override suspend fun onInputFailed(reason: String) {
                events += event("failed:$reason", channelId)
                liveJobs.forEach { it.cancel() }
                liveJobs.clear()
            }
        }

        private fun event(name: String, channelId: String): String =
            if (includeChannelIds) "$name:$channelId" else name
    }

    private data class TestRoute(
        val sco: RecordingScoRoute,
        val output: RecordingOutput,
        var source: CaptureSource,
        var routeGate: RouteGate = RouteGate("test-open-route") {
            RouteGateResult.Success("test route open")
        },
    ) {
        val resolved: ResolvedAudioRoute
            get() = ResolvedAudioRoute(sco, output, source, sco.endpoint, routeGate)
    }

    private class RecordingScoRoute(
        override val endpoint: AudioRouteEndpoint,
    ) : ScoRoute {
        override val state: StateFlow<ScoState> = MutableStateFlow(ScoState.Inactive)
        override val coldStart: Boolean = false
        var acquireCount = 0
            private set
        var releaseCount = 0
            private set
        var acquireGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null
        var acquireResult: Boolean = true

        override fun hasAvailableScoDevice(): Boolean = true
        override suspend fun acquire(): Boolean {
            acquireCount += 1
            acquireGate?.await()
            return acquireResult
        }
        override fun isActive(): Boolean = true
        override fun release() {
            releaseCount += 1
        }
    }

    private class RecordingOutput(
        private val timeline: MutableList<String>,
        private val endpoint: AudioRouteEndpoint,
    ) : PcmOutput {
        var releaseRouteCount = 0
            private set
        var readyBeepCount = 0
            private set
        var errorBeepCount = 0
            private set
        var readyBeepStarted: CompletableDeferred<Unit>? = null
        var readyBeepGate: CompletableDeferred<Unit>? = null
        var readyBeepCompleted: Boolean = false
            private set
        override suspend fun playReadyBeep(coldStart: Boolean) {
            readyBeepCount += 1
            readyBeepCompleted = false
            timeline += "ready:$endpoint"
            readyBeepStarted?.complete(Unit)
            readyBeepGate?.await()
            readyBeepCompleted = true
        }
        override suspend fun playErrorBeep(coldStart: Boolean) {
            errorBeepCount += 1
            timeline += "problem:$endpoint"
        }
        override suspend fun play(recording: RecordedPcm) = Unit
        override suspend fun playCaptureFeedback(tone: CaptureFeedbackTone) {
            timeline += "feedback:$tone:$endpoint"
        }
        override suspend fun releaseRoute() {
            releaseRouteCount += 1
        }
    }

    private class TerminalFixture(
        scope: CoroutineScope,
        target: ChannelInputTarget,
        capture: TerminalCaptureSession,
        val output: TerminalOutput,
        events: MutableList<String>,
        onTerminalCompleted: (PttAudioSessionManager.TerminalCompletion) -> Unit = {},
        cancellationGate: CompletableDeferred<Unit>? = null,
        targetReleaseTimeoutMillis: Long = 20_000,
    ) {
        val completions = mutableListOf<PttAudioSessionManager.TerminalCompletion>()
        private val captureService = mockk<CaptureService>()
        val manager: PttAudioSessionManager

        init {
            coEvery {
                captureService.startSession(
                    source = any(),
                    sco = any(),
                    output = any(),
                    maxDurationMs = any(),
                    shouldProceed = any(),
                )
            } returns CaptureStartResult.Started(capture, CaptureStartupEvidence())
            coEvery { captureService.cancelSession(capture) } coAnswers {
                events += "capture-cancel"
                cancellationGate?.await()
                RecordedPcm(shortArrayOf(), 16_000)
            }
            manager = PttAudioSessionManager(
                scope = scope,
                captureService = captureService,
                channelRouter = object : ChannelRouter {
                    override suspend fun prepareInput(channelId: String): ChannelInputAcceptance =
                        ChannelInputAcceptance.Accepted(target)
                },
                resolvePttAudioRoute = {
                    ResolvedAudioRoute(
                        sco = RecordingScoRoute(AudioRouteEndpoint.Local),
                        output = output,
                        source = CaptureServiceFakes.singleShotSource(
                            shortArrayOf(1),
                            sourceId = CaptureSourceId.Mic,
                        ),
                        endpoint = AudioRouteEndpoint.Local,
                    )
                },
                onTerminalCompleted = { completion ->
                    completions += completion
                    events += "completion"
                    onTerminalCompleted(completion)
                },
                targetReleaseTimeoutMillis = targetReleaseTimeoutMillis,
            )
        }
    }

    private class TerminalCaptureSession(
        private val events: MutableList<String>,
        private val stopFailure: Throwable? = null,
    ) : CaptureSession {
        override val frames = MutableSharedFlow<ShortArray>()
        override val completion = CompletableDeferred<CaptureCompletion>()
        override val sampleRate: Int = 16_000
        override val maxDurationMs: Long = 60_000L
        override val remainingDurationMs: Long = 60_000L
        override val semanticFeedbackEmitter: io.talkcan.audio.SemanticFeedbackEmitter = object : io.talkcan.audio.SemanticFeedbackEmitter {
            override suspend fun emit(tone: io.talkcan.service.CaptureFeedbackTone) {}
        }
        var stopCount = 0
            private set
        override suspend fun stop(): RecordedPcm {
            stopCount += 1
            events += "capture-stop"
            stopFailure?.let { throw it }
            val recording = RecordedPcm(shortArrayOf(1, 2, 3), sampleRate)
            completion.complete(CaptureCompletion.Stopped(recording))
            return recording
        }
    }

    private class TerminalTarget(
        private val events: MutableList<String>,
        private val releaseGate: CompletableDeferred<Unit>? = null,
        private val releasedResult: ChannelInputResult = ChannelInputResult.None,
        private val releaseFailure: Throwable? = null,
        private val playbackCompletionFailure: Throwable? = null,
        private val leaseFailure: Throwable? = null,
    ) : ChannelInputTarget, CommittedTargetLeaseOwner {
        override val capturePolicy = CapturePolicy(60_000L)
        var releaseCount = 0
            private set
        var cancelCount = 0
            private set
        var failureCount = 0
            private set
        var playbackCompletionCount = 0
            private set
        var leaseReleaseCount = 0
            private set

        override suspend fun onInputStarted(session: ChannelAudioInputSession) = Unit

        override suspend fun onInputReleased(recording: RecordedPcm): ChannelInputResult {
            releaseCount += 1
            events += "target-release"
            releaseGate?.await()
            releaseFailure?.let { throw it }
            return releasedResult
        }

        override fun onInputPlaybackCompleted() {
            playbackCompletionCount += 1
            events += "target-playback-complete"
            playbackCompletionFailure?.let { throw it }
        }

        override suspend fun onInputCancelled(reason: String) {
            cancelCount += 1
            events += "target-cancelled"
        }

        override suspend fun onInputFailed(reason: String) {
            failureCount += 1
            events += "target-failed"
        }

        override suspend fun releaseCommittedTargetLease() {
            leaseReleaseCount += 1
            events += "lease-release"
            leaseFailure?.let { throw it }
        }
    }

    private class TerminalOutput(
        private val events: MutableList<String>,
        private val releaseFailure: Throwable? = null,
    ) : PcmOutput {
        var releaseCount = 0
            private set
        val played = mutableListOf<RecordedPcm>()

        override suspend fun playReadyBeep(coldStart: Boolean) = Unit
        override suspend fun playErrorBeep(coldStart: Boolean) = Unit
        override suspend fun play(recording: RecordedPcm) {
            events += "playback"
            played += recording
        }
        override suspend fun playCaptureFeedback(tone: CaptureFeedbackTone) {
            events += "feedback:$tone"
        }
        override suspend fun releaseRoute() {
            releaseCount += 1
            events += "route-release"
            releaseFailure?.let { throw it }
        }
    }
    private class ReadyAfterBeepCaptureSource(
        private val pcm: ShortArray,
        private val output: RecordingOutput,
        override val sourceId: CaptureSourceId,
    ) : CaptureSource {
        var openCount: Int = 0
            private set

        override suspend fun open(): OpenedCaptureSource? {
            openCount += 1
            val delegate = CaptureServiceFakes.singleShotSource(
                pcm,
                sourceId = sourceId,
            ).open() ?: return null
            return object : OpenedCaptureSource {
                override val sampleRate: Int = delegate.sampleRate
                override val bufferSizeShorts: Int = delegate.bufferSizeShorts

                override fun read(buffer: ShortArray): Int =
                    if (output.readyBeepCompleted) delegate.read(buffer) else 0

                override fun close() = delegate.close()
            }
        }
    }


    private class DeferredOpenCaptureSource(
        private val pcm: ShortArray,
    ) : CaptureSource {
        override val sourceId: CaptureSourceId = CaptureSourceId.VoiceCommunication
        val openStarted = CompletableDeferred<Unit>()
        val allowOpen = CompletableDeferred<Unit>()
        var openCount: Int = 0
            private set

        override suspend fun open(): OpenedCaptureSource? {
            openStarted.complete(Unit)
            allowOpen.await()
            openCount += 1
            return CaptureServiceFakes.singleShotSource(pcm).open()
        }
    }

    private class GatedFrameCaptureSource(
        private val pcm: ShortArray,
    ) : CaptureSource {
        override val sourceId: CaptureSourceId = CaptureSourceId.Mic
        var allowFrames: Boolean = false
        var openCount: Int = 0
            private set

        override suspend fun open(): OpenedCaptureSource? {
            openCount += 1
            return object : OpenedCaptureSource {
                private var delivered = false
                override val sampleRate: Int = 16_000
                override val bufferSizeShorts: Int = pcm.size.coerceAtLeast(1)

                override fun read(buffer: ShortArray): Int {
                    if (!allowFrames || delivered) return 0
                    delivered = true
                    pcm.copyInto(buffer)
                    return pcm.size
                }

                override fun close() = Unit
            }
        }
    }

    private companion object {
        fun endpointFor(mode: InputMode): AudioRouteEndpoint = when (mode) {
            InputMode.Work -> AudioRouteEndpoint.Rsm
            InputMode.OnAPinch -> AudioRouteEndpoint.Local
            InputMode.OnTheRoad -> AudioRouteEndpoint.Car
        }
    }
}
