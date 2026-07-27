package io.talkcan.channel.capability

import android.os.SystemClock
import io.talkcan.service.TalkcanLogger as Log

import io.talkcan.audio.PcmTranscriber
import io.talkcan.audio.RecordedPcm
import io.talkcan.audio.SynthesisOutcome
import io.talkcan.audio.SynthesisRequest
import io.talkcan.audio.TtsSynthesizer
import java.util.UUID
import kotlinx.coroutines.CancellationException

/**
 * Host-owned wrappers for legacy audio values. They are internal, so providers and
 * runtimes can hold only the opaque interfaces declared in this package.
 */
internal class RecordedPcmAudioRecording(
    internal val recording: RecordedPcm,
    override val operationId: String = UUID.randomUUID().toString(),
    internal val generation: RuntimeGeneration,
) : OpaqueAudioRecording {
    @Volatile
    private var disposed: Boolean = false

    override val retainedBytes: Long = recording.samples.size.toLong() * 2L

    override val durationMillis: Long = if (recording.sampleRate > 0) {
        recording.samples.size * 1_000L / recording.sampleRate
    } else {
        0L
    }

    override fun dispose() {
        disposed = true
    }

    internal val isDisposed: Boolean get() = disposed
}

internal class SynthesizedAudioArtifact(
    internal val samples: FloatArray,
    override val operationId: String = UUID.randomUUID().toString(),
    internal val generation: RuntimeGeneration,
) : OpaqueSynthesizedAudio {
    @Volatile
    private var disposed: Boolean = false

    override val retainedBytes: Long = samples.size.toLong() * 4L
    override val durationMillis: Long? = null

    override fun dispose() {
        disposed = true
    }

    internal val isDisposed: Boolean get() = disposed
}

internal class AudioOperationArtifact(
    internal val recording: RecordedPcm,
    override val operationId: String = UUID.randomUUID().toString(),
    internal val generation: RuntimeGeneration,
) : OpaqueAudioOperation {
    @Volatile
    private var disposed: Boolean = false

    internal fun bindGeneration(expected: RuntimeGeneration): Boolean = generation == expected

    internal fun dispose() {
        disposed = true
    }

    internal val isDisposed: Boolean get() = disposed
}

internal fun opaqueAudioRecording(
    recording: RecordedPcm,
    generation: RuntimeGeneration,
): OpaqueAudioRecording = RecordedPcmAudioRecording(recording, generation = generation)

/** Composition-only escape hatch; it is unavailable to provider/runtime contracts. */
internal fun recordedPcmOf(recording: OpaqueAudioRecording): RecordedPcm? =
    (recording as? RecordedPcmAudioRecording)
        ?.takeUnless { it.isDisposed }
        ?.recording

/** Composition-only resolver for deferred host-owned playback. */
internal fun recordedPcmOf(operation: OpaqueAudioOperation): RecordedPcm? =
    (operation as? AudioOperationArtifact)
        ?.takeUnless { it.isDisposed }
        ?.recording

internal fun generationOf(recording: OpaqueAudioRecording): RuntimeGeneration? =
    (recording as? RecordedPcmAudioRecording)?.generation

internal fun generationOf(audio: OpaqueSynthesizedAudio): RuntimeGeneration? =
    (audio as? SynthesizedAudioArtifact)?.generation

internal fun generationOf(operation: OpaqueAudioOperation): RuntimeGeneration? =
    (operation as? AudioOperationArtifact)?.generation

internal fun dispose(operation: OpaqueAudioOperation) {
    (operation as? AudioOperationArtifact)?.dispose()
}

/**
 * Host-owned retained byte cost of a deferred playback operation, measured
 * against the deferred queue's per-instance, per-channel, per-generation, and
 * process-wide byte quotas. Mirrors [RecordedPcmAudioRecording.retainedBytes].
 */
internal fun retainedBytesOf(operation: OpaqueAudioOperation): Long =
    (operation as? AudioOperationArtifact)?.recording?.samples?.size?.toLong()?.let { it * 2L } ?: 0L

/** Bridges the legacy transcriber without admitting raw sample arrays to a runtime port. */

internal class TranscriptionCapabilityAdapter(
    private val transcriber: PcmTranscriber,
    private val identity: CapabilityScopeIdentity,
) : TranscriptionCapability {
    override suspend fun transcribe(recording: OpaqueAudioRecording): CapabilityOperationResult<Transcription> {
        val opaque = recording as? RecordedPcmAudioRecording
            ?: return CapabilityOperationResult.Failed(CapabilityFailureReason.INVALID_REQUEST)
        if (opaque.isDisposed || opaque.generation != identity.runtimeGeneration) {
            return CapabilityOperationResult.Failed(CapabilityFailureReason.INVALID_REQUEST)
        }
        val recorded = opaque.recording
        if (recorded.isEmpty) return CapabilityOperationResult.Failed(CapabilityFailureReason.INVALID_REQUEST)
        val startedAt = SystemClock.elapsedRealtime()
        Log.i(CHANNEL_EFFECT_TAG, "TRANSCRIPTION_START operation=${recording.operationId} samples=${recorded.samples.size} rate=${recorded.sampleRate}")
        return try {
            val text = transcriber.transcribe(recorded.samples, recorded.sampleRate)
            Log.i(
                CHANNEL_EFFECT_TAG,
                "TRANSCRIPTION_SUCCESS operation=${recording.operationId} text_length=${text.length} duration_ms=${SystemClock.elapsedRealtime() - startedAt}",
            )
            CapabilityOperationResult.Success(Transcription(text))
        } catch (_: CancellationException) {
            Log.i(CHANNEL_EFFECT_TAG, "TRANSCRIPTION_CANCELLED operation=${recording.operationId}")
            CapabilityOperationResult.Cancelled
        } catch (error: Exception) {
            Log.i(
                CHANNEL_EFFECT_TAG,
                "TRANSCRIPTION_FAILED operation=${recording.operationId} type=${error::class.simpleName} duration_ms=${SystemClock.elapsedRealtime() - startedAt}",
            )
            CapabilityOperationResult.Failed(CapabilityFailureReason.HOST_FAILURE)
        }
    }
}

/**
 * Host-side resolver for a logical voice identity. It deliberately keeps filesystem
 * paths on the adapter side of the boundary.
 */
internal fun interface SpeechSynthesisParametersResolver {
    fun resolve(voice: SpeechVoice): SpeechSynthesisParameters?
}

internal data class SpeechSynthesisParameters(
    val voiceStylePath: String,
    val totalSteps: Int,
)

internal class SynthesisCapabilityAdapter(
    private val synthesizer: TtsSynthesizer,
    private val parameters: SpeechSynthesisParametersResolver,
    private val identity: CapabilityScopeIdentity,
) : SynthesisCapability {
    override suspend fun synthesize(request: SpeechSynthesisRequest): CapabilityOperationResult<OpaqueSynthesizedAudio> {
        if (request.text.isBlank()) return CapabilityOperationResult.Failed(CapabilityFailureReason.INVALID_REQUEST)
        val resolved = parameters.resolve(request.voice)
            ?: return CapabilityOperationResult.Unavailable(CapabilityUnavailableReason.NOT_CONFIGURED)
        val startedAt = SystemClock.elapsedRealtime()
        Log.i(
            CHANNEL_EFFECT_TAG,
            "SYNTHESIS_START language=${request.languageTag} text_length=${request.text.length} steps=${resolved.totalSteps}",
        )
        return try {
            when (
                val outcome = synthesizer.synthesize(
                    SynthesisRequest(
                        text = request.text,
                        voiceStylePath = resolved.voiceStylePath,
                        lang = request.languageTag,
                        totalSteps = resolved.totalSteps,
                        speed = request.speed,
                    ),
                )
            ) {
                is SynthesisOutcome.Success -> {
                    Log.i(
                        CHANNEL_EFFECT_TAG,
                        "SYNTHESIS_SUCCESS samples=${outcome.samples.size} duration_ms=${SystemClock.elapsedRealtime() - startedAt}",
                    )
                    CapabilityOperationResult.Success(SynthesizedAudioArtifact(outcome.samples, generation = identity.runtimeGeneration))
                }
                SynthesisOutcome.ModelNotReady -> {
                    Log.i(CHANNEL_EFFECT_TAG, "SYNTHESIS_UNAVAILABLE reason=model_not_ready")
                    CapabilityOperationResult.Unavailable(CapabilityUnavailableReason.MODEL_NOT_READY)
                }
                SynthesisOutcome.EmptyText -> {
                    Log.i(CHANNEL_EFFECT_TAG, "SYNTHESIS_FAILED reason=empty_text")
                    CapabilityOperationResult.Failed(CapabilityFailureReason.INVALID_REQUEST)
                }
                is SynthesisOutcome.Failure -> {
                    Log.i(CHANNEL_EFFECT_TAG, "SYNTHESIS_FAILED reason=host_failure")
                    CapabilityOperationResult.Failed(CapabilityFailureReason.HOST_FAILURE)
                }
            }
        } catch (_: CancellationException) {
            Log.i(CHANNEL_EFFECT_TAG, "SYNTHESIS_CANCELLED")
            CapabilityOperationResult.Cancelled
        } catch (error: Exception) {
            Log.i(CHANNEL_EFFECT_TAG, "SYNTHESIS_FAILED type=${error::class.simpleName}")
            CapabilityOperationResult.Failed(CapabilityFailureReason.HOST_FAILURE)
        }
    }
}

/**
 * Host composition supplies the playback bridge. The bridge receives samples only in
 * this internal adapter; runtime contracts receive an [OpaqueAudioOperation] instead.
 */
internal fun interface PlaybackResultFactory {
    suspend fun create(samples: FloatArray, generation: RuntimeGeneration): OpaqueAudioOperation
}
internal fun interface RecordingPlaybackResultFactory {
    suspend fun create(recording: RecordedPcm, generation: RuntimeGeneration): OpaqueAudioOperation
}
internal class AudioOperationCapabilityAdapter(
    private val playbackResults: PlaybackResultFactory,
    private val recordingPlaybackResults: RecordingPlaybackResultFactory? = null,
    private val identity: CapabilityScopeIdentity,
) : AudioOperationCapability {
    override suspend fun createPlaybackResult(audio: OpaqueSynthesizedAudio): CapabilityOperationResult<OpaqueAudioOperation> {
        val synthesized = audio as? SynthesizedAudioArtifact
            ?: return CapabilityOperationResult.Failed(CapabilityFailureReason.INVALID_REQUEST)
        if (synthesized.isDisposed || synthesized.generation != identity.runtimeGeneration) {
            return CapabilityOperationResult.Failed(CapabilityFailureReason.INVALID_REQUEST)
        }
        return try {
            val operation = playbackResults.create(synthesized.samples, identity.runtimeGeneration)
            if (!bindGeneration(operation, identity.runtimeGeneration)) {
                dispose(operation)
                CapabilityOperationResult.Failed(CapabilityFailureReason.INVALID_REQUEST)
            } else {
                CapabilityOperationResult.Success(operation)
            }
        } catch (_: CancellationException) {
            CapabilityOperationResult.Cancelled
        } catch (_: Exception) {
            CapabilityOperationResult.Failed(CapabilityFailureReason.HOST_FAILURE)
        }
    }

    override suspend fun createPlaybackResult(recording: OpaqueAudioRecording): CapabilityOperationResult<OpaqueAudioOperation> {
        val factory = recordingPlaybackResults
            ?: return CapabilityOperationResult.Failed(CapabilityFailureReason.INVALID_REQUEST)
        val opaque = recording as? RecordedPcmAudioRecording
            ?: return CapabilityOperationResult.Failed(CapabilityFailureReason.INVALID_REQUEST)
        if (opaque.isDisposed || opaque.generation != identity.runtimeGeneration) {
            return CapabilityOperationResult.Failed(CapabilityFailureReason.INVALID_REQUEST)
        }
        return try {
            val operation = factory.create(opaque.recording, identity.runtimeGeneration)
            if (!bindGeneration(operation, identity.runtimeGeneration)) {
                dispose(operation)
                CapabilityOperationResult.Failed(CapabilityFailureReason.INVALID_REQUEST)
            } else {
                CapabilityOperationResult.Success(operation)
            }
        } catch (_: CancellationException) {
            CapabilityOperationResult.Cancelled
        } catch (_: Exception) {
            CapabilityOperationResult.Failed(CapabilityFailureReason.HOST_FAILURE)
        }
    }
}

private fun bindGeneration(operation: OpaqueAudioOperation, generation: RuntimeGeneration): Boolean =
    generationOf(operation) == generation


private const val CHANNEL_EFFECT_TAG = "TalkcanChannel"
