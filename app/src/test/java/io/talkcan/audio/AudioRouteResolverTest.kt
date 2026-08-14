package io.talkcan.audio

import io.talkcan.model.ScoState
import io.talkcan.service.CaptureFeedbackTone
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioRouteResolverTest {

    @Test
    fun rsmRouteUsesEndpointBoundScoBranch() = runTest {
        val scoRoute = RecordingFakeScoRoute(endpoint = AudioRouteEndpoint.Rsm, hasDevice = true)
        val scoOutput = RecordingOutput(endpoint = AudioRouteEndpoint.Rsm)
        val scoSource = CaptureServiceFakes.singleShotSource(shortArrayOf(1), sourceId = CaptureSourceId.VoiceCommunication)

        val resolved = resolveScoAudioRoute(
            scoRoute = scoRoute,
            scoOutput = scoOutput,
            scoSource = scoSource,
            endpoint = AudioRouteEndpoint.Rsm,
        )

        assertEquals(AudioRouteEndpoint.Rsm, resolved.endpoint)
        assertEquals(scoRoute, resolved.sco)
        assertEquals(scoSource, resolved.source)
        resolved.output.playReadyBeep()
        resolved.output.playErrorBeep()
        resolved.output.play(RecordedPcm(shortArrayOf(1), 16_000))
        assertEquals(listOf(AudioRouteEndpoint.Rsm, AudioRouteEndpoint.Rsm, AudioRouteEndpoint.Rsm), scoOutput.playEndpoints)
    }

    @Test
    fun rsmRouteRejectsCarScoRoute() {
        val carRoute = RecordingFakeScoRoute(endpoint = AudioRouteEndpoint.Car, hasDevice = true)

        val error = runCatching {
            resolveScoAudioRoute(
                scoRoute = carRoute,
                scoOutput = RecordingOutput(AudioRouteEndpoint.Car),
                scoSource = CaptureServiceFakes.singleShotSource(shortArrayOf(1)),
                endpoint = AudioRouteEndpoint.Rsm,
            )
        }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
    }

    @Test
    fun rsmRouteDoesNotFallbackToLocalWhenRsmUnavailable() = runTest {
        val rsmRoute = RecordingFakeScoRoute(endpoint = AudioRouteEndpoint.Rsm, hasDevice = false)
        val scoOutput = RecordingOutput(AudioRouteEndpoint.Rsm)
        val localOutput = RecordingOutput(AudioRouteEndpoint.Local)

        val resolved = resolveScoAudioRoute(
            scoRoute = rsmRoute,
            scoOutput = scoOutput,
            scoSource = CaptureServiceFakes.singleShotSource(shortArrayOf(1)),
            endpoint = AudioRouteEndpoint.Rsm,
        )

        assertEquals(AudioRouteEndpoint.Rsm, resolved.endpoint)
        assertFalse(resolved.sco.acquire())
        resolved.output.releaseRoute()
        assertEquals(1, rsmRoute.releaseCount)
        assertEquals(emptyList<AudioRouteEndpoint>(), localOutput.playEndpoints)
    }

    @Test
    fun localRouteUsesNoopScoAndLocalEndpoint() = runTest {
        val localOutput = RecordingOutput(AudioRouteEndpoint.Local)
        val localSource = CaptureServiceFakes.singleShotSource(shortArrayOf(2), sourceId = CaptureSourceId.Mic)

        val resolved = resolveLocalAudioRoute(localOutput, localSource)

        assertEquals(AudioRouteEndpoint.Local, resolved.endpoint)
        assertTrue(resolved.sco is NoopScoRoute)
        assertEquals(localSource, resolved.source)
        assertTrue(resolved.sco.acquire())
        assertTrue(resolved.sco.isActive())
        assertFalse(resolved.sco.hasAvailableScoDevice())
        resolved.output.play(RecordedPcm(shortArrayOf(1), 16_000))
        assertEquals(listOf(AudioRouteEndpoint.Local), localOutput.playEndpoints)
    }

    @Test
    fun scoBranchReleaseRouteReleasesTheScoRoute() = runTest {
        val scoRoute = RecordingFakeScoRoute(endpoint = AudioRouteEndpoint.Rsm)
        val resolved = resolveScoAudioRoute(
            scoRoute = scoRoute,
            scoOutput = RecordingOutput(AudioRouteEndpoint.Rsm),
            scoSource = CaptureServiceFakes.singleShotSource(shortArrayOf(1)),
            endpoint = AudioRouteEndpoint.Rsm,
        )

        resolved.output.releaseRoute()

        assertEquals(1, scoRoute.releaseCount)
    }

    @Test
    fun localBranchReleaseRouteIsNoOp() = runTest {
        val resolved = resolveLocalAudioRoute(
            localOutput = RecordingOutput(AudioRouteEndpoint.Local),
            localSource = CaptureServiceFakes.singleShotSource(shortArrayOf(2), sourceId = CaptureSourceId.Mic),
        )

        resolved.output.releaseRoute()

        assertTrue(resolved.sco is NoopScoRoute)
    }

    @Test
    fun telecomRouteReadinessComesFromTelecomLifecycle() = runTest {
        var routeReady = false
        val carRoute = TelecomCallScoRoute { routeReady }

        assertEquals(AudioRouteEndpoint.Car, carRoute.endpoint)
        assertFalse(carRoute.hasAvailableScoDevice())
        assertFalse(carRoute.acquire())

        routeReady = true

        assertTrue(carRoute.hasAvailableScoDevice())
        assertTrue(carRoute.acquire())
        assertTrue(carRoute.isActive())
    }

    @Test
    fun telecomOutputPlayDelegatesOnlyToMediaResponsePlayer() = runTest {
        val events = mutableListOf<String>()
        val output = TelecomCapturePcmOutput(
            captureOutput = RecordingOutput(AudioRouteEndpoint.Car),
            mediaResponsePlayer = RecordingResponsePlayer(events),
            releaseCaptureRoute = { events += "release" },
            awaitTelecomDisconnected = { events += "disconnected" },
        )

        output.play(RecordedPcm(shortArrayOf(1), 16_000))

        assertEquals(listOf("media"), events)
    }

    @Test
    fun telecomOutputReleaseRouteDoesNotPlayMediaResponse() = runTest {
        val events = mutableListOf<String>()
        val output = TelecomCapturePcmOutput(
            captureOutput = RecordingOutput(AudioRouteEndpoint.Car),
            mediaResponsePlayer = RecordingResponsePlayer(events),
            releaseCaptureRoute = { events += "release" },
            awaitTelecomDisconnected = { events += "disconnected" },
        )

        output.releaseRoute()

        assertEquals(listOf("release", "disconnected"), events)
    }

    @Test
    fun workScoRouteUsesOpenRouteGateWithoutReleasingWarmWorkRoute() = runTest {
        val scoRoute = RecordingFakeScoRoute(endpoint = AudioRouteEndpoint.Rsm, hasDevice = true)
        val resolved = resolveScoAudioRoute(
            scoRoute = scoRoute,
            scoOutput = RecordingOutput(AudioRouteEndpoint.Rsm),
            scoSource = CaptureServiceFakes.singleShotSource(shortArrayOf(1)),
            endpoint = AudioRouteEndpoint.Rsm,
        )

        val result = resolved.routeGate.await()

        assertTrue(result is RouteGateResult.Success)
        assertEquals("open-route", resolved.routeGate.name)
        assertEquals(0, scoRoute.releaseCount)
    }

    @Test
    fun workReleaseGatePropagatesTimeoutInsteadOfTreatingElapsedTimeAsSuccess() = runTest {
        val gate = releaseWorkRouteGate("release-work-before-car") { name ->
            RouteGateResult.Timeout(
                reason = "Timed out waiting for target RSM route release",
                facts = listOf("gate=$name", "rsmHfpAudioConnected=true"),
            )
        }

        val result = gate.await()

        assertTrue(result is RouteGateResult.Timeout)
        assertFalse(result.isSuccess)
        assertEquals("Timed out waiting for target RSM route release", result.reason)
    }

    @Test
    fun localRouteCarriesWorkReleaseGateWithoutOwningScoRouteObjects() = runTest {
        val releaseReasons = mutableListOf<String>()
        val localSource = CaptureServiceFakes.singleShotSource(
            shortArrayOf(2),
            sourceId = CaptureSourceId.Mic,
        )
        val resolved = resolveLocalAudioRoute(
            localOutput = RecordingOutput(AudioRouteEndpoint.Local),
            localSource = localSource,
            routeGate = releaseWorkRouteGate("release-work-before-local") { reason ->
                releaseReasons += reason
                RouteGateResult.Success(reason, listOf("rsm released"))
            },
        )

        val result = resolved.routeGate.await()

        assertTrue(result is RouteGateResult.Success)
        assertEquals(AudioRouteEndpoint.Local, resolved.endpoint)
        assertTrue(resolved.sco is NoopScoRoute)
        assertEquals(localSource, resolved.source)
        assertEquals(listOf("release-work-before-local"), releaseReasons)
    }

    private class RecordingFakeScoRoute(
        override val endpoint: AudioRouteEndpoint,
        private val hasDevice: Boolean = true,
        private val active: Boolean = false,
    ) : ScoRoute {
        private val _state = MutableStateFlow<ScoState>(
            if (active) ScoState.Active else ScoState.Inactive,
        )
        override val state: StateFlow<ScoState> = _state
        override val coldStart: Boolean = false
        var releaseCount: Int = 0; private set

        override fun hasAvailableScoDevice(): Boolean = hasDevice
        override suspend fun acquire(): Boolean {
            if (!hasDevice) return false
            _state.value = ScoState.Active
            return true
        }
        override fun isActive(): Boolean = active
        override fun release() { releaseCount += 1 }
    }

    private class RecordingOutput(
        private val endpoint: AudioRouteEndpoint,
    ) : PcmOutput {
        val playEndpoints = mutableListOf<AudioRouteEndpoint>()

        override suspend fun playReadyBeep(coldStart: Boolean) { playEndpoints += endpoint }
        override suspend fun playErrorBeep(coldStart: Boolean) { playEndpoints += endpoint }
        override suspend fun play(recording: RecordedPcm) { playEndpoints += endpoint }
        override suspend fun playCaptureFeedback(tone: CaptureFeedbackTone) { playEndpoints += endpoint }
    }

    private class RecordingResponsePlayer(
        private val events: MutableList<String>,
    ) : ResponsePlayer {
        override suspend fun play(recording: RecordedPcm) {
            events += "media"
        }
    }
}
