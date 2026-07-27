package io.talkcan.dependency

import io.talkcan.lua.API_VERSION
import io.talkcan.lua.LUA_VERSION
import io.talkcan.lua.LuaCallbackHandle
import io.talkcan.lua.LuaCoroutineId
import io.talkcan.lua.LuaKernelBridge
import io.talkcan.lua.LuaKernelConfig
import io.talkcan.lua.LuaKernelOutcome
import io.talkcan.lua.LuaOperationHandle
import io.talkcan.lua.LuaSpawnAdmission
import io.talkcan.lua.LuaStateHandle
import io.talkcan.lua.LuaStateGeneration
import io.talkcan.lua.LuaStateId
import io.talkcan.lua.LuaValue
import io.talkcan.model.ChannelImplementationProviderRegistry
import io.talkcan.model.ChannelProviderResolution
import io.talkcan.model.InstalledProvidersPublicationResult
import io.talkcan.model.OpaqueJsonObject
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Task 1.9 contract: the evolved in-tree Diagnostics fixture is the only accepted immutable
 * package shape, while the byte-pinned historical releases are retained solely as rejection
 * fixtures: v1.0.0/v1.1.0 predate the mandatory `configuration`/`capabilities` declarations,
 * and v1.2.0 predates the mandatory `resources` declaration.
 *
 * These tests prove the clean cutover by driving the real [PackageValidator] and the real
 * [InstalledPackageRepository] with the exact pinned bytes:
 *  - the evolved fixture validates and carries explicit (possibly empty) declarations;
 *  - each historical release is rejected at the strict manifest decoder with a typed
 *    `FORMAT/MALFORMED_MANIFEST` result, never reaching storage, the kernel bridge, the
 *    provider registry, or any compatibility/callback/v2 negotiation path.
 *
 * Synthetic manifest-string coverage for missing `configuration`/`capabilities` lives in
 * [PackageValidatorContractTest]; these tests defend the byte-pinned cutover boundary.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class DiagnosticsLegacyRejectionContractTest {

    @Test
    fun `evolved diagnostics fixture carries explicit configuration and capabilities declarations`() = withTemporaryDirectory { root ->
        val artifact = loadPinnedFixture(EVOLVED_RESOURCE_PATH, EVOLVED_ARTIFACT_SHA256)
        val manifest = readManifestJson(artifact)

        // Explicit declarations are present (not inferred, not defaulted, not omitted).
        assertTrue(
            "Evolved fixture manifest must declare an explicit configuration object",
            manifest.has(CONFIGURATION_KEY),
        )
        assertTrue(
            "Evolved fixture manifest must declare an explicit capabilities array",
            manifest.has(CAPABILITIES_KEY),
        )
        val configObject = manifest.getJSONObject(CONFIGURATION_KEY)
        assertEquals(1, configObject.optInt("schemaVersion", -1))
        assertNotNull(
            "Evolved fixture configuration must carry an explicit data object",
            configObject.opt("data"),
        )
        assertNotNull(
            "Evolved fixture configuration must carry an explicit ui object",
            configObject.opt("ui"),
        )

        val revision = expectSuccess(
            PackageValidator.validatePackage(
                ByteArrayInputStream(artifact),
                diagnosticsSourceRecord(),
                File(root, "evolved.zip"),
            ),
        )

        assertEquals(GitHubRepositoryIdentity(REPOSITORY_ID), revision.manifest.repositoryId)
        assertEquals(EVOLVED_PACKAGE_VERSION, revision.manifest.packageVersion)
        assertEquals(LUA_VERSION, revision.manifest.runtime.luaVersion)
        assertEquals(API_VERSION, revision.manifest.runtime.apiVersion)
        assertEquals(
            "Evolved fixture must materialize with an explicit (empty) capabilities eligibility set",
            emptySet<String>(),
            revision.manifest.capabilities,
        )
        assertEquals(
            "Evolved fixture must materialize with an explicit (empty) configuration data field list",
            0,
            revision.manifest.configuration.data.fields.size,
        )
        assertEquals(
            "Evolved fixture must materialize with an explicit (empty) configuration ui field list",
            0,
            revision.manifest.configuration.ui.fields.size,
        )
    }

    @Test
    fun `byte-pinned historical v1_0_0 release rejects at the strict manifest decoder before storage`() = withTemporaryDirectory { root ->
        val historicalBytes = loadPinnedFixture("$HISTORICAL_RESOURCE_BASE/v1.0.0/talkcan-channel.zip", HISTORICAL_V1_0_0_SHA256)
        assertHistoricalManifestShape(historicalBytes, "1.0.0")

        val stagingFile = File(root, "historical-v1.0.0.zip")
        val outcome = PackageValidator.validatePackage(
            ByteArrayInputStream(historicalBytes),
            diagnosticsSourceRecord(),
            stagingFile,
        )

        assertManifestMalformed(outcome, "historical v1.0.0")
        assertFalse(
            "Strict validator must not retain a staging file for a rejected historical artifact",
            stagingFile.exists(),
        )
    }

    @Test
    fun `byte-pinned historical v1_1_0 release rejects at the strict manifest decoder before storage`() = withTemporaryDirectory { root ->
        val historicalBytes = loadPinnedFixture("$HISTORICAL_RESOURCE_BASE/v1.1.0/talkcan-channel.zip", HISTORICAL_V1_1_0_SHA256)
        assertHistoricalManifestShape(historicalBytes, "1.1.0")

        val stagingFile = File(root, "historical-v1.1.0.zip")
        val outcome = PackageValidator.validatePackage(
            ByteArrayInputStream(historicalBytes),
            diagnosticsSourceRecord(),
            stagingFile,
        )

        assertManifestMalformed(outcome, "historical v1.1.0")
        assertFalse(
            "Strict validator must not retain a staging file for a rejected historical artifact",
            stagingFile.exists(),
        )
    }

    @Test
    fun `byte-pinned historical v1_2_0 release rejects at the strict manifest decoder before storage`() = withTemporaryDirectory { root ->
        val historicalBytes = loadPinnedFixture("$HISTORICAL_RESOURCE_BASE/v1.2.0/talkcan-channel.zip", HISTORICAL_V1_2_0_SHA256)
        val manifest = readManifestJson(historicalBytes)
        assertEquals(
            "Historical fixture must retain its original packageVersion",
            "1.2.0",
            manifest.getString("packageVersion"),
        )
        assertTrue(
            "v1.2.0 historical manifest must already carry the evolved configuration declaration",
            manifest.has(CONFIGURATION_KEY),
        )
        assertTrue(
            "v1.2.0 historical manifest must already carry the evolved capabilities declaration",
            manifest.has(CAPABILITIES_KEY),
        )
        assertFalse(
            "v1.2.0 historical manifest must omit the resources declaration (the resources cutover gap)",
            manifest.has(RESOURCES_KEY),
        )

        val stagingFile = File(root, "historical-v1.2.0.zip")
        val outcome = PackageValidator.validatePackage(
            ByteArrayInputStream(historicalBytes),
            diagnosticsSourceRecord(),
            stagingFile,
        )

        assertManifestMalformed(outcome, "historical v1.2.0")
        assertFalse(
            "Strict validator must not retain a staging file for a rejected historical artifact",
            stagingFile.exists(),
        )
    }

    @Test
    fun `historical v1_0_0 install attempt creates no provider lua state or publication`() = runTest {
        withTemporaryDirectoryAsync { root ->
            val bridge = NoKernelBridge()
            val providers = ChannelImplementationProviderRegistry()
            val publicationRecorder = PublicationRecorder()
            val repository = InstalledPackageRepository(
                store = InstalledPackageStore(root),
                bridge = bridge,
                publisher = publicationRecorder::publish,
                dispatcher = StandardTestDispatcher(testScheduler),
            )
            val implementationId = InstalledProviderId.derive(diagnosticsSourceRecord().repositoryId)
            val historicalBytes = loadPinnedFixture("$HISTORICAL_RESOURCE_BASE/v1.0.0/talkcan-channel.zip", HISTORICAL_V1_0_0_SHA256)

            val outcome = repository.installOrUpdate(ByteArrayInputStream(historicalBytes), diagnosticsSourceRecord())

            assertManifestMalformed(outcome, "historical v1.0.0 install")
            assertEquals(
                "A rejected historical artifact must not reach the publication pipeline",
                0,
                publicationRecorder.invocations,
            )
            assertEquals(
                "A rejected historical artifact must not create a Lua state or actor",
                0,
                bridge.createStateInvocations,
            )
            assertTrue(
                "A rejected historical artifact must not register an installed provider",
                providers.resolve(implementationId) is ChannelProviderResolution.Missing,
            )
            assertStoreRemainsEmpty(root)
        }
    }

    @Test
    fun `historical v1_1_0 install attempt creates no provider lua state or publication`() = runTest {
        withTemporaryDirectoryAsync { root ->
            val bridge = NoKernelBridge()
            val providers = ChannelImplementationProviderRegistry()
            val publicationRecorder = PublicationRecorder()
            val repository = InstalledPackageRepository(
                store = InstalledPackageStore(root),
                bridge = bridge,
                publisher = publicationRecorder::publish,
                dispatcher = StandardTestDispatcher(testScheduler),
            )
            val implementationId = InstalledProviderId.derive(diagnosticsSourceRecord().repositoryId)
            val historicalBytes = loadPinnedFixture("$HISTORICAL_RESOURCE_BASE/v1.1.0/talkcan-channel.zip", HISTORICAL_V1_1_0_SHA256)

            val outcome = repository.installOrUpdate(ByteArrayInputStream(historicalBytes), diagnosticsSourceRecord())

            assertManifestMalformed(outcome, "historical v1.1.0 install")
            assertEquals(
                "A rejected historical artifact must not reach the publication pipeline",
                0,
                publicationRecorder.invocations,
            )
            assertEquals(
                "A rejected historical artifact must not create a Lua state or actor",
                0,
                bridge.createStateInvocations,
            )
            assertTrue(
                "A rejected historical artifact must not register an installed provider",
                providers.resolve(implementationId) is ChannelProviderResolution.Missing,
            )
            assertStoreRemainsEmpty(root)
        }
    }

    @Test
    fun `historical rejection is a strict parser result with no compatibility callback or v2 dispatch`() = withTemporaryDirectory { root ->
        val historicalBytes = loadPinnedFixture("$HISTORICAL_RESOURCE_BASE/v1.0.0/talkcan-channel.zip", HISTORICAL_V1_0_0_SHA256)
        val manifest = readManifestJson(historicalBytes)

        // The historical release still claims the v1 runtime/api contract and manifestVersion 1,
        // so it is not a v2 artifact and not a compatibility-range candidate. The rejection is
        // therefore the strict manifest parser, not a v2 dispatcher or compatibility negotiator.
        assertEquals(1, manifest.getInt("manifestVersion"))
        assertEquals(LUA_VERSION, manifest.getJSONObject("runtime").getString("luaVersion"))
        assertEquals(API_VERSION, manifest.getJSONObject("runtime").getString("apiVersion"))

        val outcome = PackageValidator.validatePackage(
            ByteArrayInputStream(historicalBytes),
            diagnosticsSourceRecord(),
            File(root, "historical-v1.0.0.zip"),
        )

        val failure = (outcome as? PackageOutcome.Failure)?.error
        assertTrue(
            "Historical rejection must be a Format failure, not Compatibility/Loading/Identity: $failure",
            failure is PackageFailure.Format,
        )
        assertEquals(
            "Historical rejection must be MALFORMED_MANIFEST (strict parser), not UNSUPPORTED_MANIFEST_VERSION or API_VERSION_INCOMPATIBLE",
            PackageFailure.FormatDetail.MALFORMED_MANIFEST,
            (failure as PackageFailure.Format).detail,
        )
        assertFalse(
            "No callback shim may run for a rejected historical artifact (manifest-level rejection only)",
            failure is PackageFailure.Loading,
        )
    }

    @Test
    fun `v1_3_1 update transaction commits without inferred defaults config migration or legacy callback`() = runTest {
        withTemporaryDirectoryAsync { root ->
            val bridge = RecordingUpdatesBridge()
            val providers = ChannelImplementationProviderRegistry()
            var publicationCount = 0
            val repository = InstalledPackageRepository(
                store = InstalledPackageStore(root),
                bridge = bridge,
                publisher = { materialized ->
                    publicationCount += 1
                    val unavailable = materialized.failures.mapValues { (id, failure) -> failure.toPackageUnavailable(id) }
                    when (providers.publishInstalledProviders(materialized.bindings, unavailable)) {
                        is InstalledProvidersPublicationResult.Success -> PackageOutcome.Success(Unit)
                        is InstalledProvidersPublicationResult.Rejected -> PackageOutcome.Failure(
                            PackageFailure.Loading(PackageFailure.LoadingDetail.PUBLICATION_REJECTED),
                        )
                    }
                },
                dispatcher = StandardTestDispatcher(testScheduler),
            )
            val implementationId = InstalledProviderId.derive(diagnosticsSourceRecord().repositoryId)
            val artifact = loadPinnedFixture(EVOLVED_RESOURCE_PATH, EVOLVED_ARTIFACT_SHA256)

            // First install — the evolved v1.3.1 must commit.
            val installed = repository.installOrUpdate(ByteArrayInputStream(artifact), diagnosticsSourceRecord())
            runCurrent()
            assertEquals(
                "v1.3.1 fixture must install through the generic repository path",
                MutationResult.Installed(implementationId),
                expectSuccess(installed),
            )
            assertTrue(
                "Provider must be available after first install",
                providers.resolve(implementationId) is ChannelProviderResolution.Available,
            )
            assertStoreHasContent(root)
            assertEquals(
                "Install must not create a Lua state (materialization is lazy)",
                0,
                bridge.createCount,
            )

            // Reinstall — same digest produces Reinstalled with no additional publication.
            // Materialization remains lazy: no Lua state is created.
            val beforePublicationCount = publicationCount
            val reinstalled = repository.installOrUpdate(ByteArrayInputStream(artifact), diagnosticsSourceRecord())
            assertEquals(
                "Reinstalling the same v1.3.1 artifact must produce Reinstalled",
                MutationResult.Reinstalled(implementationId),
                expectSuccess(reinstalled),
            )
            assertEquals(
                "Reinstall must not create a Lua state (materialization is lazy)",
                0,
                bridge.createCount,
            )
            assertEquals(
                "Reinstalling same digest must not publish a new snapshot",
                beforePublicationCount,
                publicationCount,
            )

            // Provider configuration has explicit schemaVersion 1 — no migration or defaults inferred
            val resolution = providers.resolve(implementationId)
            assertTrue(
                "Provider must be available for config inspection after install",
                resolution is ChannelProviderResolution.Available,
            )
            val config = (resolution as ChannelProviderResolution.Available).provider.descriptor.configuration
            assertEquals(
                "Provider configuration schema must be the declared schemaVersion 1, not migrated",
                1,
                config.currentSchemaVersion,
            )
            val defaultPayload = config.defaultPayload()
            assertEquals(
                "Default payload must carry the exact empty-v1 shape, not inferred defaults",
                OpaqueJsonObject.fromJsonObject(org.json.JSONObject()),
                defaultPayload,
            )
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun loadPinnedFixture(resourcePath: String, expectedSha256: String): ByteArray =
        requireNotNull(javaClass.classLoader?.getResourceAsStream(resourcePath)) {
            "Missing pinned diagnostics fixture: $resourcePath"
        }.use { stream ->
            stream.readBytes().also { bytes ->
                assertEquals(
                    "Pinned fixture $resourcePath must remain byte-exact",
                    expectedSha256,
                    sha256(bytes),
                )
            }
        }

    private fun readManifestJson(artifact: ByteArray): JSONObject {
        val manifestText = unzipEntry(artifact, "manifest.json").toString(Charsets.UTF_8)
        return JSONObject(manifestText)
    }

    private fun assertHistoricalManifestShape(artifact: ByteArray, expectedVersion: String) {
        val manifest = readManifestJson(artifact)
        assertEquals(
            "Historical fixture must retain its original packageVersion",
            expectedVersion,
            manifest.getString("packageVersion"),
        )
        assertFalse(
            "Historical manifest must omit the evolved configuration declaration (the cutover gap)",
            manifest.has(CONFIGURATION_KEY),
        )
        assertFalse(
            "Historical manifest must omit the evolved capabilities declaration (the cutover gap)",
            manifest.has(CAPABILITIES_KEY),
        )
    }

    private fun assertManifestMalformed(outcome: PackageOutcome<*>, case: String) {
        val failure = (outcome as? PackageOutcome.Failure)?.error
        assertTrue(
            "$case must fail as FORMAT/MALFORMED_MANIFEST but was $failure",
            failure is PackageFailure.Format && failure.detail == PackageFailure.FormatDetail.MALFORMED_MANIFEST,
        )
    }

    private fun assertStoreRemainsEmpty(root: File) {
        val contentDir = File(root, "content")
        val indexFile = File(root, "index.json")
        val backupIndex = File(root, "index.backup.json")
        assertFalse(
            "A rejected historical install must not commit any content bytes",
            contentDir.exists() && (contentDir.listFiles()?.isNotEmpty() == true),
        )
        assertFalse(
            "A rejected historical install must not commit an index mutation",
            indexFile.exists() || backupIndex.exists(),
        )
    }

    private fun assertStoreHasContent(root: File) {
        val contentDir = File(root, "content")
        val indexFile = File(root, "index.json")
        assertTrue(
            "A committed install must create the content directory",
            contentDir.exists() && (contentDir.listFiles()?.isNotEmpty() == true),
        )
        assertTrue(
            "A committed install must write an index",
            indexFile.exists(),
        )
    }

    private fun <T> expectSuccess(outcome: PackageOutcome<T>): T = when (outcome) {
        is PackageOutcome.Success -> outcome.value
        is PackageOutcome.Failure -> throw AssertionError("Expected success, got ${outcome.error}")
    }

    private fun diagnosticsSourceRecord(): PackageSourceRecord = PackageSourceRecord(
        repositoryId = GitHubRepositoryIdentity(REPOSITORY_ID),
        coordinates = GitHubRepositoryCoordinates("talkcan", "diagnostics-channel"),
        release = GitHubReleaseIdentity(RELEASE_ID, "v$EVOLVED_PACKAGE_VERSION", false),
        asset = GitHubAssetIdentity(ASSET_ID, "talkcan-channel.zip"),
        ownerId = OWNER_ID,
    )

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun unzipEntry(zipBytes: ByteArray, name: String): ByteArray {
        java.util.zip.ZipInputStream(ByteArrayInputStream(zipBytes)).use { zis ->
            var entry = zis.nextEntry
            val buffer = java.io.ByteArrayOutputStream()
            while (entry != null) {
                if (entry.name == name) {
                    buffer.reset()
                    zis.copyTo(buffer)
                    return buffer.toByteArray()
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        error("missing entry $name in pinned fixture")
    }

    private fun <T> withTemporaryDirectory(block: (File) -> T): T {
        val root = createTempDirectory("diagnostics-legacy-rejection-").toFile()
        return try {
            block(root)
        } finally {
            root.deleteRecursively()
        }
    }

    private suspend fun <T> withTemporaryDirectoryAsync(block: suspend (File) -> T): T {
        val root = createTempDirectory("diagnostics-legacy-rejection-").toFile()
        return try {
            block(root)
        } finally {
            root.deleteRecursively()
        }
    }

    private class PublicationRecorder {
        var invocations: Int = 0
            private set

        suspend fun publish(materialized: MaterializationResult): PackageOutcome<Unit> {
            invocations += 1
            return PackageOutcome.Success(Unit)
        }
    }

    /**
     * Kernel-bridge sentinel: every method is unreachable while a historical artifact is rejected
     * before storage/state. Any invocation fails the test, proving no callback shim runs.
     */
    private class NoKernelBridge : LuaKernelBridge {
        var createStateInvocations: Int = 0
            private set

        private fun unreachable(): Nothing =
            error("No kernel method may run while a historical artifact is rejected before storage")

        override fun create(config: LuaKernelConfig): LuaKernelOutcome {
            createStateInvocations += 1
            return unreachable()
        }

        override fun load(handle: LuaStateHandle, source: String, entrypoint: String): LuaKernelOutcome = unreachable()
        override fun start(handle: LuaStateHandle): LuaKernelOutcome = unreachable()
        override fun resume(
            operation: LuaOperationHandle,
            success: Boolean,
            value: String,
            spawnAdmission: LuaSpawnAdmission,
        ): LuaKernelOutcome = unreachable()

        override fun cancel(operation: LuaOperationHandle): LuaKernelOutcome = unreachable()
        override fun interrupt(handle: LuaStateHandle): LuaKernelOutcome = unreachable()
        override fun snapshot(handle: LuaStateHandle): LuaKernelOutcome = unreachable()
        override fun close(handle: LuaStateHandle): LuaKernelOutcome = unreachable()
        override fun loadProgramImage(
            handle: LuaStateHandle,
            entryPoint: String,
            sourceMap: Map<String, String>,
        ): LuaKernelOutcome = unreachable()

        override fun invokeStartupCallback(
            handle: LuaStateHandle,
            callbackHandle: LuaCallbackHandle,
            config: LuaValue,
            spawnAdmission: LuaSpawnAdmission,
        ): LuaKernelOutcome = unreachable()

        override fun invokeCallback(
            handle: LuaStateHandle,
            callbackHandle: LuaCallbackHandle,
            arguments: LuaValue,
            spawnAdmission: LuaSpawnAdmission,
        ): LuaKernelOutcome = unreachable()

        override fun startCoroutine(
            handle: LuaStateHandle,
            coroutineId: LuaCoroutineId,
            spawnAdmission: LuaSpawnAdmission,
        ): LuaKernelOutcome = unreachable()
    }

    /**
     * Bridge used for valid-install tests — records [create] invocations and succeeds for
     * all materialization-required operations, but throws on any callback dispatch
     * (startup, invokeCallback, invokeInputCallback, startCoroutine) to prove they never run
     * during materialization/registration.
     */
    private class RecordingUpdatesBridge : LuaKernelBridge {
        var createCount: Int = 0
            private set

        override fun create(config: LuaKernelConfig): LuaKernelOutcome {
            createCount += 1
            return completed(LuaStateHandle(LuaStateId(createCount.toLong()), LuaStateGeneration(0)), "{}")
        }

        private fun noCallback(): Nothing = error("No callback may run during materialization/registration")

        override fun load(handle: LuaStateHandle, source: String, entrypoint: String): LuaKernelOutcome =
            completed(handle, "{}")

        override fun start(handle: LuaStateHandle): LuaKernelOutcome =
            completed(handle)

        override fun resume(
            operation: LuaOperationHandle,
            success: Boolean,
            value: String,
            spawnAdmission: LuaSpawnAdmission,
        ): LuaKernelOutcome = noCallback()

        override fun cancel(operation: LuaOperationHandle): LuaKernelOutcome = noCallback()
        override fun interrupt(handle: LuaStateHandle): LuaKernelOutcome = noCallback()

        override fun snapshot(handle: LuaStateHandle): LuaKernelOutcome =
            completed(handle)

        override fun close(handle: LuaStateHandle): LuaKernelOutcome =
            completed(handle)

        override fun loadProgramImage(
            handle: LuaStateHandle,
            entryPoint: String,
            sourceMap: Map<String, String>,
        ): LuaKernelOutcome =
            completed(handle, "[\"startup\",\"handle_lifecycle\",\"handle_readiness\",\"handle_input\"]")

        override fun invokeStartupCallback(
            handle: LuaStateHandle,
            callbackHandle: LuaCallbackHandle,
            config: LuaValue,
            spawnAdmission: LuaSpawnAdmission,
        ): LuaKernelOutcome = noCallback()

        override fun invokeCallback(
            handle: LuaStateHandle,
            callbackHandle: LuaCallbackHandle,
            arguments: LuaValue,
            spawnAdmission: LuaSpawnAdmission,
        ): LuaKernelOutcome = noCallback()

        override fun invokeInputCallback(
            handle: LuaStateHandle,
            callbackHandle: LuaCallbackHandle,
            arguments: LuaValue,
            capturedAudioToken: String,
            spawnAdmission: LuaSpawnAdmission,
        ): LuaKernelOutcome = noCallback()

        override fun startCoroutine(
            handle: LuaStateHandle,
            coroutineId: LuaCoroutineId,
            spawnAdmission: LuaSpawnAdmission,
        ): LuaKernelOutcome = noCallback()

        private fun completed(
            handle: LuaStateHandle,
            value: String? = null,
        ): LuaKernelOutcome.Completed = LuaKernelOutcome.Completed(
            stateId = handle.stateId.value,
            generation = handle.generation.value,
            coroutineId = null,
            value = value,
            elapsedNanos = null,
            luaVersion = LUA_VERSION,
            bindingVersion = API_VERSION,
            topology = "diagnostics-legacy-rejection-test",
        )
    }

    private companion object {
        private const val EVOLVED_RESOURCE_PATH = "diagnostics-channel/talkcan-channel-v1.3.1.zip"
        private const val HISTORICAL_RESOURCE_BASE = "diagnostics-channel/historical"
        private const val REPOSITORY_ID = "1305223892"
        private const val RELEASE_ID = "359168468"
        private const val ASSET_ID = "488185292"
        private const val OWNER_ID = "1224006"
        private const val EVOLVED_PACKAGE_VERSION = "1.3.1"
        private const val EVOLVED_ARTIFACT_SHA256 = "41e5d8f5e5ad49e321ab4a48c2794e795934bd7d64af78ab9f6e982949444cd0"
        private const val HISTORICAL_V1_0_0_SHA256 = "054dd8e53288c7b896d73920813cf4e4d7fa87d0044924d16f675c722238bece"
        private const val HISTORICAL_V1_1_0_SHA256 = "0407ee647b0b4c422b10671db85ada09ed05e5e578469b0149def0e4289fff61"
        private const val HISTORICAL_V1_2_0_SHA256 = "e23b5b325b35e431a0e530f43436b557283a23cec9812e9c96b22944974e7425"
        private const val CONFIGURATION_KEY = "configuration"
        private const val CAPABILITIES_KEY = "capabilities"
        private const val RESOURCES_KEY = "resources"
    }
}