package io.talkcan.service

import io.talkcan.dependency.GitHubRepositoryIdentity
import io.talkcan.dependency.InstalledPackageRepository
import io.talkcan.dependency.InstalledPackageStore
import io.talkcan.dependency.MaterializationResult
import io.talkcan.dependency.MutationResult
import io.talkcan.dependency.PackageFailure
import io.talkcan.dependency.PackageOutcome
import io.talkcan.dependency.PackageSourceRecord
import io.talkcan.dependency.PackageValidationBounds
import io.talkcan.dependency.StoredPackageRevision
import io.talkcan.dependency.StoredProviderRecord
import io.talkcan.dependency.toPackageUnavailable
import io.talkcan.dependency.toPackageUnavailableProjection
import io.talkcan.lua.LuaKernelBridge
import io.talkcan.lua.PluginLogSink
import io.talkcan.lua.resolver.registerResolverSource
import io.talkcan.channel.capability.CapabilityPreparerRegistry
import io.talkcan.channel.capability.CapabilityScopeIdentity
import io.talkcan.channel.capability.KeyboardOutputAdapter
import io.talkcan.channel.capability.OutputExecutionOwner
import io.talkcan.model.DynamicConfigurationChoiceResolver
import io.talkcan.model.ChannelImplementationId
import io.talkcan.model.ChannelImplementationProviderRegistry
import io.talkcan.model.ChannelProviderError
import io.talkcan.model.InstalledProvidersPublicationResult
import io.talkcan.model.InstalledProvidersRejectionReason
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Host-domain state for the installed-package subsystem.
 *
 * Every non-terminal variant carries a monotonic [generation] so observers can distinguish
 * a stale publication from a current one. Generation is assigned under the coordinator
 * operation mutex immediately before the repository call begins.
 *
 * - [Loading]: packages are being hashed, validated, and materialized off the main thread.
 * - [Ready]: a complete installed-provider snapshot has been published.
 * - [Failed]: the store could not be loaded, recovered, or reconciled.
 * - [Closed]: shutdown is complete; [error] is non-null if close failed.
 */
sealed interface InstalledPackagesState {
    val generation: Long

    data class Loading(override val generation: Long) : InstalledPackagesState
    data class Ready(
        override val generation: Long,
        val snapshotRevision: Long,
    ) : InstalledPackagesState
    data class Failed(
        override val generation: Long,
        val error: PackageFailure,
    ) : InstalledPackagesState
    data class Closed(val error: PackageFailure? = null) : InstalledPackagesState {
        override val generation: Long = -1L
    }
}

/**
 * Service-owned coordinator integrating the installed-package repository into foreground
 * service composition.
 *
 * Owns one [InstalledPackageStore] and one [InstalledPackageRepository], exposing a
 * [StateFlow] of host-domain loading/ready/failed/closed states. All package I/O runs on
 * a bounded [Dispatchers.IO] boundary; built-in provider registration and foreground-service
 * startup are never blocked.
 *
 * **Generation contract:** Every operation (load, reload, mutation) acquires [operationMutex],
 * increments [generation], and records [currentGeneration] before calling the repository.
 * The injected publisher lambda reads [currentGeneration] to decide whether state publication
 * is still valid for the operation that initiated it. This prevents an older load from
 * publishing after a newer generation begins.
 *
 * **Publication ordering:** The publisher atomically replaces the installed-provider
 * snapshot (valid bindings + typed unavailable failures) in the registry, then reconciles
 * the current catalogue **outside** registry synchronization. A reconcile failure after a
 * successful commit sets [Failed] with [PackageFailure.LoadingDetail.RECONCILIATION_FAILED];
 * the committed index remains authoritative for the next recovery.
 *
 * Lua remains dormant: no Lua state or actor is created during loading, materialization, or
 * provider registration.
 */
internal class InstalledPackagesCoordinator(
    storeRoot: File,
    private val providerRegistry: ChannelImplementationProviderRegistry,
    bridge: LuaKernelBridge,
    logSink: PluginLogSink,
    runtimeResourcesFactory: io.talkcan.lua.LuaRuntimeResourcesFactory? = null,
    private val onCatalogueReconcile: suspend () -> Unit,
    private val serviceScope: CoroutineScope,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    bounds: PackageValidationBounds = PackageValidationBounds.DEFAULT,
    preparerRegistry: CapabilityPreparerRegistry = CapabilityPreparerRegistry.empty(),
    dynamicChoiceResolver: DynamicConfigurationChoiceResolver? = null,
    keyboardOutputAdapterFactory: ((CapabilityScopeIdentity, OutputExecutionOwner) -> KeyboardOutputAdapter)? = null,
    // Task 13.3/13.4: adapter composition pass-through
    secretStore: io.talkcan.secret.ProtectedSecretStore? = null,
    httpTransport: io.talkcan.http.GenericHttpTransport? = null,
    workStore: io.talkcan.work.DurableWorkStore? = null,
    workCoordinator: io.talkcan.work.DurableWorkCoordinator? = null,
    profileRecordProvider: (suspend (String) -> io.talkcan.profile.ProfileRecord?)? = null,
    // Task 10.7: resolver factory publication
    private val dynamicChoiceSourceRegistry: io.talkcan.model.DynamicConfigurationChoiceSourceRegistry? = null,
    private val resolverOrchestrator: io.talkcan.lua.resolver.PackageResolverOrchestrator? = null,
) {
    private val store = InstalledPackageStore(
        storeRoot,
        logSink,
        runtimeResourcesFactory,
        preparerRegistry,
        dynamicChoiceResolver,
        keyboardOutputAdapterFactory,
        secretStore,
        httpTransport,
        workStore,
        workCoordinator,
        profileRecordProvider,
    )
    private val closed = AtomicBoolean(false)

    private val operationMutex = Mutex()
    /**
     * Publication mutex shared with shutdown. Both [publishMaterialization] and [shutdown]
     * acquire this mutex, guaranteeing that the publication+reconcile block and the
     * shutdown gate are mutually exclusive. Shutdown wins the boundary before setting
     * [closed] and calling [InstalledPackageRepository.requestClose]; no swap or reconcile
     * can start once shutdown has acquired this mutex.
     */
    private val publicationMutex = Mutex()
    private val generation = AtomicLong(0L)

    /** Generation of the operation currently holding [operationMutex]. -1 = idle. */
    @Volatile
    private var currentGeneration: Long = -1L

    private val _state = MutableStateFlow<InstalledPackagesState>(
        InstalledPackagesState.Loading(0L)
    )
    val state: StateFlow<InstalledPackagesState> = _state.asStateFlow()

    private val repository: InstalledPackageRepository = InstalledPackageRepository(
        store = store,
        bridge = bridge,
        publisher = ::publishMaterialization,
        dispatcher = ioDispatcher,
        bounds = bounds,
    )

    // ──────────────────────────────────────────────────────────────────────────
    // Task 10.7: Resolver factory cache and publication
    //
    // PackageResolverFactory instances are cached per canonical repository
    // identity; each scoped `resolver:<id>` source is registered once with the
    // DynamicConfigurationChoiceSourceRegistry under that identity. Subsequent
    // materializations republish via publish(newPublication). Removed repositories
    // are invalidated and unregistered without disturbing sibling repositories.

    private val resolverFactories = java.util.concurrent.ConcurrentHashMap<GitHubRepositoryIdentity, io.talkcan.lua.resolver.PackageResolverFactory>()
    private val registeredResolverSources = java.util.concurrent.ConcurrentHashMap<GitHubRepositoryIdentity, MutableSet<String>>()

    /**
     * Publish or invalidate resolver factories atomically with provider
     * publication (task 10.7). Called from [publishMaterialization] after the
     * registry commit succeeds. For each binding with a resolver publication,
     * the factory is created on first sight, its scoped `resolver:<id>` sources
     * are registered once under the declaring repository identity, and it is
     * published with the new authority. Factories for repositories absent from the
     * current publication are invalidated and unregistered without disturbing siblings.
     */
    private fun publishResolverFactories(result: MaterializationResult) {
        val registry = dynamicChoiceSourceRegistry ?: return
        val orchestrator = resolverOrchestrator ?: return
        val activeRepos = HashSet<GitHubRepositoryIdentity>()
        for ((implementationId, publication) in result.resolverPublications) {
            // Canonical repository identity comes from the installed binding; the
            // registry scope must never be a parsed numeric id.
            val repositoryId = result.bindings[implementationId]?.repositoryId ?: continue
            activeRepos.add(repositoryId)
            val factory = resolverFactories.getOrPut(repositoryId) {
                io.talkcan.lua.resolver.PackageResolverFactory(orchestrator)
            }
            // Register each scoped resolver source once; the registry rejects an
            // exact (source, repository) duplicate but permits the same local
            // resolver ID under a different repository.
            val registered = registeredResolverSources.getOrPut(repositoryId) {
                java.util.concurrent.ConcurrentHashMap.newKeySet()
            }
            for (resolverId in publication.resolvers.keys) {
                if (registered.add(resolverId)) {
                    factory.registerResolverSource(
                        registry,
                        resolverId,
                        io.talkcan.lua.resolver.PackageResolverFactory.DEFAULT_DEADLINE,
                        repositoryId,
                    )
                }
            }
            factory.publish(publication)
        }
        // Invalidate and deregister factories for repositories removed from this
        // publication without disturbing sibling repositories.
        for ((repositoryId, factory) in resolverFactories) {
            if (repositoryId !in activeRepos) {
                factory.invalidate()
                registeredResolverSources.remove(repositoryId)?.forEach { resolverId ->
                    registry.unregister(
                        io.talkcan.model.DynamicConfigurationChoiceSourceId(
                            io.talkcan.dependency.DynamicChoiceSource.RESOLVER_PREFIX + resolverId,
                        ),
                        repositoryId,
                    )
                }
                resolverFactories.remove(repositoryId)
            }
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Public operations — all serialized through [operationMutex]
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Launches asynchronous load/recovery on the service-owned I/O boundary.
     * Non-blocking: returns immediately after launching the load coroutine.
     */
    fun start() {
        serviceScope.launch {
            executeOperation(failClosedOnFailure = true) { gen ->
                _state.value = InstalledPackagesState.Loading(gen)
                repository.loadAndPublish()
            }
        }
    }

    /** Re-runs recovery from the committed index and republishes. */
    suspend fun reload() {
        executeOperation(failClosedOnFailure = true) { gen ->
            _state.value = InstalledPackagesState.Loading(gen)
            repository.recover()
        }
    }

    suspend fun installOrUpdate(
        inputStream: InputStream,
        sourceRecord: PackageSourceRecord,
    ): PackageOutcome<MutationResult> =
        executeOperation { repository.installOrUpdate(inputStream, sourceRecord) }

    suspend fun rollback(repositoryId: GitHubRepositoryIdentity): PackageOutcome<MutationResult> =
        executeOperation { repository.rollback(repositoryId) }

    suspend fun remove(repositoryId: GitHubRepositoryIdentity): PackageOutcome<MutationResult> =
        executeOperation { repository.remove(repositoryId) }

    suspend fun shutdown() {
        publicationMutex.withLock {
            if (!closed.compareAndSet(false, true)) return@withLock
            repository.requestClose()
        }
        val closeOutcome = try {
            repository.closeAndAwait(SHUTDOWN_AWAIT_MILLIS)
        } catch (e: Exception) {
            PackageOutcome.Failure(
                PackageFailure.Shutdown(PackageFailure.ShutdownDetail.TRANSACTION_ABORTED)
            )
        }
        val closeError = (closeOutcome as? PackageOutcome.Failure)?.error
        if (closeError != null) {
            TalkcanLogger.w(
                DIAGNOSTIC_TAG,
                "phase=shutdown outcome=failed detail=${closeError.category}"
            )
        } else {
            TalkcanLogger.i(DIAGNOSTIC_TAG, "phase=shutdown outcome=ok")
        }
        _state.value = InstalledPackagesState.Closed(closeError)
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Operation serialization + generation tracking
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Acquires [operationMutex], assigns a fresh monotonic generation, and runs [block].
     * On failure, sets [Failed] state if the generation is still current. The publisher
     * lambda (called from within [block]) independently sets [Ready] state.
     */
    private suspend fun <T> executeOperation(
        failClosedOnFailure: Boolean = false,
        block: suspend (gen: Long) -> PackageOutcome<T>,
    ): PackageOutcome<T> {
        return operationMutex.withLock {
            if (closed.get()) {
                return@withLock PackageOutcome.Failure(
                    PackageFailure.Shutdown(PackageFailure.ShutdownDetail.SHUTDOWN_IN_PROGRESS)
                )
            }
            val gen = generation.incrementAndGet()
            currentGeneration = gen
            val result = block(gen)
            if (currentGeneration == gen && !closed.get()) {
                when (result) {
                    is PackageOutcome.Failure -> {
                        if (result.error !is PackageFailure.Shutdown) {
                            // For store-level failures during start/reload, publish fail-closed
                            // before exposing Failed so installed-provider IDs resolve as unavailable
                            // rather than missing. Only do this for load errors where the publisher
                            // was never reached (state is still Loading).
                            if (failClosedOnFailure &&
                                _state.value is InstalledPackagesState.Loading &&
                                result.error !is PackageFailure.Loading) {
                                publishStoreUnavailable(gen, result.error)
                            }
                            // Mutation validation failures must not clear a healthy snapshot.
                            val isPublicationRejection = result.error is PackageFailure.Loading &&
                                result.error.detail == PackageFailure.LoadingDetail.PUBLICATION_REJECTED
                            val hasHealthySnapshot = _state.value is InstalledPackagesState.Ready
                            if (!isPublicationRejection || !hasHealthySnapshot) {
                                _state.value = InstalledPackagesState.Failed(gen, result.error)
                            }
                        }
                    }
                    is PackageOutcome.Success -> {
                        when (val value = result.value) {
                            is MutationResult.PublicationFailure -> {
                                // With shutdown serialization, PublicationFailure wraps SHUTDOWN_IN_PROGRESS;
                                // don't clear a healthy snapshot if one exists.
                                if (_state.value !is InstalledPackagesState.Ready) {
                                    _state.value = InstalledPackagesState.Failed(gen, value.error)
                                }
                            }
                            is MutationResult.Reinstalled -> {
                                _state.value = InstalledPackagesState.Ready(
                                    gen,
                                    providerRegistry.snapshotRevision
                                )
                            }
                            else -> { /* Installed/Updated/RolledBack/Removed: publisher set state */ }
                        }
                    }
                }
            }
            result
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Publisher lambda — injected into InstalledPackageRepository
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Called from within the repository's transaction serialization after a successful
     * commit. Atomically replaces the installed-provider snapshot, reconciles the catalogue
     * outside registry sync, and emits structured diagnostics.
     *
     * Only publishes state for the operation that is currently holding [operationMutex]
     * (identified by [currentGeneration]).
     */
    private suspend fun publishMaterialization(result: MaterializationResult): PackageOutcome<Unit> {
        return publicationMutex.withLock {
            val gen = currentGeneration

            if (closed.get()) {
                return@withLock PackageOutcome.Failure(
                    PackageFailure.Shutdown(PackageFailure.ShutdownDetail.SHUTDOWN_IN_PROGRESS)
                )
            }

            val unavailable = LinkedHashMap<ChannelImplementationId, ChannelProviderError.PackageUnavailable>()
            for ((id, failure) in result.failures) {
                unavailable[id] = failure.toPackageUnavailable(id)
            }

            // Atomic publication: registry internally synchronized.
            val publicationResult = providerRegistry.publishInstalledProviders(
                candidate = result.bindings,
                unavailable = unavailable,
            )

            val outcome: PackageOutcome<Unit> = when (publicationResult) {
                is InstalledProvidersPublicationResult.Success -> {
                    emitPublicationDiagnostics(result, unavailable, publicationResult.snapshotRevision)
                    // Task 10.7: Publish resolver factories atomically with provider
                    // publication. Register-once, publish/invalidate on lifecycle.
                    publishResolverFactories(result)
                    // Catalogue reconciliation OUTSIDE provider-registry synchronization.
                    val reconcileOutcome = runCatching { onCatalogueReconcile() }
                    if (reconcileOutcome.isFailure) {
                        TalkcanLogger.e(
                            DIAGNOSTIC_TAG,
                            "phase=reconcile rev=${publicationResult.snapshotRevision} " +
                                "outcome=failed"
                        )
                        return@withLock PackageOutcome.Failure(
                            PackageFailure.Loading(PackageFailure.LoadingDetail.RECONCILIATION_FAILED)
                        )
                    }
                    // Only set Ready if this operation generation is still current.
                    if (currentGeneration == gen && !closed.get()) {
                        _state.value = InstalledPackagesState.Ready(gen, publicationResult.snapshotRevision)
                    }
                    PackageOutcome.Success(Unit)
                }
                is InstalledProvidersPublicationResult.Rejected -> {
                    val code = mapRejectionCode(publicationResult.error)
                    TalkcanLogger.e(
                        DIAGNOSTIC_TAG,
                        "phase=publish outcome=rejected code=$code"
                    )
                    PackageOutcome.Failure(
                        PackageFailure.Loading(PackageFailure.LoadingDetail.PUBLICATION_REJECTED)
                    )
                }
            }
            return@withLock outcome
        }
    }

    /**
     * Fail-closed publication for store-level failures during coordinator start/reload.
     * Publishes a global installed-store failure template so that every canonical
     * github-repository ID without an explicit entry resolves as [PackageUnavailable]
     * with the category/detail derived from the original failure. Runs catalogue
     * reconciliation after the registry state is updated; reconcile failures are
     * diagnosed but do not abort the transition to [Failed].
     *
     * Must be called from within [executeOperation] (holds [operationMutex]) and
     * acquires [publicationMutex] to serialize with shutdown.
     */
    private suspend fun publishStoreUnavailable(gen: Long, error: PackageFailure) {
        publicationMutex.withLock {
            if (closed.get()) return@withLock
            val unavailable = error.toPackageUnavailableProjection()
            val pubResult = providerRegistry.publishFailClosed(unavailable.category, unavailable.detail)
            if (pubResult is InstalledProvidersPublicationResult.Success) {
                TalkcanLogger.w(
                    DIAGNOSTIC_TAG,
                    "phase=fail-closed gen=$gen rev=${pubResult.snapshotRevision} " +
                        "category=${unavailable.category} detail=${unavailable.detail}"
                )
            }
            val reconcileOutcome = runCatching { onCatalogueReconcile() }
            if (reconcileOutcome.isFailure) {
                TalkcanLogger.e(
                    DIAGNOSTIC_TAG,
                    "phase=fail-closed-reconcile gen=$gen outcome=failed"
                )
            }
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Structured diagnostic sink
    //
    // Emits ONLY: provider ID, digest prefix, numeric source release/asset ID,
    // publication phase, typed category/detail, outcome.
    // NEVER emits: Lua source, config payloads, credentials, message text, audio,
    // raw archive bytes, MACs, release tags, asset names, coordinates, or mutable
    // filesystem paths outside the package root.
    // ──────────────────────────────────────────────────────────────────────────

    private fun emitPublicationDiagnostics(
        result: MaterializationResult,
        unavailable: Map<ChannelImplementationId, ChannelProviderError.PackageUnavailable>,
        snapshotRevision: Long,
    ) {
        for ((id, binding) in result.bindings) {
            val record = result.records[id]
            val digestPrefix = binding.expectedDigest.value.take(DIGEST_PREFIX_LENGTH)
            val sourceIds = record?.let { formatSourceIds(it) } ?: "source=unknown"
            TalkcanLogger.i(
                DIAGNOSTIC_TAG,
                "phase=publish rev=$snapshotRevision provider=$id " +
                    "digest=$digestPrefix $sourceIds outcome=available"
            )
        }
        for ((id, error) in unavailable) {
            val record = result.records[id]
            val digestPrefix = record?.digest?.value?.take(DIGEST_PREFIX_LENGTH) ?: "unknown"
            val sourceIds = record?.let { formatSourceIds(it) } ?: "source=unknown"
            TalkcanLogger.w(
                DIAGNOSTIC_TAG,
                "phase=publish rev=$snapshotRevision provider=$id " +
                    "digest=$digestPrefix $sourceIds " +
                    "outcome=unavailable category=${error.category} detail=${error.detail}"
            )
        }
    }

    /**
     * Formats numeric source release/asset IDs only. Never logs tag, name, or coordinates.
     */
    private fun formatSourceIds(record: StoredPackageRevision): String {
        val releaseId = record.sourceRecord.release.releaseId
        val assetId = record.sourceRecord.asset.assetId
        return "release=$releaseId asset=$assetId"
    }

    /**
     * Maps exact typed rejection reason to a bounded diagnostic code string.
     * Never stringifies the arbitrary data-class object.
     */
    private fun mapRejectionCode(reason: InstalledProvidersRejectionReason): String = when (reason) {
        is InstalledProvidersRejectionReason.InvalidId -> "invalid_id"
        is InstalledProvidersRejectionReason.ReservedCollision -> "reserved_collision"
        is InstalledProvidersRejectionReason.AgreementMismatch -> "agreement_mismatch"
        is InstalledProvidersRejectionReason.MissingRevision -> "missing_revision"
        is InstalledProvidersRejectionReason.DuplicateValue -> "duplicate_value"
        is InstalledProvidersRejectionReason.RevisionOverflow -> "revision_overflow"
    }

    internal fun committedSnapshot(): Map<GitHubRepositoryIdentity, StoredProviderRecord> {
        return try {
            when (val result = store.loadIndex()) {
                is PackageOutcome.Success -> result.value.index.providers.toMap()
                is PackageOutcome.Failure -> emptyMap()
            }
        } catch (_: Throwable) {
            emptyMap()
        }
    }

    private companion object {
        const val DIAGNOSTIC_TAG = "InstalledPackages"
        const val DIGEST_PREFIX_LENGTH = 12
        const val SHUTDOWN_AWAIT_MILLIS = 10_000L
    }
}

/**
 * Narrow read-only view of installed-package state for host-domain observers
 * (e.g., generic profile management) that need atomic installed-index
 * snapshots without mutation authority.
 */
internal interface InstalledPackagesView {
    val state: StateFlow<InstalledPackagesState>
    fun committedSnapshot(): Map<GitHubRepositoryIdentity, StoredProviderRecord>
}

/**
 * Internal service-owned facade exposing installed-package state and mutation methods
 * to host actions (tests, future install/remove commands). Wraps the coordinator without
 * exposing repository internals. No UI surface.
 */
internal class InstalledPackagesFacade(private val coordinator: InstalledPackagesCoordinator) : InstalledPackagesView {
    override val state: StateFlow<InstalledPackagesState> get() = coordinator.state

    suspend fun reload() = coordinator.reload()

    suspend fun installOrUpdate(
        inputStream: InputStream,
        sourceRecord: PackageSourceRecord,
    ): PackageOutcome<MutationResult> = coordinator.installOrUpdate(inputStream, sourceRecord)

    suspend fun rollback(repositoryId: GitHubRepositoryIdentity): PackageOutcome<MutationResult> =
        coordinator.rollback(repositoryId)

    suspend fun remove(repositoryId: GitHubRepositoryIdentity): PackageOutcome<MutationResult> =
        coordinator.remove(repositoryId)

    suspend fun shutdown() = coordinator.shutdown()

    /**
     * Immutable snapshot of the committed installed index for package-management
     * summaries. Never exposes content paths, source bytes, or store clients.
     */
    override fun committedSnapshot(): Map<GitHubRepositoryIdentity, StoredProviderRecord> =
        coordinator.committedSnapshot()
}
