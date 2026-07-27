package io.talkcan.service

import io.talkcan.audio.AudioRouteEndpoint
import io.talkcan.audio.NoopCaptureSource
import io.talkcan.audio.PcmOutput
import io.talkcan.audio.RecordedPcm
import io.talkcan.audio.ResolvedAudioRoute
import io.talkcan.audio.RouteGate
import io.talkcan.audio.RouteGateResult
import io.talkcan.audio.ScoRoute
import io.talkcan.model.ScoState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PttRouteErrorFeedbackTest {
    @Test
    fun warmWorkRouteIsReleasedBeforeUnavailablePhoneFeedback() = runTest {
        val events = mutableListOf<String>()
        val sco = RecordingScoRoute(
            acquireResult = true,
            endpoint = AudioRouteEndpoint.Local,
            events = events,
        )
        val output = RecordingOutput(endpoint = AudioRouteEndpoint.Local, events = events)
        val route = ResolvedAudioRoute(
            sco = sco,
            output = output,
            source = NoopCaptureSource,
            endpoint = AudioRouteEndpoint.Local,
            routeGate = RouteGate("release-work-before-local") {
                events += "gate"
                RouteGateResult.Success("work-route-released")
            },
        )

        val played = playRouteErrorBeepIfAcquired(route)

        assertTrue(played)
        assertEquals(listOf("gate", "acquire", "beep", "release"), events)
        assertEquals(listOf(AudioRouteEndpoint.Local), output.errorBeepEndpoints)
        assertEquals(1, output.releaseRouteCount)
    }

    @Test
    fun routeGateTimeoutSuppressesFeedbackAndCleansResolvedRoute() = runTest {
        val events = mutableListOf<String>()
        val sco = RecordingScoRoute(
            acquireResult = true,
            endpoint = AudioRouteEndpoint.Local,
            events = events,
        )
        val output = RecordingOutput(endpoint = AudioRouteEndpoint.Local, events = events)
        val route = ResolvedAudioRoute(
            sco = sco,
            output = output,
            source = NoopCaptureSource,
            endpoint = AudioRouteEndpoint.Local,
            routeGate = RouteGate("release-work-before-local") {
                events += "gate"
                RouteGateResult.Timeout("work-route-release-timeout")
            },
        )

        val played = playRouteErrorBeepIfAcquired(route)

        assertFalse(played)
        assertEquals(listOf("gate", "release"), events)
        assertEquals(0, sco.acquireCount)
        assertEquals(emptyList<AudioRouteEndpoint>(), output.errorBeepEndpoints)
        assertEquals(1, output.releaseRouteCount)
    }

    @Test
    fun routeAcquisitionFailureSkipsErrorBeepAndRouteRelease() = runTest {
        val sco = RecordingScoRoute(acquireResult = false, endpoint = AudioRouteEndpoint.Rsm)
        val output = RecordingOutput(endpoint = AudioRouteEndpoint.Rsm)
        val route = ResolvedAudioRoute(
            sco = sco,
            output = output,
            source = NoopCaptureSource,
            endpoint = AudioRouteEndpoint.Rsm,
        )

        val played = playRouteErrorBeepIfAcquired(route)

        assertFalse(played)
        assertEquals(1, sco.acquireCount)
        assertEquals(emptyList<AudioRouteEndpoint>(), output.errorBeepEndpoints)
        assertEquals(0, output.releaseRouteCount)
    }

    @Test
    fun acquiredRoutePlaysErrorBeepOnThatRouteAndReleasesIt() = runTest {
        val sco = RecordingScoRoute(
            acquireResult = true,
            endpoint = AudioRouteEndpoint.Rsm,
            coldStart = true,
        )
        val output = RecordingOutput(endpoint = AudioRouteEndpoint.Rsm)
        val route = ResolvedAudioRoute(
            sco = sco,
            output = output,
            source = NoopCaptureSource,
            endpoint = AudioRouteEndpoint.Rsm,
        )

        val played = playRouteErrorBeepIfAcquired(route)

        assertTrue(played)
        assertEquals(listOf(AudioRouteEndpoint.Rsm), output.errorBeepEndpoints)
        assertEquals(listOf(true), output.errorBeepColdStarts)
        assertEquals(1, output.releaseRouteCount)
    }

    private class RecordingScoRoute(
        private val acquireResult: Boolean,
        override val endpoint: AudioRouteEndpoint,
        override val coldStart: Boolean = false,
        private val events: MutableList<String>? = null,
    ) : ScoRoute {
        private val _state = MutableStateFlow<ScoState>(ScoState.Inactive)
        override val state: StateFlow<ScoState> = _state
        var acquireCount = 0
            private set

        override fun hasAvailableScoDevice(): Boolean = acquireResult

        override suspend fun acquire(): Boolean {
            events?.add("acquire")
            acquireCount += 1
            if (acquireResult) _state.value = ScoState.Active
            return acquireResult
        }

        override fun isActive(): Boolean = state.value == ScoState.Active
        override fun release() = Unit
    }

    private class RecordingOutput(
        private val endpoint: AudioRouteEndpoint,
        private val events: MutableList<String>? = null,
    ) : PcmOutput {
        val errorBeepEndpoints = mutableListOf<AudioRouteEndpoint>()
        val errorBeepColdStarts = mutableListOf<Boolean>()
        var releaseRouteCount = 0
            private set

        override suspend fun playReadyBeep(coldStart: Boolean) = Unit

        override suspend fun playErrorBeep(coldStart: Boolean) {
            events?.add("beep")
            errorBeepEndpoints += endpoint
            errorBeepColdStarts += coldStart
        }

        override suspend fun play(recording: RecordedPcm) = Unit

        override suspend fun releaseRoute() {
            events?.add("release")
            releaseRouteCount += 1
        }
    }
}
