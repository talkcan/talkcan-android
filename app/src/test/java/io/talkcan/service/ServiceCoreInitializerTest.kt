package io.talkcan.service

import android.content.Context
import android.speech.tts.TextToSpeech
import io.talkcan.audio.FakeSttTranscriber
import io.talkcan.audio.FakeTtsSynthesizer
import io.talkcan.audio.ModelVerifier
import io.talkcan.audio.NavigationTtsEngine
import io.talkcan.audio.NavigationTtsFailure
import io.talkcan.audio.PcmTranscriber
import io.talkcan.audio.PrepareResult
import io.talkcan.audio.StateLossCallback
import io.talkcan.audio.TranscriptionService
import io.talkcan.audio.TtsController
import io.talkcan.audio.onnx.ParakeetOnnxTranscriber
import io.talkcan.audio.onnx.SupertonicOnnxSynthesizer
import io.talkcan.channel.SleepwalkerTextOutputService
import io.talkcan.channel.TextOutputAvailability
import io.talkcan.channel.capability.CapabilityAvailability
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import java.io.File
import java.nio.file.Files
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ServiceCoreInitializerTest {
    @Test
    fun `CoreInit retains successful STT TTS text-output and navigation construction`() = runTest {
        val navigation = preparedNavigationEngine()
        val fixture = Fixture(
            scope = backgroundScope,
            navigationEngines = ArrayDeque(listOf(navigation)),
        )
        val coreInit: CoreInit = fixture.initializer

        assertSame(fixture.stt, coreInit.constructSttTranscriber())
        assertSame(fixture.tts, coreInit.constructTtsSynthesizer())
        coreInit.constructTtsController(requireNotNull(fixture.tts))
        fixture.textOutputAvailability.value = TextOutputAvailability.Available
        coreInit.initializeTextOutputCapability()
        val navigationResult = coreInit.prepareNavigationTts()

        assertEquals(
            File(fixture.modelRoot, ModelVerifier.PARAKEET_DIR),
            fixture.initializer.sttModelDir,
        )
        assertEquals(
            File(fixture.modelRoot, ModelVerifier.SUPERTONIC_DIR),
            fixture.initializer.supertonicModelDir,
        )
        assertSame(fixture.stt, coreInit.sttTranscriber)
        assertSame(fixture.tts, coreInit.ttsSynthesizer)
        assertSame(fixture.controller, coreInit.ttsController)
        assertSame(navigation, coreInit.navigationTtsEngine)
        assertTrue(navigationResult is PrepareResult.Success)
        assertEquals(CapabilityAvailability.Available, coreInit.textOutputAvailability)
    }

    @Test
    fun `CoreInit preserves failed native text-output and navigation outcomes without retaining resources`() = runTest {
        val navigation = mockk<NavigationTtsEngine>()
        val navigationFailure = NavigationTtsFailure.BootstrapSetupFailure.EngineInitFailed("engine unavailable")
        coEvery { navigation.prepare() } returns PrepareResult.Failure(navigationFailure)
        coEvery { navigation.shutdown() } just Runs
        val fixture = Fixture(
            scope = backgroundScope,
            stt = null,
            tts = null,
            navigationEngines = ArrayDeque(listOf(navigation)),
        )
        val coreInit: CoreInit = fixture.initializer

        assertNull(coreInit.constructSttTranscriber())
        assertNull(coreInit.constructTtsSynthesizer())
        fixture.textOutputAvailability.value = TextOutputAvailability.Closed
        coreInit.initializeTextOutputCapability()
        val navigationResult = coreInit.prepareNavigationTts()

        assertNull(coreInit.sttTranscriber)
        assertNull(coreInit.ttsSynthesizer)
        assertNull(coreInit.navigationTtsEngine)
        assertEquals(
            CapabilityAvailability.Unavailable(
                io.talkcan.channel.capability.CapabilityUnavailableReason.HOST_NOT_READY,
            ),
            coreInit.textOutputAvailability,
        )
        assertEquals(PrepareResult.Failure(navigationFailure), navigationResult)
        coVerify(exactly = 1) { navigation.shutdown() }
    }

    @Test
    fun `retry discard cancels owned pollers and releases retained resources exactly once across repeated discard`() = runTest {
        val navigation = preparedNavigationEngine()
        val fixture = Fixture(
            scope = backgroundScope,
            navigationEngines = ArrayDeque(listOf(navigation)),
        )
        coEvery { navigation.shutdown() } coAnswers { fixture.events += "navigation shut down" }
        val coreInit: CoreInit = fixture.initializer

        coreInit.constructSttTranscriber()
        val synthesizer = requireNotNull(coreInit.constructTtsSynthesizer())
        coreInit.constructTtsController(synthesizer)
        coreInit.prepareNavigationTts()

        coreInit.discardControllers()
        coreInit.discardControllers()

        assertTrue(fixture.sttPoller.isCancelled)
        assertTrue(fixture.ttsPoller.isCancelled)
        assertNull(coreInit.sttTranscriber)
        assertNull(coreInit.ttsSynthesizer)
        assertNull(coreInit.ttsController)
        assertNull(coreInit.navigationTtsEngine)
        // Release order: navigation, pollers joined, controller work
        // terminated, references detached, engines closed exactly once.
        assertEquals(
            listOf(
                "navigation shut down",
                "stt poller cancelled",
                "tts poller cancelled",
                "controller released",
                "stt engine closed",
                "tts engine closed",
            ),
            fixture.events,
        )
        assertEquals(1, requireNotNull(fixture.stt).closeCount)
        assertEquals(1, requireNotNull(fixture.tts).closeCount)
        coVerify(exactly = 1) { fixture.controller.cancelAndRelease() }
        coVerify(exactly = 1) { navigation.shutdown() }
    }

    @Test
    fun `shutdown after partial initialization ignores absent resources and is idempotent`() = runTest {
        val navigation = preparedNavigationEngine()
        val fixture = Fixture(
            scope = backgroundScope,
            navigationEngines = ArrayDeque(listOf(navigation)),
        )
        val coreInit: CoreInit = fixture.initializer

        coreInit.constructSttTranscriber()
        coreInit.prepareNavigationTts()
        fixture.initializer.shutdown()
        fixture.initializer.shutdown()

        assertTrue(fixture.sttPoller.isCancelled)
        assertNull(coreInit.sttTranscriber)
        assertNull(coreInit.ttsSynthesizer)
        assertNull(coreInit.ttsController)
        assertNull(coreInit.navigationTtsEngine)
        // Only the constructed STT generation closes; the never-constructed
        // TTS fake, TTS poller, and controller are untouched.
        assertEquals(listOf("stt poller cancelled", "stt engine closed"), fixture.events)
        assertEquals(1, requireNotNull(fixture.stt).closeCount)
        assertEquals(0, requireNotNull(fixture.tts).closeCount)
        coVerify(exactly = 1) { navigation.shutdown() }
    }

    @Test
    fun `navigation prepare replaces its retired engine and service shutdown closes the replacement once`() = runTest {
        val first = preparedNavigationEngine()
        val replacement = preparedNavigationEngine()
        val fixture = Fixture(
            scope = backgroundScope,
            navigationEngines = ArrayDeque(listOf(first, replacement)),
        )

        fixture.initializer.prepareNavigationTts()
        fixture.initializer.prepareNavigationTts()
        fixture.initializer.shutdown()
        fixture.initializer.shutdown()

        assertNull(fixture.initializer.navigationTtsEngine)
        coVerify(exactly = 1) { first.shutdown() }
        coVerify(exactly = 1) { replacement.shutdown() }
    }

    @Test
    fun `default speech factories construct direct ONNX engines without native library inputs`() {
        val root = Files.createTempDirectory("talkcan-direct-onnx-factories").toFile()
        val stt = requireNotNull(DefaultSttFactory.create(File(root, ModelVerifier.PARAKEET_DIR)))
        try {
            val tts = requireNotNull(DefaultTtsFactory.create(File(root, ModelVerifier.SUPERTONIC_DIR)))
            try {
                assertTrue(stt is ParakeetOnnxTranscriber)
                assertTrue(tts is SupertonicOnnxSynthesizer)
            } finally {
                tts.close()
            }
        } finally {
            stt.close()
            root.deleteRecursively()
        }
    }

    private fun preparedNavigationEngine(): NavigationTtsEngine = mockk {
        coEvery { prepare() } returns PrepareResult.Success(mockk<TextToSpeech>(relaxed = true))
        coEvery { shutdown() } just Runs
    }

    private class Fixture(
        scope: CoroutineScope,
        stt: FakeSttTranscriber? = FakeSttTranscriber(),
        tts: FakeTtsSynthesizer? = FakeTtsSynthesizer(),
        val navigationEngines: ArrayDeque<NavigationTtsEngine>,
    ) {
        /** Records release events in the order teardown performs them. */
        val events = mutableListOf<String>()
        val stt: FakeSttTranscriber? = stt?.also {
            it.onClose = { events += "stt engine closed" }
        }
        val tts: FakeTtsSynthesizer? = tts?.also {
            it.onClose = { events += "tts engine closed" }
        }
        val controller: TtsController = mockk(relaxed = true) {
            coEvery { cancelAndRelease() } coAnswers { events += "controller released" }
        }
        val modelRoot = File("/service-core-initializer-test-models")
        val textOutputAvailability: MutableStateFlow<TextOutputAvailability> =
            MutableStateFlow(TextOutputAvailability.Available)
        val sttPoller = Job().also { job ->
            job.invokeOnCompletion { events += "stt poller cancelled" }
        }
        val ttsPoller = Job().also { job ->
            job.invokeOnCompletion { events += "tts poller cancelled" }
        }

        private val textOutputService = mockk<SleepwalkerTextOutputService> {
            every { availability } returns textOutputAvailability
        }

        val initializer = ServiceCoreInitializer(
            context = mockk<Context>(relaxed = true),
            scope = scope,
            filesDirProvider = { modelRoot },
            textOutputService = textOutputService,
            channelCatalogue = { mockk(relaxed = true) },
            modelStatusSink = ModelStatusSink { },
            navigationStateLoss = StateLossCallback { _, _ -> },
            hostAudioPlay = { true },
            sttFactory = SttFactory { _ -> stt },
            ttsFactory = TtsFactory { _ -> tts },
            ttsControllerFactory = TtsControllerFactory { _, _, _ -> controller },
            transcriptionServiceFactory = TranscriptionServiceFactory { mockk<TranscriptionService>(relaxed = true) },
            pcmTranscriberFallback = PcmTranscriberFallback { mockk<PcmTranscriber>(relaxed = true) },
            navigationTtsEngineFactory = NavigationTtsEngineFactory { _, _ -> navigationEngines.removeFirst() },
            sttPollerFactory = SttPollerFactory { _, _, _, _ -> sttPoller },
            ttsPollerFactory = TtsPollerFactory { _, _, _, _, _, _ -> ttsPoller },
        )
    }
}
