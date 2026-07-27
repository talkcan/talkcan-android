package io.talkcan.service

import android.content.Context
import io.talkcan.audio.DefaultTextToSpeechFactory
import io.talkcan.audio.ModelVerifier
import io.talkcan.audio.NavigationTtsEngine
import io.talkcan.audio.NavigationTtsFailure
import io.talkcan.audio.PcmTranscriber
import io.talkcan.audio.PrepareResult
import io.talkcan.audio.RecordedPcm
import io.talkcan.audio.StateLossCallback
import io.talkcan.audio.SttTranscriber
import io.talkcan.audio.TranscriptionService
import io.talkcan.audio.TtsController
import io.talkcan.audio.TtsSynthesizer
import io.talkcan.audio.onnx.ParakeetOnnxTranscriber
import io.talkcan.audio.onnx.SupertonicOnnxSynthesizer
import io.talkcan.channel.SleepwalkerTextOutputService
import io.talkcan.channel.TextOutputAvailability
import io.talkcan.channel.capability.CapabilityAvailability
import io.talkcan.channel.capability.CapabilityUnavailableReason
import io.talkcan.channel.capability.SpeechSynthesisParameters
import io.talkcan.channel.capability.SynthesisCapabilityAdapter
import io.talkcan.channel.capability.TranscriptionCapabilityAdapter
import io.talkcan.channel.capability.CapabilityScopeIdentity
import io.talkcan.channel.capability.SynthesisCapability
import io.talkcan.channel.capability.TranscriptionCapability
import io.talkcan.model.ChannelCatalogueSnapshot
import io.talkcan.model.SttModelStatus
import io.talkcan.model.TtsModelStatus
import io.talkcan.model.TtsStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Narrow status-update callback. The initializer publishes model and TTS
 * status changes through this lambda so the service can fold them into its
 * [MonitorState] projection. No [io.talkcan.model.AppState]
 * reference is exposed.
 */
internal fun interface ModelStatusSink {
    fun publish(update: ModelStatusUpdate)
}

/**
 * Batched model/controller status update. Only non-null fields are applied.
 */
internal data class ModelStatusUpdate(
    val sttModelStatus: SttModelStatus? = null,
    val ttsModelStatus: TtsModelStatus? = null,
    val ttsStatus: TtsStatus? = null,
)

/** Constructs the ONNX STT transcriber from its model directory. */
internal fun interface SttFactory {
    fun create(modelDir: File): SttTranscriber?
}

/** Constructs the ONNX TTS synthesizer from its model directory. */
internal fun interface TtsFactory {
    fun create(modelDir: File): TtsSynthesizer?
}

/** Constructs the host diagnostic [TtsController] for a prepared synthesizer. */
internal fun interface TtsControllerFactory {
    fun create(
        scope: CoroutineScope,
        synthesizer: TtsSynthesizer,
        play: suspend (RecordedPcm) -> Boolean,
    ): TtsController
}

/** Constructs a [TranscriptionService] wrapping a transcriber. */
internal fun interface TranscriptionServiceFactory {
    fun create(transcriber: SttTranscriber): TranscriptionService
}

/** Supplies a fallback [PcmTranscriber] when STT is unavailable. */
internal fun interface PcmTranscriberFallback {
    fun create(): PcmTranscriber
}

/** Constructs the Android navigation TTS engine. */
internal fun interface NavigationTtsEngineFactory {
    fun create(context: Context, stateLossCallback: StateLossCallback): NavigationTtsEngine
}

/** Constructs the journal controller from a transcriber. */

/** Factory for the STT model-status poller job. */
internal fun interface SttPollerFactory {
    fun create(
        transcriber: SttTranscriber,
        scope: CoroutineScope,
        pollMs: Long,
        onStatus: (SttModelStatus) -> Unit,
    ): Job
}

/** Factory for the TTS model-status and controller-status poller jobs. */
internal fun interface TtsPollerFactory {
    fun create(
        synthesizer: TtsSynthesizer,
        controller: TtsController,
        scope: CoroutineScope,
        pollMs: Long,
        onModelStatus: (TtsModelStatus) -> Unit,
        onTtsStatus: (TtsStatus) -> Unit,
    ): Job
}

// ---- Production default factories ----

internal object DefaultSttFactory : SttFactory {
    override fun create(modelDir: File): SttTranscriber? {
        return try {
            ParakeetOnnxTranscriber(modelDir = modelDir)
        } catch (err: Throwable) {
            TalkcanLogger.w(TAG, "STT transcriber unavailable: ${err.message}")
            null
        }
    }
}

internal object DefaultTtsFactory : TtsFactory {
    override fun create(modelDir: File): TtsSynthesizer? {
        return try {
            SupertonicOnnxSynthesizer(modelDir = modelDir)
        } catch (err: Throwable) {
            TalkcanLogger.w(TAG, "TTS synthesizer unavailable: ${err.message}")
            null
        }
    }
}

internal val DefaultTtsControllerFactory = TtsControllerFactory { scope, synthesizer, play ->
    TtsController(scope = scope, synthesizer = synthesizer, play = play)
}

internal val DefaultTranscriptionServiceFactory = TranscriptionServiceFactory { transcriber ->
    TranscriptionService(transcriber)
}

internal val DefaultPcmTranscriberFallback = PcmTranscriberFallback {
    object : PcmTranscriber {
        override suspend fun transcribe(pcm: ShortArray, sampleRate: Int): String {
            throw IllegalStateException("STT transcriber unavailable")
        }
    }
}

internal val DefaultNavigationTtsEngineFactory = NavigationTtsEngineFactory { context, stateLossCallback ->
    NavigationTtsEngine(
        context = context,
        factory = DefaultTextToSpeechFactory(context),
        stateLossCallback = stateLossCallback,
    )
}


internal val DefaultSttPollerFactory = SttPollerFactory { transcriber, scope, pollMs, onStatus ->
    scope.launch {
        var lastStatus: SttModelStatus? = null
        while (true) {
            val status = transcriber.modelStatus
            if (status != lastStatus) {
                lastStatus = status
                onStatus(status)
            }
            delay(pollMs)
        }
    }
}

internal val DefaultTtsPollerFactory = TtsPollerFactory { synthesizer, controller, scope, pollMs, onModelStatus, onTtsStatus ->
    // One owner job: cancellation and join cover both the model-status
    // poller and the controller-status collector.
    scope.launch {
        launch {
            var lastModelStatus: TtsModelStatus? = null
            while (true) {
                val status = synthesizer.modelStatus
                if (status != lastModelStatus) {
                    lastModelStatus = status
                    onModelStatus(status)
                }
                delay(pollMs)
            }
        }
        launch {
            controller.status.collect { status ->
                onTtsStatus(status)
            }
        }
    }
}

private const val TAG = "ServiceCoreInitializer"

/**
 * Core native-resource initializer. Owns STT/TTS/journal/navigation-TTS
 * controller references, model directories, model-status polling jobs, and
 * the journal-storage-backend registry. Implements [CoreInit] for
 * [BootstrapCoordinator].
 *
 * Construction results, model-path/status polling, bootstrap ordering,
 * cancellation/release order, navigation replacement, and text-output
 * availability projection are identical to the prior service-local
 * implementation. The initializer receives no [android.app.Service]
 * reference and no mutable
 * [io.talkcan.model.AppState] — status changes flow out
 * through [ModelStatusSink] and navigation state-loss through
 * [StateLossCallback].
 *
 * [discardControllers] is the retry-discard boundary and [shutdown] is the
 * idempotent service-teardown boundary — both safe after partial
 * initialization. Release follows dependency order: pollers and controller
 * work are cancelled and joined, published engine/controller/service
 * references are detached, then each constructed engine is closed exactly
 * once. The process-shared ONNX environment is never closed.
 */
internal class ServiceCoreInitializer(
    private val context: Context,
    private val scope: CoroutineScope,
    private val filesDirProvider: () -> File,
    private val textOutputService: SleepwalkerTextOutputService,
    private val channelCatalogue: () -> ChannelCatalogueSnapshot,
    private val modelStatusSink: ModelStatusSink,
    private val navigationStateLoss: StateLossCallback,
    private val hostAudioPlay: suspend (RecordedPcm) -> Boolean,
    private val sttFactory: SttFactory = DefaultSttFactory,
    private val ttsFactory: TtsFactory = DefaultTtsFactory,
    private val ttsControllerFactory: TtsControllerFactory = DefaultTtsControllerFactory,
    private val transcriptionServiceFactory: TranscriptionServiceFactory = DefaultTranscriptionServiceFactory,
    private val pcmTranscriberFallback: PcmTranscriberFallback = DefaultPcmTranscriberFallback,
    private val navigationTtsEngineFactory: NavigationTtsEngineFactory = DefaultNavigationTtsEngineFactory,
    private val sttPollerFactory: SttPollerFactory = DefaultSttPollerFactory,
    private val ttsPollerFactory: TtsPollerFactory = DefaultTtsPollerFactory,
    private val sttModelPollMs: Long = 500L,
    private val ttsModelPollMs: Long = 500L,
) : CoreInit {

    override var navigationTtsEngine: NavigationTtsEngine? = null
        private set
    override var sttTranscriber: SttTranscriber? = null
        private set
    override var ttsSynthesizer: TtsSynthesizer? = null
        private set
    override var ttsController: TtsController? = null
        private set

    /** Model directory for the Supertonic synthesizer (null until constructed). */
    var supertonicModelDir: File? = null
        private set

    /** Model directory for the Parakeet transcriber (null until constructed). */
    var sttModelDir: File? = null
        private set

    /** Transcription service (null until STT is constructed). */
    var transcriptionService: TranscriptionService? = null
        private set

    private var sttModelStatusJob: Job? = null
    private var ttsModelStatusJob: Job? = null

    override val textOutputAvailability: CapabilityAvailability
        get() = when (textOutputService.availability.value) {
            TextOutputAvailability.Available -> CapabilityAvailability.Available
            TextOutputAvailability.Preparing,
            TextOutputAvailability.Unavailable -> CapabilityAvailability.Recoverable
            TextOutputAvailability.Closed -> CapabilityAvailability.Unavailable(
                CapabilityUnavailableReason.HOST_NOT_READY,
            )
        }

    // ---- CoreInit construction ----

    override fun constructSttTranscriber(): SttTranscriber? {
        val modelDir = File(filesDirProvider(), ModelVerifier.PARAKEET_DIR)
        sttModelDir = modelDir
        val transcriber = sttFactory.create(modelDir) ?: return null
        sttTranscriber = transcriber
        transcriptionService = transcriptionServiceFactory.create(transcriber)
        sttModelStatusJob = sttPollerFactory.create(
            transcriber = transcriber,
            scope = scope,
            pollMs = sttModelPollMs,
        ) { status ->
            modelStatusSink.publish(ModelStatusUpdate(sttModelStatus = status))
        }
        return transcriber
    }

    override fun constructTtsSynthesizer(): TtsSynthesizer? {
        val modelDir = File(filesDirProvider(), ModelVerifier.SUPERTONIC_DIR)
        supertonicModelDir = modelDir
        val synth = ttsFactory.create(modelDir) ?: return null
        ttsSynthesizer = synth
        return synth
    }

    override fun constructTtsController(synthesizer: TtsSynthesizer) {
        val controller = ttsControllerFactory.create(
            scope = scope,
            synthesizer = synthesizer,
            play = hostAudioPlay,
        )
        ttsController = controller
        ttsModelStatusJob = ttsPollerFactory.create(
            synthesizer = synthesizer,
            controller = controller,
            scope = scope,
            pollMs = ttsModelPollMs,
            onModelStatus = { status ->
                modelStatusSink.publish(ModelStatusUpdate(ttsModelStatus = status))
            },
            onTtsStatus = { status ->
                modelStatusSink.publish(ModelStatusUpdate(ttsStatus = status))
            },
        )
    }


    override fun initializeTextOutputCapability() {
        // The text-output service is constructed by the service before
        // bootstrap. This is a readiness gate, not a construction step.
    }

    override suspend fun prepareNavigationTts(): PrepareResult {
        navigationTtsEngine?.shutdown()
        navigationTtsEngine = null
        val engine = navigationTtsEngineFactory.create(context, navigationStateLoss)
        return try {
            when (val result = engine.prepare()) {
                is PrepareResult.Success -> {
                    navigationTtsEngine = engine
                    result
                }
                is PrepareResult.Failure -> {
                    engine.shutdown()
                    result
                }
            }
        } catch (error: CancellationException) {
            engine.shutdown()
            throw error
        } catch (error: Exception) {
            engine.shutdown()
            PrepareResult.Failure(
                NavigationTtsFailure.BootstrapSetupFailure.EngineInitFailed(
                    error.message ?: "Unable to initialize Android text-to-speech",
                ),
            )
        }
    }

    // ---- Discard / shutdown ----

    /**
     * Retry-discard: cancel and join pollers, release controllers after
     * their work terminates, detach published references, and close each
     * constructed engine exactly once. Safe to call after partial
     * initialization.
     */
    override suspend fun discardControllers() {
        releaseCoreResources()
    }

    /**
     * Idempotent service-teardown boundary. Performs the same release as
     * [discardControllers]. Safe to call multiple times — subsequent calls
     * are no-ops because all references are already null.
     *
     * This does NOT cancel [scope] (the service owns the scope lifecycle)
     * and does NOT close [textOutputService] (owned by the service).
     */
    suspend fun shutdown() {
        releaseCoreResources()
    }

    private suspend fun releaseCoreResources() {
        navigationTtsEngine?.shutdown()
        navigationTtsEngine = null
        // 1. Stop pollers and controller work first, and await termination,
        //    so no worker can observe a closing engine or publish status
        //    after teardown starts.
        sttModelStatusJob?.cancelAndJoin()
        sttModelStatusJob = null
        ttsModelStatusJob?.cancelAndJoin()
        ttsModelStatusJob = null
        ttsController?.cancelAndRelease()
        // 2. Detach published references so repeated shutdown is a no-op
        //    and the service cannot reach retired engines.
        ttsController = null
        val retiredTranscriber = sttTranscriber
        val retiredSynthesizer = ttsSynthesizer
        sttTranscriber = null
        ttsSynthesizer = null
        transcriptionService = null
        // 3. Close each engine generation exactly once, in dependency
        //    order. Engines release only their own sessions; the
        //    process-shared ONNX environment is never closed here.
        retiredTranscriber?.close()
        retiredSynthesizer?.close()
    }

    // ---- Narrow capability accessors for capability host / runtime composition ----

    /** Transcription capability adapter, or null if STT is not yet constructed. */
    fun transcriptionCapability(identity: CapabilityScopeIdentity): TranscriptionCapability? =
        transcriptionService?.let { TranscriptionCapabilityAdapter(it, identity) }

    /**
     * Synthesis capability adapter, or null if TTS is not yet constructed.
     *
     * Only the semantic voice ID `default` is supported; every other runtime voice ID
     * keeps unsupported/not-configured behavior and is never interpreted as a profile
     * ID or filesystem path. For `default`, [voiceStylePath] is consulted with the
     * requesting identity inside the per-request resolver callback — never at runtime
     * construction — so host preference changes affect the next synthesis request
     * without replacing the capability or its runtime generation, while an in-flight
     * request keeps the path it already resolved. The [totalSteps] supplier reads
     * current monitor state from the service without exposing it.
     */
    fun synthesisCapability(
        identity: CapabilityScopeIdentity,
        voiceStylePath: (CapabilityScopeIdentity) -> String?,
        totalSteps: () -> Int,
    ): SynthesisCapability? = ttsSynthesizer?.let { synthesizer ->
        SynthesisCapabilityAdapter(synthesizer, { voice ->
            if (voice.id != "default") {
                null
            } else {
                voiceStylePath(identity)?.let { path ->
                    SpeechSynthesisParameters(
                        voiceStylePath = path,
                        totalSteps = totalSteps(),
                    )
                }
            }
        }, identity)
    }

    /**
     * Journal storage capability adapter, or null if the journal controller
     * or channel configuration is unavailable. Reads the channel catalogue
     * snapshot to resolve the journal base directory.
     */
}