package io.talkcan.audio

import android.media.AudioTrack
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Terminal-completion contract tests for [ActiveStreamPcmPlayback]. The mocked track is the
 * Android framework boundary; observable events prove a waiter cannot resume before the track
 * has been physically stopped and released.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StreamPlaybackRouteTest {
    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun completedPlaybackPublishesCompletionOnlyAfterStoppingAndReleasingTrack() = runTest {
        val events = mutableListOf<String>()
        val track = streamingTrack(events) {
            events += "write"
            320
        }
        var headReads = 0
        every { track.playbackHeadPosition } answers { if (headReads++ == 0) 0 else 1 }
        val playback = ActiveStreamPcmPlayback(
            track = track,
            recording = recording(),
            sampleRate = 16_000,
            dispatcher = StandardTestDispatcher(testScheduler),
        )
        val terminal = async { playback.awaitCompletion().also { events += "await" } }

        runCurrent()

        assertEquals(PlaybackCompletion.Completed, terminal.await())
        assertEquals(listOf("play", "write", "stop", "release", "await"), events)
    }

    @Test
    fun explicitlySkippedPlaybackPublishesCompletionOnlyAfterStoppingAndReleasingTrack() = runTest {
        val events = mutableListOf<String>()
        val track = streamingTrack(events) { 320 }
        val playback = ActiveStreamPcmPlayback(
            track = track,
            recording = recording(),
            sampleRate = 16_000,
            dispatcher = StandardTestDispatcher(testScheduler),
        )

        assertTrue(playback.skip())
        val terminal = async { playback.awaitCompletion().also { events += "await" } }
        runCurrent()

        assertEquals(PlaybackCompletion.ExplicitlySkipped, terminal.await())
        assertEquals(listOf("play", "stop", "release", "await"), events)
    }

    @Test
    fun failedPlaybackPublishesCompletionOnlyAfterStoppingAndReleasingTrack() = runTest {
        val events = mutableListOf<String>()
        val track = streamingTrack(events) { 320 }
        every { track.play() } answers {
            events += "play"
            throw IllegalStateException("driver failed")
        }
        val playback = ActiveStreamPcmPlayback(
            track = track,
            recording = recording(),
            sampleRate = 16_000,
            dispatcher = StandardTestDispatcher(testScheduler),
        )
        val terminal = async { playback.awaitCompletion().also { events += "await" } }

        runCurrent()

        assertEquals(PlaybackCompletion.Failed("driver failed"), terminal.await())
        assertEquals(listOf("play", "stop", "release", "await"), events)
    }

    @Test
    fun cancelledPumpPublishesInterruptedOnlyAfterStoppingAndReleasingTrack() = runTest {
        val events = mutableListOf<String>()
        val track = streamingTrack(events) {
            events += "write"
            throw CancellationException("pump cancelled")
        }
        val playback = ActiveStreamPcmPlayback(
            track = track,
            recording = recording(),
            sampleRate = 16_000,
            dispatcher = StandardTestDispatcher(testScheduler),
        )
        val terminal = async { playback.awaitCompletion().also { events += "await" } }

        runCurrent()

        assertEquals(PlaybackCompletion.Interrupted, terminal.await())
        assertEquals(listOf("play", "write", "stop", "release", "await"), events)
    }

    private fun recording(): RecordedPcm = RecordedPcm(shortArrayOf(1), 16_000)

    private fun streamingTrack(
        events: MutableList<String>,
        writeResult: () -> Int,
    ): AudioTrack = mockk {
        every { playState } returns AudioTrack.PLAYSTATE_PLAYING
        every { playbackHeadPosition } returns 0
        every { play() } answers { events += "play" }
        every { write(any<ShortArray>(), any(), any(), AudioTrack.WRITE_BLOCKING) } answers { writeResult() }
        every { stop() } answers { events += "stop" }
        every { release() } answers { events += "release" }
    }
}
