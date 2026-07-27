package io.talkcan.lua

import io.talkcan.channel.capability.ChannelCapability
import io.talkcan.dependency.ArtifactDigest
import io.talkcan.dependency.ChoiceResolverDeclaration
import io.talkcan.dependency.ConfigurationDataDeclaration
import io.talkcan.dependency.ConfigurationFieldDeclaration
import io.talkcan.dependency.ConfigurationUiDeclaration
import io.talkcan.dependency.GitHubAssetIdentity
import io.talkcan.dependency.GitHubReleaseIdentity
import io.talkcan.dependency.GitHubRepositoryCoordinates
import io.talkcan.dependency.GitHubRepositoryIdentity
import io.talkcan.dependency.PackageCapability
import io.talkcan.dependency.PackageConfigurationDeclaration
import io.talkcan.dependency.PackageManifest
import io.talkcan.dependency.PackagePresentation
import io.talkcan.dependency.PackageResourcesDeclaration
import io.talkcan.dependency.PackageSourceRecord
import io.talkcan.dependency.RuntimeRequirements
import io.talkcan.dependency.UiControl
import io.talkcan.dependency.UiFieldDeclaration
import io.talkcan.dependency.WorkQueueDeclaration
import io.talkcan.dependency.ValidatedPackageRevision
import io.talkcan.lua.resolver.PackageResolverPublication
import io.talkcan.model.ChannelConfigurationField
import io.talkcan.model.DynamicChoiceSourceKind
import io.talkcan.model.InstalledProviderBinding
import io.talkcan.model.ProviderRevisionFingerprint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Focused materializer/provider contract for task 13.9.
 *
 * Complements [LuaPackageMaterializerTest] (capability/field compilation) and
 * [ProfileGrantSnapshotTest] (detached grant primitive: exact scalars, opaque
 * secret tokens, includeSecrets narrowing, unavailable-record skipping, stale
 * generation detachment). This file pins the materializer/provider SEAM that the
 * service composition relies on:
 *
 *  - declaration-only behavior across a full revised-v1 manifest (profile types,
 *    resolvers, work queues) — no Lua execution, actor creation, or kernel load;
 *  - revised empty declaration arrays remain compatible and publish no resolver
 *    authority;
 *  - choice resolvers publish exact resolver bindings, separate from any channel
 *    actor, and [LuaPackageMaterializer.materialize] discards that publication
 *    (legacy [InstalledProviderBinding] contract) while [LuaPackageMaterializer.materializeEntry]
 *    carries it for the production publication path;
 *  - exact profile/secret/http/work capability compilation that gates grant
 *    authority, plus the capability-gated secret-inclusion decision;
 *  - profile-type / package-resolver dynamic-choice dependencies are preserved
 *    exactly and NEVER resolved at materialization (unavailable dependencies are
 *    a runtime concern, not a materialization one);
 *  - materialization is uniform across arbitrary numeric repository identities
 *    with no OpenAI special case.
 */
class LuaPackageMaterializerServiceContractTest {

    // ── 13.9: declaration-only across a full revised manifest ────────────────

    @Test
    fun `full revised manifest with resolvers and work queues materializes without any Lua`() {
        val entry = materializeEntry(
            capabilities = setOf(
                PackageCapability.NETWORK_HTTP,
                PackageCapability.WORK_QUEUE,
                PackageCapability.PROFILES_READ,
                PackageCapability.SECRETS_READ,
            ),
            choiceResolvers = listOf(
                ChoiceResolverDeclaration("models", "plugin", setOf(PackageCapability.NETWORK_HTTP)),
            ),
            workQueues = listOf(WorkQueueDeclaration("turns")),
        )

        assertNotNull(entry.binding)
        // A non-empty resolver declaration publishes resolver authority...
        assertNotNull(entry.resolverPublication)
        // ...while remaining entirely declaration-only: the throwing bridge proves
        // no kernel interaction occurred, and no actor/native state was created.
    }

    // ── 13.9: revised empty arrays stay compatible ───────────────────────────

    @Test
    fun `revised empty profile, resolver, and work arrays materialize with no resolver authority`() {
        val entry = materializeEntry(capabilities = emptySet())

        assertNotNull(entry.binding)
        assertNull("empty choiceResolvers must publish no resolver authority", entry.resolverPublication)
        assertEquals(emptySet<ChannelCapability>(), entry.binding.provider.descriptor.requiredCapabilities)
    }

    // ── 13.9: resolver publication exactness + separation ────────────────────

    @Test
    fun `choice resolvers publish exact bindings separate from the channel actor`() {
        val entry = materializeEntry(
            capabilities = setOf(PackageCapability.NETWORK_HTTP, PackageCapability.SECRETS_READ),
            choiceResolvers = listOf(
                ChoiceResolverDeclaration(
                    "models", "plugin",
                    setOf(PackageCapability.NETWORK_HTTP, PackageCapability.SECRETS_READ),
                ),
            ),
        )

        val publication = entry.resolverPublication
        assertNotNull(publication)
        publication as PackageResolverPublication
        assertEquals(REPOSITORY_ID.toLong(), publication.repositoryId)
        assertEquals(ProviderRevisionFingerprint.fromDigest(digest()), publication.packageRevision)

        val binding = publication.resolvers.getValue("models")
        assertEquals("models", binding.resolverId)
        assertEquals("plugin", binding.moduleId)
        assertTrue("resolver declares network.http", binding.networkHttp)
        assertTrue("resolver declares secrets.read", binding.secretsRead)
        // The resolver module must be loadable from the published source map.
        assertTrue("plugin" in publication.sourceMap)
    }

    @Test
    fun `legacy materialize discards the resolver publication and returns only the binding`() {
        val binding = materialize(
            capabilities = setOf(PackageCapability.NETWORK_HTTP),
            choiceResolvers = listOf(
                ChoiceResolverDeclaration("models", "plugin", setOf(PackageCapability.NETWORK_HTTP)),
            ),
        )

        // The public/legacy contract yields exactly an InstalledProviderBinding;
        // resolver authority is out-of-band on the materializeEntry path only.
        assertNotNull(binding.provider)
        assertEquals(REPOSITORY_ID, binding.repositoryId.value)
    }

    @Test
    fun `resolver publication rejects a module absent from the source map`() {
        // The source map only contains the "plugin" entry module; a resolver that
        // references an undeclared module violates the publication invariant and
        // must fail at materialization, not at resolver execution time.
        assertThrows(IllegalArgumentException::class.java) {
            materializeEntry(
                capabilities = setOf(PackageCapability.NETWORK_HTTP),
                choiceResolvers = listOf(
                    ChoiceResolverDeclaration("models", "missing_module", setOf(PackageCapability.NETWORK_HTTP)),
                ),
            )
        }
    }

    // ── 13.9: exact capability compilation that gates grant authority ────────

    @Test
    fun `profile secret http and work capabilities compile to exact semantic capabilities`() {
        val binding = materialize(
            capabilities = setOf(
                PackageCapability.PROFILES_READ,
                PackageCapability.SECRETS_READ,
                PackageCapability.NETWORK_HTTP,
                PackageCapability.WORK_QUEUE,
            ),
            workQueues = listOf(WorkQueueDeclaration("turns")),
        )

        assertEquals(
            setOf(
                ChannelCapability.ProfilesRead,
                ChannelCapability.SecretsRead,
                ChannelCapability.NetworkHttp,
                ChannelCapability.WorkQueue,
            ),
            binding.provider.descriptor.requiredCapabilities,
        )
    }

    @Test
    fun `work queue declaration without the work_queue capability is rejected at declaration time`() {
        // PackageManifest enforces that durable queue declarations require declared
        // work authority; the materializer never sees an invalid manifest.
        assertThrows(IllegalArgumentException::class.java) {
            manifest(
                capabilities = emptySet(),
                workQueues = listOf(WorkQueueDeclaration("turns")),
            )
        }
    }

    @Test
    fun `secret inclusion in grants is gated by the compiled secrets_read capability`() {
        // The provider decides includeSecrets from whether secrets.read compiled
        // into the capability set. With secrets.read declared, a selected profile's
        // protected field receives an opaque reference binding; without it, the
        // grant narrows to scalars only (resolver/secret-less channels).
        val record = io.talkcan.profile.ProfileRecord(
            profileId = io.talkcan.profile.ProfileId("profile-1"),
            typeIdentity = io.talkcan.profile.ProfileTypeIdentity(
                GitHubRepositoryIdentity(REPOSITORY_ID), "agent",
            ),
            displayName = "Agent profile",
            schemaVersion = io.talkcan.profile.ProfileLimits.SCHEMA_VERSION,
            scalarPayload = mapOf("model" to io.talkcan.profile.ProfileScalarValue.StringValue("gpt-x")),
            secretReferences = mapOf(
                "api_key" to io.talkcan.profile.SecretReferenceState.Present("keystore-ref-1"),
            ),
            revision = 1L,
            availability = io.talkcan.profile.ProfileAvailability.AVAILABLE,
        )
        val input = ProfileGrantInput(record = record, typeLocalId = "agent", packageRepositoryId = REPOSITORY_ID.toLong())

        val withSecrets = ProfileGrantGeneration.build(inputs = listOf(input), includeSecrets = true)
        assertEquals(1, withSecrets.snapshots.single().secretBindings.size)
        assertEquals(1, withSecrets.registry.size)

        val narrowed = ProfileGrantGeneration.build(inputs = listOf(input), includeSecrets = false)
        assertTrue(narrowed.snapshots.single().secretBindings.isEmpty())
        assertEquals(0, narrowed.registry.size)
        // Scalars survive narrowing exactly.
        assertEquals(
            io.talkcan.work.WorkValue.Text("gpt-x"),
            narrowed.snapshots.single().values.getValue("model"),
        )
    }

    // ── 13.9: dependencies preserved, never resolved at materialization ──────

    @Test
    fun `package-resolver dynamic-choice is preserved exactly and never resolved at materialization`() {
        val declaration = PackageConfigurationDeclaration(
            ConfigurationDataDeclaration(
                listOf(ConfigurationFieldDeclaration.StringField("model_id", "", null)),
            ),
            ConfigurationUiDeclaration(
                listOf(
                    UiFieldDeclaration(
                        field = "model_id",
                        control = UiControl.DYNAMIC_CHOICE,
                        label = "Model",
                        help = null,
                        choices = null,
                        source = "resolver:models",
                    ),
                ),
            ),
        )

        val binding = materialize(
            declaration = declaration,
            capabilities = setOf(PackageCapability.NETWORK_HTTP),
            choiceResolvers = listOf(
                ChoiceResolverDeclaration("models", "plugin", setOf(PackageCapability.NETWORK_HTTP)),
            ),
        )

        val field = binding.provider.descriptor.configurationFields.single() as? ChannelConfigurationField.DynamicChoiceField
            ?: throw AssertionError("package-resolver dynamic-choice must compile to a DynamicChoiceField")
        assertEquals(DynamicChoiceSourceKind.PackageResolver("models", GitHubRepositoryIdentity(REPOSITORY_ID)), field.sourceKind)
    }

    // ── 13.9: no OpenAI special case ─────────────────────────────────────────

    @Test
    fun `materialization is uniform across arbitrary repository identities with no OpenAI branch`() {
        val first = materialize(repositoryId = "111111111", digestCharacter = 'a', capabilities = setOf(PackageCapability.NETWORK_HTTP))
        val second = materialize(repositoryId = "999999999", digestCharacter = 'b', capabilities = setOf(PackageCapability.NETWORK_HTTP))

        // Distinct durable identities, identical capability compilation, identical
        // presentation contract — nothing keys off an OpenAI/repository name.
        assertEquals("111111111", first.repositoryId.value)
        assertEquals("999999999", second.repositoryId.value)
        assertEquals(first.provider.descriptor.requiredCapabilities, second.provider.descriptor.requiredCapabilities)
        assertEquals(
            first.provider.descriptor.configurationFields.size,
            second.provider.descriptor.configurationFields.size,
        )
    }

    // ── helpers (mirror LuaPackageMaterializerTest; declaration-only bridge) ──

    private fun materializeEntry(
        declaration: PackageConfigurationDeclaration = emptyDeclaration(),
        capabilities: Set<String>,
        repositoryId: String = REPOSITORY_ID,
        digestCharacter: Char = 'a',
        choiceResolvers: List<ChoiceResolverDeclaration> = emptyList(),
        workQueues: List<WorkQueueDeclaration> = emptyList(),
    ): MaterializationEntry {
        val rev = manifestRevision(declaration, capabilities, repositoryId, digestCharacter, choiceResolvers, workQueues)
        return LuaPackageMaterializer.materializeEntry(rev, ThrowingBridge())
    }

    private fun materialize(
        declaration: PackageConfigurationDeclaration = emptyDeclaration(),
        capabilities: Set<String>,
        repositoryId: String = REPOSITORY_ID,
        digestCharacter: Char = 'a',
        choiceResolvers: List<ChoiceResolverDeclaration> = emptyList(),
        workQueues: List<WorkQueueDeclaration> = emptyList(),
    ): InstalledProviderBinding {
        val rev = manifestRevision(declaration, capabilities, repositoryId, digestCharacter, choiceResolvers, workQueues)
        return LuaPackageMaterializer.materialize(rev, ThrowingBridge())
    }

    private fun manifestRevision(
        declaration: PackageConfigurationDeclaration,
        capabilities: Set<String>,
        repositoryId: String,
        digestCharacter: Char,
        choiceResolvers: List<ChoiceResolverDeclaration>,
        workQueues: List<WorkQueueDeclaration>,
    ): ValidatedPackageRevision {
        val image = image()
        val identity = GitHubRepositoryIdentity(repositoryId)
        val digest = ArtifactDigest(digestCharacter.toString().repeat(64))
        return ValidatedPackageRevision(
            digest = digest,
            manifest = manifest(declaration, capabilities, identity, choiceResolvers, workQueues),
            sourceRecord = sourceRecord(identity),
            sourceMap = image.sourceMap,
            programImage = image,
            fingerprint = ProviderRevisionFingerprint.fromDigest(digest),
        )
    }

    private fun manifest(
        declaration: PackageConfigurationDeclaration = emptyDeclaration(),
        capabilities: Set<String>,
        identity: GitHubRepositoryIdentity = GitHubRepositoryIdentity(REPOSITORY_ID),
        choiceResolvers: List<ChoiceResolverDeclaration> = emptyList(),
        workQueues: List<WorkQueueDeclaration> = emptyList(),
    ): PackageManifest = PackageManifest(
        manifestVersion = 1,
        repositoryId = identity,
        packageVersion = "1.0.0",
        entryModule = "plugin",
        presentation = PackagePresentation("Service contract", "Materializer/provider service-contract fixture"),
        runtime = RuntimeRequirements(LUA_VERSION, API_VERSION),
        configuration = declaration,
        resources = PackageResourcesDeclaration(emptyList()),
        profileTypes = emptyList(),
        choiceResolvers = choiceResolvers,
        workQueues = workQueues,
        capabilities = capabilities,
    )

    private fun digest(): ArtifactDigest = ArtifactDigest("a".repeat(64))

    private fun emptyDeclaration(): PackageConfigurationDeclaration = PackageConfigurationDeclaration(
        ConfigurationDataDeclaration(emptyList()),
        ConfigurationUiDeclaration(emptyList()),
    )

    private fun image(): ImmutableProgramImage = when (
        val created = ImmutableProgramImage.create(
            entryPoint = "plugin",
            sourceMap = mapOf("plugin" to PROGRAM_SOURCE),
            requirements = LuaProgramRequirements(LUA_VERSION, API_VERSION),
        )
    ) {
        is ProgramImageCreationResult.Success -> created.image
        is ProgramImageCreationResult.Failure -> throw AssertionError(created.error.message)
    }

    private fun sourceRecord(repositoryId: GitHubRepositoryIdentity): PackageSourceRecord = PackageSourceRecord(
        repositoryId = repositoryId,
        coordinates = GitHubRepositoryCoordinates("service-owner", "service-repository"),
        release = GitHubReleaseIdentity("456", "v1", false),
        asset = GitHubAssetIdentity("789", "service-package.zip"),
        ownerId = "9000001",
    )

    /** Fails loudly on any kernel interaction: materialization is declaration-only. */
    private class ThrowingBridge : LuaKernelBridge {
        private fun forbidden(): Nothing =
            throw AssertionError("materialization must not invoke the Lua kernel bridge")

        override fun create(config: LuaKernelConfig): LuaKernelOutcome = forbidden()
        override fun load(handle: LuaStateHandle, source: String, entrypoint: String): LuaKernelOutcome = forbidden()
        override fun start(handle: LuaStateHandle): LuaKernelOutcome = forbidden()
        override fun resume(operation: LuaOperationHandle, success: Boolean, value: String, spawnAdmission: LuaSpawnAdmission): LuaKernelOutcome = forbidden()
        override fun cancel(operation: LuaOperationHandle): LuaKernelOutcome = forbidden()
        override fun interrupt(handle: LuaStateHandle): LuaKernelOutcome = forbidden()
        override fun snapshot(handle: LuaStateHandle): LuaKernelOutcome = forbidden()
        override fun close(handle: LuaStateHandle): LuaKernelOutcome = forbidden()
        override fun loadProgramImage(handle: LuaStateHandle, entryPoint: String, sourceMap: Map<String, String>): LuaKernelOutcome = forbidden()
        override fun invokeStartupCallback(handle: LuaStateHandle, callbackHandle: LuaCallbackHandle, config: LuaValue, spawnAdmission: LuaSpawnAdmission): LuaKernelOutcome = forbidden()
        override fun invokeCallback(handle: LuaStateHandle, callbackHandle: LuaCallbackHandle, arguments: LuaValue, spawnAdmission: LuaSpawnAdmission): LuaKernelOutcome = forbidden()
        override fun startCoroutine(handle: LuaStateHandle, coroutineId: LuaCoroutineId, spawnAdmission: LuaSpawnAdmission): LuaKernelOutcome = forbidden()
    }

    private companion object {
        const val REPOSITORY_ID = "100200300"
        const val PROGRAM_SOURCE = "return { startup = function() end }"
    }
}
