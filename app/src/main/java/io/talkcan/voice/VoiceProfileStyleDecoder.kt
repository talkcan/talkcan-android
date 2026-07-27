package io.talkcan.voice

import io.talkcan.audio.onnx.SupertonicStyleException
import io.talkcan.audio.onnx.SupertonicVoiceStyle
import java.io.File

/** Strict nested Supertonic voice-style decoding with an exact Supertonic-3 profile boundary. */
internal object VoiceProfileStyleDecoder {
    fun decode(jsonText: String): VoiceProfileTensors =
        decodeParsed(SupertonicVoiceStyle.parse(jsonText))

    fun load(file: File): VoiceProfileTensors =
        decodeParsed(SupertonicVoiceStyle.load(file))

    private fun decodeParsed(style: SupertonicVoiceStyle): VoiceProfileTensors {
        requireExactDimensions(
            component = "style_ttl",
            actual = VoiceTensorDimensions(style.ttlDims.dim0, style.ttlDims.dim1, style.ttlDims.dim2),
            expected = Supertonic3VoiceProfileContract.TTL_DIMENSIONS,
        )
        requireExactDimensions(
            component = "style_dp",
            actual = VoiceTensorDimensions(style.dpDims.dim0, style.dpDims.dim1, style.dpDims.dim2),
            expected = Supertonic3VoiceProfileContract.DP_DIMENSIONS,
        )
        return VoiceProfileTensors(
            ttl = VoiceTensor.copyOf(Supertonic3VoiceProfileContract.TTL_DIMENSIONS, style.ttlData),
            dp = VoiceTensor.copyOf(Supertonic3VoiceProfileContract.DP_DIMENSIONS, style.dpData),
        )
    }

    private fun requireExactDimensions(
        component: String,
        actual: VoiceTensorDimensions,
        expected: VoiceTensorDimensions,
    ) {
        if (actual != expected) {
            throw SupertonicStyleException(
                "$component.dims must be exactly ${expected.asList()} for Supertonic-3, found ${actual.asList()}"
            )
        }
    }
}
