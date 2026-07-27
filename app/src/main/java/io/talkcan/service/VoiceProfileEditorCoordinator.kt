package io.talkcan.service

import io.talkcan.audio.AudioRouteEndpoint
import io.talkcan.audio.RecordedPcm
import io.talkcan.audio.TtsController
import io.talkcan.model.InputMode
import io.talkcan.model.TtsStatus
import io.talkcan.voice.ResolvedVoiceProfileWeights
import io.talkcan.voice.VoiceProfileChannelDependency
import io.talkcan.voice.VoiceProfileCatalogue
import io.talkcan.voice.VoiceProfileCodec
import io.talkcan.voice.VoiceProfileDraft
import io.talkcan.voice.VoiceProfileDraftEditor
import io.talkcan.voice.VoiceProfileFailure
import io.talkcan.voice.VoiceProfileId
import io.talkcan.voice.VoiceProfileLimits
import io.talkcan.voice.VoiceProfileMixSource
import io.talkcan.voice.VoiceProfileModelMetadata
import io.talkcan.voice.VoiceProfileMutation
import io.talkcan.voice.VoiceProfileMixer
import io.talkcan.voice.VoiceProfileRepository
import io.talkcan.voice.VoiceProfileTensors
import io.talkcan.voice.VoiceProfileTtlOperation
import io.talkcan.voice.VoiceProfileWeightMode
import io.talkcan.voice.VoiceTensor
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Typed actionable failure published by the voice-profile editor coordinator. */
internal sealed interface VoiceProfileEditorFailure {
    val diagnostic: String

    data class InvalidSourceSelection(override val diagnostic: String) : VoiceProfileEditorFailure

    data class UnavailableSource(
        val profileId: VoiceProfileId,
        override val diagnostic: String,
    ) : VoiceProfileEditorFailure

    data class SourceTensorsUnavailable(
        val profileId: VoiceProfileId,
        override val diagnostic: String,
    ) : VoiceProfileEditorFailure

    data class LatentEditsRequireReset(override val diagnostic: String) : VoiceProfileEditorFailure

    data class InvalidWeights(override val diagnostic: String) : VoiceProfileEditorFailure

    data class NoMaterializedDraft(override val diagnostic: String) : VoiceProfileEditorFailure

    data class OperationRejected(override val diagnostic: String) : VoiceProfileEditorFailure

    data class NoUndoSnapshot(override val diagnostic: String) : VoiceProfileEditorFailure

    /** A repository mutation was refused; the wrapped failure carries the typed cause. */
    data class RepositoryMutation(val failure: VoiceProfileFailure) : VoiceProfileEditorFailure {
        override val diagnostic: String
            get() = failure.diagnostic
    }

    data class PreviewUnavailable(override val diagnostic: String) : VoiceProfileEditorFailure

    /** Deletion refused because channels are assigned; carries resolved display names. */
    data class DeletionRefusedAssigned(
        val profileId: VoiceProfileId,
        val dependentChannels: List<VoiceProfileChannelDependency>,
        override val diagnostic: String,
    ) : VoiceProfileEditorFailure
}

/** Lifecycle phase of one editor preview attempt. */
internal enum class VoiceProfilePreviewPhase {
    IDLE,
    SYNTHESIZING,
    PLAYING,
    ERROR,
}

/** Visible preview state: phase, preview text, and the current-mode route target. */
internal data class VoiceProfilePreviewState(
    val phase: VoiceProfilePreviewPhase = VoiceProfilePreviewPhase.IDLE,
    val text: String = "",
    val targetMode: InputMode? = null,
    val targetEndpoint: AudioRouteEndpoint? = null,
    val diagnostic: String? = null,
) {
    val active: Boolean
        get() = phase == VoiceProfilePreviewPhase.SYNTHESIZING || phase == VoiceProfilePreviewPhase.PLAYING
}

/**
 * Bounded draft projection safe to publish outside the coordinator.
 *
 * [ttl] is the immutable current TTL tensor — a heatmap-safe value whose reads defensively
 * copy; the UI renders it as a regenerated bitmap rather than touching editor internals.
 */
internal data class VoiceProfileDraftSummary(
    val ttl: VoiceTensor,
    val hasEdits: Boolean,
    val canUndo: Boolean,
    val undoDepth: Int,
    val operationCount: Int,
    val baselineOperationCount: Int,
)

/**
 * Stable immutable editor state published to the Activity.
 *
 * Selected source IDs are catalogue-independent stable identities; weights carry both the raw
 * user values and the resolved normalized values; the draft summary carries the current TTL
 * tensor plus undo/edit bounds; preview reports phase, text, and the visible route target;
 * [failure] is the one-shot typed actionable failure slot.
 */
internal data class VoiceProfileEditorState(
    val selectedSourceIds: List<VoiceProfileId> = emptyList(),
    val rawWeights: List<Double> = emptyList(),
    val normalizedWeights: List<Double> = emptyList(),
    val weightMode: VoiceProfileWeightMode = VoiceProfileWeightMode.EQUAL,
    val randomSeed: Long? = null,
    val draft: VoiceProfileDraftSummary? = null,
    val preview: VoiceProfilePreviewState = VoiceProfilePreviewState(),
    val failure: VoiceProfileEditorFailure? = null,
)

/** Synthesis parameters for one preview request, read from current monitor defaults. */
internal data class VoiceProfilePreviewSynthesisDefaults(
    val lang: String,
    val totalSteps: Int,
    val speed: Float,
    val scoRate: Int,
)

/**
 * Service-owned voice-profile mixer/editor coordinator.
 *
 * Publishes one immutable [VoiceProfileEditorState] and accepts scalar intents. Every invalid
 * intent leaves selection, weights, and draft unchanged and only fills the typed failure slot.
 * Source/weight changes materialize a fresh mix baseline through [VoiceProfileMixer]; latent
 * edits after a baseline change require an explicit draft reset and are never replayed.
 *
 * Preview serializes a changed draft to one atomic cache document through [VoiceProfileCodec]
 * — an unchanged draft reuses the existing cache file — then reuses the shared service-owned
 * [TtsController] with a per-request playback callback routed through the current input mode.
 * Preview is lower priority than operational audio: [preemptPreviewForOperationalAudio] is the
 * single explicit preemption boundary invoked before service-owned PTT/capture/channel
 * synthesis/playback acquisition. The shared synthesizer is never closed here.
 */
internal class VoiceProfileEditorCoordinator(
    private val repository: VoiceProfileRepository,
    private val scope: CoroutineScope,
    private val previewCacheFile: File,
    private val ttsController: () -> TtsController?,
    private val currentInputMode: () -> InputMode,
    private val previewPlayback: suspend (RecordedPcm, onRouteAcquired: (AudioRouteEndpoint) -> Unit) -> Boolean,
    private val previewDefaults: () -> VoiceProfilePreviewSynthesisDefaults,
    private val preemptHostPlayback: () -> Unit = {},
    private val currentModel: VoiceProfileModelMetadata = VoiceProfileCodec.CURRENT_MODEL,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val mutationLock = Any()
    private var released = false
    private var loadedSources: List<VoiceProfileMixSource> = emptyList()
    private var draft: VoiceProfileDraft? = null
    private var cachedPreviewTensors: VoiceProfileTensors? = null
    private var previewJob: Job? = null
    private var previewEpoch = 0L

    private val _state = MutableStateFlow(VoiceProfileEditorState())

    /** Read-only editor state; the sole publication path. */
    val state: StateFlow<VoiceProfileEditorState> = _state.asStateFlow()

    /** The repository's published catalogue, passthrough for the Activity. */
    val catalogue: StateFlow<VoiceProfileCatalogue>
        get() = repository.catalogue

    // ── Mixer intents ────────────────────────────────────────────────────────

    /**
     * Stages up to 16 distinct available compatible sources. One source remains a visible
     * selection without creating a mix; selecting the second source materializes the
     * equal-weight baseline. When the current draft carries latent edits, [discardLatentEdits]
     * must be explicit or the intent fails atomically without touching the draft.
     */
    suspend fun selectSources(ids: List<VoiceProfileId>, discardLatentEdits: Boolean = false) {
        if (ids.size > VoiceProfileLimits.MAX_MIX_SOURCES) {
            fail(
                VoiceProfileEditorFailure.InvalidSourceSelection(
                    "A mix accepts at most ${VoiceProfileLimits.MAX_MIX_SOURCES} sources, found ${ids.size}",
                )
            )
            return
        }
        if (ids.toSet().size != ids.size) {
            fail(VoiceProfileEditorFailure.InvalidSourceSelection("Selected voice profile sources must be distinct"))
            return
        }
        val catalogue = repository.currentCatalogue
        val summaries = ids.map { id ->
            catalogue.summaryFor(id) ?: run {
                fail(VoiceProfileEditorFailure.UnavailableSource(id, "Voice profile ${id.value} is not in the catalogue"))
                return
            }
        }
        for (summary in summaries) {
            if (!summary.selectable) {
                fail(
                    VoiceProfileEditorFailure.UnavailableSource(
                        summary.id,
                        "Voice profile ${summary.displayName} is not available for mixing",
                    )
                )
                return
            }
        }
        synchronized(mutationLock) {
            if (draftRequiresReset(discardLatentEdits)) return
        }
        val sources = ArrayList<VoiceProfileMixSource>(summaries.size)
        for (summary in summaries) {
            val tensors = withContext(ioDispatcher) { repository.loadTensors(summary.id) }
            if (tensors == null) {
                fail(
                    VoiceProfileEditorFailure.SourceTensorsUnavailable(
                        summary.id,
                        "Voice profile ${summary.displayName} tensors could not be loaded",
                    )
                )
                return
            }
            sources += VoiceProfileMixSource(summary, tensors)
        }
        if (sources.size < VoiceProfileLimits.MIN_MIX_SOURCES) {
            synchronized(mutationLock) {
                if (draftRequiresReset(discardLatentEdits)) return
                loadedSources = sources
                draft = null
                cachedPreviewTensors = null
                publishDraftLocked(
                    rawWeights = List(sources.size) { 1.0 },
                    weightMode = VoiceProfileWeightMode.EQUAL,
                    randomSeed = null,
                    clearFailure = true,
                )
            }
            return
        }

        val replacement = VoiceProfileMixer.mix(sources, ResolvedVoiceProfileWeights.equal(sources.size))
        synchronized(mutationLock) {
            if (draftRequiresReset(discardLatentEdits)) return
            loadedSources = sources
            draft = replacement
            cachedPreviewTensors = null
            publishDraftLocked(
                rawWeights = List(sources.size) { 1.0 },
                weightMode = VoiceProfileWeightMode.EQUAL,
                randomSeed = null,
                clearFailure = true,
            )
        }
    }

    /** Re-materializes the current selection with equal weights. */
    fun setEqualWeights() {
        synchronized(mutationLock) {
            val sources = loadedSources
            if (sources.isEmpty()) {
                fail(VoiceProfileEditorFailure.NoMaterializedDraft("Select sources before changing weights"))
                return
            }
            if (draftRequiresReset(discardLatentEdits = false)) return
            draft = VoiceProfileMixer.mix(sources, ResolvedVoiceProfileWeights.equal(sources.size))
            cachedPreviewTensors = null
            publishDraftLocked(
                rawWeights = List(sources.size) { 1.0 },
                weightMode = VoiceProfileWeightMode.EQUAL,
                randomSeed = null,
                clearFailure = true,
            )
        }
    }

    /** Re-materializes the current selection with strictly positive raw weights. */
    fun setManualWeights(rawWeights: List<Double>) {
        synchronized(mutationLock) {
            val sources = loadedSources
            if (sources.isEmpty()) {
                fail(VoiceProfileEditorFailure.NoMaterializedDraft("Select sources before changing weights"))
                return
            }
            if (rawWeights.size != sources.size) {
                fail(
                    VoiceProfileEditorFailure.InvalidWeights(
                        "Expected ${sources.size} weights, found ${rawWeights.size}",
                    )
                )
                return
            }
            if (!rawWeights.all { it.isFinite() && it > 0.0 }) {
                fail(VoiceProfileEditorFailure.InvalidWeights("Every weight must be finite and strictly positive"))
                return
            }
            if (draftRequiresReset(discardLatentEdits = false)) return
            draft = VoiceProfileMixer.mix(sources, ResolvedVoiceProfileWeights.manual(rawWeights))
            cachedPreviewTensors = null
            publishDraftLocked(
                rawWeights = rawWeights,
                weightMode = VoiceProfileWeightMode.MANUAL,
                randomSeed = null,
                clearFailure = true,
            )
        }
    }

    /** Re-materializes the current selection with deterministic seeded-random weights. */
    fun setRandomWeights(seed: Long) {
        synchronized(mutationLock) {
            val sources = loadedSources
            if (sources.isEmpty()) {
                fail(VoiceProfileEditorFailure.NoMaterializedDraft("Select sources before changing weights"))
                return
            }
            if (draftRequiresReset(discardLatentEdits = false)) return
            val random = Random(seed)
            val rawWeights = List(sources.size) { 1.0 - random.nextDouble() }
            draft = VoiceProfileMixer.mix(sources, ResolvedVoiceProfileWeights.random(sources.size, seed))
            cachedPreviewTensors = null
            publishDraftLocked(
                rawWeights = rawWeights,
                weightMode = VoiceProfileWeightMode.RANDOM,
                randomSeed = seed,
                clearFailure = true,
            )
        }
    }

    // ── Latent-operation intents ─────────────────────────────────────────────

    /** Applies one TTL operation transactionally; a non-finite candidate leaves the draft unchanged. */
    fun applyOperation(operation: VoiceProfileTtlOperation) {
        synchronized(mutationLock) {
            val existing = draft ?: run {
                fail(VoiceProfileEditorFailure.NoMaterializedDraft("Materialize a mix before applying operations"))
                return
            }
            val next = try {
                VoiceProfileDraftEditor.apply(existing, operation)
            } catch (error: IllegalArgumentException) {
                fail(VoiceProfileEditorFailure.OperationRejected(error.message ?: "Operation rejected"))
                return
            }
            draft = next
            cachedPreviewTensors = null
            publishDraftLocked(clearFailure = true)
        }
    }

    /** Reverts the most recent operation; fails without changes when no snapshot exists. */
    fun undo() {
        synchronized(mutationLock) {
            val existing = draft ?: run {
                fail(VoiceProfileEditorFailure.NoMaterializedDraft("Materialize a mix before undoing operations"))
                return
            }
            if (!existing.canUndo) {
                fail(VoiceProfileEditorFailure.NoUndoSnapshot("No operation to undo"))
                return
            }
            draft = VoiceProfileDraftEditor.undo(existing)
            cachedPreviewTensors = null
            publishDraftLocked(clearFailure = true)
        }
    }

    /** Resets the draft to its mixed baseline, discarding every latent operation. */
    fun reset() {
        synchronized(mutationLock) {
            val existing = draft ?: run {
                fail(VoiceProfileEditorFailure.NoMaterializedDraft("Materialize a mix before resetting"))
                return
            }
            draft = VoiceProfileDraftEditor.reset(existing)
            cachedPreviewTensors = null
            publishDraftLocked(clearFailure = true)
        }
    }

    // ── Profile management intents ───────────────────────────────────────────

    /** Saves the current draft as a brand-new custom profile under a freshly allocated ID. */
    suspend fun saveDraftAsNew(displayName: String): Boolean {
        val materialized = synchronized(mutationLock) {
            draft ?: run {
                fail(VoiceProfileEditorFailure.NoMaterializedDraft("Materialize a mix before saving"))
                return false
            }
        }
        val result = withContext(ioDispatcher) {
            repository.saveAsNew(materialized.current, materialized.provenance, displayName)
        }
        return handleMutation(result)
    }

    /** Renames a custom profile; identity and tensors are unchanged. */
    suspend fun renameProfile(id: VoiceProfileId, newDisplayName: String): Boolean {
        val result = withContext(ioDispatcher) { repository.rename(id, newDisplayName) }
        return handleMutation(result)
    }

    /** Deletes a custom profile unless a channel is assigned to it. */
    suspend fun deleteProfile(id: VoiceProfileId): Boolean {
        val result = withContext(ioDispatcher) { repository.delete(id) }
        val success = handleMutation(result)
        if (success) {
            synchronized(mutationLock) {
                if (loadedSources.any { it.summary.id == id }) {
                    // The selection referenced a deleted profile; the materialized draft is
                    // still valid audio but its source list is no longer displayable, so the
                    // editor returns to the empty selection rather than a ghost source set.
                    loadedSources = emptyList()
                    draft = null
                    cachedPreviewTensors = null
                    publishDraftLocked(
                        rawWeights = emptyList(),
                        weightMode = VoiceProfileWeightMode.EQUAL,
                        randomSeed = null,
                        clearFailure = true,
                    )
                }
            }
        }
        return success
    }

    /** Imports a bounded profile document stream; the stream crosses the boundary, never a path. */
    suspend fun importProfile(input: InputStream, displayName: String): Boolean {
        val result = withContext(ioDispatcher) { repository.importFromStream(input, displayName) }
        return handleMutation(result)
    }

    /** Exports any available profile as canonical nested JSON to the given stream. */
    suspend fun exportProfile(id: VoiceProfileId, output: OutputStream): Boolean {
        val result = withContext(ioDispatcher) { repository.exportToStream(id, output) }
        return handleMutation(result)
    }

    // ── Preview ──────────────────────────────────────────────────────────────

    /**
     * Starts a preview of the current draft through the current input mode's playback route.
     * A changed draft is serialized atomically to the preview cache first; an unchanged draft
     * reuses the existing cache file. The shared [TtsController] performs synthesis with a
     * per-request playback callback; the visible route target is published as it is acquired.
     */
    fun requestPreview(text: String) {
        val controller = ttsController()
        synchronized(mutationLock) {
            if (released) return
            val existing = draft ?: run {
                fail(VoiceProfileEditorFailure.NoMaterializedDraft("Materialize a mix before previewing"))
                return
            }
            if (controller == null) {
                fail(VoiceProfileEditorFailure.PreviewUnavailable("TTS controller unavailable"))
                return
            }
            val previewText = text.trim().ifEmpty { DEFAULT_PREVIEW_TEXT }
            val defaults = previewDefaults()
            previewEpoch += 1
            val epoch = previewEpoch
            previewJob?.cancel()
            _state.update {
                it.copy(
                    preview = VoiceProfilePreviewState(
                        phase = VoiceProfilePreviewPhase.SYNTHESIZING,
                        text = previewText,
                        targetMode = currentInputMode(),
                    ),
                    failure = null,
                )
            }
            previewJob = scope.launch {
                runPreview(epoch, controller, existing, previewText, defaults)
            }.also { job ->
                job.invokeOnCompletion {
                    synchronized(mutationLock) {
                        if (previewJob === job) previewJob = null
                    }
                }
            }
        }
    }

    /** Cancels any active preview work and releases its controller route. */
    suspend fun exitEditor() {
        val pendingJob = synchronized(mutationLock) {
            previewEpoch += 1
            val pending = previewJob
            previewJob = null
            pending
        }
        pendingJob?.cancel()
        if (pendingJob != null) {
            ttsController()?.cancelAndRelease()
        }
        pendingJob?.join()
        synchronized(mutationLock) {
            _state.update { it.copy(preview = VoiceProfilePreviewState()) }
        }
    }

    /** Cancels preview work without awaiting termination; editor-exit joins through [exitEditor]. */
    fun cancelPreview() {
        synchronized(mutationLock) {
            previewEpoch += 1
            previewJob?.cancel()
            previewJob = null
            ttsController()?.cancelActive()
            _state.update { it.copy(preview = VoiceProfilePreviewState()) }
        }
    }

    /**
     * The one explicit operational-audio preemption boundary.
     *
     * Service-owned PTT/capture/channel synthesis/playback acquisition invokes this before it
     * contends for the host audio admission or the shared synthesizer. It cancels editor
     * preview synthesis, synchronously releases preview-owned host playback admission through
     * [preemptHostPlayback], and schedules deterministic controller cleanup — without closing
     * the shared synthesizer and without touching operational playback or unrelated UI state.
     */
    fun preemptPreviewForOperationalAudio() {
        val controller = ttsController()
        val hadActivePreview = synchronized(mutationLock) {
            val active = previewJob != null || _state.value.preview.phase != VoiceProfilePreviewPhase.IDLE
            previewEpoch += 1
            previewJob?.cancel()
            previewJob = null
            if (active) controller?.cancelActive()
            if (_state.value.preview.phase != VoiceProfilePreviewPhase.IDLE) {
                _state.update { it.copy(preview = VoiceProfilePreviewState()) }
            }
            active
        }
        preemptHostPlayback()
        if (hadActivePreview) {
            scope.launch { controller?.cancelAndRelease() }
        }
    }

    /** Service-teardown release: cancels jobs, releases preview audio, and deletes the cache. */
    fun release() {
        synchronized(mutationLock) {
            released = true
            previewEpoch += 1
            previewJob?.cancel()
            previewJob = null
            loadedSources = emptyList()
            draft = null
            cachedPreviewTensors = null
        }
        ttsController()?.cancelActive()
        preemptHostPlayback()
        runCatching { if (previewCacheFile.exists()) previewCacheFile.delete() }
        _state.value = VoiceProfileEditorState()
    }

    /** Clears the one-shot failure slot. */
    fun acknowledgeFailure() {
        _state.update { it.copy(failure = null) }
    }

    // ── Internals ────────────────────────────────────────────────────────────

    private fun draftRequiresReset(discardLatentEdits: Boolean): Boolean {
        // Caller holds mutationLock.
        val existing = draft
        if (existing != null && existing.hasLatentEdits && !discardLatentEdits) {
            fail(
                VoiceProfileEditorFailure.LatentEditsRequireReset(
                    "Changing sources or weights after latent edits requires an explicit draft reset",
                )
            )
            return true
        }
        return false
    }

    private fun publishDraftLocked(
        rawWeights: List<Double>? = null,
        weightMode: VoiceProfileWeightMode? = null,
        randomSeed: Long? = null,
        clearFailure: Boolean,
    ) {
        // Caller holds mutationLock.
        val currentDraft = draft
        val effectiveMode = weightMode ?: _state.value.weightMode
        val effectiveSeed =
            if (effectiveMode == VoiceProfileWeightMode.RANDOM) {
                randomSeed ?: _state.value.randomSeed
            } else {
                null
            }
        _state.update { previous ->
            previous.copy(
                selectedSourceIds = loadedSources.map { source -> source.summary.id },
                rawWeights = rawWeights ?: previous.rawWeights,
                normalizedWeights =
                    currentDraft?.provenance?.sources?.map { source -> source.normalizedWeight }
                        ?: emptyList(),
                weightMode = effectiveMode,
                randomSeed = effectiveSeed,
                draft = currentDraft?.let(::draftSummary),
                failure = if (clearFailure) null else previous.failure,
            )
        }
    }

    private fun draftSummary(source: VoiceProfileDraft) =
        VoiceProfileDraftSummary(
            ttl = source.current.ttl,
            hasEdits = source.hasLatentEdits,
            canUndo = source.canUndo,
            undoDepth = source.undoSnapshots.size,
            operationCount = source.provenance.operations.size,
            baselineOperationCount = source.baselineOperationCount,
        )

    private fun handleMutation(result: VoiceProfileMutation): Boolean =
        when (result) {
            is VoiceProfileMutation.Saved, is VoiceProfileMutation.Deleted -> {
                _state.update { it.copy(failure = null) }
                true
            }
            is VoiceProfileMutation.Failed -> {
                val editorFailure = when (val cause = result.failure) {
                    is VoiceProfileFailure.ProfileAssigned -> VoiceProfileEditorFailure.DeletionRefusedAssigned(
                        profileId = cause.id,
                        dependentChannels = cause.dependentChannels,
                        diagnostic = cause.diagnostic,
                    )
                    else -> VoiceProfileEditorFailure.RepositoryMutation(cause)
                }
                fail(editorFailure)
                false
            }
        }

    private suspend fun runPreview(
        epoch: Long,
        controller: TtsController,
        previewDraft: VoiceProfileDraft,
        previewText: String,
        defaults: VoiceProfilePreviewSynthesisDefaults,
    ) {
        val cachePath = try {
            ensurePreviewCache(previewDraft.current)
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            publishPreview(epoch) {
                VoiceProfilePreviewState(
                    phase = VoiceProfilePreviewPhase.ERROR,
                    text = previewText,
                    targetMode = it.targetMode,
                    targetEndpoint = it.targetEndpoint,
                    diagnostic = "Preview cache serialization failed: ${error.message}",
                )
            }
            return
        }
        val synthesisJob = controller.synthesize(
            text = previewText,
            voiceStylePath = cachePath,
            lang = defaults.lang,
            totalSteps = defaults.totalSteps,
            speed = defaults.speed,
            scoRate = defaults.scoRate,
            playbackOverride = { recording ->
                publishPreview(epoch) { it.copy(phase = VoiceProfilePreviewPhase.PLAYING) }
                previewPlayback(recording) { endpoint ->
                    publishPreview(epoch) { it.copy(targetEndpoint = endpoint) }
                }
            },
        )
        // Join exactly this request: the shared controller status flow conflates rapid
        // transitions and carries other callers' states, so the joined job's terminal status
        // read is the deterministic preview outcome.
        synthesisJob.join()
        when (val terminal = controller.status.value) {
            is TtsStatus.Error -> publishPreview(epoch) {
                VoiceProfilePreviewState(
                    phase = VoiceProfilePreviewPhase.ERROR,
                    text = previewText,
                    targetMode = it.targetMode,
                    targetEndpoint = it.targetEndpoint,
                    diagnostic = terminal.reason,
                )
            }
            else -> publishPreview(epoch) {
                VoiceProfilePreviewState(
                    phase = VoiceProfilePreviewPhase.IDLE,
                    text = previewText,
                    targetMode = it.targetMode,
                    targetEndpoint = it.targetEndpoint,
                )
            }
        }
    }

    /**
     * Serializes the draft tensors atomically only when they differ from the cached preview;
     * an unchanged draft with a live cache file reuses it without rewriting.
     */
    private suspend fun ensurePreviewCache(tensors: VoiceProfileTensors): String =
        withContext(ioDispatcher) {
            val reusable = synchronized(mutationLock) { cachedPreviewTensors == tensors }
            if (reusable && previewCacheFile.isFile) {
                return@withContext previewCacheFile.absolutePath
            }
            val json = VoiceProfileCodec.encode(tensors, currentModel)
            writeAtomically(previewCacheFile, json)
            synchronized(mutationLock) { cachedPreviewTensors = tensors }
            previewCacheFile.absolutePath
        }

    private fun writeAtomically(target: File, content: String) {
        val parent = checkNotNull(target.parentFile) { "Preview cache file must live in a directory" }
        if (!parent.exists() && !parent.mkdirs()) {
            throw java.io.IOException("Could not create preview cache directory: ${parent.path}")
        }
        val temp = File(parent, "${target.name}.tmp")
        try {
            FileOutputStream(temp).use { out ->
                out.write(content.toByteArray(Charsets.UTF_8))
                out.fd.sync()
            }
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (error: Exception) {
            temp.delete()
            throw error
        }
    }

    private fun publishPreview(epoch: Long, transform: (VoiceProfilePreviewState) -> VoiceProfilePreviewState) {
        synchronized(mutationLock) {
            if (epoch != previewEpoch) return
            _state.update { it.copy(preview = transform(it.preview)) }
        }
    }

    private fun fail(failure: VoiceProfileEditorFailure) {
        _state.update { it.copy(failure = failure) }
    }

    private companion object {
        const val DEFAULT_PREVIEW_TEXT = "Voice profile preview."
    }
}
