package io.talkcan.service

import android.content.Context
import android.content.pm.PackageManager
import android.speech.tts.TextToSpeech
import io.talkcan.audio.FakeSttTranscriber
import io.talkcan.audio.FakeTtsSynthesizer
import io.talkcan.audio.ModelAssetRepository
import io.talkcan.audio.NavigationSynthesisResult
import io.talkcan.audio.NavigationTtsEngine
import io.talkcan.audio.NavigationTtsFailure
import io.talkcan.audio.PrepareResult
import io.talkcan.audio.SttTranscriber
import io.talkcan.audio.TtsController
import io.talkcan.audio.TtsSynthesizer
import io.talkcan.channel.capability.CapabilityAvailability
import io.talkcan.model.BootstrapStage
import io.talkcan.model.BootstrapState
import io.talkcan.model.SttModelStatus
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BootstrapCoordinatorTest {
    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `proved navigation voice and ONNX engines complete bootstrap without announcement precomputation`() = runTest {
        val coreInit = FakeCoreInit()
        val coordinator = coordinator(coreInit)

        coordinator.startBootstrap()
        advanceUntilIdle()

        assertEquals(BootstrapState.Ready, coordinator.state.value)
        assertEquals(1, coreInit.navigationTtsPreparationCount)
    }

    @Test
    fun `verified model assets refresh voice profiles before Supertonic initialization`() = runTest {
        val events = mutableListOf<String>()
        val coreInit = FakeCoreInit(
            ttsFactory = {
                events += "Supertonic constructed"
                FakeTtsSynthesizer()
            },
        )
        val coordinator = coordinator(
            coreInit = coreInit,
            onModelAssetsReady = { events += "voice profiles refreshed" },
        )

        coordinator.startBootstrap()
        advanceUntilIdle()

        assertEquals(BootstrapState.Ready, coordinator.state.value)
        assertTrue(events.indexOf("voice profiles refreshed") < events.indexOf("Supertonic constructed"))
    }

    @Test
    fun `navigation TTS preparation completes before ONNX STT and Supertonic initialization`() = runTest {
        val events = mutableListOf<String>()
        val coreInit = FakeCoreInit(
            navigationTtsBehavior = {
                events += "navigation TTS prepared"
                preparedNavigationTts()
            },
            sttFactory = {
                events += "STT constructed"
                FakeSttTranscriber()
            },
            ttsFactory = {
                events += "Supertonic constructed"
                FakeTtsSynthesizer()
            },
        )
        val coordinator = coordinator(coreInit)

        coordinator.startBootstrap()
        advanceUntilIdle()

        assertEquals(BootstrapState.Ready, coordinator.state.value)
        assertEquals("navigation TTS prepared", events.first())
        assertTrue(events.indexOf("navigation TTS prepared") < events.indexOf("STT constructed"))
        assertTrue(events.indexOf("navigation TTS prepared") < events.indexOf("Supertonic constructed"))
    }

    @Test
    fun `missing all-files access enters dedicated setup before model download or core initialization`() = runTest {
        var modelDownloadCount = 0
        val coreInit = FakeCoreInit()
        val coordinator = coordinator(
            coreInit = coreInit,
            modelRepository = modelRepository(
                inspect = { emptyList() },
                ensureReady = {
                    modelDownloadCount += 1
                    true
                },
            ),
            hasManageExternalStorage = { false },
        )

        coordinator.startBootstrap()
        advanceUntilIdle()

        assertEquals(
            BootstrapState.NeedsSetup(needsManageExternalStorage = true),
            coordinator.state.value,
        )
        assertEquals(0, coreInit.sttConstructionCount)
        assertEquals(0, coreInit.ttsConstructionCount)

        coordinator.startModelAcquisition()
        advanceUntilIdle()

        assertEquals(0, modelDownloadCount)
        assertEquals(
            BootstrapState.NeedsSetup(needsManageExternalStorage = true),
            coordinator.state.value,
        )
    }

    @Test
    fun `refreshing after all-files access is granted completes bootstrap`() = runTest {
        var hasAllFilesAccess = false
        val coreInit = FakeCoreInit()
        val coordinator = coordinator(
            coreInit = coreInit,
            hasManageExternalStorage = { hasAllFilesAccess },
        )

        coordinator.startBootstrap()
        advanceUntilIdle()
        assertEquals(
            BootstrapState.NeedsSetup(needsManageExternalStorage = true),
            coordinator.state.value,
        )

        hasAllFilesAccess = true
        coordinator.refreshPrerequisites()
        advanceUntilIdle()

        assertEquals(BootstrapState.Ready, coordinator.state.value)
    }

    @Test
    fun `loading ONNX STT past its deadline fails at STT initialization`() = runTest {
        val coreInit = FakeCoreInit(
            sttFactory = { FakeSttTranscriber(modelStatus = SttModelStatus.Loading) },
        )
        val coordinator = coordinator(coreInit).apply {
            sttTimeoutMs = 999L
        }

        coordinator.startBootstrap()
        runCurrent()
        advanceTimeBy(1_000L)
        runCurrent()

        assertEquals(
            BootstrapState.Failed(
                BootstrapStage.InitializingStt,
                "STT initialization timed out after 999ms",
            ),
            coordinator.state.value,
        )
        coordinator.cancelAttempt()
        runCurrent()
    }

    @Test
    fun `ONNX STT load failure surfaces its diagnostic and blocks readiness`() = runTest {
        val coreInit = FakeCoreInit(
            sttFactory = {
                FakeSttTranscriber(
                    modelStatus = SttModelStatus.Failed,
                    loadError = "corrupt ONNX model",
                )
            },
        )
        val coordinator = coordinator(coreInit)

        coordinator.startBootstrap()
        advanceUntilIdle()

        assertEquals(
            BootstrapState.Failed(
                BootstrapStage.InitializingStt,
                "STT model failed to load: corrupt ONNX model",
            ),
            coordinator.state.value,
        )
    }

    @Test
    fun `exhausted navigation engine recovery maps to retryable failure`() = runTest {
        val coordinator = coordinator(FakeCoreInit())

        coordinator.startBootstrap()
        advanceUntilIdle()
        coordinator.onNavigationSynthesisResult(
            NavigationSynthesisResult.EngineServiceFailure(
                failure = NavigationTtsFailure.EngineServiceFailure.SynthesisTimeout,
                exhausted = true,
            ),
        )

        val state = coordinator.state.value
        assertTrue(state is BootstrapState.Failed)
        state as BootstrapState.Failed
        assertEquals(BootstrapStage.ProbingNavigationVoice, state.stage)
        assertTrue(state.retryable)
        assertTrue(state.diagnostic.contains("recovery exhausted"))
    }

    @Test
    fun `navigation voice setup failures expose the offline voice issue and skip core initialization`() = runTest {
        data class SetupCase(
            val name: String,
            val failure: NavigationTtsFailure.BootstrapSetupFailure,
            val diagnostic: String,
        )

        val cases = listOf(
            SetupCase(
                name = "engine unavailable",
                failure = NavigationTtsFailure.BootstrapSetupFailure.EngineUnavailable,
                diagnostic = "No Android text-to-speech engine is installed or active.",
            ),
            SetupCase(
                name = "engine initialization failed",
                failure = NavigationTtsFailure.BootstrapSetupFailure.EngineInitFailed("service crashed"),
                diagnostic = "Android text-to-speech engine initialization failed: service crashed",
            ),
            SetupCase(
                name = "engine initialization timed out",
                failure = NavigationTtsFailure.BootstrapSetupFailure.EngineInitTimeout,
                diagnostic = "Android text-to-speech engine initialization timed out.",
            ),
            SetupCase(
                name = "offline voice missing",
                failure = NavigationTtsFailure.BootstrapSetupFailure.VoiceMissing,
                diagnostic = "Install an offline English voice for the active text-to-speech engine.",
            ),
            SetupCase(
                name = "voice selection failed",
                failure = NavigationTtsFailure.BootstrapSetupFailure.VoiceSelectionFailed,
                diagnostic = "The active text-to-speech engine could not select its offline English voice.",
            ),
            SetupCase(
                name = "voice probe failed",
                failure = NavigationTtsFailure.BootstrapSetupFailure.VoiceProbeFailed("empty PCM"),
                diagnostic = "The offline English voice failed its synthesis probe: empty PCM",
            ),
            SetupCase(
                name = "voice probe timed out",
                failure = NavigationTtsFailure.BootstrapSetupFailure.VoiceProbeTimeout,
                diagnostic = "The offline English voice synthesis probe timed out.",
            ),
        )

        for (case in cases) {
            val coreInit = FakeCoreInit(
                navigationTtsBehavior = {
                    PrepareResult.Failure(case.failure, "dev.example.tts")
                },
            )
            val coordinator = coordinator(coreInit)

            coordinator.startBootstrap()
            advanceUntilIdle()

            val state = coordinator.state.value
            assertTrue("${case.name} should require setup", state is BootstrapState.NeedsSetup)
            state as BootstrapState.NeedsSetup
            assertEquals(case.diagnostic, state.offlineNavigationVoiceIssue?.diagnostic)
            assertEquals("dev.example.tts", state.offlineNavigationVoiceIssue?.enginePackage)
            assertEquals(0, coreInit.sttConstructionCount)
            assertEquals(0, coreInit.ttsConstructionCount)
            assertEquals(0, coreInit.textOutputInitializationCount)
        }
    }

    @Test
    fun `renderer infrastructure failure leaves ready state retryably failed`() = runTest {
        val coordinator = coordinator(FakeCoreInit())
        coordinator.startBootstrap()
        advanceUntilIdle()

        coordinator.onNavigationSynthesisResult(
            NavigationSynthesisResult.InfrastructureFailure(
                NavigationTtsFailure.RendererInfrastructureFailure.FileIoFailure("cache file unreadable"),
            ),
        )

        val state = coordinator.state.value
        assertTrue(state is BootstrapState.Failed)
        state as BootstrapState.Failed
        assertEquals(BootstrapStage.ProbingNavigationVoice, state.stage)
        assertTrue(state.retryable)
        assertTrue(state.diagnostic.contains("infrastructure failure"))
        assertTrue(state.diagnostic.contains("cache file unreadable"))
    }

    @Test
    fun `concurrent retries coalesce and discard only after the prior attempt joins`() = runTest {
        val events = mutableListOf<String>()
        val firstInspectionStarted = CompletableDeferred<Unit>()
        var inspectionCount = 0
        val modelRepository = modelRepository(inspect = {
            inspectionCount += 1
            if (inspectionCount == 1) {
                firstInspectionStarted.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    events += "prior joined"
                }
            } else {
                events += "replacement inspected"
                emptyList()
            }
        })
        val coreInit = FakeCoreInit(onDiscard = { events += "controllers discarded" })
        val coordinator = coordinator(coreInit, modelRepository)

        coordinator.startBootstrap()
        runCurrent()
        firstInspectionStarted.await()

        coordinator.retry()
        coordinator.retry()
        advanceUntilIdle()

        assertEquals(
            listOf("prior joined", "controllers discarded", "replacement inspected"),
            events,
        )
        assertEquals(1, coreInit.discardCount)
        assertEquals(BootstrapState.Ready, coordinator.state.value)
    }

    @Test
    fun `retry discards the retained navigation TTS engine before preparing its replacement`() = runTest {
        val events = mutableListOf<String>()
        val firstEngine = mockk<NavigationTtsEngine>(relaxed = true)
        val replacementEngine = mockk<NavigationTtsEngine>(relaxed = true)
        coEvery { firstEngine.shutdown() } coAnswers { events += "first engine shut down" }
        coEvery { replacementEngine.shutdown() } coAnswers { events += "replacement engine shut down" }
        var engineIndex = 0
        val sttGenerations = mutableListOf<FakeSttTranscriber>()
        val ttsGenerations = mutableListOf<FakeTtsSynthesizer>()
        val coreInit = FakeCoreInit(
            sttFactory = { FakeSttTranscriber().also(sttGenerations::add) },
            ttsFactory = { FakeTtsSynthesizer().also(ttsGenerations::add) },
            navigationTtsEngineFactory = {
                when (engineIndex++) {
                    0 -> firstEngine.also { events += "first engine prepared" }
                    else -> replacementEngine.also { events += "replacement engine prepared" }
                }
            },
        )
        val coordinator = coordinator(coreInit)

        coordinator.startBootstrap()
        advanceUntilIdle()
        coordinator.retry()
        advanceUntilIdle()

        assertEquals(BootstrapState.Ready, coordinator.state.value)
        assertEquals(
            listOf(
                "first engine prepared",
                "first engine shut down",
                "replacement engine prepared",
            ),
            events,
        )
        // Discard closes the retired speech engine generation exactly once;
        // the replacement generation remains open for the ready state.
        assertEquals(listOf(1, 0), sttGenerations.map { it.closeCount })
        assertEquals(listOf(1, 0), ttsGenerations.map { it.closeCount })
    }

    @Test
    fun `recoverable physical text output and journal recovery do not block core readiness`() = runTest {
        val coreInit = FakeCoreInit(
            textOutputAfterInitialization = CapabilityAvailability.Recoverable,
        )
        val coordinator = coordinator(coreInit)

        coordinator.startBootstrap()
        advanceUntilIdle()

        assertEquals(CapabilityAvailability.Recoverable, coreInit.textOutputAvailability)
        assertEquals(BootstrapState.Ready, coordinator.state.value)
    }

    @Test
    fun `unavailable semantic text output fails readiness with an actionable diagnostic`() = runTest {
        val unavailable = CapabilityAvailability.Unavailable(
            io.talkcan.channel.capability.CapabilityUnavailableReason.HOST_NOT_READY,
        )
        val coreInit = FakeCoreInit(textOutputAfterInitialization = unavailable)
        val coordinator = coordinator(coreInit)

        coordinator.startBootstrap()
        advanceUntilIdle()

        val state = coordinator.state.value
        assertTrue(state is BootstrapState.Failed)
        state as BootstrapState.Failed
        assertEquals(BootstrapStage.VerifyingReadiness, state.stage)
        assertTrue(state.diagnostic.contains("textOutputAvailability=$unavailable"))
    }

    private fun kotlinx.coroutines.test.TestScope.coordinator(
        coreInit: CoreInit,
        modelRepository: ModelAssetRepository = modelRepository(inspect = { emptyList() }),
        hasManageExternalStorage: () -> Boolean = { true },
        onModelAssetsReady: () -> Unit = {},
    ): BootstrapCoordinator = BootstrapCoordinator(
        context = grantedContext(),
        scope = this,
        modelRepository = modelRepository,
        coreInit = coreInit,
        ioDispatcher = StandardTestDispatcher(testScheduler),
        hasManageExternalStorage = hasManageExternalStorage,
        onModelAssetsReady = onModelAssetsReady,
    )

    private fun grantedContext(): Context = mockk(relaxed = true) {
        every { checkPermission(any(), any(), any()) } returns PackageManager.PERMISSION_GRANTED
    }

    private fun modelRepository(
        inspect: suspend () -> List<io.talkcan.model.ModelAssetResult>,
        ensureReady: suspend () -> Boolean = { true },
    ): ModelAssetRepository = mockk(relaxed = true) {
        coEvery { inspectAll() } coAnswers { inspect() }
        coEvery { ensureAllReady() } coAnswers { ensureReady() }
    }

    /**
     * Host-boundary fake matching the current [CoreInit] contract. It owns a retained
     * navigation engine exactly as the service does, so retry disposal is observable.
     */
    private class FakeCoreInit(
        private val sttFactory: () -> SttTranscriber? = { FakeSttTranscriber() },
        private val ttsFactory: () -> TtsSynthesizer? = { FakeTtsSynthesizer() },
        private val textOutputAfterInitialization: CapabilityAvailability =
            CapabilityAvailability.Available,
        private val navigationTtsBehavior: suspend () -> PrepareResult = {
            preparedNavigationTts()
        },
        private val navigationTtsEngineFactory: () -> NavigationTtsEngine = {
            mockk(relaxed = true)
        },
        private val onDiscard: () -> Unit = {},
    ) : CoreInit {
        override var navigationTtsEngine: NavigationTtsEngine? = null
            private set
        override var sttTranscriber: SttTranscriber? = null
            private set
        override var ttsSynthesizer: TtsSynthesizer? = null
            private set
        override var ttsController: TtsController? = null
            private set
        override var textOutputAvailability: CapabilityAvailability =
            CapabilityAvailability.Unavailable(
                io.talkcan.channel.capability.CapabilityUnavailableReason.HOST_NOT_READY,
            )
            private set

        var discardCount: Int = 0
            private set
        var navigationTtsPreparationCount: Int = 0
            private set
        var sttConstructionCount: Int = 0
            private set
        var ttsConstructionCount: Int = 0
            private set
        var textOutputInitializationCount: Int = 0
            private set

        override suspend fun prepareNavigationTts(): PrepareResult {
            navigationTtsEngine?.shutdown()
            navigationTtsEngine = null
            navigationTtsPreparationCount += 1
            return navigationTtsBehavior().also { result ->
                if (result is PrepareResult.Success) {
                    navigationTtsEngine = navigationTtsEngineFactory()
                }
            }
        }

        override fun constructSttTranscriber(): SttTranscriber? = sttFactory().also {
            sttConstructionCount += 1
            sttTranscriber = it
        }

        override fun constructTtsSynthesizer(): TtsSynthesizer? = ttsFactory().also {
            ttsConstructionCount += 1
            ttsSynthesizer = it
        }

        override fun constructTtsController(synthesizer: TtsSynthesizer) {
            ttsController = mockk(relaxed = true)
        }


        override fun initializeTextOutputCapability() {
            textOutputInitializationCount += 1
            textOutputAvailability = textOutputAfterInitialization
        }

        override suspend fun discardControllers() {
            discardCount += 1
            navigationTtsEngine?.shutdown()
            navigationTtsEngine = null
            sttTranscriber?.close()
            sttTranscriber = null
            ttsSynthesizer?.close()
            ttsSynthesizer = null
            ttsController = null
            textOutputAvailability = CapabilityAvailability.Unavailable(
                io.talkcan.channel.capability.CapabilityUnavailableReason.HOST_NOT_READY,
            )
            onDiscard()
        }
    }

    private companion object {
        fun preparedNavigationTts(): PrepareResult.Success =
            PrepareResult.Success(mockk<TextToSpeech>(relaxed = true))
    }
}