package io.talkcan.service

import io.talkcan.dependency.GitHubClientBounds
import io.talkcan.dependency.GitHubPackageSourceClient
import io.talkcan.dependency.GitHubPublishedRelease
import io.talkcan.dependency.GitHubReleaseAsset
import io.talkcan.dependency.GitHubRepositoryCoordinates
import io.talkcan.dependency.GitHubRepositoryIdentity
import io.talkcan.dependency.GitHubRepositoryOwner
import io.talkcan.dependency.GitHubResolvedRepository
import io.talkcan.dependency.GitHubSourceConfiguration
import io.talkcan.dependency.GitHubSourceFailure
import io.talkcan.dependency.GitHubSourceOutcome
import io.talkcan.lua.API_VERSION
import io.talkcan.lua.LUA_VERSION
import io.talkcan.lua.NoOpPluginLogSink
import io.talkcan.lua.LuaCallbackHandle
import io.talkcan.lua.LuaCoroutineId
import io.talkcan.lua.LuaKernelBridge
import io.talkcan.lua.LuaKernelConfig
import io.talkcan.lua.LuaKernelOutcome
import io.talkcan.lua.LuaOperationHandle
import io.talkcan.lua.LuaSpawnAdmission
import io.talkcan.lua.LuaStateHandle
import io.talkcan.lua.LuaValue
import io.talkcan.model.ChannelImplementationProviderRegistry
import java.io.File
import java.io.OutputStream
import java.nio.charset.StandardCharsets.UTF_8
import java.util.zip.CRC32
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 5.5: Trust confirmation must disclose profile/resolver/work authority and
 * the secrets.read + network.http exfiltration warning before activation,
 * sourced from validated manifest metadata only (no Lua execution).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PackageTrustDisclosureTest {

    @Test
    fun `trust confirmation discloses profile resolver and work authority`() = runTest {
        val directory = createTempDirectory("package-trust-disclosure-").toFile()
        try {
            val archive = disclosureArchive(repositoryId = "777")
            val source = SingleReleaseSourceClient(
                repository = repository("777", "4242", "Disclosure-Owner", "Disclosure-Repository"),
                release = releaseAsset("1", "1", archive.size.toLong()),
                archive = archive,
            )
            val harness = harness(directory, source, archive.size.toLong())
            try {
                harness.management.resolveRepository("https://github.com/disclosure-owner/disclosure-repository")
                advanceUntilIdle()

                val selection = harness.management.managementState.value.state
                    as? PackageManagementState.AwaitingSelection
                    ?: throw AssertionError(
                        "Expected inspected candidates, got ${harness.management.managementState.value.state}"
                    )
                assertEquals(listOf("1"), selection.candidates.map { it.release.releaseId })

                assertEquals(GitHubSourceOutcome.Success(Unit), harness.management.selectRelease("1"))
                val trust = harness.management.managementState.value.state
                    as? PackageManagementState.AwaitingTrust
                    ?: throw AssertionError(
                        "Expected trust confirmation, got ${harness.management.managementState.value.state}"
                    )
                val confirmation = trust.confirmation

                // Combined exfiltration authority flags.
                assertTrue(confirmation.declaresNetworkHttp)
                assertTrue(confirmation.declaresSecretsRead)

                // Complete capability disclosure in stable canonical order.
                assertEquals(
                    listOf("network.http", "profiles.read", "secrets.read", "work.queue"),
                    confirmation.capabilities,
                )

                // Bounded profile-type disclosure with secret presence.
                assertEquals(
                    listOf(
                        PackageConfirmationProfileType(
                            typeId = "openai_compatible",
                            label = "OpenAI Compatible",
                            declaresSecretFields = true,
                        )
                    ),
                    confirmation.profileTypes,
                )

                // Resolver presence with requested capability subset.
                assertEquals(
                    listOf(
                        PackageConfirmationResolver(
                            resolverId = "models",
                            capabilities = listOf("network.http", "profiles.read", "secrets.read"),
                        )
                    ),
                    confirmation.choiceResolvers,
                )

                // Work queue IDs.
                assertEquals(listOf("turns"), confirmation.workQueues)
            } finally {
                harness.management.shutdown()
                harness.installed.shutdown()
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `empty declarations remain explicit in the confirmation model`() = runTest {
        val directory = createTempDirectory("package-trust-disclosure-empty-").toFile()
        try {
            val archive = emptyDeclarationArchive(repositoryId = "888")
            val source = SingleReleaseSourceClient(
                repository = repository("888", "4242", "Empty-Owner", "Empty-Repository"),
                release = releaseAsset("1", "1", archive.size.toLong()),
                archive = archive,
            )
            val harness = harness(directory, source, archive.size.toLong())
            try {
                harness.management.resolveRepository("https://github.com/empty-owner/empty-repository")
                advanceUntilIdle()
                assertEquals(GitHubSourceOutcome.Success(Unit), harness.management.selectRelease("1"))
                val trust = harness.management.managementState.value.state
                    as? PackageManagementState.AwaitingTrust
                    ?: throw AssertionError(
                        "Expected trust confirmation, got ${harness.management.managementState.value.state}"
                    )
                val confirmation = trust.confirmation
                assertEquals(emptyList<String>(), confirmation.capabilities)
                assertEquals(emptyList<PackageConfirmationProfileType>(), confirmation.profileTypes)
                assertEquals(emptyList<PackageConfirmationResolver>(), confirmation.choiceResolvers)
                assertEquals(emptyList<String>(), confirmation.workQueues)
                assertEquals(false, confirmation.declaresNetworkHttp)
                assertEquals(false, confirmation.declaresSecretsRead)
            } finally {
                harness.management.shutdown()
                harness.installed.shutdown()
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // Harness
    // ──────────────────────────────────────────────────────────────────────

    private fun TestScope.harness(
        root: File,
        source: GitHubPackageSourceClient,
        assetBytes: Long,
    ): Harness {
        val providers = ChannelImplementationProviderRegistry()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val installed = InstalledPackagesCoordinator(
            storeRoot = root,
            providerRegistry = providers,
            bridge = NoopLuaKernelBridge,
            logSink = NoOpPluginLogSink,
            onCatalogueReconcile = {},
            serviceScope = this,
            ioDispatcher = dispatcher,
        )
        val facade = InstalledPackagesFacade(installed)
        return Harness(
            management = PackageManagementCoordinator(
                facade = facade,
                sourceClient = source,
                providerRegistry = providers,
                storeRoot = root,
                serviceScope = this,
                ioDispatcher = dispatcher,
                clientBounds = GitHubClientBounds(
                    maxUrlBytes = 512,
                    maxMetadataResponseBytes = 1_024,
                    maxReleaseCandidates = 10,
                    maxRedirects = 1,
                    maxExactAssetBytes = assetBytes,
                    maxInspectionFiles = 4,
                    operationDurationSeconds = 30,
                    maxRetainedFailureDetailBytes = 128,
                ),
            ),
            installed = installed,
        )
    }

    private data class Harness(
        val management: PackageManagementCoordinator,
        val installed: InstalledPackagesCoordinator,
    )

    private fun repository(id: String, ownerId: String, owner: String, name: String): GitHubResolvedRepository =
        GitHubResolvedRepository(
            id = GitHubRepositoryIdentity(id),
            fullName = "$owner/$name",
            archived = false,
            disabled = false,
            visibility = "public",
            owner = GitHubRepositoryOwner(ownerId, owner, "Organization"),
        )

    private fun releaseAsset(releaseId: String, assetId: String, size: Long): Pair<GitHubPublishedRelease, GitHubReleaseAsset> =
        GitHubPublishedRelease(releaseId, "v$releaseId", null, false, false, 1_700_000_000L + releaseId.toLong()) to
            GitHubReleaseAsset(assetId, GitHubSourceConfiguration.CANONICAL_ASSET_NAME, "uploaded", "application/zip", size, "https://downloads.example/$assetId")

    private class SingleReleaseSourceClient(
        private val repository: GitHubResolvedRepository,
        private val release: Pair<GitHubPublishedRelease, GitHubReleaseAsset>,
        private val archive: ByteArray,
    ) : GitHubPackageSourceClient {
        override suspend fun resolveRepository(coordinates: GitHubRepositoryCoordinates): GitHubSourceOutcome<GitHubResolvedRepository> =
            GitHubSourceOutcome.Success(repository)

        override suspend fun listStableReleaseAssets(coordinates: GitHubRepositoryCoordinates): GitHubSourceOutcome<List<Pair<GitHubPublishedRelease, GitHubReleaseAsset>>> =
            GitHubSourceOutcome.Success(listOf(release))

        override suspend fun downloadAsset(
            coordinates: GitHubRepositoryCoordinates,
            asset: GitHubReleaseAsset,
            destination: OutputStream,
        ): GitHubSourceOutcome<Unit> {
            destination.write(archive)
            return GitHubSourceOutcome.Success(Unit)
        }
    }

    private fun disclosureArchive(repositoryId: String): ByteArray {
        val manifest = """
            {
              "manifestVersion": 1,
              "repositoryId": "$repositoryId",
              "packageVersion": "1.0.0",
              "entryModule": "plugin",
              "presentation": {"label": "Disclosure package", "summary": "Authority disclosure fixture"},
              "runtime": {"luaVersion": "$LUA_VERSION", "apiVersion": "$API_VERSION"},
              "configuration": {"schemaVersion": 1, "data": {"fields": [], "additionalProperties": false}, "ui": {"fields": []}},
              "resources": {"mounts": []},
              "profileTypes": [
                {
                  "id": "openai_compatible",
                  "label": "OpenAI Compatible",
                  "schemaVersion": 1,
                  "data": {"additionalProperties": false, "fields": [
                    {"id": "base_url", "type": "string", "required": true},
                    {"id": "api_key", "type": "secret", "required": true}
                  ]},
                  "ui": {"fields": [
                    {"field": "base_url", "control": "text", "label": "Base URL"},
                    {"field": "api_key", "control": "secret", "label": "API Key"}
                  ]}
                }
              ],
              "choiceResolvers": [
                {"id": "models", "module": "resolver", "capabilities": ["network.http", "profiles.read", "secrets.read"]}
              ],
              "workQueues": ["turns"],
              "capabilities": ["network.http", "profiles.read", "secrets.read", "work.queue"]
            }
        """.trimIndent()
        return strictUnixStoredZip(
            listOf(
                ZipFixtureEntry("manifest.json", manifest.toByteArray(UTF_8), 0b1000000110100100),
                ZipFixtureEntry("lua/", ByteArray(0), 0b0100000111101101),
                ZipFixtureEntry("lua/plugin.lua", "return { startup = function() end }".toByteArray(UTF_8), 0b1000000110100100),
                ZipFixtureEntry("lua/resolver.lua", "return { resolve = function() return {} end }".toByteArray(UTF_8), 0b1000000110100100),
            ),
        )
    }

    private fun emptyDeclarationArchive(repositoryId: String): ByteArray {
        val manifest = """
            {
              "manifestVersion": 1,
              "repositoryId": "$repositoryId",
              "packageVersion": "1.0.0",
              "entryModule": "plugin",
              "presentation": {"label": "Empty declaration package", "summary": "Explicit empty declarations"},
              "runtime": {"luaVersion": "$LUA_VERSION", "apiVersion": "$API_VERSION"},
              "configuration": {"schemaVersion": 1, "data": {"fields": [], "additionalProperties": false}, "ui": {"fields": []}},
              "resources": {"mounts": []},
              "profileTypes": [],
              "choiceResolvers": [],
              "workQueues": [],
              "capabilities": []
            }
        """.trimIndent()
        return strictUnixStoredZip(
            listOf(
                ZipFixtureEntry("manifest.json", manifest.toByteArray(UTF_8), 0b1000000110100100),
                ZipFixtureEntry("lua/", ByteArray(0), 0b0100000111101101),
                ZipFixtureEntry("lua/plugin.lua", "return { startup = function() end }".toByteArray(UTF_8), 0b1000000110100100),
            ),
        )
    }

    private fun strictUnixStoredZip(entries: List<ZipFixtureEntry>): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val central = entries.map { entry ->
            val name = entry.name.toByteArray(UTF_8)
            val crc = CRC32().apply { update(entry.bytes) }.value
            val offset = output.size().toLong()
            output.u32(0x04034b50)
            output.u16(20)
            output.u16(0)
            output.u16(0)
            output.u16(0)
            output.u16(0)
            output.u32(crc)
            output.u32(entry.bytes.size.toLong())
            output.u32(entry.bytes.size.toLong())
            output.u16(name.size)
            output.u16(0)
            output.write(name)
            output.write(entry.bytes)
            CentralFixtureEntry(entry, name, crc, offset)
        }
        val centralOffset = output.size().toLong()
        central.forEach { item ->
            output.u32(0x02014b50)
            output.u16((3 shl 8) or 20)
            output.u16(20)
            output.u16(0)
            output.u16(0)
            output.u16(0)
            output.u16(0)
            output.u32(item.crc)
            output.u32(item.entry.bytes.size.toLong())
            output.u32(item.entry.bytes.size.toLong())
            output.u16(item.name.size)
            output.u16(0)
            output.u16(0)
            output.u16(0)
            output.u16(0)
            output.u32(item.entry.unixMode.toLong() shl 16)
            output.u32(item.offset)
            output.write(item.name)
        }
        val centralSize = output.size().toLong() - centralOffset
        output.u32(0x06054b50)
        output.u16(0)
        output.u16(0)
        output.u16(entries.size)
        output.u16(entries.size)
        output.u32(centralSize)
        output.u32(centralOffset)
        output.u16(0)
        return output.toByteArray()
    }

    private fun java.io.ByteArrayOutputStream.u16(value: Int) {
        write(value and 0xff)
        write((value ushr 8) and 0xff)
    }

    private fun java.io.ByteArrayOutputStream.u32(value: Long) {
        write((value and 0xff).toInt())
        write(((value ushr 8) and 0xff).toInt())
        write(((value ushr 16) and 0xff).toInt())
        write(((value ushr 24) and 0xff).toInt())
    }

    private data class ZipFixtureEntry(val name: String, val bytes: ByteArray, val unixMode: Int)
    private data class CentralFixtureEntry(val entry: ZipFixtureEntry, val name: ByteArray, val crc: Long, val offset: Long)

    private object NoopLuaKernelBridge : LuaKernelBridge {
        override fun create(config: LuaKernelConfig): LuaKernelOutcome = error("materialization must not create Lua states")
        override fun load(handle: LuaStateHandle, source: String, entrypoint: String): LuaKernelOutcome = error("not used")
        override fun start(handle: LuaStateHandle): LuaKernelOutcome = error("not used")
        override fun resume(operation: LuaOperationHandle, success: Boolean, value: String, spawnAdmission: LuaSpawnAdmission): LuaKernelOutcome = error("not used")
        override fun cancel(operation: LuaOperationHandle): LuaKernelOutcome = error("not used")
        override fun interrupt(handle: LuaStateHandle): LuaKernelOutcome = error("not used")
        override fun snapshot(handle: LuaStateHandle): LuaKernelOutcome = error("not used")
        override fun close(handle: LuaStateHandle): LuaKernelOutcome = error("not used")
        override fun loadProgramImage(handle: LuaStateHandle, entryPoint: String, sourceMap: Map<String, String>): LuaKernelOutcome = error("not used")
        override fun invokeStartupCallback(handle: LuaStateHandle, callbackHandle: LuaCallbackHandle, config: LuaValue, spawnAdmission: LuaSpawnAdmission): LuaKernelOutcome = error("not used")
        override fun invokeCallback(handle: LuaStateHandle, callbackHandle: LuaCallbackHandle, arguments: LuaValue, spawnAdmission: LuaSpawnAdmission): LuaKernelOutcome = error("not used")
        override fun startCoroutine(handle: LuaStateHandle, coroutineId: LuaCoroutineId, spawnAdmission: LuaSpawnAdmission): LuaKernelOutcome = error("not used")
    }
}
