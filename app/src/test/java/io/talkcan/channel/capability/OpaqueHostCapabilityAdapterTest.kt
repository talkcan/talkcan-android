package io.talkcan.channel.capability

import android.os.SystemClock
import io.talkcan.audio.PcmTranscriber
import io.talkcan.audio.RecordedPcm
import io.talkcan.audio.SynthesisOutcome
import io.talkcan.audio.SynthesisRequest
import io.talkcan.audio.TtsSynthesizer
import io.talkcan.channel.SleepwalkerTextOutputService
import io.talkcan.lua.actor.ActorCapabilityMediator
import io.talkcan.model.SttModelStatus
import io.talkcan.model.TtsModelStatus
import io.talkcan.service.ServiceChannelCapabilityHost
import io.talkcan.service.TalkcanLogger
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Boundary tests for host-owned semantic audio capabilities. Runtime-facing values remain opaque;
 * generation binding is exercised through the host's private adapter metadata and the existing
 * typed capability outcomes.
 */
class OpaqueHostCapabilityAdapterTest {
    @Before
    fun setUpAndroidBoundaries() {
        mockkStatic(SystemClock::class)
        every { SystemClock.elapsedRealtime() } returns 1_000L
        mockkObject(TalkcanLogger)
        every { TalkcanLogger.i(any(), any()) } returns Unit
    }

    @After
    fun tearDownAndroidBoundaries() {
        unmockkAll()
    }

    @Test
    fun `capture synthesis and playback cross generation scoped leases as typed opaque outcomes`() = runTest {
        val identity = identity("audio", 11)
        val recording = opaqueAudioRecording(
            RecordedPcm(shortArrayOf(1, -2, 3), 16_000),
            identity.runtimeGeneration,
        )
        val transcriber = RecordingPcmTranscriber("captured text")
        val synthesizer = RecordingSynthesizer(floatArrayOf(.25f, -.25f))
        val operationIds = mutableListOf<String>()
        val host = host(
            transcription = { requested -> TranscriptionCapabilityAdapter(transcriber, requested) },
            synthesis = { requested ->
                SynthesisCapabilityAdapter(
                    synthesizer,
                    SpeechSynthesisParametersResolver { SpeechSynthesisParameters("host-private-style", 4) },
                    requested,
                )
            },
            audioOperation = { requested ->
                AudioOperationCapabilityAdapter(
                    playbackResults = PlaybackResultFactory { samples, generation ->
                        assertEquals(2, samples.size)
                        AudioOperationArtifact(
                            RecordedPcm(shortArrayOf(9), 16_000),
                            generation = generation,
                            operationId = "play-${operationIds.size}",
                        ).also { operationIds += it.operationId }
                    },
                    recordingPlaybackResults = RecordingPlaybackResultFactory { captured, generation ->
                        assertEquals(3, captured.samples.size)
                        AudioOperationArtifact(
                            captured,
                            generation = generation,
                            operationId = "capture-play-${operationIds.size}",
                        ).also { operationIds += it.operationId }
                    },
                    identity = requested,
                )
            },
        )
        val scope = scope(identity, host)
        val actor = ActorCapabilityMediator(scope)

        val transcription = actor.useCapability(CapabilityKey.Transcription) { port ->
            port.transcribe(recording)
        }
        assertEquals(
            CapabilityOperationResult.Success(Transcription("captured text")),
            transcription,
        )
        assertTrue(transcriber.requests.single().samples.contentEquals(shortArrayOf(1, -2, 3)))

        val synthesis = actor.useCapability(CapabilityKey.Synthesis) { port ->
            port.synthesize(SpeechSynthesisRequest("hello", "en-US", SpeechVoice("voice")))
        }
        val synthesized = (synthesis as? CapabilityOperationResult.Success)?.value
            ?: throw AssertionError("Expected opaque synthesis success, got $synthesis")
        assertEquals(2 * 4L, synthesized.retainedBytes)
        assertEquals(identity.runtimeGeneration, generationOf(synthesized))

        val playback = actor.useCapability(CapabilityKey.AudioOperation) { port ->
            port.createPlaybackResult(synthesized)
        }
        val operation = (playback as? CapabilityOperationResult.Success)?.value
            ?: throw AssertionError("Expected opaque playback success, got $playback")
        assertEquals("play-0", operation.operationId)
        assertEquals(identity.runtimeGeneration, generationOf(operation))

        val capturedPlayback = actor.useCapability(CapabilityKey.AudioOperation) { port ->
            port.createPlaybackResult(recording)
        }
        assertTrue(capturedPlayback is CapabilityOperationResult.Success)
        assertEquals(2, operationIds.size)
    }

    @Test
    fun `foreign generation artifacts are rejected before host audio effects`() = runTest {
        val first = identity("audio", 21)
        val second = identity("audio", 22)
        var transcribeCalls = 0
        var synthesizeCalls = 0
        var playbackCalls = 0
        val foreignRecording = opaqueAudioRecording(RecordedPcm(shortArrayOf(1), 16_000), first.runtimeGeneration)
        val foreignSynthesis = SynthesizedAudioArtifact(floatArrayOf(.5f), generation = first.runtimeGeneration, operationId = "foreign")
        val host = host(
            transcription = { requested ->
                object : TranscriptionCapability {
                    override suspend fun transcribe(recording: OpaqueAudioRecording): CapabilityOperationResult<Transcription> {
                        transcribeCalls++
                        return TranscriptionCapabilityAdapter(RecordingPcmTranscriber("must-not-run"), requested)
                            .transcribe(recording)
                    }
                }
            },
            synthesis = { requested -> SynthesisCapabilityAdapter(RecordingSynthesizer(floatArrayOf(1f)), SpeechSynthesisParametersResolver { SpeechSynthesisParameters("x", 1) }, requested) },
            audioOperation = { requested ->
                AudioOperationCapabilityAdapter(
                    playbackResults = PlaybackResultFactory { _, generation ->
                        playbackCalls++
                        AudioOperationArtifact(RecordedPcm(shortArrayOf(1), 16_000), generation = generation)
                    },
                    identity = requested,
                )
            },
        )
        val secondScope = scope(second, host)
        val secondActor = ActorCapabilityMediator(secondScope)

        val transcription = secondActor.useCapability(CapabilityKey.Transcription) { port ->
            port.transcribe(foreignRecording)
        }
        assertEquals(CapabilityOperationResult.Failed(CapabilityFailureReason.INVALID_REQUEST), transcription)
        assertEquals(1, transcribeCalls)

        val playback = secondActor.useCapability(CapabilityKey.AudioOperation) { port ->
            port.createPlaybackResult(foreignSynthesis)
        }
        assertEquals(CapabilityOperationResult.Failed(CapabilityFailureReason.INVALID_REQUEST), playback)
        assertEquals(0, playbackCalls)
        assertEquals(0, synthesizeCalls)
    }

    @Test
    fun `revoked generation rejects stale actor operations and cleans lease exactly once`() = runTest {
        val identity = identity("audio", 31)
        var cleanups = 0
        val port = TranscriptionCapabilityAdapter(RecordingPcmTranscriber("late"), identity)
        val host = host(transcription = { port }, cleanup = { cleanups++ })
        val scope = scope(identity, host)
        val actor = ActorCapabilityMediator(scope)
        val lease = (scope.acquire(CapabilityKey.Transcription) as CapabilityAcquisition.Available).lease

        actor.revoke()
        assertTrue(scope.isClosed)
        assertEquals(CapabilityOperationResult.Closed, lease.use { it.transcribe(opaqueAudioRecording(RecordedPcm(shortArrayOf(1), 16_000), identity.runtimeGeneration)) })
        assertEquals(CapabilityReleaseResult.AlreadyTerminated, lease.release())
        assertEquals(1, cleanups)
        assertTrue(actor.isClosed)
        assertEquals(CapabilityOperationResult.Closed, actor.useCapability(CapabilityKey.Transcription) { CapabilityOperationResult.Success(Unit) })
    }

    @Test
    fun `mismatched playback operation is disposed before invalid outcome`() = runTest {
        val identity = identity("audio", 41)
        val foreign = RuntimeGeneration(40)
        val operation = AudioOperationArtifact(
            RecordedPcm(shortArrayOf(1), 16_000),
            generation = foreign,
            operationId = "foreign-operation",
        )
        val adapter = AudioOperationCapabilityAdapter(
            playbackResults = PlaybackResultFactory { _, _ -> operation },
            identity = identity,
        )
        val result = adapter.createPlaybackResult(
            SynthesizedAudioArtifact(floatArrayOf(.1f), generation = identity.runtimeGeneration, operationId = "synthesized"),
        )

        assertEquals(CapabilityOperationResult.Failed(CapabilityFailureReason.INVALID_REQUEST), result)
        assertTrue(operation.isDisposed)
    }

    @Test
    fun `runtime facing opaque values expose no platform or raw audio members`() {
        val forbidden = Regex("(?i)(pcm|sample|device|engine|path|android|route|recording|floatarray|shortarray)")
        val runtimeTypes = listOf(
            OpaqueAudioRecording::class.java,
            OpaqueSynthesizedAudio::class.java,
            OpaqueAudioOperation::class.java,
            Transcription::class.java,
            CapabilityDiagnostic::class.java,
            CapabilityScopeIdentity::class.java,
        )

        runtimeTypes.forEach { type ->
            type.declaredFields.forEach { field ->
                assertFalse("${type.simpleName}.${field.name} leaks host detail", forbidden.containsMatchIn(field.name))
                assertFalse("${type.simpleName}.${field.type.simpleName} leaks host detail", forbidden.containsMatchIn(field.type.simpleName))
            }
            type.declaredMethods.forEach { method ->
                assertFalse("${type.simpleName}.${method.name} leaks host detail", forbidden.containsMatchIn(method.name))
                assertFalse("${type.simpleName}.${method.returnType.simpleName} leaks host detail", forbidden.containsMatchIn(method.returnType.simpleName))
                method.parameterTypes.forEach { parameter ->
                    assertFalse("${type.simpleName} parameter leaks host detail", forbidden.containsMatchIn(parameter.simpleName))
                }
            }
        }

        val failure = CapabilityOperationResult.Failed(CapabilityFailureReason.INVALID_REQUEST)
        assertFalse(forbidden.containsMatchIn(failure.toString()))
        assertFalse(forbidden.containsMatchIn(CapabilityDiagnostic(identity("runtime", 1), ChannelCapability.Synthesis, CapabilityDiagnosticPhase.OPERATION, CapabilityDiagnosticOutcome.FAILED).toString()))
        assertNotNull(OpaqueAudioOperation::class.java.getMethod("getOperationId"))
    }

    private fun scope(identity: CapabilityScopeIdentity, host: ChannelCapabilityHost): RevocableChannelCapabilityScope =
        RevocableChannelCapabilityScope(
            identity = identity,
            declaredCapabilities = setOf(ChannelCapability.Transcription, ChannelCapability.Synthesis, ChannelCapability.AudioOperation),
            host = host,
        )

    private fun host(
        transcription: (CapabilityScopeIdentity) -> TranscriptionCapability? = { null },
        synthesis: (CapabilityScopeIdentity) -> SynthesisCapability? = { null },
        audioOperation: (CapabilityScopeIdentity) -> AudioOperationCapability? = { null },
        cleanup: suspend (CapabilityLeaseTermination) -> Unit = {},
    ): ChannelCapabilityHost {
        val textService = mockk<SleepwalkerTextOutputService>(relaxed = true)
        return ServiceChannelCapabilityHost(
            textOutputService = textService,
            transcription = transcription,
            synthesis = synthesis,
            audioOperation = audioOperation,
        ).let { delegate ->
            object : ChannelCapabilityHost {
                override suspend fun availability(identity: CapabilityScopeIdentity, key: CapabilityKey<*>): CapabilityAvailability = delegate.availability(identity, key)
                override suspend fun <T : ChannelCapabilityPort> acquire(identity: CapabilityScopeIdentity, key: CapabilityKey<T>): HostedCapabilityAcquisition<T> {
                    val result = delegate.acquire(identity, key)
                    return when (result) {
                        is HostedCapabilityAcquisition.Available -> HostedCapabilityAcquisition.Available(result.port, cleanup)
                        else -> result
                    }
                }
                override suspend fun <T : ChannelCapabilityPort> prepareAndAcquire(identity: CapabilityScopeIdentity, key: CapabilityKey<T>, timeoutMillis: Long): HostedCapabilityAcquisition<T> = delegate.prepareAndAcquire(identity, key, timeoutMillis)
            }
        }
    }

    private fun identity(instance: String, generation: Long) = CapabilityScopeIdentity(instance, RuntimeGeneration(generation))


    private class RecordingPcmTranscriber(private val text: String) : PcmTranscriber {
        val requests = mutableListOf<RecordedPcm>()
        override suspend fun transcribe(pcm: ShortArray, sampleRate: Int): String {
            requests += RecordedPcm(pcm, sampleRate)
            return text
        }
    }

    private class RecordingSynthesizer(private val samples: FloatArray) : TtsSynthesizer {
        override val modelStatus: TtsModelStatus = TtsModelStatus.Ready
        override val loadError: String? = null
        override fun synthesize(request: SynthesisRequest): SynthesisOutcome {
            return SynthesisOutcome.Success(samples)
        }
        override fun close() {}
    }

    /**
     * Task 10.5: Mode-dependent availability through audio capability scopes.
     *
     * Simulates Debug's mode dependency: a scope declaring all three audio capabilities,
     * but the host projects different availability per capability (e.g., STT mode where
     * transcription is available but synthesis is not). The scope must correctly project
     * per-capability availability without mode fallback.
     */
    @Test
    fun `mode dependent availability projects per-capability through audio capability scopes`() = runTest {
        val identity = identity("audio", 51)
        val transcriber = RecordingPcmTranscriber("mode text")
        val host = host(
            transcription = { requested -> TranscriptionCapabilityAdapter(transcriber, requested) },
            synthesis = { null }, // Unavailable: synthesis not needed for STT mode
            audioOperation = { null }, // Not needed for this availability test
        )
        val scope = RevocableChannelCapabilityScope(
            identity = identity,
            declaredCapabilities = setOf(
                ChannelCapability.Transcription,
                ChannelCapability.Synthesis,
                ChannelCapability.AudioOperation,
            ),
            host = host,
        )

        // Declared and available: transcription returns State(Available)
        val transcriptionAvail = scope.availability(CapabilityKey.Transcription)
        assertTrue(
            "Transcription must be available when host provides the port",
            transcriptionAvail is CapabilityAvailabilityResult.State &&
                (transcriptionAvail as CapabilityAvailabilityResult.State).availability is CapabilityAvailability.Available,
        )

        // Declared but host-unavailable: synthesis returns State(Unavailable), not Denied/Undeclared
        val synthesisAvail = scope.availability(CapabilityKey.Synthesis)
        assertTrue(
            "Synthesis must be State(Unavailable) when host returns null",
            synthesisAvail is CapabilityAvailabilityResult.State &&
                (synthesisAvail as CapabilityAvailabilityResult.State).availability is CapabilityAvailability.Unavailable,
        )
        assertEquals(
            CapabilityUnavailableReason.HOST_NOT_READY,
            ((synthesisAvail as CapabilityAvailabilityResult.State).availability as CapabilityAvailability.Unavailable).reason,
        )

        // Declared but host-null: audio operation returns State(Unavailable)
        val audioOpAvail = scope.availability(CapabilityKey.AudioOperation)
        assertTrue(
            "AudioOperation must be State(Unavailable) when host returns null",
            audioOpAvail is CapabilityAvailabilityResult.State &&
                (audioOpAvail as CapabilityAvailabilityResult.State).availability is CapabilityAvailability.Unavailable,
        )
        assertEquals(
            CapabilityUnavailableReason.HOST_NOT_READY,
            ((audioOpAvail as CapabilityAvailabilityResult.State).availability as CapabilityAvailability.Unavailable).reason,
        )

        // Verify acquisition matches availability: transcription succeeds
        val transcriptionAcq = scope.acquire(CapabilityKey.Transcription)
        assertTrue(
            "Transcription acquisition must succeed when host provides the port",
            transcriptionAcq is CapabilityAcquisition.Available,
        )

        // Acquisition for unavailable: returns Unavailable (no mode fallback)
        val synthesisAcq = scope.acquire(CapabilityKey.Synthesis)
        assertTrue(
            "Synthesis acquisition must return Unavailable (no mode fallback)",
            synthesisAcq is CapabilityAcquisition.Unavailable,
        )
    }

    /**
     * Task 10.5: Transcription borrows the recording without consuming it.
     *
     * After successful transcription, the same OpaqueAudioRecording must remain
     * usable (not disposed) for a subsequent operation like playback scheduling.
     * This proves the borrow semantic: transcription reads the data but does not
     * consume the handle.
     */
    @Test
    fun `transcription borrows the recording leaving it usable for subsequent playback`() = runTest {
        val identity = identity("audio", 52)
        val transcriber = RecordingPcmTranscriber("borrowed text")
        val playbackResults = mutableListOf<OpaqueAudioOperation>()
        var playbackCalls = 0
        val host = host(
            transcription = { requested -> TranscriptionCapabilityAdapter(transcriber, requested) },
            synthesis = { null },
            audioOperation = { requested ->
                AudioOperationCapabilityAdapter(
                    playbackResults = PlaybackResultFactory { samples, generation ->
                        playbackCalls++
                        AudioOperationArtifact(RecordedPcm(ShortArray(samples.size), 16_000), generation = generation)
                            .also { playbackResults += it }
                    },
                    recordingPlaybackResults = RecordingPlaybackResultFactory { captured, generation ->
                        playbackCalls++
                        AudioOperationArtifact(captured, generation = generation).also { playbackResults += it }
                    },
                    identity = requested,
                )
            },
        )
        val scope = RevocableChannelCapabilityScope(
            identity = identity,
            declaredCapabilities = setOf(
                ChannelCapability.Transcription,
                ChannelCapability.Synthesis,
                ChannelCapability.AudioOperation,
            ),
            host = host,
        )
        val actor = ActorCapabilityMediator(scope)

        // Create a recording for this generation
        val recording = opaqueAudioRecording(
            RecordedPcm(shortArrayOf(1, 2, 3, 4), 16_000),
            identity.runtimeGeneration,
        )

        // Transcription borrows the recording
        val transcriptionResult = actor.useCapability(CapabilityKey.Transcription) { port ->
            port.transcribe(recording)
        }
        assertEquals(
            "Transcription must succeed",
            CapabilityOperationResult.Success(Transcription("borrowed text")),
            transcriptionResult,
        )

        // Verify the recording is NOT disposed after transcription (borrow semantic)
        val internalRecording = recording as? RecordedPcmAudioRecording
            ?: throw AssertionError("Expected RecordedPcmAudioRecording")
        assertFalse(
            "Recording must NOT be disposed after transcription (borrow semantic)",
            internalRecording.isDisposed,
        )

        // Use the same recording for playback - proves it's still usable
        val playbackResult = actor.useCapability(CapabilityKey.AudioOperation) { port ->
            port.createPlaybackResult(recording)
        }
        assertTrue(
            "Playback from the same recording must succeed after transcription",
            playbackResult is CapabilityOperationResult.Success,
        )

        // Verify playback was actually called with the recording's samples
        assertEquals("Playback must be called exactly once", 1, playbackCalls)
        assertEquals(1, playbackResults.size)
    }

}
