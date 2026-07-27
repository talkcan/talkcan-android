package io.talkcan.lua
import io.talkcan.channel.capability.*
import io.talkcan.dependency.PackageCapability
import io.talkcan.dependency.PackageConfigurationLimits
import io.talkcan.storage.FilesystemOutcome
import io.talkcan.storage.ListCursor
import io.talkcan.storage.ListOptions
import io.talkcan.storage.ListPage
import io.talkcan.storage.MkdirOptions
import io.talkcan.storage.MkdirResult
import io.talkcan.storage.MkdirStatus
import io.talkcan.storage.NodeKind
import io.talkcan.storage.ReadTextOptions
import io.talkcan.storage.ReadTextResult
import io.talkcan.storage.RemoveOptions
import io.talkcan.storage.RemoveResult
import io.talkcan.storage.RemoveStatus
import io.talkcan.storage.StatResult
import io.talkcan.storage.WriteMode
import io.talkcan.storage.WriteResult
import io.talkcan.storage.WriteTextOptions
import io.talkcan.audiofile.AudioExportMode
import io.talkcan.audiofile.AudioExportOptions
import io.talkcan.audiofile.AudioFileFormat
import io.talkcan.audiofile.AudioFileOutcome
import io.talkcan.audiofile.AudioFilePort
import io.talkcan.audiofile.AudioOpenOptions
import io.talkcan.audiofile.ExecutionOwner
import io.talkcan.audiofile.ExecutionOwnerKind
import io.talkcan.audiofile.RecordingHost

import io.talkcan.audio.ChannelAudioInputSession
import io.talkcan.audio.ChannelInputAcceptance
import io.talkcan.audio.ChannelInputResult
import io.talkcan.audio.ChannelInputTarget
import io.talkcan.audio.RecordedPcm
import io.talkcan.lua.actor.ActorRuntime
import io.talkcan.model.ChannelDefinition
import io.talkcan.model.ActorRuntimeHostContext
import io.talkcan.model.Disposable
import io.talkcan.model.GenerationAdmission
import io.talkcan.model.GenerationExecutionContext
import io.talkcan.model.AgentRunId
import io.talkcan.model.AgentOperationId
import io.talkcan.model.ValidatedChannelConfiguration
import io.talkcan.model.referenceState
import io.talkcan.service.ChannelActivationResult
import io.talkcan.service.ChannelExecutionStatus
import io.talkcan.service.ChannelPreparationAvailability
import io.talkcan.service.ChannelPreparationReason
import io.talkcan.service.ChannelRuntime
import io.talkcan.service.ChannelRuntimeSnapshot
import io.talkcan.service.TalkcanLogger
import kotlinx.coroutines.flow.MutableStateFlow
import io.talkcan.lua.actor.ActorGateResult
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import io.talkcan.lua.actor.ActorOperationIdentity
import io.talkcan.lua.actor.ActorOperationToken
import io.talkcan.lua.actor.ActorTaskIdentity
import io.talkcan.lua.actor.ActorTaskId
import io.talkcan.lua.actor.ActorOperationId
import io.talkcan.lua.actor.ActorTerminal
import java.util.UUID
import java.time.Clock
import java.time.Instant

private fun normalizeSemanticAudioError(value: String): String = when (value) {
    "E_INVALID_ARGUMENT", "E_INVALID_VALUE", "E_INVALID_CONTEXT",
    "E_CAPABILITY_UNDECLARED", "E_UNAVAILABLE", "E_BUSY", "E_TIMEOUT",
    "E_CANCELLED", "E_CLOSED", "E_STALE", "E_HOST_FAILURE" -> value
    else -> "E_HOST_FAILURE"
}
private fun ChannelCapabilityScope.isPublicCapabilityDeclared(id: String): Boolean = when (id) {
    PackageCapability.AUDIO_TRANSCRIPTION -> ChannelCapability.Transcription in declaredCapabilities
    PackageCapability.AUDIO_SYNTHESIS -> ChannelCapability.Synthesis in declaredCapabilities
    PackageCapability.AUDIO_PLAYBACK -> ChannelCapability.AudioOperation in declaredCapabilities &&
        ChannelCapability.DeferredAudioPlayback in declaredCapabilities
    PackageCapability.STORAGE_FILES -> ChannelCapability.StorageFiles in declaredCapabilities
    PackageCapability.AUDIO_FILES -> ChannelCapability.AudioFiles in declaredCapabilities
    PackageCapability.KEYBOARD_OUTPUT -> ChannelCapability.TextOutput in declaredCapabilities
    PackageCapability.NETWORK_HTTP -> ChannelCapability.NetworkHttp in declaredCapabilities
    PackageCapability.PROFILES_READ -> ChannelCapability.ProfilesRead in declaredCapabilities
    PackageCapability.SECRETS_READ -> ChannelCapability.SecretsRead in declaredCapabilities
    PackageCapability.WORK_QUEUE -> ChannelCapability.WorkQueue in declaredCapabilities
    else -> false
}

private fun CapabilityAcquisition<*>.normalizedSemanticError(): String = when (this) {
    is CapabilityAcquisition.Recoverable, is CapabilityAcquisition.Unavailable -> "E_UNAVAILABLE"
    is CapabilityAcquisition.Denied -> "E_CAPABILITY_UNDECLARED"
    CapabilityAcquisition.Closed -> "E_CLOSED"
    CapabilityAcquisition.Cancelled -> "E_CANCELLED"
    is CapabilityAcquisition.Failed -> "E_HOST_FAILURE"
    is CapabilityAcquisition.Available -> "E_HOST_FAILURE"
}

private fun CapabilityOperationResult<*>.normalizedSemanticError(): String = when (this) {
    is CapabilityOperationResult.Unavailable -> "E_UNAVAILABLE"
    is CapabilityOperationResult.Denied -> "E_CAPABILITY_UNDECLARED"
    CapabilityOperationResult.Closed -> "E_CLOSED"
    CapabilityOperationResult.Cancelled -> "E_CANCELLED"
    is CapabilityOperationResult.Failed -> "E_HOST_FAILURE"
    is CapabilityOperationResult.Success -> "E_HOST_FAILURE"
}

internal fun interface LuaAudioFilePortFactory {
    fun create(recordings: RecordingHost): AudioFilePort
}
internal fun interface LuaMountReadinessStatus {
    fun status(declarationId: String): String
}

/**
 * 5.3: Atomic readiness cache entry. The ready flag and the bounded preparation
 * request are cached together so a preparation decision can never observe a
 * ready flag from one refresh and a prepare list from another.
 */
private data class CachedReadiness(
    val ready: Boolean,
    val prepare: List<String>,
)


/**
 * A [ChannelRuntime] backed by one Lua actor instance.
 *
 * Owns the actor, bridge, state handle, and lifecycle callback handles from
 * a successfully loaded program image. Manages snapshot projection, readiness
 * caching, input lifecycle, SOS fire-and-forget dispatch, and idempotent close.
 *
 * Construction does not enter Lua; the program image is loaded and callbacks
 * extracted during construction. [activate] invokes startup and lifecycle-ready
 * callbacks. [prepareInput] never enters Lua — it returns from cached readiness.
 * [close] is idempotent and suppresses all late effects.
 */
internal class LuaAdapterRuntime(
    private val definition: ChannelDefinition,
    private val actor: ActorRuntime,
    private val generationContext: GenerationExecutionContext,
    private val stateHandle: LuaStateHandle,
    private val bridge: LuaKernelBridge,
    private val callbacks: Map<String, LuaCallbackHandle>,
    private val logSink: PluginLogSink = NoOpPluginLogSink,
    private val configuration: ValidatedChannelConfiguration,
    private val capabilities: ChannelCapabilityScope,
    private val initialSummary: String? = null,
    private val storagePort: io.talkcan.storage.MountedStoragePort? = null,
    private val clock: Clock = Clock.systemDefaultZone(),
    private val audioFilePortFactory: LuaAudioFilePortFactory? = null,
    private val resourceDeclarations:
        io.talkcan.dependency.PackageResourcesDeclaration =
        io.talkcan.dependency.PackageResourcesDeclaration(emptyList()),
    private val mountReadinessStatus: LuaMountReadinessStatus? = null,
    private val resourceClose: (() -> Unit)? = null,
    private val preparerRegistry: CapabilityPreparerRegistry = CapabilityPreparerRegistry.empty(),
    private val preparationTimeoutMillis: Long = DEFAULT_PREPARATION_TIMEOUT_MILLIS,
    private val keyboardOutputAdapterFactory:
        ((CapabilityScopeIdentity, OutputExecutionOwner) -> KeyboardOutputAdapter)? = null,
    private val dynamicChoiceResolver: io.talkcan.model.DynamicConfigurationChoiceResolver? = null,
    private val requiredDynamicFields:
        List<io.talkcan.model.ChannelConfigurationField.DynamicChoiceField> = emptyList(),
    private val packageRevision: io.talkcan.model.ProviderRevisionFingerprint? = null,
    private val secretReadAdapter: SecretReadHostAdapter? = null,
    private val httpHostAdapter: HttpHostAdapter? = null,
    private val workHostAdapter: WorkHostAdapter? = null,
) : ChannelRuntime {
    @Volatile
    private var audioRegistry: LuaOpaqueAudioRegistry? = null
    @Volatile
    private var recordingHost: LuaRecordingHost? = null
    @Volatile
    private var audioFilePort: AudioFilePort? = null

    // Cached platform mount handles keyed by declaration id, revalidated by the
    // port per operation; cleared on close. The host-side mount token is the
    // validation authority — this cache only avoids re-resolving the grant.
    private val mountHandleCache = java.util.concurrent.ConcurrentHashMap<String, io.talkcan.storage.MountHandle>()

    private val closed = AtomicBoolean(false)
    private val hostGeneration = requireNotNull(generationContext as? ActorRuntimeHostContext) {
        "Lua adapter requires a host-owned actor runtime context"
    }.actorIdentity.runtimeGeneration.value
    private val activated = AtomicBoolean(false)
    private val activationClaimed = AtomicBoolean(false)
    private val lifecycleLock = Any()
    private val logLock = Any()
    private val logs = ArrayDeque<LuaAdapterLogRecord>(MAX_LOG_RECORDS)
    private val localDiagnostics = ArrayDeque<String>(MAX_LOCAL_DIAGNOSTICS)
    private val sleepLock = Any()
    private val activeSleepWaits = mutableSetOf<ActiveSleepWait>()

    private val inputLock = Any()
    private var pendingInputExecution: PendingInputExecution? = null
    private var retiredInputOwnerToken: String? = null
    // Set by host cancellation even if the callback has not yielded yet.
    private val preserveCapturedOnTimeout = AtomicBoolean(false)
    private var inputCancellationRequested = false

    // Task 13.1: set under inputLock when a durable submission commits during
    // handle_input; consumed by the input phase after route/Recording release
    // to launch the callback remainder as a generation-owned task.
    private var detachedInputContinuation: DetachedInputContinuation? = null

    // Task 13.2: completes true once Ready publication has succeeded; false on
    // close or pre-publication activation failure. Startup-admitted worker
    // closures may run before publication, but their WORK_RECEIVE dispatch
    // suspends on this gate so no receive enters the durable subsystem before
    // successful Ready publication.
    private val readyPublication = CompletableDeferred<Boolean>()

    internal val logSnapshot: List<LuaAdapterLogRecord>
        get() = synchronized(logLock) { logs.toList() }

    internal val localDiagnosticSnapshot: List<String>
        get() = synchronized(logLock) { localDiagnostics.toList() }

    // Cached readiness projection: ready flag plus the bounded, duplicate-free
    // preparation request declared by the last valid handle_readiness result.
    // Initially not-ready with no preparation per the spec neutral default.
    // Updated atomically by refreshReadiness -> handle_readiness; read by prepareInput.
    private val cachedReadiness = AtomicReference(CachedReadiness(ready = false, prepare = emptyList()))

    // Monotonic input-preparation attempt token. Bumped when an attempt is
    // released, cancelled, replaced, or closed so a preparation that completes
    // for a terminated attempt is suppressed before any acceptance (5.7).
    private val preparationAttempt = AtomicLong(0)


    private val _snapshot = MutableStateFlow(
        ChannelRuntimeSnapshot(
            id = definition.id,
            name = definition.name,
            implementationId = definition.implementationId,
            enabled = definition.enabled,
            preparation = if (definition.enabled)
                ChannelPreparationAvailability.Recoverable(ChannelPreparationReason.ProviderInitialising)
            else
                ChannelPreparationAvailability.Unavailable(ChannelPreparationReason.Disabled),
            executionStatus = ChannelExecutionStatus.IDLE,
            summary = initialSummary,
        ),
    )

    override val snapshot: StateFlow<ChannelRuntimeSnapshot> = _snapshot.asStateFlow()

    override val id: String get() = definition.id

    override val readinessRefreshIntervalMillis: Long = READINESS_REFRESH_INTERVAL_MILLIS

    private companion object {
        const val MAX_LOG_RECORDS = 128
        const val MAX_LOCAL_DIAGNOSTICS = 32
        const val MAX_SLEEP_SECONDS = 86_400.0
        const val READINESS_REFRESH_INTERVAL_MILLIS = 5_000L
        const val MAX_READINESS_STATUS_BYTES = 256
        const val READINESS_MALFORMED_DIAGNOSTIC = "invalid readiness result"
        // Matches the actor's operation-specific yielded-operation deadline.
        const val SEMANTIC_AUDIO_DEADLINE_MILLIS = 30_000L
        // Default page size for fs.list when the package omits a bounded limit.
        const val FS_DEFAULT_LIST_LIMIT = 100
        const val FS_DIAGNOSTIC_TAG = "TalkcanStorage"
        // 5.1: bound on the optional readiness `prepare` capability-ID list.
        const val MAX_READINESS_PREPARE_REQUESTS = 4
        // 4.4/5.4: per-capability host preparation deadline used for the
        // PrepareRecoverable acquisition. Bounded by the PREPARE_INPUT gate.
        const val DEFAULT_PREPARATION_TIMEOUT_MILLIS = 15_000L
        // 5.1: exact readiness result key vocabulary (ready required, status/prepare optional).
        val ALLOWED_READINESS_KEYS: Set<String> = setOf("ready", "status", "prepare")
        // 5.2: byte bound for a single public capability ID in a prepare list.
        const val MAX_PREPARE_CAPABILITY_ID_BYTES = 64
    }

    // ── Activate ──────────────────────────────────────────────────────────

    /**
     * Runs the synchronous activation sequence:
     * 1. Invoke startup() — non-yielding, non-spawning (except via context).
     * 2. Invoke handle_lifecycle({event = "ready"}) if present.
     * 3. Atomically claim Ready state.
     *
     * On any failure, closes the actor and discards every staged task before
     * transitioning to the failed snapshot state.
     */
    override suspend fun activate(): ChannelActivationResult {
        // Claim activation before touching the actor so concurrent callers can never
        // enter startup twice. The caller that wins the claim owns cleanup for every
        // failure observed before publication; a later call cannot discard work from
        // an already-live generation.
        if (!activationClaimed.compareAndSet(false, true)) {
            return ChannelActivationResult.Failed("Lua runtime activation was already claimed")
        }
        if (closed.get() || !generationContext.isActive() ||
            !actor.stageProgramImage() || !actor.claimProgramImageStartup()
        ) {
            discardStagedAndClose()
            return ChannelActivationResult.Failed("Lua runtime is closed or not staged")
        }

        val config = validatedConfigurationToLua(configuration)
        val startupOutcome = invokeStartupCallback(config).asGeneralCallbackOutcome()
        if (startupOutcome !is CallbackInvocationResult.Success) {
            discardStagedAndClose()
            return ChannelActivationResult.Failed("startup failed: ${startupOutcome.diagnostic()}")
        }

        callbacks["handle_lifecycle"]?.let { lifecycleHandle ->
            val lifecycleOutcome = invokeCallbackHandle(
                lifecycleHandle,
                LuaValue.Map(mapOf("event" to LuaValue.StringValue("ready"))),
            ).asGeneralCallbackOutcome()
            if (lifecycleOutcome !is CallbackInvocationResult.Success) {
                discardStagedAndClose()
                return ChannelActivationResult.Failed(
                    "handle_lifecycle failed: ${lifecycleOutcome.diagnostic()}",
                )
            }
        }

        val registry = LuaOpaqueAudioRegistry(stateHandle)
        val recordings = LuaRecordingHost(registry)
        val filePort = try {
            audioFilePortFactory?.create(recordings)
        } catch (_: Throwable) {
            registry.close()
            discardStagedAndClose()
            return ChannelActivationResult.Failed("audio-file port creation failed")
        }

        // Publish the actor before returning Ready. The registry separately
        // authorizes context-staged tasks only after this activation result.
        if (closed.get() || !generationContext.isActive() || !activated.compareAndSet(false, true) ||
            !actor.publishProgramImageLive()
        ) {
            filePort?.close()
            registry.close()
            discardStagedAndClose()
            return ChannelActivationResult.Failed("Lua runtime closed during activation")
        }
        // Task 13.2: Ready publication succeeded; release gated worker receives.
        readyPublication.complete(true)
        audioRegistry = registry
        recordingHost = recordings
        audioFilePort = filePort
        updateSnapshot(
            preparation = ChannelPreparationAvailability.Available,
            executionStatus = ChannelExecutionStatus.IDLE,
        )
        // Publish the content-free work projection once the generation is live so
        // recovered queued/claimed work (task 13.8 restart) and declared queues
        // (task 13.2) are visible to host presentation immediately after Ready.
        refreshWorkProjection()

        return ChannelActivationResult.Ready
    }

    // ── prepareInput ──────────────────────────────────────────────────────

    /**
     * Returns input acceptance from cached readiness plus the presence of
     * handle_input.
     *
     * Per spec: readiness is cached from [refreshReadiness] calls. When cached
     * readiness is ready, acceptance is immediate and never enters Lua. When
     * cached readiness is not-ready with a valid, nonempty preparation request
     * (5.4), the runtime runs the declared host preparers sequentially under the
     * current attempt, refreshes readiness exactly once (5.5), and accepts only
     * if the refreshed result is ready. Target commitment, ready beep, capture,
     * and all later effects stay gated behind acceptance (5.6); a failed,
     * timed-out, cancelled, stale, or still-not-ready preparation suppresses
     * acceptance entirely (5.7).
     */
    override suspend fun prepareInput(): ChannelInputAcceptance {
        if (closed.get()) return ChannelInputAcceptance.Unavailable("Lua runtime is closed")
        if (!definition.enabled) return ChannelInputAcceptance.Refused("Channel is disabled")
        if (!activated.get()) return ChannelInputAcceptance.Unavailable("Lua runtime not yet activated")

        val hasHandleInput = callbacks.containsKey("handle_input")
        if (!hasHandleInput) {
            return ChannelInputAcceptance.Refused("Input not supported (no handle_input callback)")
        }

        val attempt = preparationAttempt.get()
        val readiness = cachedReadiness.get()
        if (readiness.ready) {
            return ChannelInputAcceptance.Accepted(LuaInputTarget())
        }
        if (readiness.prepare.isEmpty()) {
            return ChannelInputAcceptance.Refused("Channel is not ready")
        }

        for (publicCapabilityId in readiness.prepare) {
            if (isPreparationStale(attempt)) {
                return ChannelInputAcceptance.Unavailable("Lua runtime is closed")
            }
            val preparer = preparerRegistry.preparerFor(publicCapabilityId)
                ?: return ChannelInputAcceptance.Refused("Channel is not ready")
            when (
                preparer.prepare(
                    capabilities,
                    CapabilityPreparationRequest(
                        publicCapabilityId = publicCapabilityId,
                        identity = capabilities.identity,
                        attempt = attempt,
                        timeoutMillis = preparationTimeoutMillis,
                    ),
                )
            ) {
                CapabilityPreparationOutcome.Prepared -> Unit
                is CapabilityPreparationOutcome.Unavailable ->
                    return ChannelInputAcceptance.Refused("Channel is not ready")
                CapabilityPreparationOutcome.Cancelled ->
                    return ChannelInputAcceptance.Refused("Channel preparation was cancelled")
                CapabilityPreparationOutcome.Closed ->
                    return ChannelInputAcceptance.Unavailable("Lua runtime is closed")
                is CapabilityPreparationOutcome.Failed ->
                    return ChannelInputAcceptance.Refused("Channel is not ready")
            }
        }
        if (isPreparationStale(attempt)) {
            return ChannelInputAcceptance.Unavailable("Lua runtime is closed")
        }
        refreshReadiness()
        if (isPreparationStale(attempt)) {
            return ChannelInputAcceptance.Unavailable("Lua runtime is closed")
        }
        return if (cachedReadiness.get().ready) {
            ChannelInputAcceptance.Accepted(LuaInputTarget())
        } else {
            ChannelInputAcceptance.Refused("Channel is not ready")
        }
    }

    /**
     * 5.7/4.6: A preparation is stale once the runtime closed, the generation
     * retired, or the attempt token advanced (release, cancellation, replacement,
     * or shutdown). A stale preparation can never commit an accepted target.
     */
    private fun isPreparationStale(attempt: Long): Boolean =
        closed.get() || !generationContext.isActive() || preparationAttempt.get() != attempt

    /** Current suspended input owner, or null when no semantic operation is pending. */
    internal fun pendingInput(): LuaInputPending? = synchronized(inputLock) {
        pendingInputExecution?.let { pending ->
            LuaInputPending(
                ownerToken = pending.ownerToken,
                operationId = pending.operation.operationId.value,
                label = pending.label,
            )
        }
    }

    /**
     * Complete one pending semantic input operation. Ownership is checked before
     * actor entry; stale or foreign callers cannot consume or resume the token.
     * A yielded continuation keeps the same owner token and publishes its new
     * operation id for the next authorized completion.
     */
    internal suspend fun resumeInput(
        ownerToken: String,
        operationId: Long,
        success: Boolean,
        value: String,
        normalizeAudioError: Boolean = true,
    ): LuaInputResumeResult {
        if (closed.get() || !generationContext.isActive()) {
            synchronized(inputLock) { pendingInputExecution?.takeIf { it.ownerToken == ownerToken } }
                ?.let { finishPendingInput(it, CallbackInvocationResult.Failure("runtime is closed")) }
            return LuaInputResumeResult.Closed
        }
        val pending = synchronized(inputLock) {
            pendingInputExecution?.takeIf {
                it.ownerToken == ownerToken && it.operation.operationId.value == operationId
            }
        } ?: synchronized(inputLock) {
            when {
                pendingInputExecution?.ownerToken == ownerToken -> LuaInputResumeResult.Stale
                retiredInputOwnerToken == ownerToken -> LuaInputResumeResult.Stale
                else -> LuaInputResumeResult.ForeignOwner
            }
        }.let { return it }

        val deliveredValue = if (!success && normalizeAudioError) normalizeSemanticAudioError(value) else value

        val admission = ScopedSpawnAdmission(pending.audioOwner)
        val gateOutcome = try {
            actor.resumeProgramImageCoroutine(pending.operation, success, deliveredValue, admission)
        } catch (error: Throwable) {
            admission.release(false)
            finishPendingInput(pending, CallbackInvocationResult.Failure("input resume failed"))
            return LuaInputResumeResult.Closed
        }
        when (val release = admission.releaseFor(gateOutcome)) {
            SpawnAdmissionRelease.Matched -> Unit
            is SpawnAdmissionRelease.Mismatch -> {
                finishPendingInput(pending, CallbackInvocationResult.Failure(release.diagnostic))
                return LuaInputResumeResult.Stale
            }
        }
        val outcome = (gateOutcome as? ActorGateResult.Success)?.value
            ?: run {
                finishPendingInput(pending, CallbackInvocationResult.Failure("input owner is no longer live"))
                return LuaInputResumeResult.Closed
            }
        when (outcome) {
            is LuaKernelOutcome.Yielded -> {
                val next = LuaOperationHandle(
                    stateHandle,
                    LuaCoroutineId(outcome.coroutineId),
                    LuaOperationId(outcome.operationId),
                )
                // A chained yield (e.g. synthesize -> playback.schedule in TTS) is a
                // distinct semantic operation: mint a fresh terminal gate so the next
                // executor can claim it independently of the completed predecessor.
                val nextSemantic = SemanticAudioOperation.create(
                    next,
                    (generationContext as ActorRuntimeHostContext).actorIdentity,
                )
                synchronized(inputLock) {
                    if (pendingInputExecution?.ownerToken != ownerToken ||
                        pendingInputExecution?.operation?.operationId?.value != operationId
                    ) return LuaInputResumeResult.Stale
                    pendingInputExecution = pending.copy(
                        operation = next,
                        label = outcome.value ?: "",
                        semantic = nextSemantic,
                    )
                }
                return LuaInputResumeResult.Accepted
            }
            is LuaKernelOutcome.Completed -> {
                val completion = consumeCompletedOutcome(outcome)
                val terminal = if (completion is CallbackInvocationResult.Success) {
                    completion.value.strictInputStatus()?.let { CallbackInvocationResult.Success(completion.value) }
                        ?: CallbackInvocationResult.InvalidOutcome("malformed handle_input terminal result")
                } else completion
                finishPendingInput(pending, terminal)
                return LuaInputResumeResult.Accepted
            }
            else -> {
                finishPendingInput(pending, CallbackInvocationResult.Failure("input operation failed"))
                return LuaInputResumeResult.Accepted
            }
        }
    }
    private fun finishPendingInput(
        pending: PendingInputExecution,
        result: CallbackInvocationResult,
    ) {
        pending.semantic.terminalize(result)
        synchronized(inputLock) {
            if (pendingInputExecution?.ownerToken == pending.ownerToken) {
                retiredInputOwnerToken = pending.ownerToken
                pendingInputExecution = null
            }
        }
        pending.terminal.complete(result)
    }

    // ── refreshReadiness ──────────────────────────────────────────────────

    /**
     * Invokes handle_readiness() and caches the result. On any failure
     * (throw, error table, non-table, invalid ready field, yield) caches
     * not-ready and logs a diagnostic locally without failing the actor.
     *
     * Neutral default (no callback): cached as not-ready.
     */
    override suspend fun refreshReadiness() {
        if (closed.get()) return

        val readinessHandle = callbacks["handle_readiness"]
        if (readinessHandle == null) {
            cachedReadiness.set(CachedReadiness(ready = false, prepare = emptyList()))
            updateSnapshot(
                preparation = ChannelPreparationAvailability.Unavailable(
                    ChannelPreparationReason.RuntimeReadiness(),
                ),
            )
            return
        }

        val context = readinessContext()
        val outcome = invokeCallbackHandle(
            readinessHandle,
            LuaValue.Map(context),
        )
        when (val projection = outcome.readinessProjection()) {
            is ReadinessProjection.Valid -> {
                // Cache readiness and preparation atomically, then publish the same
                // decision to host-visible runtime state. Input admission and card/
                // catalogue readiness must never diverge.
                cachedReadiness.set(CachedReadiness(ready = projection.ready, prepare = projection.prepare))
                val readinessMessage = projection.status?.takeIf(String::isNotBlank)
                val preparation = when {
                    projection.ready -> ChannelPreparationAvailability.Available
                    projection.prepare.isNotEmpty() -> ChannelPreparationAvailability.Recoverable(
                        ChannelPreparationReason.RuntimeReadiness(
                            readinessMessage ?: "Channel can prepare for input",
                        ),
                    )
                    else -> ChannelPreparationAvailability.Unavailable(
                        ChannelPreparationReason.RuntimeReadiness(
                            readinessMessage ?: "Channel is not ready",
                        ),
                    )
                }
                updateSnapshot(
                    preparation = preparation,
                    summary = projection.status.takeIf { projection.statusPresent },
                )
            }
            ReadinessProjection.Malformed -> {
                cachedReadiness.set(CachedReadiness(ready = false, prepare = emptyList()))
                updateSnapshot(
                    preparation = ChannelPreparationAvailability.Unavailable(
                        ChannelPreparationReason.RuntimeReadiness("Channel readiness is invalid"),
                    ),
                )
                recordDiagnostic(READINESS_MALFORMED_DIAGNOSTIC)
            }
        }
    }

    // ── handleSos ─────────────────────────────────────────────────────────

    /**
     * 9.1-9.7: Bounded yield-capable SOS dispatch. Invokes handle_sos through the
     * host-managed SOS coroutine entrypoint. A callback that never yields completes
     * in one slice exactly like the legacy synchronous path; a yielded keyboard
     * output operation is claimed and completed through the same typed
     * claim/resume path under an SOS execution owner, then the coroutine resumes
     * under the same generation gate. Sleep, spawn, defer, and raw yields are
     * rejected natively before suspension. Every failure is local-contained: no
     * snapshot mutation and no generation failure, and a generation that closes
     * while suspended suppresses every late resume.
     */
    override suspend fun handleSos() {
        if (closed.get() || !generationContext.isActive()) return
        val sosHandle = callbacks["handle_sos"] ?: return
        val sosArg = LuaValue.Map(mapOf("event" to LuaValue.StringValue("sos")))
        val ownerToken = UUID.randomUUID().toString()

        val admission = ScopedSpawnAdmission()
        val gateOutcome = try {
            actor.invokeProgramImageSosCallback(sosHandle, sosArg, admission)
        } catch (_: Throwable) {
            admission.release(false)
            return
        }
        when (admission.releaseFor(gateOutcome)) {
            SpawnAdmissionRelease.Matched -> Unit
            is SpawnAdmissionRelease.Mismatch -> return
        }
        var outcome = (gateOutcome as? ActorGateResult.Success)?.value ?: return

        // Drive yielded keyboard-output operations until the SOS coroutine reaches
        // a terminal outcome. A non-yielding callback skips the loop entirely.
        while (outcome is LuaKernelOutcome.Yielded) {
            if (closed.get() || !generationContext.isActive()) return
            consumeNativeLogs(outcome.logs)
            val operation = try {
                LuaOperationHandle(stateHandle, LuaCoroutineId(outcome.coroutineId), LuaOperationId(outcome.operationId))
            } catch (_: IllegalArgumentException) {
                return
            }
            val claim = outcome.value?.toLongOrNull()?.let { bridge.claimHostOperation(stateHandle, it) }
            val completion = if (claim is HostOperationClaim.Admitted) {
                when (claim.kind) {
                    HostOperationKind.KEYBOARD_SEND_TEXT,
                    HostOperationKind.KEYBOARD_SEND_KEY -> {
                        val (success, value) = submitKeyboardOperation(claim, OutputExecutionOwnerKind.SOS, ownerToken)
                        HostOperationCompletion(success, value)
                    }
                    // SOS authority is exactly keyboard output; every other
                    // yielded family (secrets/http/work) is denied host-side
                    // as defense in depth behind the native SOS owner rules.
                    else -> HostOperationCompletion(false, "E_DENIED")
                }
            } else {
                HostOperationCompletion(false, (claim as? HostOperationClaim.Rejected)?.errorCode ?: "E_INVALID_YIELD")
            }
            if (closed.get() || !generationContext.isActive()) return
            val resumeAdmission = ScopedSpawnAdmission()
            val resumeGate = try {
                actor.resumeProgramImageCoroutine(operation, completion.success, completion.value, resumeAdmission)
            } catch (_: Throwable) {
                resumeAdmission.release(false)
                return
            }
            when (resumeAdmission.releaseFor(resumeGate)) {
                SpawnAdmissionRelease.Matched -> Unit
                is SpawnAdmissionRelease.Mismatch -> return
            }
            outcome = (resumeGate as? ActorGateResult.Success)?.value ?: return
        }
        if (outcome is LuaKernelOutcome.Completed) {
            consumeNativeLogs(outcome.logs)
        }
    }

    // ── close ─────────────────────────────────────────────────────────────

    /**
     * Idempotent close: stop admission, dispose timers via context, cancel
     * and join background tasks, close actor Lua state once, suppress all
     * late effects.
     *
     * After close the snapshot is terminal-unavailable and no further Lua
     * entry occurs.
     */
    override suspend fun close() {
        if (!closed.compareAndSet(false, true)) return
        readyPublication.complete(false)
        audioFilePort?.close()
        audioFilePort = null
        recordingHost = null
        audioRegistry?.close()
        audioRegistry = null
        mountHandleCache.clear()
        resourceClose?.invoke()
        cachedReadiness.set(CachedReadiness(ready = false, prepare = emptyList()))
        preparationAttempt.incrementAndGet()
        synchronized(logLock) {
            logs.clear()
            localDiagnostics.clear()
        }
        val sleepWaits = synchronized(sleepLock) {
            activeSleepWaits.toList().also { activeSleepWaits.clear() }
        }
        sleepWaits.forEach { sleep ->
            sleep.timer.dispose()
            sleep.deadline.dispose()
            sleep.terminal.complete(false)
        }
        val closingInput = synchronized(inputLock) {
            detachedInputContinuation = null
            pendingInputExecution?.also { pendingInputExecution = null }
        }
        closingInput?.let {
            it.semantic.terminalize(CallbackInvocationResult.Failure("runtime is closed"))
            it.terminal.complete(CallbackInvocationResult.Failure("runtime is closed"))
        }
        discardStagedTasks()
        workHostAdapter?.close(clock.millis())
        actor.close()
        synchronized(lifecycleLock) {
            _snapshot.value = _snapshot.value.copy(
                preparation = ChannelPreparationAvailability.Unavailable(ChannelPreparationReason.RuntimeClosed),
                executionStatus = ChannelExecutionStatus.IDLE,
            )
        }
    }

    // ── Internal helpers ──────────────────────────────────────────────────

    /**
     * Invoke a named callback via the bridge, parsing the outcome into our
     * internal result type.
     */
    private suspend fun invokeCallback(
        name: String,
        arguments: LuaValue,
    ): CallbackInvocationResult {
        val handle = callbacks[name]
            ?: return CallbackInvocationResult.Failure("callback '$name' not found")
        return invokeCallbackHandle(handle, arguments)
    }

    /**
     * Converts the validated host configuration into a fresh normalized Lua table.
     * Parses the JSON payload string into an independent LuaValue tree so Lua
     * mutations cannot affect the host, a sibling instance, or a later generation.
     */
    private fun validatedConfigurationToLua(config: ValidatedChannelConfiguration): LuaValue.Map {
        val parsed = LuaValue.fromJsonString(config.payload.toJsonString())
        val valuesMap = when (parsed) {
            is JsonParsingResult.Success -> parsed.value
            is JsonParsingResult.Failure -> LuaValue.Map(emptyMap())
        }
        return LuaValue.Map(mapOf(
            "schema_version" to LuaValue.Integer(config.schemaVersion.toLong()),
            "values" to (valuesMap as? LuaValue.Map ?: LuaValue.Map(emptyMap())),
        ))
    }

    private suspend fun invokeStartupCallback(config: LuaValue): CallbackInvocationResult {
        val handle = callbacks["startup"] ?: return CallbackInvocationResult.Failure("callback 'startup' not found")
        return invokeCallbackHandle(handle, config)
    }

    /**
     * Invoke a callback handle via the bridge. The bridge enforces
     * non-yielding, protected execution, and contract validation.
     */
    private suspend fun invokeCallbackHandle(
        handle: LuaCallbackHandle,
        arguments: LuaValue,
    ): CallbackInvocationResult {
        if (closed.get() || !generationContext.isActive()) {
            return CallbackInvocationResult.Failure("runtime is closed")
        }
        val admission = ScopedSpawnAdmission()
        val gateOutcome = try {
            if (handle.name == "startup") {
                actor.invokeProgramImageStartup(handle, arguments, admission)
            } else {
                actor.invokeProgramImageCallback(handle, arguments, admission)
            }
        } catch (error: Throwable) {
            admission.release(false)
            return failProgramImage("callback bridge failure: ${error.message ?: error::class.simpleName}")
        }
        when (val release = admission.releaseFor(gateOutcome)) {
            SpawnAdmissionRelease.Matched -> Unit
            is SpawnAdmissionRelease.Mismatch -> return failProgramImage(release.diagnostic)
        }
        val outcome = when (gateOutcome) {
            is ActorGateResult.Success -> gateOutcome.value
            else -> return CallbackInvocationResult.Failure("callback gate is unavailable")
        }
        return when (outcome) {
            is LuaKernelOutcome.Completed -> consumeCompletedOutcome(outcome)
            is LuaKernelOutcome.RuntimeFailure -> CallbackInvocationResult.Failure(outcome.diagnostic)
            is LuaKernelOutcome.Interrupted -> CallbackInvocationResult.Failure(outcome.diagnostic ?: "interrupted")
            is LuaKernelOutcome.Cancelled -> CallbackInvocationResult.Failure("callback cancelled")
            is LuaKernelOutcome.ValidationFailure -> CallbackInvocationResult.InvalidOutcome(outcome.diagnostic)
            is LuaKernelOutcome.InvalidOwnership -> CallbackInvocationResult.Failure(outcome.diagnostic)
            is LuaKernelOutcome.Stale -> CallbackInvocationResult.YieldViolation("stale generation")
            is LuaKernelOutcome.Closed -> CallbackInvocationResult.Failure("state is closed")
            is LuaKernelOutcome.Yielded -> CallbackInvocationResult.YieldViolation(
                "callback yielded operation ${outcome.operationId}",
            )
            is LuaKernelOutcome.Created,
            is LuaKernelOutcome.SyntaxFailure,
            is LuaKernelOutcome.Snapshot -> CallbackInvocationResult.Failure(
                "unexpected bridge outcome: ${outcome::class.simpleName}",
            )
        }
    }

    private suspend fun invokeInputCallbackHandle(
        handle: LuaCallbackHandle,
        arguments: LuaValue,
        capturedAudioToken: String,
        audioOwner: LuaOpaqueAudioRegistry.Owner,
    ): CallbackInvocationResult {
        if (closed.get() || !generationContext.isActive()) {
            return CallbackInvocationResult.Failure("runtime is closed")
        }
        val admission = ScopedSpawnAdmission(audioOwner)
        val gateOutcome = try {
            actor.invokeProgramImageInputCallback(handle, arguments, capturedAudioToken, admission)
        } catch (_: Throwable) {
            admission.release(false)
            return CallbackInvocationResult.Failure("callback bridge failure")
        }
        when (val release = admission.releaseFor(gateOutcome)) {
            SpawnAdmissionRelease.Matched -> Unit
            is SpawnAdmissionRelease.Mismatch -> return failProgramImage(release.diagnostic)
        }
        val outcome = (gateOutcome as? ActorGateResult.Success)?.value
            ?: return CallbackInvocationResult.Failure("callback gate is unavailable")
        return when (outcome) {
            is LuaKernelOutcome.Completed -> consumeCompletedOutcome(outcome)
            is LuaKernelOutcome.Yielded -> {
                consumeNativeLogs(outcome.logs)
                val operation = try {
                    LuaOperationHandle(
                        stateHandle,
                        LuaCoroutineId(outcome.coroutineId),
                        LuaOperationId(outcome.operationId),
                    )
                } catch (_: IllegalArgumentException) {
                    return CallbackInvocationResult.Failure("invalid input operation handle")
                }
                CallbackInvocationResult.Pending(operation, outcome.value ?: "")
            }
            is LuaKernelOutcome.RuntimeFailure -> CallbackInvocationResult.Failure(outcome.diagnostic)
            is LuaKernelOutcome.Interrupted -> CallbackInvocationResult.Failure(outcome.diagnostic ?: "interrupted")
            is LuaKernelOutcome.Cancelled -> CallbackInvocationResult.Failure("callback cancelled")
            is LuaKernelOutcome.ValidationFailure -> CallbackInvocationResult.InvalidOutcome(outcome.diagnostic)
            is LuaKernelOutcome.InvalidOwnership -> CallbackInvocationResult.Failure(outcome.diagnostic)
            is LuaKernelOutcome.Stale -> CallbackInvocationResult.Failure("stale input operation")
            is LuaKernelOutcome.Closed -> CallbackInvocationResult.Failure("state is closed")
            else -> CallbackInvocationResult.Failure("unexpected input bridge outcome")
        }
    }

    /** Drains only non-admission side effects; admission occurred inside JNI. */
    private fun consumeCompletedOutcome(outcome: LuaKernelOutcome.Completed): CallbackInvocationResult {
        consumeNativeLogs(outcome.logs)
        return luaValueFromOutcome(outcome.value)
    }

    /**
     * JNI invokes [admitTask] while Lua is still in the originating slice.
     * Accepted context tasks begin only after a completed or yielded native
     * slice returns the exact ordered IDs admitted synchronously.
     */
    private sealed class SpawnAdmissionRelease {
        data object Matched : SpawnAdmissionRelease()
        data class Mismatch(val diagnostic: String) : SpawnAdmissionRelease()
    }

    private inner class ScopedSpawnAdmission(
        private val audioOwner: LuaOpaqueAudioRegistry.Owner? = null,
    ) : LuaSpawnAdmission {
        private val pending = mutableListOf<Pair<Long, CompletableDeferred<Boolean>>>()

        override fun admitTask(coroutineId: Long): Int {
            val decision = CompletableDeferred<Boolean>()
            return when (generationContext.admitTask {
                if (decision.await()) runBackgroundCoroutine(LuaCoroutineId(coroutineId))
            }) {
                is GenerationAdmission.Accepted -> { pending += coroutineId to decision; 0 }
                is GenerationAdmission.Rejected -> if (generationContext.isActive()) 2 else 1
            }
        }

        fun releaseFor(outcome: LuaKernelOutcome): SpawnAdmissionRelease {
            val accepted = pending.map { it.first }
            val matched = when (outcome) {
                is LuaKernelOutcome.Completed -> outcome.spawnedCoroutines.orEmpty() == accepted
                is LuaKernelOutcome.Yielded -> outcome.spawnedCoroutines.orEmpty() == accepted
                else -> accepted.isEmpty()
            }
            val release = matched && (outcome is LuaKernelOutcome.Completed || outcome is LuaKernelOutcome.Yielded)
            pending.forEach { it.second.complete(release) }
            return if (matched) SpawnAdmissionRelease.Matched else {
                SpawnAdmissionRelease.Mismatch(
                    "native spawn admission mismatch: accepted=$accepted outcome=${outcome::class.simpleName}",
                )
            }
        }

        fun releaseFor(result: ActorGateResult<LuaKernelOutcome>): SpawnAdmissionRelease = when (result) {
            is ActorGateResult.Success -> releaseFor(result.value)
            else -> {
                val accepted = pending.map { it.first }
                val matched = accepted.isEmpty()
                pending.forEach { it.second.complete(false) }
                if (matched) SpawnAdmissionRelease.Matched else {
                    SpawnAdmissionRelease.Mismatch(
                        "native spawn admission mismatch: accepted=$accepted gate=${result::class.simpleName}",
                    )
                }
            }
        }

        fun release(run: Boolean) {
            pending.forEach { it.second.complete(run) }
        }
    }

    private class ActiveSleepWait(
        val terminal: CompletableDeferred<Boolean>,
        val timer: Disposable,
        val deadline: Disposable,
    )

    private data class HostOperationCompletion(
        val success: Boolean,
        val value: String,
    )

    private fun TypedHostCompletion.toHostCompletion(): HostOperationCompletion =
        HostOperationCompletion(success, value)
    private suspend fun runBackgroundCoroutine(initialCoroutineId: LuaCoroutineId) {
        val audioOwner = LuaOpaqueAudioRegistry.Owner.Task(
            "coroutine-${initialCoroutineId.value}",
        )
        try {
            runBackgroundCoroutineOwned(initialCoroutineId, audioOwner)
        } finally {
            audioRegistry?.invalidateOwner(audioOwner)
        }
    }

    /** Runs one native coroutine without recursion, retaining task capacity while asleep. */
    private suspend fun runBackgroundCoroutineOwned(
        initialCoroutineId: LuaCoroutineId,
        audioOwner: LuaOpaqueAudioRegistry.Owner.Task,
    ) {
        val startAdmission = ScopedSpawnAdmission()
        val startOutcome = actor.startProgramImageCoroutine(initialCoroutineId, startAdmission)
        when (val release = startAdmission.releaseFor(startOutcome)) {
            SpawnAdmissionRelease.Matched -> Unit
            is SpawnAdmissionRelease.Mismatch -> {
                failProgramImage(release.diagnostic)
                return
            }
        }
        driveManagedTaskOutcomes(startOutcome, audioOwner)
    }

    /**
     * Drives one already-admitted managed-task gate-outcome stream to its
     * terminal outcome. Shared by startup-spawned background coroutines and by
     * detached input continuations whose durable submission committed (task
     * 13.1). Host operations dispatch under task ownership; WORK_RECEIVE stays
     * gated on successful Ready publication (task 13.2).
     */
    private suspend fun driveManagedTaskOutcomes(
        initialGateOutcome: ActorGateResult<LuaKernelOutcome>,
        audioOwner: LuaOpaqueAudioRegistry.Owner.Task,
    ) {
        suspend fun resumeSlice(operation: LuaOperationHandle, success: Boolean, value: String): ActorGateResult<LuaKernelOutcome>? {
            val admission = ScopedSpawnAdmission()
            val result = actor.resumeProgramImageCoroutine(operation, success, value, admission)
            return when (val release = admission.releaseFor(result)) {
                SpawnAdmissionRelease.Matched -> result
                is SpawnAdmissionRelease.Mismatch -> {
                    failProgramImage(release.diagnostic)
                    null
                }
            }
        }
        var gateOutcome = initialGateOutcome
        while (!closed.get() && generationContext.isActive()) {
            val outcome = when (gateOutcome) {
                is ActorGateResult.Success -> gateOutcome.value
                else -> return
            }
            when (outcome) {
                is LuaKernelOutcome.Completed -> {
                    val completion = consumeCompletedOutcome(outcome)
                    if (completion !is CallbackInvocationResult.Success) {
                        recordDiagnostic("malformed background completion: ${completion.diagnostic()}")
                    }
                    return
                }
                is LuaKernelOutcome.Yielded -> {
                    consumeNativeLogs(outcome.logs)
                    val operation = try {
                        LuaOperationHandle(stateHandle, LuaCoroutineId(outcome.coroutineId), LuaOperationId(outcome.operationId))
                    } catch (_: IllegalArgumentException) {
                        failProgramImage("invalid background operation handle")
                        return
                    }
                    val requestId = outcome.value?.toLongOrNull()
                    val claim = requestId?.let { bridge.claimHostOperation(stateHandle, it) }
                    if (claim is HostOperationClaim.Admitted) {
                        val completion = when (claim.kind) {
                            HostOperationKind.TRANSCRIBE ->
                                performBackgroundTranscription(audioOwner, claim)
                            HostOperationKind.AUDIO_OPEN,
                            HostOperationKind.AUDIO_EXPORT ->
                                performBackgroundAudioFileOperation(audioOwner, claim)
                            HostOperationKind.SYNTHESIZE ->
                                performBackgroundSynthesis(audioOwner, claim)
                            HostOperationKind.PLAYBACK ->
                                performBackgroundPlayback(audioOwner, claim)
                            HostOperationKind.FS_MKDIR,
                            HostOperationKind.FS_STAT,
                            HostOperationKind.FS_LIST,
                            HostOperationKind.FS_READ_TEXT,
                            HostOperationKind.FS_WRITE_TEXT,
                            HostOperationKind.FS_REMOVE -> performFsOperation(claim)
                            HostOperationKind.KEYBOARD_SEND_TEXT,
                            HostOperationKind.KEYBOARD_SEND_KEY -> {
                                val (success, value) = submitKeyboardOperation(
                                    claim,
                                    OutputExecutionOwnerKind.MANAGED_TASK,
                                    audioOwner.id,
                                )
                                HostOperationCompletion(success, value)
                            }
                            HostOperationKind.SECRET_READ ->
                                secretReadAdapter?.complete(claim)?.toHostCompletion()
                                    ?: HostOperationCompletion(false, "E_DENIED")
                            HostOperationKind.HTTP_REQUEST ->
                                httpHostAdapter?.complete(claim)?.toHostCompletion()
                                    ?: HostOperationCompletion(false, "E_DENIED")
                            HostOperationKind.WORK_SUBMIT,
                            HostOperationKind.WORK_BEGIN_EFFECT,
                            HostOperationKind.WORK_COMMIT_EFFECT,
                            HostOperationKind.WORK_COMPLETE,
                            HostOperationKind.WORK_FAIL ->
                                workHostAdapter?.complete(claim)?.toHostCompletion()
                                    ?: HostOperationCompletion(false, "E_DENIED")
                            HostOperationKind.WORK_RECEIVE -> {
                                // Task 13.2: startup may admit worker closures before
                                // Ready publication; their receives suspend on the
                                // publication gate and enter the durable subsystem only
                                // after publication succeeds. Close or pre-publication
                                // activation failure completes the gate false, failing
                                // the receive closed without dispatch.
                                if (!readyPublication.await()) {
                                    HostOperationCompletion(false, "E_CLOSED")
                                } else {
                                    workHostAdapter?.complete(claim)?.toHostCompletion()
                                        ?: HostOperationCompletion(false, "E_DENIED")
                                }
                            }
                            else -> {
                                android.util.Log.w("TalkcanManagedTask",
                                    "UNHANDLED kind=${claim.kind} req=$requestId -> E_UNSUPPORTED")
                                HostOperationCompletion(false, "E_UNSUPPORTED")
                            }
                        }
                        if (claim.kind == HostOperationKind.WORK_SUBMIT ||
                            claim.kind == HostOperationKind.WORK_RECEIVE ||
                            claim.kind == HostOperationKind.WORK_BEGIN_EFFECT ||
                            claim.kind == HostOperationKind.WORK_COMMIT_EFFECT ||
                            claim.kind == HostOperationKind.WORK_COMPLETE ||
                            claim.kind == HostOperationKind.WORK_FAIL
                        ) {
                            // Managed-task work mutation: republish the content-free
                            // projection (task 14.1). No-op without a work adapter.
                            refreshWorkProjection()
                        }
                        gateOutcome = resumeSlice(
                            operation,
                            completion.success,
                            completion.value,
                        ) ?: return
                        continue
                    }
                    val seconds = parseSleepSeconds(outcome.value)
                    if (seconds == null) {
                        gateOutcome = resumeSlice(operation, false, "E_INVALID_YIELD") ?: return
                        continue
                    }
                    val deadlineMillis = actor.calculateSleepDeadline((seconds * 1_000.0).toLong())
                    if (deadlineMillis == null) {
                        gateOutcome = resumeSlice(operation, false, "E_INVALID_ARGUMENT") ?: return
                        continue
                    }
                    val terminal = CompletableDeferred<Boolean>()
                    val terminalWon = AtomicBoolean(false)
                    val timerAdmission = generationContext.scheduleTimer(seconds) {
                        if (terminalWon.compareAndSet(false, true)) terminal.complete(true)
                    }
                    if (timerAdmission is GenerationAdmission.Rejected) {
                        gateOutcome = resumeSlice(operation, false, "E_BUSY") ?: return
                        continue
                    }
                    val timer = (timerAdmission as GenerationAdmission.Accepted<Disposable>).value
                    val deadlineAdmission = generationContext.scheduleTimer(deadlineMillis / 1_000.0) {
                        if (terminalWon.compareAndSet(false, true)) terminal.complete(false)
                    }
                    if (deadlineAdmission is GenerationAdmission.Rejected) {
                        timer.dispose()
                        gateOutcome = resumeSlice(operation, false, "E_BUSY") ?: return
                        continue
                    }
                    val deadline = (deadlineAdmission as GenerationAdmission.Accepted<Disposable>).value
                    val sleep = ActiveSleepWait(terminal, timer, deadline)
                    val admittedSleep = synchronized(sleepLock) {
                        if (closed.get()) false else activeSleepWaits.add(sleep)
                    }
                    if (!admittedSleep) {
                        timer.dispose()
                        deadline.dispose()
                        return
                    }
                    val timerFirst = try {
                        terminal.await()
                    } finally {
                        synchronized(sleepLock) { activeSleepWaits.remove(sleep) }
                        timer.dispose()
                        deadline.dispose()
                    }
                    if (closed.get() || !generationContext.isActive()) return
                    gateOutcome = resumeSlice(operation, timerFirst, if (timerFirst) "" else "E_TIMEOUT") ?: return
                }
                else -> {
                    recordDiagnostic("background coroutine terminated: ${outcome::class.simpleName}")
                    return
                }
            }
        }
    }

    private suspend fun performBackgroundTranscription(
        audioOwner: LuaOpaqueAudioRegistry.Owner.Task,
        claim: HostOperationClaim.Admitted,
    ): HostOperationCompletion {
        if (!capabilities.isPublicCapabilityDeclared(PackageCapability.AUDIO_TRANSCRIPTION)) {
            return HostOperationCompletion(false, "E_CAPABILITY_UNDECLARED")
        }
        val token = claim.audioToken
            ?: return HostOperationCompletion(false, "E_INVALID_ARGUMENT")
        val registry = audioRegistry
            ?: return HostOperationCompletion(false, "E_CLOSED")
        val recording = registry.resolve(
            LuaOpaqueAudioRegistry.Token(token),
            audioOwner,
            LuaOpaqueAudioRegistry.Kind.Captured,
        ) as? LuaOpaqueAudioRegistry.Resolution.Captured
            ?: return HostOperationCompletion(false, "E_STALE")
        val result = when (val acquisition = capabilities.acquire(CapabilityKey.Transcription)) {
            is CapabilityAcquisition.Available -> {
                awaitSemanticBackend(
                    operation = {
                        acquisition.lease.use { it.transcribe(recording.recording) }
                    },
                ) ?: return HostOperationCompletion(false, "E_TIMEOUT")
            }
            is CapabilityAcquisition.Recoverable,
            is CapabilityAcquisition.Unavailable ->
                CapabilityOperationResult.Unavailable(CapabilityUnavailableReason.NOT_CONFIGURED)
            is CapabilityAcquisition.Denied ->
                CapabilityOperationResult.Denied(acquisition.reason)
            CapabilityAcquisition.Closed -> CapabilityOperationResult.Closed
            CapabilityAcquisition.Cancelled -> CapabilityOperationResult.Cancelled
            is CapabilityAcquisition.Failed ->
                CapabilityOperationResult.Failed(CapabilityFailureReason.HOST_FAILURE)
        }
        return when (result) {
            is CapabilityOperationResult.Success -> {
                val text = result.value.text
                if (text.toByteArray(Charsets.UTF_8).size <= 16 * 1024) {
                    HostOperationCompletion(true, text)
                } else {
                    HostOperationCompletion(false, "E_HOST_FAILURE")
                }
            }
            is CapabilityOperationResult.Unavailable ->
                HostOperationCompletion(false, "E_UNAVAILABLE")
            is CapabilityOperationResult.Denied ->
                HostOperationCompletion(false, "E_CAPABILITY_UNDECLARED")
            CapabilityOperationResult.Closed ->
                HostOperationCompletion(false, "E_CLOSED")
            CapabilityOperationResult.Cancelled ->
                HostOperationCompletion(false, "E_CANCELLED")
            is CapabilityOperationResult.Failed ->
                HostOperationCompletion(false, "E_HOST_FAILURE")
        }
    }

    private suspend fun performBackgroundAudioFileOperation(
        audioOwner: LuaOpaqueAudioRegistry.Owner.Task,
        claim: HostOperationClaim.Admitted,
    ): HostOperationCompletion {
        val port = audioFilePort
            ?: return HostOperationCompletion(false, "E_UNAVAILABLE")
        val recordings = recordingHost
            ?: return HostOperationCompletion(false, "E_UNAVAILABLE")
        val declarationId = claim.declarationId
            ?: return HostOperationCompletion(false, "E_INVALID_ARGUMENT")
        val path = claim.path
            ?: return HostOperationCompletion(false, "E_INVALID_ARGUMENT")
        val format = claim.format?.let(AudioFileFormat::fromToken)
            ?: return HostOperationCompletion(false, "E_INVALID_ARGUMENT")
        val storage = storagePort
            ?: return HostOperationCompletion(false, "E_MOUNT_UNAVAILABLE")
        val mount = when (val resolved = resolveMountHandle(storage, declarationId)) {
            is FilesystemOutcome.Success -> resolved.value
            is FilesystemOutcome.Failure ->
                return HostOperationCompletion(false, resolved.error.code.name)
        }
        val owner = ExecutionOwner(
            id = audioOwner.id,
            kind = ExecutionOwnerKind.TASK,
            generation = hostGeneration,
        )
        return when (claim.kind) {
            HostOperationKind.AUDIO_OPEN -> {
                if (format != AudioFileFormat.WAV_PCM_S16LE) {
                    return HostOperationCompletion(false, "E_INVALID_ARGUMENT")
                }
                val outcome = awaitSemanticBackend(
                    operation = {
                        port.open(owner, mount, path, AudioOpenOptions(format))
                    },
                    onLateResult = { late ->
                        if (late is AudioFileOutcome.Success) recordings.dispose(late.value)
                    },
                ) ?: return HostOperationCompletion(false, "E_TIMEOUT")
                when (outcome) {
                    is AudioFileOutcome.Failure ->
                        HostOperationCompletion(false, outcome.error.code.name)
                    is AudioFileOutcome.Success -> {
                        val handle = outcome.value
                        when (val described = port.describe(handle, owner)) {
                            is AudioFileOutcome.Failure -> {
                                recordings.dispose(handle)
                                HostOperationCompletion(false, described.error.code.name)
                            }
                            is AudioFileOutcome.Success -> {
                                val metadata = described.value
                                HostOperationCompletion(
                                    true,
                                    org.json.JSONObject()
                                        .put("token", recordings.tokenFor(handle))
                                        .put(
                                            "metadata",
                                            org.json.JSONObject()
                                                .put("sample_rate", metadata.sampleRate)
                                                .put("channels", metadata.channels)
                                                .put("duration_ms", metadata.durationMs)
                                                .put("pcm_bytes", metadata.pcmBytes),
                                        )
                                        .toString(),
                                )
                            }
                        }
                    }
                }
            }
            HostOperationKind.AUDIO_EXPORT -> {
                val token = claim.audioToken
                    ?: return HostOperationCompletion(false, "E_INVALID_ARGUMENT")
                val mode = when (claim.mode) {
                    "create-new" -> AudioExportMode.CREATE_NEW
                    "replace" -> AudioExportMode.REPLACE
                    else -> return HostOperationCompletion(false, "E_INVALID_ARGUMENT")
                }
                val outcome = awaitSemanticBackend(
                    operation = {
                        port.export(
                            owner = owner,
                            recording = recordings.handleFor(
                                LuaOpaqueAudioRegistry.Token(token),
                            ),
                            mount = mount,
                            path = path,
                            options = AudioExportOptions(format, mode),
                        )
                    },
                ) ?: return HostOperationCompletion(false, "E_TIMEOUT")
                when (outcome) {
                    is AudioFileOutcome.Failure ->
                        HostOperationCompletion(false, outcome.error.code.name)
                    is AudioFileOutcome.Success -> {
                        val result = outcome.value
                        HostOperationCompletion(
                            true,
                            org.json.JSONObject()
                                .put("status", "written")
                                .put("format", result.format.token)
                                .put("sample_rate", result.sampleRate)
                                .put("channels", result.channels)
                                .put("duration_ms", result.durationMs)
                                .put("bytes", result.bytes)
                                .toString(),
                        )
                    }
                }
            }
            else -> HostOperationCompletion(false, "E_UNSUPPORTED")
        }
    }
    private suspend fun performBackgroundSynthesis(
        audioOwner: LuaOpaqueAudioRegistry.Owner.Task,
        claim: HostOperationClaim.Admitted,
    ): HostOperationCompletion {
        if (!capabilities.isPublicCapabilityDeclared(PackageCapability.AUDIO_SYNTHESIS)) {
            return HostOperationCompletion(false, "E_CAPABILITY_UNDECLARED")
        }
        val text = claim.text.orEmpty()
        val language = claim.language.orEmpty()
        val voice = claim.voice.orEmpty()
        val speed = claim.speed
        if (text.isBlank() || language.isBlank() || voice.isBlank() || !speed.isFinite() || speed <= 0.0) {
            return HostOperationCompletion(false, "E_INVALID_ARGUMENT")
        }
        val registry = audioRegistry ?: return HostOperationCompletion(false, "E_CLOSED")
        val result = when (val acquisition = capabilities.acquire(CapabilityKey.Synthesis)) {
            is CapabilityAcquisition.Available -> {
                awaitSemanticBackend(
                    operation = {
                        acquisition.lease.use {
                            it.synthesize(
                                SpeechSynthesisRequest(text, language, SpeechVoice(voice), speed.toFloat()),
                            )
                        }
                    },
                    onLateResult = { late ->
                        if (late is CapabilityOperationResult.Success) late.value.dispose()
                    },
                ) ?: return HostOperationCompletion(false, "E_TIMEOUT")
            }
            is CapabilityAcquisition.Recoverable,
            is CapabilityAcquisition.Unavailable ->
                CapabilityOperationResult.Unavailable(CapabilityUnavailableReason.NOT_CONFIGURED)
            is CapabilityAcquisition.Denied ->
                CapabilityOperationResult.Denied(acquisition.reason)
            CapabilityAcquisition.Closed -> CapabilityOperationResult.Closed
            CapabilityAcquisition.Cancelled -> CapabilityOperationResult.Cancelled
            is CapabilityAcquisition.Failed ->
                CapabilityOperationResult.Failed(CapabilityFailureReason.HOST_FAILURE)
        }
        return when (result) {
            is CapabilityOperationResult.Success -> {
                val token = registry.admitSynthesized(audioOwner, result.value)
                if (token == null) {
                    result.value.dispose()
                    HostOperationCompletion(false, "E_BUSY")
                } else {
                    HostOperationCompletion(true, token.value)
                }
            }
            is CapabilityOperationResult.Unavailable -> HostOperationCompletion(false, "E_UNAVAILABLE")
            is CapabilityOperationResult.Denied -> HostOperationCompletion(false, "E_CAPABILITY_UNDECLARED")
            CapabilityOperationResult.Closed -> HostOperationCompletion(false, "E_CLOSED")
            CapabilityOperationResult.Cancelled -> HostOperationCompletion(false, "E_CANCELLED")
            is CapabilityOperationResult.Failed -> HostOperationCompletion(false, "E_HOST_FAILURE")
        }
    }

    private suspend fun performBackgroundPlayback(
        audioOwner: LuaOpaqueAudioRegistry.Owner.Task,
        claim: HostOperationClaim.Admitted,
    ): HostOperationCompletion {
        if (!capabilities.isPublicCapabilityDeclared(PackageCapability.AUDIO_PLAYBACK)) {
            return HostOperationCompletion(false, "E_CAPABILITY_UNDECLARED")
        }
        val token = claim.audioToken
        val delay = claim.delaySeconds
        if (token == null || !delay.isFinite() || delay < 0.0 || delay > 86_400.0) {
            return HostOperationCompletion(false, "E_INVALID_VALUE")
        }
        val registry = audioRegistry ?: return HostOperationCompletion(false, "E_CLOSED")
        val captured = registry.resolve(
            LuaOpaqueAudioRegistry.Token(token),
            audioOwner,
            LuaOpaqueAudioRegistry.Kind.Captured,
        )
        val kind = if (captured is LuaOpaqueAudioRegistry.Resolution.Captured) {
            LuaOpaqueAudioRegistry.Kind.Captured
        } else {
            LuaOpaqueAudioRegistry.Kind.Synthesized
        }
        val audio = if (captured is LuaOpaqueAudioRegistry.Resolution.Captured) {
            captured.recording
        } else {
            (registry.resolve(LuaOpaqueAudioRegistry.Token(token), audioOwner, kind)
                as? LuaOpaqueAudioRegistry.Resolution.Synthesized)?.audio
        }
            ?: return HostOperationCompletion(false, "E_STALE")
        val operation: CapabilityOperationResult<OpaqueAudioOperation> = when (
            val acquisition = capabilities.acquire(CapabilityKey.AudioOperation)
        ) {
            is CapabilityAcquisition.Available -> acquisition.lease.use { port ->
                if (audio is OpaqueAudioRecording) port.createPlaybackResult(audio)
                else port.createPlaybackResult(audio as OpaqueSynthesizedAudio)
            }
            is CapabilityAcquisition.Recoverable, is CapabilityAcquisition.Unavailable ->
                CapabilityOperationResult.Unavailable(CapabilityUnavailableReason.NOT_CONFIGURED)
            is CapabilityAcquisition.Denied -> CapabilityOperationResult.Denied(acquisition.reason)
            CapabilityAcquisition.Closed -> CapabilityOperationResult.Closed
            CapabilityAcquisition.Cancelled -> CapabilityOperationResult.Cancelled
            is CapabilityAcquisition.Failed -> CapabilityOperationResult.Failed(acquisition.reason)
        }
        if (operation !is CapabilityOperationResult.Success) {
            return HostOperationCompletion(false, operation.normalizedSemanticError())
        }
        val scheduledResult: CapabilityOperationResult<io.talkcan.model.DelayedPlaybackOutcome>? =
            when (val acquisition = capabilities.acquire(CapabilityKey.DeferredAudioPlayback)) {
                is CapabilityAcquisition.Available -> awaitSemanticBackend(
                    operation = {
                        acquisition.lease.use { port ->
                            CapabilityOperationResult.Success(
                                port.scheduleAudio(
                                    AgentOperationContext(
                                        capabilities.identity,
                                        AgentRunId(UUID.randomUUID().toString()),
                                        AgentOperationId(UUID.randomUUID().toString()),
                                    ),
                                    operation.value,
                                    (delay * 1000.0).toLong(),
                                ),
                            )
                        }
                    },
                )
                is CapabilityAcquisition.Recoverable, is CapabilityAcquisition.Unavailable ->
                    CapabilityOperationResult.Unavailable(CapabilityUnavailableReason.NOT_CONFIGURED)
                is CapabilityAcquisition.Denied -> CapabilityOperationResult.Denied(acquisition.reason)
                CapabilityAcquisition.Closed -> CapabilityOperationResult.Closed
                CapabilityAcquisition.Cancelled -> CapabilityOperationResult.Cancelled
                is CapabilityAcquisition.Failed -> CapabilityOperationResult.Failed(acquisition.reason)
            }
        if (scheduledResult == null) {
            dispose(operation.value)
            return HostOperationCompletion(false, "E_TIMEOUT")
        }
        return when (scheduledResult) {
            is CapabilityOperationResult.Success -> {
                val scheduled = scheduledResult.value
                if (scheduled is io.talkcan.model.DelayedPlaybackOutcome.Pending ||
                    scheduled is io.talkcan.model.DelayedPlaybackOutcome.Playing ||
                    scheduled is io.talkcan.model.DelayedPlaybackOutcome.Heard
                ) {
                    val consumed = registry.consume(LuaOpaqueAudioRegistry.Token(token), audioOwner, kind)
                    if (consumed == null) {
                        dispose(operation.value)
                        HostOperationCompletion(false, "E_STALE")
                    } else {
                        disposeRegistryArtifact(consumed)
                        HostOperationCompletion(true, "scheduled")
                    }
                } else if (scheduled == io.talkcan.model.DelayedPlaybackOutcome.Busy) {
                    HostOperationCompletion(false, "E_BUSY")
                } else {
                    dispose(operation.value)
                    HostOperationCompletion(false, "E_HOST_FAILURE")
                }
            }
            else -> {
                dispose(operation.value)
                HostOperationCompletion(false, scheduledResult.normalizedSemanticError())
            }
        }
    }

    private fun parseSleepSeconds(label: String?): Double? {
        val encoded = label?.removePrefix("sleep:") ?: return null
        if (encoded === label) return null
        return encoded.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0.0 && it <= MAX_SLEEP_SECONDS }
    }

    /** Accepts only native-normalized level/payload records; host owns metadata. */
    private fun consumeNativeLogs(rawRecords: List<String>?) {
        rawRecords.orEmpty().forEach { raw ->
            val record = when (val parsed = LuaValue.fromJsonString(raw)) {
                is JsonParsingResult.Success -> parsed.value as? LuaValue.Map
                is JsonParsingResult.Failure -> null
            }
            val level = (record?.pairs?.get("level") as? LuaValue.StringValue)?.value
            val payload = record?.pairs?.get("payload") as? LuaValue.Map
            if (record == null || record.pairs.keys != setOf("level", "payload") ||
                level !in setOf("debug", "info", "warn", "error") || payload == null
            ) {
                recordDiagnostic("invalid native log record")
                return@forEach
            }
            val timestamp = System.currentTimeMillis()
            synchronized(logLock) {
                if (logs.size == MAX_LOG_RECORDS) logs.removeFirst()
                logs.addLast(
                    LuaAdapterLogRecord(
                        instanceId = generationContext.instanceId,
                        generation = hostGeneration,
                        timestampMillis = timestamp,
                        level = requireNotNull(level),
                        payload = requireNotNull(payload),
                    ),
                )
            }
            if (!closed.get() && generationContext.isActive()) {
                val serializedPayload = try {
                    canonicalSerialize(payload, 262_144)
                } catch (e: Exception) {
                    null
                }
                if (serializedPayload != null) {
                    logSink.tryPublish(
                        instanceId = generationContext.instanceId,
                        generation = hostGeneration,
                        timestampMillis = timestamp,
                        level = requireNotNull(level),
                        payloadJson = serializedPayload
                    )
                }
            }
        }
    }

    private fun recordDiagnostic(message: String) = synchronized(logLock) {
        if (localDiagnostics.size == MAX_LOCAL_DIAGNOSTICS) localDiagnostics.removeFirst()
        localDiagnostics.addLast(message)
    }

    /** Parses normalized native callback output without throwing. */
    private fun luaValueFromOutcome(value: String?): CallbackInvocationResult {
        if (value == null || value.isEmpty() || value == "null") {
            return CallbackInvocationResult.Success(LuaValue.Nil)
        }
        return when (val parsed = LuaValue.fromJsonString(value)) {
            is JsonParsingResult.Success -> CallbackInvocationResult.Success(parsed.value)
            is JsonParsingResult.Failure -> CallbackInvocationResult.InvalidOutcome(parsed.diagnostic)
        }
    }

    /**
     * Extract a boolean readiness from a callback return table.
     *
     * Per spec: only `{ready = true}` is ready; all malformed or absent values are not ready.
     */
    private suspend fun readinessContext(): Map<String, LuaValue> {
        val capabilityContext = linkedMapOf<String, LuaValue>()
        PackageCapability.ALL.forEach { capabilityId ->
            if (!capabilities.isPublicCapabilityDeclared(capabilityId)) return@forEach
            val availability = when (capabilityId) {
                PackageCapability.AUDIO_TRANSCRIPTION ->
                    availabilityFor(CapabilityKey.Transcription)
                PackageCapability.AUDIO_SYNTHESIS ->
                    availabilityFor(CapabilityKey.Synthesis)
                PackageCapability.AUDIO_PLAYBACK -> {
                    val audio = availabilityFor(CapabilityKey.AudioOperation)
                    val playback = availabilityFor(CapabilityKey.DeferredAudioPlayback)
                    combineAvailability(audio, playback)
                }
                PackageCapability.STORAGE_FILES ->
                    if (storagePort != null) "available" else "unavailable"
                PackageCapability.AUDIO_FILES ->
                    if (audioFilePort != null) "available" else "unavailable"
                PackageCapability.KEYBOARD_OUTPUT -> availabilityFor(CapabilityKey.TextOutput)
                PackageCapability.NETWORK_HTTP ->
                    // Adapter-mediated transport (design D6): available only when composition
                    // wired a generic HTTPS adapter for this generation. No provider request,
                    // credential, or OpenAI shape is consulted here.
                    if (httpHostAdapter != null) "available" else "unavailable"
                PackageCapability.PROFILES_READ ->
                    // Detached selected-profile grants are installed before startup (design D4);
                    // the capability is available once declared. Per-profile lookup denial is
                    // resolved by talkcan.profiles.get, not by readiness availability.
                    "available"
                PackageCapability.SECRETS_READ ->
                    // Adapter-mediated protected-secret resolution (design D5): available only
                    // when composition wired a protected store adapter for this generation.
                    if (secretReadAdapter != null) "available" else "unavailable"
                PackageCapability.WORK_QUEUE ->
                    // Adapter-mediated durable work (design D9): available only when composition
                    // wired a work store/coordinator adapter for this generation.
                    if (workHostAdapter != null) "available" else "unavailable"
                else -> return@forEach
            }
            capabilityContext[capabilityId] = LuaValue.StringValue(availability)
        }
        val mounts = resourceDeclarations.mounts.associate { declaration ->
            val status = mountReadinessStatus
                ?.status(declaration.id)
                ?.takeIf {
                    it == "available" || it == "read-only" ||
                        it == "needs-reauthorization" || it == "unavailable"
                }
                ?: "unavailable"
            declaration.id to LuaValue.StringValue(status)
        }
        val result = linkedMapOf<String, LuaValue>(
            "capabilities" to LuaValue.Map(capabilityContext),
            "resources" to LuaValue.Map(
                mapOf("mounts" to LuaValue.Map(mounts)),
            ),
        )
        // 3.8: project detached reference states only when the package declares
        // required dynamic fields, preserving the exact context shape otherwise.
        if (requiredDynamicFields.isNotEmpty()) {
            result["references"] = LuaValue.Map(projectDynamicReferences())
        }
        return result
    }

    /**
     * 3.8/3.9: Project a detached `available`/`unavailable` scalar state for every
     * required dynamic field, resolved against the host choice registry without
     * retaining host profile objects. Re-resolved on each readiness refresh because
     * [readinessContext] is rebuilt on every call. Resolution failure or a missing
     * resolver projects `unavailable`; the persisted scalar is never replaced and
     * call-time profile revalidation stays in the host output service.
     */
    private suspend fun projectDynamicReferences(): Map<String, LuaValue> {
        val resolver = dynamicChoiceResolver
        if (resolver == null) {
            return requiredDynamicFields.associate { field ->
                field.id to LuaValue.StringValue("unavailable")
            }
        }
        val payload = runCatching { configuration.payload.toJsonObject() }.getOrNull()
        val result = linkedMapOf<String, LuaValue>()
        for (field in requiredDynamicFields) {
            val state = try {
                val selectedValue = payload?.optString(field.id, "")?.takeIf { it.isNotBlank() }
                val dependencyValue = field.dependsOnFieldId
                    ?.let { dependencyId -> payload?.optString(dependencyId, "")?.takeIf { it.isNotBlank() } }
                val resolution = resolver.resolve(
                    io.talkcan.model.DynamicConfigurationChoiceRequest(
                        source = field.source,
                        dependencyValue = dependencyValue,
                        sourceKind = field.effectiveSourceKind,
                        packageRevision = packageRevision,
                        requestingFieldId = field.id,
                        dependencyFieldId = field.dependsOnFieldId,
                    ),
                )
                when (resolution.referenceState(selectedValue)) {
                    io.talkcan.model.DynamicConfigurationReferenceState.AVAILABLE -> "available"
                    io.talkcan.model.DynamicConfigurationReferenceState.UNAVAILABLE -> "unavailable"
                }
            } catch (_: Exception) {
                "unavailable"
            }
            result[field.id] = LuaValue.StringValue(state)
        }
        return result
    }

    private suspend fun availabilityFor(key: CapabilityKey<*>): String = try {
        when (val result = capabilities.availability(key)) {
            is CapabilityAvailabilityResult.State -> when (result.availability) {
                CapabilityAvailability.Available -> "available"
                CapabilityAvailability.Recoverable -> "recoverable"
                is CapabilityAvailability.Unavailable -> "unavailable"
            }
            else -> "unavailable"
        }
    } catch (_: Exception) {
        "unavailable"
    }

    private fun combineAvailability(first: String, second: String): String = when {
        first == "unavailable" || second == "unavailable" -> "unavailable"
        first == "recoverable" || second == "recoverable" -> "recoverable"
        else -> "available"
    }

    private sealed interface ReadinessProjection {
        data class Valid(
            val ready: Boolean,
            val statusPresent: Boolean,
            val status: String?,
            val prepare: List<String>,
        ) : ReadinessProjection

        data object Malformed : ReadinessProjection
    }

    private fun CallbackInvocationResult.readinessProjection(): ReadinessProjection {
        val value = (this as? CallbackInvocationResult.Success)?.value as? LuaValue.Map
            ?: return ReadinessProjection.Malformed
        val keys = value.pairs.keys
        // 5.1: exact-key result; `ready` is required, `status` and `prepare` optional.
        if ("ready" !in keys || !ALLOWED_READINESS_KEYS.containsAll(keys)) {
            return ReadinessProjection.Malformed
        }
        val ready = (value.pairs["ready"] as? LuaValue.Bool)?.value
            ?: return ReadinessProjection.Malformed
        val statusPresent = "status" in keys
        val status: String? = if (statusPresent) {
            val raw = (value.pairs["status"] as? LuaValue.StringValue)?.value
                ?: return ReadinessProjection.Malformed
            if (!isWellFormedUtf16(raw) || raw.toByteArray(Charsets.UTF_8).size > MAX_READINESS_STATUS_BYTES) {
                return ReadinessProjection.Malformed
            }
            raw
        } else {
            null
        }
        val prepare = decodePrepareRequest(value.pairs["prepare"], ready)
            ?: return ReadinessProjection.Malformed
        return ReadinessProjection.Valid(ready, statusPresent, status, prepare)
    }

    /**
     * 5.1/5.2: Decode the optional bounded, duplicate-free preparation request.
     * Returns null when the readiness result must be treated as malformed: a
     * non-array (including metatable-backed) value, an over-bound list, a
     * non-string/blank/over-bound element, a duplicate, an unknown or undeclared
     * capability, a non-preparable capability, or a ready result carrying a
     * nonempty preparation request.
     */
    private fun decodePrepareRequest(value: LuaValue?, ready: Boolean): List<String>? {
        if (value == null) return emptyList()
        val array = value as? LuaValue.Array ?: return null
        if (array.values.size > MAX_READINESS_PREPARE_REQUESTS) return null
        val ids = LinkedHashSet<String>()
        for (element in array.values) {
            val id = (element as? LuaValue.StringValue)?.value ?: return null
            if (!isWellFormedUtf16(id)) return null
            if (id.toByteArray(Charsets.UTF_8).size > MAX_PREPARE_CAPABILITY_ID_BYTES) return null
            if (!ids.add(id)) return null
            if (!capabilities.isPublicCapabilityDeclared(id)) return null
            if (!preparerRegistry.isPreparable(id)) return null
        }
        if (ready && ids.isNotEmpty()) return null
        return ids.toList()
    }

    private fun isWellFormedUtf16(value: String): Boolean {
        var index = 0
        while (index < value.length) {
            val character = value[index]
            if (character.isHighSurrogate()) {
                if (index + 1 >= value.length || !value[index + 1].isLowSurrogate()) return false
                index += 2
            } else if (character.isLowSurrogate()) {
                return false
            } else {
                index++
            }
        }
        return true
    }

    /** Fails the actor and atomically projects its terminal unavailability to the registry. */
    private fun failProgramImage(diagnostic: String): CallbackInvocationResult.Failure {
        actor.latchProgramImageFailure()
        recordDiagnostic(diagnostic)
        updateSnapshot(
            preparation = ChannelPreparationAvailability.Unavailable(
                ChannelPreparationReason.RuntimeFailed(diagnostic),
            ),
            executionStatus = ChannelExecutionStatus.FAILED,
        )
        return CallbackInvocationResult.Failure(diagnostic)
    }

    private fun discardStagedTasks() {
        (generationContext as? ActorRuntimeHostContext)?.discardActorStagedTasks()
    }

    /**
     * Close and discard everything on activation failure.
     */
    private suspend fun discardStagedAndClose() {
        readyPublication.complete(false)
        audioFilePort?.close()
        audioFilePort = null
        recordingHost = null
        actor.latchProgramImageFailure()
        discardStagedTasks()
        actor.close()
        audioRegistry?.close()
        audioRegistry = null
        mountHandleCache.clear()
        resourceClose?.invoke()
        updateSnapshot(
            preparation = ChannelPreparationAvailability.Unavailable(
                ChannelPreparationReason.RuntimeFailed("activation failed"),
            ),
            executionStatus = ChannelExecutionStatus.FAILED,
        )
    }

    private fun updateSnapshot(
        preparation: ChannelPreparationAvailability? = null,
        executionStatus: ChannelExecutionStatus? = null,
        summary: String? = null,
    ) = synchronized(lifecycleLock) {
        if (closed.get()) return@synchronized
        _snapshot.value = _snapshot.value.copy(
            preparation = preparation ?: _snapshot.value.preparation,
            executionStatus = executionStatus ?: _snapshot.value.executionStatus,
            summary = summary ?: _snapshot.value.summary,
        )
    }

    /**
     * Refresh the content-free generic work projection on the snapshot from the
     * durable-work adapter (task 14.1/14.2, design D12). Only instances that wired
     * a [WorkHostAdapter] (i.e. declare `work.queue`) publish a non-null projection;
     * built-in/Journal/Keyboard keep `workProjection = null` and their existing
     * snapshot shape. The projection derives from work metadata only — no payload,
     * effect, transcript, profile, or secret content reaches the snapshot.
     */
    private fun refreshWorkProjection() {
        val adapter = workHostAdapter ?: return
        val projection = try {
            adapter.workProjection()
        } catch (_: Exception) {
            // A projection failure is content-free diagnostics only; it must not
            // disturb the snapshot or sibling state (task 14.4 failure isolation).
            return
        }
        synchronized(lifecycleLock) {
            if (closed.get()) return@synchronized
            if (_snapshot.value.workProjection != projection) {
                _snapshot.value = _snapshot.value.copy(workProjection = projection)
            }
        }
    }

    // ── Input target ─────────────────────────────────────────────────────

    private inner class LuaInputTarget : ChannelInputTarget {
        private val sessionId = UUID.randomUUID().toString()
        private var sampleRate: Int = 0

        override fun onInputStarted(session: ChannelAudioInputSession) {
            if (!closed.get()) {
                synchronized(inputLock) { inputCancellationRequested = false }
                sampleRate = session.sampleRate
                updateSnapshot(executionStatus = ChannelExecutionStatus.RECORDING)
            }
        }

        override suspend fun onInputReleased(recording: RecordedPcm): ChannelInputResult {
            if (closed.get()) return ChannelInputResult.None
            val cancelled = synchronized(inputLock) { inputCancellationRequested }
            if (cancelled || recording.isEmpty) {
                updateSnapshot(executionStatus = if (cancelled) ChannelExecutionStatus.IDLE else ChannelExecutionStatus.FAILED)
                return ChannelInputResult.None
            }
            updateSnapshot(executionStatus = ChannelExecutionStatus.PROCESSING)
            val callback = callbacks["handle_input"]
            if (callback == null) {
                updateSnapshot(executionStatus = ChannelExecutionStatus.FAILED)
                return ChannelInputResult.None
            }
            val registry = audioRegistry ?: run {
                if (!closed.get()) updateSnapshot(executionStatus = ChannelExecutionStatus.FAILED)
                return ChannelInputResult.None
            }
            val owner = LuaOpaqueAudioRegistry.Owner.Input(UUID.randomUUID().toString())
            val opaqueRecording = opaqueAudioRecording(recording, capabilities.identity.runtimeGeneration)
            val token = registry.admitCaptured(owner, opaqueRecording)
            if (token == null) {
                updateSnapshot(executionStatus = ChannelExecutionStatus.FAILED)
                return ChannelInputResult.None
            }
            val outcome = try {
                val effectiveSampleRate = if (sampleRate > 0) sampleRate else recording.sampleRate
                val durationMillis = if (effectiveSampleRate > 0) {
                    recording.samples.size * 1_000L / effectiveSampleRate
                } else {
                    0L
                }
                val capturedAt = Instant.now(clock)
                val localTime = capturedAt.atZone(clock.zone)
                val event = LuaValue.Map(
                    mapOf(
                        "event" to LuaValue.StringValue("capture"),
                        "session" to LuaValue.StringValue(sessionId),
                        "timestamp" to LuaValue.Map(
                            mapOf(
                                "unix_ms" to LuaValue.Integer(capturedAt.toEpochMilli()),
                                "local_time" to LuaValue.Map(
                                    mapOf(
                                        "year" to LuaValue.Integer(localTime.year.toLong()),
                                        "month" to LuaValue.Integer(localTime.monthValue.toLong()),
                                        "day" to LuaValue.Integer(localTime.dayOfMonth.toLong()),
                                        "hour" to LuaValue.Integer(localTime.hour.toLong()),
                                        "minute" to LuaValue.Integer(localTime.minute.toLong()),
                                        "second" to LuaValue.Integer(localTime.second.toLong()),
                                        "millisecond" to LuaValue.Integer(
                                            (localTime.nano / 1_000_000).toLong(),
                                        ),
                                        "utc_offset_minutes" to LuaValue.Integer(
                                            (localTime.offset.totalSeconds / 60).toLong(),
                                        ),
                                    ),
                                ),
                            ),
                        ),
                        "metadata" to LuaValue.Map(
                            mapOf(
                                "duration_ms" to LuaValue.Integer(durationMillis),
                                "sample_rate" to LuaValue.Integer(effectiveSampleRate.toLong()),
                                "channels" to LuaValue.Integer(1L),
                                "pcm_bytes" to LuaValue.Integer(
                                    recording.samples.size.toLong() * 2L,
                                ),
                            ),
                        ),
                    ),
                )
                val invocation = invokeInputCallbackHandle(callback, event, token.value, owner)
                when (invocation) {
                    is CallbackInvocationResult.Pending -> {
                        val pending = PendingInputExecution(
                            ownerToken = UUID.randomUUID().toString(),
                            operation = invocation.operation,
                            label = invocation.label,
                            terminal = CompletableDeferred(),
                            audioOwner = owner,
                            capturedAudioToken = token.value,
                            semantic = SemanticAudioOperation.create(
                                invocation.operation,
                                (generationContext as ActorRuntimeHostContext).actorIdentity,
                            ),
                        )
                        val cancellationRequested = synchronized(inputLock) {
                            when {
                                closed.get() || pendingInputExecution != null -> null
                                 else -> {
                                    pendingInputExecution = pending
                                    inputCancellationRequested
                                }
                            }
                        }
                        if (cancellationRequested != null) {
                            dispatchPending(pending)
                            // Chained yields: each resume may produce a new semantic
                            // operation (e.g. synthesize -> playback.schedule in TTS,
                            // or transcribe -> synthesize -> playback.schedule in
                            // STT_TTS). Dispatch each successive yield until the
                            // callback terminates or no further progress is made.
                            var lastDispatchedOperationId = pending.operation.operationId.value
                            while (true) {
                                val current = synchronized(inputLock) { pendingInputExecution } ?: break
                                if (current.ownerToken != pending.ownerToken) break
                                if (current.operation.operationId.value == lastDispatchedOperationId) break
                                lastDispatchedOperationId = current.operation.operationId.value
                                dispatchPending(current)
                            }
                        }
                        if (cancellationRequested == null) {
                            CallbackInvocationResult.Failure("input execution is busy")
                        } else {
                            if (cancellationRequested) cancelPendingInput()
                            try {
                                pending.terminal.await()
                            } finally {
                                synchronized(inputLock) {
                                    if (pendingInputExecution?.ownerToken == pending.ownerToken) {
                                        pendingInputExecution = null
                                    }
                                }
                            }
                        }
                    }
                    else -> invocation
                }
            } finally {
                if (!preserveCapturedOnTimeout.getAndSet(false)) registry.invalidateOwner(owner)
            }
            when (outcome) {
                is CallbackInvocationResult.Success -> {
                    val status = outcome.value.strictInputStatus()
                    if (status != null) {
                        if (status == ChannelExecutionStatus.FAILED) {
                            val error = (outcome.value as? LuaValue.Map)
                                ?.pairs?.get("error") as? LuaValue.Map
                            val code = (error?.pairs?.get("code") as? LuaValue.StringValue)
                                ?.value?.take(MAX_READINESS_STATUS_BYTES)
                            val detail = (error?.pairs?.get("detail") as? LuaValue.StringValue)
                                ?.value?.take(MAX_READINESS_STATUS_BYTES)
                            TalkcanLogger.w(
                                "LuaChannel",
                                "input_application_failure code=$code detail=$detail",
                            )
                        }
                        updateSnapshot(executionStatus = status)
                    } else {
                        TalkcanLogger.w("LuaChannel", "input_invalid_terminal")
                        updateSnapshot(executionStatus = ChannelExecutionStatus.FAILED)
                    }
                }
                is CallbackInvocationResult.Failure,
                is CallbackInvocationResult.YieldViolation,
                is CallbackInvocationResult.InvalidOutcome,
                is CallbackInvocationResult.Pending -> {
                    TalkcanLogger.w(
                        "LuaChannel",
                        "input_runtime_failure type=${outcome::class.simpleName} " +
                            "detail=${outcome.diagnostic().take(MAX_READINESS_STATUS_BYTES)}",
                    )
                    updateSnapshot(executionStatus = ChannelExecutionStatus.FAILED)
                }
            }
            launchDetachedInputContinuation()
            return ChannelInputResult.None
        }

        override fun onInputPlaybackCompleted() = Unit

        override fun onInputCancelled(reason: String) {
            synchronized(inputLock) { inputCancellationRequested = true }
            cancelPendingInput()
            if (!closed.get()) updateSnapshot(executionStatus = ChannelExecutionStatus.IDLE)
        }


        override fun onInputFailed(reason: String) {
            if (!closed.get()) updateSnapshot(executionStatus = ChannelExecutionStatus.FAILED)
        }
    }
    private suspend fun executeTranscription(pending: PendingInputExecution) {
        if (!capabilities.isPublicCapabilityDeclared(PackageCapability.AUDIO_TRANSCRIPTION)) {
            resumeInput(pending.ownerToken, pending.operation.operationId.value, false, "E_CAPABILITY_UNDECLARED")
            return
        }
        val registry = audioRegistry
        val recording = registry?.resolve(LuaOpaqueAudioRegistry.Token(pending.capturedAudioToken), pending.audioOwner, LuaOpaqueAudioRegistry.Kind.Captured) as? LuaOpaqueAudioRegistry.Resolution.Captured
        if (recording == null) { resumeInput(pending.ownerToken, pending.operation.operationId.value, false, "E_STALE"); return }
        val result = when (val acquisition = capabilities.acquire(CapabilityKey.Transcription)) {
            is CapabilityAcquisition.Available -> {
                val value = awaitSemanticBackend(
                    operation = {
                        acquisition.lease.use { it.transcribe(recording.recording) }
                    },
                )
                if (value == null) {
                    preserveCapturedOnTimeout.set(true)
                    resumeSemantic(pending, false, "E_TIMEOUT")
                    return
                } else {
                    value
                }
            }
            is CapabilityAcquisition.Recoverable, is CapabilityAcquisition.Unavailable -> CapabilityOperationResult.Unavailable(CapabilityUnavailableReason.NOT_CONFIGURED)
            is CapabilityAcquisition.Denied -> CapabilityOperationResult.Denied(acquisition.reason)
            CapabilityAcquisition.Closed -> CapabilityOperationResult.Closed
            CapabilityAcquisition.Cancelled -> CapabilityOperationResult.Cancelled
            is CapabilityAcquisition.Failed -> CapabilityOperationResult.Failed(CapabilityFailureReason.HOST_FAILURE)
        }
        val (success, value) = when (result) {
            is CapabilityOperationResult.Success -> result.value.text.takeIf { it.toByteArray(Charsets.UTF_8).size <= 16 * 1024 }?.let { true to it } ?: (false to "E_HOST_FAILURE")
            is CapabilityOperationResult.Unavailable -> false to "E_UNAVAILABLE"
            is CapabilityOperationResult.Denied -> false to "E_CAPABILITY_UNDECLARED"
            CapabilityOperationResult.Closed -> false to "E_CLOSED"
            CapabilityOperationResult.Cancelled -> false to "E_CANCELLED"
            is CapabilityOperationResult.Failed -> false to "E_HOST_FAILURE"
        }
        resumeSemantic(pending, success, value)
    }

    private suspend fun resumeSemantic(
        pending: PendingInputExecution,
        success: Boolean,
        value: String,
        normalizeAudioError: Boolean = true,
    ) {
        if (!pending.semantic.claimTerminal()) return
        pending.semantic.completeClaimed(
            if (success) CallbackInvocationResult.Success(LuaValue.StringValue(value))
            else CallbackInvocationResult.Failure(value),
        )
        resumeInput(pending.ownerToken, pending.operation.operationId.value, success, value, normalizeAudioError)
    }

    /** Claims one pending semantic operation and dispatches it by its typed kind. */
    private suspend fun dispatchPending(pending: PendingInputExecution) {
        val requestId = pending.label.toLongOrNull()
        if (requestId == null) {
            resumeInput(
                pending.ownerToken,
                pending.operation.operationId.value,
                false,
                "E_HOST_FAILURE",
            )
            return
        }
        when (val claim = bridge.claimHostOperation(stateHandle, requestId)) {
            is HostOperationClaim.Rejected ->
                resumeInput(
                    pending.ownerToken,
                    pending.operation.operationId.value,
                    false,
                    claim.errorCode,
                )
            is HostOperationClaim.Admitted -> when (claim.kind) {
                HostOperationKind.TRANSCRIBE -> executeTranscription(pending)
                HostOperationKind.SYNTHESIZE -> executeSynthesis(pending, claim)
                HostOperationKind.PLAYBACK -> executePlayback(pending, claim)
                HostOperationKind.AUDIO_OPEN,
                HostOperationKind.AUDIO_EXPORT -> executeAudioFileOperation(pending, claim)
                HostOperationKind.FS_MKDIR,
                HostOperationKind.FS_STAT,
                HostOperationKind.FS_LIST,
                HostOperationKind.FS_READ_TEXT,
                HostOperationKind.FS_WRITE_TEXT,
                HostOperationKind.FS_REMOVE -> executeFsOperation(pending, claim)
                HostOperationKind.KEYBOARD_SEND_TEXT,
                HostOperationKind.KEYBOARD_SEND_KEY -> executeKeyboardOperation(pending, claim)
                HostOperationKind.SECRET_READ,
                HostOperationKind.HTTP_REQUEST,
                HostOperationKind.WORK_SUBMIT,
                HostOperationKind.WORK_RECEIVE,
                HostOperationKind.WORK_BEGIN_EFFECT,
                HostOperationKind.WORK_COMMIT_EFFECT,
                HostOperationKind.WORK_COMPLETE,
                HostOperationKind.WORK_FAIL -> executeTypedOperation(pending, claim)
            }
        }
    }

    /**
     * Dispatches one claimed portable recording-file operation. The native
     * broker has already validated Lua shape, capability declaration, mount
     * access, and execution ownership; the generic ports revalidate the live
     * mount and Recording handles before any provider or codec effect.
     */
    private suspend fun executeAudioFileOperation(
        pending: PendingInputExecution,
        claim: HostOperationClaim.Admitted,
    ) {
        val port = audioFilePort
        val recordings = recordingHost
        if (port == null || recordings == null) {
            resumeSemantic(pending, false, "E_UNAVAILABLE", normalizeAudioError = false)
            return
        }
        val declarationId = claim.declarationId
        val path = claim.path
        val format = claim.format?.let(AudioFileFormat::fromToken)
        if (declarationId == null || path == null || format == null) {
            resumeSemantic(pending, false, "E_INVALID_ARGUMENT", normalizeAudioError = false)
            return
        }
        val storage = storagePort
        if (storage == null) {
            resumeSemantic(pending, false, "E_MOUNT_UNAVAILABLE", normalizeAudioError = false)
            return
        }
        val mount = when (val resolved = resolveMountHandle(storage, declarationId)) {
            is FilesystemOutcome.Success -> resolved.value
            is FilesystemOutcome.Failure -> {
                resumeSemantic(pending, false, resolved.error.code.name, normalizeAudioError = false)
                return
            }
        }
        val owner = ExecutionOwner(
            id = pending.audioOwner.id,
            kind = when (pending.audioOwner) {
                is LuaOpaqueAudioRegistry.Owner.Input -> ExecutionOwnerKind.INPUT
                is LuaOpaqueAudioRegistry.Owner.Task -> ExecutionOwnerKind.TASK
            },
            generation = hostGeneration,
        )
        when (claim.kind) {
            HostOperationKind.AUDIO_OPEN -> {
                if (format != AudioFileFormat.WAV_PCM_S16LE) {
                    resumeSemantic(pending, false, "E_INVALID_ARGUMENT", normalizeAudioError = false)
                    return
                }
                val outcome = awaitSemanticBackend(
                    operation = { port.open(owner, mount, path, AudioOpenOptions(format)) },
                    onLateResult = { late ->
                        if (late is AudioFileOutcome.Success) recordings.dispose(late.value)
                    },
                )
                if (outcome == null) {
                    resumeSemantic(pending, false, "E_TIMEOUT", normalizeAudioError = false)
                    return
                }
                when (outcome) {
                    is AudioFileOutcome.Failure ->
                        resumeSemantic(
                            pending,
                            false,
                            outcome.error.code.name,
                            normalizeAudioError = false,
                        )
                    is AudioFileOutcome.Success -> {
                        val handle = outcome.value
                        when (val described = port.describe(handle, owner)) {
                            is AudioFileOutcome.Failure -> {
                                recordings.dispose(handle)
                                resumeSemantic(
                                    pending,
                                    false,
                                    described.error.code.name,
                                    normalizeAudioError = false,
                                )
                            }
                            is AudioFileOutcome.Success -> {
                                val metadata = described.value
                                val result = org.json.JSONObject()
                                    .put("token", recordings.tokenFor(handle))
                                    .put(
                                        "metadata",
                                        org.json.JSONObject()
                                            .put("sample_rate", metadata.sampleRate)
                                            .put("channels", metadata.channels)
                                            .put("duration_ms", metadata.durationMs)
                                            .put("pcm_bytes", metadata.pcmBytes),
                                    )
                                    .toString()
                                resumeSemantic(
                                    pending,
                                    true,
                                    result,
                                    normalizeAudioError = false,
                                )
                            }
                        }
                    }
                }
            }
            HostOperationKind.AUDIO_EXPORT -> {
                val token = claim.audioToken
                if (token == null) {
                    resumeSemantic(
                        pending,
                        false,
                        "E_INVALID_ARGUMENT",
                        normalizeAudioError = false,
                    )
                    return
                }
                val mode = when (claim.mode) {
                    "create-new" -> AudioExportMode.CREATE_NEW
                    "replace" -> AudioExportMode.REPLACE
                    else -> {
                        resumeSemantic(
                            pending,
                            false,
                            "E_INVALID_ARGUMENT",
                            normalizeAudioError = false,
                        )
                        return
                    }
                }
                val outcome = awaitSemanticBackend(
                    operation = {
                        port.export(
                            owner = owner,
                            recording = recordings.handleFor(
                                LuaOpaqueAudioRegistry.Token(token),
                            ),
                            mount = mount,
                            path = path,
                            options = AudioExportOptions(format, mode),
                        )
                    },
                )
                if (outcome == null) {
                    resumeSemantic(pending, false, "E_TIMEOUT", normalizeAudioError = false)
                    return
                }
                when (outcome) {
                    is AudioFileOutcome.Failure ->
                        resumeSemantic(
                            pending,
                            false,
                            outcome.error.code.name,
                            normalizeAudioError = false,
                        )
                    is AudioFileOutcome.Success -> {
                        val result = outcome.value
                        val value = org.json.JSONObject()
                            .put("status", "written")
                            .put("format", result.format.token)
                            .put("sample_rate", result.sampleRate)
                            .put("channels", result.channels)
                            .put("duration_ms", result.durationMs)
                            .put("bytes", result.bytes)
                            .toString()
                        resumeSemantic(pending, true, value, normalizeAudioError = false)
                    }
                }
            }
            else -> Unit
        }
    }


    /**
     * Dispatch one claimed filesystem operation to the generic [storagePort].
     *
     * Generic only: no semantic labels and no Journal branches. The claim
     * carries the declaration id and validated logical path/arguments; this
     * resolves the platform [io.talkcan.storage.MountHandle]
     * (revalidated per operation), performs exactly one bounded port call, and
     * resumes with a normalized JSON result on success or a portable error code
     * on failure. Success, provider failure, cancellation, revocation,
     * generation close, and process teardown all race through the same
     * exactly-once terminal gate as the audio operations via [resumeSemantic].
     */
    private suspend fun executeFsOperation(
        pending: PendingInputExecution,
        claim: HostOperationClaim.Admitted,
    ) {
        val completion = performFsOperation(claim)
        resumeSemantic(
            pending,
            completion.success,
            completion.value,
            normalizeAudioError = false,
        )
    }

    private suspend fun performFsOperation(
        claim: HostOperationClaim.Admitted,
    ): HostOperationCompletion {
        val port = storagePort ?: return HostOperationCompletion(false, "E_CLOSED")
        val declarationId = claim.declarationId
            ?: return HostOperationCompletion(false, "E_INVALID_ARGUMENT")
        val path = claim.path
            ?: return HostOperationCompletion(false, "E_INVALID_ARGUMENT")
        val handle = when (val resolved = resolveMountHandle(port, declarationId)) {
            is FilesystemOutcome.Success -> resolved.value
            is FilesystemOutcome.Failure ->
                return HostOperationCompletion(false, resolved.error.code.name)
        }
        val outcome: FilesystemOutcome<Any> = try {
            when (claim.kind) {
                HostOperationKind.FS_MKDIR ->
                    port.mkdir(handle, path, MkdirOptions(claim.parents))
                HostOperationKind.FS_STAT -> port.stat(handle, path)
                HostOperationKind.FS_LIST -> port.list(
                    handle,
                    path,
                    ListOptions(
                        limit = if (claim.limit > 0) {
                            claim.limit.toInt()
                        } else {
                            FS_DEFAULT_LIST_LIMIT
                        },
                        cursor = claim.cursor?.let { ListCursor(it) },
                    ),
                )
                HostOperationKind.FS_READ_TEXT ->
                    port.readText(handle, path, ReadTextOptions(claim.maxBytes))
                HostOperationKind.FS_WRITE_TEXT -> {
                    val mode = when (claim.mode) {
                        "replace" -> WriteMode.REPLACE
                        else -> WriteMode.CREATE_NEW
                    }
                    port.writeText(
                        handle,
                        path,
                        claim.text.orEmpty(),
                        WriteTextOptions(mode),
                    )
                }
                HostOperationKind.FS_REMOVE ->
                    port.remove(handle, path, RemoveOptions(claim.missingOk))
                else -> return HostOperationCompletion(false, "E_UNSUPPORTED")
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            TalkcanLogger.w(
                FS_DIAGNOSTIC_TAG,
                "port_throw kind=${claim.kind} type=${failure.javaClass.simpleName}",
            )
            return HostOperationCompletion(false, "E_IO")
        }
        return when (outcome) {
            is FilesystemOutcome.Success ->
                HostOperationCompletion(true, fsResultJson(claim.kind, outcome.value))
            is FilesystemOutcome.Failure -> {
                TalkcanLogger.w(
                    FS_DIAGNOSTIC_TAG,
                    "port_failure kind=${claim.kind} code=${outcome.error.code}",
                )
                HostOperationCompletion(false, outcome.error.code.name)
            }
        }
    }

    /** Resolve (and cache) the platform mount handle for a declaration id. */
    private fun resolveMountHandle(
        port: io.talkcan.storage.MountedStoragePort,
        declarationId: String,
    ): FilesystemOutcome<io.talkcan.storage.MountHandle> {
        mountHandleCache[declarationId]?.let { return FilesystemOutcome.Success(it) }
        return when (val result = port.mount(declarationId)) {
            is FilesystemOutcome.Success -> {
                mountHandleCache[declarationId] = result.value
                result
            }
            is FilesystemOutcome.Failure -> result
        }
    }

    /**
     * 8.5-8.9: Submits one claimed keyboard-output operation through the
     * generation-scoped adapter and returns the normalized resume pair
     * `(success, value)`. The native broker has already validated the request
     * table shape, bounds, and execution ownership; the host rechecks capability
     * declaration, profile liveness, and admission capacity before any physical
     * effect. Terminal outcomes map to the normalized result/error contract:
     * `delivered`/`rejected`/`failed`/`indeterminate` produce a JSON result table
     * (success=true); capacity/closed/revoked produce stable error codes.
     * [ownerKind] distinguishes input, yielded-SOS, and managed-task owners; the
     * current active channel selection is never an authorization condition.
     */
    private suspend fun submitKeyboardOperation(
        claim: HostOperationClaim.Admitted,
        ownerKind: OutputExecutionOwnerKind,
        ownerToken: String,
    ): Pair<Boolean, String> {
        if (!capabilities.isPublicCapabilityDeclared(PackageCapability.KEYBOARD_OUTPUT)) {
            return false to "E_CAPABILITY_UNDECLARED"
        }
        val factory = keyboardOutputAdapterFactory ?: return false to "E_UNAVAILABLE"
        val profile = claim.profile
        if (profile.isNullOrBlank()) return false to "E_INVALID_ARGUMENT"
        // 1.4: call-time UTF-8 bound revalidation mirroring the native pre-yield
        // check, using the central TextOutputProfile.MAX_BYTES constant.
        if (profile.toByteArray(Charsets.UTF_8).size > TextOutputProfile.MAX_BYTES) {
            return false to "E_INVALID_ARGUMENT"
        }
        val adapter = factory(capabilities.identity, OutputExecutionOwner(ownerKind, ownerToken))
        val submission = when (claim.kind) {
            HostOperationKind.KEYBOARD_SEND_TEXT -> {
                val text = claim.text
                if (text.isNullOrEmpty()) return false to "E_INVALID_ARGUMENT"
                if (text.toByteArray(Charsets.UTF_8).size > PackageConfigurationLimits.MAX_STRING_VALUE_BYTES) {
                    return false to "E_INVALID_ARGUMENT"
                }
                adapter.sendText(TextOutputRequest(text, TextOutputProfile(profile)))
            }
            HostOperationKind.KEYBOARD_SEND_KEY -> {
                val key = when (claim.key) {
                    "enter" -> TextOutputKey.ENTER
                    "escape" -> TextOutputKey.ESCAPE
                    else -> return false to "E_INVALID_ARGUMENT"
                }
                adapter.sendKey(TextKeyRequest(key, TextOutputProfile(profile)))
            }
            else -> return false to "E_HOST_FAILURE"
        }
        return when (submission) {
            is KeyboardOutputSubmission.Completed -> {
                val json = org.json.JSONObject()
                when (val outcome = submission.outcome) {
                    is TextDeliveryOutcome.Delivered -> json.put("status", "delivered")
                    is TextDeliveryOutcome.Rejected ->
                        json.put("status", "rejected").put("reason", outcome.reason.name.lowercase())
                    is TextDeliveryOutcome.Failed ->
                        json.put("status", "failed").put("reason", outcome.reason.name.lowercase())
                    is TextDeliveryOutcome.Indeterminate ->
                        json.put("status", "indeterminate").put("reason", outcome.reason.name.lowercase())
                }
                true to json.toString()
            }
            KeyboardOutputSubmission.Busy -> false to "E_BUSY"
            KeyboardOutputSubmission.Closed -> false to "E_CLOSED"
            KeyboardOutputSubmission.Revoked -> false to "E_CLOSED"
        }
    }

    /** 8.5-8.9: Input-path keyboard dispatch; resumes the suspended input owner. */
    private suspend fun executeKeyboardOperation(
        pending: PendingInputExecution,
        claim: HostOperationClaim.Admitted,
    ) {
        val (success, value) = submitKeyboardOperation(claim, OutputExecutionOwnerKind.INPUT, pending.ownerToken)
        resumeInput(pending.ownerToken, pending.operation.operationId.value, success, value, normalizeAudioError = false)
    }

    /**
     * 8.4/9.2/12.10: input-path dispatch for typed generic host operations
     * (protected secret reads, HTTPS requests, durable work). The native
     * broker has already validated capability, context, and execution
     * ownership; the typed adapter revalidates the payload and drives the
     * generic store/transport. A missing adapter fails closed with no host
     * effect.
     */
    private suspend fun executeTypedOperation(
        pending: PendingInputExecution,
        claim: HostOperationClaim.Admitted,
    ) {
        val adapter = when (claim.kind) {
            HostOperationKind.SECRET_READ -> secretReadAdapter
            HostOperationKind.HTTP_REQUEST -> httpHostAdapter
            else -> workHostAdapter
        }
        val completion = adapter?.complete(claim) ?: TypedHostCompletion.failure("E_DENIED")
        if (claim.kind != HostOperationKind.SECRET_READ && claim.kind != HostOperationKind.HTTP_REQUEST) {
            // A work mutation can change queued/active/terminal phase; republish the
            // content-free projection so host presentation tracks durable work state
            // (task 14.1). No-op when no work adapter is wired.
            refreshWorkProjection()
        }
        if (claim.kind != HostOperationKind.WORK_SUBMIT || !completion.success) {
            // Callback failure (or a non-submission operation) resumes under input
            // ownership; a submission rejected before commit must never detach.
            resumeInput(
                pending.ownerToken,
                pending.operation.operationId.value,
                completion.success,
                completion.value,
                normalizeAudioError = false,
            )
            return
        }
        // Task 13.1: the durable admission committed. Detach the callback
        // continuation from Recording/route/callback/input-execution ownership;
        // the input phase finalizes with exact success and launches the remainder
        // as a generation-owned task after release. A commit result that lost the
        // slot to cancel/close is already finalized by the winner: the durable
        // item stands and nothing resumes.
        detachCommittedSubmission(pending, completion.value)
    }

    /**
     * Task 13.1: finalize the input phase after a durable submission commits.
     * Retires the input execution slot, claims the semantic terminal with exact
     * success, and completes the input terminal so the PTT pipeline releases
     * Recording and route ownership. The detached continuation is stashed for
     * the input phase to launch after release. Returns false when cancel/close
     * already finalized the slot — the durable item stands, no input resource
     * is touched, and no continuation is stashed.
     */
    private fun detachCommittedSubmission(
        pending: PendingInputExecution,
        submitResult: String,
    ): Boolean {
        val exactSuccess = CallbackInvocationResult.Success(
            LuaValue.Map(mapOf("ok" to LuaValue.Bool(true))),
        )
        val claimed = synchronized(inputLock) {
            if (closed.get() || !generationContext.isActive() ||
                pendingInputExecution?.ownerToken != pending.ownerToken
            ) {
                false
            } else {
                retiredInputOwnerToken = pending.ownerToken
                pendingInputExecution = null
                detachedInputContinuation = DetachedInputContinuation(pending.operation, submitResult)
                true
            }
        }
        if (!claimed) return false
        pending.semantic.terminalize(exactSuccess)
        pending.terminal.complete(exactSuccess)
        return true
    }

    /**
     * Task 13.1: resume the detached remainder of a handle_input callback under
     * generation/task ownership. The input slot, Recording, and route were
     * released before this task runs; further yields dispatch through the shared
     * managed-task drive loop exactly like a startup-spawned worker.
     */
    private suspend fun runDetachedInputContinuation(
        operation: LuaOperationHandle,
        submitResult: String,
    ) {
        if (closed.get() || !generationContext.isActive()) return
        val audioOwner = LuaOpaqueAudioRegistry.Owner.Task(
            "input-continuation-${operation.operationId.value}",
        )
        try {
            val admission = ScopedSpawnAdmission()
            val gateOutcome = try {
                actor.resumeProgramImageCoroutine(operation, true, submitResult, admission)
            } catch (_: Throwable) {
                admission.release(false)
                recordDiagnostic("detached input continuation resume failed")
                return
            }
            when (val release = admission.releaseFor(gateOutcome)) {
                SpawnAdmissionRelease.Matched -> Unit
                is SpawnAdmissionRelease.Mismatch -> {
                    failProgramImage(release.diagnostic)
                    return
                }
            }
            driveManagedTaskOutcomes(gateOutcome, audioOwner)
        } finally {
            audioRegistry?.invalidateOwner(audioOwner)
        }
    }

    /**
     * Task 13.1: launch a committed submission's detached callback continuation
     * after the input phase finalized and released every runtime-side input
     * resource (slot retired, semantic terminal claimed, Recording invalidated,
     * exact success published). The task carries no PTT ownership; on host
     * dispatchers its first actor entry serializes behind the generation
     * execution lock held by the input execution, so the route is released
     * before the continuation enters Lua.
     */
    private fun launchDetachedInputContinuation() {
        if (closed.get() || !generationContext.isActive()) return
        val continuation = synchronized(inputLock) {
            detachedInputContinuation?.also { detachedInputContinuation = null }
        } ?: return
        if (generationContext.admitTask {
                runDetachedInputContinuation(continuation.operation, continuation.submitResult)
            } is GenerationAdmission.Rejected
        ) {
            recordDiagnostic("detached input continuation was not admitted")
        }
    }

    /** Serialize one portable filesystem result to the exact JSON the Lua module returns. */
    private fun fsResultJson(kind: HostOperationKind, value: Any): String = when (kind) {
        HostOperationKind.FS_MKDIR -> {
            val status = when ((value as MkdirResult).status) {
                MkdirStatus.CREATED -> "created"
                MkdirStatus.EXISTING -> "existing"
            }
            org.json.JSONObject().put("status", status).toString()
        }
        HostOperationKind.FS_STAT -> {
            val stat = value as StatResult
            val obj = org.json.JSONObject()
                .put("name", stat.name)
                .put("kind", if (stat.kind == NodeKind.FILE) "file" else "directory")
                .put("size", stat.sizeBytes)
            stat.modifiedAtUnixMs?.let { obj.put("modified_at_unix_ms", it) }
            obj.toString()
        }
        HostOperationKind.FS_LIST -> {
            val page = value as ListPage
            val entries = org.json.JSONArray()
            for (entry in page.entries) {
                entries.put(
                    org.json.JSONObject()
                        .put("name", entry.name)
                        .put("kind", if (entry.kind == NodeKind.FILE) "file" else "directory"),
                )
            }
            val obj = org.json.JSONObject().put("entries", entries)
            page.nextCursor?.let { obj.put("next_cursor", it.token) }
            obj.toString()
        }
        HostOperationKind.FS_READ_TEXT -> {
            val read = value as ReadTextResult
            org.json.JSONObject().put("text", read.text).put("bytes", read.bytes).toString()
        }
        HostOperationKind.FS_WRITE_TEXT -> {
            val write = value as WriteResult
            org.json.JSONObject().put("status", "written").put("bytes", write.bytes).toString()
        }
        HostOperationKind.FS_REMOVE -> {
            val status = when ((value as RemoveResult).status) {
                RemoveStatus.REMOVED -> "removed"
                RemoveStatus.MISSING -> "missing"
            }
            org.json.JSONObject().put("status", status).toString()
        }
        else -> "{}"
    }

    private suspend fun <T : Any> awaitSemanticBackend(
        operation: suspend () -> T,
        onLateResult: (T) -> Unit = {},
    ): T? {
        val hostContext = generationContext as? ActorRuntimeHostContext
            ?: return withTimeoutOrNull(SEMANTIC_AUDIO_DEADLINE_MILLIS) { operation() }
        val backend = hostContext.actorParentScope.async { operation() }
        val result = withTimeoutOrNull(SEMANTIC_AUDIO_DEADLINE_MILLIS) { backend.await() }
        if (result == null) {
            hostContext.actorParentScope.launch {
                runCatching { backend.await() }.getOrNull()?.let(onLateResult)
            }
        }
        return result
    }
    private suspend fun executeSynthesis(pending: PendingInputExecution, claim: HostOperationClaim.Admitted) {
        if (!capabilities.isPublicCapabilityDeclared(PackageCapability.AUDIO_SYNTHESIS)) { resumeInput(pending.ownerToken, pending.operation.operationId.value, false, "E_CAPABILITY_UNDECLARED"); return }
        val text = claim.text.orEmpty(); val language = claim.language.orEmpty(); val voice = claim.voice.orEmpty(); val speed = claim.speed
        if (text.isBlank() || language.isBlank() || voice.isBlank() || !speed.isFinite() || speed <= 0.0) { resumeInput(pending.ownerToken, pending.operation.operationId.value, false, "E_INVALID_ARGUMENT"); return }
        val result = when (val acquisition = capabilities.acquire(CapabilityKey.Synthesis)) {
            is CapabilityAcquisition.Available -> {
                val value = awaitSemanticBackend(
                    operation = {
                        acquisition.lease.use {
                            it.synthesize(
                                SpeechSynthesisRequest(text, language, SpeechVoice(voice), speed.toFloat()),
                            )
                        }
                    },
                    onLateResult = { late ->
                        if (late is CapabilityOperationResult.Success) late.value.dispose()
                    },
                )
                if (value == null) { resumeSemantic(pending, false, "E_TIMEOUT"); return }
                value
            }
            is CapabilityAcquisition.Denied -> CapabilityOperationResult.Denied(acquisition.reason)
            is CapabilityAcquisition.Recoverable, is CapabilityAcquisition.Unavailable -> CapabilityOperationResult.Unavailable(CapabilityUnavailableReason.NOT_CONFIGURED)
            CapabilityAcquisition.Closed -> CapabilityOperationResult.Closed
            CapabilityAcquisition.Cancelled -> CapabilityOperationResult.Cancelled
            is CapabilityAcquisition.Failed -> CapabilityOperationResult.Failed(CapabilityFailureReason.HOST_FAILURE)
        }
        when (result) {
            is CapabilityOperationResult.Success -> {
                if (!pending.semantic.claimTerminal()) { result.value.dispose(); return }
                val token = audioRegistry?.admitSynthesized(pending.audioOwner, result.value)
                if (token == null) {
                    result.value.dispose()
                    pending.semantic.completeClaimed(CallbackInvocationResult.Failure("E_BUSY"))
                    resumeInput(pending.ownerToken, pending.operation.operationId.value, false, "E_BUSY")
                } else {
                    pending.semantic.completeClaimed(CallbackInvocationResult.Success(LuaValue.StringValue(token.value)))
                    resumeInput(pending.ownerToken, pending.operation.operationId.value, true, token.value)
                }
            }
            is CapabilityOperationResult.Unavailable -> resumeSemantic(pending, false, "E_UNAVAILABLE")
            is CapabilityOperationResult.Denied -> resumeSemantic(pending, false, "E_CAPABILITY_UNDECLARED")
            CapabilityOperationResult.Closed -> resumeSemantic(pending, false, "E_CLOSED")
            CapabilityOperationResult.Cancelled -> resumeSemantic(pending, false, "E_CANCELLED")
            is CapabilityOperationResult.Failed -> resumeSemantic(pending, false, "E_HOST_FAILURE")
        }
    }

    private suspend fun executePlayback(pending: PendingInputExecution, claim: HostOperationClaim.Admitted) {
        if (!capabilities.isPublicCapabilityDeclared(PackageCapability.AUDIO_PLAYBACK)) {
            resumeInput(pending.ownerToken, pending.operation.operationId.value, false, "E_CAPABILITY_UNDECLARED")
            return
        }
        val token = claim.audioToken
        val delay = claim.delaySeconds
        if (token == null || !delay.isFinite() || delay < 0.0 || delay > 86_400.0) {
            resumeInput(pending.ownerToken, pending.operation.operationId.value, false, "E_INVALID_VALUE")
            return
        }
        val registry = audioRegistry ?: run {
            resumeInput(pending.ownerToken, pending.operation.operationId.value, false, "E_CLOSED")
            return
        }
        val captured = registry.resolve(
            LuaOpaqueAudioRegistry.Token(token),
            pending.audioOwner,
            LuaOpaqueAudioRegistry.Kind.Captured,
        )
        val kind = if (captured is LuaOpaqueAudioRegistry.Resolution.Captured) {
            LuaOpaqueAudioRegistry.Kind.Captured
        } else {
            LuaOpaqueAudioRegistry.Kind.Synthesized
        }
        val audio = if (captured is LuaOpaqueAudioRegistry.Resolution.Captured) {
            captured.recording
        } else {
            (registry.resolve(LuaOpaqueAudioRegistry.Token(token), pending.audioOwner, kind)
                as? LuaOpaqueAudioRegistry.Resolution.Synthesized)?.audio
        }
        if (audio == null) {
            resumeSemantic(pending, false, "E_STALE")
            return
        }
        val operation: CapabilityOperationResult<OpaqueAudioOperation> = when (
            val acquisition = capabilities.acquire(CapabilityKey.AudioOperation)
        ) {
            is CapabilityAcquisition.Available -> acquisition.lease.use { port ->
                if (audio is OpaqueAudioRecording) port.createPlaybackResult(audio)
                else port.createPlaybackResult(audio as OpaqueSynthesizedAudio)
            }
            is CapabilityAcquisition.Recoverable, is CapabilityAcquisition.Unavailable ->
                CapabilityOperationResult.Unavailable(CapabilityUnavailableReason.NOT_CONFIGURED)
            is CapabilityAcquisition.Denied -> CapabilityOperationResult.Denied(acquisition.reason)
            CapabilityAcquisition.Closed -> CapabilityOperationResult.Closed
            CapabilityAcquisition.Cancelled -> CapabilityOperationResult.Cancelled
            is CapabilityAcquisition.Failed -> CapabilityOperationResult.Failed(acquisition.reason)
        }
        if (operation !is CapabilityOperationResult.Success) {
            resumeSemantic(pending, false, operation.normalizedSemanticError())
            return
        }
        val scheduledResult: CapabilityOperationResult<io.talkcan.model.DelayedPlaybackOutcome>? =
            when (val acquisition = capabilities.acquire(CapabilityKey.DeferredAudioPlayback)) {
                is CapabilityAcquisition.Available -> awaitSemanticBackend(
                    operation = {
                        acquisition.lease.use { port ->
                            CapabilityOperationResult.Success(
                                port.scheduleAudio(
                                    AgentOperationContext(
                                        capabilities.identity,
                                        AgentRunId(UUID.randomUUID().toString()),
                                        AgentOperationId(UUID.randomUUID().toString()),
                                    ),
                                    operation.value,
                                    (delay * 1000.0).toLong(),
                                ),
                            )
                        }
                    },
                )
                is CapabilityAcquisition.Recoverable, is CapabilityAcquisition.Unavailable ->
                    CapabilityOperationResult.Unavailable(CapabilityUnavailableReason.NOT_CONFIGURED)
                is CapabilityAcquisition.Denied -> CapabilityOperationResult.Denied(acquisition.reason)
                CapabilityAcquisition.Closed -> CapabilityOperationResult.Closed
                CapabilityAcquisition.Cancelled -> CapabilityOperationResult.Cancelled
                is CapabilityAcquisition.Failed -> CapabilityOperationResult.Failed(acquisition.reason)
            }
        if (scheduledResult == null) {
            dispose(operation.value)
            resumeSemantic(pending, false, "E_TIMEOUT")
            return
        }
        when (scheduledResult) {
            is CapabilityOperationResult.Success -> {
                val scheduled = scheduledResult.value
                if (scheduled is io.talkcan.model.DelayedPlaybackOutcome.Pending ||
                    scheduled is io.talkcan.model.DelayedPlaybackOutcome.Playing ||
                    scheduled is io.talkcan.model.DelayedPlaybackOutcome.Heard
                ) {
                    if (!pending.semantic.claimTerminal()) {
                        dispose(operation.value)
                        return
                    }
                    val consumed = registry.consume(LuaOpaqueAudioRegistry.Token(token), pending.audioOwner, kind)
                    if (consumed == null) {
                        dispose(operation.value)
                        pending.semantic.completeClaimed(CallbackInvocationResult.Failure("E_STALE"))
                        resumeInput(pending.ownerToken, pending.operation.operationId.value, false, "E_STALE")
                        return
                    }
                    disposeRegistryArtifact(consumed)
                    pending.semantic.completeClaimed(CallbackInvocationResult.Success(LuaValue.StringValue("scheduled")))
                    resumeInput(pending.ownerToken, pending.operation.operationId.value, true, "scheduled")
                } else if (scheduled == io.talkcan.model.DelayedPlaybackOutcome.Busy) {
                    // Queue quota rejection does not consume or dispose the caller's token;
                    // the Lua caller may retry once capacity is released.
                    resumeSemantic(pending, false, "E_BUSY")
                } else {
                    dispose(operation.value)
                    resumeSemantic(pending, false, "E_HOST_FAILURE")
                }
            }
            else -> {
                dispose(operation.value)
                resumeSemantic(pending, false, scheduledResult.normalizedSemanticError())
            }
        }
    }
    private fun disposeRegistryArtifact(entry: LuaOpaqueAudioRegistry.Entry) {
        when (entry) {
            is LuaOpaqueAudioRegistry.Entry.Captured -> entry.recording.dispose()
            is LuaOpaqueAudioRegistry.Entry.Synthesized -> entry.audio.dispose()
        }
    }

    private fun cancelPendingInput() {
        val pending = synchronized(inputLock) { pendingInputExecution } ?: return
        val hostContext = generationContext as? ActorRuntimeHostContext ?: return
        if (closed.get() || !generationContext.isActive()) return
        // Cancellation is a live terminal delivery, not generation retirement:
        // re-enter exactly the owner operation with E_CANCELLED. The generation
        // parent scope keeps this cleanup bounded to the actor lifetime without
        // consuming managed-task capacity or imposing a generic task deadline.
        hostContext.actorParentScope.launch {
            resumeInput(
                ownerToken = pending.ownerToken,
                operationId = pending.operation.operationId.value,
                success = false,
                value = "E_CANCELLED",
            )
        }
    }
}

/** Host-enriched, bounded diagnostic record; plugin payload cannot supply metadata. */
internal data class LuaAdapterLogRecord(
    val instanceId: String,
    val generation: Long,
    val timestampMillis: Long,
    val level: String,
    val payload: LuaValue.Map,
)

private fun LuaValue.strictInputStatus(): ChannelExecutionStatus? {
    if (this !is LuaValue.Map) return null
    val ok = pairs["ok"]
    if (pairs.keys == setOf("ok") && ok is LuaValue.Bool && ok.value) {
        return ChannelExecutionStatus.SUCCESS
    }
    val error = pairs["error"] as? LuaValue.Map ?: return null
    return if (
        pairs.keys == setOf("error") &&
        error.pairs.keys == setOf("code", "detail") &&
        (error.pairs["code"] as? LuaValue.StringValue)?.value?.isNotEmpty() == true &&
        (error.pairs["detail"] as? LuaValue.StringValue)?.value?.isNotEmpty() == true
    ) {
        ChannelExecutionStatus.FAILED
    } else {
        null
    }
}

internal data class LuaInputPending(
    val ownerToken: String,
    val operationId: Long,
    val label: String,
)

internal sealed interface LuaInputResumeResult {
    data object Accepted : LuaInputResumeResult
    data object Stale : LuaInputResumeResult
    data object ForeignOwner : LuaInputResumeResult
    data object Closed : LuaInputResumeResult
}
private data class PendingInputExecution(
    val ownerToken: String,
    val operation: LuaOperationHandle,
    val label: String,
    val terminal: CompletableDeferred<CallbackInvocationResult>,
    val audioOwner: LuaOpaqueAudioRegistry.Owner,
    val capturedAudioToken: String,
    val semantic: SemanticAudioOperation,
)

/** Task 13.1: a committed submission's detached generation-owned continuation. */
private data class DetachedInputContinuation(
    val operation: LuaOperationHandle,
    val submitResult: String,
)

private class SemanticAudioOperation private constructor(
    val actorToken: ActorOperationToken,
    val generationIdentity: io.talkcan.channel.capability.CapabilityScopeIdentity,
) {
    private val terminalGate = AtomicBoolean(false)
    fun claimTerminal(): Boolean = terminalGate.compareAndSet(false, true)
    fun terminalize(result: CallbackInvocationResult): Boolean {
        if (!claimTerminal()) return false
        completeClaimed(result)
        return true
    }

    fun completeClaimed(result: CallbackInvocationResult) {
        val outcome = when (result) {
            is CallbackInvocationResult.Success -> ActorTerminal.Completed(actorToken.identity, result.value.toString())
            is CallbackInvocationResult.Failure -> ActorTerminal.Failed(actorToken.identity, result.diagnostic)
            is CallbackInvocationResult.YieldViolation -> ActorTerminal.Failed(actorToken.identity, result.diagnostic)
            is CallbackInvocationResult.InvalidOutcome -> ActorTerminal.Failed(actorToken.identity, result.diagnostic)
            is CallbackInvocationResult.Pending -> ActorTerminal.Failed(actorToken.identity, "semantic operation remained pending")
        }
        actorToken.complete(outcome)
    }

    companion object {
        fun create(
            operation: LuaOperationHandle,
            generationIdentity: io.talkcan.channel.capability.CapabilityScopeIdentity,
        ): SemanticAudioOperation {
            val identity = ActorOperationIdentity(
                scope = generationIdentity,
                taskId = ActorTaskIdentity(generationIdentity, ActorTaskId.next()),
                operationId = ActorOperationId.next(),
            )
            return SemanticAudioOperation(
                ActorOperationToken(identity, operation),
                generationIdentity,
            )
        }
    }
}

// ── Internal callback invocation result type ─────────────────────────────

/**
 * Typed outcome for a synchronous callback invocation.
 */
internal sealed class CallbackInvocationResult {
    /** Callback returned successfully, possibly with a normalized value. */
    data class Success(val value: LuaValue = LuaValue.Nil) : CallbackInvocationResult()

    /** Callback returned an application error, threw, or a bridge error occurred. */
    data class Failure(val diagnostic: String) : CallbackInvocationResult()

    /** Callback violated the non-yielding contract by yielding or spawning. */
    data class YieldViolation(val diagnostic: String) : CallbackInvocationResult()

    /** Callback returned a value whose shape does not match the contract for that callback. */
    data class InvalidOutcome(val diagnostic: String) : CallbackInvocationResult()
    /** Input callback suspended on a host semantic operation. */
    data class Pending(
        val operation: LuaOperationHandle,
        val label: String,
    ) : CallbackInvocationResult()
}

private fun CallbackInvocationResult.asGeneralCallbackOutcome(): CallbackInvocationResult = when (this) {
    is CallbackInvocationResult.Success -> when (val value = value) {
        is LuaValue.Map -> {
            if ("error" !in value.pairs) this
            else if (value.strictInputStatus() == ChannelExecutionStatus.FAILED) {
                CallbackInvocationResult.Failure("Lua callback returned an application error")
            } else {
                CallbackInvocationResult.InvalidOutcome("malformed application error table")
            }
        }
        else -> this
    }
    else -> this
}

private fun CallbackInvocationResult.diagnostic(): String = when (this) {
    is CallbackInvocationResult.Success -> "unexpected successful outcome"
    is CallbackInvocationResult.Failure -> diagnostic
    is CallbackInvocationResult.YieldViolation -> diagnostic
    is CallbackInvocationResult.InvalidOutcome -> diagnostic
    is CallbackInvocationResult.Pending -> "callback suspended"
}
