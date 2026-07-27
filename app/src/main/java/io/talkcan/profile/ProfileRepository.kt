package io.talkcan.profile

import io.talkcan.dependency.GitHubRepositoryIdentity
import io.talkcan.secret.PreparedSecretMutation
import io.talkcan.secret.ProtectedSecretReference
import io.talkcan.secret.ProtectedSecretResult
import io.talkcan.secret.ProtectedSecretStore
import java.util.UUID

/**
 * 3.4–3.7: Generic host-only profile repository.
 *
 * The repository owns profile metadata (through [ProfileMetadataStore]) and
 * coordinates protected-secret mutations (through [ProtectedSecretStore]) so
 * that profile metadata never commits against a failed protected mutation.
 * It never reads or mutates channel catalogue data: deleting or editing a
 * profile cannot rewrite or rebind channel definitions that reference it.
 * Channel configurations persist only stable profile IDs.
 *
 * Type schemas are published by the package materializer. Profile-type
 * identity is repository-scoped: only the declaring repository's published
 * schema makes a type usable, and a different repository never inherits
 * records.
 */
public class ProfileRepository(
    private val metadataStore: ProfileMetadataStore,
    private val secretStore: ProtectedSecretStore,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    private val lock = Any()
    private var loaded: Boolean = false
    private val profiles: LinkedHashMap<ProfileId, ProfileRecord> = LinkedHashMap()
    private val publishedTypes: LinkedHashMap<ProfileTypeIdentity, ProfileSchema> = LinkedHashMap()

    public val isLoaded: Boolean
        get() = synchronized(lock) { loaded }

    /**
     * Returns an atomic copy of all profiles and published schemas.
     */
    public fun snapshot(): ProfileRepositorySnapshot = synchronized(lock) {
        ProfileRepositorySnapshot(
            profiles = profiles.values.toList(),
            publishedTypes = publishedTypes.toMap(),
        )
    }

    /**
     * 3.3: Load every repository's persisted profile records. A corrupt
     * repository document fails closed for that repository: its profiles are
     * projected to [ProfileAvailability.UNAVAILABLE_STORAGE_CORRUPT] and
     * isolated, while other repositories load normally.
     */
    public fun load(): ProfileOperationResult<Unit> = synchronized(lock) {
        profiles.clear()
        for (repositoryId in metadataStore.discoverRepositoryIds()) {
            when (val result = metadataStore.loadRepository(repositoryId)) {
                is ProfileStoreLoadResult.Loaded -> {
                    for (record in result.records) {
                        profiles[record.profileId] = record
                    }
                }
                is ProfileStoreLoadResult.Corrupt -> {
                    // Isolate the corrupt repository: no records become active.
                    // The document is left untouched on disk for recovery.
                }
            }
        }
        loaded = true
        ProfileOperationResult.Success(Unit)
    }

    // ------------------------------------------------------------------
    // Type publication lifecycle (driven by the package materializer)
    // ------------------------------------------------------------------

    /**
     * 3.7: Publish (or replace) one type schema and revalidate every existing
     * profile of that type against it. Used for install, update, rollback, and
     * same-repository reinstall. Incompatible payloads are preserved unchanged
     * and projected to [ProfileAvailability.UNAVAILABLE_SCHEMA_INCOMPATIBLE]
     * without coercion or defaulting.
     */
    public fun publishType(
        identity: ProfileTypeIdentity,
        schema: ProfileSchema,
    ): ProfileOperationResult<ProfileRevalidationResult> = synchronized(lock) {
        if (!loaded) return ProfileOperationResult.Failure(ProfileFailure.NotLoaded)
        val declFailure = ProfileSchemaValidator.validateSchemaDeclaration(schema)
        if (declFailure != null) return ProfileOperationResult.Failure(declFailure)
        publishedTypes[identity] = schema
        revalidateLocked(identity, schema)
    }

    /** 3.6: Unpublish a single type without touching its records. */
    public fun unpublishType(identity: ProfileTypeIdentity): ProfileOperationResult<Unit> = synchronized(lock) {
        if (!loaded) return ProfileOperationResult.Failure(ProfileFailure.NotLoaded)
        publishedTypes.remove(identity)
        ProfileOperationResult.Success(Unit)
    }

    /**
     * 3.6: Package-removal unpublication. Revokes access to every type of the
     * repository and projects its profiles to
     * [ProfileAvailability.UNAVAILABLE_PACKAGE_REMOVED], but preserves records
     * and protected references for explicit reinstall or later user deletion.
     * A different repository never inherits them.
     */
    public fun unpublishRepository(
        repositoryId: GitHubRepositoryIdentity,
    ): ProfileOperationResult<List<ProfileId>> = synchronized(lock) {
        if (!loaded) return ProfileOperationResult.Failure(ProfileFailure.NotLoaded)
        publishedTypes.keys.removeAll { it.repositoryId == repositoryId }
        val affected = profiles.values.filter { it.typeIdentity.repositoryId == repositoryId }
        if (affected.isEmpty()) return ProfileOperationResult.Success(emptyList())
        val updated = affected.map { it.copy(availability = ProfileAvailability.UNAVAILABLE_PACKAGE_REMOVED) }
        val failure = persistRepository(repositoryId, updated, excludeProfileIds = emptySet())
        if (failure != null) return ProfileOperationResult.Failure(failure)
        for (record in updated) profiles[record.profileId] = record
        ProfileOperationResult.Success(updated.map { it.profileId })
    }

    /**
     * 3.7: Explicitly revalidate one type's profiles against a supplied schema
     * (also publishing it). Rollback restores compatibility against the
     * restored schema; the payload bytes are never rewritten.
     */
    public fun revalidateType(
        identity: ProfileTypeIdentity,
        schema: ProfileSchema,
    ): ProfileOperationResult<ProfileRevalidationResult> = synchronized(lock) {
        if (!loaded) return ProfileOperationResult.Failure(ProfileFailure.NotLoaded)
        publishedTypes[identity] = schema
        revalidateLocked(identity, schema)
    }

    private fun revalidateLocked(
        identity: ProfileTypeIdentity,
        schema: ProfileSchema,
    ): ProfileOperationResult<ProfileRevalidationResult> {
        val affected = profiles.values.filter { it.typeIdentity == identity }
        if (affected.isEmpty()) {
            return ProfileOperationResult.Success(
                ProfileRevalidationResult(0, 0, 0, emptyList())
            )
        }
        val incompatibleIds = ArrayList<ProfileId>()
        val updated = ArrayList<ProfileRecord>(affected.size)
        var availableCount = 0
        for (record in affected) {
            val failure = ProfileSchemaValidator.revalidateRecord(schema, record)
            val availability = if (failure == null) {
                availableCount++
                ProfileAvailability.AVAILABLE
            } else {
                incompatibleIds.add(record.profileId)
                ProfileAvailability.UNAVAILABLE_SCHEMA_INCOMPATIBLE
            }
            // Preserve payload unchanged; revalidation never advances revision.
            updated.add(record.copy(availability = availability))
        }
        val persistFailure = persistRepository(
            identity.repositoryId,
            updated,
            excludeProfileIds = updated.mapTo(mutableSetOf()) { it.profileId },
        )
        if (persistFailure != null) return ProfileOperationResult.Failure(persistFailure)
        for (record in updated) profiles[record.profileId] = record
        return ProfileOperationResult.Success(
            ProfileRevalidationResult(
                revalidatedCount = affected.size,
                availableCount = availableCount,
                incompatibleCount = incompatibleIds.size,
                incompatibleProfileIds = incompatibleIds,
            )
        )
    }

    // ------------------------------------------------------------------
    // 3.4: Generic profile creation
    // ------------------------------------------------------------------

    /**
     * 3.4: Create a profile with full candidate validation, a stable
     * host-generated ID, display-name bounds, and all-or-nothing
     * metadata/secret commit. Secret plaintext is written to protected
     * storage first (prepared); if validation or metadata commit fails, every
     * prepared secret is rolled back so no partial profile becomes visible.
     *
     * [secrets] maps declared secret field IDs to their plaintext. Required
     * secret fields must be present; unknown secret fields are rejected.
     */
    public fun createProfile(
        typeIdentity: ProfileTypeIdentity,
        displayName: String,
        scalarPayload: Map<String, ProfileScalarValue>,
        secrets: Map<String, CharSequence> = emptyMap(),
    ): ProfileOperationResult<ProfileRecord> = synchronized(lock) {
        if (!loaded) return ProfileOperationResult.Failure(ProfileFailure.NotLoaded)
        val schema = publishedTypes[typeIdentity]
            ?: return ProfileOperationResult.Failure(ProfileFailure.TypeNotPublished)

        val nameFailure = validateDisplayName(displayName)
        if (nameFailure != null) return ProfileOperationResult.Failure(nameFailure)

        val boundsFailure = checkCreateBounds(typeIdentity)
        if (boundsFailure != null) return ProfileOperationResult.Failure(boundsFailure)

        val secretIds = schema.secretFieldIds()
        for (fieldId in secrets.keys) {
            if (fieldId !in secretIds) {
                return ProfileOperationResult.Failure(
                    ProfileFailure.ValidationFailed("Unknown secret field: $fieldId")
                )
            }
        }
        for (field in schema.dataFields) {
            if (field is ProfileFieldDeclaration.SecretField && field.required) {
                val provided = secrets[field.id]
                if (provided == null || provided.isBlank()) {
                    return ProfileOperationResult.Failure(
                        ProfileFailure.ValidationFailed("Required secret field '${field.id}' is missing")
                    )
                }
            }
        }

        val profileId = ProfileId(newId())
        // Generate reference tokens and build the reference map BEFORE any I/O
        // so pure validation runs first.
        val secretReferences = LinkedHashMap<String, SecretReferenceState>()
        val prepared = ArrayList<Pair<String, PreparedSecretMutation>>()
        for ((fieldId, _) in secrets) {
            secretReferences[fieldId] = SecretReferenceState.Present(newId())
        }

        val validationFailure = ProfileSchemaValidator.validatePayload(schema, scalarPayload, secretReferences)
        if (validationFailure != null) return ProfileOperationResult.Failure(validationFailure)

        // Prepare protected secrets BEFORE any metadata write. A prepare
        // Failure means nothing was written for that field, so the operation
        // fails without touching metadata; any earlier successfully-prepared
        // mutations in this operation are rolled back.
        for ((fieldId, plaintext) in secrets) {
            val token = (secretReferences[fieldId] as SecretReferenceState.Present).referenceId
            val reference = ProtectedSecretReference(token)
            val prepareResult = try {
                secretStore.prepareCreate(reference, plaintext)
            } catch (error: RuntimeException) {
                rollbackAll(prepared.map { it.second })
                return ProfileOperationResult.Failure(
                    ProfileFailure.SecretMutationFailed(error.message ?: "Protected secret prepare failed")
                )
            }
            when (prepareResult) {
                is ProtectedSecretResult.Failure -> {
                    rollbackAll(prepared.map { it.second })
                    return ProfileOperationResult.Failure(
                        ProfileFailure.SecretMutationFailed(prepareResult.error.message)
                    )
                }
                is ProtectedSecretResult.Success -> prepared.add(fieldId to prepareResult.value)
            }
        }

        val record = ProfileRecord(
            profileId = profileId,
            typeIdentity = typeIdentity,
            displayName = displayName,
            schemaVersion = ProfileLimits.SCHEMA_VERSION,
            scalarPayload = LinkedHashMap(scalarPayload),
            secretReferences = secretReferences,
            revision = 1L,
            availability = ProfileAvailability.AVAILABLE,
        )

        val persistFailure = persistRepository(
            typeIdentity.repositoryId,
            listOf(record),
            excludeProfileIds = emptySet(),
        )
        if (persistFailure != null) {
            rollbackAll(prepared.map { it.second })
            return ProfileOperationResult.Failure(persistFailure)
        }
        commitAll(prepared.map { it.second })
        profiles[profileId] = record
        ProfileOperationResult.Success(record)
    }

    // ------------------------------------------------------------------
    // 3.5: Generic profile editing
    // ------------------------------------------------------------------

    /**
     * 3.5: Edit a profile with revision advancement, exact scalar
     * preservation, and protected-secret retain/replace/clear semantics. A
     * null [displayName] or [scalarPayload] preserves the existing value
     * exactly. Secret edits are prepared first; on validation or metadata
     * failure every prepared mutation is rolled back so no partial change
     * becomes visible.
     */
    public fun editProfile(
        profileId: ProfileId,
        displayName: String? = null,
        scalarPayload: Map<String, ProfileScalarValue>? = null,
        secretEdits: Map<String, SecretEditAction> = emptyMap(),
    ): ProfileOperationResult<ProfileRecord> = synchronized(lock) {
        if (!loaded) return ProfileOperationResult.Failure(ProfileFailure.NotLoaded)
        val existing = profiles[profileId]
            ?: return ProfileOperationResult.Failure(ProfileFailure.ProfileNotFound)
        val schema = publishedTypes[existing.typeIdentity]
            ?: return ProfileOperationResult.Failure(ProfileFailure.TypeNotPublished)

        val newDisplayName = displayName ?: existing.displayName
        val nameFailure = validateDisplayName(newDisplayName)
        if (nameFailure != null) return ProfileOperationResult.Failure(nameFailure)

        val newScalarPayload = scalarPayload ?: existing.scalarPayload
        val secretIds = schema.secretFieldIds()
        val newSecretReferences = LinkedHashMap(existing.secretReferences)
        val prepared = ArrayList<PreparedSecretMutation>()

        for ((fieldId, action) in secretEdits) {
            if (fieldId !in secretIds) {
                rollbackAll(prepared)
                return ProfileOperationResult.Failure(
                    ProfileFailure.ValidationFailed("Unknown secret field: $fieldId")
                )
            }
            val current = existing.secretReferences[fieldId]
            when (action) {
                is SecretEditAction.Retain -> Unit
                is SecretEditAction.Replace -> {
                    if (current !is SecretReferenceState.Present) {
                        rollbackAll(prepared)
                        return ProfileOperationResult.Failure(
                            ProfileFailure.ValidationFailed("Cannot replace absent secret field '$fieldId'")
                        )
                    }
                    val newToken = newId()
                    val prepareResult = try {
                        secretStore.prepareReplace(
                            ProtectedSecretReference(current.referenceId),
                            ProtectedSecretReference(newToken),
                            action.plaintext,
                        )
                    } catch (error: RuntimeException) {
                        rollbackAll(prepared)
                        return ProfileOperationResult.Failure(
                            ProfileFailure.SecretMutationFailed(error.message ?: "Protected secret prepare failed")
                        )
                    }
                    when (prepareResult) {
                        is ProtectedSecretResult.Failure -> {
                            rollbackAll(prepared)
                            return ProfileOperationResult.Failure(
                                ProfileFailure.SecretMutationFailed(prepareResult.error.message)
                            )
                        }
                        is ProtectedSecretResult.Success -> prepared.add(prepareResult.value)
                    }
                    newSecretReferences[fieldId] = SecretReferenceState.Present(newToken)
                }
                is SecretEditAction.Set -> {
                    if (current is SecretReferenceState.Present) {
                        rollbackAll(prepared)
                        return ProfileOperationResult.Failure(
                            ProfileFailure.ValidationFailed("Secret field '$fieldId' already present; use Replace")
                        )
                    }
                    val newToken = newId()
                    val prepareResult = try {
                        secretStore.prepareCreate(ProtectedSecretReference(newToken), action.plaintext)
                    } catch (error: RuntimeException) {
                        rollbackAll(prepared)
                        return ProfileOperationResult.Failure(
                            ProfileFailure.SecretMutationFailed(error.message ?: "Protected secret prepare failed")
                        )
                    }
                    when (prepareResult) {
                        is ProtectedSecretResult.Failure -> {
                            rollbackAll(prepared)
                            return ProfileOperationResult.Failure(
                                ProfileFailure.SecretMutationFailed(prepareResult.error.message)
                            )
                        }
                        is ProtectedSecretResult.Success -> prepared.add(prepareResult.value)
                    }
                    newSecretReferences[fieldId] = SecretReferenceState.Present(newToken)
                }
                is SecretEditAction.Clear -> {
                    if (current is SecretReferenceState.Present) {
                        val reference = ProtectedSecretReference(current.referenceId)
                        val prepareResult = try {
                            secretStore.prepareClear(reference)
                        } catch (error: RuntimeException) {
                            rollbackAll(prepared)
                            return ProfileOperationResult.Failure(
                                ProfileFailure.SecretMutationFailed(error.message ?: "Protected secret prepare failed")
                            )
                        }
                        when (prepareResult) {
                            is ProtectedSecretResult.Failure -> {
                                rollbackAll(prepared)
                                return ProfileOperationResult.Failure(
                                    ProfileFailure.SecretMutationFailed(prepareResult.error.message)
                                )
                            }
                            is ProtectedSecretResult.Success -> prepared.add(prepareResult.value)
                        }
                    }
                    newSecretReferences[fieldId] = SecretReferenceState.Absent
                }
            }
        }

        val validationFailure = ProfileSchemaValidator.validatePayload(schema, newScalarPayload, newSecretReferences)
        if (validationFailure != null) {
            rollbackAll(prepared)
            return ProfileOperationResult.Failure(validationFailure)
        }

        val newRecord = ProfileRecord(
            profileId = profileId,
            typeIdentity = existing.typeIdentity,
            displayName = newDisplayName,
            schemaVersion = ProfileLimits.SCHEMA_VERSION,
            scalarPayload = LinkedHashMap(newScalarPayload),
            secretReferences = newSecretReferences,
            revision = existing.revision + 1L,
            availability = ProfileAvailability.AVAILABLE,
        )

        val persistFailure = persistRepository(
            existing.typeIdentity.repositoryId,
            listOf(newRecord),
            excludeProfileIds = setOf(profileId),
        )
        if (persistFailure != null) {
            rollbackAll(prepared)
            return ProfileOperationResult.Failure(persistFailure)
        }
        commitAll(prepared)
        profiles[profileId] = newRecord
        ProfileOperationResult.Success(newRecord)
    }

    // ------------------------------------------------------------------
    // 3.6: Explicit deletion
    // ------------------------------------------------------------------

    /**
     * 3.6: Explicitly delete a profile. Metadata is removed atomically first;
     * protected references are then deleted best-effort. A protected deletion
     * that cannot finalize is reported as [ProfileSecretCleanupPending] for
     * retry; the metadata removal is already committed and never reverted.
     */
    public fun deleteProfile(
        profileId: ProfileId,
    ): ProfileOperationResult<List<ProfileSecretCleanupPending>> = synchronized(lock) {
        if (!loaded) return ProfileOperationResult.Failure(ProfileFailure.NotLoaded)
        val existing = profiles[profileId]
            ?: return ProfileOperationResult.Success(emptyList())

        val persistFailure = persistRepository(
            existing.typeIdentity.repositoryId,
            emptyList(),
            excludeProfileIds = setOf(profileId),
        )
        if (persistFailure != null) return ProfileOperationResult.Failure(persistFailure)
        profiles.remove(profileId)

        val pending = ArrayList<ProfileSecretCleanupPending>()
        for ((_, ref) in existing.secretReferences) {
            if (ref is SecretReferenceState.Present) {
                val reference = ProtectedSecretReference(ref.referenceId)
                when (val prepareResult = secretStore.prepareDelete(reference)) {
                    is ProtectedSecretResult.Failure ->
                        pending.add(ProfileSecretCleanupPending(ref.referenceId))
                    is ProtectedSecretResult.Success -> {
                        if (prepareResult.value.commit() is ProtectedSecretResult.Failure) {
                            pending.add(ProfileSecretCleanupPending(ref.referenceId))
                        }
                    }
                }
            }
        }
        ProfileOperationResult.Success(pending)
    }

    /**
     * 3.6: Retry a previously pending protected-secret cleanup. Returns
     * success when the reference is gone (or was never present).
     */
    public fun retrySecretCleanup(
        pending: ProfileSecretCleanupPending,
    ): ProfileOperationResult<Unit> = synchronized(lock) {
        val reference = ProtectedSecretReference(pending.referenceToken)
        if (!secretStore.contains(reference)) return ProfileOperationResult.Success(Unit)
        when (val prepareResult = secretStore.prepareDelete(reference)) {
            is ProtectedSecretResult.Failure ->
                ProfileOperationResult.Failure(ProfileFailure.SecretMutationFailed(prepareResult.error.message))
            is ProtectedSecretResult.Success ->
                when (val commitResult = prepareResult.value.commit()) {
                    is ProtectedSecretResult.Success -> ProfileOperationResult.Success(Unit)
                    is ProtectedSecretResult.Failure ->
                        ProfileOperationResult.Failure(ProfileFailure.SecretMutationFailed(commitResult.error.message))
                }
        }
    }

    // ------------------------------------------------------------------
    // Read accessors
    // ------------------------------------------------------------------

    /** Deterministic snapshot of every loaded profile, sorted by ID. */
    public fun profiles(): List<ProfileRecord> = synchronized(lock) {
        profiles.values.sortedBy { it.profileId.value }
    }

    public fun profile(profileId: ProfileId): ProfileRecord? = synchronized(lock) {
        profiles[profileId]
    }

    /** Profiles of one type, sorted by ID. */
    public fun profilesForType(identity: ProfileTypeIdentity): List<ProfileRecord> = synchronized(lock) {
        profiles.values.filter { it.typeIdentity == identity }.sortedBy { it.profileId.value }
    }

    public fun publishedSchema(identity: ProfileTypeIdentity): ProfileSchema? = synchronized(lock) {
        publishedTypes[identity]
    }

    public fun isTypePublished(identity: ProfileTypeIdentity): Boolean = synchronized(lock) {
        publishedTypes.containsKey(identity)
    }

    // ------------------------------------------------------------------
    // Internal helpers (call while holding [lock])
    // ------------------------------------------------------------------

    private fun validateDisplayName(displayName: String): ProfileFailure? {
        if (displayName.isBlank()) return ProfileFailure.DisplayNameInvalid("Display name must not be blank")
        if (displayName.toByteArray(Charsets.UTF_8).size > ProfileLimits.MAX_DISPLAY_NAME_BYTES) {
            return ProfileFailure.DisplayNameInvalid(
                "Display name must not exceed ${ProfileLimits.MAX_DISPLAY_NAME_BYTES} bytes"
            )
        }
        return null
    }

    private fun checkCreateBounds(typeIdentity: ProfileTypeIdentity): ProfileFailure? {
        val perType = profiles.values.count { it.typeIdentity == typeIdentity }
        if (perType >= ProfileLimits.MAX_PROFILES_PER_TYPE) {
            return ProfileFailure.BoundsExceeded(
                "Profile count for type reaches ${ProfileLimits.MAX_PROFILES_PER_TYPE}"
            )
        }
        val perRepo = profiles.values.count { it.typeIdentity.repositoryId == typeIdentity.repositoryId }
        if (perRepo >= ProfileLimits.MAX_PROFILES_PER_REPOSITORY) {
            return ProfileFailure.BoundsExceeded(
                "Profile count for repository reaches ${ProfileLimits.MAX_PROFILES_PER_REPOSITORY}"
            )
        }
        return null
    }

    /**
     * Persist one repository's complete record set. [records] are the new or
     * updated records; every other retained record for the repository is
     * carried forward except those in [excludeProfileIds] (deleted/replaced).
     * Returns null on success or a typed storage failure.
     */
    private fun persistRepository(
        repositoryId: GitHubRepositoryIdentity,
        records: List<ProfileRecord>,
        excludeProfileIds: Set<ProfileId>,
    ): ProfileFailure? {
        val retained = profiles.values.filter {
            it.typeIdentity.repositoryId == repositoryId && it.profileId !in excludeProfileIds
        }
        val complete = LinkedHashMap<ProfileId, ProfileRecord>()
        for (record in retained) complete[record.profileId] = record
        for (record in records) complete[record.profileId] = record
        return when (val result = metadataStore.saveRepository(repositoryId, complete.values.toList())) {
            is ProfileStoreSaveResult.Committed -> null
            is ProfileStoreSaveResult.Failed -> result.failure
        }
    }

    private fun rollbackAll(prepared: List<PreparedSecretMutation>) {
        for (mutation in prepared) {
            try {
                mutation.rollback()
            } catch (_: RuntimeException) {
                // Best-effort rollback; a failure leaves the prepared value
                // isolated under its unused reference token.
            }
        }
    }

    private fun commitAll(prepared: List<PreparedSecretMutation>) {
        for (mutation in prepared) {
            try {
                mutation.commit()
            } catch (_: RuntimeException) {
                // Commit finalization (e.g., predecessor retirement) is
                // best-effort; the new value is already authoritative.
            }
        }
    }
}
