package io.talkcan.audio

import io.talkcan.model.TtsModelStatus

/**
 * Port for on-device text-to-speech synthesis.
 *
 * Implementations:
 * - [FakeTtsSynthesizer] for unit tests;
 * - [io.talkcan.audio.onnx.SupertonicOnnxSynthesizer] for Android
 *   runtime, backed by the Kotlin ONNX engine.
 *
 * Implementations MUST NOT perform network I/O or persist text or synthesized
 * audio.
 */
interface TtsSynthesizer : AutoCloseable {
    /** Current model readiness status. Safe to poll from any thread. */
    val modelStatus: TtsModelStatus

    /** Most recent model-load failure message, if any. */
    val loadError: String?

    /**
     * Synthesize speech from [request]. Blocks until the model is ready (or
     * loading fails) and synthesis completes. Callers MUST invoke this off
     * the main thread.
     */
    fun synthesize(request: SynthesisRequest): SynthesisOutcome

    /**
     * Release the engine generation owned by this synthesizer. Idempotent:
     * the first call releases, later calls are no-ops. Implementations MUST
     * NOT close the process-shared ONNX Runtime environment and MUST fail
     * subsequent [synthesize] calls through [SynthesisOutcome.Failure]
     * without entering the model runtime.
     */
    override fun close()
}

/**
 * Parameters for a synthesis call.
 *
 * [voiceStylePath] is the filesystem path to the voice style JSON (e.g.
 * `M1.json`). [totalSteps] is the number of denoising steps (quality).
 * [speed] is the speech speed factor (higher = faster).
 */
data class SynthesisRequest(
    val text: String,
    val voiceStylePath: String,
    val lang: String,
    val totalSteps: Int,
    val speed: Float,
)

sealed interface SynthesisOutcome {
    data class Success(val samples: FloatArray) : SynthesisOutcome {
        override fun equals(other: Any?): Boolean =
            this === other || (other is Success && samples.contentEquals(other.samples))
        override fun hashCode(): Int = samples.contentHashCode()
    }
    data object ModelNotReady : SynthesisOutcome
    data class Failure(val reason: String) : SynthesisOutcome
    data object EmptyText : SynthesisOutcome
}