package io.talkcan.audio

import android.content.Context
import android.speech.tts.TextToSpeech

// ---------------------------------------------------------------------------
// Failure classification
// ---------------------------------------------------------------------------

/**
 * Sealed hierarchy of failure categories for the Android navigation TTS engine.
 *
 * These categories are designed to be sufficient for the bootstrap layer to
 * distinguish [BootstrapSetupFailure] (which maps to `NeedsSetup` — a
 * user-resolvable prerequisite) from [EngineServiceFailure] (which triggers
 * bounded runtime recovery, then `NeedsSetup` if recovery's re-probe fails)
 * and [RendererInfrastructureFailure] (which maps to retryable
 * `BootstrapState.Failed` without engine reinitialization).
 *
 * See design D12 "Failure classification" for the load-bearing classification
 * table mapping each detection point to a concrete consequence.
 */
sealed interface NavigationTtsFailure {

    /**
     * A setup-phase failure detected during bootstrap or recovery re-probe.
     * The bootstrap layer SHALL map this to `NeedsSetup` with an Android TTS
     * settings or voice install action exposed to the user. The engine/voice
     * is not in a usable state and user intervention is required.
     */
    sealed interface BootstrapSetupFailure : NavigationTtsFailure {

        /** No TTS engine is installed, or the default engine package is absent. */
        data object EngineUnavailable : BootstrapSetupFailure

        /**
         * `onInit` returned `ERROR` or a negative status.
         */
        data class EngineInitFailed(val reason: String) : BootstrapSetupFailure

        /**
         * `onInit` timed out without firing. The partially constructed
         * instance has been shut down.
         */
        data object EngineInitTimeout : BootstrapSetupFailure

        /**
         * No valid offline English voice was discovered: `getVoices()`
         * returned null, returned no English voice meeting all four validity
         * criteria, or all English voices were network-dependent /
         * not-installed / missing language data.
         */
        data object VoiceMissing : BootstrapSetupFailure

        /**
         * `setVoice` returned `ERROR` for the selected voice. The instance
         * has been shut down.
         */
        data object VoiceSelectionFailed : BootstrapSetupFailure

        /**
         * The synthesis probe failed: `synthesizeToFile` returned `ERROR`,
         * the callback fired `onError`, the probe output file was empty /
         * undecodable / had zero samples.
         */
        data class VoiceProbeFailed(val reason: String) : BootstrapSetupFailure

        /**
         * The synthesis probe timed out without `onDone` or `onError`.
         * `stop()` has been called on the instance to interrupt the stalled
         * probe.
         */
        data object VoiceProbeTimeout : BootstrapSetupFailure
    }

    /**
     * A runtime engine or service failure (callback `onError`, synthesis
     * timeout, or TTS service loss). This triggers the bounded recovery path
     * (D11): at most one `TextToSpeech` reinitialization + voice probe +
     * retry of the newest pending announcement. If the re-probe fails, the
     * bootstrap layer SHALL transition from `Ready` to `NeedsSetup`. If the
     * re-probe succeeds but the retried synthesis fails, the bootstrap layer
     * SHALL transition to retryable `BootstrapState.Failed`.
     */
    sealed interface EngineServiceFailure : NavigationTtsFailure {

        /**
         * `synthesizeToFile` returned `ERROR` at runtime, or the
         * `UtteranceProgressListener` fired `onError` for a runtime
         * navigation synthesis.
         */
        data class SynthesisError(val reason: String) : EngineServiceFailure

        /**
         * The configured runtime terminal-callback timeout elapsed without
         * `onDone` or `onError` for a runtime navigation synthesis. `stop()`
         * has been called on the instance to interrupt the stalled utterance.
         */
        data object SynthesisTimeout : EngineServiceFailure
    }

    /**
     * A renderer / transient-file / format-infrastructure failure. This does
     * NOT indicate a broken engine or voice — the probed instance is still
     * valid. The bootstrap layer SHALL classify this as retryable
     * `BootstrapState.Failed` (no `NeedsSetup` transition, no engine
     * reinitialization), drop the failed generation, and leave `Ready`.
     */
    sealed interface RendererInfrastructureFailure : NavigationTtsFailure {

        /** Transient file I/O failure (could not create, read, or delete the
         * transient cache file). */
        data class FileIoFailure(val reason: String) : RendererInfrastructureFailure

        /** The WAV file could not be parsed or its header was malformed. */
        data object WavDecodeFailure : RendererInfrastructureFailure

        /** The WAV encoding is not one of the supported formats (PCM 8-bit,
         * PCM 16-bit, or IEEE float). */
        data object UnsupportedEncoding : RendererInfrastructureFailure

        /** The channel count is not 1 or 2. */
        data object UnsupportedChannelCount : RendererInfrastructureFailure

        /** The PCM output is empty after decoding/normalization (file empty or
         * zero samples after `onDone`). */
        data object EmptyPcm : RendererInfrastructureFailure
    }
}

// ---------------------------------------------------------------------------
// TextToSpeech factory seam
// ---------------------------------------------------------------------------

/**
 * Injectable factory seam for constructing Android [TextToSpeech] instances.
 * This enables testing without a real Android context.
 *
 * The factory MUST construct a [TextToSpeech] bound to the installed default
 * engine. The [onInit] callback is wired by the engine internally.
 */
fun interface TextToSpeechFactory {
    /**
     * Create a [TextToSpeech] instance with the given [OnInitListener].
     * The listener's `onInit(status)` will be called by the engine.
     */
    fun create(listener: TextToSpeech.OnInitListener): TextToSpeech
}

/**
 * Default [TextToSpeechFactory] that constructs a [TextToSpeech] bound to the
 * installed default engine using the provided [Context].
 */
class DefaultTextToSpeechFactory(private val context: Context) : TextToSpeechFactory {
    override fun create(listener: TextToSpeech.OnInitListener): TextToSpeech {
        return TextToSpeech(context, listener)
    }
}

// ---------------------------------------------------------------------------
// Engine configuration and result types
// ---------------------------------------------------------------------------

/**
 * Configuration for the [NavigationTtsEngine].
 *
 * @param initTimeoutMs timeout for `TextToSpeech.onInit` to fire.
 * @param probeTimeoutMs timeout for the bootstrap/recovery synthesis probe
 *   callback (`onDone` or `onError`).
 * @param synthesisTimeoutMs timeout for runtime navigation synthesis
 *   callback (`onDone` or `onError`).
 */
data class NavigationTtsConfig(
    val initTimeoutMs: Long = 5_000L,
    val probeTimeoutMs: Long = 10_000L,
    val synthesisTimeoutMs: Long = 15_000L,
)

/**
 * The result of [NavigationTtsEngine.prepare] — the bootstrap prerequisite
 * gate (construct + init + voice-select + probe).
 *
 * On [Success], the probed `TextToSpeech` instance is retained for runtime
 * synthesis. On [Failure], the instance has been shut down and the
 * [failure] carries the specific setup-phase failure category that the
 * bootstrap layer SHALL map to `NeedsSetup`.
 */
sealed interface PrepareResult {
    data class Success(val tts: TextToSpeech) : PrepareResult
    data class Failure(
        val failure: NavigationTtsFailure.BootstrapSetupFailure,
        val enginePackage: String? = null,
    ) : PrepareResult
}

/**
 * The result of a runtime navigation synthesis attempt
 * ([NavigationTtsEngine.synthesize]).
 */
sealed interface NavigationSynthesisResult {

    /**
     * Synthesis succeeded and the PCM was normalized to 16 kHz mono PCM16.
     */
    data class Success(val pcm: RecordedPcm) : NavigationSynthesisResult

    /** The request was replaced by newer non-synthesized navigation feedback. */
    data object Superseded : NavigationSynthesisResult

    /**
     * An engine/service failure occurred. If bounded recovery has not yet
     * been attempted for this failure chain, the engine enters `Recovering`
     * and attempts one reinitialization + re-probe + retry. If recovery
     * fails, [StateLossCallback.onStateLoss] is invoked. If recovery
     * succeeds but the retry fails, this result is returned with
     * [exhausted] = true to signal retryable `BootstrapState.Failed`.
     *
     * @param exhausted true if the bounded recovery's single retry has been
     *   exhausted (same-chain failure); false if this is the first failure
     *   in a new chain (recovery will be attempted).
     */
    data class EngineServiceFailure(
        val failure: NavigationTtsFailure.EngineServiceFailure,
        val exhausted: Boolean,
    ) : NavigationSynthesisResult

    /**
     * A renderer/infrastructure failure. No recovery is attempted. The
     * bootstrap layer SHALL classify this as retryable `BootstrapState.Failed`.
     */
    data class InfrastructureFailure(
        val failure: NavigationTtsFailure.RendererInfrastructureFailure,
    ) : NavigationSynthesisResult
}

/**
 * Callback invoked when the engine detects a state loss that requires the
 * bootstrap layer to transition from `Ready` to `NeedsSetup`.
 *
 * The engine calls this when:
 * - A bounded recovery re-probe fails (missing/unusable engine/voice).
 * - No engine is installed during recovery re-initialization.
 *
 * This is the injectable state-loss seam: the orchestration wrapper (the
 * bootstrap layer) registers this callback to receive state-loss signals
 * without the engine editing service/bootstrap callers directly.
 */
fun interface StateLossCallback {
    /**
     * Called when the engine detects a condition requiring `NeedsSetup`.
     * The [failure] carries the specific setup-phase failure category.
     */
    fun onStateLoss(failure: NavigationTtsFailure.BootstrapSetupFailure, enginePackage: String?)
}
