package io.talkcan.profile

import io.talkcan.dependency.GitHubRepositoryIdentity
import java.io.File
import kotlin.io.path.createTempDirectory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 3.4–3.7, 3.9, 3.10: Generic profile repository contract tests covering
 * create/edit/delete atomicity, required fields, bounds, schema
 * incompatibility, repository identity, foreign-repository denial, storage
 * corruption, and package removal/reinstall/update/rollback lifecycle.
 */
class ProfileRepositoryTest {

    private class Harness(val dir: File) {
        val secrets = FakeProtectedSecretStore()
        private var counter = 0
        val store = ProfileMetadataStore(dir)
        val repo = ProfileRepository(store, secrets, newId = { "id-${counter++}" })

        init {
            val loaded = repo.load()
            assertTrue("Repository must load: $loaded", loaded is ProfileOperationResult.Success)
        }

        fun publish(identity: ProfileTypeIdentity = TYPE_A, schema: ProfileSchema = openAiSchema()) {
            val result = repo.publishType(identity, schema)
            assertTrue("publishType must succeed: $result", result is ProfileOperationResult.Success)
        }

        fun create(
            identity: ProfileTypeIdentity = TYPE_A,
            name: String = "My Profile",
            payload: Map<String, ProfileScalarValue> = basePayload(),
            secrets: Map<String, CharSequence> = mapOf("api_key" to "sk-secret"),
        ): ProfileRecord {
            val result = repo.createProfile(identity, name, payload, secrets)
            val success = result as? ProfileOperationResult.Success
                ?: throw AssertionError("createProfile must succeed: $result")
            return success.value
        }
    }

    private fun newHarness(): Harness =
        Harness(createTempDirectory(prefix = "profile-repo-").toFile())

    /** Make the store root unusable so metadata writes fail deterministically. */
    private fun breakStoreRoot(dir: File) {
        dir.deleteRecursively()
        dir.createNewFile()
    }

    private fun secretToken(record: ProfileRecord, field: String = "api_key"): String =
        (record.secretReferences[field] as SecretReferenceState.Present).referenceId

    // ------------------------------------------------------------------
    // 3.4: Creation
    // ------------------------------------------------------------------

    @Test
    fun createCommitsStableProfileWithSecret() {
        val h = newHarness()
        h.publish()
        val record = h.create()

        assertEquals(1L, record.revision)
        assertEquals(ProfileAvailability.AVAILABLE, record.availability)
        assertEquals(TYPE_A, record.typeIdentity)
        assertEquals(basePayload(), record.scalarPayload)
        // Secret value is in protected storage under the persisted reference.
        val token = secretToken(record)
        assertTrue(h.secrets.contains(io.talkcan.secret.ProtectedSecretReference(token)))
        // No plaintext in the persisted record.
        assertEquals(1, record.secretReferences.size)
    }

    @Test
    fun createAssignsDistinctStableIds() {
        val h = newHarness()
        h.publish()
        val a = h.create(name = "A")
        val b = h.create(name = "B")
        assertNotEquals(a.profileId, b.profileId)
    }

    @Test
    fun createRequiresPublishedType() {
        val h = newHarness()
        val result = h.repo.createProfile(TYPE_A, "X", basePayload(), mapOf("api_key" to "s"))
        val failure = result as? ProfileOperationResult.Failure
        assertNotNull(failure)
        assertTrue(failure!!.failure is ProfileFailure.TypeNotPublished)
    }

    @Test
    fun createRejectsBlankDisplayName() {
        val h = newHarness()
        h.publish()
        val result = h.repo.createProfile(TYPE_A, "   ", basePayload(), mapOf("api_key" to "s"))
        assertTrue((result as ProfileOperationResult.Failure).failure is ProfileFailure.DisplayNameInvalid)
    }

    @Test
    fun createRejectsMissingRequiredSecret() {
        val h = newHarness()
        h.publish()
        val result = h.repo.createProfile(TYPE_A, "X", basePayload(), emptyMap())
        assertTrue((result as ProfileOperationResult.Failure).failure is ProfileFailure.ValidationFailed)
    }

    @Test
    fun createRejectsUnknownSecretField() {
        val h = newHarness()
        h.publish()
        val result = h.repo.createProfile(TYPE_A, "X", basePayload(), mapOf("bogus" to "s"))
        assertTrue((result as ProfileOperationResult.Failure).failure is ProfileFailure.ValidationFailed)
    }

    @Test
    fun createPersistsAcrossRestart() {
        val h = newHarness()
        h.publish()
        val record = h.create()

        val restarted = ProfileRepository(ProfileMetadataStore(h.dir), FakeProtectedSecretStore())
        restarted.load()
        val loaded = restarted.profile(record.profileId)
        assertEquals(record, loaded)
    }

    // ------------------------------------------------------------------
    // 3.9: Atomicity
    // ------------------------------------------------------------------

    @Test
    fun createRollsBackSecretWhenMetadataFails() {
        val h = newHarness()
        h.publish()
        breakStoreRoot(h.dir)

        val result = h.repo.createProfile(TYPE_A, "X", basePayload(), mapOf("api_key" to "s"))
        assertTrue(result is ProfileOperationResult.Failure)
        // No protected secret survived the rolled-back operation.
        assertTrue("Prepared secret must be rolled back", h.secrets.active.isEmpty())
        assertTrue(h.repo.profiles().isEmpty())
    }

    @Test
    fun createFailsCleanlyWhenSecretPrepareFails() {
        val h = newHarness()
        h.publish()
        h.secrets.failCreate = true

        val result = h.repo.createProfile(TYPE_A, "X", basePayload(), mapOf("api_key" to "s"))
        val failure = result as? ProfileOperationResult.Failure
        assertTrue(failure!!.failure is ProfileFailure.SecretMutationFailed)
        assertTrue(h.repo.profiles().isEmpty())
        assertTrue(h.secrets.active.isEmpty())
    }

    @Test
    fun editRollsBackSecretWhenMetadataFails() {
        val h = newHarness()
        h.publish()
        val record = h.create()
        val oldToken = secretToken(record)

        breakStoreRoot(h.dir)
        val result = h.repo.editProfile(
            record.profileId,
            secretEdits = mapOf("api_key" to SecretEditAction.Replace("sk-new")),
        )
        assertTrue(result is ProfileOperationResult.Failure)
        // Old profile preserved in memory; old secret still active; new secret rolled back.
        assertEquals(record, h.repo.profile(record.profileId))
        assertTrue(h.secrets.contains(io.talkcan.secret.ProtectedSecretReference(oldToken)))
        assertEquals(1, h.secrets.active.size)
    }

    @Test
    fun editValidationFailureRollsBackPreparedSecret() {
        val h = newHarness()
        h.publish()
        val record = h.create()
        val oldToken = secretToken(record)

        // Replace secret (prepares a new value) but supply an invalid scalar so
        // validation fails AFTER preparation; the new value must be rolled back.
        val result = h.repo.editProfile(
            record.profileId,
            scalarPayload = mapOf("base_url" to ProfileScalarValue.IntegerValue(5)),
            secretEdits = mapOf("api_key" to SecretEditAction.Replace("sk-new")),
        )
        assertTrue(result is ProfileOperationResult.Failure)
        assertEquals(record, h.repo.profile(record.profileId))
        assertTrue(h.secrets.contains(io.talkcan.secret.ProtectedSecretReference(oldToken)))
        assertEquals(1, h.secrets.active.size)
    }

    // ------------------------------------------------------------------
    // 3.5: Editing
    // ------------------------------------------------------------------

    @Test
    fun editAdvancesRevisionAndPreservesScalars() {
        val h = newHarness()
        h.publish()
        val record = h.create()

        val edited = (h.repo.editProfile(record.profileId, displayName = "Renamed")
            as ProfileOperationResult.Success).value
        assertEquals(2L, edited.revision)
        assertEquals("Renamed", edited.displayName)
        assertEquals(record.scalarPayload, edited.scalarPayload)
        assertEquals(record.secretReferences, edited.secretReferences)
    }

    @Test
    fun editReplaceSecretRetiresOldReference() {
        val h = newHarness()
        h.publish()
        val record = h.create()
        val oldToken = secretToken(record)

        val edited = (h.repo.editProfile(
            record.profileId,
            secretEdits = mapOf("api_key" to SecretEditAction.Replace("sk-new")),
        ) as ProfileOperationResult.Success).value

        val newToken = secretToken(edited)
        assertNotEquals(oldToken, newToken)
        // New value active, old value retired on commit.
        assertTrue(h.secrets.contains(io.talkcan.secret.ProtectedSecretReference(newToken)))
        assertFalse(h.secrets.contains(io.talkcan.secret.ProtectedSecretReference(oldToken)))
        assertTrue(h.secrets.retiredOnCommit.contains(oldToken))
    }

    @Test
    fun editRetainKeepsSameReference() {
        val h = newHarness()
        h.publish()
        val record = h.create()
        val edited = (h.repo.editProfile(
            record.profileId,
            displayName = "Renamed",
            secretEdits = mapOf("api_key" to SecretEditAction.Retain),
        ) as ProfileOperationResult.Success).value
        assertEquals(record.secretReferences, edited.secretReferences)
        assertEquals(2L, edited.revision)
    }

    @Test
    fun editClearOptionalSecretRemovesReference() {
        val h = newHarness()
        h.publish(schema = openAiSchema(apiKeyRequired = false))
        val record = h.create(secrets = mapOf("api_key" to "sk-secret"))
        val token = secretToken(record)

        val edited = (h.repo.editProfile(
            record.profileId,
            secretEdits = mapOf("api_key" to SecretEditAction.Clear),
        ) as ProfileOperationResult.Success).value
        assertEquals(SecretReferenceState.Absent, edited.secretReferences["api_key"])
        assertFalse(h.secrets.contains(io.talkcan.secret.ProtectedSecretReference(token)))
    }

    @Test
    fun editClearRequiredSecretFailsValidation() {
        val h = newHarness()
        h.publish()
        val record = h.create()
        val result = h.repo.editProfile(
            record.profileId,
            secretEdits = mapOf("api_key" to SecretEditAction.Clear),
        )
        assertTrue((result as ProfileOperationResult.Failure).failure is ProfileFailure.ValidationFailed)
    }

    @Test
    fun editReplaceFailsWhenPrepareFails() {
        val h = newHarness()
        h.publish()
        val record = h.create()
        val oldToken = secretToken(record)
        h.secrets.failReplace = true

        val result = h.repo.editProfile(
            record.profileId,
            secretEdits = mapOf("api_key" to SecretEditAction.Replace("sk-new")),
        )
        assertTrue((result as ProfileOperationResult.Failure).failure is ProfileFailure.SecretMutationFailed)
        assertEquals(record, h.repo.profile(record.profileId))
        assertTrue(h.secrets.contains(io.talkcan.secret.ProtectedSecretReference(oldToken)))
    }

    @Test
    fun editUnknownProfileFails() {
        val h = newHarness()
        h.publish()
        val result = h.repo.editProfile(ProfileId("nope"), displayName = "X")
        assertTrue((result as ProfileOperationResult.Failure).failure is ProfileFailure.ProfileNotFound)
    }

    @Test
    fun editRequiresPublishedType() {
        val h = newHarness()
        h.publish()
        val record = h.create()
        h.repo.unpublishType(TYPE_A)
        val result = h.repo.editProfile(record.profileId, displayName = "X")
        assertTrue((result as ProfileOperationResult.Failure).failure is ProfileFailure.TypeNotPublished)
    }

    // ------------------------------------------------------------------
    // 3.6: Deletion
    // ------------------------------------------------------------------

    @Test
    fun deleteRemovesProfileAndSecret() {
        val h = newHarness()
        h.publish()
        val record = h.create()
        val token = secretToken(record)

        val result = h.repo.deleteProfile(record.profileId) as ProfileOperationResult.Success
        assertTrue(result.value.isEmpty())
        assertNull(h.repo.profile(record.profileId))
        assertFalse(h.secrets.contains(io.talkcan.secret.ProtectedSecretReference(token)))
    }

    @Test
    fun deleteNonexistentSucceeds() {
        val h = newHarness()
        h.publish()
        val result = h.repo.deleteProfile(ProfileId("nope"))
        assertTrue(result is ProfileOperationResult.Success)
    }

    @Test
    fun deleteReportsCleanupPendingAndRetries() {
        val h = newHarness()
        h.publish()
        val record = h.create()
        val token = secretToken(record)
        h.secrets.failDelete = true

        val result = h.repo.deleteProfile(record.profileId) as ProfileOperationResult.Success
        assertNull(h.repo.profile(record.profileId))
        assertEquals(1, result.value.size)
        assertEquals(token, result.value[0].referenceToken)
        // Secret still present because the protected delete failed.
        assertTrue(h.secrets.contains(io.talkcan.secret.ProtectedSecretReference(token)))

        // Retry succeeds once protected storage recovers.
        h.secrets.failDelete = false
        val retry = h.repo.retrySecretCleanup(result.value[0])
        assertTrue(retry is ProfileOperationResult.Success)
        assertFalse(h.secrets.contains(io.talkcan.secret.ProtectedSecretReference(token)))
    }

    // ------------------------------------------------------------------
    // 3.9: Bounds, required fields, corruption
    // ------------------------------------------------------------------

    @Test
    fun createRejectsOverlongDisplayName() {
        val h = newHarness()
        h.publish()
        val longName = "x".repeat(ProfileLimits.MAX_DISPLAY_NAME_BYTES + 1)
        val result = h.repo.createProfile(TYPE_A, longName, basePayload(), mapOf("api_key" to "s"))
        assertTrue((result as ProfileOperationResult.Failure).failure is ProfileFailure.DisplayNameInvalid)
    }

    @Test
    fun createEnforcesPerTypeProfileBound() {
        val h = newHarness()
        h.publish(schema = openAiSchema(apiKeyRequired = false))
        repeat(ProfileLimits.MAX_PROFILES_PER_TYPE) { i ->
            val result = h.repo.createProfile(TYPE_A, "P$i", basePayload(), emptyMap())
            assertTrue("Create #$i must succeed: $result", result is ProfileOperationResult.Success)
        }
        val overflow = h.repo.createProfile(TYPE_A, "overflow", basePayload(), emptyMap())
        assertTrue((overflow as ProfileOperationResult.Failure).failure is ProfileFailure.BoundsExceeded)
    }

    @Test
    fun loadIsolatesCorruptRepository() {
        val h = newHarness()
        h.publish()
        h.create()
        h.repo.publishType(TYPE_B, openAiSchema())
        h.repo.createProfile(TYPE_B, "B profile", basePayload(), mapOf("api_key" to "s"))

        // Corrupt repository A's document on disk, then reload fresh.
        File(h.dir, "${REPO_A.value}.json").writeText("{ corrupt ")
        val reloaded = ProfileRepository(ProfileMetadataStore(h.dir), FakeProtectedSecretStore())
        reloaded.load()

        // Repository A isolated; repository B still loads.
        assertTrue(reloaded.profilesForType(TYPE_A).isEmpty())
        assertEquals(1, reloaded.profilesForType(TYPE_B).size)
    }

    // ------------------------------------------------------------------
    // 3.7 / 3.10: Lifecycle — update, rollback, removal, reinstall
    // ------------------------------------------------------------------

    @Test
    fun updateIncompatibleSchemaPreservesPayloadAndMarksUnavailable() {
        val h = newHarness()
        h.publish()
        val record = h.create()

        val incompatible = openAiSchema(
            extraFields = listOf(ProfileFieldDeclaration.StringField("org_id", true, null, null)),
        )
        val result = (h.repo.publishType(TYPE_A, incompatible) as ProfileOperationResult.Success).value
        assertEquals(1, result.incompatibleCount)
        assertEquals(0, result.availableCount)

        val revalidated = h.repo.profile(record.profileId)!!
        assertEquals(ProfileAvailability.UNAVAILABLE_SCHEMA_INCOMPATIBLE, revalidated.availability)
        // Payload, revision, and identity preserved unchanged.
        assertEquals(record.scalarPayload, revalidated.scalarPayload)
        assertEquals(record.secretReferences, revalidated.secretReferences)
        assertEquals(record.revision, revalidated.revision)
        assertEquals(record.profileId, revalidated.profileId)
    }

    @Test
    fun rollbackRestoresCompatibilityAndIdentity() {
        val h = newHarness()
        h.publish()
        val record = h.create()

        val incompatible = openAiSchema(
            extraFields = listOf(ProfileFieldDeclaration.StringField("org_id", true, null, null)),
        )
        h.repo.publishType(TYPE_A, incompatible)
        assertEquals(
            ProfileAvailability.UNAVAILABLE_SCHEMA_INCOMPATIBLE,
            h.repo.profile(record.profileId)!!.availability,
        )

        // Rollback restores the original schema; the profile becomes available again.
        h.repo.publishType(TYPE_A, openAiSchema())
        val restored = h.repo.profile(record.profileId)!!
        assertEquals(ProfileAvailability.AVAILABLE, restored.availability)
        assertEquals(record.profileId, restored.profileId)
        assertEquals(record.scalarPayload, restored.scalarPayload)
    }

    @Test
    fun packageRemovalUnpublishesButPreservesRecordsAndSecrets() {
        val h = newHarness()
        h.publish()
        val record = h.create()
        val token = secretToken(record)

        val removed = (h.repo.unpublishRepository(REPO_A) as ProfileOperationResult.Success).value
        assertEquals(listOf(record.profileId), removed)

        assertFalse(h.repo.isTypePublished(TYPE_A))
        val preserved = h.repo.profile(record.profileId)!!
        assertEquals(ProfileAvailability.UNAVAILABLE_PACKAGE_REMOVED, preserved.availability)
        // Records and protected references are preserved, not deleted.
        assertEquals(record.scalarPayload, preserved.scalarPayload)
        assertTrue(h.secrets.contains(io.talkcan.secret.ProtectedSecretReference(token)))
    }

    @Test
    fun reinstallRepublishesAndRestoresSameProfileIdentity() {
        val h = newHarness()
        h.publish()
        val record = h.create()

        h.repo.unpublishRepository(REPO_A)
        assertEquals(
            ProfileAvailability.UNAVAILABLE_PACKAGE_REMOVED,
            h.repo.profile(record.profileId)!!.availability,
        )

        // Same-repository reinstall revalidates against the original schema.
        h.repo.publishType(TYPE_A, openAiSchema())
        val reinstalled = h.repo.profile(record.profileId)!!
        assertEquals(ProfileAvailability.AVAILABLE, reinstalled.availability)
        assertEquals(record.profileId, reinstalled.profileId)
    }

    @Test
    fun removedPackageProfilesNeverReassignedToAnotherRepository() {
        val h = newHarness()
        h.publish()
        val record = h.create()

        h.repo.unpublishRepository(REPO_A)
        // A different repository publishes the same local type id.
        h.repo.publishType(TYPE_B, openAiSchema())

        // Repository A's profile is NOT exposed under repository B's type.
        assertTrue(h.repo.profilesForType(TYPE_B).isEmpty())
        val preserved = h.repo.profile(record.profileId)!!
        assertEquals(TYPE_A, preserved.typeIdentity)
        assertEquals(ProfileAvailability.UNAVAILABLE_PACKAGE_REMOVED, preserved.availability)
    }

    @Test
    fun foreignRepositoryCannotAccessAnotherRepositorysProfiles() {
        val h = newHarness()
        h.publish()
        h.create()
        // Publishing repository B (same local type id) must not surface A's profiles.
        h.repo.publishType(TYPE_B, openAiSchema())
        assertTrue(h.repo.profilesForType(TYPE_B).isEmpty())
        assertEquals(1, h.repo.profilesForType(TYPE_A).size)
    }

    @Test
    fun repositoryRenamePreservesIdentityViaDatabaseId() {
        // Identity is (databaseId, localTypeId); coordinates are not part of it.
        val before = ProfileTypeIdentity(GitHubRepositoryIdentity("123456"), "openai_compatible")
        val afterRename = ProfileTypeIdentity(GitHubRepositoryIdentity("123456"), "openai_compatible")
        assertEquals(before, afterRename)
        assertNotEquals(before, ProfileTypeIdentity(GitHubRepositoryIdentity("999999"), "openai_compatible"))

        // A profile stays accessible when the declaring repository republishes
        // under the same database id after a rename.
        val h = newHarness()
        h.publish()
        val record = h.create()
        h.repo.publishType(ProfileTypeIdentity(GitHubRepositoryIdentity("123456"), "openai_compatible"), openAiSchema())
        assertEquals(record.profileId, h.repo.profile(record.profileId)!!.profileId)
        assertEquals(ProfileAvailability.AVAILABLE, h.repo.profile(record.profileId)!!.availability)
    }

    // ------------------------------------------------------------------
    // 3.8 integration: shared selection + targeted invalidation
    // ------------------------------------------------------------------

    @Test
    fun sharedProfileEditInvalidatesOnlyDependentInstances() {
        val h = newHarness()
        h.publish()
        val profile = h.create()

        val index = ProfileDependencyIndex()
        index.registerDependency(profile.profileId, "instance-a")
        index.registerDependency(profile.profileId, "instance-b")

        // Unrelated profile has no dependents.
        val unrelated = h.create(name = "Other")

        val edited = (h.repo.editProfile(profile.profileId, displayName = "Changed")
            as ProfileOperationResult.Success).value
        val affected = index.affectedInstances(listOf(edited.profileId))
        assertEquals(setOf("instance-a", "instance-b"), affected)
        // Editing the unrelated profile affects nothing.
        assertTrue(index.affectedInstances(listOf(unrelated.profileId)).isEmpty())
    }
}
