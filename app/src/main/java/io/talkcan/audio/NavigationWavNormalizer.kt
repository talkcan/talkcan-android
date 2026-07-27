package io.talkcan.audio

import java.io.File

/**
 * Map a [WavDecodeResult] failure to the corresponding
 * [NavigationTtsFailure.RendererInfrastructureFailure] category.
 *
 * This bridges WavNormalization's [WavDecodeResult] sealed hierarchy (from
 * [WavPcmReader.readNormalizedResult]) into this engine's failure
 * classification, keeping the distinction between unsupported encoding,
 * unsupported channel count, empty PCM, and malformed header — which is
 * load-bearing for the infrastructure-vs-setup classification (D12).
 */
internal fun WavDecodeResult.toInfrastructureFailure():
    NavigationTtsFailure.RendererInfrastructureFailure = when (this) {
        is WavDecodeResult.Success -> error("Success is not a failure")
        is WavDecodeResult.UnsupportedEncoding ->
            NavigationTtsFailure.RendererInfrastructureFailure.UnsupportedEncoding
        is WavDecodeResult.UnsupportedChannelCount ->
            NavigationTtsFailure.RendererInfrastructureFailure.UnsupportedChannelCount
        WavDecodeResult.EmptyPcm ->
            NavigationTtsFailure.RendererInfrastructureFailure.EmptyPcm
        WavDecodeResult.MalformedHeader ->
            NavigationTtsFailure.RendererInfrastructureFailure.WavDecodeFailure
    }

// ---------------------------------------------------------------------------
// WAV normalization: decode + resample + PCM16 conversion
// ---------------------------------------------------------------------------

/**
 * Result of decoding and normalizing a transient WAV file to 16 kHz mono
 * PCM16 [RecordedPcm].
 *
 * Uses [WavPcmReader.readNormalizedResult] to decode the WAV (supporting PCM8,
 * PCM16, and IEEE float with stereo downmix) and [TtsAudio.toNavigationPcm]
 * to resample to 16 kHz and convert to PCM16. Typed failures from
 * [WavDecodeResult] are mapped to [NavigationTtsFailure.RendererInfrastructureFailure]
 * for the engine's failure classification.
 */
internal sealed interface NormalizeResult {
    data class Success(val pcm: RecordedPcm) : NormalizeResult
    data class Failure(val failure: NavigationTtsFailure.RendererInfrastructureFailure) : NormalizeResult
}

/**
 * Decode and normalize a transient WAV file to 16 kHz mono PCM16.
 *
 * Pipeline (D5):
 * 1. [WavPcmReader.readNormalizedResult] decodes the WAV header + data to a
 *    mono normalized `FloatArray` in [-1.0, 1.0] (PCM8 unsigned centered,
 *    PCM16 signed divided by 32768, IEEE float clamped; stereo downmixed by
 *    averaging). Returns typed failures for unsupported encoding, unsupported
 *    channel count, empty PCM, or malformed header.
 * 2. [TtsAudio.toNavigationPcm] resamples the `FloatArray` from the WAV's
 *    native sample rate to 16 kHz using linear interpolation, then converts
 *    to PCM16 via `f32ToPcm16`. Returns null if the result is empty
 *    (empty-PCM rejection).
 *
 * @return [NormalizeResult.Success] with the normalized [RecordedPcm], or
 *   [NormalizeResult.Failure] with the specific infrastructure failure category.
 */
internal fun normalizeWavToScoPcm(file: File): NormalizeResult {
    val decodeResult = WavPcmReader.readNormalizedResult(file)
    if (decodeResult !is WavDecodeResult.Success) {
        return NormalizeResult.Failure(decodeResult.toInfrastructureFailure())
    }

    val decoded = decodeResult.decoded
    val pcm = TtsAudio.toNavigationPcm(decoded.samples, decoded.sampleRate)
        ?: return NormalizeResult.Failure(
            NavigationTtsFailure.RendererInfrastructureFailure.EmptyPcm,
        )

    return NormalizeResult.Success(pcm)
}
