package io.talkcan.service

import io.talkcan.audio.ChannelAudioInputSession
import io.talkcan.audio.ChannelInputAcceptance
import io.talkcan.audio.ChannelInputResult
import io.talkcan.audio.ChannelInputTarget
import io.talkcan.audio.RecordedPcm
import io.talkcan.channel.capability.CapabilityScopeIdentity
import io.talkcan.channel.capability.ChannelCapabilityHost
import io.talkcan.channel.capability.RevocableChannelCapabilityScope
import io.talkcan.channel.capability.RuntimeGeneration
import io.talkcan.model.ChannelCatalogueSnapshot
import io.talkcan.model.ChannelDefinition
import io.talkcan.model.ChannelImplementationProvider
import io.talkcan.model.ChannelImplementationProviderRegistry
import io.talkcan.model.ChannelProviderError
import io.talkcan.model.ChannelProviderResolution
import io.talkcan.model.ChannelRuntimeConstructionRequest
import io.talkcan.model.ChannelRuntimeConstructionResult
import io.talkcan.model.GenerationExecutionContext
import io.talkcan.model.GenerationExecutionContextImpl
import io.talkcan.model.ProviderConfigurationResult
import io.talkcan.model.ChannelImplementationId
import io.talkcan.model.ProviderRevisionFingerprint
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Ordered host projection; its entries always mirror catalogue membership and order. */
data class RuntimeRegistrySnapshot(
    val activeChannelId: String,
    val entries: List<ChannelRuntimeSnapshot>,
)

sealed interface ChannelRuntimeRegistryShutdownResult {
    data object Closed : ChannelRuntimeRegistryShutdownResult
    data object TimedOutWaitingForCommittedTargets : ChannelRuntimeRegistryShutdownResult
}

/**
 * The only instance-ID keyed registry of channel runtimes. Its monitor protects structural
 * ownership only; all provider, runtime, capability, and close work is performed after release.
 */
class ChannelRuntimeRegistry(
    private val providers: ChannelImplementationProviderRegistry,
    private val capabilityHost: ChannelCapabilityHost,
    private val invocationBoundary: RuntimeInvocationBoundary,
    private val runtimeScope: CoroutineScope,
    private val closeScope: CoroutineScope,
    private val onPttSessionCancelRequested: suspend () -> Unit = {},
    private val shutdownAwaitMillis: Long = DEFAULT_SHUTDOWN_AWAIT_MILLIS,
) : ChannelRouter {
    private val lock = Any()
    private val entries = mutableMapOf<String, RuntimeEntry>()
    private val retiredEntries = linkedSetOf<RuntimeEntry>()
    private var orderedIds = emptyList<String>()
    private var activeChannelId = ""
    private var isShutdown = false

    private val _runtimeSnapshots = MutableStateFlow(RuntimeRegistrySnapshot("", emptyList()))
    val runtimeSnapshots: StateFlow<RuntimeRegistrySnapshot> = _runtimeSnapshots.asStateFlow()

    private sealed class RuntimeEntry(
        val definition: ChannelDefinition,
        val generation: RuntimeGeneration,
        var snapshotState: ChannelRuntimeSnapshot,
        val implementationId: ChannelImplementationId,
        val providerFingerprint: ProviderRevisionFingerprint?,
    ) {
        var retired = false
        var activeLeases = 0
        var closeStarted = false
        var snapshotCollection: Job? = null
        val closed = CompletableDeferred<Unit>()
        /**
         * True while installConstructedRuntime/installConstructionFailure has atomically claimed
         * the activation path (stale check passed, predecessor barrier awaited). retireLocked must
         * not stop admission or close a claimed entry; the activation path exclusively owns its
         * closure and completes [closed] after authorize/startup finish.
         */
        var activationClaimed = false
    }

    private sealed class LifecycleEntry(
        definition: ChannelDefinition,
        generation: RuntimeGeneration,
        snapshotState: ChannelRuntimeSnapshot,
        implementationId: ChannelImplementationId,
        providerFingerprint: ProviderRevisionFingerprint,
        val capabilities: RevocableChannelCapabilityScope,
        val gate: RuntimeGenerationInvocationGate,
        val generationContext: GenerationExecutionContextImpl,
    ) : RuntimeEntry(definition, generation, snapshotState, implementationId, providerFingerprint) {
        /** A detached runtime can arrive after a pending generation's gate has already closed. */
        val detachedRuntimeCloseOwner = AtomicBoolean(false)
        val detachedRuntimeCloseFinished = CompletableDeferred<Unit>()
    }

    private class PendingEntry(
        definition: ChannelDefinition,
        generation: RuntimeGeneration,
        snapshotState: ChannelRuntimeSnapshot,
        implementationId: ChannelImplementationId,
        providerFingerprint: ProviderRevisionFingerprint,
        capabilities: RevocableChannelCapabilityScope,
        gate: RuntimeGenerationInvocationGate,
        generationContext: GenerationExecutionContextImpl,
        /**
         * Predecessor terminal closure that must complete before this successor is promoted to
         * live. `null` when there is no staged predecessor (fresh install). The successor may be
         * constructed and validated effect-free while the predecessor drains, but is not
         * current/ready/authorized/startable until this completes.
         */
        val predecessorClosed: CompletableDeferred<Unit>? = null,
    ) : LifecycleEntry(
        definition,
        generation,
        snapshotState,
        implementationId,
        providerFingerprint,
        capabilities,
        gate,
        generationContext,
    )

    private class LiveEntry(
        definition: ChannelDefinition,
        generation: RuntimeGeneration,
        snapshotState: ChannelRuntimeSnapshot,
        implementationId: ChannelImplementationId,
        providerFingerprint: ProviderRevisionFingerprint,
        capabilities: RevocableChannelCapabilityScope,
        gate: RuntimeGenerationInvocationGate,
        generationContext: GenerationExecutionContextImpl,
        val runtime: ChannelRuntime,
    ) : LifecycleEntry(
        definition,
        generation,
        snapshotState,
        implementationId,
        providerFingerprint,
        capabilities,
        gate,
        generationContext,
    ) {
        var readinessRefreshJob: Job? = null
    }

    private class UnavailableEntry(
        definition: ChannelDefinition,
        generation: RuntimeGeneration,
        snapshotState: ChannelRuntimeSnapshot,
        implementationId: ChannelImplementationId,
        providerFingerprint: ProviderRevisionFingerprint?,
    ) : RuntimeEntry(definition, generation, snapshotState, implementationId, providerFingerprint)

    private data class ConstructionPlan(
        val entry: PendingEntry,
        val provider: ChannelImplementationProvider,
        val implementationId: ChannelImplementationId,
        val providerFingerprint: ProviderRevisionFingerprint,
        val summary: String,
    )

    private sealed interface ConstructionAttempt {
        data class Runtime(val runtime: ChannelRuntime) : ConstructionAttempt
        data class Failed(val error: ChannelProviderError) : ConstructionAttempt
    }

    /**
     * Reconciles a committed catalogue in two phases. Construction plans are recorded while the
     * lock is held and all provider work begins only after the structural transition is visible.
     */
    suspend fun reconcile(snapshot: ChannelCatalogueSnapshot) {
        val constructionPlans = mutableListOf<ConstructionPlan>()
        val stops = mutableListOf<LifecycleEntry>()
        val closures = mutableListOf<LifecycleEntry>()

        synchronized(lock) {
            if (isShutdown) return

            val nextDefinitions = snapshot.definitions.associateBy(ChannelDefinition::id)
            entries.keys.filter { it !in nextDefinitions }.toList().forEach { id ->
                val entry = entries.remove(id) ?: return@forEach
                retireLocked(entry, stops, closures)
            }

            snapshot.definitions.forEach { definition ->
                val existing = entries[definition.id]

                if (!definition.enabled) {
                    val disabledRetained = existing is UnavailableEntry &&
                        existing.definition == definition &&
                        existing.implementationId == definition.implementationId
                    if (!disabledRetained) {
                        if (existing != null) {
                            retireLocked(existing, stops, closures)
                        }
                        entries[definition.id] = unavailableEntry(
                            definition,
                            RuntimeGeneration.next(),
                            ChannelPreparationReason.Disabled,
                            null,
                        )
                    }
                    return@forEach
                }

                val resolution = providers.resolve(definition.implementationId)
                val canKeep = when (resolution) {
                    is ChannelProviderResolution.Available -> {
                        existing != null &&
                            existing.definition == definition &&
                            existing.implementationId == definition.implementationId &&
                            existing.providerFingerprint == resolution.provider.fingerprint
                    }
                    is ChannelProviderResolution.Missing -> {
                        existing != null &&
                            existing is UnavailableEntry &&
                            existing.definition == definition &&
                            existing.implementationId == definition.implementationId &&
                            providerReasonMatches(existing, resolution.error)
                    }
                    is ChannelProviderResolution.Unavailable -> {
                        existing != null &&
                            existing is UnavailableEntry &&
                            existing.definition == definition &&
                            existing.implementationId == definition.implementationId &&
                            providerReasonMatches(existing, resolution.error)
                    }
                }

                if (!canKeep) {
                    if (existing != null) {
                        retireLocked(existing, stops, closures)
                    }
                    installDefinitionLocked(
                        definition,
                        constructionPlans,
                        predecessorClosed = existing?.let { inheritedPredecessorBarrier(it) },
                    )
                }
            }

            orderedIds = snapshot.definitions.map(ChannelDefinition::id)
            activeChannelId = snapshot.activeChannelId.orEmpty()
            publishAggregateLocked()
        }

        stops.forEach { it.gate.stopAdmission() }
        for (entry in closures) closeLifecycleEntry(entry)
        for (plan in constructionPlans) construct(plan)
    }

    /**
     * 2.7: Force exactly one fresh runtime generation for a single instance after
     * a resource mount binding replacement. The catalogue definition is unchanged,
     * so the generic [reconcile] retention condition would keep the existing
     * generation; this method applies the same atomic retire / predecessor-barrier
     * / reinstall / construct path to one instance keyed by [channelInstanceId].
     * Predecessor authority is revoked before the successor is authorized.
     */
    suspend fun reconcileResourceBinding(snapshot: ChannelCatalogueSnapshot, channelInstanceId: String) {
        val definition = snapshot.definitions.firstOrNull { it.id == channelInstanceId } ?: return
        if (!definition.enabled) return
        val constructionPlans = mutableListOf<ConstructionPlan>()
        val stops = mutableListOf<LifecycleEntry>()
        val closures = mutableListOf<LifecycleEntry>()

        synchronized(lock) {
            if (isShutdown) return
            val existing = entries[definition.id] ?: return
            val predecessorBarrier = inheritedPredecessorBarrier(existing)
            retireLocked(existing, stops, closures)
            installDefinitionLocked(definition, constructionPlans, predecessorBarrier)
            publishAggregateLocked()
        }

        stops.forEach { it.gate.stopAdmission() }
        for (entry in closures) closeLifecycleEntry(entry)
        for (plan in constructionPlans) construct(plan)
    }

    /**
     * Returns the original unresolved predecessor close barrier for [entry], carrying forward
     * the root through PendingEntry replacement chains. For PendingEntry with a non-null
     * predecessor barrier, returns that inherited barrier; otherwise returns entry.closed.
     */
    private fun inheritedPredecessorBarrier(entry: RuntimeEntry): CompletableDeferred<Unit> =
        (entry as? PendingEntry)?.let { pending ->
            if (pending.activationClaimed) pending.closed else pending.predecessorClosed
        } ?: entry.closed

    override suspend fun prepareInput(channelId: String): ChannelInputAcceptance {
        val entry = synchronized(lock) {
            when {
                isShutdown -> return ChannelInputAcceptance.Unavailable(
                    ChannelPreparationReason.RegistryShutDown.message,
                )
                else -> entries[channelId] as? LiveEntry
                    ?: return ChannelInputAcceptance.Unavailable(
                        preparationLocked(channelId).reasonMessage(),
                    )
            }.also { live ->
                if (!live.definition.enabled) {
                    return ChannelInputAcceptance.Refused(ChannelPreparationReason.Disabled.message)
                }
                when (val preparation = live.snapshotState.preparation) {
                    is ChannelPreparationAvailability.Unavailable ->
                        return ChannelInputAcceptance.Unavailable(preparation.reason.message)
                    ChannelPreparationAvailability.Available,
                    is ChannelPreparationAvailability.Recoverable -> Unit
                }
            }
        }

        val acceptance = when (
            val outcome = entry.gate.invoke(RuntimeInvocationPhase.PREPARE_INPUT) {
                val result = entry.runtime.prepareInput()
                val stillCurrent = synchronized(lock) {
                    !isShutdown && !entry.retired && entries[channelId] === entry
                }
                if (result is ChannelInputAcceptance.Accepted && !stillCurrent) {
                    // This code still executes inside the generation's serialized invocation.
                    // Do not defer cleanup to a closed gate when provider code ignored cancellation.
                    result.target.onInputCancelled("Channel $channelId changed during preparation")
                    ChannelInputAcceptance.Unavailable(ChannelPreparationReason.RuntimeClosed.message)
                } else {
                    result
                }
            }
        ) {
            is RuntimeInvocationOutcome.Success -> outcome.value
            RuntimeInvocationOutcome.Busy -> ChannelInputAcceptance.Refused(ChannelPreparationReason.RuntimeBusy.message)
            RuntimeInvocationOutcome.Cancelled -> ChannelInputAcceptance.Unavailable(
                ChannelPreparationReason.RuntimeCancelled.message,
            )
            RuntimeInvocationOutcome.TimedOut -> ChannelInputAcceptance.Unavailable(
                ChannelPreparationReason.RuntimeTimedOut.message,
            )
            RuntimeInvocationOutcome.Closed -> ChannelInputAcceptance.Unavailable(
                ChannelPreparationReason.RuntimeClosed.message,
            )
            is RuntimeInvocationOutcome.Unavailable -> ChannelInputAcceptance.Unavailable(outcome.reason)
            is RuntimeInvocationOutcome.ProviderFailure,
            is RuntimeInvocationOutcome.RuntimeFailure -> ChannelInputAcceptance.Unavailable(
                ChannelPreparationReason.RuntimeFailed().message,
            )
        }

        if (acceptance !is ChannelInputAcceptance.Accepted) return acceptance
        val committed = entry.gate.commitIfLive {
            synchronized(lock) {
                if (!isShutdown && !entry.retired && entries[channelId] === entry) {
                    entry.gate.openCommittedTarget()?.also {
                        entry.activeLeases += 1
                    }
                } else {
                    null
                }
            }
        }
        val committedTarget = when (committed) {
            is RuntimeInvocationOutcome.Success -> committed.value
            else -> null
        }
        if (committedTarget == null) {
            discardUncommittedTarget(entry, acceptance.target, "Channel $channelId changed during preparation")
            return ChannelInputAcceptance.Unavailable(ChannelPreparationReason.RuntimeClosed.message)
        }
        return ChannelInputAcceptance.Accepted(LeaseWrappingTarget(entry, acceptance.target, committedTarget))
    }

    /** Executes a provider-neutral SOS action through the selected generation gate. */
    suspend fun dispatchSos(channelId: String): ChannelPreparationAvailability {
        val entry = synchronized(lock) {
            if (isShutdown) return ChannelPreparationAvailability.Unavailable(
                ChannelPreparationReason.RegistryShutDown,
            )
            entries[channelId] as? LiveEntry
                ?: return preparationLocked(channelId)
        }
        return when (val outcome = entry.gate.invoke(RuntimeInvocationPhase.HANDLE_SOS) {
            entry.runtime.handleSos()
        }) {
            is RuntimeInvocationOutcome.Success -> ChannelPreparationAvailability.Available
            else -> ChannelPreparationAvailability.Unavailable(outcome.reason())
        }
    }

    /** Returns the aggregate projection's current generic preparation state without sampling a child flow. */
    fun preparation(channelId: String): ChannelPreparationAvailability = synchronized(lock) {
        preparationLocked(channelId)
    }


    /** Compatibility read of the aggregate's entry state; it never samples a runtime StateFlow. */
    fun getRuntimeSnapshot(id: String): ChannelRuntimeSnapshot? = synchronized(lock) {
        entries[id]?.snapshotState
    }

    /**
     * Read-only retrieval of the current [CapabilityScopeIdentity] (channel id + live/pending
     * generation) for a live or pending entry. Returns null for unavailable, missing, or retired
     * entries; only [LifecycleEntry] subclasses carry an active capability scope.
     */
    internal fun capabilityScopeIdentity(id: String): CapabilityScopeIdentity? = synchronized(lock) {
        (entries[id] as? LifecycleEntry)?.let { CapabilityScopeIdentity(it.definition.id, it.generation) }
    }

    /** Compatibility read of the ordered aggregate; new consumers should collect [runtimeSnapshots]. */
    fun getAllRuntimeSnapshots(): List<ChannelRuntimeSnapshot> = runtimeSnapshots.value.entries

    /** Schedules readiness callbacks through generation gates after taking a structural snapshot. */
    fun refreshReadiness() {
        val liveEntries = synchronized(lock) { entries.values.filterIsInstance<LiveEntry>() }
        liveEntries.forEach { entry ->
            runtimeScope.launch {
                entry.gate.invoke(RuntimeInvocationPhase.READINESS_REFRESH) {
                    entry.runtime.refreshReadiness()
                }
            }
        }
    }

    /**
     * Stops admission, asks the audio terminal owner to end committed input, then waits within a
     * bounded policy for every committed lease to release and every generation to close.
     */
    suspend fun shutdownAndAwait(): ChannelRuntimeRegistryShutdownResult {
        val stops = mutableListOf<LifecycleEntry>()
        val closures = mutableListOf<LifecycleEntry>()
        val toAwait: List<CompletableDeferred<Unit>>
        synchronized(lock) {
            if (!isShutdown) {
                isShutdown = true
                entries.values.toList().forEach { entry ->
                    retireLocked(entry, stops, closures)
                }
                entries.clear()
                orderedIds = emptyList()
                activeChannelId = ""
                publishAggregateLocked()
            }
            toAwait = retiredEntries.map { it.closed }
        }

        stops.forEach { it.gate.stopAdmission() }
        try {
            withContext(NonCancellable) { onPttSessionCancelRequested() }
        } finally {
            // Close ownership remains host-scoped; the barriers below keep shutdown bounded even
            // if a runtime ignores cancellation after its terminal callback has started.
            closures.forEach { entry ->
                closeScope.launch(start = CoroutineStart.UNDISPATCHED) { closeLifecycleEntry(entry) }
            }
        }

        val completed = withContext(NonCancellable) {
            withTimeoutOrNull(shutdownAwaitMillis) {
                toAwait.forEach { it.await() }
                true
            } ?: false
        }
        return if (completed) {
            ChannelRuntimeRegistryShutdownResult.Closed
        } else {
            ChannelRuntimeRegistryShutdownResult.TimedOutWaitingForCommittedTargets
        }
    }

    private fun installDefinitionLocked(
        definition: ChannelDefinition,
        constructionPlans: MutableList<ConstructionPlan>,
        predecessorClosed: CompletableDeferred<Unit>?,
    ) {
        val generation = RuntimeGeneration.next()
        when (val resolution = providers.resolve(definition.implementationId)) {
            is ChannelProviderResolution.Missing -> {
                entries[definition.id] = unavailableEntry(
                    definition,
                    generation,
                    ChannelPreparationReason.Provider(resolution.error),
                    null,
                )
            }
            is ChannelProviderResolution.Unavailable -> {
                entries[definition.id] = unavailableEntry(
                    definition,
                    generation,
                    ChannelPreparationReason.Provider(resolution.error),
                    null,
                )
            }
            is ChannelProviderResolution.Available -> {
                val descriptor = resolution.provider.descriptor
                val scope = RevocableChannelCapabilityScope(
                    CapabilityScopeIdentity(definition.id, generation),
                    descriptor.requiredCapabilities,
                    capabilityHost,
                    initiallyAuthorized = predecessorClosed == null,
                )
                val gate = invocationBoundary.openGeneration(
                    definition.id,
                    generation,
                    runtimeScope,
                    closeScope,
                )
                val context = GenerationExecutionContextImpl(definition.id, gate, gate.childWork.scope)
                val pending = PendingEntry(
                    definition,
                    generation,
                    snapshotFor(
                        definition,
                        ChannelPreparationAvailability.Unavailable(ChannelPreparationReason.ProviderInitialising),
                        descriptor.presentation.summary,
                    ),
                    definition.implementationId,
                    resolution.provider.fingerprint,
                    scope,
                    gate,
                    context,
                    predecessorClosed,
                )
                entries[definition.id] = pending
                constructionPlans += ConstructionPlan(
                    pending,
                    resolution.provider,
                    definition.implementationId,
                    resolution.provider.fingerprint,
                    descriptor.presentation.summary,
                )
            }
        }
    }

    private suspend fun construct(plan: ConstructionPlan) {
        val attempt = when (
            val outcome = plan.entry.gate.invoke(
                RuntimeInvocationPhase.CONSTRUCT,
                RuntimeInvocationOrigin.PROVIDER,
            ) {
                construct(plan.provider, plan.entry)
            }
        ) {
            is RuntimeInvocationOutcome.Success -> outcome.value
            else -> ConstructionAttempt.Failed(outcome.providerError(plan.entry.definition))
        }

        when (attempt) {
            is ConstructionAttempt.Runtime -> installConstructedRuntime(plan, attempt.runtime)
            is ConstructionAttempt.Failed -> installConstructionFailure(plan, attempt.error)
        }
    }

    private suspend fun construct(
        provider: ChannelImplementationProvider,
        entry: PendingEntry,
    ): ConstructionAttempt {
        val configuration = when (
            val result = provider.descriptor.configuration.migrateAndValidate(
                entry.definition.configSchemaVersion,
                entry.definition.configPayload,
            )
        ) {
            is ProviderConfigurationResult.Success -> result.configuration
            is ProviderConfigurationResult.Failure -> return ConstructionAttempt.Failed(result.error)
        }
        val effectiveDefinition = entry.definition.copy(
            configSchemaVersion = configuration.schemaVersion,
            configPayload = configuration.payload,
        )
        return when (
            val result = provider.constructRuntime(
                ChannelRuntimeConstructionRequest(
                    effectiveDefinition,
                    configuration,
                    entry.capabilities,
                    entry.generationContext,
                    provider.descriptor.resourceDeclarations,
                ),
            )
        ) {
            is ChannelRuntimeConstructionResult.Success -> ConstructionAttempt.Runtime(result.runtime)
            is ChannelRuntimeConstructionResult.Failure -> ConstructionAttempt.Failed(result.error)
        }
    }

    private suspend fun installConstructedRuntime(plan: ConstructionPlan, runtime: ChannelRuntime) {
        // Staged successor semantics: the successor may be constructed and validated
        // effect-free while the predecessor drains, but is not promoted to live until the
        // predecessor has stopped admission, committed its terminal callback, drained
        // descendants, revoked capabilities, and closed its gate exactly once.
        plan.entry.predecessorClosed?.let { withContext(NonCancellable) { it.await() } }

        // Atomically claim the activation path and authorize its scope. If the plan is no
        // longer current, already retired, or the registry has shut down, close the detached
        // runtime without authorizing or starting it. authorize() is a non-suspending CAS; by
        // running it while the structural lock holds the claim, retirement cannot interleave
        // between the stale decision and authorization. Once claimed, this path exclusively
        // owns stopAdmission, revoke, gate.close, and entry.closed, so a rapid successor that
        // inherited this barrier waits for it.
        val stale = synchronized(lock) {
            if (isShutdown || entries[plan.entry.definition.id] !== plan.entry || plan.entry.retired) {
                true
            } else {
                plan.entry.activationClaimed = true
                plan.entry.capabilities.authorize()
                false
            }
        }
        if (stale) {
            closeDetachedGeneration(plan.entry, runtime)
            return
        }

        // Invoke bounded, protected runtime startup through the generation gate.
        // For existing Kotlin providers, activate() defaults to Ready.
        val activationOutcome = plan.entry.gate.invoke(RuntimeInvocationPhase.STARTUP) {
            runtime.activate()
        }
        val activation = when (activationOutcome) {
            is RuntimeInvocationOutcome.Success -> activationOutcome.value
            else -> null
        }
        if (activation !is ChannelActivationResult.Ready) {
            // Startup failed or gate rejected the callback. Close H, install unavailable.
            closeDetachedGeneration(plan.entry, runtime)
            val startupError = when (activation) {
                is ChannelActivationResult.Failed -> activation.message
                is ChannelActivationResult.Ready -> error("unreachable")
                null -> activationOutcome.reason().message
            }
            installStartupFailure(plan, startupError)
            return
        }

        val live = synchronized(lock) {
            if (!isShutdown && entries[plan.entry.definition.id] === plan.entry && !plan.entry.retired) {
                LiveEntry(
                    plan.entry.definition,
                    plan.entry.generation,
                    runtime.snapshot.value.copy(
                        id = plan.entry.definition.id,
                        name = plan.entry.definition.name,
                        implementationId = plan.entry.definition.implementationId,
                        enabled = plan.entry.definition.enabled,
                    ),
                    plan.entry.implementationId,
                    plan.entry.providerFingerprint!!,
                    plan.entry.capabilities,
                    plan.entry.gate,
                    plan.entry.generationContext,
                    runtime,
                ).also {
                    entries[plan.entry.definition.id] = it
                    publishAggregateLocked()
                }
            } else {
                null
            }
        }
        if (live == null) {
            closeDetachedGeneration(plan.entry, runtime)
            return
        }
        collectSnapshots(live)
        val firstReadiness = live.gate.invoke(RuntimeInvocationPhase.READINESS_REFRESH) {
            live.runtime.refreshReadiness()
        }
        val stagedJobs = synchronized(lock) {
            val stillCurrent = !isShutdown && !live.retired && entries[live.definition.id] === live && live.gate.isLive
            if (firstReadiness is RuntimeInvocationOutcome.Success && stillCurrent) {
                live.generationContext.authorizeStagedTasksAfterReady()
            } else {
                live.generationContext.discardStagedTasks()
                emptyList()
            }
        }
        stagedJobs.forEach(Job::start)
        val stillCurrent = synchronized(lock) {
            !isShutdown && !live.retired && entries[live.definition.id] === live && live.gate.isLive
        }
        if (firstReadiness is RuntimeInvocationOutcome.Success && stillCurrent) {
            live.runtime.readinessRefreshIntervalMillis
                ?.takeIf { it > 0L }
                ?.let { intervalMillis ->
                    live.readinessRefreshJob = runtimeScope.launch {
                        while (true) {
                            delay(intervalMillis)
                            val current = synchronized(lock) {
                                !isShutdown && entries[live.definition.id] === live && !live.retired && live.gate.isLive
                            }
                            if (!current) return@launch
                            live.gate.invoke(RuntimeInvocationPhase.READINESS_REFRESH) {
                                live.runtime.refreshReadiness()
                            }
                        }
                    }
                }
        }
    }

    private suspend fun installConstructionFailure(plan: ConstructionPlan, error: ChannelProviderError) {
        plan.entry.predecessorClosed?.let { withContext(NonCancellable) { it.await() } }

        // Atomically claim and authorize. authorize() is non-suspending, so it is safe under
        // the structural lock and cannot run after retirement won the stale decision.
        val stale = synchronized(lock) {
            if (isShutdown || entries[plan.entry.definition.id] !== plan.entry || plan.entry.retired) {
                true
            } else {
                plan.entry.activationClaimed = true
                plan.entry.capabilities.authorize()
                false
            }
        }
        if (stale) {
            closeDetachedGeneration(plan.entry, runtime = null)
            return
        }

        val reason = when (error) {
            is ChannelProviderError.InvalidConfiguration ->
                ChannelPreparationReason.ConfigurationIncompatible(error)
            else ->
                ChannelPreparationReason.Provider(error)
        }

        val closePending = synchronized(lock) {
            if (!isShutdown && entries[plan.entry.definition.id] === plan.entry && !plan.entry.retired) {
                entries[plan.entry.definition.id] = unavailableEntry(
                    plan.entry.definition,
                    plan.entry.generation,
                    reason,
                    plan.summary,
                    plan.provider.fingerprint,
                )
                publishAggregateLocked()
            }
            plan.entry
        }
        closeDetachedGeneration(closePending, runtime = null)
    }

    private suspend fun installStartupFailure(plan: ConstructionPlan, message: String) {
        val closePending = synchronized(lock) {
            if (!isShutdown && entries[plan.entry.definition.id] === plan.entry && !plan.entry.retired) {
                entries[plan.entry.definition.id] = unavailableEntry(
                    plan.entry.definition,
                    plan.entry.generation,
                    ChannelPreparationReason.RuntimeFailed(message),
                    plan.summary,
                    plan.provider.fingerprint,
                )
                publishAggregateLocked()
            }
            plan.entry
        }
        closeDetachedGeneration(closePending, runtime = null)
    }

    private fun collectSnapshots(entry: LiveEntry) {
        entry.snapshotCollection = runtimeScope.launch {
            entry.runtime.snapshot.collect { runtimeSnapshot ->
                entry.gate.commitIfLive {
                    synchronized(lock) {
                        if (!isShutdown && entries[entry.definition.id] === entry && !entry.retired) {
                            entry.snapshotState = runtimeSnapshot.copy(
                                id = entry.definition.id,
                                name = entry.definition.name,
                                implementationId = entry.definition.implementationId,
                                enabled = entry.definition.enabled,
                            )
                            publishAggregateLocked()
                        }
                    }
                }
            }
        }
    }

    private fun retireLocked(
        entry: RuntimeEntry,
        stops: MutableList<LifecycleEntry>,
        closures: MutableList<LifecycleEntry>,
    ) {
        if (entry.retired) return
        entry.retired = true
        retiredEntries += entry
        (entry as? LifecycleEntry)?.generationContext?.stopAdmission()
        (entry as? LifecycleEntry)?.gate?.stopAdmission()
        if (entry.activationClaimed) {
            // Activation path exclusively owns closure; do not stop admission or close here.
            // It completes the claimed entry after startup failure or a failed promotion check,
            // allowing a rapid successor to await the claimed entry's closed barrier.
            return
        }
        (entry as? LifecycleEntry)?.let(stops::add)
        if (entry.activeLeases == 0) markForCloseLocked(entry)?.let(closures::add)
    }

    private fun markForCloseLocked(entry: RuntimeEntry): LifecycleEntry? {
        if (entry.closeStarted) return null
        entry.closeStarted = true
        return entry as? LifecycleEntry ?: run {
            entry.closed.complete(Unit)
            retiredEntries -= entry
            null
        }
    }

    private suspend fun closeLifecycleEntry(entry: LifecycleEntry) = withContext(NonCancellable) {
        entry.snapshotCollection?.cancel()
        (entry as? LiveEntry)?.readinessRefreshJob?.cancel()
        entry.gate.stopAdmission()
        try {
            entry.generationContext.closeAndDrain()
            entry.capabilities.revoke()
            val runtime = (entry as? LiveEntry)?.runtime
            if (runtime == null) {
                entry.gate.close {}
            } else {
                val runtimeCloseStarted = AtomicBoolean(false)
                entry.gate.close {
                    runtimeCloseStarted.set(true)
                    runtime.close()
                }
                if (!runtimeCloseStarted.get()) {
                    // The terminal worker can time out before it starts the callback. Its close
                    // job is joined before gate.close returns, so this elected fallback cannot
                    // overlap a late worker dispatch.
                    runtime.close()
                }
            }
        } finally {
            synchronized(lock) {
                if (!entry.closed.isCompleted) entry.closed.complete(Unit)
                retiredEntries -= entry
            }
        }
    }

    private suspend fun closeDetachedGeneration(entry: LifecycleEntry, runtime: ChannelRuntime?) =
        withContext(NonCancellable) {
            entry.snapshotCollection?.cancel()
            (entry as? LiveEntry)?.readinessRefreshJob?.cancel()
            entry.gate.stopAdmission()
            try {
                entry.generationContext.closeAndDrain()
                entry.capabilities.revoke()
                if (runtime == null) {
                    entry.gate.close {}
                } else if (entry.detachedRuntimeCloseOwner.compareAndSet(false, true)) {
                    try {
                        // A retired PendingEntry may already have closed its gate before H is
                        // constructed. Keep callback start separate from close completion: a
                        // started callback owns H even if the gate reports timeout/cancellation.
                        val gateCallbackStarted = AtomicBoolean(false)
                        val runtimeCloseCompleted = AtomicBoolean(false)
                        val gateClose = entry.gate.close {
                            gateCallbackStarted.set(true)
                            runtime.close()
                            runtimeCloseCompleted.set(true)
                        }
                        if (!gateCallbackStarted.get()) {
                            // gate.close guarantees that a timed out/cancelled callback has
                            // stopped before returning, so this elected fallback cannot overlap it.
                            when (gateClose) {
                                is RuntimeInvocationOutcome.Success,
                                RuntimeInvocationOutcome.TimedOut,
                                RuntimeInvocationOutcome.Cancelled,
                                RuntimeInvocationOutcome.Closed,
                                RuntimeInvocationOutcome.Busy,
                                is RuntimeInvocationOutcome.Unavailable,
                                is RuntimeInvocationOutcome.ProviderFailure,
                                is RuntimeInvocationOutcome.RuntimeFailure,
                                -> {
                                    runtime.close()
                                    runtimeCloseCompleted.set(true)
                                }
                            }
                        }
                        check(gateCallbackStarted.get() || runtimeCloseCompleted.get()) {
                            "Detached runtime close callback neither started nor completed"
                        }
                    } finally {
                        entry.detachedRuntimeCloseFinished.complete(Unit)
                    }
                } else {
                    entry.detachedRuntimeCloseFinished.await()
                }
            } finally {
                synchronized(lock) {
                    if (!entry.closed.isCompleted) entry.closed.complete(Unit)
                    retiredEntries -= entry
                }
            }
        }

    private suspend fun releaseLease(entry: LiveEntry) {
        val closure = synchronized(lock) {
            check(entry.activeLeases > 0) { "Committed target lease underflow" }
            entry.activeLeases -= 1
            if (entry.activeLeases == 0 && entry.retired) markForCloseLocked(entry) else null
        }
        if (closure != null) closeLifecycleEntry(closure)
    }

    private fun unavailableEntry(
        definition: ChannelDefinition,
        generation: RuntimeGeneration,
        reason: ChannelPreparationReason,
        summary: String?,
        providerFingerprint: ProviderRevisionFingerprint? = null,
    ): UnavailableEntry = UnavailableEntry(
        definition,
        generation,
        snapshotFor(definition, ChannelPreparationAvailability.Unavailable(reason), summary),
        definition.implementationId,
        providerFingerprint,
    )

    /**
     * Returns true when the existing unavailable entry's typed provider error matches the
     * newly resolved [ChannelProviderError], so reconciliation can retain the entry without
     * churn. A mismatch (reason transition) returns false, causing the caller to retire and
     * reinstall with the updated reason.
     */
    private fun providerReasonMatches(
        entry: RuntimeEntry,
        error: ChannelProviderError,
    ): Boolean {
        val preparation = entry.snapshotState.preparation
        return preparation is ChannelPreparationAvailability.Unavailable &&
            preparation.reason is ChannelPreparationReason.Provider &&
            preparation.reason.error == error
    }

    private fun snapshotFor(
        definition: ChannelDefinition,
        preparation: ChannelPreparationAvailability,
        summary: String?,
    ): ChannelRuntimeSnapshot = ChannelRuntimeSnapshot(
        id = definition.id,
        name = definition.name,
        implementationId = definition.implementationId,
        enabled = definition.enabled,
        preparation = if (definition.enabled) preparation else {
            ChannelPreparationAvailability.Unavailable(ChannelPreparationReason.Disabled)
        },
        executionStatus = ChannelExecutionStatus.IDLE,
        summary = summary,
    )

    private fun preparationLocked(channelId: String): ChannelPreparationAvailability = when {
        isShutdown -> ChannelPreparationAvailability.Unavailable(ChannelPreparationReason.RegistryShutDown)
        else -> entries[channelId]?.snapshotState?.preparation
            ?: ChannelPreparationAvailability.Unavailable(ChannelPreparationReason.UnknownInstance)
    }

    private fun publishAggregateLocked() {
        _runtimeSnapshots.value = RuntimeRegistrySnapshot(
            activeChannelId = activeChannelId,
            entries = orderedIds.mapNotNull { entries[it]?.snapshotState },
        )
    }

    private fun ChannelPreparationAvailability.reasonMessage(): String = when (this) {
        ChannelPreparationAvailability.Available -> ChannelPreparationReason.UnknownInstance.message
        is ChannelPreparationAvailability.Recoverable -> reason.message
        is ChannelPreparationAvailability.Unavailable -> reason.message
    }

    private fun RuntimeInvocationOutcome<*>.reason(): ChannelPreparationReason = when (this) {
        RuntimeInvocationOutcome.Busy -> ChannelPreparationReason.RuntimeBusy
        RuntimeInvocationOutcome.Cancelled -> ChannelPreparationReason.RuntimeCancelled
        RuntimeInvocationOutcome.TimedOut -> ChannelPreparationReason.RuntimeTimedOut
        RuntimeInvocationOutcome.Closed -> ChannelPreparationReason.RuntimeClosed
        is RuntimeInvocationOutcome.Unavailable -> ChannelPreparationReason.RuntimeFailed(reason)
        is RuntimeInvocationOutcome.ProviderFailure,
        is RuntimeInvocationOutcome.RuntimeFailure -> ChannelPreparationReason.RuntimeFailed()
        is RuntimeInvocationOutcome.Success -> ChannelPreparationReason.RuntimeClosed
    }

    private fun RuntimeInvocationOutcome<*>.providerError(definition: ChannelDefinition): ChannelProviderError = when (this) {
        is RuntimeInvocationOutcome.ProviderFailure -> ChannelProviderError.RuntimeConstructionFailed(
            definition.implementationId,
            failure.message,
        )
        RuntimeInvocationOutcome.Busy -> ChannelProviderError.RuntimeConstructionFailed(
            definition.implementationId,
            ChannelPreparationReason.RuntimeBusy.message,
        )
        RuntimeInvocationOutcome.Cancelled -> ChannelProviderError.RuntimeConstructionFailed(
            definition.implementationId,
            ChannelPreparationReason.RuntimeCancelled.message,
        )
        RuntimeInvocationOutcome.TimedOut -> ChannelProviderError.RuntimeConstructionFailed(
            definition.implementationId,
            ChannelPreparationReason.RuntimeTimedOut.message,
        )
        RuntimeInvocationOutcome.Closed -> ChannelProviderError.RuntimeConstructionFailed(
            definition.implementationId,
            ChannelPreparationReason.RuntimeClosed.message,
        )
        is RuntimeInvocationOutcome.Unavailable -> ChannelProviderError.RuntimeConstructionFailed(
            definition.implementationId,
            reason,
        )
        is RuntimeInvocationOutcome.RuntimeFailure -> ChannelProviderError.RuntimeConstructionFailed(
            definition.implementationId,
            failure.message,
        )
        is RuntimeInvocationOutcome.Success -> error("Successful construction has no provider error")
    }

    private suspend fun discardUncommittedTarget(
        entry: LiveEntry,
        target: ChannelInputTarget,
        reason: String,
    ) {
        withContext(NonCancellable) {
            val callbackStarted = AtomicBoolean(false)
            val result = entry.gate.invoke(RuntimeInvocationPhase.INPUT_CANCELLED) {
                callbackStarted.set(true)
                target.onInputCancelled(reason)
            }
            if (!callbackStarted.get() && (
                result == RuntimeInvocationOutcome.Closed ||
                    result == RuntimeInvocationOutcome.Cancelled ||
                    result == RuntimeInvocationOutcome.TimedOut
                )
            ) {
                target.onInputCancelled(reason)
            }
        }
    }

    private inner class LeaseWrappingTarget(
        private val entry: LiveEntry,
        private val original: ChannelInputTarget,
        private val committedTarget: RuntimeCommittedTarget,
    ) : ChannelInputTarget, CommittedTargetLeaseOwner {
        private val terminalLock = Any()
        private var terminalAdmissionClosed = false
        private val terminalCallbacks = mutableListOf<Job>()

        override fun onInputStarted(session: ChannelAudioInputSession) {
            runtimeScope.launch(start = CoroutineStart.UNDISPATCHED) {
                entry.gate.invoke(RuntimeInvocationPhase.INPUT_STARTED) {
                    original.onInputStarted(session)
                }
            }
        }

        override suspend fun onInputReleased(recording: RecordedPcm): ChannelInputResult = when (
            val outcome = committedTarget.invoke(RuntimeInvocationPhase.INPUT_RELEASED) {
                original.onInputReleased(recording)
            }
        ) {
            is RuntimeInvocationOutcome.Success -> outcome.value
            else -> ChannelInputResult.None
        }

        override fun onInputPlaybackCompleted() {
            enqueueTerminal(RuntimeInvocationPhase.INPUT_PLAYBACK_COMPLETED) {
                original.onInputPlaybackCompleted()
            }
        }

        override fun onInputCancelled(reason: String) {
            enqueueTerminal(RuntimeInvocationPhase.INPUT_CANCELLED) {
                original.onInputCancelled(reason)
            }
        }

        override fun onInputFailed(reason: String) {
            enqueueTerminal(RuntimeInvocationPhase.INPUT_FAILED) {
                original.onInputFailed(reason)
            }
        }

        override suspend fun releaseCommittedTargetLease() {
            val callbacks = synchronized(terminalLock) {
                if (terminalAdmissionClosed) return
                terminalAdmissionClosed = true
                terminalCallbacks.toList()
            }
            withContext(NonCancellable) { callbacks.forEach { it.join() } }
            committedTarget.release()
            releaseLease(entry)
        }

        private fun enqueueTerminal(
            phase: RuntimeInvocationPhase,
            callback: suspend () -> Unit,
        ) {
            val job = runtimeScope.launch(start = CoroutineStart.LAZY) {
                committedTarget.invoke(phase, callback = callback)
            }
            val admitted = synchronized(terminalLock) {
                if (terminalAdmissionClosed) {
                    false
                } else {
                    terminalCallbacks += job
                    true
                }
            }
            if (admitted) job.start()
            else job.cancel()
        }
    }

    private companion object {
        const val DEFAULT_SHUTDOWN_AWAIT_MILLIS = 10_000L
    }
}
