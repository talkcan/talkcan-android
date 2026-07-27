package io.talkcan.audio

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Short test phrase used by the bootstrap and recovery synthesis probes.
 */
private const val PROBE_UTTERANCE: String = "Talkcan"




// ---------------------------------------------------------------------------
// NavigationTtsEngine: lifecycle, callback routing, synthesis, recovery
// ---------------------------------------------------------------------------

/**
 * The Android navigation TTS engine: owns the `TextToSpeech` instance
 * lifecycle, routes `UtteranceProgressListener` callbacks through a
 * two-dimension ownership model (engine epoch + attempt/utterance identity,
 * and navigation generation), performs `synthesizeToFile`-only synthesis
 * to transient cache files, normalizes WAV output to 16 kHz mono PCM16,
 * and implements bounded runtime recovery with at-most-once
 * stop/shutdown semantics.
 *
 * **Callback ownership model (D4):**
 *
 * 1. **Engine-instance epoch + attempt/utterance registry**: each
 *    `synthesizeToFile` call carries a unique [UtteranceId]. The engine
 *    strongly owns the live `TextToSpeech` instance and maintains a
 *    monotonically increasing [EngineEpoch]. A callback is valid only if its
 *    utteranceId matches a registered pending call AND the issuing
 *    instance's epoch matches the current live instance epoch. Callbacks
 *    from a shut-down (old-epoch) instance or an unregistered utteranceId
 *    are rejected (ignored).
 *
 * 2. **Navigation generation**: when a callback is valid by dimension 1,
 *    its result is delivered to the owning operation. For navigation
 *    synthesis, the result is delivered only if the owning navigation
 *    generation is still the current [navigationGeneration]. For
 *    bootstrap/recovery probes, the result is delivered to the owning
 *    bootstrap/recovery attempt regardless of `navigationGeneration`.
 *
 * **Lifecycle invariants:**
 *
 * - `synthesizeToFile` only, never `speak()`, never `AudioAttributes`.
 * - Each transient file is a unique regular file in `Context.cacheDir`,
 *   deleted immediately after reading or on any terminal path.
 * - `stop()` and `shutdown()` are called at most once each per instance.
 * - Bounded recovery: at most one reinitialization + re-probe + retry per
 *   independent failure chain. Newer requests during recovery coalesce
 *   (update authoritative newest without cancelling the recovery).
 *
 * **Usage:**
 * - Bootstrap calls [prepare] as a prerequisite gate before STT/Supertonic
 *   init. On [PrepareResult.Success], the retained instance is available for
 *   runtime synthesis.
 * - Runtime calls [synthesize] for each navigation announcement. The method
 *   implements latest-wins supersession and bounded recovery internally.
 * - Service teardown calls [shutdown] to release the instance.
 *
 * @param context Android context for cache directory access.
 * @param factory injectable factory seam for constructing `TextToSpeech`.
 * @param config timeout configuration.
 * @param stateLossCallback invoked when the engine detects a condition
 *   requiring the bootstrap layer to transition to `NeedsSetup`.
 */
class NavigationTtsEngine(
    private val context: Context,
    private val factory: TextToSpeechFactory,
    private val config: NavigationTtsConfig = NavigationTtsConfig(),
    private val stateLossCallback: StateLossCallback = StateLossCallback { _, _ -> },
) {
    private val cacheDir: File get() = context.cacheDir

    // Engine state — guarded by stateMutex
    private val stateMutex = Mutex()

    @Volatile
    private var liveTts: TextToSpeech? = null

    @Volatile
    private var liveEpoch: EngineEpoch = EngineEpoch.initial

    @Volatile
    private var navigationGeneration: NavigationGeneration = NavigationGeneration.fresh()

    // Callback registry — utteranceId → PendingOperation. Accessed under
    // stateMutex for registration/unregistration; the listener callback
    // reads/writes under stateMutex too.
    private val pendingOps = mutableMapOf<String, PendingOperation>()

    // At-most-once shutdown flag per live instance. stop() does NOT use a
    // flag — it is called on every supersession and is idempotent via
    // try/catch (the engine tolerates redundant stop calls). Only shutdown()
    // is at-most-once to handle the cancel/shutdown race (8.6): once an
    // instance is shut down, no further stop/shutdown shall be issued on it.
    private val instanceShutDown = AtomicBoolean(false)

    // Recovery state — guarded by recoveryMutex
    private val recoveryMutex = Mutex()

    @Volatile
    private var recovering: Boolean = false

    // Coalesced pending request during recovery: the text and generation
    // of the newest request. The recovery retry synthesizes this when it
    // completes. A CompletableDeferred allows coalesced callers to suspend
    // and receive the recovery's retry result (not a failure).
    @Volatile
    private var pendingDuringRecoveryText: String? = null

    @Volatile
    private var pendingDuringRecoveryGen: NavigationGeneration? = null

    private var recoveryResultDeferred: CompletableDeferred<NavigationSynthesisResult>? = null
    private var recoveryRetrySuppressed: Boolean = false

    // The play callback for the newest coalesced request during recovery.
    // The recovery retry synthesizes the newest text and plays the result
    // through this callback — not through the original (Alpha) caller's
    // play lambda. Updated each time a newer request coalesces.
    private var recoveryPlayCallback: (suspend (RecordedPcm) -> Unit)? = null

    // Renderer-owned request orchestration (D7): the engine owns a scope and
    // a single activeJob. request() advances generation → stop → cancelAndJoin
    // prior job → launch new synthesis+playback job. This guarantees the
    // required supersession ordering atomically and avoids the caller needing
    // to manage job cancellation. Recovery is NOT cancelled by new requests
    // (coalescing).
    private val requestMutex = Mutex()
    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var activeJob: Job? = null
    private var externalPlaybackJob: Job? = null

    private val attemptCounter = AtomicLong(0L)

    // Dedicated scope for UtteranceProgressListener callbacks. The TTS
    // engine fires callbacks from a binder thread; we launch into this
    // scope to avoid blocking the binder thread with runBlocking.
    private val callbackScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // -----------------------------------------------------------------------
    // Bootstrap prerequisite: construct + init + voice-select + probe
    // -----------------------------------------------------------------------

    /**
     * Bootstrap prerequisite gate: construct and initialize the Android TTS
     * engine, discover and select an offline English voice, and perform a
     * real synthesis probe.
     *
     * On [PrepareResult.Success], the probed `TextToSpeech` instance is
     * retained for runtime synthesis. On [PrepareResult.Failure], the
     * instance has been shut down and the [failure] carries the specific
     * setup-phase failure category that the bootstrap layer SHALL map to
     * `NeedsSetup`.
     *
     * This method is cancellable: on cancellation during init, voice
     * discovery, or probe, the instance is shut down and no late callbacks
     * are processed (tasks 1.5, 2.7, 3.7).
     *
     * Cold boot performs exactly one mandatory synthesis probe (D3). Zero
     * eager navigation announcement syntheses occur during bootstrap.
     */
    suspend fun prepare(): PrepareResult {
        return try {
            val attempt = AttemptToken.fresh()
            val tts = when (val initResult = constructAndInit(attempt)) {
                is PrepareResult.Success -> initResult.tts
                is PrepareResult.Failure -> return initResult
            }

            // Wire the callback listener before voice selection and probe so
            // probe callbacks are routed through the ownership model.
            setListener(tts)

            val voiceResult = selectOfflineEnglishVoice(tts)
            when (voiceResult) {
                is VoiceSelectionResult.Missing -> {
                    shutdownInstance(tts)
                    return PrepareResult.Failure(
                        NavigationTtsFailure.BootstrapSetupFailure.VoiceMissing,
                        tts.defaultEngine,
                    )
                }
                is VoiceSelectionResult.SelectionFailed -> {
                    shutdownInstance(tts)
                    return PrepareResult.Failure(
                        NavigationTtsFailure.BootstrapSetupFailure.VoiceSelectionFailed,
                        tts.defaultEngine,
                    )
                }
                is VoiceSelectionResult.Selected -> { /* proceed to probe */ }
            }

            val probeResult = probe(attempt, tts)
            when (probeResult) {
                is ProbeOutcome.Success -> {
                    publishLiveInstance(tts)
                    PrepareResult.Success(tts)
                }
                is ProbeOutcome.ImmediateError -> {
                    shutdownInstance(tts)
                    PrepareResult.Failure(
                        NavigationTtsFailure.BootstrapSetupFailure.VoiceProbeFailed(
                            "synthesizeToFile returned ERROR",
                        ),
                        tts.defaultEngine,
                    )
                }
                is ProbeOutcome.CallbackError -> {
                    shutdownInstance(tts)
                    PrepareResult.Failure(
                        NavigationTtsFailure.BootstrapSetupFailure.VoiceProbeFailed("onError callback"),
                        tts.defaultEngine,
                    )
                }
                is ProbeOutcome.Timeout -> {
                    stopInstance(tts)
                    shutdownInstance(tts)
                    PrepareResult.Failure(
                        NavigationTtsFailure.BootstrapSetupFailure.VoiceProbeTimeout,
                        tts.defaultEngine,
                    )
                }
                is ProbeOutcome.InvalidOutput -> {
                    shutdownInstance(tts)
                    PrepareResult.Failure(
                        NavigationTtsFailure.BootstrapSetupFailure.VoiceProbeFailed(
                            "invalid output: ${probeResult.reason}",
                        ),
                        tts.defaultEngine,
                    )
                }
            }
        } catch (e: CancellationException) {
            // Cancellation during prepare — ensure any partially constructed
            // instance is shut down. No late callbacks are processed.
            cleanupOnCancel()
            throw e
        }
    }

    // -----------------------------------------------------------------------
    // Runtime navigation request — renderer-owned orchestration (D7)
    // -----------------------------------------------------------------------

    /**
     * Request a navigation announcement: synthesize [text] and deliver the
     * normalized PCM to [play]. This is the single atomic entry point for
     * runtime navigation synthesis.
     *
     * The engine owns the request orchestration (D7):
     * 1. Advance the navigation generation (authoritative before cancel).
     * 2. Call `TextToSpeech.stop()` to interrupt the prior utterance.
     * 3. `cancelAndJoin` the prior [activeJob] (only when not in recovery).
     * 4. Launch the new synthesis+playback job as [activeJob].
     *
     * During `Recovering` state (bounded recovery active), newer requests
     * coalesce: they update only the authoritative generation and
     * latest-pending text without cancelling or restarting the recovery.
     * The caller suspends and receives the recovery's retry result — if the
     * retry succeeds, the coalesced caller's [play] receives the PCM through
     * the recovery's retry. The coalesced request does NOT return a failure.
     *
     * @param text the resolved navigation phrase text (from the live
     *   catalogue at request time, per D6).
     * @param play the playback lambda that receives the normalized
     *   [RecordedPcm] and routes it through the existing app-owned playback
     *   path (HostAudioCoordinator.play). Called only on success.
     * @return the synthesis result. [NavigationSynthesisResult.Success] on
     *   valid PCM delivery; [NavigationSynthesisResult.EngineServiceFailure]
     *   on engine/service failure (with recovery attempted); or
     *   [NavigationSynthesisResult.InfrastructureFailure] on
     *   renderer/format/file failure.
     */
    suspend fun request(text: String, play: suspend (RecordedPcm) -> Unit): NavigationSynthesisResult {
        val deferred = requestMutex.withLock {
            var coalesced: CompletableDeferred<NavigationSynthesisResult>? = null
            var generation: NavigationGeneration? = null
            var priorJob: Job? = null
            var priorExternalJob: Job? = null
            stateMutex.withLock {
                if (recovering) {
                    val nextGeneration = NavigationGeneration.fresh()
                    navigationGeneration = nextGeneration
                    pendingDuringRecoveryText = text
                    pendingDuringRecoveryGen = nextGeneration
                    recoveryRetrySuppressed = false
                    // Store the newest play callback — the recovery retry
                    // plays through this, not through Alpha's original lambda.
                    recoveryPlayCallback = play
                    coalesced = recoveryResultDeferred ?: CompletableDeferred<NavigationSynthesisResult>()
                        .also { recoveryResultDeferred = it }
                } else {
                    val nextGeneration = NavigationGeneration.fresh()
                    navigationGeneration = nextGeneration
                    stopLiveInstance()
                    generation = nextGeneration
                    priorJob = activeJob
                    activeJob = null
                }
                priorExternalJob = externalPlaybackJob
                externalPlaybackJob = null
            }

            priorExternalJob?.cancelAndJoin()
            coalesced?.let { return@withLock it }

            priorJob?.cancelAndJoin()
            stateMutex.withLock { recoveryPlayCallback = play }
            val result = CompletableDeferred<NavigationSynthesisResult>()
            val currentGeneration = checkNotNull(generation)
            val job = engineScope.launch {
                val currentJob = coroutineContext[Job]
                try {
                    val synthesis = runSynthesis(text, currentGeneration, isRecoveryRetry = false)
                    if (synthesis is NavigationSynthesisResult.Success) play(synthesis.pcm)
                    result.complete(synthesis)
                } catch (error: CancellationException) {
                    result.cancel(error)
                    throw error
                } catch (error: Exception) {
                    result.complete(
                        NavigationSynthesisResult.InfrastructureFailure(
                            NavigationTtsFailure.RendererInfrastructureFailure.FileIoFailure(
                                "playback error: ${error.message}",
                            ),
                        ),
                    )
                } finally {
                    stateMutex.withLock {
                        if (activeJob === currentJob) activeJob = null
                    }
                }
            }
            stateMutex.withLock { activeJob = job }
            result
        }

        // Coalesced callers observe the one recovery result but never replay its PCM. The
        // original active job owns the generic playback callback and delivers the newest retry.
        return deferred.await()
    }

    suspend fun requestPcm(
        recording: RecordedPcm,
        play: suspend (RecordedPcm) -> Unit,
    ): NavigationSynthesisResult {
        val deferred = requestMutex.withLock {
            var recoveryInFlight = false
            var priorJob: Job? = null
            var priorExternalJob: Job? = null
            stateMutex.withLock {
                navigationGeneration = NavigationGeneration.fresh()
                stopLiveInstance()
                priorExternalJob = externalPlaybackJob
                externalPlaybackJob = null
                recoveryPlayCallback = null
                if (recovering) {
                    recoveryRetrySuppressed = true
                    pendingDuringRecoveryText = null
                    pendingDuringRecoveryGen = navigationGeneration
                    recoveryInFlight = true
                } else {
                    priorJob = activeJob
                    activeJob = null
                }
            }

            priorExternalJob?.cancelAndJoin()
            priorJob?.cancelAndJoin()
            val result = CompletableDeferred<NavigationSynthesisResult>()
            val job = engineScope.launch {
                val currentJob = coroutineContext[Job]
                try {
                    play(recording)
                    result.complete(NavigationSynthesisResult.Success(recording))
                } catch (error: CancellationException) {
                    result.cancel(error)
                    throw error
                } catch (error: Exception) {
                    result.complete(
                        NavigationSynthesisResult.InfrastructureFailure(
                            NavigationTtsFailure.RendererInfrastructureFailure.FileIoFailure(
                                "playback error: ${error.message}",
                            ),
                        ),
                    )
                } finally {
                    stateMutex.withLock {
                        if (recoveryInFlight) {
                            if (externalPlaybackJob === currentJob) externalPlaybackJob = null
                        } else if (activeJob === currentJob) {
                            activeJob = null
                        }
                    }
                }
            }
            stateMutex.withLock {
                if (recoveryInFlight) externalPlaybackJob = job else activeJob = job
            }
            result
        }
        return deferred.await()
    }

    // -----------------------------------------------------------------------
    // Internal synthesis
    // -----------------------------------------------------------------------

    private suspend fun runSynthesis(
        text: String,
        gen: NavigationGeneration,
        isRecoveryRetry: Boolean,
    ): NavigationSynthesisResult {
        val tts = liveTts
            ?: return NavigationSynthesisResult.EngineServiceFailure(
                NavigationTtsFailure.EngineServiceFailure.SynthesisError("no live instance"),
                exhausted = isRecoveryRetry,
            )

        val epoch = liveEpoch
        val file = createTransientFile(epoch)
        val utteranceId = UtteranceId.forNavigation(epoch, gen)

        val result = CompletableDeferred<CallbackTerminal>()
        val op = PendingOperation(
            epoch = epoch,
            utteranceId = utteranceId,
            generation = gen,
            file = file,
            result = result,
        )

        stateMutex.withLock {
            pendingOps[utteranceId.value] = op
        }

        val rc = try {
            tts.synthesizeToFile(text, Bundle.EMPTY, file, utteranceId.value)
        } catch (e: Exception) {
            cancelAndUnregisterOp(utteranceId, file)
            return classifyInfrastructureFailure(e)
        }

        // Immediate ERROR return: do NOT wait for callback (4.7, 4.8).
        if (rc == TextToSpeech.ERROR) {
            cancelAndUnregisterOp(utteranceId, file)
            return if (isRecoveryRetry) {
                // Same-chain exhaustion: retryable Failed, no second recovery (4.10).
                NavigationSynthesisResult.EngineServiceFailure(
                    NavigationTtsFailure.EngineServiceFailure.SynthesisError("synthesizeToFile ERROR on retry"),
                    exhausted = true,
                )
            } else {
                // First failure in a new chain — trigger bounded recovery (14.1).
                triggerRecovery(text, gen)
            }
        }

        // SUCCESS: queue acceptance only — wait for onDone/onError (4.7).
        return try {
            val callbackTerminal = withTimeout(config.synthesisTimeoutMs) {
                result.await()
            }
            if (callbackTerminal == CallbackTerminal.Rejected) {
                return NavigationSynthesisResult.Superseded
            }
            if (callbackTerminal == CallbackTerminal.Error) {
                return if (isRecoveryRetry) {
                    NavigationSynthesisResult.EngineServiceFailure(
                        NavigationTtsFailure.EngineServiceFailure.SynthesisError("onError on retry"),
                        exhausted = true,
                    )
                } else {
                    triggerRecovery(text, gen)
                }
            }
            // onDone fired — validate output.
            if (!isCurrentGeneration(gen) && !isRecoveryRetry) {
                // Stale navigation generation callback — reject (6.3).
                deleteFile(file)
                return NavigationSynthesisResult.Superseded
            }

            // Read, decode, normalize, and validate the WAV output (D5).
            val pcm = when (val normResult = normalizeWavToScoPcm(file)) {
                is NormalizeResult.Success -> normResult.pcm
                is NormalizeResult.Failure -> {
                    deleteFile(file)
                    return NavigationSynthesisResult.InfrastructureFailure(normResult.failure)
                }
            }

            // Delete transient file immediately after reading PCM (4.4).
            deleteFile(file)

            if (pcm.samples.isEmpty()) {
                return NavigationSynthesisResult.InfrastructureFailure(
                    NavigationTtsFailure.RendererInfrastructureFailure.EmptyPcm,
                )
            }

            NavigationSynthesisResult.Success(pcm)
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            // Terminal-callback timeout (4.9).
            stopLiveInstance()
            cancelAndUnregisterOp(utteranceId, file)
            if (isRecoveryRetry) {
                // Same-chain exhaustion (4.10).
                NavigationSynthesisResult.EngineServiceFailure(
                    NavigationTtsFailure.EngineServiceFailure.SynthesisTimeout,
                    exhausted = true,
                )
            } else {
                triggerRecovery(text, gen)
            }
        } catch (e: CancellationException) {
            // Caller cancelled — stop, clean up, no PCM delivery (4.6).
            stopLiveInstance()
            cancelAndUnregisterOp(utteranceId, file)
            throw e
        } catch (e: Exception) {
            cancelAndUnregisterOp(utteranceId, file)
            classifyInfrastructureFailure(e)
        }
    }

    // -----------------------------------------------------------------------
    // Bounded recovery (D11)
    // -----------------------------------------------------------------------

    /**
     * Trigger one bounded recovery: shut down the current instance, construct
     * a new one, re-probe, and retry only the newest pending announcement.
     *
     * Newer navigation requests arriving during recovery coalesce (update
     * authoritative newest without cancelling the recovery) and suspend on
     * the recovery's result deferred. On successful re-probe, the retry
     * synthesizes the current newest generation and delivers PCM through the
     * recovery's own play path.
     *
     * If the re-probe fails (missing/unusable voice), [StateLossCallback] is
     * invoked to signal `NeedsSetup`. If the re-probe succeeds but the retry
     * synthesis fails, [NavigationSynthesisResult.EngineServiceFailure] with
     * [exhausted] = true is returned (retryable `BootstrapState.Failed`).
     *
     * Only one recovery is active at a time. The bound is one recovery per
     * independent failure chain, not one per process lifetime (14.6).
     */
    private suspend fun triggerRecovery(
        originalText: String,
        originalGen: NavigationGeneration,
    ): NavigationSynthesisResult {
        val recoveryDeferred = recoveryMutex.withLock {
            if (recovering) {
                // Already recovering — coalesce into the active recovery.
                stateMutex.withLock {
                    pendingDuringRecoveryText = originalText
                    pendingDuringRecoveryGen = originalGen
                }
                // Suspend on the existing recovery deferred.
                return recoveryResultDeferred?.await()
                    ?: NavigationSynthesisResult.EngineServiceFailure(
                        NavigationTtsFailure.EngineServiceFailure.SynthesisError("recovery race"),
                        exhausted = false,
                    )
            }
            recovering = true
            stateMutex.withLock {
                pendingDuringRecoveryText = originalText
                pendingDuringRecoveryGen = originalGen
                recoveryRetrySuppressed = false
                val d = CompletableDeferred<NavigationSynthesisResult>()
                recoveryResultDeferred = d
                d
            }
        }

        var recoveryCandidate: TextToSpeech? = null
        try {
            // Shut down current instance — invalidates old epoch (D11 step 2).
            val oldTts = liveTts
            if (oldTts != null) {
                stopInstance(oldTts)
                shutdownInstance(oldTts)
            }
            liveTts = null

            // Construct + init + voice-select + probe new instance.
            val attempt = AttemptToken.fresh()
            val newTts = when (val initResult = constructAndInit(attempt)) {
                is PrepareResult.Success -> initResult.tts
                is PrepareResult.Failure -> {
                    recovering = false
                    stateLossCallback.onStateLoss(initResult.failure, initResult.enginePackage)
                    val result = NavigationSynthesisResult.EngineServiceFailure(
                        NavigationTtsFailure.EngineServiceFailure.SynthesisError(
                            "recovery init failed: ${initResult.failure}",
                        ),
                        exhausted = true,
                    )
                    recoveryDeferred.complete(result)
                    recoveryResultDeferred = null
                    return result
                }
            }
            recoveryCandidate = newTts

            // Wire the callback listener before voice selection and probe so
            // recovery probe callbacks are routed through the ownership model.
            setListener(newTts)

            val voiceResult = selectOfflineEnglishVoice(newTts)
            when (voiceResult) {
                is VoiceSelectionResult.Missing -> {
                    shutdownInstance(newTts)
                    recovering = false
                    stateLossCallback.onStateLoss(
                        NavigationTtsFailure.BootstrapSetupFailure.VoiceMissing,
                        newTts.defaultEngine,
                    )
                    val result = NavigationSynthesisResult.EngineServiceFailure(
                        NavigationTtsFailure.EngineServiceFailure.SynthesisError("recovery: voice missing"),
                        exhausted = true,
                    )
                    recoveryDeferred.complete(result)
                    recoveryResultDeferred = null
                    return result
                }
                is VoiceSelectionResult.SelectionFailed -> {
                    shutdownInstance(newTts)
                    recovering = false
                    stateLossCallback.onStateLoss(
                        NavigationTtsFailure.BootstrapSetupFailure.VoiceSelectionFailed,
                        newTts.defaultEngine,
                    )
                    val result = NavigationSynthesisResult.EngineServiceFailure(
                        NavigationTtsFailure.EngineServiceFailure.SynthesisError("recovery: voice selection failed"),
                        exhausted = true,
                    )
                    recoveryDeferred.complete(result)
                    recoveryResultDeferred = null
                    return result
                }
                is VoiceSelectionResult.Selected -> { /* proceed */ }
            }

            val probeResult = probe(attempt, newTts)
            when (probeResult) {
                is ProbeOutcome.Success -> { /* proceed to handoff */ }
                else -> {
                    shutdownInstance(newTts)
                    recovering = false
                    val failure = when (probeResult) {
                        is ProbeOutcome.ImmediateError ->
                            NavigationTtsFailure.BootstrapSetupFailure.VoiceProbeFailed(
                                "recovery probe: synthesizeToFile ERROR",
                            )
                        is ProbeOutcome.CallbackError ->
                            NavigationTtsFailure.BootstrapSetupFailure.VoiceProbeFailed("recovery probe: onError")
                        is ProbeOutcome.Timeout ->
                            NavigationTtsFailure.BootstrapSetupFailure.VoiceProbeTimeout
                        is ProbeOutcome.InvalidOutput ->
                            NavigationTtsFailure.BootstrapSetupFailure.VoiceProbeFailed(
                                "recovery probe: invalid output: ${probeResult.reason}",
                            )
                        is ProbeOutcome.Success -> error("unreachable")
                    }
                    stateLossCallback.onStateLoss(failure, newTts.defaultEngine)
                    val result = NavigationSynthesisResult.EngineServiceFailure(
                        NavigationTtsFailure.EngineServiceFailure.SynthesisError("recovery probe failed"),
                        exhausted = true,
                    )
                    recoveryDeferred.complete(result)
                    recoveryResultDeferred = null
                    return result
                }
            }

            // Atomic handoff under stateMutex (D11 step 5 / 14.11):
            // publish recovered instance, clear recovery, snapshot newest
            // pending, and prepare the retry generation — all before
            // releasing the mutex. This prevents a request from enqueuing
            // between recovery completion and retry launch.
            val retry = stateMutex.withLock {
                val newEpoch = EngineEpoch.next()
                liveTts = newTts
                liveEpoch = newEpoch
                instanceShutDown.set(false)
                setListener(newTts)
                recovering = false

                val suppressed = recoveryRetrySuppressed
                recoveryRetrySuppressed = false
                val text = pendingDuringRecoveryText ?: originalText
                val retryGeneration = NavigationGeneration.fresh()
                if (!suppressed) navigationGeneration = retryGeneration
                pendingDuringRecoveryText = null
                pendingDuringRecoveryGen = null
                if (suppressed) null else text to retryGeneration
            }
            recoveryCandidate = null

            if (retry == null) {
                val result = NavigationSynthesisResult.Superseded
                recoveryDeferred.complete(result)
                recoveryResultDeferred = null
                return result
            }

            val retryResult = runSynthesis(retry.first, retry.second, isRecoveryRetry = true)
            // Play the retry PCM through the newest coalesced caller's play
            // callback (recoveryPlayCallback), NOT through the original
            // (Alpha) caller's play lambda. The original caller receives
            // Superseded so its engineScope.launch block does not replay.
            val playCallback = stateMutex.withLock { recoveryPlayCallback }
            var retryPlaybackDelivered = false
            if (retryResult is NavigationSynthesisResult.Success && playCallback != null) {
                try {
                    playCallback(retryResult.pcm)
                    retryPlaybackDelivered = true
                } catch (e: CancellationException) {
                    recoveryDeferred.complete(NavigationSynthesisResult.Superseded)
                    recoveryResultDeferred = null
                    stateMutex.withLock { recoveryPlayCallback = null }
                    throw e
                } catch (e: Exception) {
                    // Playback failure is an infrastructure failure.
                    val infraResult = NavigationSynthesisResult.InfrastructureFailure(
                        NavigationTtsFailure.RendererInfrastructureFailure.FileIoFailure(
                            "recovery playback error: ${e.message}",
                        ),
                    )
                    recoveryDeferred.complete(infraResult)
                    recoveryResultDeferred = null
                    stateMutex.withLock { recoveryPlayCallback = null }
                    return infraResult
                }
            }
            // Complete the recovery deferred so coalesced callers resume.
            // They receive the result but do NOT replay — the retry already
            // played through recoveryPlayCallback.
            recoveryDeferred.complete(retryResult)
            recoveryResultDeferred = null
            stateMutex.withLock { recoveryPlayCallback = null }
            return if (retryPlaybackDelivered) {
                NavigationSynthesisResult.Superseded
            } else {
                retryResult
            }
        } catch (error: CancellationException) {
            if (!recovering) {
                // Recovery already published the replacement instance. Cancellation now
                // supersedes only the retry; the next request must reuse that live engine.
                recoveryDeferred.complete(NavigationSynthesisResult.Superseded)
                recoveryResultDeferred = null
                throw error
            }

            // Teardown/discard before handoff owns the partially initialized instance.
            recovering = false
            val partial = recoveryCandidate ?: liveTts
            if (partial != null) {
                stopInstance(partial)
                shutdownInstance(partial)
                if (liveTts === partial) liveTts = null
            }
            recoveryDeferred.completeExceptionally(error)
            recoveryResultDeferred = null
            throw error
        } catch (e: Exception) {
            recovering = false
            val result = NavigationSynthesisResult.EngineServiceFailure(
                NavigationTtsFailure.EngineServiceFailure.SynthesisError("recovery exception: ${e.message}"),
                exhausted = true,
            )
            recoveryDeferred.complete(result)
            recoveryResultDeferred = null
            return result
        } finally {
            // Always clear the recovery play callback — it's only valid for
            // the single retry attempt. If recovery failed or was cancelled,
            // the callback should not linger. Use NonCancellable so cleanup
            // completes even during cancellation.
            withContext(NonCancellable) {
                stateMutex.withLock { recoveryPlayCallback = null }
            }
        }
    }

    // -----------------------------------------------------------------------
    // Engine construction and init
    // -----------------------------------------------------------------------

    /**
     * Construct a `TextToSpeech` instance and await `onInit(SUCCESS)`.
     *
     * On `onInit(ERROR)` or negative status, returns [EngineInitFailed].
     * On init timeout, returns [EngineInitTimeout] and shuts down the instance.
     * On cancellation during init, calls `shutdown()` on the instance and
     * does not process any late `onInit` callback (task 1.5).
     */
    private suspend fun constructAndInit(
        @Suppress("UNUSED_PARAMETER") attempt: AttemptToken,
    ): PrepareResult {
        val initDeferred = CompletableDeferred<Int>()

        val tts = factory.create(TextToSpeech.OnInitListener { status ->
            initDeferred.complete(status)
        })

        // Reset the at-most-once shutdown flag for the new instance.
        instanceShutDown.set(false)
        val epoch = EngineEpoch.next()

        return try {
            val status = withTimeout(config.initTimeoutMs) {
                initDeferred.await()
            }
            if (status == TextToSpeech.SUCCESS) {
                // Check engine availability (task 1.4): verify the default
                // engine package is present, not just that the engines list
                // is non-empty. An empty list or absent default engine
                // package means no usable engine is installed.
                val defaultEngine = tts.defaultEngine
                val engines = tts.engines
                if (defaultEngine == null || engines.isNullOrEmpty() || engines.none { it.name == defaultEngine }) {
                    shutdownInstance(tts)
                    return PrepareResult.Failure(
                        NavigationTtsFailure.BootstrapSetupFailure.EngineUnavailable,
                        defaultEngine,
                    )
                }
                liveEpoch = epoch
                PrepareResult.Success(tts)
            } else {
                shutdownInstance(tts)
                PrepareResult.Failure(
                    NavigationTtsFailure.BootstrapSetupFailure.EngineInitFailed("onInit status: $status"),
                    tts.defaultEngine,
                )
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            shutdownInstance(tts)
            PrepareResult.Failure(
                NavigationTtsFailure.BootstrapSetupFailure.EngineInitTimeout,
                tts.defaultEngine,
            )
        } catch (e: CancellationException) {
            // Cancellation during init — shutdown, no late callback processing.
            shutdownInstance(tts)
            throw e
        }
    }

    // -----------------------------------------------------------------------
    // Probe (bootstrap and recovery)
    // -----------------------------------------------------------------------

    private sealed interface ProbeOutcome {
        data object Success : ProbeOutcome
        data object ImmediateError : ProbeOutcome
        data object CallbackError : ProbeOutcome
        data object Timeout : ProbeOutcome
        data class InvalidOutput(val reason: String) : ProbeOutcome
    }

    /**
     * Perform a real silent synthesis probe: call `synthesizeToFile` with a
     * short test phrase to a transient file, wait for `onDone`, and validate
     * the output is non-empty, decodable, and reports a positive sample count.
     *
     * The probe's `utteranceId` is tagged with the [attempt] token (D4
     * dimension 1), not with any navigation generation. Newer navigation
     * requests arriving during the probe advance `navigationGeneration`
     * (dimension 2) but do NOT invalidate the probe callback (6.6).
     *
     * The probe does NOT declare the voice gate satisfied based on
     * `isLanguageAvailable` or `setVoice` return code alone — the real
     * synthesis probe is mandatory (3.8).
     */
    private suspend fun probe(
        attempt: AttemptToken,
        tts: TextToSpeech,
    ): ProbeOutcome {
        val epoch = liveEpoch
        val file = createTransientFile(epoch)
        val utteranceId = UtteranceId.forProbe(epoch, attemptCounter.incrementAndGet())
        val result = CompletableDeferred<CallbackTerminal>()

        val op = PendingOperation(
            epoch = epoch,
            utteranceId = utteranceId,
            generation = null, // Probe — not tied to navigation generation.
            file = file,
            result = result,
        )

        stateMutex.withLock {
            pendingOps[utteranceId.value] = op
        }

        val rc = try {
            tts.synthesizeToFile(PROBE_UTTERANCE, Bundle.EMPTY, file, utteranceId.value)
        } catch (e: Exception) {
            cancelAndUnregisterOp(utteranceId, file)
            return ProbeOutcome.InvalidOutput("file I/O: ${e.message}")
        }

        // Immediate ERROR return: do NOT wait for callback (4.7, 4.8).
        if (rc == TextToSpeech.ERROR) {
            cancelAndUnregisterOp(utteranceId, file)
            return ProbeOutcome.ImmediateError
        }

        return try {
            val callbackTerminal = withTimeout(config.probeTimeoutMs) {
                result.await()
            }
            if (callbackTerminal != CallbackTerminal.Done) {
                return ProbeOutcome.CallbackError
            }
            // onDone — validate output is non-empty and decodable (3.2).
            if (!file.exists() || file.length() == 0L) {
                deleteFile(file)
                return ProbeOutcome.InvalidOutput("empty file")
            }
            // Decode + normalize to validate the output is real audio.
            when (val normResult = normalizeWavToScoPcm(file)) {
                is NormalizeResult.Success -> {
                    if (normResult.pcm.samples.isEmpty()) {
                        deleteFile(file)
                        ProbeOutcome.InvalidOutput("zero samples")
                    } else {
                        deleteFile(file)
                        ProbeOutcome.Success
                    }
                }
                is NormalizeResult.Failure -> {
                    deleteFile(file)
                    ProbeOutcome.InvalidOutput("decode failure: ${normResult.failure}")
                }
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            // Probe timeout — stop the stalled probe (3.6).
            stopInstance(tts)
            cancelAndUnregisterOp(utteranceId, file)
            ProbeOutcome.Timeout
        } catch (e: CancellationException) {
            // Cancellation during probe — stop, delete file, shut down (3.7).
            stopInstance(tts)
            cancelAndUnregisterOp(utteranceId, file)
            shutdownInstance(tts)
            throw e
        } catch (e: Exception) {
            cancelAndUnregisterOp(utteranceId, file)
            ProbeOutcome.InvalidOutput("exception: ${e.message}")
        }
    }

    // -----------------------------------------------------------------------
    // UtteranceProgressListener — callback routing
    // -----------------------------------------------------------------------

    /**
     * Set the [UtteranceProgressListener] on the given `TextToSpeech` instance.
     *
     * The listener routes callbacks through the two-dimension ownership model:
     *
     * 1. **Epoch + utteranceId validation**: the callback's utteranceId must
     *    match a registered pending operation whose epoch equals the current
     *    live instance epoch. Callbacks from an old (shut-down) instance or
     *    an unregistered utteranceId are rejected — the deferred is completed
     *    with `false` (terminal) so the awaiting coroutine is never stranded
     *    until timeout (6.7).
     *
     * 2. **Navigation generation gating**: for navigation synthesis callbacks
     *    (pending operation has a non-null `generation`), the result is
     *    delivered only if the generation matches the current
     *    `navigationGeneration`. For probe callbacks (generation is null),
     *    the result is delivered regardless of `navigationGeneration` (6.6).
     *    Stale-generation callbacks complete the deferred with `false`
     *    (terminal) and delete the file — the awaiting coroutine treats
     *    this as a rejection, not a strand (6.3).
     *
     * - `onDone`: completes the pending operation's deferred with `true`.
     * - `onError`: completes with `false`.
     * - `onStop`: completes with `false` and deletes the file — treated as a
     *   rejection (not success), but the awaiting coroutine handles `false`
     *   as an error/timeout path, not a stranded wait (6.5).
     */
    private fun setListener(tts: TextToSpeech) {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) { /* no-op */ }

            override fun onDone(utteranceId: String?) {
                routeCallback(utteranceId, CallbackTerminal.Done)
            }

            override fun onError(utteranceId: String?) {
                routeCallback(utteranceId, CallbackTerminal.Error)
            }

            @Deprecated("Deprecated in Java", ReplaceWith("onError(utteranceId)"))
            override fun onError(utteranceId: String?, errorCode: Int) {
                onError(utteranceId)
            }

            override fun onBeginSynthesis(
                utteranceId: String?,
                sampleRate: Int,
                audioFormat: Int,
                channelCount: Int,
            ) {
                // Format metadata is validated from the written WAV file
                // header after onDone (D5). We do not act on this callback
                // beyond allowing it — the file header is authoritative.
            }

            override fun onAudioAvailable(utteranceId: String?, audio: ByteArray?) {
                // Not used — we read the complete file after onDone.
            }

            override fun onStop(utteranceId: String?, interrupted: Boolean) {
                // Discard onStop for interrupted utterances — not a success
                // or failure for the current generation (6.5). Complete the
                // deferred with false (terminal) so the awaiting coroutine is
                // not stranded, and delete the file.
                val id = utteranceId ?: return
                callbackScope.launch {
                    stateMutex.withLock {
                        val op = pendingOps.remove(id)
                        if (op != null && op.epoch == liveEpoch) {
                            // Complete with false so the awaiter is not stranded.
                            op.result.complete(CallbackTerminal.Rejected)
                            deleteFileSafe(op.file)
                        }
                    }
                }
            }
        })
    }

    /**
     * Route a callback (onDone/onError) through the two-dimension ownership model.
     *
     * Stale/old-instance callbacks complete the deferred with `false`
     * (terminal) so the awaiting coroutine is never stranded until timeout.
     */
    private fun routeCallback(utteranceId: String?, terminal: CallbackTerminal) {
        val id = utteranceId ?: return
        callbackScope.launch {
            stateMutex.withLock {
                val op = pendingOps.remove(id)
                if (op == null) return@withLock

                // Dimension 1: epoch must match the current live instance (6.7).
                if (op.epoch != liveEpoch) {
                    // Old-instance or stale callback — complete the deferred
                    // with false (terminal) so the awaiter is not stranded,
                    // then delete the file.
                    op.result.complete(CallbackTerminal.Rejected)
                    deleteFileSafe(op.file)
                    return@withLock
                }

                // Dimension 2: for navigation synthesis, the generation must
                // match the current navigationGeneration (6.2, 6.3). For probes
                // (generation == null), deliver regardless of navigationGeneration (6.6).
                if (op.generation != null && op.generation != navigationGeneration) {
                    // Stale navigation generation callback — complete with false
                    // (terminal) so the awaiter is not stranded, delete file.
                    op.result.complete(CallbackTerminal.Rejected)
                    deleteFileSafe(op.file)
                    return@withLock
                }

                // Valid callback — complete the deferred.
                op.result.complete(terminal)
                if (terminal != CallbackTerminal.Done) {
                    deleteFileSafe(op.file)
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    // Lifecycle: stop, shutdown, cleanup
    // -----------------------------------------------------------------------

    /**
     * Call `TextToSpeech.stop()` on the live instance.
     *
     * stop() is called on every supersession to interrupt the prior utterance.
     * It is idempotent via try/catch — the engine tolerates redundant calls.
     * There is NO at-most-once flag for stop(); the at-most-once invariant
     * applies only to the cancel/shutdown race (8.6), enforced by
     * [shutdownInstance]'s [instanceShutDown] flag.
     */
    private fun stopLiveInstance() {
        val tts = liveTts ?: return
        stopInstance(tts)
    }

    /**
     * Call `TextToSpeech.stop()` on the given instance. Idempotent —
     * the engine tolerates redundant stop calls (some OEM engines throw
     * if already stopped; we swallow that). Not guarded by a flag because
     * stop() is needed on every supersession, not once per instance.
     */
    private fun stopInstance(tts: TextToSpeech) {
        if (instanceShutDown.get()) return // Already shut down — no stop needed.
        try {
            tts.stop()
        } catch (_: Exception) { /* engine may throw if already stopped */ }
    }

    /**
     * Call `TextToSpeech.shutdown()` on the given instance at most once.
     * After shutdown, the instance is unusable — no further synthesis, voice
     * query, stop, or shutdown shall be attempted on it (8.2, 8.6). The
     * [instanceShutDown] flag ensures stop() and shutdown() are not called
     * again after shutdown in the cancel/shutdown race.
     */
    private fun shutdownInstance(tts: TextToSpeech) {
        if (!instanceShutDown.compareAndSet(false, true)) return
        try {
            tts.shutdown()
        } catch (_: Exception) { /* engine may throw if already shut down */ }
    }

    /**
     * Publish the successfully probed instance as the live instance for
     * runtime synthesis, reset the shutdown flag, and wire its
     * [UtteranceProgressListener] (3.3).
     */
    private suspend fun publishLiveInstance(tts: TextToSpeech) {
        stateMutex.withLock {
            liveTts = tts
            liveEpoch = EngineEpoch.next()
            instanceShutDown.set(false)
            setListener(tts)
        }
    }

    /**
     * Cleanup on cancellation during prepare — shut down any partially
     * constructed instance (1.5, 2.7, 3.7).
     */
    private fun cleanupOnCancel() {
        val tts = liveTts
        if (tts != null) {
            stopInstance(tts)
            shutdownInstance(tts)
            liveTts = null
        }
    }

    /**
     * Full shutdown for service teardown or renderer disposal.
     *
     * Calls `stop()` then `shutdown()` on the live instance, clears all
     * pending operations (completing their deferreds terminally), and
     * deletes any transient files. After this call, the engine is
     * unusable (8.2, 8.4).
     */
    suspend fun shutdown() {
        requestMutex.withLock {
            val jobs = stateMutex.withLock {
                val owned = activeJob to externalPlaybackJob
                activeJob = null
                externalPlaybackJob = null
                owned
            }
            jobs.first?.cancelAndJoin()
            jobs.second?.cancelAndJoin()
            stateMutex.withLock {
                val tts = liveTts
                if (tts != null) {
                    stopInstance(tts)
                    shutdownInstance(tts)
                }
                // Clear all pending operations: complete deferreds terminally so
                // no awaiter is stranded, and delete transient files (8.4).
                for (op in pendingOps.values) {
                    op.result.complete(CallbackTerminal.Rejected)
                    deleteFileSafe(op.file)
                }
                pendingOps.clear()
                liveTts = null
                recovering = false
                recoveryRetrySuppressed = false
                pendingDuringRecoveryText = null
                pendingDuringRecoveryGen = null
            }
        }
        // Cancel the callback and engine scopes so no late callbacks or jobs fire.
        callbackScope.cancel()
        engineScope.cancel()
    }

    // -----------------------------------------------------------------------
    // Transient file management
    // -----------------------------------------------------------------------

    /**
     * Create a unique regular file in [Context.cacheDir] for `synthesizeToFile`.
     *
     * The file is a regular file (not a character device, pipe, or
     * `/dev/null`) so that engine seek operations succeed (4.2). The file
     * is in `cacheDir`, not `noBackupFilesDir` — no persistence is desired.
     */
    private fun createTransientFile(epoch: EngineEpoch): File {
        val name = "nav-tts-${epoch.value}-${System.nanoTime()}.wav"
        val file = File(cacheDir, name)
        // Ensure parent exists.
        cacheDir.mkdirs()
        // Create as a regular file.
        if (file.exists()) file.delete()
        file.createNewFile()
        return file
    }

    /**
     * Delete a transient file. Swallows exceptions — file deletion is
     * best-effort cleanup on terminal paths.
     */
    private fun deleteFile(file: File) {
        try {
            if (file.exists()) file.delete()
        } catch (_: Exception) { /* best-effort */ }
    }

    /**
     * Delete a transient file under [NonCancellable] semantics — used in
     * cleanup paths that may run during cancellation (4.6, 8.4).
     */
    private suspend fun deleteFileSafe(file: File) {
        withContext(NonCancellable) {
            deleteFile(file)
        }
    }

    // -----------------------------------------------------------------------
    // Registry helpers
    // -----------------------------------------------------------------------

    /**
     * Unregister an operation from the callback registry and cancel its
     * deferred (complete with false) so the awaiting coroutine is terminal.
     * Then delete the transient file. Used on immediate-error, timeout, and
     * cancellation paths.
     */
    private suspend fun cancelAndUnregisterOp(utteranceId: UtteranceId, file: File) {
        stateMutex.withLock {
            pendingOps.remove(utteranceId.value)
        }
        deleteFile(file)
    }

    private fun isCurrentGeneration(gen: NavigationGeneration): Boolean {
        return gen == navigationGeneration
    }

    // -----------------------------------------------------------------------
    // Failure classification helpers
    // -----------------------------------------------------------------------

    /**
     * Classify an unexpected exception as an infrastructure failure.
     * Engine/voice failures are classified separately in [runSynthesis].
     */
    private fun classifyInfrastructureFailure(e: Exception): NavigationSynthesisResult {
        val failure = when (e) {
            is java.io.IOException ->
                NavigationTtsFailure.RendererInfrastructureFailure.FileIoFailure(e.message ?: "I/O error")
            else ->
                NavigationTtsFailure.RendererInfrastructureFailure.FileIoFailure(e.message ?: "unknown error")
        }
        return NavigationSynthesisResult.InfrastructureFailure(failure)
    }
}