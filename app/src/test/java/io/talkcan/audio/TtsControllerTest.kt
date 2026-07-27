package io.talkcan.audio

import io.talkcan.model.TtsStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.coroutines.coroutineContext

@OptIn(ExperimentalCoroutinesApi::class)
class TtsControllerTest {
    @Test
    fun successfulDiagnosticSynthesisPlaysRenderedAudioAndReturnsIdle() = runTest {
        val playRecorder = RecordingPlay(returns = true)
        val controller = controller(
            scope = this,
            play = playRecorder::play,
            synthesizer = FakeTtsSynthesizer().apply {
                setOutcome(SynthesisOutcome.Success(floatArrayOf(0.25f, -0.25f)))
            },
            dispatcher = coroutineContext[CoroutineDispatcher]!!,
        )

        controller.synthesize("diagnostic", "/voices/M1.json", "en", 8, 1.0f, 16_000)
        advanceUntilIdle()

        assertEquals(TtsStatus.Idle, controller.status.value)
        assertEquals(1, playRecorder.played.size)
        assertFalse(playRecorder.played.single().isEmpty)
        // The play callback returned true, so the controller returns to idle.
        assertEquals(1, playRecorder.callCount)
    }

    @Test
    fun diagnosticSynthesisFailureSurfacesHostErrorWithoutPlayback() = runTest {
        val playRecorder = RecordingPlay(returns = true)
        val controller = controller(
            scope = this,
            play = playRecorder::play,
            synthesizer = FakeTtsSynthesizer().apply {
                setOutcome(SynthesisOutcome.Failure("model unavailable"))
            },
            dispatcher = coroutineContext[CoroutineDispatcher]!!,
        )

        controller.synthesize("diagnostic", "/voices/M1.json", "en", 8, 1.0f, 16_000)
        advanceUntilIdle()

        assertEquals(TtsStatus.Error("model unavailable"), controller.status.value)
        assertTrue(playRecorder.played.isEmpty())
    }

    @Test
    fun cancelledHostPlayReturnsAudioUnavailableError() = runTest {
        val playRecorder = RecordingPlay(returns = false)
        val controller = controller(
            scope = this,
            play = playRecorder::play,
            synthesizer = FakeTtsSynthesizer().apply {
                setOutcome(SynthesisOutcome.Success(floatArrayOf(0.25f, -0.25f)))
            },
            dispatcher = coroutineContext[CoroutineDispatcher]!!,
        )

        controller.synthesize("diagnostic", "/voices/M1.json", "en", 8, 1.0f, 16_000)
        advanceUntilIdle()

        // When the host play callback returns false, the controller reports an error.
        assertEquals(TtsStatus.Error("Audio unavailable"), controller.status.value)
        assertEquals(1, playRecorder.played.size)
    }

    @Test
    fun cancellingDiagnosticPlaybackReturnsIdle() = runTest {
        val playbackStarted = CompletableDeferred<Unit>()
        val playRecorder = RecordingPlay(returns = true, playbackStarted = playbackStarted)
        val controller = controller(
            scope = this,
            play = playRecorder::play,
            synthesizer = FakeTtsSynthesizer().apply {
                setOutcome(SynthesisOutcome.Success(floatArrayOf(0.25f)))
            },
            dispatcher = coroutineContext[CoroutineDispatcher]!!,
        )

        controller.synthesize("diagnostic", "/voices/M1.json", "en", 8, 1.0f, 16_000)
        runCurrent()
        assertTrue(playbackStarted.isCompleted)
        assertEquals(TtsStatus.Playing, controller.status.value)

        controller.cancelAndRelease()

        // cancelAndRelease suspends until the cancelled synthesis job
        // terminates, so idle is observable without further scheduling.
        assertEquals(TtsStatus.Idle, controller.status.value)
        assertEquals(1, playRecorder.played.size)
        advanceUntilIdle()
    }

    @Test
    fun emptySynthesisResultSurfacesErrorWithoutCallingPlay() = runTest {
        val playRecorder = RecordingPlay(returns = true)
        val controller = controller(
            scope = this,
            play = playRecorder::play,
            synthesizer = FakeTtsSynthesizer().apply {
                setOutcome(SynthesisOutcome.Success(FloatArray(0)))
            },
            dispatcher = coroutineContext[CoroutineDispatcher]!!,
        )

        controller.synthesize("diagnostic", "/voices/M1.json", "en", 8, 1.0f, 16_000)
        advanceUntilIdle()

        // Empty samples produce an error without invoking the host play callback.
        assertEquals(TtsStatus.Error("Synthesis produced no audio"), controller.status.value)
        assertTrue(playRecorder.played.isEmpty())
    }

    @Test
    fun blankTextReturnsEmptyTextWithoutSynthesisOrPlayback() = runTest {
        val playRecorder = RecordingPlay(returns = true)
        val controller = controller(
            scope = this,
            play = playRecorder::play,
            synthesizer = FakeTtsSynthesizer(),
            dispatcher = coroutineContext[CoroutineDispatcher]!!,
        )

        controller.synthesize("", "/voices/M1.json", "en", 8, 1.0f, 16_000)
        advanceUntilIdle()

        assertEquals(TtsStatus.EmptyText, controller.status.value)
        assertTrue(playRecorder.played.isEmpty())
    }

    @Test
    fun playbackOverrideReplacesConstructionPlayForOneRequestOnly() = runTest {
        val defaultPlay = RecordingPlay(returns = true)
        val overridePlay = RecordingPlay(returns = true)
        val controller = controller(
            scope = this,
            play = defaultPlay::play,
            synthesizer = FakeTtsSynthesizer().apply {
                setOutcome(SynthesisOutcome.Success(floatArrayOf(0.25f, -0.25f)))
            },
            dispatcher = coroutineContext[CoroutineDispatcher]!!,
        )

        controller.synthesize(
            "preview", "/voices/M1.json", "en", 8, 1.0f, 16_000,
            playbackOverride = overridePlay::play,
        )
        advanceUntilIdle()

        assertEquals(TtsStatus.Idle, controller.status.value)
        assertEquals(1, overridePlay.callCount)
        assertEquals(0, defaultPlay.callCount)

        // Subsequent requests without an override return to the construction-time play.
        controller.synthesize("diagnostic", "/voices/M1.json", "en", 8, 1.0f, 16_000)
        advanceUntilIdle()

        assertEquals(1, defaultPlay.callCount)
        assertEquals(1, overridePlay.callCount)
    }

    @Test
    fun cancelActiveResetsStatusSynchronouslyWithoutJoin() = runTest {
        val playbackStarted = CompletableDeferred<Unit>()
        val playRecorder = RecordingPlay(returns = true, playbackStarted = playbackStarted)
        val controller = controller(
            scope = this,
            play = playRecorder::play,
            synthesizer = FakeTtsSynthesizer().apply {
                setOutcome(SynthesisOutcome.Success(floatArrayOf(0.25f)))
            },
            dispatcher = coroutineContext[CoroutineDispatcher]!!,
        )

        controller.synthesize("diagnostic", "/voices/M1.json", "en", 8, 1.0f, 16_000)
        runCurrent()
        assertEquals(TtsStatus.Playing, controller.status.value)

        // Status is idle immediately, without awaiting job termination.
        controller.cancelActive()
        assertEquals(TtsStatus.Idle, controller.status.value)
        advanceUntilIdle()
    }

    private fun controller(
        scope: kotlinx.coroutines.CoroutineScope,
        play: suspend (RecordedPcm) -> Boolean,
        synthesizer: TtsSynthesizer,
        dispatcher: CoroutineDispatcher,
    ) = TtsController(
        scope = scope,
        synthesizer = synthesizer,
        play = play,
        synthesisDispatcher = dispatcher,
    )

    private class RecordingPlay(
        private val returns: Boolean,
        val playbackStarted: CompletableDeferred<Unit>? = null,
    ) {
        val played = mutableListOf<RecordedPcm>()
        var callCount = 0
            private set

        suspend fun play(recording: RecordedPcm): Boolean {
            played += recording
            callCount += 1
            playbackStarted?.complete(Unit)
            if (playbackStarted != null) awaitCancellation()
            return returns
        }
    }
}