package io.talkcan.dependency

import io.talkcan.channel.capability.ChannelCapability
import io.talkcan.lua.API_VERSION
import io.talkcan.lua.LUA_VERSION
import io.talkcan.lua.LuaCallbackHandle
import io.talkcan.lua.LuaCoroutineId
import io.talkcan.lua.LuaKernelBridge
import io.talkcan.lua.kernel.KotlinLuaKernelBridge
import io.talkcan.lua.LuaKernelConfig
import io.talkcan.lua.LuaKernelOutcome
import io.talkcan.lua.LuaOperationHandle
import io.talkcan.lua.LuaPackageMaterializer
import io.talkcan.lua.LuaSpawnAdmission
import io.talkcan.lua.LuaStateHandle
import io.talkcan.lua.LuaStateGeneration
import io.talkcan.lua.LuaStateId
import io.talkcan.lua.LuaValue
import io.talkcan.model.ChannelConfigurationField
import io.talkcan.model.ChannelProviderResolution
import io.talkcan.model.InstalledProvidersPublicationResult
import io.talkcan.model.ProviderRevisionFingerprint
import io.talkcan.resource.MountBindingStore
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Task 10.1: Install the exact published Journal asset through the generic validator/store
 * and register its repository-derived provider without special dispatch.
 */
class ExternalJournalChannelContractTest {

    @Test
    fun `exact Journal archive validates identity source map resources schema and materializes without Lua state`() =
        withTemporaryDirectory { root ->
            // Verify exact artifact bytes
            val artifact = fixture()
            assertEquals(ARTIFACT_SHA256, sha256(artifact))
            assertEquals(ARTIFACT_SIZE, artifact.size)

            // Verify archive structure
            ZipFile(File(root, "journal-fixture.zip").also { it.writeBytes(artifact) }).use { zip ->
                assertEquals(
                    setOf("manifest.json", "lua/json.lua", "lua/plugin.lua"),
                    zip.entries().asSequence().map { it.name }.toSet()
                )
            }

            // Validate through generic PackageValidator
            val bridge = CountingBridge()
            val revision = success(
                PackageValidator.validatePackage(
                    ByteArrayInputStream(artifact),
                    sourceRecord(),
                    File(root, "staging.zip"),
                )
            )

            // Verify manifest identity
            assertEquals(GitHubRepositoryIdentity(REPOSITORY_ID), revision.manifest.repositoryId)
            assertEquals(PACKAGE_VERSION, revision.manifest.packageVersion)
            assertEquals("plugin", revision.programImage.entryPoint)
            assertEquals(LUA_VERSION, revision.manifest.runtime.luaVersion)
            assertEquals(API_VERSION, revision.manifest.runtime.apiVersion)

            // Verify source map includes both Lua modules
            assertEquals(setOf("json", "plugin"), revision.sourceMap.keys)

            // Verify digest and fingerprint
            assertEquals(ARTIFACT_SHA256, revision.digest.value)
            assertEquals(ProviderRevisionFingerprint.fromDigest(revision.digest), revision.fingerprint)

            // Pin the immutable publication identity used to source these bytes.
            val source = sourceRecord()
            assertEquals(RELEASE_TAG, source.release.tag)
            assertEquals(RELEASE_ID, source.release.releaseId)
            assertTrue(RELEASE_URL.endsWith("/$RELEASE_TAG"))
            assertTrue(RELEASE_COMMIT.matches(Regex("[0-9a-f]{40}")))

            // Verify exact capabilities
            assertEquals(
                setOf("storage.files", "audio.files", "audio.transcription"),
                revision.manifest.capabilities
            )

            val configurationFields = revision.manifest.configuration.data.fields
            assertEquals(2, configurationFields.size)
            val outputModeField = configurationFields.single { it.id == "output_mode" }
            assertTrue(outputModeField is ConfigurationFieldDeclaration.StringField)
            val stringField = outputModeField as ConfigurationFieldDeclaration.StringField
            assertEquals("VOICE_AND_TRANSCRIPT", stringField.default)
            assertEquals(
                listOf("VOICE", "TRANSCRIPT", "VOICE_AND_TRANSCRIPT"),
                stringField.allowedValues
            )
            val recordingLimitField =
                configurationFields.single { it.id == "recording_limit_minutes" }
                    as? ConfigurationFieldDeclaration.IntegerField
                    ?: throw AssertionError("recording_limit_minutes must be an integer")
            assertEquals(5L, recordingLimitField.default)
            assertEquals(1L, recordingLimitField.minimum)
            assertEquals(10L, recordingLimitField.maximum)

            // Verify exact resource declarations
            val mounts = revision.manifest.resources.mounts
            assertEquals(1, mounts.size)
            val outputMount = mounts.single()
            assertEquals("output", outputMount.id)
            assertEquals(PackageMountKind.DIRECTORY_TREE, outputMount.kind)
            assertEquals(PackageMountAccess.READ_WRITE, outputMount.access)
            assertTrue(outputMount.required)
            assertEquals("Journal directory", outputMount.label)
            assertEquals("Directory containing Journal entries and daily Markdown.", outputMount.help)

            // Derive implementation ID from repository
            val implementationId = InstalledProviderId.derive(revision.manifest.repositoryId)
            assertEquals("github-repository:$REPOSITORY_ID", implementationId.value)

            // Verify this is NOT a built-in channel
            assertFalse(implementationId.value.startsWith("builtin:"))

            // Materialize through generic LuaPackageMaterializer
            val binding = LuaPackageMaterializer.materialize(revision, bridge)

            // Verify descriptor identity and presentation
            assertEquals(implementationId, binding.provider.descriptor.implementationId)
            assertEquals("Journal Channel", binding.provider.descriptor.presentation.label)
            assertEquals("External portable journal channel", binding.provider.descriptor.presentation.summary)

            // Verify compiled capabilities
            assertEquals(
                setOf(
                    ChannelCapability.StorageFiles,
                    ChannelCapability.AudioFiles,
                    ChannelCapability.Transcription
                ),
                binding.provider.descriptor.requiredCapabilities
            )

            // Verify compiled resource declarations
            val descriptorMounts = binding.provider.descriptor.resourceDeclarations.mounts
            assertEquals(1, descriptorMounts.size)
            val descriptorMount = descriptorMounts.single()
            assertEquals("output", descriptorMount.id)
            assertEquals(PackageMountKind.DIRECTORY_TREE, descriptorMount.kind)
            assertEquals(PackageMountAccess.READ_WRITE, descriptorMount.access)
            assertTrue(descriptorMount.required)
            assertEquals("Journal directory", descriptorMount.label)
            assertEquals("Directory containing Journal entries and daily Markdown.", descriptorMount.help)

            val descriptorFields = binding.provider.descriptor.configurationFields
            assertEquals(2, descriptorFields.size)
            val descriptorOutputMode = descriptorFields.single { it.id == "output_mode" }
                as? ChannelConfigurationField.ChoiceField
                ?: throw AssertionError("output_mode must be a choice")
            assertEquals("Output mode", descriptorOutputMode.label)
            assertEquals(
                listOf(
                    ChannelConfigurationField.ChoiceField.Choice("VOICE", "VOICE"),
                    ChannelConfigurationField.ChoiceField.Choice("TRANSCRIPT", "TRANSCRIPT"),
                    ChannelConfigurationField.ChoiceField.Choice("VOICE_AND_TRANSCRIPT", "VOICE_AND_TRANSCRIPT")
                ),
                descriptorOutputMode.choices
            )
            val descriptorRecordingLimit =
                descriptorFields.single { it.id == "recording_limit_minutes" }
                    as? ChannelConfigurationField.NumberField
                    ?: throw AssertionError("recording_limit_minutes must be a number")
            assertEquals("Recording limit (minutes)", descriptorRecordingLimit.label)
            assertEquals("Maximum duration of one recording.", descriptorRecordingLimit.help)
            assertEquals(1L, descriptorRecordingLimit.minimum)
            assertEquals(10L, descriptorRecordingLimit.maximum)

            // Verify no Lua state was constructed during validation/materialization
            assertEquals(0, bridge.created)
            assertEquals(0, bridge.loads)
            assertEquals(0, bridge.invocations)
        }

    @Test
    fun `repository installation publishes provider without constructing Lua state or mount grants`() = runTest {
        withTemporaryDirectorySuspend { root ->
            val bridge = CountingBridge()
            val providers = io.talkcan.model.ChannelImplementationProviderRegistry()
            val store = InstalledPackageStore(root)
            val repository = InstalledPackageRepository(
                store = store,
                bridge = bridge,
                publisher = { materialized ->
                    when (providers.publishInstalledProviders(
                        materialized.bindings,
                        materialized.failures.mapValues { (id, failure) -> failure.toPackageUnavailable(id) }
                    )) {
                        is InstalledProvidersPublicationResult.Success -> PackageOutcome.Success(Unit)
                        is InstalledProvidersPublicationResult.Rejected -> PackageOutcome.Failure(
                            PackageFailure.Loading(PackageFailure.LoadingDetail.PUBLICATION_REJECTED)
                        )
                    }
                },
                dispatcher = StandardTestDispatcher(testScheduler),
            )

            val id = InstalledProviderId.derive(sourceRecord().repositoryId)

            // Install through generic repository transaction
            assertEquals(
                MutationResult.Installed(id),
                success(repository.installOrUpdate(ByteArrayInputStream(fixture()), sourceRecord()))
            )

            // Verify provider is resolvable
            val resolution = providers.resolve(id)
            assertTrue(resolution is ChannelProviderResolution.Available)

            // Verify no Lua state was constructed during installation
            assertEquals(0, bridge.created)
            assertEquals(0, bridge.loads)

            // Verify no automatic mount grants were created
            // (Mount grants require explicit user selection and MountBindingStore operations)
            val mountStore = MountBindingStore(File(root, "mounts.json"))
            mountStore.load()
            assertEquals(0, mountStore.bindingsForInstance("journal-test-instance").size)

            repository.requestClose()
        }
    }

    private fun fixture(): ByteArray =
        requireNotNull(javaClass.classLoader?.getResourceAsStream(RESOURCE_PATH))
            .use { it.readBytes() }

    private fun sourceRecord() = PackageSourceRecord(
        repositoryId = GitHubRepositoryIdentity(REPOSITORY_ID),
        coordinates = GitHubRepositoryCoordinates("talkcan-channels", "journal"),
        release = GitHubReleaseIdentity(RELEASE_ID, "v$PACKAGE_VERSION", false),
        asset = GitHubAssetIdentity(ASSET_ID, "talkcan-channel.zip"),
        ownerId = OFFICIAL_OWNER_ID,
    )

    private fun <T> success(outcome: PackageOutcome<T>): T = when (outcome) {
        is PackageOutcome.Success -> outcome.value
        is PackageOutcome.Failure -> throw AssertionError("Expected success, got ${outcome.error}")
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    private fun <T> withTemporaryDirectory(block: (File) -> T): T {
        val root = createTempDirectory("external-journal-contract-").toFile()
        return try {
            block(root)
        } finally {
            root.deleteRecursively()
        }
    }

    private suspend fun <T> withTemporaryDirectorySuspend(block: suspend (File) -> T): T {
        val root = createTempDirectory("external-journal-contract-").toFile()
        return try {
            block(root)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `exact Journal warning tasks yield their configured sleep durations in the native kernel`() =
        withTemporaryDirectory { root ->
            val revision = success(
                PackageValidator.validatePackage(
                    ByteArrayInputStream(fixture()),
                    sourceRecord(),
                    File(root, "native-kernel.zip"),
                ),
            )
            val bridge: LuaKernelBridge = KotlinLuaKernelBridge()
            val created = bridge.create(
                LuaKernelConfig(hookInterval = 100, instructionBudget = 10_000_000),
            ) as? LuaKernelOutcome.Created
                ?: throw AssertionError("native kernel state creation failed")
            val handle = LuaStateHandle(
                LuaStateId(created.stateId),
                LuaStateGeneration(created.generation),
            )
            try {
                assertTrue(
                    bridge.loadProgramImage(
                        handle,
                        revision.programImage.entryPoint,
                        revision.sourceMap,
                    ) is LuaKernelOutcome.Completed,
                )
                val startupAdmission = RecordingSpawnAdmission()
                val startup = bridge.invokeStartupCallback(
                    handle,
                    LuaCallbackHandle(handle, "startup"),
                    LuaValue.Map(
                        mapOf(
                            "schema_version" to LuaValue.Integer(1),
                            "values" to LuaValue.Map(
                                mapOf(
                                    "output_mode" to LuaValue.StringValue("VOICE_AND_TRANSCRIPT"),
                                    "recording_limit_minutes" to LuaValue.Integer(1),
                                ),
                            ),
                        ),
                    ),
                    startupAdmission,
                )
                assertTrue("startup failed: $startup", startup is LuaKernelOutcome.Completed)

                val warningAdmission = RecordingSpawnAdmission()
                val lifecycle = bridge.invokeCallback(
                    handle,
                    LuaCallbackHandle(handle, "handle_capture_lifecycle"),
                    LuaValue.Map(
                        mapOf(
                            "event" to LuaValue.StringValue("capture_started"),
                            "session" to LuaValue.StringValue("native-warning-test"),
                            "max_duration_ms" to LuaValue.Integer(60_000),
                            "remaining_duration_ms" to LuaValue.Integer(60_000),
                        ),
                    ),
                    warningAdmission,
                )
                assertTrue("capture lifecycle failed: $lifecycle", lifecycle is LuaKernelOutcome.Completed)
                assertEquals(2, warningAdmission.coroutineIds.size)

                val yieldedLabels = warningAdmission.coroutineIds.map { coroutineId ->
                    val outcome = bridge.startCoroutine(
                        handle,
                        LuaCoroutineId(coroutineId),
                        RecordingSpawnAdmission(),
                    ) as? LuaKernelOutcome.Yielded
                        ?: throw AssertionError("warning task did not yield")
                    outcome.value ?: throw AssertionError("warning task yielded without a label")
                }
                assertEquals(
                    setOf(45.0, 57.0),
                    yieldedLabels.map { it.substringAfterLast(':').toDouble() }.toSet(),
                )
            } finally {
                bridge.close(handle)
            }
        }

    private class RecordingSpawnAdmission : LuaSpawnAdmission {
        val coroutineIds = mutableListOf<Long>()

        override fun admitTask(coroutineId: Long): Int {
            coroutineIds += coroutineId
            return 0
        }
    }

    private class CountingBridge : LuaKernelBridge {
        var created = 0
        var loads = 0
        var invocations = 0

        override fun create(config: LuaKernelConfig): LuaKernelOutcome {
            created += 1
            return LuaKernelOutcome.Created(created.toLong(), created.toLong(), LUA_VERSION, API_VERSION, "test")
        }

        override fun load(handle: LuaStateHandle, source: String, entrypoint: String): LuaKernelOutcome {
            loads += 1
            return complete(handle)
        }

        override fun start(handle: LuaStateHandle): LuaKernelOutcome = complete(handle)

        override fun resume(
            operation: LuaOperationHandle,
            success: Boolean,
            value: String,
            spawnAdmission: LuaSpawnAdmission
        ): LuaKernelOutcome = complete(operation.stateHandle)

        override fun cancel(operation: LuaOperationHandle): LuaKernelOutcome = complete(operation.stateHandle)

        override fun interrupt(handle: LuaStateHandle): LuaKernelOutcome = complete(handle)

        override fun snapshot(handle: LuaStateHandle): LuaKernelOutcome =
            LuaKernelOutcome.Snapshot(
                handle.stateId.value,
                handle.generation.value,
                null,
                LUA_VERSION,
                API_VERSION,
                "test"
            )

        override fun close(handle: LuaStateHandle): LuaKernelOutcome =
            LuaKernelOutcome.Closed(handle.stateId.value, handle.generation.value)

        override fun loadProgramImage(
            handle: LuaStateHandle,
            entryPoint: String,
            sourceMap: Map<String, String>
        ): LuaKernelOutcome = complete(handle, "[\"startup\",\"handle_readiness\",\"handle_input\"]")

        override fun invokeStartupCallback(
            handle: LuaStateHandle,
            callbackHandle: LuaCallbackHandle,
            config: LuaValue,
            spawnAdmission: LuaSpawnAdmission
        ): LuaKernelOutcome {
            invocations += 1
            return complete(handle)
        }

        override fun invokeCallback(
            handle: LuaStateHandle,
            callbackHandle: LuaCallbackHandle,
            arguments: LuaValue,
            spawnAdmission: LuaSpawnAdmission
        ): LuaKernelOutcome {
            invocations += 1
            return complete(handle, "{\"ready\":false}")
        }

        override fun startCoroutine(
            handle: LuaStateHandle,
            coroutineId: LuaCoroutineId,
            spawnAdmission: LuaSpawnAdmission
        ): LuaKernelOutcome = complete(handle)

        private fun complete(handle: LuaStateHandle, value: String? = null) =
            LuaKernelOutcome.Completed(
                handle.stateId.value,
                handle.generation.value,
                null,
                value,
                null,
                LUA_VERSION,
                API_VERSION,
                "test"
            )
    }

    private companion object {
        const val RESOURCE_PATH = "journal-channel/talkcan-channel.zip"
        const val REPOSITORY_ID = "1313912742"
        const val RELEASE_ID = "370507991"
        const val ASSET_ID = "514295711"
        const val OFFICIAL_OWNER_ID = "1224006"
        const val PACKAGE_VERSION = "2.1.0"
        const val RELEASE_TAG = "v2.1.0"
        const val RELEASE_URL =
            "https://github.com/talkcan-channels/journal/releases/tag/v2.1.0"
        const val RELEASE_COMMIT = "fdd7ff949c7c29bd2db2994ae76cf4a1a382d9eb"
        const val ARTIFACT_SIZE = 72602
        const val ARTIFACT_SHA256 = "01f43ba266eb634766316fd39e97b8e5baa10cbd1db69b249674fd265de0f532"
    }
}
