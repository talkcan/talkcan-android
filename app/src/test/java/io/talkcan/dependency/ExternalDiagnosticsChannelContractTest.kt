package io.talkcan.dependency

import io.talkcan.lua.LUA_VERSION
import io.talkcan.lua.API_VERSION
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
import kotlin.io.path.createTempDirectory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Contract fixture for the externally built diagnostics channel. Its byte digest pins the
 * release artifact, while validation and materialization run only the ordinary installed-Lua
 * package path; it is deliberately not a built-in channel or a test-only provider registration.
 */
class ExternalDiagnosticsChannelContractTest {
    @Test
    fun `external diagnostics v1 artifact validates source-only and materializes through the generic installed Lua provider`() = withTemporaryDirectory { root ->
        val artifact = requireNotNull(javaClass.classLoader?.getResourceAsStream(RESOURCE_PATH)) {
            "Missing diagnostics channel fixture: $RESOURCE_PATH"
        }.use { it.readBytes() }

        assertEquals(ARTIFACT_SHA256, sha256(artifact))
        assertEquals(ARTIFACT_SIZE, artifact.size)

        val revision = success(
            PackageValidator.validatePackage(
                ByteArrayInputStream(artifact),
                sourceRecord(),
                File(root, "diagnostics-channel.zip"),
            ),
        )

        assertEquals(GitHubRepositoryIdentity(REPOSITORY_ID), revision.manifest.repositoryId)
        assertEquals("1.3.1", revision.manifest.packageVersion)
        assertEquals("plugin", revision.programImage.entryPoint)
        assertEquals(LUA_VERSION, revision.manifest.runtime.luaVersion)
        assertEquals(API_VERSION, revision.manifest.runtime.apiVersion)
        assertEquals(setOf("plugin"), revision.sourceMap.keys)
        assertEquals(ARTIFACT_SHA256, revision.digest.value)

        val implementationId = InstalledProviderId.derive(revision.manifest.repositoryId)
        assertEquals("github-repository:$REPOSITORY_ID", implementationId.value)
        assertFalse(
            "An external package must not become a diagnostics-specific built-in before publication.",
            implementationId.value.startsWith("builtin:"),
        )

        val bridge = UnusedBridge()
        val binding = LuaPackageMaterializer.materialize(revision, bridge)
        assertEquals(revision.manifest.repositoryId, binding.repositoryId)
        assertEquals(revision.digest, binding.expectedDigest)
        assertEquals(implementationId, binding.provider.descriptor.implementationId)
        assertEquals("Diagnostics Channel", binding.provider.descriptor.presentation.label)
        assertEquals(
            "External diagnostics package backplane",
            binding.provider.descriptor.presentation.summary,
        )
        assertEquals(0, bridge.calls)
    }

    private fun sourceRecord(): PackageSourceRecord = PackageSourceRecord(
        repositoryId = GitHubRepositoryIdentity(REPOSITORY_ID),
        coordinates = GitHubRepositoryCoordinates("talkcan-channels", "diagnostics"),
        release = GitHubReleaseIdentity(RELEASE_ID, "v1.3.1", false),
        asset = GitHubAssetIdentity(ASSET_ID, "talkcan-channel.zip"),
        ownerId = OFFICIAL_OWNER_ID,
    )

    private fun <T> success(outcome: PackageOutcome<T>): T = when (outcome) {
        is PackageOutcome.Success -> outcome.value
        is PackageOutcome.Failure -> throw AssertionError("Expected artifact validation success, got ${outcome.error}")
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun <T> withTemporaryDirectory(block: (File) -> T): T {
        val root = createTempDirectory("external-diagnostics-channel-").toFile()
        return try {
            block(root)
        } finally {
            root.deleteRecursively()
        }
    }

    /** Materialization must remain lazy: no kernel callback may run before a runtime is selected. */
    private class UnusedBridge : LuaKernelBridge {
        var calls: Int = 0
            private set

        private fun unused(): Nothing {
            calls += 1
            error("Package materialization must not enter the Lua kernel")
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
        private const val RESOURCE_PATH = "diagnostics-channel/talkcan-channel-v1.3.1.zip"
        private const val REPOSITORY_ID = "1313912734"
        private const val RELEASE_ID = "359168468"
        private const val ASSET_ID = "488185292"
        private const val OFFICIAL_OWNER_ID = "1224006"
        private const val ARTIFACT_SIZE = 5230
        private const val ARTIFACT_SHA256 = "7a1f9363f5b7784fa238b334d5eb5a8815ed5fe2f742bf15b6db284dece525d2"
    }
}
