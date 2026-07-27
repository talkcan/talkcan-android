package io.talkcan.audio

import android.content.Context
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.talkcan.audio.onnx.SupertonicOnnxSynthesizer
import io.talkcan.model.DEFAULT_TTS_LANG
import io.talkcan.model.DEFAULT_TTS_SPEED
import io.talkcan.model.DEFAULT_TTS_TEXT
import io.talkcan.model.DEFAULT_TTS_TOTAL_STEPS
import io.talkcan.model.DEFAULT_TTS_VOICE_STYLE
import io.talkcan.model.TtsModelStatus
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SupertonicOnnxInstrumentedTest {
    @Test
    fun kotlinOnnxEngineProducesFiniteAudiblePcmWithoutBlockingMainThread() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val synthesizerRef = AtomicReference<SupertonicOnnxSynthesizer>()
        val constructorMs = AtomicLong()

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val startedAt = SystemClock.elapsedRealtime()
            synthesizerRef.set(
                SupertonicOnnxSynthesizer(
                    modelDir = File(context.filesDir, "supertonic-3"),
                ),
            )
            constructorMs.set(SystemClock.elapsedRealtime() - startedAt)
        }

        val synthesizer = requireNotNull(synthesizerRef.get())
        try {
            assertTrue(
                "Supertonic construction blocked main thread for ${constructorMs.get()}ms",
                constructorMs.get() < MAX_MAIN_THREAD_CONSTRUCTION_MS,
            )
            awaitReady(synthesizer)

            val executor = Executors.newSingleThreadExecutor()
            val synthesisStartedAt = SystemClock.elapsedRealtime()
            val outcome = try {
                executor.submit<SynthesisOutcome> {
                    assertNotEquals(Looper.getMainLooper().thread, Thread.currentThread())
                    synthesizer.synthesize(
                        SynthesisRequest(
                            text = DEFAULT_TTS_TEXT,
                            voiceStylePath = File(
                                context.filesDir,
                                "supertonic-3/$DEFAULT_TTS_VOICE_STYLE.json",
                            ).absolutePath,
                            lang = DEFAULT_TTS_LANG,
                            totalSteps = DEFAULT_TTS_TOTAL_STEPS,
                            speed = DEFAULT_TTS_SPEED,
                        ),
                    )
                }.get(SYNTHESIS_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            } finally {
                executor.shutdownNow()
            }
            val synthesisMs = SystemClock.elapsedRealtime() - synthesisStartedAt
            val samples = (outcome as? SynthesisOutcome.Success)?.samples
                ?: throw AssertionError("Kotlin Supertonic synthesis failed: $outcome")
            assertTrue("Supertonic returned no samples", samples.isNotEmpty())
            assertTrue("Supertonic returned non-finite PCM", samples.all(Float::isFinite))

            runBlocking {
                LocalPcmOutput().play(TtsAudio.toScoPlayback(samples, targetRate = SCO_SAMPLE_RATE))
            }
            Log.i(
                LOG_TAG,
                "constructorMs=${constructorMs.get()} synthesisMs=$synthesisMs " +
                    "samples=${samples.size} sampleRate=${TtsAudio.SUPERTONIC_SAMPLE_RATE} " +
                    "playback=completed",
            )
        } finally {
            synthesizer.close()
            synthesizer.close()
        }
    }

    private fun awaitReady(synthesizer: TtsSynthesizer) {
        val deadline = SystemClock.elapsedRealtime() + MODEL_LOAD_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            when (synthesizer.modelStatus) {
                TtsModelStatus.Ready -> return
                TtsModelStatus.Failed -> {
                    throw AssertionError("Kotlin Supertonic model load failed: ${synthesizer.loadError}")
                }
                else -> Thread.sleep(MODEL_STATUS_POLL_MS)
            }
        }
        throw AssertionError("Kotlin Supertonic did not become ready within ${MODEL_LOAD_TIMEOUT_MS}ms")
    }

    private companion object {
        const val LOG_TAG = "TalkcanSupertonicOnnx"
        const val SCO_SAMPLE_RATE = 16_000
        const val MAX_MAIN_THREAD_CONSTRUCTION_MS = 1_000L
        const val MODEL_LOAD_TIMEOUT_MS = 120_000L
        const val SYNTHESIS_TIMEOUT_MS = 120_000L
        const val MODEL_STATUS_POLL_MS = 100L
    }
}
