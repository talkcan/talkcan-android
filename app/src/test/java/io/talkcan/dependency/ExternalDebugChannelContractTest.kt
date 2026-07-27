package io.talkcan.dependency

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
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile
import kotlin.io.path.createTempDirectory
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Exact-byte external Debug package gate. */
class ExternalDebugChannelContractTest {
    @Test
    fun `exact Debug archive validates identity source map and materializes without Lua state`() = withTemporaryDirectory { root ->
        val artifact = fixture()
        assertEquals(ARTIFACT_SHA256, sha256(artifact))
        assertEquals(ARTIFACT_SIZE, artifact.size)
        ZipFile(File(root, "debug-fixture.zip").also { it.writeBytes(artifact) }).use { zip ->
            assertEquals(setOf("manifest.json", "lua/plugin.lua"), zip.entries().asSequence().map { it.name }.toSet())
            assertTrue(zip.getEntry("manifest.json") != null)
            assertTrue(zip.getEntry("lua/plugin.lua") != null)
        }
        val bridge = CountingBridge()
        val revision = success(PackageValidator.validatePackage(
            ByteArrayInputStream(artifact), sourceRecord(), File(root, "staging.zip"),
        ))
        assertEquals(GitHubRepositoryIdentity(REPOSITORY_ID), revision.manifest.repositoryId)
        assertEquals("1.3.0", revision.manifest.packageVersion)
        assertEquals("plugin", revision.programImage.entryPoint)
        assertEquals(LUA_VERSION, revision.manifest.runtime.luaVersion)
        assertEquals(API_VERSION, revision.manifest.runtime.apiVersion)
        assertEquals(setOf("plugin"), revision.sourceMap.keys)
        assertEquals(ARTIFACT_SHA256, revision.digest.value)
        assertEquals(setOf("audio.transcription", "audio.synthesis", "audio.playback"), revision.manifest.capabilities)
        val mode = revision.manifest.configuration.data.fields.single()
        assertEquals("mode", mode.id)
        val implementationId = InstalledProviderId.derive(revision.manifest.repositoryId)
        assertFalse(implementationId.value.startsWith("builtin:"))
        val binding = LuaPackageMaterializer.materialize(revision, bridge)
        assertEquals(implementationId, binding.provider.descriptor.implementationId)
        assertEquals("Debug Channel", binding.provider.descriptor.presentation.label)
        assertEquals("External audio diagnostics channel", binding.provider.descriptor.presentation.summary)
        assertEquals(0, bridge.created)
        assertEquals(0, bridge.loads)
        assertEquals(0, bridge.invocations)
    }
    @Test
    fun `repository installation publishes provider without constructing Lua state`() = kotlinx.coroutines.test.runTest {
        withTemporaryDirectorySuspend { root ->
            val bridge = CountingBridge()
            val providers = io.talkcan.model.ChannelImplementationProviderRegistry()
            val repository = InstalledPackageRepository(
                store = InstalledPackageStore(root),
                bridge = bridge,
                publisher = { materialized ->
                    when (providers.publishInstalledProviders(materialized.bindings, materialized.failures.mapValues { (id, failure) -> failure.toPackageUnavailable(id) })) {
                        is io.talkcan.model.InstalledProvidersPublicationResult.Success -> PackageOutcome.Success(Unit)
                        is io.talkcan.model.InstalledProvidersPublicationResult.Rejected -> PackageOutcome.Failure(PackageFailure.Loading(PackageFailure.LoadingDetail.PUBLICATION_REJECTED))
                    }
                },
                dispatcher = kotlinx.coroutines.test.StandardTestDispatcher(testScheduler),
            )
            val id = InstalledProviderId.derive(sourceRecord().repositoryId)
            assertEquals(MutationResult.Installed(id), success(repository.installOrUpdate(ByteArrayInputStream(fixture()), sourceRecord())))
            assertTrue(providers.resolve(id) is io.talkcan.model.ChannelProviderResolution.Available)
            assertEquals(0, bridge.created)
            assertEquals(0, bridge.loads)
            repository.requestClose()
        }
    }

    @Test
    fun `historical releases remain byte-exact with their revision-specific resource shape`() = withTemporaryDirectory { root ->
        val cases = listOf(
            Triple(HISTORICAL_V1_0_0_PATH, HISTORICAL_V1_0_0_SHA256, "1.0.0"),
            Triple(HISTORICAL_V1_1_0_PATH, HISTORICAL_V1_1_0_SHA256, "1.1.0"),
            Triple(HISTORICAL_V1_2_0_PATH, HISTORICAL_V1_2_0_SHA256, "1.2.0"),
        )
        cases.forEachIndexed { i, (path, expectedSha, expectedVersion) ->
            val bytes = requireNotNull(javaClass.classLoader?.getResourceAsStream(path)) {
                "Missing preserved historical debug fixture: $path"
            }.use { it.readBytes() }
            assertEquals(expectedSha, sha256(bytes))
            ZipFile(File(root, "historical-$i.zip").also { it.writeBytes(bytes) }).use { zip ->
                val manifest = JSONObject(String(zip.getInputStream(zip.getEntry("manifest.json")).readBytes()))
                assertEquals(expectedVersion, manifest.getString("packageVersion"))
                assertEquals("Historical resource shape must remain byte-exact", expectedVersion == "1.2.0", manifest.has("resources"))
            }
        }
    }


    private fun fixture(): ByteArray = requireNotNull(javaClass.classLoader?.getResourceAsStream(RESOURCE_PATH))
        .use { it.readBytes() }

    private fun sourceRecord() = PackageSourceRecord(
        repositoryId = GitHubRepositoryIdentity(REPOSITORY_ID),
        coordinates = GitHubRepositoryCoordinates("talkcan", "debug-channel"),
        release = GitHubReleaseIdentity(RELEASE_ID, "v1.3.0", false),
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
        val root = createTempDirectory("external-debug-contract-").toFile()
        return try { block(root) } finally { root.deleteRecursively() }
    }
    private suspend fun <T> withTemporaryDirectorySuspend(block: suspend (File) -> T): T {
        val root = createTempDirectory("external-debug-contract-").toFile()
        return try { block(root) } finally { root.deleteRecursively() }
    }

    private class CountingBridge : LuaKernelBridge {
        var created = 0
        var loads = 0
        var invocations = 0
        override fun create(config: LuaKernelConfig): LuaKernelOutcome {
            created += 1
            return LuaKernelOutcome.Created(created.toLong(), created.toLong(), LUA_VERSION, API_VERSION, "test")
        }
        override fun load(handle: LuaStateHandle, source: String, entrypoint: String): LuaKernelOutcome { loads += 1; return complete(handle) }
        override fun start(handle: LuaStateHandle): LuaKernelOutcome = complete(handle)
        override fun resume(operation: LuaOperationHandle, success: Boolean, value: String, spawnAdmission: LuaSpawnAdmission): LuaKernelOutcome = complete(operation.stateHandle)
        override fun cancel(operation: LuaOperationHandle): LuaKernelOutcome = complete(operation.stateHandle)
        override fun interrupt(handle: LuaStateHandle): LuaKernelOutcome = complete(handle)
        override fun snapshot(handle: LuaStateHandle): LuaKernelOutcome = LuaKernelOutcome.Snapshot(handle.stateId.value, handle.generation.value, null, LUA_VERSION, API_VERSION, "test")
        override fun close(handle: LuaStateHandle): LuaKernelOutcome = LuaKernelOutcome.Closed(handle.stateId.value, handle.generation.value)
        override fun loadProgramImage(handle: LuaStateHandle, entryPoint: String, sourceMap: Map<String, String>): LuaKernelOutcome = complete(handle, "[\"startup\",\"handle_readiness\",\"handle_input\"]")
        override fun invokeStartupCallback(handle: LuaStateHandle, callbackHandle: LuaCallbackHandle, config: LuaValue, spawnAdmission: LuaSpawnAdmission): LuaKernelOutcome { invocations += 1; return complete(handle) }
        override fun invokeCallback(handle: LuaStateHandle, callbackHandle: LuaCallbackHandle, arguments: LuaValue, spawnAdmission: LuaSpawnAdmission): LuaKernelOutcome { invocations += 1; return complete(handle, "{\"ready\":false}") }
        override fun startCoroutine(handle: LuaStateHandle, coroutineId: LuaCoroutineId, spawnAdmission: LuaSpawnAdmission): LuaKernelOutcome = complete(handle)
        private fun complete(handle: LuaStateHandle, value: String? = null) = LuaKernelOutcome.Completed(handle.stateId.value, handle.generation.value, null, value, null, LUA_VERSION, API_VERSION, "test")
    }

    private companion object {
        const val RESOURCE_PATH = "debug-channel/talkcan-channel.zip"
        const val REPOSITORY_ID = "1306065111"
        const val RELEASE_ID = "359174403"
        const val ASSET_ID = "488198107"
        const val OFFICIAL_OWNER_ID = "1224006"
        const val ARTIFACT_SIZE = 7184
        const val ARTIFACT_SHA256 = "46b111d37c808c0f426a3d72f54558a24fac778dfe9f889682b37b0d4271b6bb"
        const val HISTORICAL_V1_0_0_PATH = "debug-channel/historical/v1.0.0/talkcan-channel.zip"
        const val HISTORICAL_V1_0_0_SHA256 = "f90c8c073378659acac1fbb63f100e1d1b180d69b05a154ecefc3cd17887b76a"
        const val HISTORICAL_V1_1_0_PATH = "debug-channel/historical/v1.1.0/talkcan-channel.zip"
        const val HISTORICAL_V1_1_0_SHA256 = "464293ebdf2dade6a0577b4e99f52293faf80d3deebce84db7ae041143dd7342"
        const val HISTORICAL_V1_2_0_PATH = "debug-channel/historical/v1.2.0/talkcan-channel.zip"
        const val HISTORICAL_V1_2_0_SHA256 = "2ae022f3c5854a449ad7cbafd6fe8341a52e1bec29a991c9683f02add77aea74"
    }
}
