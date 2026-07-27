package io.talkcan.audio

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.io.FileOutputStream
import java.nio.file.Files
import java.util.Locale

@OptIn(ExperimentalCoroutinesApi::class)
class NavigationTtsEngineTest {
    private val temporaryDirectories = mutableListOf<File>()

    @After
    fun tearDown() {
        temporaryDirectories.forEach(File::deleteRecursively)
        unmockkAll()
    }

    @Test
    fun offlineEnglishVoiceSelectionFiltersInvalidCandidatesAndUsesStableOrdering() {
        val fastest = voice(name = "en-GB-fast", locale = Locale.UK, latency = 10, quality = 100)
        val sameLatencyHigherQuality = voice(
            name = "en-US-quality",
            locale = Locale.US,
            latency = 20,
            quality = 500,
        )
        val sameQualitySmallerLocale = voice(
            name = "z-name",
            locale = Locale.CANADA,
            latency = 20,
            quality = 500,
        )
        val sameLocaleSmallerName = voice(
            name = "a-name",
            locale = Locale.CANADA,
            latency = 20,
            quality = 500,
        )
        val networkOnly = voice(
            name = "network",
            locale = Locale.US,
            latency = 1,
            quality = 1_000,
            requiresNetwork = true,
        )
        val notInstalled = voice(
            name = "not-installed",
            locale = Locale.US,
            latency = 1,
            quality = 1_000,
            features = setOf(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED),
        )
        val unavailable = voice(
            name = "missing-data",
            locale = Locale.US,
            latency = 1,
            quality = 1_000,
        )
        val nonEnglish = voice(
            name = "fr-fast",
            locale = Locale.FRANCE,
            latency = 0,
            quality = 1_000,
        )
        val tts = ttsDouble(
            voices = setOf(
                sameLatencyHigherQuality,
                sameQualitySmallerLocale,
                sameLocaleSmallerName,
                networkOnly,
                notInstalled,
                unavailable,
                nonEnglish,
                fastest,
            ),
            languageAvailability = mapOf(unavailable.locale to TextToSpeech.LANG_MISSING_DATA),
        )

        val result = selectOfflineEnglishVoice(tts.tts)

        assertEquals(VoiceSelectionResult.Selected(fastest), result)
        assertSame(fastest, tts.selectedVoice)
        val tieBreakerTts = ttsDouble(
            voices = setOf(
                sameLatencyHigherQuality,
                sameQualitySmallerLocale,
                sameLocaleSmallerName,
            ),
        )
        val qualityTts = ttsDouble(
            voices = setOf(
                voice("lower-quality", Locale.US, latency = 20, quality = 100),
                voice("higher-quality", Locale.US, latency = 20, quality = 500),
            ),
        )

        assertEquals(
            VoiceSelectionResult.Selected(sameLocaleSmallerName),
            selectOfflineEnglishVoice(tieBreakerTts.tts),
        )
        assertEquals(
            "higher-quality",
            (selectOfflineEnglishVoice(qualityTts.tts) as VoiceSelectionResult.Selected).voice.name,
        )
    }

    @Test
    fun preparePropagatesFactoryConstructionFailureUnchanged() = runBlocking {
        val factoryFailure = IllegalStateException("factory construction failed")

        val thrown = runCatching {
            engine(TextToSpeechFactory { throw factoryFailure }).prepare()
        }.exceptionOrNull()

        assertSame(factoryFailure, thrown)
    }

    @Test
    fun prepareMapsInitErrorToSetupFailureWithoutQueryingVoiceOrSynthesizing() = runBlocking {
        val tts = ttsDouble(initStatus = TextToSpeech.ERROR)
        val engine = engine(factoryOf(tts))

        val result = engine.prepare()

        assertEquals(
            NavigationTtsFailure.BootstrapSetupFailure.EngineInitFailed(
                "onInit status: ${TextToSpeech.ERROR}",
            ),
            (result as PrepareResult.Failure).failure,
        )
        assertEquals(1, tts.shutdownCount)
        assertTrue(tts.synthesisCalls.isEmpty())
        verify(exactly = 0) { tts.tts.voices }
    }

    @Test
    fun prepareRejectsAbsentDefaultEngineAsEngineUnavailable() = runBlocking {
        val tts = ttsDouble(defaultEngine = null, engines = emptyList())
        val engine = engine(factoryOf(tts))

        val result = engine.prepare()

        assertEquals(
            NavigationTtsFailure.BootstrapSetupFailure.EngineUnavailable,
            (result as PrepareResult.Failure).failure,
        )
        assertEquals(1, tts.shutdownCount)
        assertTrue(tts.synthesisCalls.isEmpty())
    }

    @Test
    fun prepareProbesASelectedOfflineVoiceAndRetainsTheProvenInstance() = runBlocking {
        val tts = ttsDouble()
        tts.onSynthesize = { call ->
            writePcm16Wav(call.file)
            tts.emitDone(call.utteranceId)
            TextToSpeech.SUCCESS
        }
        val cacheDir = newCacheDir()
        val engine = engine(factoryOf(tts), cacheDir)

        val result = engine.prepare()

        assertTrue("prepare result=$result", result is PrepareResult.Success)
        assertSame(tts.tts, (result as PrepareResult.Success).tts)
        assertEquals(listOf("Talkcan"), tts.synthesisCalls.map { it.text })
        assertTrue(tts.synthesisCalls.single().file.parentFile == cacheDir)
        assertFalse(tts.synthesisCalls.single().file.exists())
        assertEquals(0, tts.shutdownCount)
    }

    @Test
    fun prepareMapsImmediateProbeEnqueueErrorToSetupFailureAndCleansTheFile() = runBlocking {
        val tts = ttsDouble().apply {
            onSynthesize = { TextToSpeech.ERROR }
        }
        val engine = engine(factoryOf(tts))

        val result = engine.prepare()

        assertEquals(
            NavigationTtsFailure.BootstrapSetupFailure.VoiceProbeFailed(
                "synthesizeToFile returned ERROR",
            ),
            (result as PrepareResult.Failure).failure,
        )
        assertEquals(1, tts.synthesisCalls.size)
        assertFalse(tts.synthesisCalls.single().file.exists())
        assertEquals(1, tts.shutdownCount)
    }

    @Test
    fun requestSynthesizesOnlyToAUniqueTransientFileThenDeliversNormalizedPcm() = runBlocking {
        val tts = ttsDouble()
        tts.onSynthesize = { call ->
            writePcm16Wav(call.file)
            tts.emitDone(call.utteranceId)
            TextToSpeech.SUCCESS
        }
        val cacheDir = newCacheDir()
        val engine = engine(factoryOf(tts), cacheDir)
        assertTrue(engine.prepare() is PrepareResult.Success)
        val delivered = mutableListOf<RecordedPcm>()

        val result = engine.request("Journal", delivered::add)

        assertTrue(result is NavigationSynthesisResult.Success)
        assertEquals(listOf("Talkcan", "Journal"), tts.synthesisCalls.map { it.text })
        val probe = tts.synthesisCalls[0]
        val navigation = tts.synthesisCalls[1]
        assertTrue(navigation.file.parentFile == cacheDir)
        assertTrue(navigation.wasRegularFileAtEnqueue)
        assertFalse(navigation.file.exists())
        assertTrue(probe.utteranceId != navigation.utteranceId)
        assertEquals(1, delivered.size)
        assertEquals(16_000, delivered.single().sampleRate)
        assertTrue(delivered.single().samples.isNotEmpty())
        verify(exactly = 0) {
            tts.tts.speak(any<CharSequence>(), any<Int>(), any<Bundle>(), any<String>())
        }
    }

    @Test
    fun requestPcmSupersedesActiveSynthesisAndDeliversOnlyItsCallerOwnedPcm() = runBlocking {
        val runtimeQueued = kotlinx.coroutines.CompletableDeferred<SynthesisCall>()
        val tts = ttsDouble().apply {
            onSynthesize = { call ->
                if (call.text == "Talkcan") {
                    writePcm16Wav(call.file)
                    emitDone(call.utteranceId)
                } else {
                    runtimeQueued.complete(call)
                }
                TextToSpeech.SUCCESS
            }
        }
        val engine = engine(factoryOf(tts))
        assertTrue(engine.prepare() is PrepareResult.Success)
        val synthesizedPlayback = mutableListOf<RecordedPcm>()
        val synthesis = async { engine.request("Alpha", synthesizedPlayback::add) }
        val alpha = runtimeQueued.await()
        val bypass = RecordedPcm(shortArrayOf(-1_024, 0, 2_048), 16_000)
        val bypassPlayback = mutableListOf<RecordedPcm>()

        val result = engine.requestPcm(bypass, bypassPlayback::add)

        assertTrue(result is NavigationSynthesisResult.Success)
        assertSame(bypass, (result as NavigationSynthesisResult.Success).pcm)
        assertTrue(synthesis.isCancelled)
        assertFalse(alpha.file.exists())
        assertTrue(synthesizedPlayback.isEmpty())
        assertEquals(1, bypassPlayback.size)
        assertEquals(bypass.samples.toList(), bypassPlayback.single().samples.toList())
        assertEquals(bypass.sampleRate, bypassPlayback.single().sampleRate)
    }

    @Test
    fun supersedingRequestStopsAndCleansPriorOperationBeforeEnqueuingNewestPhrase() = runBlocking {
        val firstRuntimeStarted = kotlinx.coroutines.CompletableDeferred<SynthesisCall>()
        val events = mutableListOf<String>()
        val tts = ttsDouble(onStop = { events += "stop" })
        tts.onSynthesize = { call ->
            events += "enqueue:${call.text}"
            when (call.text) {
                "Talkcan" -> {
                    writePcm16Wav(call.file)
                    tts.emitDone(call.utteranceId)
                }
                "Alpha" -> firstRuntimeStarted.complete(call)
                "Bravo" -> {
                    assertFalse("prior transient file must be cleaned before replacement", firstRuntimeStarted.getCompleted().file.exists())
                    writePcm16Wav(call.file)
                    tts.emitDone(call.utteranceId)
                }
            }
            TextToSpeech.SUCCESS
        }
        val engine = engine(factoryOf(tts))
        assertTrue(engine.prepare() is PrepareResult.Success)
        val delivered = mutableListOf<String>()

        val first = async { engine.request("Alpha") { delivered += "Alpha" } }
        val alpha = firstRuntimeStarted.await()
        val second = engine.request("Bravo") { delivered += "Bravo" }

        assertTrue(second is NavigationSynthesisResult.Success)
        assertTrue(first.isCancelled)
        assertFalse(alpha.file.exists())
        assertEquals(listOf("Bravo"), delivered)
        assertTrue(events.indexOf("stop") < events.indexOf("enqueue:Bravo"))

        // A late terminal callback from the canceled generation is unregistered and cannot replay it.
        tts.emitDone(alpha.utteranceId)
        assertEquals(listOf("Bravo"), delivered)
    }

    @Test
    fun recoveryCoalescesNewerRequestsAndRetriesOnlyTheLatestTextOnce() = runBlocking {
        val recoveryProbeQueued = kotlinx.coroutines.CompletableDeferred<SynthesisCall>()
        val failedAlphaCalls = mutableListOf<SynthesisCall>()
        val initial = ttsDouble()
        val replacement = ttsDouble()
        initial.onSynthesize = { call ->
            when (call.text) {
                "Talkcan" -> {
                    writePcm16Wav(call.file)
                    initial.emitDone(call.utteranceId)
                }
                "Alpha" -> {
                    failedAlphaCalls += call
                    initial.emitError(call.utteranceId)
                }
            }
            TextToSpeech.SUCCESS
        }
        replacement.onSynthesize = { call ->
            when (call.text) {
                "Talkcan" -> recoveryProbeQueued.complete(call)
                "Journal" -> {
                    writePcm16Wav(call.file)
                    replacement.emitDone(call.utteranceId)
                }
            }
            TextToSpeech.SUCCESS
        }
        val engine = engine(factoryOf(initial, replacement))
        val prepared = engine.prepare()
        assertTrue("initial prepare=$prepared", prepared is PrepareResult.Success)
        val delivered = mutableListOf<String>()

        val alpha = async { engine.request("Alpha") { delivered += "Alpha" } }
        val probe = recoveryProbeQueued.await()
        val bravo = async { engine.request("Bravo") { delivered += "Bravo" } }
        val journal = async { engine.request("Journal") { delivered += "Journal" } }
        yield()

        writePcm16Wav(probe.file)
        yield()
        replacement.emitDone(probe.utteranceId)

        assertEquals(NavigationSynthesisResult.Superseded, alpha.await())
        assertTrue(bravo.await() is NavigationSynthesisResult.Success)
        assertTrue(journal.await() is NavigationSynthesisResult.Success)
        assertEquals(listOf("Journal"), delivered)
        assertEquals(listOf("Talkcan", "Alpha"), initial.synthesisCalls.map { it.text })
        assertEquals(listOf("Talkcan", "Journal"), replacement.synthesisCalls.map { it.text })
        assertEquals(1, initial.shutdownCount)

        // The initial instance was shut down during recovery; its late terminal callback
        // must not deliver stale PCM into the recovered engine's playback path.
        initial.emitDone(failedAlphaCalls.single().utteranceId)
        assertEquals(listOf("Journal"), delivered)
    }
    @Test
    fun supersedingAnEnqueuedRecoveryRetryKeepsTheRecoveredEngineUsableForTheNewRequest() = runBlocking {
        val retryQueued = kotlinx.coroutines.CompletableDeferred<SynthesisCall>()
        val stateLosses = mutableListOf<NavigationTtsFailure.BootstrapSetupFailure>()
        val initial = ttsDouble()
        val replacement = ttsDouble()
        initial.onSynthesize = { call ->
            when (call.text) {
                "Talkcan" -> {
                    writePcm16Wav(call.file)
                    initial.emitDone(call.utteranceId)
                }
                "Alpha" -> initial.emitError(call.utteranceId)
            }
            TextToSpeech.SUCCESS
        }
        replacement.onSynthesize = { call ->
            when (call.text) {
                "Talkcan" -> {
                    writePcm16Wav(call.file)
                    replacement.emitDone(call.utteranceId)
                }
                "Alpha" -> retryQueued.complete(call)
                "Journal" -> {
                    writePcm16Wav(call.file)
                    replacement.emitDone(call.utteranceId)
                }
            }
            TextToSpeech.SUCCESS
        }
        val engine = engine(
            factory = factoryOf(initial, replacement),
            stateLossCallback = StateLossCallback { failure, _ -> stateLosses += failure },
        )
        assertTrue(engine.prepare() is PrepareResult.Success)
        val delivered = mutableListOf<String>()

        val alpha = async { engine.request("Alpha") { delivered += "Alpha" } }
        val staleRetry = retryQueued.await()
        val journal = engine.request("Journal") { delivered += "Journal" }

        assertTrue(journal is NavigationSynthesisResult.Success)
        assertTrue(alpha.isCancelled)
        assertFalse(staleRetry.file.exists())
        assertEquals(listOf("Journal"), delivered)
        assertEquals(listOf("Talkcan", "Alpha", "Journal"), replacement.synthesisCalls.map { it.text })
        assertEquals(0, replacement.shutdownCount)
        assertTrue(stateLosses.isEmpty())
    }


    @Test
    fun requestPcmDuringRecoverySuppressesTheRetryButKeepsTheRecoveredEngineUsable() = runBlocking {
        val recoveryProbeQueued = kotlinx.coroutines.CompletableDeferred<SynthesisCall>()
        val initial = ttsDouble()
        val replacement = ttsDouble()
        initial.onSynthesize = { call ->
            when (call.text) {
                "Talkcan" -> {
                    writePcm16Wav(call.file)
                    initial.emitDone(call.utteranceId)
                }
                "Alpha" -> initial.emitError(call.utteranceId)
            }
            TextToSpeech.SUCCESS
        }
        replacement.onSynthesize = { call ->
            when (call.text) {
                "Talkcan" -> recoveryProbeQueued.complete(call)
                "Bravo" -> {
                    writePcm16Wav(call.file)
                    replacement.emitDone(call.utteranceId)
                }
            }
            TextToSpeech.SUCCESS
        }
        val engine = engine(factoryOf(initial, replacement))
        assertTrue(engine.prepare() is PrepareResult.Success)
        val alpha = async { engine.request("Alpha") {} }
        val recoveryProbe = recoveryProbeQueued.await()
        val bypass = RecordedPcm(shortArrayOf(-2_048, 2_048), 16_000)
        val bypassPlayback = mutableListOf<RecordedPcm>()

        val bypassResult = engine.requestPcm(bypass, bypassPlayback::add)
        writePcm16Wav(recoveryProbe.file)
        replacement.emitDone(recoveryProbe.utteranceId)

        assertTrue(bypassResult is NavigationSynthesisResult.Success)
        assertSame(bypass, (bypassResult as NavigationSynthesisResult.Success).pcm)
        assertEquals(1, bypassPlayback.size)
        assertEquals(bypass.samples.toList(), bypassPlayback.single().samples.toList())
        assertEquals(NavigationSynthesisResult.Superseded, alpha.await())
        assertEquals(listOf("Talkcan"), replacement.synthesisCalls.map { it.text })

        val bravo = engine.request("Bravo") {}

        assertTrue(bravo is NavigationSynthesisResult.Success)
        assertEquals(listOf("Talkcan", "Bravo"), replacement.synthesisCalls.map { it.text })
    }

    @Test
    fun shutdownIsAtMostOnceWhenConcurrentTeardownRaces() = runBlocking {
        val tts = ttsDouble()
        tts.onSynthesize = { call ->
            writePcm16Wav(call.file)
            tts.emitDone(call.utteranceId)
            TextToSpeech.SUCCESS
        }
        val engine = engine(factoryOf(tts))
        val prepared = engine.prepare()
        assertTrue("initial prepare=$prepared", prepared is PrepareResult.Success)

        coroutineScope {
            val first = async { engine.shutdown() }
            val second = async { engine.shutdown() }
            first.await()
            second.await()
        }

        assertEquals(1, tts.stopCount)
        assertEquals(1, tts.shutdownCount)
    }

    @Test
    fun prepareTimeoutShutsDownInstanceBeforeAnyVoiceQueryOrSynthesis() = runBlocking {
        val tts = ttsDouble()
        val factory = TextToSpeechFactory { listener -> tts.initListener = listener; tts.tts }
        val result = engine(
            factory = factory,
            config = NavigationTtsConfig(initTimeoutMs = 0, probeTimeoutMs = 5_000, synthesisTimeoutMs = 5_000),
        ).prepare()

        assertEquals(NavigationTtsFailure.BootstrapSetupFailure.EngineInitTimeout, (result as PrepareResult.Failure).failure)
        assertEquals(1, tts.shutdownCount)
        assertTrue(tts.synthesisCalls.isEmpty())
        verify(exactly = 0) { tts.tts.voices }
    }

    @Test
    fun prepareClassifiesNullOrInvalidVoiceSetAndSelectionFailureWithoutProbing() = runBlocking {
        val missing = ttsDouble(voices = null)
        val missingResult = engine(factoryOf(missing)).prepare()
        assertEquals(NavigationTtsFailure.BootstrapSetupFailure.VoiceMissing, (missingResult as PrepareResult.Failure).failure)
        assertEquals(1, missing.shutdownCount)
        assertTrue(missing.synthesisCalls.isEmpty())

        val unavailable = ttsDouble(
            voices = setOf(voice("network-only", Locale.US, 1, 100, requiresNetwork = true)),
        )
        val unavailableResult = engine(factoryOf(unavailable)).prepare()
        assertEquals(NavigationTtsFailure.BootstrapSetupFailure.VoiceMissing, (unavailableResult as PrepareResult.Failure).failure)
        assertTrue(unavailable.synthesisCalls.isEmpty())

        val rejected = ttsDouble(setVoiceReturn = TextToSpeech.ERROR)
        val rejectedResult = engine(factoryOf(rejected)).prepare()
        assertEquals(NavigationTtsFailure.BootstrapSetupFailure.VoiceSelectionFailed, (rejectedResult as PrepareResult.Failure).failure)
        assertEquals(1, rejected.shutdownCount)
        assertTrue(rejected.synthesisCalls.isEmpty())
    }

    @Test
    fun probeCallbackErrorAndTimeoutMapToDistinctSetupFailuresWithTerminalCleanup() = runBlocking {
        val callbackError = ttsDouble()
        callbackError.onSynthesize = { call ->
            callbackError.emitError(call.utteranceId)
            TextToSpeech.SUCCESS
        }
        val callbackErrorResult = engine(factoryOf(callbackError)).prepare()
        assertTrue((callbackErrorResult as PrepareResult.Failure).failure is NavigationTtsFailure.BootstrapSetupFailure.VoiceProbeFailed)
        assertEquals(1, callbackError.shutdownCount)

        val timeout = ttsDouble().apply { onSynthesize = { TextToSpeech.SUCCESS } }
        val timeoutResult = engine(
            factory = factoryOf(timeout),
            config = NavigationTtsConfig(initTimeoutMs = 5_000, probeTimeoutMs = 0, synthesisTimeoutMs = 5_000),
        ).prepare()
        assertEquals(NavigationTtsFailure.BootstrapSetupFailure.VoiceProbeTimeout, (timeoutResult as PrepareResult.Failure).failure)
        assertTrue(timeout.stopCount > 0)
        assertEquals(1, timeout.shutdownCount)
        assertFalse(timeout.synthesisCalls.single().file.exists())
    }

    @Test
    fun onStopRejectsCurrentNavigationWithoutPlaybackOrRecovery() = runBlocking {
        val tts = ttsDouble()
        tts.onSynthesize = { call ->
            if (call.text == "Talkcan") {
                writePcm16Wav(call.file)
                tts.emitDone(call.utteranceId)
            } else {
                tts.emitStop(call.utteranceId)
            }
            TextToSpeech.SUCCESS
        }
        val engine = engine(factoryOf(tts))
        assertTrue(engine.prepare() is PrepareResult.Success)
        val delivered = mutableListOf<RecordedPcm>()

        assertEquals(NavigationSynthesisResult.Superseded, engine.request("Alpha", delivered::add))
        assertTrue(delivered.isEmpty())
        assertEquals(1, tts.synthesisCalls.count { it.text == "Alpha" })
    }

    @Test
    fun laterIndependentEngineFailureGetsOneFreshRecoveryAfterPriorRecoveryRetrySucceeded() = runBlocking {
        val first = ttsDouble()
        val firstRecovery = ttsDouble()
        val secondRecovery = ttsDouble()
        first.onSynthesize = { call ->
            when (call.text) {
                "Talkcan" -> { writePcm16Wav(call.file); first.emitDone(call.utteranceId) }
                "Alpha" -> first.emitError(call.utteranceId)
            }
            TextToSpeech.SUCCESS
        }
        firstRecovery.onSynthesize = { call ->
            when (call.text) {
                "Talkcan", "Alpha" -> { writePcm16Wav(call.file); firstRecovery.emitDone(call.utteranceId) }
                "Bravo" -> firstRecovery.emitError(call.utteranceId)
            }
            TextToSpeech.SUCCESS
        }
        secondRecovery.onSynthesize = { call ->
            writePcm16Wav(call.file)
            secondRecovery.emitDone(call.utteranceId)
            TextToSpeech.SUCCESS
        }
        val stateLosses = mutableListOf<NavigationTtsFailure.BootstrapSetupFailure>()
        val engine = engine(
            factory = factoryOf(first, firstRecovery, secondRecovery),
            stateLossCallback = StateLossCallback { failure, _ -> stateLosses += failure },
        )
        assertTrue(engine.prepare() is PrepareResult.Success)

        assertEquals(NavigationSynthesisResult.Superseded, engine.request("Alpha") {})
        assertEquals(NavigationSynthesisResult.Superseded, engine.request("Bravo") {})
        assertEquals(listOf("Talkcan", "Alpha", "Bravo"), firstRecovery.synthesisCalls.map { it.text })
        assertEquals(listOf("Talkcan", "Bravo"), secondRecovery.synthesisCalls.map { it.text })
        assertTrue(stateLosses.isEmpty())
    }

    @Test
    fun cancellingInitShutsDownAndIgnoresTheLateSuccessCallback() = runBlocking {
        val constructed = kotlinx.coroutines.CompletableDeferred<Unit>()
        val tts = ttsDouble()
        val factory = TextToSpeechFactory { listener ->
            tts.initListener = listener
            constructed.complete(Unit)
            tts.tts
        }
        val engine = engine(factory)
        val prepare = async { engine.prepare() }
        constructed.await()

        prepare.cancel()
        prepare.join()
        tts.initListener.onInit(TextToSpeech.SUCCESS)

        assertTrue(prepare.isCancelled)
        assertEquals(1, tts.shutdownCount)
        verify(exactly = 0) { tts.tts.voices }
        assertTrue(tts.synthesisCalls.isEmpty())
    }

    @Test
    fun cancellingProbeStopsShutsDownAndDeletesThePartialTransientFile() = runBlocking {
        val probeQueued = kotlinx.coroutines.CompletableDeferred<SynthesisCall>()
        val tts = ttsDouble().apply {
            onSynthesize = { call ->
                probeQueued.complete(call)
                TextToSpeech.SUCCESS
            }
        }
        val engine = engine(factoryOf(tts))
        val prepare = async { engine.prepare() }
        val probe = probeQueued.await()

        prepare.cancel()
        prepare.join()

        assertTrue(prepare.isCancelled)
        assertTrue(tts.stopCount > 0)
        assertEquals(1, tts.shutdownCount)
        assertFalse(probe.file.exists())
    }

    @Test
    fun runtimeImmediateErrorTriggersOneRecoveryWhoseMissingVoiceSignalsStateLoss() = runBlocking {
        val initial = ttsDouble()
        val replacement = ttsDouble(voices = null)
        initial.onSynthesize = { call ->
            if (call.text == "Talkcan") {
                writePcm16Wav(call.file)
                initial.emitDone(call.utteranceId)
                TextToSpeech.SUCCESS
            } else {
                TextToSpeech.ERROR
            }
        }
        val stateLosses = mutableListOf<NavigationTtsFailure.BootstrapSetupFailure>()
        val engine = engine(
            factory = factoryOf(initial, replacement),
            stateLossCallback = StateLossCallback { failure, _ -> stateLosses += failure },
        )
        assertTrue(engine.prepare() is PrepareResult.Success)

        val result = engine.request("Alpha") {}

        assertTrue(result is NavigationSynthesisResult.EngineServiceFailure)
        assertTrue((result as NavigationSynthesisResult.EngineServiceFailure).exhausted)
        assertEquals(listOf(NavigationTtsFailure.BootstrapSetupFailure.VoiceMissing), stateLosses)
        assertEquals(1, initial.shutdownCount)
        assertEquals(1, replacement.shutdownCount)
        assertFalse(initial.synthesisCalls.last().file.exists())
    }

    @Test
    fun runtimeSynthesizeToFileIoFailureMapsToExactInfrastructureFailure() = runBlocking {
        val tts = ttsDouble()
        tts.onSynthesize = { call ->
            if (call.text == "Talkcan") {
                writePcm16Wav(call.file)
                tts.emitDone(call.utteranceId)
                TextToSpeech.SUCCESS
            } else {
                throw IOException("cache unavailable")
            }
        }
        val engine = engine(factoryOf(tts))
        assertTrue(engine.prepare() is PrepareResult.Success)

        val result = engine.request("Alpha") {}

        assertEquals(
            NavigationSynthesisResult.InfrastructureFailure(
                NavigationTtsFailure.RendererInfrastructureFailure.FileIoFailure("cache unavailable"),
            ),
            result,
        )
        assertFalse(tts.synthesisCalls.last().file.exists())
    }

    @Test
    fun malformedRuntimeOutputIsInfrastructureFailureWithoutEngineReinitialization() = runBlocking {
        val tts = ttsDouble()
        tts.onSynthesize = { call ->
            if (call.text == "Talkcan") writePcm16Wav(call.file) else call.file.writeText("not a wav")
            tts.emitDone(call.utteranceId)
            TextToSpeech.SUCCESS
        }
        val engine = engine(factoryOf(tts))
        assertTrue(engine.prepare() is PrepareResult.Success)

        val result = engine.request("Alpha") {}

        assertEquals(
            NavigationSynthesisResult.InfrastructureFailure(
                NavigationTtsFailure.RendererInfrastructureFailure.WavDecodeFailure,
            ),
            result,
        )
        assertEquals(0, tts.shutdownCount)
        assertFalse(tts.synthesisCalls.last().file.exists())
    }

    @Test
    fun sameChainRetryOnErrorPropagatesExhaustedEngineFailureWithoutThirdConstruction() = runBlocking {
        val initial = ttsDouble()
        val retry = ttsDouble()
        initial.onSynthesize = { call ->
            if (call.text == "Talkcan") { writePcm16Wav(call.file); initial.emitDone(call.utteranceId) } else initial.emitError(call.utteranceId)
            TextToSpeech.SUCCESS
        }
        retry.onSynthesize = { call ->
            if (call.text == "Talkcan") { writePcm16Wav(call.file); retry.emitDone(call.utteranceId) } else retry.emitError(call.utteranceId)
            TextToSpeech.SUCCESS
        }
        val engine = engine(factoryOf(initial, retry))
        assertTrue(engine.prepare() is PrepareResult.Success)

        val result = engine.request("Alpha") {}

        assertEquals(
            NavigationSynthesisResult.EngineServiceFailure(
                NavigationTtsFailure.EngineServiceFailure.SynthesisError("onError on retry"),
                exhausted = true,
            ),
            result,
        )
        assertEquals(listOf("Talkcan", "Alpha"), retry.synthesisCalls.map { it.text })
    }

    @Test
    fun sameChainRetryTimeoutPropagatesExhaustionWithoutSecondRecovery() = runBlocking {
        val initial = ttsDouble()
        val retry = ttsDouble()
        initial.onSynthesize = { call ->
            if (call.text == "Talkcan") { writePcm16Wav(call.file); initial.emitDone(call.utteranceId) } else initial.emitError(call.utteranceId)
            TextToSpeech.SUCCESS
        }
        retry.onSynthesize = { call ->
            if (call.text == "Talkcan") { writePcm16Wav(call.file); retry.emitDone(call.utteranceId) }
            TextToSpeech.SUCCESS
        }
        val engine = engine(
            factory = factoryOf(initial, retry),
            config = NavigationTtsConfig(initTimeoutMs = 5_000, probeTimeoutMs = 5_000, synthesisTimeoutMs = 0),
        )
        assertTrue(engine.prepare() is PrepareResult.Success)

        assertEquals(
            NavigationSynthesisResult.EngineServiceFailure(
                NavigationTtsFailure.EngineServiceFailure.SynthesisTimeout,
                exhausted = true,
            ),
            engine.request("Alpha") {},
        )
        assertTrue(retry.stopCount > 0)
        assertEquals(listOf("Talkcan", "Alpha"), retry.synthesisCalls.map { it.text })
    }

    @Test
    fun sameChainRetryInfrastructureFailurePropagatesWithoutAnotherReinitialization() = runBlocking {
        val initial = ttsDouble()
        val retry = ttsDouble()
        initial.onSynthesize = { call ->
            if (call.text == "Talkcan") { writePcm16Wav(call.file); initial.emitDone(call.utteranceId) } else initial.emitError(call.utteranceId)
            TextToSpeech.SUCCESS
        }
        retry.onSynthesize = { call ->
            if (call.text == "Talkcan") writePcm16Wav(call.file) else call.file.writeText("corrupt")
            retry.emitDone(call.utteranceId)
            TextToSpeech.SUCCESS
        }
        val engine = engine(factoryOf(initial, retry))
        assertTrue(engine.prepare() is PrepareResult.Success)

        assertEquals(
            NavigationSynthesisResult.InfrastructureFailure(
                NavigationTtsFailure.RendererInfrastructureFailure.WavDecodeFailure,
            ),
            engine.request("Alpha") {},
        )
        assertEquals(listOf("Talkcan", "Alpha"), retry.synthesisCalls.map { it.text })
    }

    @Test
    fun shutdownDuringRuntimeSynthesisStopsDeletesPartialFileAndDeliversNoPcm() = runBlocking {
        val runtimeQueued = kotlinx.coroutines.CompletableDeferred<SynthesisCall>()
        val tts = ttsDouble().apply {
            onSynthesize = { call ->
                if (call.text == "Talkcan") {
                    writePcm16Wav(call.file)
                    emitDone(call.utteranceId)
                } else {
                    runtimeQueued.complete(call)
                }
                TextToSpeech.SUCCESS
            }
        }
        val engine = engine(factoryOf(tts))
        assertTrue(engine.prepare() is PrepareResult.Success)
        val delivered = mutableListOf<RecordedPcm>()
        val request = async { engine.request("Alpha", delivered::add) }
        val operation = runtimeQueued.await()

        engine.shutdown()
        request.join()

        assertTrue(request.isCancelled)
        assertTrue(tts.stopCount > 0)
        assertEquals(1, tts.shutdownCount)
        assertFalse(operation.file.exists())
        assertTrue(delivered.isEmpty())
    }

    @Test
    fun lateTerminalCallbacksAfterShutdownCannotDeliverPcmOrReviveTheEngine() = runBlocking {
        val runtimeQueued = kotlinx.coroutines.CompletableDeferred<SynthesisCall>()
        val tts = ttsDouble().apply {
            onSynthesize = { call ->
                if (call.text == "Talkcan") {
                    writePcm16Wav(call.file)
                    emitDone(call.utteranceId)
                } else {
                    runtimeQueued.complete(call)
                }
                TextToSpeech.SUCCESS
            }
        }
        val engine = engine(factoryOf(tts))
        assertTrue(engine.prepare() is PrepareResult.Success)
        val delivered = mutableListOf<RecordedPcm>()
        val request = async { engine.request("Alpha", delivered::add) }
        val alpha = runtimeQueued.await()

        engine.shutdown()
        request.join()
        tts.emitDone(alpha.utteranceId)
        tts.emitError(alpha.utteranceId)
        tts.emitStop(alpha.utteranceId)

        assertTrue(request.isCancelled)
        assertTrue(delivered.isEmpty())
        assertFalse(alpha.file.exists())
        assertEquals(1, tts.shutdownCount)
    }

    @Test
    fun shutdownDuringRecoveryProbeShutsDownTheCandidateAndDeletesItsProbeFile() = runBlocking {
        val recoveryProbeQueued = kotlinx.coroutines.CompletableDeferred<SynthesisCall>()
        val initial = ttsDouble()
        val candidate = ttsDouble()
        initial.onSynthesize = { call ->
            if (call.text == "Talkcan") { writePcm16Wav(call.file); initial.emitDone(call.utteranceId) } else initial.emitError(call.utteranceId)
            TextToSpeech.SUCCESS
        }
        candidate.onSynthesize = { call ->
            recoveryProbeQueued.complete(call)
            TextToSpeech.SUCCESS
        }
        val engine = engine(factoryOf(initial, candidate))
        assertTrue(engine.prepare() is PrepareResult.Success)
        val request = async { engine.request("Alpha") {} }
        val probe = recoveryProbeQueued.await()

        engine.shutdown()
        request.join()

        assertTrue(request.isCancelled)
        assertEquals(1, candidate.shutdownCount)
        assertFalse(probe.file.exists())
    }

    @Test
    fun emptyAndMalformedProbeOutputsNeverEstablishTheVoiceGate() = runBlocking {
        val cases = listOf<(File) -> Unit>(
            { _ -> },
            { file -> file.writeText("not a wav") },
        )

        cases.forEach { writeOutput ->
            val tts = ttsDouble()
            tts.onSynthesize = { call ->
                writeOutput(call.file)
                tts.emitDone(call.utteranceId)
                TextToSpeech.SUCCESS
            }
            val result = engine(factoryOf(tts)).prepare()

            assertTrue((result as PrepareResult.Failure).failure is NavigationTtsFailure.BootstrapSetupFailure.VoiceProbeFailed)
            assertEquals(1, tts.shutdownCount)
            assertFalse(tts.synthesisCalls.single().file.exists())
        }
    }

    @Test
    fun runtimeTerminalTimeoutStopsDeletesAndBeginsBoundedRecovery() = runBlocking {
        val initial = ttsDouble()
        val replacement = ttsDouble(voices = null)
        initial.onSynthesize = { call ->
            if (call.text == "Talkcan") {
                writePcm16Wav(call.file)
                initial.emitDone(call.utteranceId)
            }
            TextToSpeech.SUCCESS
        }
        val stateLosses = mutableListOf<NavigationTtsFailure.BootstrapSetupFailure>()
        val engine = engine(
            factory = factoryOf(initial, replacement),
            config = NavigationTtsConfig(initTimeoutMs = 5_000, probeTimeoutMs = 5_000, synthesisTimeoutMs = 0),
            stateLossCallback = StateLossCallback { failure, _ -> stateLosses += failure },
        )
        assertTrue(engine.prepare() is PrepareResult.Success)

        val result = engine.request("Alpha") {}

        assertTrue(result is NavigationSynthesisResult.EngineServiceFailure)
        assertTrue((result as NavigationSynthesisResult.EngineServiceFailure).exhausted)
        assertTrue(initial.stopCount > 0)
        assertFalse(initial.synthesisCalls.last().file.exists())
        assertEquals(listOf(NavigationTtsFailure.BootstrapSetupFailure.VoiceMissing), stateLosses)
    }

    @Test
    fun requestPcmDuringPrepareProbeDeliversItsPcmWithoutInvalidatingTheProbe() = runBlocking {
        val probeQueued = kotlinx.coroutines.CompletableDeferred<SynthesisCall>()
        val tts = ttsDouble().apply {
            onSynthesize = { call ->
                probeQueued.complete(call)
                TextToSpeech.SUCCESS
            }
        }
        val engine = engine(factoryOf(tts))
        val prepare = async { engine.prepare() }
        val probe = probeQueued.await()
        val bypass = RecordedPcm(shortArrayOf(-321, 654), 16_000)
        val playback = mutableListOf<RecordedPcm>()

        val bypassResult = engine.requestPcm(bypass, playback::add)
        writePcm16Wav(probe.file)
        tts.emitDone(probe.utteranceId)

        assertTrue(bypassResult is NavigationSynthesisResult.Success)
        assertSame(bypass, (bypassResult as NavigationSynthesisResult.Success).pcm)
        assertEquals(1, playback.size)
        assertEquals(bypass.samples.toList(), playback.single().samples.toList())
        assertEquals(bypass.sampleRate, playback.single().sampleRate)
        assertTrue(prepare.await() is PrepareResult.Success)
    }


    @Test
    fun newerRequestPcmCancelsEarlierInFlightPlaybackAndDeliversOnlyTheNewestPcm() = runBlocking {
        val engine = engine(factoryOf(ttsDouble()))
        val firstPlaybackStarted = kotlinx.coroutines.CompletableDeferred<Unit>()
        val firstPlaybackCancelled = kotlinx.coroutines.CompletableDeferred<Unit>()
        val first = RecordedPcm(shortArrayOf(1), 16_000)
        val second = RecordedPcm(shortArrayOf(2), 16_000)
        val delivered = mutableListOf<RecordedPcm>()
        val firstRequest = async {
            engine.requestPcm(first) {
                firstPlaybackStarted.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    firstPlaybackCancelled.complete(Unit)
                }
            }
        }
        firstPlaybackStarted.await()

        val secondResult = engine.requestPcm(second, delivered::add)
        firstPlaybackCancelled.await()
        firstRequest.join()

        assertTrue(firstRequest.isCancelled)
        assertTrue(secondResult is NavigationSynthesisResult.Success)
        assertSame(second, (secondResult as NavigationSynthesisResult.Success).pcm)
        assertEquals(1, delivered.size)
        assertEquals(second.samples.toList(), delivered.single().samples.toList())
    }

    @Test
    fun cancellingRequestPcmCallerDoesNotCancelEngineOwnedPlaybackBeforeSupersession() = runBlocking {
        val engine = engine(factoryOf(ttsDouble()))
        val playbackStarted = kotlinx.coroutines.CompletableDeferred<Unit>()
        val playbackCancelled = kotlinx.coroutines.CompletableDeferred<Unit>()
        val cancelledCaller = async {
            engine.requestPcm(RecordedPcm(shortArrayOf(10), 16_000)) {
                playbackStarted.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    playbackCancelled.complete(Unit)
                }
            }
        }
        playbackStarted.await()

        cancelledCaller.cancel()
        cancelledCaller.join()

        assertTrue(cancelledCaller.isCancelled)
        assertFalse(playbackCancelled.isCompleted)

        val replacement = RecordedPcm(shortArrayOf(20), 16_000)
        val replacementPlayback = mutableListOf<RecordedPcm>()
        val replacementResult = engine.requestPcm(replacement, replacementPlayback::add)
        playbackCancelled.await()

        assertTrue(replacementResult is NavigationSynthesisResult.Success)
        assertEquals(1, replacementPlayback.size)
        assertEquals(replacement.samples.toList(), replacementPlayback.single().samples.toList())
    }

    @Test
    fun shutdownCancelsInFlightRequestPcmPlaybackWithoutDeliveringAResult() = runBlocking {
        val tts = ttsDouble()
        tts.onSynthesize = { call ->
            writePcm16Wav(call.file)
            tts.emitDone(call.utteranceId)
            TextToSpeech.SUCCESS
        }
        val engine = engine(factoryOf(tts))
        assertTrue(engine.prepare() is PrepareResult.Success)
        val playbackStarted = kotlinx.coroutines.CompletableDeferred<Unit>()
        val playbackCancelled = kotlinx.coroutines.CompletableDeferred<Unit>()
        val request = async {
            engine.requestPcm(RecordedPcm(shortArrayOf(123), 16_000)) {
                playbackStarted.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    playbackCancelled.complete(Unit)
                }
            }
        }
        playbackStarted.await()

        engine.shutdown()
        request.join()
        playbackCancelled.await()

        assertTrue(request.isCancelled)
        assertEquals(1, tts.shutdownCount)
    }

    private fun engine(
        factory: TextToSpeechFactory,
        cacheDir: File = newCacheDir(),
        stateLossCallback: StateLossCallback = StateLossCallback { _, _ -> },
        config: NavigationTtsConfig = NavigationTtsConfig(
            initTimeoutMs = 5_000,
            probeTimeoutMs = 5_000,
            synthesisTimeoutMs = 5_000,
        ),
    ): NavigationTtsEngine = NavigationTtsEngine(
        context = mockk<Context> {
            every { this@mockk.cacheDir } returns cacheDir
        },
        factory = factory,
        config = config,
        stateLossCallback = stateLossCallback,
    )


    private fun newCacheDir(): File = Files.createTempDirectory("navigation-tts-test").toFile().also {
        temporaryDirectories += it
    }

    private fun factoryOf(vararg doubles: TtsDouble): TextToSpeechFactory {
        var next = 0
        return TextToSpeechFactory { listener ->
            check(next < doubles.size) { "Unexpected TextToSpeech construction" }
            val selected = doubles[next++]
            selected.initListener = listener
            listener.onInit(selected.initStatus)
            selected.tts
        }
    }

    private fun ttsDouble(
        initStatus: Int = TextToSpeech.SUCCESS,
        defaultEngine: String? = "test.engine",
        engines: List<TextToSpeech.EngineInfo> = listOf(engineInfo("test.engine")),
        voices: Set<Voice>? = setOf(voice("offline-en", Locale.US, latency = 20, quality = 400)),
        languageAvailability: Map<Locale, Int> = emptyMap(),
        setVoiceReturn: Int = TextToSpeech.SUCCESS,
        onStop: () -> Unit = {},
    ): TtsDouble = TtsDouble(
        initStatus = initStatus,
        defaultEngine = defaultEngine,
        engines = engines,
        voices = voices,
        languageAvailability = languageAvailability,
        setVoiceReturn = setVoiceReturn,
        onStop = onStop,
    )

    private fun engineInfo(name: String): TextToSpeech.EngineInfo = TextToSpeech.EngineInfo().apply {
        this.name = name
    }

    private fun voice(
        name: String,
        locale: Locale,
        latency: Int,
        quality: Int,
        requiresNetwork: Boolean = false,
        features: Set<String> = emptySet(),
    ): Voice = mockk {
        every { this@mockk.name } returns name
        every { this@mockk.locale } returns locale
        every { this@mockk.latency } returns latency
        every { this@mockk.quality } returns quality
        every { this@mockk.isNetworkConnectionRequired } returns requiresNetwork
        every { this@mockk.features } returns features
    }

    private data class SynthesisCall(
        val text: String,
        val params: Bundle?,
        val file: File,
        val wasRegularFileAtEnqueue: Boolean,
        val utteranceId: String,
    )

    private class TtsDouble(
        val initStatus: Int,
        defaultEngine: String?,
        engines: List<TextToSpeech.EngineInfo>,
        voices: Set<Voice>?,
        languageAvailability: Map<Locale, Int>,
        private val setVoiceReturn: Int,
        private val onStop: () -> Unit,
    ) {
        val tts: TextToSpeech = mockk(relaxed = true)
        lateinit var initListener: TextToSpeech.OnInitListener
        var listener: UtteranceProgressListener? = null
        var selectedVoice: Voice? = null
        var stopCount = 0
        var shutdownCount = 0
        val synthesisCalls = mutableListOf<SynthesisCall>()
        var onSynthesize: (SynthesisCall) -> Int = { TextToSpeech.ERROR }

        init {
            every { tts.defaultEngine } returns defaultEngine
            every { tts.engines } returns engines
            every { tts.voices } returns voices
            every { tts.isLanguageAvailable(any()) } answers {
                languageAvailability[firstArg<Locale>()] ?: TextToSpeech.LANG_COUNTRY_AVAILABLE
            }
            every { tts.setVoice(any()) } answers {
                selectedVoice = firstArg()
                setVoiceReturn
            }
            every { tts.setOnUtteranceProgressListener(any()) } answers {
                listener = firstArg()
                TextToSpeech.SUCCESS
            }
            every { tts.synthesizeToFile(any<CharSequence>(), any<Bundle>(), any<File>(), any<String>()) } answers {
                SynthesisCall(
                    text = firstArg<CharSequence>().toString(),
                    params = secondArg<Bundle?>(),
                    file = thirdArg(),
                    wasRegularFileAtEnqueue = thirdArg<File>().isFile,
                    utteranceId = args[3] as String,
                ).also(synthesisCalls::add).let(onSynthesize)
            }
            every { tts.stop() } answers {
                stopCount += 1
                onStop()
                TextToSpeech.SUCCESS
            }
            every { tts.shutdown() } answers {
                shutdownCount += 1
                Unit
            }
        }


        fun emitDone(utteranceId: String) {
            listener?.onDone(utteranceId)
        }

        fun emitError(utteranceId: String) {
            listener?.onError(utteranceId)
        }

        fun emitStop(utteranceId: String) {
            listener?.onStop(utteranceId, true)
        }
    }

    private fun writePcm16Wav(file: File) {
        val pcm = byteArrayOf(0, 0, 0xff.toByte(), 0x7f)
        FileOutputStream(file).use { output ->
            output.write("RIFF".encodeToByteArray())
            writeLe32(output, 36 + pcm.size)
            output.write("WAVEfmt ".encodeToByteArray())
            writeLe32(output, 16)
            writeLe16(output, 1)
            writeLe16(output, 1)
            writeLe32(output, 16_000)
            writeLe32(output, 32_000)
            writeLe16(output, 2)
            writeLe16(output, 16)
            output.write("data".encodeToByteArray())
            writeLe32(output, pcm.size)
            output.write(pcm)
        }
    }

    private fun writeLe16(output: FileOutputStream, value: Int) {
        output.write(value and 0xff)
        output.write((value ushr 8) and 0xff)
    }

    private fun writeLe32(output: FileOutputStream, value: Int) {
        output.write(value and 0xff)
        output.write((value ushr 8) and 0xff)
        output.write((value ushr 16) and 0xff)
        output.write((value ushr 24) and 0xff)
    }
}
