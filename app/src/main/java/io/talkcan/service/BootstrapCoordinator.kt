package io.talkcan.service

import android.content.Context
import io.talkcan.audio.ModelAssetRepository
import io.talkcan.audio.SttTranscriber
import io.talkcan.audio.TtsSynthesizer
import io.talkcan.audio.TtsController
import io.talkcan.audio.NavigationSynthesisResult
import io.talkcan.audio.NavigationTtsEngine
import io.talkcan.audio.NavigationTtsFailure
import io.talkcan.audio.PrepareResult
import io.talkcan.channel.capability.CapabilityAvailability
import io.talkcan.model.BootstrapStage
import io.talkcan.model.BootstrapState
import io.talkcan.model.OfflineNavigationVoiceIssue
import io.talkcan.model.ModelAssetResult
import io.talkcan.model.SttModelStatus
import io.talkcan.model.TtsModelStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Service-owned bootstrap coordinator. Owns the authoritative bootstrap
 * [StateFlow] and drives the transition from launch through prerequisite
 * checking, model acquisition, native initialization, controller
 * construction, and announcement rendering to [BootstrapState.Ready].
 *
 * The coordinator delegates native engine construction and controller wiring
 * to [CoreInit] (implemented by [ServiceCoreInitializer]) because the
 * initializer owns the audio infrastructure, ONNX engines, and capture
 * pipeline.
 *
 * RSM, SPP, HFP, Keyboard BLE, Android Auto, Telecom, reconnect, and
 * journal-recovery readiness are explicitly excluded from the bootstrap
 * completion predicate (see [isCoreReady]).
 *
 * Each attempt owns its own [Job] so that a safe retry can cancel and
 * discard prior attempt jobs, pollers, and controllers before
 * reconstructing.
 */
class BootstrapCoordinator(
    private val context: Context,
    private val scope: CoroutineScope,
    private val modelRepository: ModelAssetRepository,
    private val coreInit: CoreInit,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val hasManageExternalStorage: () -> Boolean = RequiredPermissions::hasManageExternalStorage,
    private val onModelAssetsReady: () -> Unit = {},
) {
    private val _state = MutableStateFlow<BootstrapState>(BootstrapState.ConnectingService)
    val state: StateFlow<BootstrapState> = _state.asStateFlow()

    /**
     * Per-attempt bootstrap job. Cancelled on retry.
     */
    private var attemptJob: Job? = null
    private var retryJob: Job? = null

    /**
     * Test-injectable finite timeouts per stage.
     */
    var sttTimeoutMs: Long = 60_000L
    var ttsTimeoutMs: Long = 60_000L

    private fun launchAttempt() {
        if (attemptJob?.isActive == true || retryJob?.isActive == true) {
            return
        }
        attemptJob = scope.launch {
            val currentJob = coroutineContext[Job]
            try {
                runAttempt()
            } finally {
                if (attemptJob === currentJob) {
                    attemptJob = null
                }
            }
        }
    }

    /**
     * Called by the service after binding to start bootstrap.
     * Checks permissions and model assets, then either routes to setup
     * or proceeds to core preparation.
     */
    fun startBootstrap() {
        if (_state.value is BootstrapState.ConnectingService ||
            _state.value is BootstrapState.Failed
        ) {
            launchAttempt()
        }
    }

    /**
     * Binder command: refresh prerequisites. Re-checks permissions and model
     * assets. Used after a permission result or a model-acquisition retry.
     */
    fun refreshPrerequisites() {
        if (_state.value is BootstrapState.NeedsSetup) launchAttempt()
    }

    /**
     * Binder command: explicitly start model acquisition. Transitions to
     * [BootstrapState.AcquiringModels] and starts downloading all invalid
     * sets through the model repository.
     */
    fun startModelAcquisition() {
        val setup = _state.value as? BootstrapState.NeedsSetup ?: return
        if (setup.missingPermissions.isNotEmpty() || setup.needsManageExternalStorage) return
        scope.launch {
            _state.value = BootstrapState.AcquiringModels(
                progress = modelRepository.progress.value,
            )
            val ready = modelRepository.ensureAllReady()
            if (ready) {
                launchAttempt()
            } else {
                val results = modelRepository.inspectAll()
                val failedSets = results.filterIsInstance<ModelAssetResult.Failed>()
                    .map { it.dirName }
                val errorMessages = results.filterIsInstance<ModelAssetResult.Failed>()
                    .joinToString("; ") { it.reason }
                _state.value = BootstrapState.NeedsSetup(
                    invalidModelSets = failedSets,
                    error = errorMessages.ifEmpty { "Model acquisition failed" },
                )
            }
        }
    }

    /**
     * Binder command: retry a failed bootstrap. Cancels and discards prior
     * attempt jobs, pollers, and controllers before re-entering prerequisite
     * checking.
     */
    fun retry() {
        if (retryJob?.isActive == true) {
            return
        }
        val prior = attemptJob

        val replacement = scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            val currentJob = coroutineContext[Job]
            try {
                prior?.cancelAndJoin()
                coreInit.discardControllers()
                runAttempt()
            } finally {
                if (retryJob === currentJob) {
                    retryJob = null
                }
                if (attemptJob === currentJob) {
                    attemptJob = null
                }
            }
        }

        retryJob = replacement
        attemptJob = replacement
        replacement.start()
    }

    /**
     * Cancel all jobs from the current attempt. Called on retry and on
     * service destruction.
     */
    fun cancelAttempt() {
        attemptJob?.cancel()
        retryJob?.cancel()
        attemptJob = null
        retryJob = null
    }

    fun onNavigationVoiceStateLoss(
        failure: NavigationTtsFailure.BootstrapSetupFailure,
        enginePackage: String?,
    ) {
        _state.value = BootstrapState.NeedsSetup(
            offlineNavigationVoiceIssue = failure.toSetupIssue(enginePackage),
        )
    }

    fun onNavigationSynthesisResult(result: NavigationSynthesisResult) {
        if (_state.value !is BootstrapState.Ready) return
        when (result) {
            is NavigationSynthesisResult.Success -> Unit
            NavigationSynthesisResult.Superseded -> Unit
            is NavigationSynthesisResult.EngineServiceFailure -> if (result.exhausted) {
                _state.value = BootstrapState.Failed(
                    BootstrapStage.ProbingNavigationVoice,
                    "Navigation TTS recovery exhausted: ${result.failure}",
                )
            }
            is NavigationSynthesisResult.InfrastructureFailure -> {
                _state.value = BootstrapState.Failed(
                    BootstrapStage.ProbingNavigationVoice,
                    "Navigation TTS infrastructure failure: ${result.failure}",
                )
            }
        }
    }

    private suspend fun runAttempt() {
        _state.value = BootstrapState.CheckingPrerequisites(
            BootstrapStage.CheckingPermissions,
        )
        val missingPermissions = withContext(ioDispatcher) {
            RequiredPermissions.missing(context)
        }
        val needsManageExternalStorage = withContext(ioDispatcher) {
            !hasManageExternalStorage()
        }
        if (missingPermissions.isNotEmpty() || needsManageExternalStorage) {
            _state.value = BootstrapState.NeedsSetup(
                missingPermissions = missingPermissions,
                needsManageExternalStorage = needsManageExternalStorage,
            )
            return
        }

        _state.value = BootstrapState.CheckingPrerequisites(
            BootstrapStage.CheckingModels,
        )
        val results = modelRepository.inspectAll()
        val invalidSets = results.filterIsInstance<ModelAssetResult.UserActionRequired>()
            .map { it.dirName }
        if (invalidSets.isNotEmpty()) {
            _state.value = BootstrapState.NeedsSetup(
                invalidModelSets = invalidSets,
            )
            return
        }
        withContext(ioDispatcher) {
            onModelAssetsReady()
        }


        _state.value = BootstrapState.CheckingPrerequisites(
            BootstrapStage.ProbingNavigationVoice,
        )
        when (val navigationVoice = coreInit.prepareNavigationTts()) {
            is PrepareResult.Success -> Unit
            is PrepareResult.Failure -> {
                _state.value = BootstrapState.NeedsSetup(
                    offlineNavigationVoiceIssue = navigationVoice.failure.toSetupIssue(
                        navigationVoice.enginePackage,
                    ),
                )
                return
            }
        }

        _state.value = BootstrapState.PreparingCore(
            BootstrapStage.InitializingStt,
        )

        kotlinx.coroutines.coroutineScope {
            val sttDeferred = async(ioDispatcher) {
                prepareStt()
            }
            val ttsDeferred = async(ioDispatcher) {
                prepareTts()
            }

            val sttResult = withTimeoutOrNull(sttTimeoutMs) { sttDeferred.await() }
            val ttsResult = withTimeoutOrNull(ttsTimeoutMs) { ttsDeferred.await() }

            if (sttResult is PrepareResultInternal.Failed) {
                _state.value = BootstrapState.Failed(
                    BootstrapStage.InitializingStt,
                    sttResult.reason,
                )
                return@coroutineScope
            }
            if (ttsResult is PrepareResultInternal.Failed) {
                _state.value = BootstrapState.Failed(
                    BootstrapStage.InitializingTts,
                    ttsResult.reason,
                )
                return@coroutineScope
            }
            if (sttResult == null) {
                _state.value = BootstrapState.Failed(
                    BootstrapStage.InitializingStt,
                    "STT initialization timed out after ${sttTimeoutMs}ms",
                )
                return@coroutineScope
            }
            if (ttsResult == null) {
                _state.value = BootstrapState.Failed(
                    BootstrapStage.InitializingTts,
                    "TTS initialization timed out after ${ttsTimeoutMs}ms",
                )
                return@coroutineScope
            }

            _state.value = BootstrapState.PreparingCore(
                BootstrapStage.ConstructingControllers,
            )

            _state.value = BootstrapState.PreparingCore(
                BootstrapStage.VerifyingReadiness,
            )
            if (isCoreReady()) {
                _state.value = BootstrapState.Ready
            } else {
                val diagnostic = isCoreReadyDiagnostic()
                try {
                    android.util.Log.w("BootstrapCoordinator", "Core readiness failed: $diagnostic")
                } catch (_: Throwable) {
                    println("[BootstrapCoordinator] Core readiness failed: $diagnostic")
                }
                _state.value = BootstrapState.Failed(
                    BootstrapStage.VerifyingReadiness,
                    "Core readiness verification failed: $diagnostic",
                )
            }
        }
    }


    /**
     * Core readiness predicate: verified model assets, ready Kotlin ONNX STT and
     * TTS engines, initialized host services, initialized semantic text output, and every
     * required announcement phrase cached as non-empty SCO-ready audio.
     *
     * RSM, SPP, HFP, physical text-output connection, Android Auto, Telecom, reconnect, and
     * journal-recovery are explicitly excluded. A semantic text capability may be recoverable
     * without making bootstrap fail; bounded connection preparation belongs to PTT handling.
     */
    private fun isCoreReady(): Boolean {
        return coreInit.navigationTtsEngine != null &&
            coreInit.sttTranscriber != null &&
            coreInit.ttsSynthesizer != null &&
            coreInit.ttsController != null &&
            coreInit.textOutputAvailability !is CapabilityAvailability.Unavailable
    }

    private fun isCoreReadyDiagnostic(): String {
        val checks = listOf(
            "navigationTtsEngine=${coreInit.navigationTtsEngine != null}",
            "sttTranscriber=${coreInit.sttTranscriber != null}",
            "ttsSynthesizer=${coreInit.ttsSynthesizer != null}",
            "ttsController=${coreInit.ttsController != null}",
            "textOutputAvailability=${coreInit.textOutputAvailability}",
        )
        return checks.joinToString("; ")
    }

    /**
     * Prepare STT: construct the ONNX transcriber, wait for Ready or Failed,
     * then initialize semantic text output.
     */
    private suspend fun prepareStt(): PrepareResultInternal {
        return try {
            val transcriber = coreInit.constructSttTranscriber()
                ?: return PrepareResultInternal.Failed(
                    "STT transcriber construction failed",
                )
            // Wait for STT model Ready or Failed.
            while (true) {
                when (transcriber.modelStatus) {
                    SttModelStatus.Ready -> break
                    SttModelStatus.Failed -> {
                        return PrepareResultInternal.Failed(
                            "STT model failed to load: ${transcriber.loadError ?: "unknown"}",
                        )
                    }
                    else -> delay(STT_MODEL_POLL_MS)
                }
            }
            coreInit.initializeTextOutputCapability()
            PrepareResultInternal.Success(transcriber)
        } catch (e: Exception) {
            PrepareResultInternal.Failed("STT initialization failed: ${e.message}")
        }
    }

    /**
     * Prepare Supertonic: construct the ONNX synthesizer, wait for Ready or Failed,
     * then construct the host diagnostic TTS controller.
     */
    private suspend fun prepareTts(): PrepareResultInternal {
        return try {
            val synthesizer = coreInit.constructTtsSynthesizer()
                ?: return PrepareResultInternal.Failed(
                    "TTS synthesizer construction failed",
                )
            // Wait for TTS model Ready or Failed.
            while (true) {
                when (synthesizer.modelStatus) {
                    TtsModelStatus.Ready -> break
                    TtsModelStatus.Failed -> {
                        return PrepareResultInternal.Failed(
                            "TTS model failed to load: ${synthesizer.loadError ?: "unknown"}",
                        )
                    }
                    else -> delay(TTS_MODEL_POLL_MS)
                }
            }
            coreInit.constructTtsController(synthesizer)
            PrepareResultInternal.SuccessTts(synthesizer)
        } catch (e: Exception) {
            PrepareResultInternal.Failed("TTS initialization failed: ${e.message}")
        }
    }

    private sealed interface PrepareResultInternal {
        data class Success(val transcriber: SttTranscriber) : PrepareResultInternal
        data class SuccessTts(val synthesizer: TtsSynthesizer) : PrepareResultInternal
        data class Failed(val reason: String) : PrepareResultInternal
    }

    companion object {
        private const val STT_MODEL_POLL_MS = 500L
        private const val TTS_MODEL_POLL_MS = 500L
    }
}

internal fun NavigationTtsFailure.BootstrapSetupFailure.toSetupIssue(
    enginePackage: String?,
): OfflineNavigationVoiceIssue = OfflineNavigationVoiceIssue(
    diagnostic = when (this) {
        NavigationTtsFailure.BootstrapSetupFailure.EngineUnavailable ->
            "No Android text-to-speech engine is installed or active."
        is NavigationTtsFailure.BootstrapSetupFailure.EngineInitFailed ->
            "Android text-to-speech engine initialization failed: $reason"
        NavigationTtsFailure.BootstrapSetupFailure.EngineInitTimeout ->
            "Android text-to-speech engine initialization timed out."
        NavigationTtsFailure.BootstrapSetupFailure.VoiceMissing ->
            "Install an offline English voice for the active text-to-speech engine."
        NavigationTtsFailure.BootstrapSetupFailure.VoiceSelectionFailed ->
            "The active text-to-speech engine could not select its offline English voice."
        is NavigationTtsFailure.BootstrapSetupFailure.VoiceProbeFailed ->
            "The offline English voice failed its synthesis probe: $reason"
        NavigationTtsFailure.BootstrapSetupFailure.VoiceProbeTimeout ->
            "The offline English voice synthesis probe timed out."
    },
    enginePackage = enginePackage,
)

/**
 * Interface implemented by [ServiceCoreInitializer] to delegate native engine
 * construction and controller wiring to the initializer, which owns the
 * audio infrastructure, ONNX engines, and capture pipeline.
 */
interface CoreInit {
    val navigationTtsEngine: NavigationTtsEngine?
    val sttTranscriber: SttTranscriber?
    val ttsSynthesizer: TtsSynthesizer?
    val ttsController: TtsController?
    val textOutputAvailability: CapabilityAvailability

    /** Construct the STT ONNX transcriber and return it, or null on failure. */
    fun constructSttTranscriber(): SttTranscriber?

    /** Construct the TTS ONNX synthesizer and return it, or null on failure. */
    fun constructTtsSynthesizer(): TtsSynthesizer?


    /** Construct the TtsController and start its status poller. */
    fun constructTtsController(synthesizer: TtsSynthesizer)


    /** Construct the Journal host controller. */

    /** Initialize host-owned semantic text output without exposing transport state. */
    fun initializeTextOutputCapability()

    /** Construct, select, probe, and retain the Android navigation TTS engine. */
    suspend fun prepareNavigationTts(): PrepareResult

    /** Discard all controllers, pollers, native engines, and navigation TTS for retry. */
    suspend fun discardControllers()
}