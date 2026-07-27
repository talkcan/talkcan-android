package io.talkcan.dependency

import io.talkcan.channel.capability.ChannelCapability
import io.talkcan.lua.API_VERSION
import io.talkcan.lua.LUA_VERSION
import io.talkcan.lua.LuaCallbackHandle
import io.talkcan.lua.LuaCoroutineId
import io.talkcan.lua.LuaKernelBridge
import io.talkcan.lua.LuaKernelConfig
import io.talkcan.lua.LuaKernelOutcome
import io.talkcan.lua.LuaOperationHandle
import io.talkcan.lua.LuaPackageMaterializer
import io.talkcan.lua.LuaSpawnAdmission
import io.talkcan.lua.LuaStateHandle
import io.talkcan.lua.LuaValue
import io.talkcan.model.ChannelConfigurationField
import io.talkcan.model.ChannelImplementationId
import io.talkcan.model.ChannelImplementationProviderRegistry
import io.talkcan.model.ChannelProviderResolution
import io.talkcan.model.DynamicChoiceSourceKind
import io.talkcan.model.DynamicConfigurationChoiceSourceId
import io.talkcan.model.InstalledProvidersPublicationResult
import io.talkcan.model.ProviderRevisionFingerprint
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Byte-pinned external OpenAI Agent package contract.
 *
 * The exact published v1.0.4 release artifact traverses the generic host
 * machinery only — validator → immutable store → materializer → provider
 * registry. The v1.0.4 manifest declares the full OpenAI declaration surface:
 * a same-repository `openai_compatible` profile type (scalar base_url plus a
 * secret api_key), a package-local `models` choice resolver, one durable
 * `turns` work queue, and seven configuration fields spanning dynamic-choice
 * (profile-type, package-resolver, and three-stage host keyboard hierarchy),
 * multiline, and toggle controls. There is no OpenAI-name dispatch, no
 * repository-identity special case, no automatic instance, no built-in
 * configuration cloning, and no Lua state, preparation, connection, secret,
 * profile, network, or output effect before a runtime is explicitly selected.
 *
 * The source-record release/asset IDs are the published v1.0.4 GitHub IDs
 * (release tag commit 6b5d23d2772023240dbdf336952eb957164a8c3e).
 */
class ExternalOpenAiAgentChannelContractTest {

    @Test
    fun `exact OpenAI archive validates identity capabilities profile resolver work queue dynamic choice and materializes without Lua state`() =
        withTemporaryDirectory { root ->
            // Exact published bytes.
            val artifact = fixture()
            assertEquals(ARTIFACT_SHA256, sha256(artifact))
            assertEquals(ARTIFACT_SIZE, artifact.size)

            // Canonical archive layout: exact entry set, order, and stored-entry metadata.
            ZipFile(File(root, "openai-fixture.zip").also { it.writeBytes(artifact) }).use { zip ->
                assertEquals(EXPECTED_ENTRIES, zip.entries().asSequence().map { it.name }.toList())
                for (name in EXPECTED_ENTRIES) {
                    val entry = zip.getEntry(name)
                    assertNotNull("canonical fixture must contain $name", entry)
                    assertEquals("canonical fixture entries must be stored uncompressed", ZipEntry.STORED, entry.method)
                    val bytes = zip.getInputStream(entry).readBytes()
                    assertEquals(bytes.size.toLong(), entry.size)
                    assertEquals(CRC32().apply { update(bytes) }.value, entry.crc)
                }
            }

            // Validate through the generic PackageValidator.
            val bridge = UnusedBridge()
            val revision = success(
                PackageValidator.validatePackage(
                    ByteArrayInputStream(artifact),
                    sourceRecord(),
                    File(root, "staging.zip"),
                ),
            )

            // Strict manifest identity and runtime.
            assertEquals(GitHubRepositoryIdentity(REPOSITORY_ID), revision.manifest.repositoryId)
            assertEquals(PACKAGE_VERSION, revision.manifest.packageVersion)
            assertEquals("plugin", revision.programImage.entryPoint)
            assertEquals(LUA_VERSION, revision.manifest.runtime.luaVersion)
            assertEquals(API_VERSION, revision.manifest.runtime.apiVersion)
            assertEquals("OpenAI Agent", revision.manifest.presentation.label)
            assertEquals(
                "OpenAI-compatible agent channel with tool calling and voice output",
                revision.manifest.presentation.summary,
            )

            // Source map, digest, and fingerprint.
            assertEquals(EXPECTED_SOURCE_MODULES, revision.sourceMap.keys)
            assertEquals(ARTIFACT_SHA256, revision.digest.value)
            assertEquals(ProviderRevisionFingerprint.fromDigest(revision.digest), revision.fingerprint)

            // Exact capabilities, in declared order.
            assertEquals(
                listOf(
                    PackageCapability.AUDIO_TRANSCRIPTION,
                    PackageCapability.AUDIO_SYNTHESIS,
                    PackageCapability.AUDIO_PLAYBACK,
                    PackageCapability.NETWORK_HTTP,
                    PackageCapability.PROFILES_READ,
                    PackageCapability.SECRETS_READ,
                    PackageCapability.WORK_QUEUE,
                    PackageCapability.KEYBOARD_OUTPUT,
                ),
                revision.manifest.capabilities.toList(),
            )

            // No mount declarations whatsoever.
            assertTrue(revision.manifest.resources.mounts.isEmpty())

            // Same-repository profile type: one scalar field plus one secret field.
            val profileType = revision.manifest.profileTypes.single()
            assertEquals("openai_compatible", profileType.id)
            assertEquals("OpenAI Compatible", profileType.label)
            assertEquals("Connection settings for any OpenAI-compatible API endpoint.", profileType.help)
            assertEquals(1, profileType.schemaVersion)
            assertEquals(setOf("base_url"), profileType.schema.scalarFieldIds())
            assertEquals(setOf("api_key"), profileType.schema.secretFieldIds())

            // Package-local model resolver bound to a same-package module and a
            // capability subset of the package's own declarations.
            val resolver = revision.manifest.choiceResolvers.single()
            assertEquals("models", resolver.id)
            assertEquals("openai.model_choices", resolver.module)
            assertEquals(
                setOf(
                    PackageCapability.NETWORK_HTTP,
                    PackageCapability.PROFILES_READ,
                    PackageCapability.SECRETS_READ,
                ),
                resolver.capabilities,
            )

            // One durable work queue; authority requires the declared work.queue capability.
            assertEquals(listOf("turns"), revision.manifest.workQueues.map { it.id })

            // Exact scalar data schema: seven fields, string scalars plus one boolean.
            val dataFields = revision.manifest.configuration.data.fields
            assertEquals(
                listOf(
                    "profile_id", "model", "system_prompt", "keyboard_enabled",
                    "host_os", "host_layout", "host_profile",
                ),
                dataFields.map { it.id },
            )
            for (index in listOf(0, 1, 2, 4, 5, 6)) {
                assertTrue(dataFields[index] is ConfigurationFieldDeclaration.StringField)
            }
            assertTrue(dataFields[3] is ConfigurationFieldDeclaration.BooleanField)
            assertEquals("You are a helpful assistant.", (dataFields[2] as ConfigurationFieldDeclaration.StringField).default)
            assertEquals(false, (dataFields[3] as ConfigurationFieldDeclaration.BooleanField).default)

            // Exact UI declaration: four dynamic-choice sources (profile-type,
            // package-resolver, and the three-stage host keyboard hierarchy), one
            // multiline, and one toggle.
            val uiFields = revision.manifest.configuration.ui.fields
            assertEquals(
                listOf(
                    "profile_id", "model", "system_prompt", "keyboard_enabled",
                    "host_os", "host_layout", "host_profile",
                ),
                uiFields.map { it.field },
            )
            assertEquals(
                listOf(
                    UiControl.DYNAMIC_CHOICE, UiControl.DYNAMIC_CHOICE, UiControl.MULTILINE, UiControl.TOGGLE,
                    UiControl.DYNAMIC_CHOICE, UiControl.DYNAMIC_CHOICE, UiControl.DYNAMIC_CHOICE,
                ),
                uiFields.map { it.control },
            )
            assertEquals("Connection Profile", uiFields[0].label)
            assertEquals(DynamicChoiceSource.PROFILE_PREFIX + "openai_compatible", uiFields[0].source)
            assertEquals(DynamicChoiceSourceReference.ProfileType("openai_compatible"), uiFields[0].sourceReference)
            assertNull(uiFields[0].dependsOnFieldId)
            assertEquals("Model", uiFields[1].label)
            assertEquals(DynamicChoiceSource.RESOLVER_PREFIX + "models", uiFields[1].source)
            assertEquals(DynamicChoiceSourceReference.PackageResolver("models"), uiFields[1].sourceReference)
            assertEquals("profile_id", uiFields[1].dependsOnFieldId)
            assertEquals("System Prompt", uiFields[2].label)
            assertNull(uiFields[2].sourceReference)
            assertEquals("Enable Keyboard Tools", uiFields[3].label)
            assertNull(uiFields[3].sourceReference)
            assertEquals("Keyboard Platform", uiFields[4].label)
            assertEquals(DynamicChoiceSource.KEYBOARD_OUTPUT_PLATFORMS, uiFields[4].source)
            assertEquals(DynamicChoiceSourceReference.Host(DynamicChoiceSource.KEYBOARD_OUTPUT_PLATFORMS), uiFields[4].sourceReference)
            assertNull(uiFields[4].dependsOnFieldId)
            assertEquals("Keyboard Layout", uiFields[5].label)
            assertEquals(DynamicChoiceSource.KEYBOARD_OUTPUT_LAYOUTS, uiFields[5].source)
            assertEquals("host_os", uiFields[5].dependsOnFieldId)
            assertEquals("Keyboard Profile", uiFields[6].label)
            assertEquals(DynamicChoiceSource.KEYBOARD_OUTPUT_PROFILES, uiFields[6].source)
            assertEquals("host_layout", uiFields[6].dependsOnFieldId)

            // Repository-derived identity only: not a built-in at all.
            val implementationId = InstalledProviderId.derive(revision.manifest.repositoryId)
            assertEquals("github-repository:$REPOSITORY_ID", implementationId.value)
            assertFalse(
                "An external OpenAI package must not become a built-in before publication.",
                implementationId.value.startsWith("builtin:"),
            )

            // Materialize through the generic materializer: descriptor only, no Lua entry.
            val binding = LuaPackageMaterializer.materialize(revision, bridge)
            assertEquals(revision.manifest.repositoryId, binding.repositoryId)
            assertEquals(revision.digest, binding.expectedDigest)
            assertEquals(ARTIFACT_SHA256, binding.provider.fingerprint.value)
            assertEquals(implementationId, binding.provider.descriptor.implementationId)
            assertEquals("OpenAI Agent", binding.provider.descriptor.presentation.label)
            assertEquals(
                "OpenAI-compatible agent channel with tool calling and voice output",
                binding.provider.descriptor.presentation.summary,
            )

            // Compiled capability set: every public ID maps to its channel-neutral
            // semantic requirement; no provider, credential, or OpenAI contract shape.
            assertEquals(
                setOf(
                    ChannelCapability.Transcription,
                    ChannelCapability.Synthesis,
                    ChannelCapability.AudioOperation,
                    ChannelCapability.DeferredAudioPlayback,
                    ChannelCapability.NetworkHttp,
                    ChannelCapability.ProfilesRead,
                    ChannelCapability.SecretsRead,
                    ChannelCapability.WorkQueue,
                    ChannelCapability.TextOutput,
                ),
                binding.provider.descriptor.requiredCapabilities,
            )
            assertTrue(binding.provider.descriptor.resourceDeclarations.mounts.isEmpty())

            // Compiled configuration schema is exactly the package-owned declaration;
            // nothing is cloned from any built-in.
            assertEquals(
                listOf<ChannelConfigurationField>(
                    ChannelConfigurationField.DynamicChoiceField(
                        id = "profile_id",
                        label = "Connection Profile",
                        source = DynamicConfigurationChoiceSourceId(DynamicChoiceSource.PROFILE_PREFIX + "openai_compatible"),
                        sourceKind = DynamicChoiceSourceKind.ProfileType(
                            "openai_compatible",
                            GitHubRepositoryIdentity(REPOSITORY_ID),
                        ),
                        help = "Select an OpenAI-compatible connection profile.",
                    ),
                    ChannelConfigurationField.DynamicChoiceField(
                        id = "model",
                        label = "Model",
                        source = DynamicConfigurationChoiceSourceId(DynamicChoiceSource.RESOLVER_PREFIX + "models"),
                        sourceKind = DynamicChoiceSourceKind.PackageResolver("models", GitHubRepositoryIdentity(REPOSITORY_ID)),
                        help = "Select a model from the connected endpoint.",
                        dependsOnFieldId = "profile_id",
                    ),
                    ChannelConfigurationField.TextField(
                        id = "system_prompt",
                        label = "System Prompt",
                        help = "Instructions sent as the system message at the start of every conversation.",
                        multiline = true,
                    ),
                    ChannelConfigurationField.BooleanField(
                        id = "keyboard_enabled",
                        label = "Enable Keyboard Tools",
                        help = "Allow the model to type text and press Enter on the controlled device.",
                    ),
                    ChannelConfigurationField.DynamicChoiceField(
                        id = "host_os",
                        label = "Keyboard Platform",
                        source = DynamicConfigurationChoiceSourceId(DynamicChoiceSource.KEYBOARD_OUTPUT_PLATFORMS),
                        help = "Target operating system for keyboard output.",
                    ),
                    ChannelConfigurationField.DynamicChoiceField(
                        id = "host_layout",
                        label = "Keyboard Layout",
                        source = DynamicConfigurationChoiceSourceId(DynamicChoiceSource.KEYBOARD_OUTPUT_LAYOUTS),
                        help = "Keyboard layout for the selected platform.",
                        dependsOnFieldId = "host_os",
                    ),
                    ChannelConfigurationField.DynamicChoiceField(
                        id = "host_profile",
                        label = "Keyboard Profile",
                        source = DynamicConfigurationChoiceSourceId.KEYBOARD_OUTPUT_PROFILES,
                        help = "Keyboard profile used for type_text and press_enter.",
                        dependsOnFieldId = "host_layout",
                    ),
                ),
                binding.provider.descriptor.configurationFields,
            )
            assertEquals(0, bridge.calls)
        }

    @Test
    fun `repository installation persists exact bytes reparses and republishes without Lua or automatic instance effects`() =
        runTest {
            withTemporaryDirectorySuspend { root ->
                val bridge = UnusedBridge()
                val providers = ChannelImplementationProviderRegistry()
                val publications = mutableListOf<MaterializationResult>()
                val repository = InstalledPackageRepository(
                    store = InstalledPackageStore(root),
                    bridge = bridge,
                    publisher = publicationSink(providers, publications),
                    dispatcher = StandardTestDispatcher(testScheduler),
                )

                val id = InstalledProviderId.derive(sourceRecord().repositoryId)
                assertEquals("github-repository:$REPOSITORY_ID", id.value)

                // Install through the generic repository transaction.
                assertEquals(
                    MutationResult.Installed(id),
                    success(repository.installOrUpdate(ByteArrayInputStream(fixture()), sourceRecord())),
                )

                // Exactly one repository-derived provider binding; no failures; no
                // instance/definition payload of any kind in the publication.
                assertEquals(1, publications.size)
                assertEquals(setOf<ChannelImplementationId>(id), publications.single().bindings.keys)
                assertTrue(publications.single().failures.isEmpty())
                val resolution = providers.resolve(id)
                assertTrue(resolution is ChannelProviderResolution.Available)
                assertEquals(
                    "OpenAI Agent",
                    (resolution as ChannelProviderResolution.Available).provider.descriptor.presentation.label,
                )
                // The registry published exactly the one package provider: no automatic
                // instance and no co-registered built-in beside it.
                assertEquals(listOf(id), providers.descriptors().map { it.implementationId })
                assertEquals(0, bridge.calls)

                // Exact bytes survive in the immutable content-addressed store.
                assertTrue(
                    "committed content must be byte-exact",
                    File(root, "content/sha256/$ARTIFACT_SHA256").readBytes().contentEquals(fixture()),
                )

                // The cached index retains the exact digest and the full OpenAI
                // declaration surface; a fresh install carries no rollback generation.
                val record = index(root).providers.getValue(sourceRecord().repositoryId)
                assertEquals(ARTIFACT_SHA256, record.active.digest.value)
                assertNull(record.rollback)
                val activeManifest = record.active.manifest
                assertEquals(
                    listOf(
                        PackageCapability.AUDIO_TRANSCRIPTION,
                        PackageCapability.AUDIO_SYNTHESIS,
                        PackageCapability.AUDIO_PLAYBACK,
                        PackageCapability.NETWORK_HTTP,
                        PackageCapability.PROFILES_READ,
                        PackageCapability.SECRETS_READ,
                        PackageCapability.WORK_QUEUE,
                        PackageCapability.KEYBOARD_OUTPUT,
                    ),
                    activeManifest.capabilities.toList(),
                )
                assertEquals("openai_compatible", activeManifest.profileTypes.single().id)
                assertEquals("models", activeManifest.choiceResolvers.single().id)
                assertEquals(listOf("turns"), activeManifest.workQueues.map { it.id })
                assertEquals(
                    listOf(
                        "profile_id", "model", "system_prompt", "keyboard_enabled",
                        "host_os", "host_layout", "host_profile",
                    ),
                    activeManifest.configuration.ui.fields.map { it.field },
                )

                // Reparse-from-artifact recovery: exact stored bytes revalidate into the
                // identical manifest, digest, and source map.
                val reparsed = success(InstalledPackageStore(root).revalidateStoredRevision(record.active))
                assertEquals(record.active.manifest, reparsed.manifest)
                assertEquals(ARTIFACT_SHA256, reparsed.digest.value)
                assertEquals(EXPECTED_SOURCE_MODULES, reparsed.sourceMap.keys)

                // Restart materialization from stored bytes: exact digest survives, still no Lua entry.
                val reloaded = success(InstalledPackageStore(root).loadAndMaterialize(bridge))
                assertEquals(ARTIFACT_SHA256, reloaded.bindings.getValue(id).provider.fingerprint.value)
                assertTrue(reloaded.failures.isEmpty())
                assertEquals(0, bridge.calls)

                // Restart publication reparses and republishes the same provider with zero effects.
                val restartBridge = UnusedBridge()
                val restarted = InstalledPackageRepository(
                    store = InstalledPackageStore(root),
                    bridge = restartBridge,
                    publisher = publicationSink(providers, publications),
                    dispatcher = StandardTestDispatcher(testScheduler),
                )
                repository.requestClose()
                success(restarted.loadAndPublish())
                assertTrue(providers.resolve(id) is ChannelProviderResolution.Available)
                assertEquals(setOf<ChannelImplementationId>(id), publications.last().bindings.keys)
                assertTrue(publications.last().failures.isEmpty())
                assertEquals(0, restartBridge.calls)

                restarted.requestClose()
            }
        }

    private fun fixture(): ByteArray =
        requireNotNull(javaClass.classLoader?.getResourceAsStream(RESOURCE_PATH)) {
            "Missing OpenAI channel fixture: $RESOURCE_PATH"
        }.use { it.readBytes() }

    private fun sourceRecord(): PackageSourceRecord = PackageSourceRecord(
        repositoryId = GitHubRepositoryIdentity(REPOSITORY_ID),
        coordinates = GitHubRepositoryCoordinates("talkcan-channels", "openai-agent"),
        release = GitHubReleaseIdentity(RELEASE_ID, RELEASE_TAG, false),
        asset = GitHubAssetIdentity(ASSET_ID, "talkcan-channel.zip"),
        ownerId = OFFICIAL_OWNER_ID,
    )

    private fun publicationSink(
        providers: ChannelImplementationProviderRegistry,
        publications: MutableList<MaterializationResult>,
    ): suspend (MaterializationResult) -> PackageOutcome<Unit> = { materialized ->
        publications += materialized
        when (providers.publishInstalledProviders(
            materialized.bindings,
            materialized.failures.mapValues { (id, failure) -> failure.toPackageUnavailable(id) },
        )) {
            is InstalledProvidersPublicationResult.Success -> PackageOutcome.Success(Unit)
            is InstalledProvidersPublicationResult.Rejected -> PackageOutcome.Failure(
                PackageFailure.Loading(PackageFailure.LoadingDetail.PUBLICATION_REJECTED),
            )
        }
    }

    private fun index(root: File): StoredInstalledIndex =
        success(InstalledPackageStore(root).loadIndex()).index

    private fun <T> success(outcome: PackageOutcome<T>): T = when (outcome) {
        is PackageOutcome.Success -> outcome.value
        is PackageOutcome.Failure -> throw AssertionError("Expected success, got ${outcome.error}")
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    private fun <T> withTemporaryDirectory(block: (File) -> T): T {
        val root = createTempDirectory("external-openai-contract-").toFile()
        return try { block(root) } finally { root.deleteRecursively() }
    }

    private suspend fun <T> withTemporaryDirectorySuspend(block: suspend (File) -> T): T {
        val root = createTempDirectory("external-openai-contract-").toFile()
        return try { block(root) } finally { root.deleteRecursively() }
    }

    /** Validation, storage, materialization, publication, and inspection must never enter the Lua kernel. */
    private class UnusedBridge : LuaKernelBridge {
        var calls: Int = 0
            private set

        private fun unused(): Nothing {
            calls += 1
            error("Package validation, storage, materialization, and inspection must not enter the Lua kernel")
        }

        override fun create(config: LuaKernelConfig): LuaKernelOutcome = unused()
        override fun load(handle: LuaStateHandle, source: String, entrypoint: String): LuaKernelOutcome = unused()
        override fun start(handle: LuaStateHandle): LuaKernelOutcome = unused()
        override fun resume(
            operation: LuaOperationHandle,
            success: Boolean,
            value: String,
            spawnAdmission: LuaSpawnAdmission,
        ): LuaKernelOutcome = unused()

        override fun cancel(operation: LuaOperationHandle): LuaKernelOutcome = unused()
        override fun interrupt(handle: LuaStateHandle): LuaKernelOutcome = unused()
        override fun snapshot(handle: LuaStateHandle): LuaKernelOutcome = unused()
        override fun close(handle: LuaStateHandle): LuaKernelOutcome = unused()
        override fun loadProgramImage(
            handle: LuaStateHandle,
            entryPoint: String,
            sourceMap: Map<String, String>,
        ): LuaKernelOutcome = unused()

        override fun invokeStartupCallback(
            handle: LuaStateHandle,
            callbackHandle: LuaCallbackHandle,
            config: LuaValue,
            spawnAdmission: LuaSpawnAdmission,
        ): LuaKernelOutcome = unused()

        override fun invokeCallback(
            handle: LuaStateHandle,
            callbackHandle: LuaCallbackHandle,
            arguments: LuaValue,
            spawnAdmission: LuaSpawnAdmission,
        ): LuaKernelOutcome = unused()

        override fun startCoroutine(
            handle: LuaStateHandle,
            coroutineId: LuaCoroutineId,
            spawnAdmission: LuaSpawnAdmission,
        ): LuaKernelOutcome = unused()
    }

    private companion object {
        const val RESOURCE_PATH = "openai-agent-channel/talkcan-channel.zip"
        const val REPOSITORY_ID = "1313913383"
        const val PACKAGE_VERSION = "1.0.7"
        const val RELEASE_TAG = "v1.0.7"
        // Published v1.0.7 provenance (release tag commit
        // fd94676).
        const val RELEASE_ID = "359437600"
        const val ASSET_ID = "488626975"
        const val OFFICIAL_OWNER_ID = "1224006"
        const val ARTIFACT_SIZE = 173132
        const val ARTIFACT_SHA256 = "602cbf54008e5e204de08118b6a2131bb5f5fac120f0a42ff5eb0e529a00d9f3"

        val EXPECTED_ENTRIES = listOf(
            "manifest.json",
            "lua/agent.lua",
            "lua/cjson.lua",
            "lua/client.lua",
            "lua/ltn12.lua",
            "lua/openai.lua",
            "lua/openai/chat_completions.lua",
            "lua/openai/init.lua",
            "lua/openai/model_choices.lua",
            "lua/openai/sse.lua",
            "lua/plugin.lua",
            "lua/policy.lua",
            "lua/profile.lua",
            "lua/socket.lua",
            "lua/socket/http.lua",
            "lua/socket/url.lua",
            "lua/tableshape.lua",
            "lua/tableshape/init.lua",
        )

        val EXPECTED_SOURCE_MODULES = setOf(
            "agent",
            "cjson",
            "client",
            "ltn12",
            "openai",
            "openai.chat_completions",
            "openai.init",
            "openai.model_choices",
            "openai.sse",
            "plugin",
            "policy",
            "profile",
            "socket",
            "socket.http",
            "socket.url",
            "tableshape",
            "tableshape.init",
        )
    }
}
