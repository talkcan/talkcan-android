package io.talkcan.audio

import io.talkcan.model.SttModelStatus

/**
 * Port for on-device speech-to-text transcription of normalized 16 kHz mono
 * `f32` samples.
 *
 * Implementations:
 * - [FakeSttTranscriber] for unit tests;
 * - [io.talkcan.audio.onnx.ParakeetOnnxTranscriber] for Android
 *   runtime, backed by the Kotlin ONNX engine.
 *
 * Implementations MUST NOT perform network I/O or persist captured audio.
 */
interface SttTranscriber : AutoCloseable {
    /** Current model readiness status. Safe to poll from any thread. */
    val modelStatus: SttModelStatus

    /** Most recent model-load failure message, if any. */
    val loadError: String?

    /**
     * Transcribe [samples]. Blocks until the model is ready (or loading fails)
     * and inference completes. Callers MUST invoke this off the main thread.
     */
    fun transcribe(samples: FloatArray): TranscriptionOutcome

    /**
     * Release the engine generation owned by this transcriber. Idempotent:
     * the first call releases, later calls are no-ops. Implementations MUST
     * NOT close the process-shared ONNX Runtime environment and MUST fail
     * subsequent [transcribe] calls through [TranscriptionOutcome.Failure]
     * without entering the model runtime.
     */
    override fun close()
}

sealed interface TranscriptionOutcome {
    data class Success(val text: String) : TranscriptionOutcome
    data object ModelNotReady : TranscriptionOutcome
    data class Failure(val reason: String) : TranscriptionOutcome
    data object EmptyInput : TranscriptionOutcome
}