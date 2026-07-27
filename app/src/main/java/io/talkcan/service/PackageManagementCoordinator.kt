package io.talkcan.service

import io.talkcan.dependency.ArtifactDigest
import io.talkcan.dependency.GitHubAssetIdentity
import io.talkcan.dependency.GitHubClientBounds
import io.talkcan.dependency.GitHubCompatibleCandidate
import io.talkcan.dependency.GitHubPackageSourceClient
import io.talkcan.dependency.GitHubPublishedRelease
import io.talkcan.dependency.GitHubPublisherTier
import io.talkcan.dependency.GitHubReleaseAsset
import io.talkcan.dependency.GitHubReleaseIdentity
import io.talkcan.dependency.GitHubRepositoryCoordinates
import io.talkcan.dependency.GitHubRepositoryIdentity
import io.talkcan.dependency.GitHubResolvedRepository
import io.talkcan.dependency.GitHubSourceConfiguration
import io.talkcan.dependency.GitHubSourceFailure
import io.talkcan.dependency.GitHubSourceOutcome
import io.talkcan.dependency.GitHubUrlParser
import io.talkcan.dependency.InstalledProviderId
import io.talkcan.dependency.MutationResult
import io.talkcan.dependency.PackageFailure
import io.talkcan.dependency.PackageManifest
import io.talkcan.dependency.PackageOutcome
import io.talkcan.dependency.PackageSourceRecord
import io.talkcan.dependency.PackageValidationBounds
import io.talkcan.dependency.PackageValidator
import io.talkcan.dependency.StoredProviderRecord
import io.talkcan.model.ChannelImplementationProviderRegistry
import io.talkcan.model.ChannelProviderResolution
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import io.talkcan.dependency.PackageCapability
import java.util.concurrent.atomic.AtomicReference

/**
 * Immutable presentation of one committed installed provider.
 * No content paths, source bytes, archive handles, store clients, active digest, or raw ownerId.
 */
public data class InstalledPackageSummary(
    val repositoryId: GitHubRepositoryIdentity,
    val canonicalOwner: String,
    val canonicalRepository: String,
    val trustTier: GitHubPublisherTier,
    val packageVersion: String,
    val releaseTag: String,
    val releaseId: String,
    val assetId: String,
    val rollbackVersion: String?,
    val rollbackReleaseTag: String?,
    val hasRollback: Boolean,
    val status: PackageManagementStatus,
    val failureCategory: String?,
    val failureDetail: String?,
)

public enum class PackageManagementStatus {
    AVAILABLE,
    UNAVAILABLE,
}

/**
 * Opaque generation token incremented for each new inspect/mutate operation.
 * Compose can observe this value to reset acknowledgement / trust across
 * semantically identical operations (same repository, release, asset, digest).
 */
public data class OperationGeneration(val value: Long)

public data class PackageManagementSummary(
    val installedPackages: List<InstalledPackageSummary>,
    val state: PackageManagementState,
    val operationGeneration: OperationGeneration,
)

/**
 * One bounded declared profile type disclosed before trust activation and
 * profile binding. Static manifest data only — no Lua execution, no
 * profile or secret access.
 */
public data class PackageConfirmationProfileType(
    val typeId: String,
    val label: String,
    val declaresSecretFields: Boolean,
)

/**
 * One declared choice resolver disclosed before trust activation, with its
 * requested capability subset (statically validated as a subset of the
 * declaring package's capabilities).
 */
public data class PackageConfirmationResolver(
    val resolverId: String,
    val capabilities: List<String>,
)

/**
 * Immutable confirmation model presented before mutation. Contains canonical resolved
 * coordinates, tier, package presentation, release/asset metadata, the inspected
 * artifact digest, and the complete bounded authority disclosure: declared
 * capabilities, profile types (with protected-secret presence), choice
 * resolvers with requested capabilities, and work queue IDs. Does NOT claim
 * signature, review, audit, or endorsement.
 *
 * [operationGeneration] changes for every new inspection so Compose can distinguish
 * a fresh operation from a prior one even when repository/release/asset/digest match.
 */
public data class PackageConfirmationModel(
    val canonicalRepositoryUrl: String,
    val canonicalOwner: String,
    val canonicalRepository: String,
    val publisherTier: GitHubPublisherTier,
    val packageLabel: String,
    val packageSummary: String,
    val releaseTag: String,
    val publicationTimeEpochSeconds: Long,
    val assetName: String,
    val assetSize: Long,
    val inspectedDigest: ArtifactDigest,
    val declaresNetworkHttp: Boolean,
    val declaresSecretsRead: Boolean,
    val capabilities: List<String>,
    val profileTypes: List<PackageConfirmationProfileType>,
    val choiceResolvers: List<PackageConfirmationResolver>,
    val workQueues: List<String>,
    val operationGeneration: Long,
)

public sealed interface PackageManagementState {
    public object Idle : PackageManagementState
    public object ResolvingRepository : PackageManagementState
    public object LoadingReleases : PackageManagementState
    public data class InspectingCandidate(val current: Int, val total: Int) : PackageManagementState
    public data class AwaitingSelection(
        val repository: GitHubResolvedRepository,
        val candidates: List<GitHubCompatibleCandidate>,
        val ineligible: List<IneligibleRelease>,
    ) : PackageManagementState
    public data class AwaitingTrust(
        val repository: GitHubResolvedRepository,
        val candidate: GitHubCompatibleCandidate,
        val tier: GitHubPublisherTier,
        val confirmation: PackageConfirmationModel,
        val operationGeneration: Long,
    ) : PackageManagementState
    public object Installing : PackageManagementState
    public object Updating : PackageManagementState
    public object Ready : PackageManagementState
    public object Refreshing : PackageManagementState
    public object RollingBack : PackageManagementState
    public object Removing : PackageManagementState
    public data class Failed(val failure: GitHubSourceFailure) : PackageManagementState
    public object Closed : PackageManagementState
}

/**
 * Typed ineligible reason for a sibling release that failed inspection.
 * Preserves the exact source/package failure category for the UI.
 */
public data class IneligibleRelease(
    val release: GitHubPublishedRelease,
    val reason: GitHubSourceFailure,
)

/**
 * Service-owned package-management coordinator.
 *
 * Owns one private bounded inspection-staging directory, serializes all source/mutation
 * operations under [operationMutex], assigns monotonic generations, delegates mutations
 * exclusively to [InstalledPackagesFacade], and never exposes content paths, streams,
 * store clients, staged tokens, or archive bytes in published immutable state.
 *
 * Lock order: this coordinator's [operationMutex] is always acquired BEFORE any
 * [InstalledPackagesFacade] mutation. The facade never calls back into this coordinator,
 * so no recursive mutex acquisition or deadlock is possible.
 *
 * Cancellation: [cancelInspection] promptly cancels the active inspection [Job]
 * (interrupting in-flight network/disk reads via coroutine cancellation) without
 * waiting behind the mutex; the cancelled coroutine releases its own generation lock
 * and runs bounded cleanup.
 */
internal class PackageManagementCoordinator(
    private val facade: InstalledPackagesFacade,
    private val sourceClient: GitHubPackageSourceClient,
    private val providerRegistry: ChannelImplementationProviderRegistry,
    private val storeRoot: File,
    private val serviceScope: CoroutineScope,
    private val inspectStagingDir: File = File(storeRoot, "inspect_staging"),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val bounds: PackageValidationBounds = PackageValidationBounds.DEFAULT,
    private val clientBounds: GitHubClientBounds = GitHubClientBounds.DEFAULT,
) {
    private val operationMutex = Mutex()
    private val closed = AtomicBoolean(false)
    private val generation = AtomicLong(0L)

    @Volatile
    private var activeGeneration: Long = -1L

    private val activeJob = AtomicReference<Job?>(null)

    /**
     * Internal staging state for inspected compatible candidates. Keyed by opaque
     * staging-id (UUID); NEVER exposed in public [PackageManagementState]. Holds the
     * parsed manifest and the exact staged file for reopening after trust confirmation.
     */
    private data class StagedCandidate(
        val manifest: PackageManifest,
        val stagedFile: File,
        val stagingId: String,
    )

    private val stagedCandidates = LinkedHashMap<String, StagedCandidate>()
    /** Maps releaseId -> stagingId for lookup during selection/install. */
    private val releaseToStagingId = LinkedHashMap<String, String>()

    private val _managementState = MutableStateFlow(
        PackageManagementSummary(emptyList(), PackageManagementState.Idle, OperationGeneration(0L))
    )
    val managementState: StateFlow<PackageManagementSummary> = _managementState.asStateFlow()

    // ──────────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Performs bounded startup orphan cleanup of the private inspection staging dir
     * and publishes the initial summary from committed metadata + provider availability.
     * Never touches committed installed content (owned by InstalledPackageStore).
     */
    fun start() {
        cleanupInspectStaging()
        rebuildSummary(PackageManagementState.Idle)
    }

    /**
     * Route-exit cleanup: cancels any active inspection, clears staging, invalidates
     * generation, returns to Idle. Called when the user navigates away from the
     * package management route.
     */
    fun cleanupRouteExit() {
        activeJob.getAndSet(null)?.cancel()
        serviceScope.launch(ioDispatcher) {
            operationMutex.withLock {
                invalidateGeneration()
                cleanupInspectStaging()
                stagedCandidates.clear()
                releaseToStagingId.clear()
                publishState(PackageManagementState.Idle)
            }
        }
    }

    fun shutdown() {
        closed.set(true)
        activeJob.getAndSet(null)?.cancel()
        operationMutex.tryLock().also { locked ->
            invalidateGeneration()
            cleanupInspectStaging()
            stagedCandidates.clear()
            releaseToStagingId.clear()
            publishState(PackageManagementState.Closed)
            if (locked) operationMutex.unlock()
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Source resolution + inspection
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Resolve + inspect a canonical repository URL. Launches a cancellable coroutine;
     * [cancelInspection] interrupts in-flight network/disk reads. Results are published
     * via [managementState]. This method returns immediately.
     */
    fun resolveRepository(url: String) {
        launchCancellableInspection(url, PackageManagementState.ResolvingRepository)
    }

    /**
     * Manual refresh of compatible releases for an already-resolved repository URL.
     * Same pipeline as [resolveRepository] but starts from [PackageManagementState.Refreshing].
     */
    fun refresh(url: String) {
        launchCancellableInspection(url, PackageManagementState.Refreshing)
    }

    private fun launchCancellableInspection(url: String, initialState: PackageManagementState) {
        val job = serviceScope.launch(ioDispatcher) {
            // Capture the current coroutine Job for the finally block — avoids
            // self-reference on activeJob.compareAndSet.
            val currentJob = coroutineContext.job
            try {
                resolveRepositoryInternal(url, initialState)
            } catch (_: CancellationException) {
                operationMutex.withLock {
                    cleanupInspectStaging()
                    stagedCandidates.clear()
                    releaseToStagingId.clear()
                    publishState(PackageManagementState.Idle)
                }
            } catch (_: Throwable) {
                publishState(PackageManagementState.Failed(GitHubSourceFailure.NetworkError))
            } finally {
                activeJob.compareAndSet(currentJob, null)
            }
        }
        activeJob.getAndSet(job)?.cancel()
    }

    private suspend fun resolveRepositoryInternal(
        url: String,
        initialState: PackageManagementState,
    ): GitHubSourceOutcome<Unit> {
        return operationMutex.withLock {
            if (closed.get()) {
                publishState(PackageManagementState.Failed(GitHubSourceFailure.LifecycleClosed))
                return@withLock GitHubSourceOutcome.Failure(GitHubSourceFailure.LifecycleClosed)
            }

            cleanupInspectStaging()
            stagedCandidates.clear()
            releaseToStagingId.clear()

            val gen = generation.incrementAndGet()
            activeGeneration = gen
            publishState(initialState)
            val opGen = OperationGeneration(gen)

            val parsedCoords = when (val parseRes = GitHubUrlParser.parse(url, clientBounds)) {
                is GitHubSourceOutcome.Success -> parseRes.value
                is GitHubSourceOutcome.Failure -> {
                    publishState(PackageManagementState.Failed(parseRes.error))
                    return@withLock parseRes
                }
            }

            val resolvedRepo = when (val resolveRes = sourceClient.resolveRepository(parsedCoords)) {
                is GitHubSourceOutcome.Success -> resolveRes.value
                is GitHubSourceOutcome.Failure -> {
                    publishState(PackageManagementState.Failed(resolveRes.error))
                    return@withLock resolveRes
                }
            }
            if (!isCurrent(gen)) {
                cleanupInspectStaging()
                stagedCandidates.clear()
                releaseToStagingId.clear()
                publishState(PackageManagementState.Idle)
                return@withLock GitHubSourceOutcome.Failure(GitHubSourceFailure.StaleOperation)
            }

            // Derive canonical coordinates immediately from the resolved repository.
            // These are used for ALL subsequent operations (release listing, download,
            // inspection PackageSourceRecord) — never the raw parsed submitted URL.
            val canonicalCoords = canonicalCoordinates(resolvedRepo)

            publishState(PackageManagementState.LoadingReleases)
            val releases = when (val releaseRes = sourceClient.listStableReleaseAssets(canonicalCoords)) {
                is GitHubSourceOutcome.Success -> releaseRes.value
                is GitHubSourceOutcome.Failure -> {
                    publishState(PackageManagementState.Failed(releaseRes.error))
                    return@withLock releaseRes
                }
            }
            if (!isCurrent(gen)) {
                cleanupInspectStaging()
                stagedCandidates.clear()
                releaseToStagingId.clear()
                publishState(PackageManagementState.Idle)
                return@withLock GitHubSourceOutcome.Failure(GitHubSourceFailure.StaleOperation)
            }

            val capped = releases.take(clientBounds.maxInspectionFiles.coerceAtLeast(1))
            val compatibleCandidates = mutableListOf<GitHubCompatibleCandidate>()
            val ineligible = mutableListOf<IneligibleRelease>()
            val total = capped.size
            for ((i, pair) in capped.withIndex()) {
                if (!isCurrent(gen)) {
                    cleanupInspectStaging()
                    stagedCandidates.clear()
                    releaseToStagingId.clear()
                    publishState(PackageManagementState.Idle)
                    return@withLock GitHubSourceOutcome.Failure(GitHubSourceFailure.StaleOperation)
                }
                val (release, asset) = pair
                publishState(PackageManagementState.InspectingCandidate(i + 1, total))
                inspectOneCandidate(gen, resolvedRepo, canonicalCoords, release, asset, compatibleCandidates, ineligible)
            }
            if (!isCurrent(gen)) {
                cleanupInspectStaging()
                stagedCandidates.clear()
                releaseToStagingId.clear()
                publishState(PackageManagementState.Idle)
                return@withLock GitHubSourceOutcome.Failure(GitHubSourceFailure.StaleOperation)
            }

            if (compatibleCandidates.isEmpty()) {
                val failure = GitHubSourceFailure.IncompatiblePackage
                publishState(PackageManagementState.Failed(failure))
                return@withLock GitHubSourceOutcome.Failure(failure)
            }

            _managementState.value = PackageManagementSummary(
                _managementState.value.installedPackages,
                PackageManagementState.AwaitingSelection(resolvedRepo, compatibleCandidates, ineligible),
                opGen,
            )
            GitHubSourceOutcome.Success(Unit)
        }
    }

    private suspend fun inspectOneCandidate(
        gen: Long,
        resolvedRepo: GitHubResolvedRepository,
        canonicalCoords: GitHubRepositoryCoordinates,
        release: GitHubPublishedRelease,
        asset: GitHubReleaseAsset,
        compatibleCandidates: MutableList<GitHubCompatibleCandidate>,
        ineligible: MutableList<IneligibleRelease>,
    ) {
        if (asset.size > clientBounds.maxExactAssetBytes) {
            ineligible.add(IneligibleRelease(release, GitHubSourceFailure.BoundsExceeded))
            return
        }

        val stagingId = UUID.randomUUID().toString()
        val downloadFile = File(inspectStagingDir, "dl-${gen}-${stagingId}")
        val stagedFile = File(inspectStagingDir, "staged-${gen}-${stagingId}")

        var downloadOk = false
        try {
            val downloadRes = downloadFile.outputStream().use { fos ->
                sourceClient.downloadAsset(canonicalCoords, asset, fos)
            }
            if (downloadRes is GitHubSourceOutcome.Success) {
                downloadOk = true
            } else {
                val failure = (downloadRes as GitHubSourceOutcome.Failure).error
                ineligible.add(IneligibleRelease(release, failure))
            }
        } catch (_: IOException) {
            ineligible.add(IneligibleRelease(release, GitHubSourceFailure.NetworkError))
        } finally {
            if (!downloadOk) downloadFile.delete()
        }
        if (!downloadOk) return

        val sourceRecord = PackageSourceRecord(
            repositoryId = resolvedRepo.id,
            coordinates = canonicalCoords,
            release = GitHubReleaseIdentity(release.releaseId, release.tag, release.isPrerelease),
            asset = GitHubAssetIdentity(asset.assetId, asset.name),
            ownerId = resolvedRepo.owner.ownerId,
        )

        val validationOutcome = try {
            downloadFile.inputStream().use { fis ->
                PackageValidator.validatePackage(fis, sourceRecord, stagedFile, bounds)
            }
        } catch (_: IOException) {
            PackageOutcome.Failure(PackageFailure.Format(PackageFailure.FormatDetail.INVALID_ZIP))
        } finally {
            downloadFile.delete()
        }

        when (validationOutcome) {
            is PackageOutcome.Success -> {
                stagedCandidates[stagingId] = StagedCandidate(
                    manifest = validationOutcome.value.manifest,
                    stagedFile = stagedFile,
                    stagingId = stagingId,
                )
                releaseToStagingId[release.releaseId] = stagingId
                compatibleCandidates.add(
                    GitHubCompatibleCandidate(release, asset, validationOutcome.value.digest, stagingId)
                )
            }
            is PackageOutcome.Failure -> {
                stagedFile.delete()
                ineligible.add(IneligibleRelease(release, mapPackageFailureToSource(validationOutcome.error)))
            }
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Selection + trust confirmation
    // ──────────────────────────────────────────────────────────────────────────

    suspend fun selectRelease(releaseId: String): GitHubSourceOutcome<Unit> = withContext(ioDispatcher) {
        operationMutex.withLock {
            val summary = _managementState.value
            val state = summary.state
            if (state !is PackageManagementState.AwaitingSelection) {
                return@withLock GitHubSourceOutcome.Failure(GitHubSourceFailure.StaleOperation)
            }
            val candidate = state.candidates.find { it.release.releaseId == releaseId }
                ?: return@withLock GitHubSourceOutcome.Failure(GitHubSourceFailure.StaleOperation)

            val repository = state.repository
            val tier = evaluateTier(repository.owner.ownerId)
            val stagingId = releaseToStagingId[releaseId]
                ?: return@withLock GitHubSourceOutcome.Failure(GitHubSourceFailure.StagingError)
            val staged = stagedCandidates[stagingId]
                ?: return@withLock GitHubSourceOutcome.Failure(GitHubSourceFailure.StagingError)

            val confirmation = PackageConfirmationModel(
                canonicalRepositoryUrl = canonicalUrl(repository),
                canonicalOwner = repository.owner.login,
                canonicalRepository = repository.fullName.substringAfter('/'),
                publisherTier = tier,
                packageLabel = staged.manifest.presentation.label,
                packageSummary = staged.manifest.presentation.summary,
                releaseTag = candidate.release.tag,
                publicationTimeEpochSeconds = candidate.release.publishedAtEpochSeconds,
                assetName = candidate.asset.name,
                assetSize = candidate.asset.size,
                inspectedDigest = candidate.digest,
                declaresNetworkHttp = staged.manifest.capabilities.contains(PackageCapability.NETWORK_HTTP),
                declaresSecretsRead = staged.manifest.capabilities.contains(PackageCapability.SECRETS_READ),
                capabilities = PackageCapability.ALL.filter { it in staged.manifest.capabilities },
                profileTypes = staged.manifest.profileTypes.map { typeDecl ->
                    PackageConfirmationProfileType(
                        typeId = typeDecl.id,
                        label = typeDecl.label,
                        declaresSecretFields = typeDecl.schema.secretFieldIds().isNotEmpty(),
                    )
                },
                choiceResolvers = staged.manifest.choiceResolvers.map { resolver ->
                    PackageConfirmationResolver(
                        resolverId = resolver.id,
                        capabilities = PackageCapability.ALL.filter { it in resolver.capabilities },
                    )
                },
                workQueues = staged.manifest.workQueues.map { it.id },
                operationGeneration = summary.operationGeneration.value,
            )

            publishState(
                PackageManagementState.AwaitingTrust(repository, candidate, tier, confirmation, summary.operationGeneration.value)
            )
            GitHubSourceOutcome.Success(Unit)
        }
    }

    /**
     * Trust confirmation then exact staged-byte handoff. Reopens the EXACT staged bytes
     * already inspected and passes them to [InstalledPackagesFacade.installOrUpdate],
     * which rehashes and revalidates before commit (no second HTTP path).
     *
     * Official install/update requires explicit confirmation. Community requires explicit
     * current trusted-code acknowledgement.
     */
    suspend fun confirmTrustAndInstall(acknowledged: Boolean): GitHubSourceOutcome<MutationResult> = withContext(ioDispatcher) {
        operationMutex.withLock {
            val summary = _managementState.value
            val state = summary.state
            if (state !is PackageManagementState.AwaitingTrust) {
                return@withLock GitHubSourceOutcome.Failure(GitHubSourceFailure.StaleOperation)
            }
            val candidate = state.candidate
            val repository = state.repository
            if (!acknowledged) {
                return@withLock GitHubSourceOutcome.Failure(GitHubSourceFailure.TrustRefused)
            }
            val isUpdate = summary.installedPackages.any { it.repositoryId == repository.id }
            publishState(if (isUpdate) PackageManagementState.Updating else PackageManagementState.Installing)

            val stagingId = releaseToStagingId[candidate.release.releaseId]
            val staged = if (stagingId != null) stagedCandidates[stagingId] else null
            if (staged == null || !staged.stagedFile.exists()) {
                cleanupInspectStaging()
                stagedCandidates.clear()
                releaseToStagingId.clear()
                publishState(PackageManagementState.Failed(GitHubSourceFailure.StagingError))
                return@withLock GitHubSourceOutcome.Failure(GitHubSourceFailure.StagingError)
            }

            val sourceRecord = PackageSourceRecord(
                repositoryId = repository.id,
                coordinates = canonicalCoordinates(repository),
                release = GitHubReleaseIdentity(
                    candidate.release.releaseId,
                    candidate.release.tag,
                    candidate.release.isPrerelease,
                ),
                asset = GitHubAssetIdentity(candidate.asset.assetId, candidate.asset.name),
                ownerId = repository.owner.ownerId,
            )

            val outcome = try {
                FileInputStream(staged.stagedFile).use { fis ->
                    facade.installOrUpdate(fis, sourceRecord)
                }
            } catch (_: IOException) {
                PackageOutcome.Failure(PackageFailure.Storage(PackageFailure.StorageDetail.WRITE_FAILED))
            } finally {
                cleanupInspectStaging()
                stagedCandidates.clear()
                releaseToStagingId.clear()
            }

            when (outcome) {
                is PackageOutcome.Success -> {
                    rebuildSummary(PackageManagementState.Ready)
                    GitHubSourceOutcome.Success(outcome.value)
                }
                is PackageOutcome.Failure -> {
                    val src = mapPackageFailureToSource(outcome.error)
                    rebuildSummary(PackageManagementState.Failed(src))
                    GitHubSourceOutcome.Failure(src)
                }
            }
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Rollback + removal (delegated to facade)
    // ──────────────────────────────────────────────────────────────────────────

    suspend fun confirmRollback(
        repositoryId: GitHubRepositoryIdentity,
        confirmed: Boolean,
    ): GitHubSourceOutcome<MutationResult> = withContext(ioDispatcher) {
        operationMutex.withLock {
            if (closed.get()) {
                return@withLock GitHubSourceOutcome.Failure(GitHubSourceFailure.LifecycleClosed)
            }
            if (!confirmed) {
                return@withLock GitHubSourceOutcome.Failure(GitHubSourceFailure.TrustRefused)
            }
            publishState(PackageManagementState.RollingBack)
            val outcome = facade.rollback(repositoryId)
            handleDelegatedOutcome(outcome)
        }
    }

    suspend fun confirmRemove(
        repositoryId: GitHubRepositoryIdentity,
        confirmed: Boolean,
    ): GitHubSourceOutcome<MutationResult> = withContext(ioDispatcher) {
        operationMutex.withLock {
            if (closed.get()) {
                return@withLock GitHubSourceOutcome.Failure(GitHubSourceFailure.LifecycleClosed)
            }
            if (!confirmed) {
                return@withLock GitHubSourceOutcome.Failure(GitHubSourceFailure.TrustRefused)
            }
            publishState(PackageManagementState.Removing)
            val outcome = facade.remove(repositoryId)
            handleDelegatedOutcome(outcome, idleOnSuccess = true)
        }
    }

    private fun handleDelegatedOutcome(
        outcome: PackageOutcome<MutationResult>,
        idleOnSuccess: Boolean = false,
    ): GitHubSourceOutcome<MutationResult> {
        return when (outcome) {
            is PackageOutcome.Success -> {
                rebuildSummary(if (idleOnSuccess) PackageManagementState.Idle else PackageManagementState.Ready)
                GitHubSourceOutcome.Success(outcome.value)
            }
            is PackageOutcome.Failure -> {
                val src = mapPackageFailureToSource(outcome.error)
                rebuildSummary(PackageManagementState.Failed(src))
                GitHubSourceOutcome.Failure(src)
            }
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Cancellation
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Promptly cancels any active inspection coroutine (interrupting in-flight I/O),
     * invalidates the generation, and clears staging. Does not wait behind
     * [operationMutex]; the cancelled coroutine runs its own bounded cleanup (mutex
     * acquired once in the CancellationException handler). The generation invalidation
     * after launch ensures that even if the cancelled coroutine's cleanup hasn't run
     * yet, any subsequent inspection sees a fresh generation.
     */
    fun cancelInspection() {
        activeJob.getAndSet(null)?.cancel()
        invalidateGeneration()
        serviceScope.launch(ioDispatcher) {
            operationMutex.withLock {
                cleanupInspectStaging()
                stagedCandidates.clear()
                releaseToStagingId.clear()
                publishState(PackageManagementState.Idle)
            }
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Helpers
    // ──────────────────────────────────────────────────────────────────────────

    private fun isCurrent(gen: Long): Boolean = activeGeneration == gen && !closed.get()

    /**
     * Invalidates the active generation so no stale/raced coroutine can commit
     * staged candidates or perform mutations after cleanup.
     */
    private fun invalidateGeneration() {
        activeGeneration = -1L
        generation.incrementAndGet()
    }


    private fun evaluateTier(ownerId: String): GitHubPublisherTier =
        if (ownerId == GitHubSourceConfiguration.OFFICIAL_PUBLISHER_ID) GitHubPublisherTier.OFFICIAL
        else GitHubPublisherTier.COMMUNITY

    private fun canonicalUrl(repository: GitHubResolvedRepository): String =
        "https://github.com/${repository.fullName}"

    private fun canonicalCoordinates(repository: GitHubResolvedRepository): GitHubRepositoryCoordinates =
        GitHubRepositoryCoordinates(repository.owner.login, repository.fullName.substringAfter('/'))

    /**
     * Maps a package-level failure to a typed source failure, preserving the exact
     * category/detail for mutation failures (never collapses everything into StagingError).
     */
    private fun mapPackageFailureToSource(failure: PackageFailure): GitHubSourceFailure = when (failure) {
        is PackageFailure.Format -> GitHubSourceFailure.Format(failure.detail)
        is PackageFailure.Identity -> GitHubSourceFailure.Identity(failure.detail)
        is PackageFailure.Compatibility -> GitHubSourceFailure.Compatibility(failure.detail)
        is PackageFailure.Integrity -> GitHubSourceFailure.Integrity(failure.detail)
        is PackageFailure.Storage -> GitHubSourceFailure.Storage(failure.detail)
        is PackageFailure.Mutation -> GitHubSourceFailure.Mutation(failure.detail)
        is PackageFailure.Rollback -> GitHubSourceFailure.Rollback(failure.detail)
        is PackageFailure.Shutdown -> GitHubSourceFailure.LifecycleClosed
        is PackageFailure.Recovery -> GitHubSourceFailure.InvalidPackage
        is PackageFailure.Loading -> GitHubSourceFailure.InvalidPackage
        is PackageFailure.Capability -> GitHubSourceFailure.InvalidPackage
    }

    private fun cleanupInspectStaging() {
        try {
            if (inspectStagingDir.exists()) {
                val files = inspectStagingDir.listFiles()
                if (files != null) {
                    var count = 0
                    val cap = clientBounds.maxInspectionFiles * 10L
                    for (file in files) {
                        if (count >= cap) break
                        if (file.isFile) {
                            file.delete()
                            count++
                        }
                    }
                }
            } else {
                inspectStagingDir.mkdirs()
            }
        } catch (_: Throwable) {
            // Best-effort cleanup; never fatal.
        }
    }

    private fun publishState(state: PackageManagementState) {
        _managementState.value = _managementState.value.copy(state = state)
    }

    /**
     * Rebuilds [PackageManagementSummary.installedPackages] from the committed installed
     * index and published provider availability. Trust tier is evaluated by EXACT owner-ID
     * equality against the pinned official publisher ID (1224006); no repository-ID,
     * login, or label fallback. Never exposes raw ownerId, content paths, source bytes,
     * store clients, staged tokens, or digests.
     */
    private fun rebuildSummary(state: PackageManagementState) {
        val packages = try {
            val snapshot = facade.committedSnapshot()
            snapshot.map { (repoId, record) ->
                val implId = InstalledProviderId.derive(repoId)
                val resolution = providerRegistry.resolve(implId)
                val status = when (resolution) {
                    is ChannelProviderResolution.Available -> PackageManagementStatus.AVAILABLE
                    else -> PackageManagementStatus.UNAVAILABLE
                }
                val (failureCategory, failureDetail) = when (resolution) {
                    is ChannelProviderResolution.Unavailable -> Pair(
                        resolution.error.category.name,
                        resolution.error.detail.name,
                    )
                    else -> Pair(null, null)
                }
                InstalledPackageSummary(
                    repositoryId = repoId,
                    canonicalOwner = record.active.sourceRecord.coordinates.owner,
                    canonicalRepository = record.active.sourceRecord.coordinates.repository,
                    trustTier = evaluateTier(record.active.sourceRecord.ownerId),
                    packageVersion = record.active.manifest.packageVersion,
                    releaseTag = record.active.sourceRecord.release.tag,
                    releaseId = record.active.sourceRecord.release.releaseId,
                    assetId = record.active.sourceRecord.asset.assetId,
                    rollbackVersion = record.rollback?.manifest?.packageVersion,
                    rollbackReleaseTag = record.rollback?.sourceRecord?.release?.tag,
                    hasRollback = record.rollback != null,
                    status = status,
                    failureCategory = failureCategory,
                    failureDetail = failureDetail,
                )
            }
        } catch (_: Throwable) {
            emptyList()
        }
        _managementState.value = PackageManagementSummary(packages, state, OperationGeneration(generation.get()))
    }
}
