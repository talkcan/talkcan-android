package io.talkcan.audio

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaResponsePcmOutputTest {

    @Test
    fun readyBeepUsesRawLocalOutputWithoutStartingMediaResponsePlayback() = runTest {
        val localOutput = RecordingLocalOutput()
        val responsePlayer = RecordingResponsePlayer()
        val output = MediaResponsePcmOutput(localOutput, responsePlayer)

        output.playReadyBeep(coldStart = true)

        assertEquals(listOf(LocalEvent.ReadyBeep(coldStart = true)), localOutput.events)
        assertEquals(emptyList<RecordedPcm>(), responsePlayer.recordings)
    }

    @Test
    fun errorBeepUsesRawLocalOutputWithoutStartingMediaResponsePlayback() = runTest {
        val localOutput = RecordingLocalOutput()
        val responsePlayer = RecordingResponsePlayer()
        val output = MediaResponsePcmOutput(localOutput, responsePlayer)

        output.playErrorBeep(coldStart = false)

        assertEquals(listOf(LocalEvent.ErrorBeep(coldStart = false)), localOutput.events)
        assertEquals(emptyList<RecordedPcm>(), responsePlayer.recordings)
    }

    @Test
    fun recordedResponseUsesMediaResponsePlayerWithoutWritingToRawLocalOutput() = runTest {
        val localOutput = RecordingLocalOutput()
        val responsePlayer = RecordingResponsePlayer()
        val output = MediaResponsePcmOutput(localOutput, responsePlayer)
        val recording = RecordedPcm(shortArrayOf(3, -2, 1), sampleRate = 24_000)

        output.play(recording)

        assertEquals(emptyList<LocalEvent>(), localOutput.events)
        assertEquals(1, responsePlayer.recordings.size)
        assertSame(recording, responsePlayer.recordings.single())
    }

    @Test
    fun releaseRouteDoesNotTearDownRawLocalOutput() = runTest {
        val localOutput = RecordingLocalOutput()
        val output = MediaResponsePcmOutput(localOutput, RecordingResponsePlayer())

        output.releaseRoute()

        assertEquals(emptyList<LocalEvent>(), localOutput.events)
    }

    private sealed interface LocalEvent {
        data class ReadyBeep(val coldStart: Boolean) : LocalEvent
        data class ErrorBeep(val coldStart: Boolean) : LocalEvent
        data class Recording(val recording: RecordedPcm) : LocalEvent
        data object ReleaseRoute : LocalEvent
    }

    private class RecordingLocalOutput : PcmOutput {
        val events = mutableListOf<LocalEvent>()

        override suspend fun playReadyBeep(coldStart: Boolean) {
            events += LocalEvent.ReadyBeep(coldStart)
        }

        override suspend fun playErrorBeep(coldStart: Boolean) {
            events += LocalEvent.ErrorBeep(coldStart)
        }

        override suspend fun play(recording: RecordedPcm) {
            events += LocalEvent.Recording(recording)
        }

        override suspend fun releaseRoute() {
            events += LocalEvent.ReleaseRoute
        }
    }

    private class RecordingResponsePlayer : ResponsePlayer {
        val recordings = mutableListOf<RecordedPcm>()

        override suspend fun play(recording: RecordedPcm) {
            recordings += recording
        }
    }
}

class MediaRoutePlaybackGateTest {

    @Test
    fun timedOutRouteDoesNotRequestFocusOrProceedToOutput() = runTest {
        val events = mutableListOf<String>()
        val gate = playbackGate(
            events = events,
            routeReady = false,
            focusGranted = true,
        )

        val played = gate.play(RECORDING, RecordingOutput(events))

        assertFalse(played)
        assertEquals(listOf("route"), events)
    }

    @Test
    fun deniedMediaFocusDoesNotProceedToOutput() = runTest {
        val events = mutableListOf<String>()
        val gate = playbackGate(
            events = events,
            routeReady = true,
            focusGranted = false,
        )

        val played = gate.play(RECORDING, RecordingOutput(events))

        assertFalse(played)
        assertEquals(listOf("route", "focus"), events)
    }

    @Test
    fun stableRouteWithMediaFocusPlaysBeforeAbandoningFocus() = runTest {
        val events = mutableListOf<String>()
        val output = RecordingOutput(events)
        val gate = playbackGate(
            events = events,
            routeReady = true,
            focusGranted = true,
        )

        val played = gate.play(RECORDING, output)

        assertTrue(played)
        assertEquals(listOf("route", "focus", "output", "abandon"), events)
        assertSame(RECORDING, output.recording)
    }

    @Test
    fun outputFailureStillAbandonsMediaFocusAndPropagatesFailure() = runTest {
        val events = mutableListOf<String>()
        val failure = IllegalStateException("local output failed")
        val gate = playbackGate(
            events = events,
            routeReady = true,
            focusGranted = true,
        )

        val thrown = runCatching {
            gate.play(RECORDING, RecordingOutput(events, failure))
        }.exceptionOrNull()

        assertSame(failure, thrown)
        assertEquals(listOf("route", "focus", "output", "abandon"), events)
    }

    private fun playbackGate(
        events: MutableList<String>,
        routeReady: Boolean,
        focusGranted: Boolean,
    ) = MediaRoutePlaybackGate(
        awaitRouteReady = {
            events += "route"
            routeReady
        },
        requestFocus = {
            events += "focus"
            focusGranted
        },
        abandonFocus = { events += "abandon" },
    )

    private class RecordingOutput(
        private val events: MutableList<String>,
        private val failure: Throwable? = null,
    ) : PcmOutput {
        var recording: RecordedPcm? = null
            private set

        override suspend fun playReadyBeep(coldStart: Boolean) = error("Unexpected ready beep")

        override suspend fun playErrorBeep(coldStart: Boolean) = error("Unexpected error beep")

        override suspend fun play(recording: RecordedPcm) {
            events += "output"
            failure?.let { throw it }
            this.recording = recording
        }
    }

    private companion object {
        val RECORDING = RecordedPcm(shortArrayOf(9, -4), sampleRate = 16_000)
    }
}
