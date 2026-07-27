package io.talkcan.service

import io.talkcan.dependency.GitHubRepositoryIdentity
import io.talkcan.dependency.PackageCapability
import io.talkcan.dependency.PackageSourceRecord
import io.talkcan.dependency.ProfileTypeDeclaration
import io.talkcan.profile.ProfileAvailability
import io.talkcan.profile.ProfileId
import io.talkcan.profile.ProfileOperationResult
import io.talkcan.profile.ProfileRecord
import io.talkcan.profile.ProfileRepository
import io.talkcan.profile.ProfileSchema
import io.talkcan.profile.ProfileScalarValue
import io.talkcan.profile.ProfileSecretCleanupPending
import io.talkcan.profile.ProfileTypeIdentity
import io.talkcan.profile.SecretEditAction
import io.talkcan.profile.ProfileFailure
import io.talkcan.model.DynamicConfigurationChoice
import io.talkcan.model.DynamicConfigurationChoiceRequest
import io.talkcan.model.DynamicConfigurationChoiceResolution
import io.talkcan.model.DynamicConfigurationChoiceResolver
import io.talkcan.model.DynamicConfigurationChoiceUnavailableReason
import io.talkcan.model.DynamicChoiceSourceKind
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 5.1: Generic profile-management state.
 *
 * Every field is derived from one atomic composition of the committed
 * installed-package index and the profile repository snapshot, checked
 * against the installed-package generation before and after each read so a
 * concurrent package mutation can never tear the publication.
 */
public data class GenericProfileManagementState(
    val publishedTypes: List<PublishedProfileTypeSummary> = emptyList(),
    val profiles: List<GenericProfileSummary> = emptyList(),
    val isOperationActive: Boolean = false,
    val failureMessage: String? = null,
    val generation: Long = -1L,
    /** Monotonic count of completed create/edit/delete operations. */
    val completedOperations: Long = 0L,
)

/**
 * 5.2: One profile type discovered from an installed package manifest.
 *
 * Discovery reads only validated manifest metadata — no package Lua is
 * executed, no resolver runs, no profile/secret is touched. Source identity
 * is the canonical repository coordinates plus the durable repository
 * database ID carried by [identity].
 */
public data class PublishedProfileTypeSummary(
    val identity: ProfileTypeIdentity,
    val canonicalOwner: String,
    val canonicalRepository: String,
    val label: String,
    val help: String?,
    val schema: ProfileSchema,
    val hasSecretFields: Boolean,
    val declaresSecretsRead: Boolean,
    val declaresNetworkHttp: Boolean,
    val isSchemaIncompatible: Boolean = false,
)

/**
 * 5.4: One retained profile record projected for display. Unavailable
 * profiles are presented with a typed reason; the record payload is never
 * mutated by projection and channel configuration is never rewritten.
 */
public data class GenericProfileSummary(
    val id: ProfileId,
    val identity: ProfileTypeIdentity,
    val displayName: String,
    val record: ProfileRecord,
    val typeLabel: String?,
    val isUnavailable: Boolean = false,
    val unavailableReason: String? = null,
)

/**
 * 5.1: Service-owned coordinator for generic profile management.
 *
 * State is sourced exclusively from the atomic installed-package/profile
 * snapshots: the committed installed index ([InstalledPackagesView]) yields
 * the currently declared profile types (manifest metadata only), and the
 * [ProfileRepository] snapshot yields retained records. The coordinator
 * reconciles repository type publication from installed manifests so that
 * create/edit/delete operate against exactly the schemas the installed
 * packages currently declare:
 *
 * - newly discovered types are published (revalidating retained profiles);
 * - types removed by a same-repository update are unpublished without
 *   touching retained records;
 * - removed packages unpublish their repository, projecting retained profiles
 *   to [ProfileAvailability.UNAVAILABLE_PACKAGE_REMOVED] while preserving
 *   records and protected references for explicit reinstall or deletion.
 *
 * The coordinator never reads or rewrites channel catalogue data; deleting
 * or editing a profile cannot rebind channel definitions that reference it.
 */
internal class GenericProfileManagementCoordinator(
    private val packages: InstalledPackagesView,
    private val profileRepository: ProfileRepository,
    private val serviceScope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val mutex = Mutex()
    private val _state = MutableStateFlow(GenericProfileManagementState())
    val state: StateFlow<GenericProfileManagementState> = _state.asStateFlow()

    private data class DiscoveredType(
        val declaration: ProfileTypeDeclaration,
        val sourceRecord: PackageSourceRecord,
        val capabilities: Set<String>,
    )

    /**
     * Rebuilds on every installed-package state publication so package
     * install/update/rollback/removal is reflected without a profile
     * operation. Cancelled by [close].
     */
    private val collectorJob: Job = serviceScope.launch(ioDispatcher) {
        packages.state.collect {
            mutex.withLock {
                rebuildStateLocked()
            }
        }
    }

    /** Stops observing installed-package state. Idempotent. */
    fun close() {
        collectorJob.cancel()
    }

    fun refresh() {
        serviceScope.launch(ioDispatcher) {
            mutex.withLock {
                rebuildStateLocked()
            }
        }
    }

    /**
     * 5.1/5.4: Rebuild the published state from one generation-checked
     * composition of the installed index and the profile snapshot. Callers
     * hold [mutex].
     */
    private fun rebuildStateLocked() {
        val facadeState = packages.state.value
        if (facadeState !is InstalledPackagesState.Ready) {
            // Preserve the last publication until the installed index
            // publishes a complete ready snapshot.
            return
        }
        val generation = facadeState.generation

        val installed = packages.committedSnapshot()
        // Generation gate: abort before any profile mutation if the
        // installed index moved underneath the snapshot read.
        if (packages.state.value.generation != generation) return

        // 5.2: Discover published profile types from installed manifests.
        // Manifest metadata only — no Lua execution.
        val discovered = LinkedHashMap<ProfileTypeIdentity, DiscoveredType>()
        for ((repositoryId, record) in installed) {
            val revision = record.active
            for (typeDecl in revision.manifest.profileTypes) {
                val identity = ProfileTypeIdentity(repositoryId, typeDecl.id)
                discovered[identity] = DiscoveredType(
                    declaration = typeDecl,
                    sourceRecord = revision.sourceRecord,
                    capabilities = revision.manifest.capabilities,
                )
            }
        }

        val before = profileRepository.snapshot()
        if (packages.state.value.generation != generation) return

        // Reconcile repository publication from installed manifests.
        var reconcileFailure: ProfileFailure? = null
        for ((identity, found) in discovered) {
            if (before.publishedTypes[identity] != found.declaration.schema) {
                val result = profileRepository.publishType(identity, found.declaration.schema)
                if (result is ProfileOperationResult.Failure && reconcileFailure == null) {
                    reconcileFailure = result.failure
                }
            }
        }

        val orphanRepositories = LinkedHashSet<GitHubRepositoryIdentity>()
        for (identity in before.publishedTypes.keys) {
            if (identity in discovered) continue
            if (identity.repositoryId !in installed.keys) {
                orphanRepositories.add(identity.repositoryId)
            } else {
                // Same repository, type removed by an update: revoke access,
                // preserve records unchanged.
                profileRepository.unpublishType(identity)
            }
        }
        // Retained profiles whose repository is no longer installed but whose
        // types were never published in this process (e.g., loaded from disk
        // after a prior removal) still project to the package-removed state.
        for (record in before.profiles) {
            val repositoryId = record.typeIdentity.repositoryId
            if (repositoryId !in installed.keys &&
                repositoryId !in orphanRepositories &&
                record.availability == ProfileAvailability.AVAILABLE
            ) {
                orphanRepositories.add(repositoryId)
            }
        }
        for (repositoryId in orphanRepositories) {
            val result = profileRepository.unpublishRepository(repositoryId)
            if (result is ProfileOperationResult.Failure && reconcileFailure == null) {
                reconcileFailure = result.failure
            }
        }

        val after = profileRepository.snapshot()
        if (packages.state.value.generation != generation) return

        val typeSummaries = ArrayList<PublishedProfileTypeSummary>(discovered.size)
        for ((identity, found) in discovered) {
            val incompatible = after.profiles.any { profile ->
                profile.typeIdentity == identity &&
                    profile.availability == ProfileAvailability.UNAVAILABLE_SCHEMA_INCOMPATIBLE
            }
            typeSummaries.add(
                PublishedProfileTypeSummary(
                    identity = identity,
                    canonicalOwner = found.sourceRecord.coordinates.owner,
                    canonicalRepository = found.sourceRecord.coordinates.repository,
                    label = found.declaration.label,
                    help = found.declaration.help,
                    schema = found.declaration.schema,
                    hasSecretFields = found.declaration.schema.secretFieldIds().isNotEmpty(),
                    declaresSecretsRead = PackageCapability.SECRETS_READ in found.capabilities,
                    declaresNetworkHttp = PackageCapability.NETWORK_HTTP in found.capabilities,
                    isSchemaIncompatible = incompatible,
                )
            )
        }
        typeSummaries.sortWith(
            compareBy(
                { it.canonicalOwner },
                { it.canonicalRepository },
                { it.identity.localTypeId },
            )
        )

        val profileSummaries = after.profiles.map { record ->
            val known = record.typeIdentity in discovered
            val reason = when (record.availability) {
                ProfileAvailability.AVAILABLE -> when {
                    known -> null
                    record.typeIdentity.repositoryId !in installed.keys -> UNAVAILABLE_PACKAGE_REMOVED_REASON
                    else -> UNAVAILABLE_TYPE_REMOVED_REASON
                }
                ProfileAvailability.UNAVAILABLE_SCHEMA_INCOMPATIBLE -> UNAVAILABLE_SCHEMA_INCOMPATIBLE_REASON
                ProfileAvailability.UNAVAILABLE_PACKAGE_REMOVED -> UNAVAILABLE_PACKAGE_REMOVED_REASON
                ProfileAvailability.UNAVAILABLE_STORAGE_CORRUPT -> UNAVAILABLE_STORAGE_CORRUPT_REASON
            }
            GenericProfileSummary(
                id = record.profileId,
                identity = record.typeIdentity,
                displayName = record.displayName,
                record = record,
                typeLabel = discovered[record.typeIdentity]?.declaration?.label,
                isUnavailable = reason != null,
                unavailableReason = reason,
            )
        }.sortedWith(compareBy({ it.displayName }, { it.id.value }))

        _state.value = _state.value.copy(
            publishedTypes = typeSummaries,
            profiles = profileSummaries,
            generation = generation,
            failureMessage = reconcileFailure?.let { describeFailure(it) } ?: _state.value.failureMessage,
        )
    }

    /**
     * 3.4/5.3: Create a profile of a published type with full candidate
     * validation and all-or-nothing metadata/secret commit.
     */
    suspend fun createProfile(
        identity: ProfileTypeIdentity,
        displayName: String,
        scalars: Map<String, ProfileScalarValue>,
        secrets: Map<String, CharSequence> = emptyMap(),
    ): ProfileOperationResult<ProfileRecord> = mutex.withLock {
        _state.value = _state.value.copy(isOperationActive = true, failureMessage = null)
        val result = profileRepository.createProfile(identity, displayName, scalars, secrets)
        when (result) {
            is ProfileOperationResult.Success -> {
                rebuildStateLocked()
                _state.value = _state.value.copy(
                    isOperationActive = false,
                    failureMessage = null,
                    completedOperations = _state.value.completedOperations + 1L,
                )
            }
            is ProfileOperationResult.Failure -> {
                _state.value = _state.value.copy(
                    isOperationActive = false,
                    failureMessage = describeFailure(result.failure),
                    completedOperations = _state.value.completedOperations + 1L,
                )
            }
        }
        result
    }

    /**
     * 3.5/5.3: Edit a profile with revision advancement, exact scalar
     * preservation for null arguments, and retain/replace/clear secret
     * semantics. Secret plaintext enters only the protected store boundary.
     */
    suspend fun editProfile(
        id: ProfileId,
        displayName: String? = null,
        scalars: Map<String, ProfileScalarValue>? = null,
        secretEdits: Map<String, SecretEditAction> = emptyMap(),
    ): ProfileOperationResult<ProfileRecord> = mutex.withLock {
        _state.value = _state.value.copy(isOperationActive = true, failureMessage = null)
        val result = profileRepository.editProfile(id, displayName, scalars, secretEdits)
        when (result) {
            is ProfileOperationResult.Success -> {
                rebuildStateLocked()
                _state.value = _state.value.copy(
                    isOperationActive = false,
                    failureMessage = null,
                    completedOperations = _state.value.completedOperations + 1L,
                )
            }
            is ProfileOperationResult.Failure -> {
                _state.value = _state.value.copy(
                    isOperationActive = false,
                    failureMessage = describeFailure(result.failure),
                    completedOperations = _state.value.completedOperations + 1L,
                )
            }
        }
        result
    }

    /**
     * 3.6/5.4: Delete a profile. Metadata removal commits first; pending
     * protected-secret cleanups get one immediate retry and are returned
     * either way. Channel configuration is never touched: referencing
     * channels preserve their selected profile ID and become unavailable
     * through their own readiness projection.
     */
    suspend fun deleteProfile(id: ProfileId): ProfileOperationResult<List<ProfileSecretCleanupPending>> = mutex.withLock {
        _state.value = _state.value.copy(isOperationActive = true, failureMessage = null)
        val result = profileRepository.deleteProfile(id)
        when (result) {
            is ProfileOperationResult.Success -> {
                for (pending in result.value) {
                    profileRepository.retrySecretCleanup(pending)
                }
                rebuildStateLocked()
                _state.value = _state.value.copy(
                    isOperationActive = false,
                    failureMessage = null,
                    completedOperations = _state.value.completedOperations + 1L,
                )
            }
            is ProfileOperationResult.Failure -> {
                _state.value = _state.value.copy(
                    isOperationActive = false,
                    failureMessage = describeFailure(result.failure),
                    completedOperations = _state.value.completedOperations + 1L,
                )
            }
        }
        result
    }

    private fun describeFailure(failure: ProfileFailure): String = when (failure) {
        is ProfileFailure.NotLoaded -> "Profile storage is not loaded."
        is ProfileFailure.ProfileNotFound -> "Profile not found."
        is ProfileFailure.TypeNotPublished -> "Profile type is not published."
        is ProfileFailure.DuplicateProfileId -> "Duplicate profile ID."
        is ProfileFailure.DisplayNameInvalid -> failure.reason
        is ProfileFailure.ValidationFailed -> failure.reason
        is ProfileFailure.BoundsExceeded -> failure.reason
        is ProfileFailure.SchemaIncompatible -> "Schema incompatible: ${failure.reason}"
        is ProfileFailure.SecretMutationFailed -> "Protected secret storage failure: ${failure.reason}"
        is ProfileFailure.StorageFailed -> "Profile storage failure: ${failure.operation}"
        is ProfileFailure.ForeignRepository -> "Profile type belongs to a different repository."
        is ProfileFailure.PackageRemoved -> UNAVAILABLE_PACKAGE_REMOVED_REASON
    }

    private companion object {
        const val UNAVAILABLE_SCHEMA_INCOMPATIBLE_REASON = "Schema incompatible"
        const val UNAVAILABLE_PACKAGE_REMOVED_REASON = "Package removed"
        const val UNAVAILABLE_STORAGE_CORRUPT_REASON = "Storage corrupt"
        const val UNAVAILABLE_TYPE_REMOVED_REASON = "Type no longer published"
    }
}

/**
 * Resolves a package-declared `profile:<type>` source against profiles owned by
 * that exact repository. Unscoped and unpublished sources fail closed.
 */
internal class GenericProfileDynamicChoiceResolver(
    private val profileRepository: ProfileRepository,
) : DynamicConfigurationChoiceResolver {
    override suspend fun resolve(
        request: DynamicConfigurationChoiceRequest,
    ): DynamicConfigurationChoiceResolution {
        if (!profileRepository.isLoaded) {
            return unavailable(DynamicConfigurationChoiceUnavailableReason.HOST_NOT_READY)
        }
        val source = request.effectiveSourceKind as? DynamicChoiceSourceKind.ProfileType
            ?: return unavailable(DynamicConfigurationChoiceUnavailableReason.SOURCE_UNAVAILABLE)
        val repositoryId = source.repositoryId
            ?: return unavailable(DynamicConfigurationChoiceUnavailableReason.SOURCE_UNAVAILABLE)
        val identity = ProfileTypeIdentity(repositoryId, source.typeId)
        if (!profileRepository.isTypePublished(identity)) {
            return unavailable(DynamicConfigurationChoiceUnavailableReason.SOURCE_UNAVAILABLE)
        }
        val choices = profileRepository.profilesForType(identity)
            .asSequence()
            .filter { it.availability == ProfileAvailability.AVAILABLE }
            .map { DynamicConfigurationChoice(it.profileId.value, it.displayName) }
            .toList()
        return DynamicConfigurationChoiceResolution.Available(choices)
    }

    private fun unavailable(
        reason: DynamicConfigurationChoiceUnavailableReason,
    ): DynamicConfigurationChoiceResolution =
        DynamicConfigurationChoiceResolution.Unavailable(reason)
}
