package io.talkcan.service

import io.talkcan.dependency.ArtifactDigest
import io.talkcan.dependency.GitHubAssetIdentity
import io.talkcan.dependency.GitHubReleaseIdentity
import io.talkcan.dependency.GitHubRepositoryCoordinates
import io.talkcan.dependency.GitHubRepositoryIdentity
import io.talkcan.dependency.PackageCapability
import io.talkcan.dependency.PackageConfigurationDeclaration
import io.talkcan.dependency.ConfigurationDataDeclaration
import io.talkcan.dependency.ConfigurationUiDeclaration
import io.talkcan.dependency.PackageManifest
import io.talkcan.dependency.PackagePresentation
import io.talkcan.dependency.PackageResourcesDeclaration
import io.talkcan.dependency.PackageSourceRecord
import io.talkcan.dependency.ProfileTypeDeclaration
import io.talkcan.dependency.RuntimeRequirements
import io.talkcan.dependency.StoredPackageRevision
import io.talkcan.dependency.StoredProviderRecord
import io.talkcan.model.DynamicConfigurationChoice
import io.talkcan.model.DynamicConfigurationChoiceRequest
import io.talkcan.model.DynamicConfigurationChoiceResolution
import io.talkcan.model.DynamicConfigurationChoiceSourceId
import io.talkcan.model.DynamicConfigurationChoiceUnavailableReason
import io.talkcan.model.DynamicChoiceSourceKind
import io.talkcan.profile.FakeProtectedSecretStore
import io.talkcan.profile.ProfileAvailability
import io.talkcan.profile.ProfileFieldDeclaration
import io.talkcan.profile.ProfileFailure
import io.talkcan.profile.ProfileMetadataStore
import io.talkcan.profile.ProfileOperationResult
import io.talkcan.profile.ProfileRecord
import io.talkcan.profile.ProfileRepository
import io.talkcan.profile.ProfileScalarValue
import io.talkcan.profile.ProfileSchema
import io.talkcan.profile.ProfileTypeIdentity
import io.talkcan.profile.SecretEditAction
import io.talkcan.profile.SecretReferenceState
import io.talkcan.profile.basePayload
import io.talkcan.profile.openAiSchema
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.io.path.createTempDirectory

/**
 * 5.1–5.4 coverage for the generic profile-management coordinator:
 * generation-checked snapshot composition, published-type discovery from
 * installed manifests (no Lua), exact CRUD scalar/secret semantics,
 * protected-storage failure isolation, retained-profile unavailable states
 * on package removal, type removal, and incompatible schema updates, and
 * the guarantee that profile operations never mutate the installed index.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GenericProfileManagementCoordinatorTest {

    private val repositoryA = GitHubRepositoryIdentity("123456")
    private val typeA = ProfileTypeIdentity(repositoryA, "openai_compatible")

    private class FakePackagesView : InstalledPackagesView {
        private val _state = MutableStateFlow<InstalledPackagesState>(InstalledPackagesState.Loading(0L))
        override val state: StateFlow<InstalledPackagesState> get() = _state
        var packages: Map<GitHubRepositoryIdentity, StoredProviderRecord> = emptyMap()

        fun publishReady(generation: Long) {
            _state.value = InstalledPackagesState.Ready(generation, 1L)
        }

        override fun committedSnapshot(): Map<GitHubRepositoryIdentity, StoredProviderRecord> = packages
    }

    private class Context(
        val view: FakePackagesView,
        val secretStore: FakeProtectedSecretStore,
        val profileRepository: ProfileRepository,
        val coordinator: GenericProfileManagementCoordinator,
    ) {
        fun close() = coordinator.close()
    }

    private fun TestScope.context(): Context {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val view = FakePackagesView()
        val secretStore = FakeProtectedSecretStore()
        var seq = 0
        val profileRepository = ProfileRepository(
            ProfileMetadataStore(createTempDirectory("profile-ui-coordinator-").toFile()),
            secretStore,
            newId = { "ref-${seq++}" },
        )
        profileRepository.load()
        val coordinator = GenericProfileManagementCoordinator(view, profileRepository, this, dispatcher)
        return Context(view, secretStore, profileRepository, coordinator)
    }

    private fun typeDeclaration(schema: ProfileSchema = openAiSchema()) = ProfileTypeDeclaration(
        id = "openai_compatible",
        label = "OpenAI Compatible",
        help = null,
        schemaVersion = 1,
        schema = schema,
    )

    private fun packageRecord(
        repositoryId: GitHubRepositoryIdentity,
        types: List<ProfileTypeDeclaration>,
        capabilities: Set<String> = setOf(PackageCapability.SECRETS_READ),
    ): StoredProviderRecord {
        val sourceRecord = PackageSourceRecord(
            repositoryId = repositoryId,
            coordinates = GitHubRepositoryCoordinates("owner", "repo"),
            release = GitHubReleaseIdentity("1001", "v1.0", false),
            asset = GitHubAssetIdentity("2001", "talkcan-channel.zip"),
            ownerId = "4242",
        )
        val active = StoredPackageRevision(
            digest = ArtifactDigest("a".repeat(64)),
            manifest = PackageManifest(
                manifestVersion = 1,
                repositoryId = repositoryId,
                packageVersion = "1.0.0",
                entryModule = "plugin",
                presentation = PackagePresentation("Title", "Summary"),
                runtime = RuntimeRequirements("1.0", "1"),
                configuration = PackageConfigurationDeclaration(
                    ConfigurationDataDeclaration(emptyList()),
                    ConfigurationUiDeclaration(emptyList()),
                ),
                resources = PackageResourcesDeclaration(emptyList()),
                profileTypes = types,
                choiceResolvers = emptyList(),
                workQueues = emptyList(),
                capabilities = capabilities,
            ),
            sourceRecord = sourceRecord,
        )
        return StoredProviderRecord(active = active, rollback = null)
    }

    private fun Context.publishInstalled(vararg types: ProfileTypeDeclaration, generation: Long) {
        view.packages = mapOf(repositoryA to packageRecord(repositoryA, types.toList()))
        view.publishReady(generation)
    }

    private suspend fun Context.createWorkProfile(
        url: String = "https://api.example/v1",
        secret: String = "sk-first",
    ): ProfileRecord {
        val result = coordinator.createProfile(
            typeA,
            "Work",
            mapOf("base_url" to ProfileScalarValue.StringValue(url)),
            mapOf("api_key" to secret),
        )
        return (result as ProfileOperationResult.Success).value
    }

    @Test
    fun `published types are discovered from installed manifests only when ready`() = runTest {
        val ctx = context()
        try {
            ctx.coordinator.state.value.let { initial ->
                assertTrue(initial.publishedTypes.isEmpty())
                assertTrue(initial.profiles.isEmpty())
                assertEquals(-1L, initial.generation)
            }

            ctx.publishInstalled(typeDeclaration(), generation = 1L)
            advanceUntilIdle()

            val state = ctx.coordinator.state.value
            assertEquals(1L, state.generation)
            val type = state.publishedTypes.single()
            assertEquals(typeA, type.identity)
            assertEquals("OpenAI Compatible", type.label)
            assertEquals("owner", type.canonicalOwner)
            assertEquals("repo", type.canonicalRepository)
            assertTrue(type.hasSecretFields)
            assertTrue(type.declaresSecretsRead)
            assertFalse(type.declaresNetworkHttp)
            assertFalse(type.isSchemaIncompatible)
            assertTrue(state.profiles.isEmpty())

            // Publication was reconciled into the repository: creation succeeds.
            val created = ctx.coordinator.createProfile(
                typeA,
                "Work",
                basePayload(),
                mapOf("api_key" to "sk-secret"),
            )
            assertTrue(created is ProfileOperationResult.Success)
            assertEquals(1, ctx.coordinator.state.value.profiles.size)
        } finally {
            ctx.close()
        }
    }

    @Test
    fun `network capability is surfaced on discovered types`() = runTest {
        val ctx = context()
        try {
            ctx.view.packages = mapOf(
                repositoryA to packageRecord(
                    repositoryA,
                    listOf(typeDeclaration()),
                    capabilities = setOf(PackageCapability.SECRETS_READ, PackageCapability.NETWORK_HTTP),
                )
            )
            ctx.view.publishReady(1L)
            advanceUntilIdle()

            val type = ctx.coordinator.state.value.publishedTypes.single()
            assertTrue(type.declaresSecretsRead)
            assertTrue(type.declaresNetworkHttp)
        } finally {
            ctx.close()
        }
    }

    @Test
    fun `create edit and delete enforce exact scalar and secret semantics`() = runTest {
        val ctx = context()
        try {
            ctx.publishInstalled(typeDeclaration(openAiSchema(apiKeyRequired = false)), generation = 1L)
            advanceUntilIdle()

            val record = ctx.createWorkProfile(secret = "sk-first")
            assertEquals(1L, record.revision)
            assertEquals(1, ctx.secretStore.active.size)
            // Secret plaintext never enters published state.
            assertFalse(ctx.coordinator.state.value.toString().contains("sk-first"))
            val summary = ctx.coordinator.state.value.profiles.single()
            assertEquals("Work", summary.displayName)
            assertFalse(summary.isUnavailable)
            assertNull(summary.unavailableReason)

            // Replace: new reference committed, predecessor retired.
            val oldRef = (record.secretReferences["api_key"] as SecretReferenceState.Present).referenceId
            val edited = ctx.coordinator.editProfile(
                id = record.profileId,
                displayName = "Work 2",
                scalars = mapOf("base_url" to ProfileScalarValue.StringValue("https://two.example/v1")),
                secretEdits = mapOf("api_key" to SecretEditAction.Replace("sk-second")),
            )
            val editedRecord = (edited as ProfileOperationResult.Success).value
            assertEquals(2L, editedRecord.revision)
            assertEquals("Work 2", editedRecord.displayName)
            val newRef = (editedRecord.secretReferences["api_key"] as SecretReferenceState.Present).referenceId
            assertNotEquals(oldRef, newRef)
            assertEquals("sk-second", ctx.secretStore.active[newRef])
            assertFalse(ctx.secretStore.active.containsKey(oldRef))
            assertEquals(listOf(oldRef), ctx.secretStore.retiredOnCommit)
            assertFalse(ctx.coordinator.state.value.toString().contains("sk-second"))

            // Retain: null arguments preserve exact values, revision still advances.
            val retained = ctx.coordinator.editProfile(id = record.profileId)
            val retainedRecord = (retained as ProfileOperationResult.Success).value
            assertEquals(3L, retainedRecord.revision)
            assertEquals(editedRecord.scalarPayload, retainedRecord.scalarPayload)
            assertEquals(newRef, (retainedRecord.secretReferences["api_key"] as SecretReferenceState.Present).referenceId)

            // Clear: reference becomes Absent, ciphertext removed on commit.
            val cleared = ctx.coordinator.editProfile(
                id = record.profileId,
                secretEdits = mapOf("api_key" to SecretEditAction.Clear),
            )
            val clearedRecord = (cleared as ProfileOperationResult.Success).value
            assertEquals(4L, clearedRecord.revision)
            assertEquals(SecretReferenceState.Absent, clearedRecord.secretReferences["api_key"])
            assertTrue(ctx.secretStore.active.isEmpty())

            // Delete: metadata removed, protected references cleaned.
            val deleted = ctx.coordinator.deleteProfile(record.profileId)
            assertTrue(deleted is ProfileOperationResult.Success)
            assertTrue(ctx.coordinator.state.value.profiles.isEmpty())
            assertTrue(ctx.secretStore.active.isEmpty())

            // Profile operations never disturb package publication or the
            // installed index: the type stays published and the committed
            // snapshot is untouched.
            assertEquals(1, ctx.coordinator.state.value.publishedTypes.size)
            assertTrue(ctx.view.committedSnapshot().containsKey(repositoryA))
        } finally {
            ctx.close()
        }
    }

    @Test
    fun `protected storage failure is typed and commits nothing`() = runTest {
        val ctx = context()
        try {
            ctx.publishInstalled(typeDeclaration(), generation = 1L)
            advanceUntilIdle()

            ctx.secretStore.failCreate = true
            val result = ctx.coordinator.createProfile(
                typeA,
                "Work",
                basePayload(),
                mapOf("api_key" to "sk-x"),
            )
            val failure = result as ProfileOperationResult.Failure
            assertTrue(failure.failure is ProfileFailure.SecretMutationFailed)

            val state = ctx.coordinator.state.value
            assertTrue(state.profiles.isEmpty())
            assertFalse(state.isOperationActive)
            assertNotNull(state.failureMessage)
            assertTrue(state.failureMessage!!.startsWith("Protected secret storage failure"))
            assertTrue(state.completedOperations > 0L)
            // No partial protected value was retained.
            assertTrue(ctx.secretStore.active.isEmpty())

            // Recovery: the next successful operation clears the failure state.
            ctx.secretStore.failCreate = false
            val recovered = ctx.coordinator.createProfile(
                typeA,
                "Work",
                basePayload(),
                mapOf("api_key" to "sk-y"),
            )
            assertTrue(recovered is ProfileOperationResult.Success)
            assertNull(ctx.coordinator.state.value.failureMessage)
            assertEquals(1, ctx.coordinator.state.value.profiles.size)
        } finally {
            ctx.close()
        }
    }

    @Test
    fun `package removal retains profiles unavailable without rewriting records`() = runTest {
        val ctx = context()
        try {
            ctx.publishInstalled(typeDeclaration(), generation = 1L)
            advanceUntilIdle()
            val record = ctx.createWorkProfile()

            ctx.view.packages = emptyMap()
            ctx.view.publishReady(2L)
            advanceUntilIdle()

            val removed = ctx.coordinator.state.value
            assertTrue(removed.publishedTypes.isEmpty())
            val summary = removed.profiles.single()
            assertEquals(record.profileId, summary.id)
            assertTrue(summary.isUnavailable)
            assertEquals("Package removed", summary.unavailableReason)
            assertEquals(ProfileAvailability.UNAVAILABLE_PACKAGE_REMOVED, summary.record.availability)
            // The record itself is retained for reinstall or explicit deletion.
            assertNotNull(ctx.profileRepository.profile(record.profileId))

            // Access is revoked: creation is denied while unpublished.
            val denied = ctx.coordinator.createProfile(
                typeA,
                "Other",
                basePayload(),
                mapOf("api_key" to "sk-z"),
            )
            assertTrue((denied as ProfileOperationResult.Failure).failure is ProfileFailure.TypeNotPublished)

            // Same-repository reinstall restores availability without a new revision.
            ctx.publishInstalled(typeDeclaration(), generation = 3L)
            advanceUntilIdle()
            val restored = ctx.coordinator.state.value.profiles.single()
            assertEquals(record.profileId, restored.id)
            assertFalse(restored.isUnavailable)
            assertEquals(ProfileAvailability.AVAILABLE, restored.record.availability)
            assertEquals(1L, restored.record.revision)
            assertEquals(record.scalarPayload, restored.record.scalarPayload)
        } finally {
            ctx.close()
        }
    }

    @Test
    fun `type removed by update revokes access and preserves the record unchanged`() = runTest {
        val ctx = context()
        try {
            ctx.publishInstalled(typeDeclaration(), generation = 1L)
            advanceUntilIdle()
            val record = ctx.createWorkProfile()

            // Same repository, no profile types anymore.
            ctx.view.packages = mapOf(repositoryA to packageRecord(repositoryA, emptyList()))
            ctx.view.publishReady(2L)
            advanceUntilIdle()

            val state = ctx.coordinator.state.value
            assertTrue(state.publishedTypes.isEmpty())
            val summary = state.profiles.single()
            assertTrue(summary.isUnavailable)
            assertEquals("Type no longer published", summary.unavailableReason)
            // Record payload and availability bytes are untouched by projection.
            assertEquals(ProfileAvailability.AVAILABLE, summary.record.availability)
            assertEquals(record.scalarPayload, summary.record.scalarPayload)

            // Editing requires a published schema.
            val denied = ctx.coordinator.editProfile(record.profileId, displayName = "Changed")
            assertTrue((denied as ProfileOperationResult.Failure).failure is ProfileFailure.TypeNotPublished)

            // Deletion remains authorized for retained profiles.
            assertTrue(ctx.coordinator.deleteProfile(record.profileId) is ProfileOperationResult.Success)
            assertTrue(ctx.coordinator.state.value.profiles.isEmpty())
        } finally {
            ctx.close()
        }
    }

    @Test
    fun `incompatible schema update preserves payload and marks profile unavailable`() = runTest {
        val ctx = context()
        try {
            ctx.publishInstalled(typeDeclaration(), generation = 1L)
            advanceUntilIdle()
            val record = ctx.createWorkProfile()

            val incompatible = openAiSchema(
                extraFields = listOf(
                    ProfileFieldDeclaration.IntegerField("max_tokens", true, null, 1L, 4096L)
                ),
            )
            ctx.publishInstalled(typeDeclaration(incompatible), generation = 2L)
            advanceUntilIdle()

            val state = ctx.coordinator.state.value
            assertTrue(state.publishedTypes.single().isSchemaIncompatible)
            val summary = state.profiles.single()
            assertEquals(record.profileId, summary.id)
            assertTrue(summary.isUnavailable)
            assertEquals("Schema incompatible", summary.unavailableReason)
            // The exact payload is preserved, never coerced or defaulted.
            assertEquals(record.scalarPayload, summary.record.scalarPayload)
            assertEquals(1L, summary.record.revision)

            // Rollback to the original schema restores availability.
            ctx.publishInstalled(typeDeclaration(), generation = 3L)
            advanceUntilIdle()
            val restored = ctx.coordinator.state.value.profiles.single()
            assertFalse(restored.isUnavailable)
            assertFalse(ctx.coordinator.state.value.publishedTypes.single().isSchemaIncompatible)
        } finally {
            ctx.close()
        }
    }

    @Test
    fun `profile choice resolver returns only same-repository published profiles`() = runTest {
        val ctx = context()
        try {
            ctx.publishInstalled(typeDeclaration(), generation = 1L)
            advanceUntilIdle()
            val profile = ctx.createWorkProfile()
            val resolver = GenericProfileDynamicChoiceResolver(ctx.profileRepository)
            val source = DynamicConfigurationChoiceSourceId("profile:openai_compatible")

            assertEquals(
                DynamicConfigurationChoiceResolution.Available(
                    listOf(DynamicConfigurationChoice(profile.profileId.value, "Work")),
                ),
                resolver.resolve(
                    DynamicConfigurationChoiceRequest(
                        source = source,
                        sourceKind = DynamicChoiceSourceKind.ProfileType(
                            "openai_compatible",
                            repositoryA,
                        ),
                    ),
                ),
            )

            val unavailable = DynamicConfigurationChoiceResolution.Unavailable(
                DynamicConfigurationChoiceUnavailableReason.SOURCE_UNAVAILABLE,
            )
            assertEquals(
                unavailable,
                resolver.resolve(
                    DynamicConfigurationChoiceRequest(
                        source = source,
                        sourceKind = DynamicChoiceSourceKind.ProfileType(
                            "openai_compatible",
                            GitHubRepositoryIdentity("654321"),
                        ),
                    ),
                ),
            )
            assertEquals(
                unavailable,
                resolver.resolve(
                    DynamicConfigurationChoiceRequest(
                        source = source,
                        sourceKind = DynamicChoiceSourceKind.ProfileType("openai_compatible"),
                    ),
                ),
            )

            ctx.publishInstalled(generation = 2L)
            advanceUntilIdle()
            assertEquals(
                unavailable,
                resolver.resolve(
                    DynamicConfigurationChoiceRequest(
                        source = source,
                        sourceKind = DynamicChoiceSourceKind.ProfileType(
                            "openai_compatible",
                            repositoryA,
                        ),
                    ),
                ),
            )
        } finally {
            ctx.close()
        }
    }

    @Test
    fun `torn snapshot aborts publication`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val secretStore = FakeProtectedSecretStore()
        val profileRepository = ProfileRepository(
            ProfileMetadataStore(createTempDirectory("profile-ui-tear-").toFile()),
            secretStore,
        )
        profileRepository.load()

        val ready = InstalledPackagesState.Ready(1L, 1L)
        val snapshot = mapOf(repositoryA to packageRecord(repositoryA, listOf(typeDeclaration())))
        var torn = false
        val view = object : InstalledPackagesView {
            private val _state = MutableStateFlow<InstalledPackagesState>(ready)
            override val state: StateFlow<InstalledPackagesState> get() = _state

            override fun committedSnapshot(): Map<GitHubRepositoryIdentity, StoredProviderRecord> {
                if (!torn) {
                    torn = true
                    // A concurrent package operation lands mid-composition.
                    _state.value = InstalledPackagesState.Loading(2L)
                }
                return snapshot
            }
        }

        val coordinator = GenericProfileManagementCoordinator(view, profileRepository, this, dispatcher)
        try {
            advanceUntilIdle()

            // The torn composition must never publish; the repository must not
            // be reconciled from a torn read.
            val state = coordinator.state.value
            assertEquals(-1L, state.generation)
            assertTrue(state.publishedTypes.isEmpty())
            assertFalse(profileRepository.isTypePublished(typeA))
        } finally {
            coordinator.close()
        }
    }
}
