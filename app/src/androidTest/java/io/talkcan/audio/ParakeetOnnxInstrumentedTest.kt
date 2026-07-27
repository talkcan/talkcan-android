package io.talkcan.audio

import android.content.Context
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.talkcan.audio.onnx.ParakeetOnnxTranscriber
import io.talkcan.model.SttModelStatus
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ParakeetOnnxInstrumentedTest {
    @Test
    fun kotlinOnnxEngineProducesBaselineWithoutBlockingMainThread() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val transcriberRef = AtomicReference<ParakeetOnnxTranscriber>()
        val constructorMs = AtomicLong()

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val startedAt = SystemClock.elapsedRealtime()
            transcriberRef.set(
                ParakeetOnnxTranscriber(
                    modelDir = File(context.filesDir, "parakeet-tdt-0.6b-v3-int8"),
                ),
            )
            constructorMs.set(SystemClock.elapsedRealtime() - startedAt)
        }

        val transcriber = requireNotNull(transcriberRef.get())
        try {
            assertTrue(
                "Parakeet construction blocked main thread for ${constructorMs.get()}ms",
                constructorMs.get() < MAX_MAIN_THREAD_CONSTRUCTION_MS,
            )
            awaitReady(transcriber)

            val fixtureFile = File(context.cacheDir, "kotlin-onnx-parakeet-jfk.wav")
            InstrumentationRegistry.getInstrumentation().context.assets.open("jfk.wav").use { input ->
                fixtureFile.outputStream().use(input::copyTo)
            }
            val decoded = try {
                requireNotNull(WavPcmReader.readNormalized(fixtureFile))
            } finally {
                fixtureFile.delete()
            }
            val samples = SttAudio.resample(decoded.samples, decoded.sampleRate, SAMPLE_RATE)

            val executor = Executors.newSingleThreadExecutor()
            val inferenceStartedAt = SystemClock.elapsedRealtime()
            val transcription = try {
                executor.submit<TranscriptionOutcome> {
                    assertNotEquals(Looper.getMainLooper().thread, Thread.currentThread())
                    transcriber.transcribe(samples)
                }.get(INFERENCE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            } finally {
                executor.shutdownNow()
            }
            val inferenceMs = SystemClock.elapsedRealtime() - inferenceStartedAt
            val transcript = (transcription as? TranscriptionOutcome.Success)?.text
                ?: throw AssertionError("Kotlin Parakeet inference failed: $transcription")
            assertEquals(JFK_TRANSCRIPT, transcript)

            Log.i(
                LOG_TAG,
                "constructorMs=${constructorMs.get()} inferenceMs=$inferenceMs transcript=$transcript",
            )
        } finally {
            transcriber.close()
            transcriber.close()
        }
    }

    private fun awaitReady(transcriber: SttTranscriber) {
        val deadline = SystemClock.elapsedRealtime() + MODEL_LOAD_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            when (transcriber.modelStatus) {
                SttModelStatus.Ready -> return
                SttModelStatus.Failed -> {
                    throw AssertionError("Kotlin Parakeet model load failed: ${transcriber.loadError}")
                }
                else -> Thread.sleep(MODEL_STATUS_POLL_MS)
            }
        }
        throw AssertionError("Kotlin Parakeet did not become ready within ${MODEL_LOAD_TIMEOUT_MS}ms")
    }

    private companion object {
        const val LOG_TAG = "TalkcanParakeetOnnx"
        const val SAMPLE_RATE = 16_000
        const val MAX_MAIN_THREAD_CONSTRUCTION_MS = 1_000L
        const val MODEL_LOAD_TIMEOUT_MS = 120_000L
        const val INFERENCE_TIMEOUT_MS = 120_000L
        const val MODEL_STATUS_POLL_MS = 100L
        const val JFK_TRANSCRIPT =
            "And so, my fellow Americans, ask not what your country can do for you. Ask what you can do for your country."
    }
}
