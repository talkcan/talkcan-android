package io.talkcan.audio

import io.talkcan.model.TtsModelStatus

/**
 * Unit-test fake synthesizer. Records the last request and returns a
 * configurable outcome. Default behavior: model is ready, synthesize returns a
 * short nonzero test tone proportional to the requested text length.
 */
class FakeTtsSynthesizer(
    override var modelStatus: TtsModelStatus = TtsModelStatus.Ready,
    override var loadError: String? = null,
    private var outcomeFactory: ((SynthesisRequest) -> SynthesisOutcome)? = null,
) : TtsSynthesizer {
    var lastRequest: SynthesisRequest? = null
        private set
    var callCount: Int = 0
        private set
    var closeCount: Int = 0
        private set
    /** Optional hook invoked on every [close], for close-order assertions. */
    var onClose: (() -> Unit)? = null

    fun setOutcome(outcome: SynthesisOutcome) {
        outcomeFactory = { outcome }
    }

    fun setOutcomeFactory(factory: (SynthesisRequest) -> SynthesisOutcome) {
        outcomeFactory = factory
    }

    override fun synthesize(request: SynthesisRequest): SynthesisOutcome {
        if (request.text.isBlank()) return SynthesisOutcome.EmptyText
        callCount += 1
        lastRequest = request.copy()
        return when (modelStatus) {
            TtsModelStatus.Ready -> outcomeFactory?.invoke(request)
                ?: SynthesisOutcome.Success(FloatArray(request.text.length.coerceAtLeast(1) * 10) { 0.5f })
            TtsModelStatus.Failed -> SynthesisOutcome.Failure(loadError ?: "model failed")
            else -> SynthesisOutcome.ModelNotReady
        }
    }

    override fun close() {
        closeCount += 1
        onClose?.invoke()
    }
}
