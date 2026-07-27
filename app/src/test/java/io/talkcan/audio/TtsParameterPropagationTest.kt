package io.talkcan.audio

import org.junit.Assert.assertEquals
import org.junit.Test

class TtsParameterPropagationTest {
    @Test
    fun parametersPropagateFromRequestToSynthesizerCall() {
        val synth = FakeTtsSynthesizer()
        val request = SynthesisRequest(
            text = "Hello world",
            voiceStylePath = "/data/M2.json",
            lang = "ko",
            totalSteps = 16,
            speed = 1.2f,
        )
        synth.synthesize(request)

        assertEquals(1, synth.callCount)
        assertEquals("Hello world", synth.lastRequest?.text)
        assertEquals("/data/M2.json", synth.lastRequest?.voiceStylePath)
        assertEquals("ko", synth.lastRequest?.lang)
        assertEquals(16, synth.lastRequest?.totalSteps)
        assertEquals(1.2f, synth.lastRequest!!.speed, 0.001f)
    }

    @Test
    fun emptyTextReturnsEmptyTextWithoutCallingModel() {
        val synth = FakeTtsSynthesizer()
        val request = SynthesisRequest(
            text = "",
            voiceStylePath = "/data/M1.json",
            lang = "en",
            totalSteps = 8,
            speed = 1.0f,
        )
        val outcome = synth.synthesize(request)
        assertEquals(SynthesisOutcome.EmptyText, outcome)
        assertEquals(0, synth.callCount)
    }

    @Test
    fun modelNotReadyReturnsModelNotReady() {
        val synth = FakeTtsSynthesizer(modelStatus = io.talkcan.model.TtsModelStatus.Loading)
        val request = SynthesisRequest(
            text = "Hello",
            voiceStylePath = "/data/M1.json",
            lang = "en",
            totalSteps = 8,
            speed = 1.0f,
        )
        val outcome = synth.synthesize(request)
        assertEquals(SynthesisOutcome.ModelNotReady, outcome)
    }

    @Test
    fun differentVoiceStylesProduceDifferentRequests() {
        val synth = FakeTtsSynthesizer()
        synth.synthesize(SynthesisRequest("hi", "/data/F1.json", "en", 8, 1.0f))
        assertEquals("/data/F1.json", synth.lastRequest?.voiceStylePath)
        synth.synthesize(SynthesisRequest("hi", "/data/M5.json", "en", 8, 1.0f))
        assertEquals("/data/M5.json", synth.lastRequest?.voiceStylePath)
    }
}
