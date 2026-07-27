package io.talkcan.audio

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

interface PcmTranscriber {
    suspend fun transcribe(pcm: ShortArray, sampleRate: Int): String
}

class TranscriptionService(
    private val transcriber: SttTranscriber,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : PcmTranscriber {
    override suspend fun transcribe(pcm: ShortArray, sampleRate: Int): String = withContext(dispatcher) {
        val samples = SttAudio.toParakeetInput(RecordedPcm(pcm, sampleRate))
        when (val outcome = transcriber.transcribe(samples)) {
            is TranscriptionOutcome.Success -> outcome.text
            is TranscriptionOutcome.Failure -> throw TranscriptionException.Failed(outcome.reason)
            TranscriptionOutcome.ModelNotReady -> throw TranscriptionException.ModelNotReady
            TranscriptionOutcome.EmptyInput -> throw TranscriptionException.EmptyInput
        }
    }
}

sealed class TranscriptionException(message: String) : Exception(message) {
    data class Failed(val reason: String) : TranscriptionException(reason)
    data object ModelNotReady : TranscriptionException("STT model not ready")
    data object EmptyInput : TranscriptionException("Empty audio")
}
