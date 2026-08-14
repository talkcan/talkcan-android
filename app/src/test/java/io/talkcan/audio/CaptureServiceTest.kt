package io.talkcan.audio

import io.talkcan.model.ScoState
import io.talkcan.service.CaptureFeedbackTone
import kotlin.coroutines.ContinuationInterceptor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CaptureServiceTest {

    @Test
    fun secondStartWhileSessionActiveIsRejected() = runTest {
        val service = captureService()
        val source = FakeSource(continuous = true)

        val first = service.startSession(source, FakeScoRoute(), FakeOutput(), 60_000L) { true }
        assertTrue(first is CaptureStartResult.Started)

        val second = service.startSession(FakeSource(), FakeScoRoute(), FakeOutput(), 60_000L) { true }
        assertEquals(CaptureStartResult.SessionActive, second)

        (first as CaptureStartResult.Started).session.stop()
    }

    @Test
    fun stopClearsActiveSoNextStartIsAccepted() = runTest {
        val service = captureService()
        val source = FakeSource(continuous = true)

        val first = service.startSession(source, FakeScoRoute(), FakeOutput(), 60_000L) { true }
        assertTrue(first is CaptureStartResult.Started)
        (first as CaptureStartResult.Started).session.stop()

        // The active reference must be cleared synchronously inside stop() so
        // a rapid PTT re-press — with no coroutine pump between release and
        // the next press — is accepted instead of rejected as SessionActive.
        val second = service.startSession(FakeSource(continuous = true), FakeScoRoute(), FakeOutput(), 60_000L) { true }
        assertTrue(
            "expected second start to be accepted immediately after stop, got $second",
            second is CaptureStartResult.Started,
        )
        (second as CaptureStartResult.Started).session.stop()
    }

    @Test
    fun startWithNoActiveSessionAcceptsAndOpensSource() = runTest {
        val service = captureService()
        val source = FakeSource(
            continuous = true,
            sourceId = CaptureSourceId.VoiceCommunication,
        )

        val result = service.startSession(source, FakeScoRoute(), FakeOutput(), 60_000L) { true }

        assertTrue(result is CaptureStartResult.Started)
        assertEquals(1, source.openCount)
        assertEquals(CaptureSourceId.VoiceCommunication, source.openedSourceId)
        assertTrue(service.isCapturing.value)

        (result as CaptureStartResult.Started).session.stop()
    }

    @Test
    fun framesEmitsChunksReadFromTheSource() = runTest {
        val service = captureService()
        val source = FakeSource(
            scriptedChunks = listOf(
                ShortArray(4) { 1 },
                ShortArray(4) { 2 },
                ShortArray(4) { 3 },
            ),
            continuous = false,
        )
        val session = startedSession(service, source)

        val collected = mutableListOf<ShortArray>()
        val collector = launch { session.frames.toList(collected) }

        advanceTimeBy(LOOP_TICK_MS * source.scriptedChunkCount)
        runCurrent()
        collector.cancel()

        assertTrue("expected at least one frame emitted, got ${collected.size}", collected.isNotEmpty())
        session.stop()
    }

    @Test
    fun stopReturnsFullCaptureWithAllSamples() = runTest {
        val service = captureService()
        val source = FakeSource(
            scriptedChunks = listOf(
                ShortArray(4) { 1 },
                ShortArray(4) { 2 },
                ShortArray(4) { 3 },
            ),
            continuous = false,
        )
        val session = startedSession(service, source)

        advanceTimeBy(LOOP_TICK_MS * source.scriptedChunkCount)
        runCurrent()
        val pcm = session.stop()

        assertEquals(12, pcm.samples.size)
        assertEquals(1.toShort(), pcm.samples[0])
        assertEquals(2.toShort(), pcm.samples[4])
        assertEquals(3.toShort(), pcm.samples[8])
    }

    @Test
    fun stopWithNoChunksReturnsEmptyPcm() = runTest {
        val service = captureService()
        val source = FakeSource(continuous = false)
        val session = startedSession(service, source)

        advanceTimeBy(SMALL_TICK_MS)
        runCurrent()

        val pcm = session.stop()
        assertEquals(0, pcm.samples.size)
        assertEquals(DEFAULT_RATE, pcm.sampleRate)
    }

    @Test
    fun sessionEndsAtMaxDurationCapWithCapturedPcm() = runTest {
        val service = captureService()
        val source = FakeSource(
            scriptedChunks = listOf(ShortArray(2) { 5 }),
            continuous = true,
        )
        val session = startedSession(service, source, maxDurationMs = MAX_DURATION_TEST_MS)

        advanceTimeBy(MAX_DURATION_TEST_MS + SMALL_TICK_MS)
        runCurrent()

        val completion = session.completion.await()
        assertTrue("expected MaxDuration, got $completion", completion is CaptureCompletion.MaxDuration)
        val pcm = completion.recordedPcm
        assertTrue(
            "expected at least the scripted chunk captured, got ${pcm.samples.size}",
            pcm.samples.size >= 2,
        )
        assertFalse(service.isCapturing.value)
    }

    @Test
    fun levelReflectsMicrophoneInputWhileCapturing() = runTest {
        val service = captureService()
        val source = FakeSource(
            scriptedChunks = listOf(ShortArray(16) { Short.MAX_VALUE }),
            continuous = false,
        )
        val session = startedSession(service, source)

        advanceTimeBy(SMALL_TICK_MS)
        runCurrent()

        assertTrue(
            "expected positive level for loud input, got ${service.level.value}",
            service.level.value > 0f,
        )

        session.stop()
    }

    @Test
    fun levelIsZeroWhenNoSessionActive() = runTest {
        val service = captureService()
        assertEquals(0f, service.level.value)
        assertFalse(service.isCapturing.value)
    }

    @Test
    fun isCapturingAndLevelReflectFullSessionLifecycle() = runTest {
        val service = captureService()
        val source = FakeSource(
            scriptedChunks = listOf(ShortArray(16) { Short.MAX_VALUE }),
            continuous = false,
        )

        assertFalse(service.isCapturing.value)
        assertEquals(0f, service.level.value)

        val session = startedSession(service, source)
        assertTrue(service.isCapturing.value)

        advanceTimeBy(SMALL_TICK_MS)
        runCurrent()
        assertTrue(
            "level should reflect input while capturing, got ${service.level.value}",
            service.level.value > 0f,
        )

        session.stop()
        assertFalse(service.isCapturing.value)
        assertEquals(0f, service.level.value)
    }

    @Test
    fun silentInputYieldsNearZeroLevel() = runTest {
        val service = captureService()
        val source = FakeSource(
            scriptedChunks = listOf(ShortArray(16) { 0 }),
            continuous = false,
        )
        val session = startedSession(service, source)

        advanceTimeBy(SMALL_TICK_MS)
        runCurrent()

        assertEquals(0f, service.level.value, 0.001f)
        session.stop()
    }

    @Test
    fun isCapturingTransitionsThroughSessionLifecycle() = runTest {
        val service = captureService()
        val source = FakeSource(continuous = true)

        assertFalse(service.isCapturing.value)

        val session = startedSession(service, source)
        assertTrue(service.isCapturing.value)

        session.stop()
        assertFalse(service.isCapturing.value)
    }

    @Test
    fun cancelledSessionReportsCancelledCompletion() = runTest {
        val service = captureService()
        val source = FakeSource(continuous = false)
        val session = startedSession(service, source)

        advanceTimeBy(SMALL_TICK_MS)
        runCurrent()
        val pcm = service.cancelSession(session)

        assertEquals(0, pcm.samples.size)
        val completion = session.completion.await()
        assertTrue("expected Cancelled, got $completion", completion is CaptureCompletion.Cancelled)
        assertFalse(service.isCapturing.value)
    }

    @Test
    fun scoUnavailableIsTypedFailureAndDoesNotOpenSource() = runTest {
        val service = captureService()
        val source = FakeSource(continuous = true)
        val sco = FakeScoRoute(scoAvailable = false)

        val result = service.startSession(source, sco, FakeOutput(), 60_000L) { true }

        assertEquals(CaptureStartResult.ScoUnavailable, result)
        assertEquals(0, source.openCount)
        assertFalse(service.isCapturing.value)
    }

    @Test
    fun shouldProceedFalseAfterScoAcquireCancelsBeforeBeep() = runTest {
        val service = captureService()
        val source = FakeSource(continuous = true)
        val sco = FakeScoRoute()
        val output = FakeOutput()

        val result = service.startSession(source, sco, output, 60_000L) { false }

        assertEquals(CaptureStartResult.Cancelled, result)
        assertEquals(0, output.readyBeepCount)
        assertEquals(0, source.openCount)
        assertEquals(1, sco.acquireCount)
        assertEquals(
            "service must release SCO on Cancelled after SCO acquire (PTT released during acquire)",
            1,
            sco.releaseCount,
        )
    }

    @Test
    fun shouldProceedFalseAfterPreflightCancelsBeforeBeep() = runTest {
        val service = captureService()
        val source = FakeSource(continuous = true)
        val sco = FakeScoRoute()
        val output = FakeOutput()

        // First predicate call (after SCO acquire) returns true → source preflight opens.
        // Second predicate call (after preflight) returns false → no beep, no recording.
        var predicateCalls = 0
        val result = service.startSession(source, sco, output, 60_000L) {
            predicateCalls += 1
            predicateCalls == 1
        }

        assertEquals(CaptureStartResult.Cancelled, result)
        assertEquals(0, output.readyBeepCount)
        assertEquals(1, source.openCount)
        assertEquals(
            "service must release SCO on Cancelled after preflight before beep",
            1,
            sco.releaseCount,
        )
    }

    @Test
    fun sourceOpenFailureReportsRecordingFailed() = runTest {
        val service = captureService()
        val source = FakeSource(openShouldFail = true)
        val sco = FakeScoRoute()
        val output = FakeOutput()

        val result = service.startSession(source, sco, output, 60_000L) { true }

        assertEquals(CaptureStartResult.RecordingFailed, result)
        assertEquals(0, output.readyBeepCount)
        assertFalse(service.isCapturing.value)
        assertEquals(
            "service must release SCO on RecordingFailed (source open returned null before beep)",
            1,
            sco.releaseCount,
        )
    }

    @Test
    fun rapidRePressAfterCancelSessionIsAccepted() = runTest {
        val service = captureService()
        val source = FakeSource(continuous = true)

        val first = service.startSession(source, FakeScoRoute(), FakeOutput(), 60_000L) { true }
        assertTrue(first is CaptureStartResult.Started)
        val firstSession = (first as CaptureStartResult.Started).session

        service.cancelSession(firstSession)

        // No advanceTimeBy / runCurrent between cancel and re-press — the
        // service must have cleared `active` synchronously inside
        // `cancelSession()` → `ActiveSession.cancel()` → `finalize()`.
        val second = service.startSession(
            FakeSource(continuous = true),
            FakeScoRoute(),
            FakeOutput(),
            60_000L,
        ) { true }
        assertTrue(
            "expected rapid re-press after cancelSession to be accepted, got $second",
            second is CaptureStartResult.Started,
        )
        (second as CaptureStartResult.Started).session.stop()
    }

    @Test
    fun bufferCapPreventsAccumulationPastMaxDurationLimit() = runTest {
        val tinySampleRate = 4
        val maxDurationMs = 1000L // 1 second worth -> 4 samples
        val capSamples = 4
        val scriptedChunkCount = 4
        val scriptedChunkSize = 4

        val service = captureService()
        val source = FakeSource(
            sampleRate = tinySampleRate,
            scriptedChunks = List(scriptedChunkCount) { ShortArray(scriptedChunkSize) { 1 } },
            continuous = false,
        )
        val session = startedSession(service, source, maxDurationMs = maxDurationMs)

        advanceTimeBy(LOOP_TICK_MS * scriptedChunkCount + SMALL_TICK_MS)
        runCurrent()
        val pcm = session.stop()

        assertEquals(capSamples, pcm.samples.size)
    }

    @Test
    fun pcmBeyond60SecondsIsRetained() = runTest {
        val sampleRate = 16_000
        val maxDurationMs = 70_000L // 70 seconds
        val chunkSize = 1600 // 100ms
        val service = captureService()
        val chunks = List(650) { index -> ShortArray(chunkSize) { index.toShort() } }
        val source = FakeSource(
            sampleRate = sampleRate,
            scriptedChunks = chunks,
            continuous = false
        )
        val session = startedSession(service, source, maxDurationMs = maxDurationMs)

        advanceTimeBy(LOOP_TICK_MS * 650 + SMALL_TICK_MS)
        runCurrent()

        val pcm = session.stop()
        assertEquals(1_040_000, pcm.samples.size)
        for (i in 0 until 650) {
            val offset = i * chunkSize
            for (j in 0 until chunkSize) {
                assertEquals(i.toShort(), pcm.samples[offset + j])
            }
        }
    }

    @Test
    fun exactMaxDurationCompletion() = runTest {
        val sampleRate = 16_000
        val maxDurationMs = 1000L // 1 second -> exactly 16,000 samples
        val maxSamples = 16_000
        val service = captureService()
        val chunks = List(11) { index -> ShortArray(1600) { (index + 1).toShort() } }
        val source = FakeSource(
            sampleRate = sampleRate,
            scriptedChunks = chunks,
            continuous = false
        )
        val session = startedSession(service, source, maxDurationMs = maxDurationMs)

        advanceTimeBy(1000L + SMALL_TICK_MS)
        runCurrent()

        val completion = session.completion.await()
        assertTrue("expected MaxDuration, got $completion", completion is CaptureCompletion.MaxDuration)
        val pcm = completion.recordedPcm
        assertEquals(maxSamples, pcm.samples.size)

        for (i in 0 until 10) {
            val offset = i * 1600
            for (j in 0 until 1600) {
                assertEquals((i + 1).toShort(), pcm.samples[offset + j])
            }
        }
    }

    @Test
    fun flattenOrderIsCorrect() = runTest {
        val sampleRate = 16_000
        val maxDurationMs = 5000L
        val service = captureService()
        val chunks = listOf(
            shortArrayOf(1, 2, 3, 4),
            shortArrayOf(5, 6),
            shortArrayOf(7, 8, 9)
        )
        val source = FakeSource(
            sampleRate = sampleRate,
            scriptedChunks = chunks,
            continuous = false
        )
        val session = startedSession(service, source, maxDurationMs = maxDurationMs)

        advanceTimeBy(LOOP_TICK_MS * chunks.size + SMALL_TICK_MS)
        runCurrent()

        val pcm = session.stop()
        val expected = shortArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9)
        org.junit.Assert.assertArrayEquals(expected, pcm.samples)
    }

    @Test
    fun boundaryOverflowBehavior() = runTest {
        val opened = object : OpenedCaptureSource {
            override val sampleRate: Int = 16_000
            override val bufferSizeShorts: Int = 1024
            override val startupEvidence: CaptureStartupEvidence = CaptureStartupEvidence()
            override fun readNonBlocking(buffer: ShortArray): Int = 0
            override fun read(buffer: ShortArray): Int = 0
            override fun close() {}
        }

        val validSession = CaptureSessionImpl(
            scope = this,
            opened = opened,
            coldStart = false,
            readDispatcher = testDispatcher(this),
            maxDurationMs = 60_000L,
            clock = { 0L },
            onCaptureSignalChange = {},
            onLevelUpdate = {},
            onFinalize = {},
            pcmOutput = FakeOutput(),
        )
        validSession.cancel()

        try {
            CaptureSessionImpl(
                scope = this,
                opened = opened,
                coldStart = false,
                readDispatcher = testDispatcher(this),
                maxDurationMs = -1L,
                clock = { 0L },
                onCaptureSignalChange = {},
                onLevelUpdate = {},
                onFinalize = {},
                pcmOutput = FakeOutput(),
            )
            org.junit.Assert.fail("expected IllegalArgumentException for negative duration")
        } catch (e: IllegalArgumentException) {
            // expected
        }

        try {
            CaptureSessionImpl(
                scope = this,
                opened = opened,
                coldStart = false,
                readDispatcher = testDispatcher(this),
                maxDurationMs = Long.MAX_VALUE,
                clock = { 0L },
                onCaptureSignalChange = {},
                onLevelUpdate = {},
                onFinalize = {},
                pcmOutput = FakeOutput(),
            )
            org.junit.Assert.fail("expected IllegalArgumentException for overflow duration")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun sessionExposesNegotiated16kHzSampleRate() = runTest {
        val service = captureService()
        val source = FakeSource(
            sampleRate = 16_000,
            continuous = true,
        )

        val session = startedSession(service, source)

        assertEquals(16_000, session.sampleRate)
        session.stop()
    }

    @Test
    fun sessionExposesNegotiated8kHzSampleRate() = runTest {
        val service = captureService()
        val source = FakeSource(
            sampleRate = 8_000,
            continuous = true,
        )

        val session = startedSession(service, source)

        assertEquals(8_000, session.sampleRate)
        session.stop()
    }

    @Test
    fun startSessionReturnsRecorderStartupEvidence() = runTest {
        val service = captureService()
        val source = FakeSource(
            sampleRate = 8_000,
            sourceId = CaptureSourceId.VoiceCommunication,
            continuous = true,
            startupEvidence = CaptureStartupEvidence(
                clientSilenced = false,
                inputDeviceName = "car-hfp",
            ),
        )

        val result = service.startSession(source, FakeScoRoute(), FakeOutput(), 60_000L) { true }

        assertTrue(result is CaptureStartResult.Started)
        val started = result as CaptureStartResult.Started
        val evidence = started.evidence
        assertTrue(evidence.recorderOpened)
        assertEquals(CaptureSourceId.VoiceCommunication, evidence.sourceId)
        assertEquals(8_000, evidence.sampleRate)
        assertEquals(false, evidence.clientSilenced)
        assertEquals("car-hfp", evidence.inputDeviceName)
        started.session.stop()
    }

    @Test
    fun requiredPreCommitSignalTimesOutBeforeReadyBeepAndCleansUpExactlyOnce() = runTest {
        val service = captureService()
        val source = BeepBoundarySource(
            preBeepChunks = listOf(shortArrayOf(0, 0)),
            requiresPreCommitSignal = true,
        )
        val sco = FakeScoRoute()
        val output = FakeOutput()

        val result = service.startSession(source, sco, output, 60_000L) { true }

        assertEquals(CaptureStartResult.RecordingFailed, result)
        assertEquals(1, source.preBeepChunksRead)
        assertEquals(0, output.readyBeepCount)
        assertEquals(1, source.closeCount)
        assertEquals(1, sco.releaseCount)
        assertFalse(service.isCapturing.value)
    }

    @Test
    fun laterNonzeroPreCommitSignalStartsAndKeepsOnlyCommittedPcm() = runTest {
        val service = captureService()
        val source = BeepBoundarySource(
            preBeepChunks = listOf(
                shortArrayOf(0, 0),
                shortArrayOf(0, 6),
            ),
            postBeepChunks = listOf(shortArrayOf(21, 22)),
            requiresPreCommitSignal = true,
        )
        val output = FakeOutput(onReadyBeep = source::markBeepComplete)

        val result = service.startSession(source, FakeScoRoute(), output, 60_000L) { true }

        assertTrue("expected Started after a nonzero pre-commit sample, got $result", result is CaptureStartResult.Started)
        assertEquals(2, source.preBeepChunksRead)
        assertEquals(1, output.readyBeepCount)

        val session = (result as CaptureStartResult.Started).session
        source.allowPostBeepReads()
        advanceTimeBy(SMALL_TICK_MS)
        runCurrent()

        val pcm = session.stop()
        assertEquals(listOf<Short>(21, 22), pcm.samples.asList())
    }

    @Test
    fun zeroOnlyFirstRecorderReopensWithinScoAndReturnsOnlySecondRecorderPcm() = runTest {
        val service = captureService()
        val source = ReopeningSource(
            attempts = listOf(
                ReopenAttempt(
                    preCommitChunks = listOf(shortArrayOf(0, 0)),
                    committedChunks = listOf(shortArrayOf(91, 92)),
                ),
                ReopenAttempt(
                    preCommitChunks = listOf(shortArrayOf(0, 8)),
                    committedChunks = listOf(shortArrayOf(21, 22)),
                ),
            ),
        )
        val sco = FakeScoRoute()
        val output = FakeOutput(onReadyBeep = source::markBeepComplete)

        val result = service.startSession(source, sco, output, 60_000L) { true }

        assertTrue("expected second recorder to start, got $result", result is CaptureStartResult.Started)
        assertEquals(listOf("open-1", "close-1", "open-2"), source.lifecycleHistory)
        assertEquals(listOf(1, 0), source.closeCounts)
        assertEquals(1, sco.acquireCount)
        assertEquals(0, sco.releaseCount)
        assertEquals(1, output.readyBeepCount)

        advanceTimeBy(SMALL_TICK_MS)
        runCurrent()
        val pcm = (result as CaptureStartResult.Started).session.stop()

        assertEquals(listOf<Short>(21, 22), pcm.samples.asList())
        assertEquals(listOf(1, 1), source.closeCounts)
    }

    @Test
    fun twoZeroOnlyRecordersFailWithoutReadyBeepAndCleanUpRouteOnce() = runTest {
        val service = captureService()
        val source = ReopeningSource(
            attempts = listOf(
                ReopenAttempt(preCommitChunks = listOf(shortArrayOf(0, 0))),
                ReopenAttempt(preCommitChunks = listOf(shortArrayOf(0, 0))),
            ),
        )
        val sco = FakeScoRoute()
        val output = FakeOutput()

        val result = service.startSession(source, sco, output, 60_000L) { true }

        assertEquals(CaptureStartResult.RecordingFailed, result)
        assertEquals(listOf("open-1", "close-1", "open-2", "close-2"), source.lifecycleHistory)
        assertEquals(listOf(1, 1), source.closeCounts)
        assertEquals(0, output.readyBeepCount)
        assertEquals(1, sco.acquireCount)
        assertEquals(1, sco.releaseCount)
        assertFalse(service.isCapturing.value)
    }

    @Test
    fun releaseDuringFinalPreCommitSignalTimeoutCancelsAfterClosingLastRecorder() = runTest {
        val service = captureService()
        val source = ReopeningSource(
            attempts = listOf(
                ReopenAttempt(preCommitChunks = listOf(shortArrayOf(0, 0))),
                ReopenAttempt(preCommitChunks = listOf(shortArrayOf(0, 0))),
            ),
        )
        val sco = FakeScoRoute()
        val output = FakeOutput()
        val start = CompletableDeferred<CaptureStartResult>()
        var shouldProceed = true

        launch {
            start.complete(service.startSession(source, sco, output, 60_000L) { shouldProceed })
        }
        runCurrent()
        advanceTimeBy(PRE_COMMIT_SIGNAL_TIMEOUT_MS)
        runCurrent()
        advanceTimeBy(PRE_COMMIT_SIGNAL_RETRY_DELAY_MS)
        runCurrent()
        assertEquals(listOf("open-1", "close-1", "open-2"), source.lifecycleHistory)

        shouldProceed = false
        advanceTimeBy(PRE_COMMIT_SIGNAL_TIMEOUT_MS)
        runCurrent()

        assertEquals(CaptureStartResult.Cancelled, start.await())
        assertEquals(listOf("open-1", "close-1", "open-2", "close-2"), source.lifecycleHistory)
        assertEquals(listOf(1, 1), source.closeCounts)
        assertEquals(0, output.readyBeepCount)
        assertFalse(service.isCapturing.value)
        assertEquals(1, sco.releaseCount)
    }

    @Test
    fun releaseDuringRecorderRetryDelayCancelsBeforeSecondOpen() = runTest {
        val service = captureService()
        val source = ReopeningSource(
            attempts = listOf(
                ReopenAttempt(preCommitChunks = listOf(shortArrayOf(0, 0))),
                ReopenAttempt(preCommitChunks = listOf(shortArrayOf(0, 7))),
            ),
        )
        val sco = FakeScoRoute()
        val output = FakeOutput()
        val start = CompletableDeferred<CaptureStartResult>()
        var released = false

        launch {
            start.complete(service.startSession(source, sco, output, 60_000L) { !released })
        }
        source.firstClose.await()
        released = true

        assertEquals(CaptureStartResult.Cancelled, start.await())
        assertEquals(listOf("open-1", "close-1"), source.lifecycleHistory)
        assertEquals(listOf(1), source.closeCounts)
        assertEquals(0, output.readyBeepCount)
        assertEquals(1, sco.acquireCount)
        assertEquals(1, sco.releaseCount)
        assertFalse(service.isCapturing.value)
    }

    @Test
    fun preCommitUsesNonBlockingReadsThenPublishesOnlyCommittedPcm() = runTest {
        val service = captureService()
        val source = BeepBoundarySource(
            preBeepChunks = listOf(ShortArray(2) { 1 }),
            postBeepChunks = listOf(ShortArray(2) { 2 }),
        )
        val output = GatedReadyBeepOutput(onComplete = source::markBeepComplete)
        val start = CompletableDeferred<CaptureStartResult>()

        launch {
            start.complete(service.startSession(source, FakeScoRoute(), output, 60_000L) { true })
        }
        runCurrent()
        output.readyBeepStarted.await()

        advanceTimeBy(SMALL_TICK_MS * 2)
        runCurrent()

        assertEquals(1, source.preBeepChunksRead)
        assertEquals(0, source.committedReadCount)
        assertTrue(source.readHistory.all { it == BoundaryRead.NonBlockingDrain })

        output.completeReadyBeep()
        val result = start.await()
        assertTrue("expected Started, got $result", result is CaptureStartResult.Started)
        val session = (result as CaptureStartResult.Started).session
        runCurrent()

        assertTrue("committed reader did not start after the drain joined", source.committedReadCount > 0)
        val readsAfterHandoff = source.readHistory
        val firstCommitted = readsAfterHandoff.indexOf(BoundaryRead.Committed)
        assertTrue("expected at least one pre-commit drain read", firstCommitted > 0)
        assertTrue(
            "nonblocking drain read occurred after committed capture began: $readsAfterHandoff",
            readsAfterHandoff.drop(firstCommitted).all { it == BoundaryRead.Committed },
        )

        val frames = mutableListOf<ShortArray>()
        val collector = launch { session.frames.toList(frames) }
        runCurrent()
        source.allowPostBeepReads()
        advanceTimeBy(SMALL_TICK_MS * 2)
        runCurrent()

        val pcm = session.stop()
        collector.cancel()

        assertEquals(listOf<Short>(2, 2), frames.flatMap { it.asList() })
        assertEquals(listOf<Short>(2, 2), pcm.samples.asList())
    }

    @Test
    fun releaseDuringPreCommitDrainClosesOpenedSourceWithoutVisiblePcm() = runTest {
        val service = captureService()
        val source = BeepBoundarySource(preBeepChunks = listOf(ShortArray(2) { 7 }))
        val output = GatedReadyBeepOutput(onComplete = source::markBeepComplete)
        var released = false
        val start = CompletableDeferred<CaptureStartResult>()

        launch {
            start.complete(service.startSession(source, FakeScoRoute(), output, 60_000L) { !released })
        }
        runCurrent()
        output.readyBeepStarted.await()
        advanceTimeBy(SMALL_TICK_MS * 2)
        runCurrent()
        released = true
        output.completeReadyBeep()

        val result = start.await()
        assertEquals(CaptureStartResult.Cancelled, result)
        assertEquals(1, source.preBeepChunksRead)
        assertEquals(1, source.closeCount)
        assertFalse(service.isCapturing.value)
    }

    @Test
    fun readyBeepFailureClosesOpenedSourceWithoutVisiblePcm() = runTest {
        val service = captureService()
        val source = BeepBoundarySource(preBeepChunks = listOf(ShortArray(2) { 9 }))
        val output = GatedReadyBeepOutput(
            onComplete = source::markBeepComplete,
            failure = IllegalStateException("beep failed"),
        )
        val start = CompletableDeferred<CaptureStartResult>()

        launch {
            start.complete(service.startSession(source, FakeScoRoute(), output, 60_000L) { true })
        }
        runCurrent()
        output.readyBeepStarted.await()
        advanceTimeBy(SMALL_TICK_MS * 2)
        runCurrent()
        output.completeReadyBeep()

        val result = start.await()
        assertEquals(CaptureStartResult.RecordingFailed, result)
        assertEquals(1, source.preBeepChunksRead)
        assertEquals(1, source.closeCount)
        assertFalse(service.isCapturing.value)
    }

    @Test
    fun unknownRecorderSilencingEvidenceDoesNotRejectCapture() = runTest {
        val service = captureService()
        val output = FakeOutput()
        val source = FakeSource(
            continuous = true,
            startupEvidence = CaptureStartupEvidence(clientSilenced = null),
        )

        val result = service.startSession(source, FakeScoRoute(), output, 60_000L) { true }

        assertTrue("expected Started, got $result", result is CaptureStartResult.Started)
        val evidence = (result as CaptureStartResult.Started).evidence
        assertEquals(null, evidence.clientSilenced)
        assertEquals(1, output.readyBeepCount)
        (result as CaptureStartResult.Started).session.stop()
    }

    @Test
    fun silencedRecorderIsTypedFailureAndClosesOpenedSource() = runTest {
        val service = captureService()
        val source = FakeSource(
            continuous = true,
            startupEvidence = CaptureStartupEvidence(clientSilenced = true),
        )
        val sco = FakeScoRoute()
        val output = FakeOutput()

        val result = service.startSession(source, sco, output, 60_000L) { true }

        assertTrue(result is CaptureStartResult.RecordingSilenced)
        val evidence = (result as CaptureStartResult.RecordingSilenced).evidence
        assertTrue(evidence.recorderOpened)
        assertEquals(CaptureSourceId.VoiceCommunication, evidence.sourceId)
        assertEquals(1, source.openCount)
        assertEquals(1, source.lastOpened?.closeCount)
        assertEquals(1, sco.releaseCount)
        assertEquals(0, output.readyBeepCount)
        assertFalse(service.isCapturing.value)
    }

    @Test
    fun toneGeneratorExactShapes() {
        val sampleRate = 16_000
        val warning = CaptureFeedbackToneGenerator.generateWarningTones(sampleRate)
        // 3 tones of 150ms and 2 silences of 150ms => total 750ms => 12_000 samples
        assertEquals(12_000, warning.size)

        // T1: 0..2399, Silence 1: 2400..4799, T2: 4800..7199, Silence 2: 7200..9599, T3: 9600..11999
        for (i in 2400 until 4800) {
            assertEquals(0.toShort(), warning[i])
        }
        for (i in 7200 until 9600) {
            assertEquals(0.toShort(), warning[i])
        }

        // Check amplitudes and zero-crossings / shape of active parts
        for (i in listOf(0, 4800, 9600)) {
            // Tones should be exactly 880 Hz
            for (idx in 0 until 2400) {
                val phase = 2.0 * Math.PI * 880.0 * idx / sampleRate
                val expected = (Math.sin(phase) * Short.MAX_VALUE * 0.35).toInt().toShort()
                assertEquals(expected, warning[i + idx])
            }
        }

        // Final tone: 750ms => 12_000 samples
        val finalTone = CaptureFeedbackToneGenerator.generateFinalTone(sampleRate)
        assertEquals(12_000, finalTone.size)
        for (idx in 0 until 12_000) {
            val phase = 2.0 * Math.PI * 880.0 * idx / sampleRate
            val expected = (Math.sin(phase) * Short.MAX_VALUE * 0.35).toInt().toShort()
            assertEquals(expected, finalTone[idx])
        }
    }

    @Test
    fun wrapperDelegationAndActiveRouteUse() = runTest {
        val fakeOutput = FakeOutput()

        // 1. ScopedPcmOutput
        var released = false
        val scoped = ScopedPcmOutput(fakeOutput) { released = true }
        scoped.playCaptureFeedback(CaptureFeedbackTone.RecordingLimitWarning)
        assertEquals(CaptureFeedbackTone.RecordingLimitWarning, fakeOutput.playedFeedbackTones.single())
        fakeOutput.playedFeedbackTones.clear()

        // 2. MediaResponsePcmOutput
        val mediaResponse = MediaResponsePcmOutput(fakeOutput, object : ResponsePlayer {
            override suspend fun play(recording: RecordedPcm) {}
        })
        mediaResponse.playCaptureFeedback(CaptureFeedbackTone.RecordingLimitFinal)
        assertEquals(CaptureFeedbackTone.RecordingLimitFinal, fakeOutput.playedFeedbackTones.single())
        fakeOutput.playedFeedbackTones.clear()

        // 3. TelecomCapturePcmOutput
        val telecom = TelecomCapturePcmOutput(
            captureOutput = fakeOutput,
            mediaResponsePlayer = object : ResponsePlayer {
                override suspend fun play(recording: RecordedPcm) {}
            },
            releaseCaptureRoute = {},
            awaitTelecomDisconnected = { null }
        )
        telecom.playCaptureFeedback(CaptureFeedbackTone.RecordingLimitWarning)
        assertEquals(CaptureFeedbackTone.RecordingLimitWarning, fakeOutput.playedFeedbackTones.single())
    }

    @Test
    fun continuedReadDuringTone() = runTest {
        val service = captureService()
        val source = BeepBoundarySource(
            preBeepChunks = emptyList(),
            postBeepChunks = listOf(ShortArray(160) { 1 }, ShortArray(160) { 2 }),
        )
        val mockOutput = object : PcmOutput {
            override suspend fun playReadyBeep(coldStart: Boolean) = source.markBeepComplete()
            override suspend fun playErrorBeep(coldStart: Boolean) = Unit
            override suspend fun play(recording: RecordedPcm) = Unit
            override suspend fun playCaptureFeedback(tone: CaptureFeedbackTone) {
                delay(100)
            }
        }
        val startResult = service.startSession(
            source,
            FakeScoRoute(),
            mockOutput,
            60_000L,
        ) { true }
        val session = (startResult as CaptureStartResult.Started).session

        val collectedFrames = mutableListOf<ShortArray>()
        val collectJob = launch(UnconfinedTestDispatcher()) {
            session.frames.collect {
                collectedFrames.add(it)
            }
        }
        runCurrent()

        val emitJob = launch {
            session.semanticFeedbackEmitter.emit(CaptureFeedbackTone.RecordingLimitWarning)
        }
        runCurrent()
        source.allowPostBeepReads()
        advanceTimeBy(50)
        runCurrent()

        assertTrue(collectedFrames.isNotEmpty())
        assertTrue(source.committedReadCount > 0)
        assertFalse(emitJob.isCompleted)
        advanceTimeBy(50)
        emitJob.join()
        collectJob.cancel()
        session.stop()
    }

    @Test
    fun captureFeedbackPlaybackFailureReachesEmitterCaller() = runTest {
        val service = captureService()
        val source = FakeSource(continuous = true)
        val output = object : PcmOutput {
            override suspend fun playReadyBeep(coldStart: Boolean) = Unit
            override suspend fun playErrorBeep(coldStart: Boolean) = Unit
            override suspend fun play(recording: RecordedPcm) = Unit
            override suspend fun playCaptureFeedback(tone: CaptureFeedbackTone) {
                throw IllegalStateException("feedback route failed")
            }
        }
        val startResult = service.startSession(
            source,
            FakeScoRoute(),
            output,
            60_000L,
        ) { true }
        val session = (startResult as CaptureStartResult.Started).session

        val failure = runCatching {
            session.semanticFeedbackEmitter.emit(CaptureFeedbackTone.RecordingLimitWarning)
        }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertEquals("feedback route failed", failure?.message)
        service.cancelSession(session)
    }

    @Test
    fun lateFeedbackRejection() = runTest {
        val service = captureService()
        val source = FakeSource(continuous = true)
        val fakeOutput = FakeOutput()
        val startResult = service.startSession(source, FakeScoRoute(), fakeOutput, 60_000L) { true }
        val session = (startResult as CaptureStartResult.Started).session

        // Play warning once successfully
        session.semanticFeedbackEmitter.emit(CaptureFeedbackTone.RecordingLimitWarning)
        assertEquals(CaptureFeedbackTone.RecordingLimitWarning, fakeOutput.playedFeedbackTones.single())
        fakeOutput.playedFeedbackTones.clear()

        // Stop session
        session.stop()

        // Try emitting late feedback -> should reject (throw IllegalStateException)
        var thrown = false
        try {
            session.semanticFeedbackEmitter.emit(CaptureFeedbackTone.RecordingLimitFinal)
        } catch (e: IllegalStateException) {
            thrown = true
        }
        assertTrue("Expected IllegalStateException for late feedback after stop", thrown)

        // Try cancel session
        val startResult2 = service.startSession(source, FakeScoRoute(), fakeOutput, 60_000L) { true }
        val session2 = (startResult2 as CaptureStartResult.Started).session
        service.cancelSession(session2)
        thrown = false
        try {
            session2.semanticFeedbackEmitter.emit(CaptureFeedbackTone.RecordingLimitFinal)
        } catch (e: IllegalStateException) {
            thrown = true
        }
        assertTrue("Expected IllegalStateException for late feedback after cancel", thrown)

        // Test natural max duration finalization rejection
        val source3 = FakeSource(continuous = true)
        val startResult3 = service.startSession(source3, FakeScoRoute(), fakeOutput, 50L) { true }
        val session3 = (startResult3 as CaptureStartResult.Started).session

        // Advance time to trigger natural max duration finalization
        advanceTimeBy(100L)
        runCurrent()

        thrown = false
        try {
            session3.semanticFeedbackEmitter.emit(CaptureFeedbackTone.RecordingLimitFinal)
        } catch (e: IllegalStateException) {
            thrown = true
        }
        assertTrue("Expected IllegalStateException for late feedback after max duration", thrown)
    }

    @Test
    fun stopCancelJoinAndCancelFeedback() = runTest {
        val service = captureService()
        val source = FakeSource(continuous = true)

        var tonePlayStarted = false
        var tonePlayCompleted = false
        var tonePlayCancelled = false
        val slowOutput = object : PcmOutput {
            override suspend fun playReadyBeep(coldStart: Boolean) {}
            override suspend fun playErrorBeep(coldStart: Boolean) {}
            override suspend fun play(recording: RecordedPcm) {}
            override suspend fun playCaptureFeedback(tone: CaptureFeedbackTone) {
                tonePlayStarted = true
                try {
                    delay(10_000L) // Simulate long playing tone
                    tonePlayCompleted = true
                } catch (e: CancellationException) {
                    tonePlayCancelled = true
                    throw e
                }
            }
        }

        val startResult = service.startSession(source, FakeScoRoute(), slowOutput, 60_000L) { true }
        val session = (startResult as CaptureStartResult.Started).session

        // Start playing tone
        val emitJob = launch {
            session.semanticFeedbackEmitter.emit(CaptureFeedbackTone.RecordingLimitWarning)
        }
        runCurrent()
        assertTrue(tonePlayStarted)

        // Stop session -> should cancel tone and wait for it to join
        session.stop()
        runCurrent()
        assertTrue(tonePlayCancelled)
        assertFalse(tonePlayCompleted)
        emitJob.join()

        // Same for cancel
        tonePlayStarted = false
        tonePlayCompleted = false
        tonePlayCancelled = false
        val startResult2 = service.startSession(source, FakeScoRoute(), slowOutput, 60_000L) { true }
        val session2 = (startResult2 as CaptureStartResult.Started).session

        val emitJob2 = launch {
            session2.semanticFeedbackEmitter.emit(CaptureFeedbackTone.RecordingLimitWarning)
        }
        runCurrent()
        assertTrue(tonePlayStarted)

        service.cancelSession(session2)
        runCurrent()
        assertTrue(tonePlayCancelled)
        assertFalse(tonePlayCompleted)
        emitJob2.join()
    }

    @Test
    fun callerCancellationCannotOrphanPlayback() = runTest {
        val service = captureService()
        val source = FakeSource(continuous = true)

        var tonePlayStarted = false
        var tonePlayCompleted = false
        var tonePlayCancelled = false
        val slowOutput = object : PcmOutput {
            override suspend fun playReadyBeep(coldStart: Boolean) {}
            override suspend fun playErrorBeep(coldStart: Boolean) {}
            override suspend fun play(recording: RecordedPcm) {}
            override suspend fun playCaptureFeedback(tone: CaptureFeedbackTone) {
                tonePlayStarted = true
                try {
                    delay(10_000L) // Simulate long playing tone
                    tonePlayCompleted = true
                } catch (e: CancellationException) {
                    tonePlayCancelled = true
                    throw e
                }
            }
        }

        val startResult = service.startSession(source, FakeScoRoute(), slowOutput, 60_000L) { true }
        val session = (startResult as CaptureStartResult.Started).session

        val emitJob = launch {
            session.semanticFeedbackEmitter.emit(CaptureFeedbackTone.RecordingLimitWarning)
        }
        runCurrent()
        assertTrue(tonePlayStarted)

        // Cancel the caller job (emitJob)
        emitJob.cancel()
        runCurrent()

        // Assert that the playback was cancelled and joined, and is not orphaned
        assertTrue(tonePlayCancelled)
        assertFalse(tonePlayCompleted)

        // Ensure clean shutdown
        session.stop()
    }

    @Test
    fun maxDurationWaitsForFeedbackCancellation() = runTest {
        val service = captureService()
        val source = FakeSource(continuous = true)

        var tonePlayStarted = false
        var tonePlayCancelled = false
        var toneCleanupFinished = false
        var sessionCompletedWhileToneActive = false

        val slowOutput = object : PcmOutput {
            override suspend fun playReadyBeep(coldStart: Boolean) {}
            override suspend fun playErrorBeep(coldStart: Boolean) {}
            override suspend fun play(recording: RecordedPcm) {}
            override suspend fun playCaptureFeedback(tone: CaptureFeedbackTone) {
                tonePlayStarted = true
                try {
                    delay(10_000L)
                } catch (e: CancellationException) {
                    tonePlayCancelled = true
                    withContext(NonCancellable) {
                        delay(1)
                        toneCleanupFinished = true
                    }
                    throw e
                }
            }
        }

        val startResult = service.startSession(source, FakeScoRoute(), slowOutput, 100L) { true }
        val session = (startResult as CaptureStartResult.Started).session

        val emitJob = launch {
            session.semanticFeedbackEmitter.emit(CaptureFeedbackTone.RecordingLimitWarning)
        }
        runCurrent()
        assertTrue(tonePlayStarted)

        var sessionCompleted = false
        launch {
            session.completion.await()
            sessionCompleted = true
            if (tonePlayStarted && !toneCleanupFinished) {
                sessionCompletedWhileToneActive = true
            }
        }

        advanceTimeBy(120L)
        runCurrent()

        assertTrue(tonePlayCancelled)
        assertTrue(toneCleanupFinished)
        assertTrue(sessionCompleted)
        assertFalse("Session completed before tone cleanup was finished!", sessionCompletedWhileToneActive)

        emitJob.join()
    }

    @Test
    fun alreadyFinalizedStopCannotLeakPlayback() = runTest {
        val service = captureService()
        val source = FakeSource(continuous = true)

        var tonePlayStarted = false
        var tonePlayCancelled = false
        var toneCleanupFinished = false
        var stopCompleted = false

        val slowOutput = object : PcmOutput {
            override suspend fun playReadyBeep(coldStart: Boolean) {}
            override suspend fun playErrorBeep(coldStart: Boolean) {}
            override suspend fun play(recording: RecordedPcm) {}
            override suspend fun playCaptureFeedback(tone: CaptureFeedbackTone) {
                tonePlayStarted = true
                try {
                    delay(10_000L)
                } catch (e: CancellationException) {
                    tonePlayCancelled = true
                    withContext(NonCancellable) {
                        delay(5_000L)
                        toneCleanupFinished = true
                    }
                    throw e
                }
            }
        }

        val startResult = service.startSession(source, FakeScoRoute(), slowOutput, 100L) { true }
        val session = (startResult as CaptureStartResult.Started).session

        val emitJob = launch {
            session.semanticFeedbackEmitter.emit(CaptureFeedbackTone.RecordingLimitWarning)
        }
        runCurrent()
        assertTrue(tonePlayStarted)

        val stopProbe = launch {
            while (!tonePlayCancelled) {
                delay(1)
            }
            val stopJob = launch {
                session.stop()
                stopCompleted = true
            }
            runCurrent()

            assertFalse(stopCompleted)
            assertFalse(toneCleanupFinished)

            advanceTimeBy(5_000L)
            runCurrent()

            assertTrue(toneCleanupFinished)
            assertTrue(stopCompleted)
            stopJob.join()
        }

        advanceTimeBy(120L)
        runCurrent()

        stopProbe.join()
        emitJob.join()
    }

    // -- helpers -------------------------------------------------------------

    private suspend fun startedSession(
        service: CaptureService,
        source: FakeSource,
        maxDurationMs: Long = 60_000L,
    ): CaptureSession {
        val result = service.startSession(source, FakeScoRoute(), FakeOutput(onReadyBeep = source::markBeepComplete), maxDurationMs) { true }
        return (result as CaptureStartResult.Started).session
    }

    private fun testDispatcher(scope: TestScope): CoroutineDispatcher =
        scope.coroutineContext[ContinuationInterceptor] as CoroutineDispatcher

    private fun TestScope.captureService(
        clock: () -> Long = { testScheduler.currentTime },
    ): CaptureService = CaptureService(
        scope = this,
        readDispatcher = testDispatcher(this),
        clock = clock,
    )

    private enum class BoundaryRead {
        NonBlockingDrain,
        Committed,
    }

    private class BeepBoundarySource(
        private val preBeepChunks: List<ShortArray>,
        private val postBeepChunks: List<ShortArray> = emptyList(),
        private val requiresPreCommitSignal: Boolean = false,
    ) : CaptureSource {
        override val sourceId: CaptureSourceId = CaptureSourceId.VoiceCommunication

        private var opened: BoundaryOpened? = null
        private val reads = mutableListOf<BoundaryRead>()

        override suspend fun open(): OpenedCaptureSource = BoundaryOpened().also { opened = it }

        val readHistory: List<BoundaryRead>
            get() = reads.toList()

        @Volatile var preBeepChunksRead: Int = 0
            private set
        @Volatile var committedReadCount: Int = 0
            private set
        @Volatile var closeCount: Int = 0
            private set

        fun markBeepComplete() {
            opened?.beepComplete = true
        }

        fun allowPostBeepReads() {
            opened?.postBeepReadsEnabled = true
        }

        private inner class BoundaryOpened : OpenedCaptureSource {
            override val sampleRate: Int = DEFAULT_RATE
            override val requiresPreCommitSignal: Boolean = this@BeepBoundarySource.requiresPreCommitSignal
            override val bufferSizeShorts: Int = DEFAULT_BUFFER_SHORTS
            override val startupEvidence: CaptureStartupEvidence = CaptureStartupEvidence()
            private val preQueue = ArrayDeque<ShortArray>().apply { preBeepChunks.forEach(::addLast) }
            private val postQueue = ArrayDeque<ShortArray>().apply { postBeepChunks.forEach(::addLast) }
            @Volatile var beepComplete: Boolean = false
            @Volatile var postBeepReadsEnabled: Boolean = false
            @Volatile private var closed: Boolean = false

            override fun readNonBlocking(buffer: ShortArray): Int {
                if (closed) return -1
                check(!beepComplete) { "pre-commit drain read after beep completion" }
                reads += BoundaryRead.NonBlockingDrain
                val next = preQueue.removeFirstOrNull() ?: return 0
                preBeepChunksRead += 1
                return copyInto(buffer, next)
            }

            override fun read(buffer: ShortArray): Int {
                if (closed) return -1
                check(beepComplete) { "committed read before beep completion" }
                reads += BoundaryRead.Committed
                committedReadCount += 1
                if (!postBeepReadsEnabled) return 0
                val next = postQueue.removeFirstOrNull() ?: return 0
                return copyInto(buffer, next)
            }

            private fun copyInto(buffer: ShortArray, source: ShortArray): Int {
                val n = minOf(buffer.size, source.size)
                source.copyInto(buffer, 0, 0, n)
                return n
            }

            override fun close() {
                closeCount += 1
                closed = true
            }
        }
    }

    private data class ReopenAttempt(
        val preCommitChunks: List<ShortArray>,
        val committedChunks: List<ShortArray> = emptyList(),
    )

    private class ReopeningSource(
        private val attempts: List<ReopenAttempt>,
    ) : CaptureSource {
        override val sourceId: CaptureSourceId = CaptureSourceId.VoiceCommunication

        private val lifecycle = mutableListOf<String>()
        private val opened = mutableListOf<ReopeningOpened>()
        private var current: ReopeningOpened? = null

        val firstClose = CompletableDeferred<Unit>()
        val lifecycleHistory: List<String>
            get() = lifecycle.toList()
        val closeCounts: List<Int>
            get() = opened.map { it.closeCount }

        override suspend fun open(): OpenedCaptureSource? {
            val attemptIndex = opened.size
            val attempt = attempts.getOrNull(attemptIndex) ?: return null
            lifecycle += "open-${attemptIndex + 1}"
            return ReopeningOpened(attemptIndex, attempt).also {
                opened += it
                current = it
            }
        }

        fun markBeepComplete() {
            current?.beepComplete = true
        }

        private inner class ReopeningOpened(
            private val attemptIndex: Int,
            attempt: ReopenAttempt,
        ) : OpenedCaptureSource {
            override val sampleRate: Int = DEFAULT_RATE
            override val requiresPreCommitSignal: Boolean = true
            override val preCommitSignalAttempts: Int = 2
            override val bufferSizeShorts: Int = DEFAULT_BUFFER_SHORTS
            override val startupEvidence: CaptureStartupEvidence = CaptureStartupEvidence()

            private val preCommitQueue = ArrayDeque<ShortArray>().apply {
                attempt.preCommitChunks.forEach(::addLast)
            }
            private val committedQueue = ArrayDeque<ShortArray>().apply {
                attempt.committedChunks.forEach(::addLast)
            }
            @Volatile var beepComplete: Boolean = false
            @Volatile private var closed: Boolean = false
            var closeCount: Int = 0
                private set

            override fun readNonBlocking(buffer: ShortArray): Int {
                if (closed) return -1
                val next = preCommitQueue.removeFirstOrNull() ?: return 0
                return copyInto(buffer, next)
            }

            override fun read(buffer: ShortArray): Int {
                if (closed) return -1
                check(beepComplete) { "committed read before ready beep completed" }
                val next = committedQueue.removeFirstOrNull() ?: return 0
                return copyInto(buffer, next)
            }

            private fun copyInto(buffer: ShortArray, source: ShortArray): Int {
                val count = minOf(buffer.size, source.size)
                source.copyInto(buffer, endIndex = count)
                return count
            }

            override fun close() {
                closeCount += 1
                closed = true
                lifecycle += "close-${attemptIndex + 1}"
                if (attemptIndex == 0) firstClose.complete(Unit)
            }
        }
    }

    private class GatedReadyBeepOutput(
        private val onComplete: () -> Unit = {},
        private val failure: Throwable? = null,
    ) : PcmOutput {
        val readyBeepStarted = CompletableDeferred<Unit>()
        private val readyBeepGate = CompletableDeferred<Unit>()
        var readyBeepCount: Int = 0
            private set

        override suspend fun playReadyBeep(coldStart: Boolean) {
            readyBeepCount += 1
            readyBeepStarted.complete(Unit)
            readyBeepGate.await()
            onComplete()
            failure?.let { throw it }
        }

        fun completeReadyBeep() {
            readyBeepGate.complete(Unit)
        }

        override suspend fun playErrorBeep(coldStart: Boolean) = Unit

        override suspend fun play(recording: RecordedPcm) = Unit

        override suspend fun playCaptureFeedback(tone: CaptureFeedbackTone) = Unit
    }

    private class FakeSource(
        override val sourceId: CaptureSourceId = CaptureSourceId.VoiceCommunication,
        private val sampleRate: Int = DEFAULT_RATE,
        private val scriptedChunks: List<ShortArray> = emptyList(),
        private val continuous: Boolean = false,
        private val openShouldFail: Boolean = false,
        private val startupEvidence: CaptureStartupEvidence = CaptureStartupEvidence(),
    ) : CaptureSource {
        var openCount: Int = 0; private set
        var openedSourceId: CaptureSourceId? = null; private set
        val scriptedChunkCount: Int get() = scriptedChunks.size
        var lastOpened: Opened? = null
            private set

        override suspend fun open(): OpenedCaptureSource? {
            openCount += 1
            if (openShouldFail) return null
            openedSourceId = sourceId
            return Opened(
                sampleRate = sampleRate,
                bufferSizeShorts = scriptedChunks.firstOrNull()?.size ?: DEFAULT_BUFFER_SHORTS,
                scripted = scriptedChunks,
                continuousAfter = continuous,
                startupEvidence = startupEvidence,
            ).also { lastOpened = it }
        }

        fun markBeepComplete() {
            lastOpened?.beepComplete = true
        }
    }

    private class Opened(
        override val sampleRate: Int,
        override val bufferSizeShorts: Int,
        scripted: List<ShortArray>,
        private val continuousAfter: Boolean,
        override val startupEvidence: CaptureStartupEvidence,
    ) : OpenedCaptureSource {
        private val queue = ArrayDeque<ShortArray>().apply { scripted.forEach { addLast(it) } }
        @Volatile var beepComplete: Boolean = false
        @Volatile private var closed: Boolean = false
        var closeCount: Int = 0
            private set

        override fun readNonBlocking(buffer: ShortArray): Int = 0

        override fun read(buffer: ShortArray): Int {
            if (closed) return -1
            if (!beepComplete && queue.isNotEmpty()) return 0
            val next = queue.removeFirstOrNull()
                ?: if (continuousAfter) ShortArray(buffer.size) { 0 }
                else return 0
            val n = minOf(next.size, buffer.size)
            next.copyInto(buffer, 0, 0, n)
            return n
        }

        override fun close() {
            closeCount += 1
            closed = true
        }
    }

    private class FakeScoRoute(
        private val scoAvailable: Boolean = true,
    ) : ScoRoute {
        private val _state = MutableStateFlow<ScoState>(ScoState.Inactive)
        override val state: StateFlow<ScoState> = _state.asStateFlow()
        override val coldStart: Boolean = false
        var acquireCount: Int = 0; private set
        var releaseCount: Int = 0; private set

        override fun hasAvailableScoDevice(): Boolean = scoAvailable

        override suspend fun acquire(): Boolean {
            acquireCount += 1
            if (!scoAvailable) return false
            _state.value = ScoState.Active
            return true
        }

        override fun isActive(): Boolean = _state.value == ScoState.Active

        override fun release() {
            releaseCount += 1
            _state.value = ScoState.Inactive
        }
    }

    private class FakeOutput(
        private val onReadyBeep: () -> Unit = {},
    ) : PcmOutput {
        var readyBeepCount: Int = 0; private set
        var errorBeepCount: Int = 0; private set
        var playCount: Int = 0; private set
        val playedFeedbackTones = mutableListOf<CaptureFeedbackTone>()

        override suspend fun playReadyBeep(coldStart: Boolean) {
            readyBeepCount += 1
            onReadyBeep()
        }

        override suspend fun playErrorBeep(coldStart: Boolean) {
            errorBeepCount += 1
        }

        override suspend fun play(recording: RecordedPcm) {
            playCount += 1
        }

        override suspend fun playCaptureFeedback(tone: CaptureFeedbackTone) {
            synchronized(playedFeedbackTones) {
                playedFeedbackTones.add(tone)
            }
        }
    }

    private companion object {
        const val DEFAULT_RATE = CaptureService.DEFAULT_SAMPLE_RATE
        const val DEFAULT_BUFFER_SHORTS = 4
        const val SMALL_TICK_MS = 5L
        const val LOOP_TICK_MS = 2L
        const val MAX_DURATION_TEST_MS = 50L
        const val PRE_COMMIT_SIGNAL_TIMEOUT_MS = 500L
        const val PRE_COMMIT_SIGNAL_RETRY_DELAY_MS = 100L
    }
}
