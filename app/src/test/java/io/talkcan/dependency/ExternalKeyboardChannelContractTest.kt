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
import io.talkcan.model.ChannelDescriptorResolution
import io.talkcan.model.ChannelImplementationId
import io.talkcan.model.ChannelImplementationProviderRegistry
import io.talkcan.model.ChannelProviderResolution
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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tasks 11.13 / 12.3 / 12.11 / 13.6 for the byte-pinned external Keyboard package.
 *
 * The exact published v1.1.0 release artifact traverses the generic host
 * machinery only — validator → immutable store → materializer → provider
 * registry. The v1.1.0 manifest declares the three-stage detached hierarchy
 * (host_os -> host_layout -> host_profile) as dynamic-choice fields with
 * dependsOn edges. There is no Keyboard-name dispatch, no repository-identity
 * special case, no automatic instance, no built-in configuration cloning, and
 * no Lua state, preparation, connection, or output effect before a runtime is
 * explicitly selected.
 *
 * The source-record release/asset IDs are the published v1.1.0 GitHub IDs
 * (release tag commit c1e312ee4699035efce4fd4f0cf927155a87921d).
 */
class ExternalKeyboardChannelContractTest {

    @Test
    fun `exact Keyboard archive validates identity capabilities dynamic choice no mounts and materializes without Lua state`() =
        withTemporaryDirectory { root ->
            // 11.13: exact published bytes.
            val artifact = fixture()
            assertEquals(ARTIFACT_SHA256, sha256(artifact))
            assertEquals(ARTIFACT_SIZE, artifact.size)

            // Canonical archive layout: exact entry set, order, and stored-entry metadata.
            ZipFile(File(root, "keyboard-fixture.zip").also { it.writeBytes(artifact) }).use { zip ->
                assertEquals(
                    listOf("manifest.json", "lua/plugin.lua"),
                    zip.entries().asSequence().map { it.name }.toList(),
                )
                for (name in listOf("manifest.json", "lua/plugin.lua")) {
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
            assertEquals("Keyboard Channel", revision.manifest.presentation.label)
            assertEquals("External portable keyboard output channel", revision.manifest.presentation.summary)

            // Source map, digest, and fingerprint.
            assertEquals(setOf("plugin"), revision.sourceMap.keys)
            assertEquals(ARTIFACT_SHA256, revision.digest.value)
            assertEquals(ProviderRevisionFingerprint.fromDigest(revision.digest), revision.fingerprint)

            // Exact capabilities, in declared order.
            assertEquals(
                listOf(PackageCapability.AUDIO_TRANSCRIPTION, PackageCapability.KEYBOARD_OUTPUT),
                revision.manifest.capabilities.toList(),
            )

            // No mount declarations whatsoever.
            assertTrue(revision.manifest.resources.mounts.isEmpty())

            // Exact scalar data schema: the three-stage detached hierarchy
            // selection, each an unconstrained string with its stage default and
            // no enumeration.
            val dataFields = revision.manifest.configuration.data.fields
            assertEquals(listOf("host_os", "host_layout", "host_profile"), dataFields.map { it.id })
            for (dataField in dataFields) {
                assertTrue(dataField is ConfigurationFieldDeclaration.StringField)
                assertNull((dataField as ConfigurationFieldDeclaration.StringField).allowedValues)
            }
            assertEquals("linux", (dataFields[0] as ConfigurationFieldDeclaration.StringField).default)
            assertEquals("linux:us", (dataFields[1] as ConfigurationFieldDeclaration.StringField).default)
            assertEquals("linux:us", (dataFields[2] as ConfigurationFieldDeclaration.StringField).default)

            // Exact dynamic-choice UI declaration: platforms (no dependency) ->
            // layouts (dependsOn host_os) -> profiles (dependsOn host_layout).
            val uiFields = revision.manifest.configuration.ui.fields
            assertEquals(listOf("host_os", "host_layout", "host_profile"), uiFields.map { it.field })
            for (uiField in uiFields) {
                assertEquals(UiControl.DYNAMIC_CHOICE, uiField.control)
                assertNull(uiField.help)
                assertNull(uiField.choices)
            }
            assertEquals("Host platform", uiFields[0].label)
            assertEquals(DynamicChoiceSource.KEYBOARD_OUTPUT_PLATFORMS, uiFields[0].source)
            assertNull(uiFields[0].dependsOnFieldId)
            assertEquals("Keyboard layout", uiFields[1].label)
            assertEquals(DynamicChoiceSource.KEYBOARD_OUTPUT_LAYOUTS, uiFields[1].source)
            assertEquals("host_os", uiFields[1].dependsOnFieldId)
            assertEquals("Host profile", uiFields[2].label)
            assertEquals(DynamicChoiceSource.KEYBOARD_OUTPUT_PROFILES, uiFields[2].source)
            assertEquals("host_layout", uiFields[2].dependsOnFieldId)

            // Repository-derived identity only: not the built-in keyboard, not a built-in at all.
            val implementationId = InstalledProviderId.derive(revision.manifest.repositoryId)
            assertEquals("github-repository:$REPOSITORY_ID", implementationId.value)
            assertNotEquals(ChannelImplementationId("builtin:keyboard"), implementationId)
            assertFalse(
                "An external Keyboard package must not become a built-in before publication.",
                implementationId.value.startsWith("builtin:"),
            )

            // Materialize through the generic materializer: descriptor only, no Lua entry.
            val binding = LuaPackageMaterializer.materialize(revision, bridge)
            assertEquals(revision.manifest.repositoryId, binding.repositoryId)
            assertEquals(revision.digest, binding.expectedDigest)
            assertEquals(ARTIFACT_SHA256, binding.provider.fingerprint.value)
            assertEquals(implementationId, binding.provider.descriptor.implementationId)
            assertEquals("Keyboard Channel", binding.provider.descriptor.presentation.label)
            assertEquals(
                "External portable keyboard output channel",
                binding.provider.descriptor.presentation.summary,
            )
            assertEquals(
                setOf(ChannelCapability.Transcription, ChannelCapability.TextOutput),
                binding.provider.descriptor.requiredCapabilities,
            )
            assertTrue(binding.provider.descriptor.resourceDeclarations.mounts.isEmpty())
            assertEquals(
                listOf<ChannelConfigurationField>(
                    ChannelConfigurationField.DynamicChoiceField(
                        id = "host_os",
                        label = "Host platform",
                        source = DynamicConfigurationChoiceSourceId(DynamicChoiceSource.KEYBOARD_OUTPUT_PLATFORMS),
                    ),
                    ChannelConfigurationField.DynamicChoiceField(
                        id = "host_layout",
                        label = "Keyboard layout",
                        source = DynamicConfigurationChoiceSourceId(DynamicChoiceSource.KEYBOARD_OUTPUT_LAYOUTS),
                        dependsOnFieldId = "host_os",
                    ),
                    ChannelConfigurationField.DynamicChoiceField(
                        id = "host_profile",
                        label = "Host profile",
                        source = DynamicConfigurationChoiceSourceId.KEYBOARD_OUTPUT_PROFILES,
                        dependsOnFieldId = "host_layout",
                    ),
                ),
                binding.provider.descriptor.configurationFields,
            )
            assertEquals(0, bridge.calls)
        }

    @Test
    fun `repository installation persists exact bytes reparses and republishes without Lua keyboard or automatic instance effects`() =
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
                    "Keyboard Channel",
                    (resolution as ChannelProviderResolution.Available).provider.descriptor.presentation.label,
                )
                // The registry published exactly the one package provider: no automatic
                // instance and no co-registered built-in keyboard beside it.
                assertEquals(listOf(id), providers.descriptors().map { it.implementationId })
                assertEquals(0, bridge.calls)

                // Exact bytes survive in the immutable content-addressed store.
                assertTrue(
                    "committed content must be byte-exact",
                    File(root, "content/sha256/$ARTIFACT_SHA256").readBytes().contentEquals(fixture()),
                )

                // The cached index retains the exact digest and dynamic-choice declaration;
                // a fresh install carries no rollback generation.
                val record = index(root).providers.getValue(sourceRecord().repositoryId)
                assertEquals(ARTIFACT_SHA256, record.active.digest.value)
                assertNull(record.rollback)
                val storedUi = record.active.manifest.configuration.ui.fields
                assertEquals(listOf("host_os", "host_layout", "host_profile"), storedUi.map { it.field })
                assertTrue(storedUi.all { it.control == UiControl.DYNAMIC_CHOICE })
                assertEquals(
                    listOf(
                        DynamicChoiceSource.KEYBOARD_OUTPUT_PLATFORMS,
                        DynamicChoiceSource.KEYBOARD_OUTPUT_LAYOUTS,
                        DynamicChoiceSource.KEYBOARD_OUTPUT_PROFILES,
                    ),
                    storedUi.map { it.source },
                )
                assertEquals(listOf(null, "host_os", "host_layout"), storedUi.map { it.dependsOnFieldId })
                assertTrue(record.active.manifest.capabilities.contains(PackageCapability.KEYBOARD_OUTPUT))
                assertTrue(record.active.manifest.capabilities.contains(PackageCapability.AUDIO_TRANSCRIPTION))

                // Reparse-from-artifact recovery: exact stored bytes revalidate into the
                // identical manifest, digest, and source map.
                val reparsed = success(InstalledPackageStore(root).revalidateStoredRevision(record.active))
                assertEquals(record.active.manifest, reparsed.manifest)
                assertEquals(ARTIFACT_SHA256, reparsed.digest.value)
                assertEquals(setOf("plugin"), reparsed.sourceMap.keys)

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

    @Test
    fun `provider descriptor inspection remains effect free and carries no built-in keyboard configuration clone`() =
        withTemporaryDirectory { root ->
            val bridge = UnusedBridge()
            val revision = success(
                PackageValidator.validatePackage(
                    ByteArrayInputStream(fixture()),
                    sourceRecord(),
                    File(root, "staging.zip"),
                ),
            )
            val binding = LuaPackageMaterializer.materialize(revision, bridge)
            val id = InstalledProviderId.derive(revision.manifest.repositoryId)

            val providers = ChannelImplementationProviderRegistry()
            val result = providers.publishInstalledProviders(mapOf(id to binding), emptyMap())
            assertTrue(result is InstalledProvidersPublicationResult.Success)

            // Repeated provider inspection and editor-input projection stay effect-free.
            repeat(3) {
                val descriptor = (providers.resolve(id) as ChannelProviderResolution.Available).provider.descriptor
                assertEquals(id, descriptor.implementationId)
                assertEquals(
                    listOf("host_os", "host_layout", "host_profile"),
                    descriptor.configurationFields.map(ChannelConfigurationField::id),
                )
                assertTrue(descriptor.resourceDeclarations.mounts.isEmpty())
            }
            val descriptorResolution = providers.resolveDescriptor(id)
            assertTrue(descriptorResolution is ChannelDescriptorResolution.Available)
            assertEquals(0, bridge.calls)

            // The editor sees exactly the package-owned three-stage dynamic choice
            // hierarchy; nothing is cloned from the built-in keyboard.
            val fields = providers.descriptors().single().configurationFields
            assertEquals(
                listOf("host_os", "host_layout", "host_profile"),
                fields.map(ChannelConfigurationField::id),
            )
            for (field in fields) {
                assertTrue(field is ChannelConfigurationField.DynamicChoiceField)
                assertTrue((field as ChannelConfigurationField.DynamicChoiceField).required)
            }
            val platformField = fields[0] as ChannelConfigurationField.DynamicChoiceField
            assertEquals("Host platform", platformField.label)
            assertEquals(
                DynamicConfigurationChoiceSourceId(DynamicChoiceSource.KEYBOARD_OUTPUT_PLATFORMS),
                platformField.source,
            )
            assertNull(platformField.dependsOnFieldId)
            val layoutField = fields[1] as ChannelConfigurationField.DynamicChoiceField
            assertEquals("Keyboard layout", layoutField.label)
            assertEquals(
                DynamicConfigurationChoiceSourceId(DynamicChoiceSource.KEYBOARD_OUTPUT_LAYOUTS),
                layoutField.source,
            )
            assertEquals("host_os", layoutField.dependsOnFieldId)
            val profileField = fields[2] as ChannelConfigurationField.DynamicChoiceField
            assertEquals("Host profile", profileField.label)
            assertEquals(DynamicConfigurationChoiceSourceId.KEYBOARD_OUTPUT_PROFILES, profileField.source)
            assertEquals("host_layout", profileField.dependsOnFieldId)
            assertNotEquals(ChannelImplementationId("builtin:keyboard"), providers.descriptors().single().implementationId)
            assertEquals(0, bridge.calls)
        }

    private fun fixture(): ByteArray =
        requireNotNull(javaClass.classLoader?.getResourceAsStream(RESOURCE_PATH)) {
            "Missing keyboard channel fixture: $RESOURCE_PATH"
        }.use { it.readBytes() }

    private fun sourceRecord(): PackageSourceRecord = PackageSourceRecord(
        repositoryId = GitHubRepositoryIdentity(REPOSITORY_ID),
        coordinates = GitHubRepositoryCoordinates("talkcan", "keyboard-channel"),
        release = GitHubReleaseIdentity(RELEASE_ID, "v$PACKAGE_VERSION", false),
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
        val root = createTempDirectory("external-keyboard-contract-").toFile()
        return try { block(root) } finally { root.deleteRecursively() }
    }

    private suspend fun <T> withTemporaryDirectorySuspend(block: suspend (File) -> T): T {
        val root = createTempDirectory("external-keyboard-contract-").toFile()
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
        const val RESOURCE_PATH = "keyboard-channel/talkcan-channel.zip"
        const val REPOSITORY_ID = "1310281239"
        const val PACKAGE_VERSION = "1.1.0"
        // Published v1.1.0 provenance (release tag commit
        // c1e312ee4699035efce4fd4f0cf927155a87921d).
        const val RELEASE_ID = "359168730"
        const val ASSET_ID = "488185797"
        const val OFFICIAL_OWNER_ID = "1224006"
        const val ARTIFACT_SIZE = 14291
        const val ARTIFACT_SHA256 = "f4cef488cf4afbc8c3cad643d62e2511a99b314eed9fa823874f48e959e9397c"
    }
}
