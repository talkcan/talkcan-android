package io.talkcan.audio

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.talkcan.model.DEFAULT_TTS_LANG
import io.talkcan.model.DEFAULT_TTS_SPEED
import io.talkcan.model.DEFAULT_TTS_TEXT
import io.talkcan.model.DEFAULT_TTS_TOTAL_STEPS
import io.talkcan.model.DEFAULT_TTS_VOICE_STYLE
import io.talkcan.model.SttModelStatus
import io.talkcan.model.TtsModelStatus
import io.talkcan.service.PttForegroundService
import io.talkcan.service.DefaultSttFactory
import io.talkcan.service.DefaultTtsFactory
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SpeechEngineInstrumentedTest {
    @Test
    fun kotlinOnnxProductionCutoverCoversSpeechChainAndServiceRestart() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val parakeetStartedAt = SystemClock.elapsedRealtime()
        val transcriber = requireNotNull(
            DefaultSttFactory.create(
                File(context.filesDir, "parakeet-tdt-0.6b-v3-int8"),
            ),
        )
        awaitSttReady(transcriber)
        val parakeetLoadMs = SystemClock.elapsedRealtime() - parakeetStartedAt

        val supertonicStartedAt = SystemClock.elapsedRealtime()
        val synthesizer = requireNotNull(
            DefaultTtsFactory.create(
                File(context.filesDir, "supertonic-3"),
            ),
        )
        awaitTtsReady(synthesizer)
        val supertonicLoadMs = SystemClock.elapsedRealtime() - supertonicStartedAt

        val fixtureFile = File(context.cacheDir, "kotlin-onnx-jfk.wav")
        InstrumentationRegistry.getInstrumentation().context.assets.open("jfk.wav").use { input ->
            fixtureFile.outputStream().use(input::copyTo)
        }
        val decoded = try {
            requireNotNull(WavPcmReader.readNormalized(fixtureFile))
        } finally {
            fixtureFile.delete()
        }
        val parakeetInput = SttAudio.resample(decoded.samples, decoded.sampleRate, 16_000)
        val transcriptionStartedAt = SystemClock.elapsedRealtime()
        val transcription = transcriber.transcribe(parakeetInput)
        val transcriptionMs = SystemClock.elapsedRealtime() - transcriptionStartedAt
        val transcript = (transcription as? TranscriptionOutcome.Success)?.text
            ?: throw AssertionError("Parakeet production inference failed: $transcription")
        assertEquals(JFK_TRANSCRIPT, transcript)

        val request = SynthesisRequest(
            text = DEFAULT_TTS_TEXT,
            voiceStylePath = File(
                context.filesDir,
                "supertonic-3/$DEFAULT_TTS_VOICE_STYLE.json",
            ).absolutePath,
            lang = DEFAULT_TTS_LANG,
            totalSteps = DEFAULT_TTS_TOTAL_STEPS,
            speed = DEFAULT_TTS_SPEED,
        )
        val synthesisStartedAt = SystemClock.elapsedRealtime()
        val synthesis = synthesizer.synthesize(request)
        val synthesisMs = SystemClock.elapsedRealtime() - synthesisStartedAt
        val samples = (synthesis as? SynthesisOutcome.Success)?.samples
            ?: throw AssertionError("Supertonic production inference failed: $synthesis")
        assertTrue("Supertonic returned no samples", samples.isNotEmpty())
        assertTrue("Supertonic returned non-finite PCM", samples.all(Float::isFinite))
        val pcmDurationMs = samples.size * 1_000L / TtsAudio.SUPERTONIC_SAMPLE_RATE
        LocalPcmOutput().play(TtsAudio.toScoPlayback(samples, targetRate = 16_000))

        val chainedStartedAt = SystemClock.elapsedRealtime()
        val chained = synthesizer.synthesize(request.copy(text = transcript))
        val chainedMs = SystemClock.elapsedRealtime() - chainedStartedAt
        val chainedSamples = (chained as? SynthesisOutcome.Success)?.samples
            ?: throw AssertionError("STT-to-TTS production chain failed: $chained")
        assertTrue("STT-to-TTS returned no samples", chainedSamples.isNotEmpty())
        assertTrue("STT-to-TTS returned non-finite PCM", chainedSamples.all(Float::isFinite))

        val loadedRssKb = currentRssKb()
        transcriber.close()
        transcriber.close()
        synthesizer.close()
        synthesizer.close()

        val serviceIntent = Intent(context, PttForegroundService::class.java)
            .setAction(PttForegroundService.ACTION_START_MONITORING)
        context.startForegroundService(serviceIntent)
        Thread.sleep(SERVICE_SETTLE_MS)
        context.startActivity(
            Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        Thread.sleep(SERVICE_SETTLE_MS)
        val backgroundServiceRssKb = currentRssKb()
        assertTrue("foreground service was not running after app backgrounding", context.stopService(serviceIntent))
        Thread.sleep(SERVICE_STOP_SETTLE_MS)
        context.startForegroundService(serviceIntent)
        Thread.sleep(SERVICE_SETTLE_MS)
        val restartedRssKb = currentRssKb()
        assertTrue("restarted foreground service was not running", context.stopService(serviceIntent))

        Log.i(
            LOG_TAG,
            "parakeetLoadMs=$parakeetLoadMs transcriptionMs=$transcriptionMs " +
                "transcript=${transcript.replace(' ', '_')} " +
                "supertonicLoadMs=$supertonicLoadMs synthesisMs=$synthesisMs " +
                "pcmDurationMs=$pcmDurationMs samples=${samples.size} " +
                "chainedMs=$chainedMs chainedSamples=${chainedSamples.size} " +
                "loadedRssKb=$loadedRssKb backgroundServiceRssKb=$backgroundServiceRssKb " +
                "restartedRssKb=$restartedRssKb playback=completed",
        )
        Unit
    }

    private fun awaitSttReady(transcriber: SttTranscriber) {
        awaitStatus(
            status = { transcriber.modelStatus },
            ready = SttModelStatus.Ready,
            failed = SttModelStatus.Failed,
            error = { transcriber.loadError },
        )
    }

    private fun awaitTtsReady(synthesizer: TtsSynthesizer) {
        awaitStatus(
            status = { synthesizer.modelStatus },
            ready = TtsModelStatus.Ready,
            failed = TtsModelStatus.Failed,
            error = { synthesizer.loadError },
        )
    }

    private fun <T> awaitStatus(
        status: () -> T,
        ready: T,
        failed: T,
        error: () -> String?,
    ) {
        val deadline = SystemClock.elapsedRealtime() + MODEL_LOAD_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            when (status()) {
                ready -> return
                failed -> throw AssertionError("Model load failed: ${error()}")
            }
            Thread.sleep(MODEL_STATUS_POLL_MS)
        }
        throw AssertionError("Model did not become ready within ${MODEL_LOAD_TIMEOUT_MS}ms")
    }
    private fun currentRssKb(): Long {
        val line = File("/proc/self/status").useLines { lines ->
            lines.firstOrNull { it.startsWith("VmRSS:") }
        } ?: throw AssertionError("VmRSS missing from /proc/self/status")
        return line.split(Regex("\\s+"))[1].toLong()
    }


    private companion object {
        const val LOG_TAG = "TalkcanSpeechOnnx"
        const val MODEL_LOAD_TIMEOUT_MS = 120_000L
        const val MODEL_STATUS_POLL_MS = 100L
        const val SERVICE_SETTLE_MS = 3_000L
        const val SERVICE_STOP_SETTLE_MS = 1_000L
        const val JFK_TRANSCRIPT =
            "And so, my fellow Americans, ask not what your country can do for you. Ask what you can do for your country."
    }
}
