package io.talkcan.service

import io.talkcan.dependency.GitHubClientBounds
import io.talkcan.dependency.GitHubPackageSourceClient
import io.talkcan.dependency.GitHubPublishedRelease
import io.talkcan.dependency.GitHubPublisherTier
import io.talkcan.dependency.GitHubReleaseAsset
import io.talkcan.dependency.GitHubRepositoryCoordinates
import io.talkcan.dependency.GitHubRepositoryIdentity
import io.talkcan.dependency.GitHubRepositoryOwner
import io.talkcan.dependency.GitHubResolvedRepository
import io.talkcan.dependency.ArtifactDigest
import io.talkcan.dependency.GitHubSourceConfiguration
import io.talkcan.dependency.GitHubSourceFailure
import io.talkcan.dependency.GitHubSourceOutcome
import io.talkcan.dependency.InstalledProviderId
import io.talkcan.dependency.MutationResult
import io.talkcan.dependency.PackageFailure
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Public-host transaction coverage for package management. Source responses are deterministic
 * fakes, while mutations use the real facade and durable package store. The assertions therefore
 * defend the coordinator's externally visible state and committed bytes, not its private staging.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PackageManagementCoordinatorTest {
    @Test
    fun `bounded sequential inspection keeps an older compatible sibling canonicalizes coordinates and installs its exact bytes`() = runTest {
        withTemporaryDirectory { root ->
            val archive = packageArchive(repositoryId = "101", version = "1.0.0", marker = "exact-staged-byte")
            val incompatible = packageArchive(repositoryId = "101", version = "0.9.0", marker = "incompatible", luaVersion = "0.0")
            val source = RecordingSourceClient(
                repository = repository("101", "9000001", "Canonical-Owner", "Canonical-Repository"),
                releases = listOf(
                    releaseAsset("5", "15", archive.size.toLong() + 1),
                    releaseAsset("4", "14", incompatible.size.toLong()),
                    releaseAsset("3", "13", 7),
                    releaseAsset("2", "12", archive.size.toLong()),
                    releaseAsset("1", "11", archive.size.toLong()),
                ),
                archives = mapOf("14" to incompatible, "13" to "invalid".toByteArray(UTF_8), "12" to archive, "11" to archive),
            )
            val harness = harness(root, source, clientBounds = bounds(maxInspectionFiles = 4, maxExactAssetBytes = maxOf(archive.size, incompatible.size).toLong()))
            try {
                harness.management.resolveRepository("https://github.com/submitted-owner/submitted-repository")
                advanceUntilIdle()

                val awaiting = harness.management.managementState.value.state as? PackageManagementState.AwaitingSelection
                    ?: throw AssertionError("Expected inspected candidates, got ${harness.management.managementState.value.state}")
                assertEquals(listOf("2"), awaiting.candidates.map { it.release.releaseId })
                assertEquals(
                    listOf("14", "13", "12"),
                    source.downloads.map { it.asset.assetId },
                )
                assertTrue(source.downloads.all { it.coordinates == GitHubRepositoryCoordinates("Canonical-Owner", "Canonical-Repository") })
                assertEquals(
                    listOf(
                        GitHubSourceFailure.BoundsExceeded,
                        GitHubSourceFailure.Compatibility(PackageFailure.CompatibilityDetail.LUA_VERSION_INCOMPATIBLE),
                        GitHubSourceFailure.Format(PackageFailure.FormatDetail.INVALID_ZIP),
                    ),
                    awaiting.ineligible.map { it.reason },
                )

                assertEquals(GitHubSourceOutcome.Success(Unit), harness.management.selectRelease("2"))
                val trust = awaitingTrust(harness.management)
                assertEquals("https://github.com/Canonical-Owner/Canonical-Repository", trust.confirmation.canonicalRepositoryUrl)
                assertEquals(GitHubPublisherTier.COMMUNITY, trust.tier)
                assertEquals("Coordinator package", trust.confirmation.packageLabel)
                assertEquals(false, trust.confirmation.declaresNetworkHttp)
                assertEquals(false, trust.confirmation.declaresSecretsRead)
                assertTrue(trust.confirmation.operationGeneration > 0)
                assertFalse("public state must not expose the private staging pathname", harness.management.managementState.value.toString().contains("inspect_staging"))
                assertFalse("public state must not expose staged archive source", harness.management.managementState.value.toString().contains("exact-staged-byte"))

                assertEquals(GitHubSourceOutcome.Success(MutationResult.Installed(InstalledProviderId.derive(GitHubRepositoryIdentity("101")))), harness.management.confirmTrustAndInstall(true))
                val committed = File(root, "content/sha256/${trust.confirmation.inspectedDigest.value}")
                assertTrue("confirmation must hand the already-inspected artifact to the facade", committed.readBytes().contentEquals(archive))
                val summary = harness.management.managementState.value.installedPackages.single()
                assertEquals("Canonical-Owner", summary.canonicalOwner)
                assertEquals("Canonical-Repository", summary.canonicalRepository)
                assertEquals(GitHubPublisherTier.COMMUNITY, summary.trustTier)
            } finally {
                harness.shutdown()
            }
        }
    }

    @Test
    fun `official and community confirmation require current acknowledgement and identical inspections get new generations`() = runTest {
        withTemporaryDirectory { root ->
            val archive = packageArchive("102", "1.0.0", "generation")
            val official = RecordingSourceClient(
                repository = repository("102", GitHubSourceConfiguration.OFFICIAL_PUBLISHER_ID, "official-owner", "official-repository"),
                releases = listOf(releaseAsset("20", "120", archive.size.toLong())),
                archives = mapOf("120" to archive),
            )
            val harness = harness(root, official, clientBounds = bounds(maxInspectionFiles = 1, maxExactAssetBytes = archive.size.toLong()))
            try {
                harness.management.resolveRepository("https://github.com/ignored/ignored")
                advanceUntilIdle()
                assertEquals(GitHubSourceOutcome.Success(Unit), harness.management.selectRelease("20"))
                val first = awaitingTrust(harness.management)
                assertEquals(GitHubPublisherTier.OFFICIAL, first.tier)
                assertEquals(GitHubSourceOutcome.Failure(GitHubSourceFailure.TrustRefused), harness.management.confirmTrustAndInstall(false))
                assertTrue("refusing confirmation must not mutate committed state", harness.management.managementState.value.installedPackages.isEmpty())

                harness.management.resolveRepository("https://github.com/ignored/ignored")
                advanceUntilIdle()
                assertEquals(GitHubSourceOutcome.Success(Unit), harness.management.selectRelease("20"))
                val second = awaitingTrust(harness.management)
                assertTrue("a new inspection must invalidate an old acknowledgement", second.confirmation.operationGeneration > first.confirmation.operationGeneration)
                assertEquals(GitHubSourceOutcome.Failure(GitHubSourceFailure.TrustRefused), harness.management.confirmTrustAndInstall(false))

                val communityArchive = packageArchive("103", "1.0.0", "community-generation")
                val community = RecordingSourceClient(
                    repository = repository("103", "9000002", "community-owner", "community-repository"),
                    releases = listOf(releaseAsset("21", "121", communityArchive.size.toLong())),
                    archives = mapOf("121" to communityArchive),
                )
                val communityHarness = harness(root, community, clientBounds = bounds(maxInspectionFiles = 1, maxExactAssetBytes = communityArchive.size.toLong()))
                try {
                    communityHarness.management.resolveRepository("https://github.com/ignored/ignored")
                    advanceUntilIdle()
                    assertEquals(GitHubSourceOutcome.Success(Unit), communityHarness.management.selectRelease("21"))
                    assertEquals(GitHubPublisherTier.COMMUNITY, awaitingTrust(communityHarness.management).tier)
                    assertEquals(GitHubSourceOutcome.Failure(GitHubSourceFailure.TrustRefused), communityHarness.management.confirmTrustAndInstall(false))
                    assertTrue("community acknowledgement refusal must not commit", communityHarness.management.managementState.value.installedPackages.isEmpty())
                } finally {
                    communityHarness.shutdown()
                }
            } finally {
                harness.shutdown()
            }
        }
    }

    @Test
    fun `cancellation route exit and shutdown suppress stale work clear staging and reject later mutations`() = runTest {
        withTemporaryDirectory { root ->
            val archive = packageArchive("104", "1.0.0", "cancel")
            val blocking = BlockingSourceClient(
                repository = repository("104", "9000003", "cancel-owner", "cancel-repository"),
                release = releaseAsset("30", "130", archive.size.toLong()),
            )
            val harness = harness(root, blocking, clientBounds = bounds(maxInspectionFiles = 1, maxExactAssetBytes = archive.size.toLong()))
            try {
                harness.management.resolveRepository("https://github.com/ignored/ignored")
                blocking.downloadStarted.await()
                harness.management.cancelInspection()
                advanceUntilIdle()
                assertEquals(PackageManagementState.Idle, harness.management.managementState.value.state)
                assertTrue("cancelled work must not leave a candidate for selection", harness.management.managementState.value.installedPackages.isEmpty())
                assertTrue(File(root, "inspect_staging").listFiles().orEmpty().isEmpty())
                assertEquals(GitHubSourceOutcome.Failure(GitHubSourceFailure.StaleOperation), harness.management.selectRelease("30"))

                harness.management.cleanupRouteExit()
                advanceUntilIdle()
                assertEquals(PackageManagementState.Idle, harness.management.managementState.value.state)
                assertTrue(File(root, "inspect_staging").listFiles().orEmpty().isEmpty())

                harness.management.shutdown()
                assertEquals(PackageManagementState.Closed, harness.management.managementState.value.state)
                assertEquals(
                    GitHubSourceOutcome.Failure(GitHubSourceFailure.LifecycleClosed),
                    harness.management.confirmRemove(GitHubRepositoryIdentity("104"), confirmed = true),
                )
            } finally {
                harness.shutdown()
            }
        }
    }

    @Test
    fun `facade delegated rollback removal and restart summary preserve committed owner identity after publication failure`() = runTest {
        withTemporaryDirectory { root ->
            val v1 = packageArchive("105", "1.0.0", "v1")
            val v2 = packageArchive("105", "2.0.0", "v2")
            val precommitIncompatible = packageArchive("105", "3.0.0", "precommit-incompatible", luaVersion = "0.0")
            val source = RecordingSourceClient(
                repository = repository("105", "9000004", "restart-owner", "restart-repository"),
                releases = listOf(
                    releaseAsset("41", "141", v2.size.toLong()),
                    releaseAsset("40", "140", v1.size.toLong()),
                ),
                archives = mapOf("141" to v2, "140" to v1),
            )
            val harness = harness(root, source, clientBounds = bounds(maxInspectionFiles = 2, maxExactAssetBytes = maxOf(v1.size, v2.size).toLong()))
            try {
                installSelected(harness.management, "40")
                installSelected(harness.management, "41")
                val rejected = harness(
                    root,
                    RecordingSourceClient(
                        repository = repository("105", "9000004", "restart-owner", "restart-repository"),
                        releases = listOf(releaseAsset("42", "142", precommitIncompatible.size.toLong())),
                        archives = mapOf("142" to precommitIncompatible),
                    ),
                    clientBounds = bounds(maxInspectionFiles = 1, maxExactAssetBytes = precommitIncompatible.size.toLong()),
                )
                try {
                    rejected.management.start()
                    rejected.management.resolveRepository("https://github.com/ignored/ignored")
                    advanceUntilIdle()
                    assertEquals(
                        PackageManagementState.Failed(GitHubSourceFailure.IncompatiblePackage),
                        rejected.management.managementState.value.state,
                    )
                    assertEquals(
                        "2.0.0",
                        rejected.management.managementState.value.installedPackages.single().packageVersion,
                    )
                } finally {
                    rejected.shutdown()
                }
                val repositoryId = GitHubRepositoryIdentity("105")
                assertEquals(GitHubSourceOutcome.Failure(GitHubSourceFailure.TrustRefused), harness.management.confirmRollback(repositoryId, confirmed = false))
                assertEquals("2.0.0", harness.management.managementState.value.installedPackages.single().packageVersion)
                assertEquals(GitHubSourceOutcome.Success(MutationResult.RolledBack(InstalledProviderId.derive(repositoryId))), harness.management.confirmRollback(repositoryId, confirmed = true))
                val rolledBack = harness.management.managementState.value.installedPackages.single()
                assertEquals("1.0.0", rolledBack.packageVersion)
                assertEquals("2.0.0", rolledBack.rollbackVersion)
                assertTrue(rolledBack.hasRollback)
                assertEquals(GitHubSourceOutcome.Failure(GitHubSourceFailure.TrustRefused), harness.management.confirmRemove(repositoryId, confirmed = false))
                assertEquals(GitHubSourceOutcome.Success(MutationResult.Removed(InstalledProviderId.derive(repositoryId))), harness.management.confirmRemove(repositoryId, confirmed = true))
                assertTrue("facade removal must remove the committed summary", harness.management.managementState.value.installedPackages.isEmpty())
            } finally {
                harness.shutdown()
            }

            val failingPublication = RecordingSourceClient(
                repository = repository("105", "9000004", "restart-owner", "restart-repository"),
                releases = listOf(releaseAsset("42", "142", v1.size.toLong())),
                archives = mapOf("142" to v1),
            )
            val failedHarness = harness(
                root,
                failingPublication,
                clientBounds = bounds(maxInspectionFiles = 1, maxExactAssetBytes = v1.size.toLong()),
                onReconcile = { throw IllegalStateException("publication unavailable after durable commit") },
            )
            try {
                failedHarness.management.resolveRepository("https://github.com/ignored/ignored")
                advanceUntilIdle()
                assertEquals(GitHubSourceOutcome.Success(Unit), failedHarness.management.selectRelease("42"))
                val outcome = failedHarness.management.confirmTrustAndInstall(true)
                assertTrue("a post-commit publication failure remains a typed mutation outcome", outcome is GitHubSourceOutcome.Success && outcome.value is MutationResult.PublicationFailure)
                assertTrue("durable commit must survive a publication failure", failedHarness.facade.committedSnapshot().containsKey(GitHubRepositoryIdentity("105")))
            } finally {
                failedHarness.shutdown()
            }

            val restarted = harness(
                root,
                RecordingSourceClient(repository("105", "9999999", "wrong", "wrong"), emptyList(), emptyMap()),
                clientBounds = bounds(maxInspectionFiles = 1, maxExactAssetBytes = v1.size.toLong()),
            )
            try {
                restarted.management.start()
                val summary = restarted.management.managementState.value.installedPackages.single()
                assertEquals(GitHubPublisherTier.COMMUNITY, summary.trustTier)
                assertEquals("restart-owner", summary.canonicalOwner)
                assertEquals("restart-repository", summary.canonicalRepository)
            } finally {
                restarted.shutdown()
            }
        }
    }

    private suspend fun TestScope.installSelected(coordinator: PackageManagementCoordinator, releaseId: String) {
        coordinator.resolveRepository("https://github.com/ignored/ignored")
        advanceUntilIdle()
        assertEquals(GitHubSourceOutcome.Success(Unit), coordinator.selectRelease(releaseId))
        val result = coordinator.confirmTrustAndInstall(true)
        assertTrue("selected staged archive must install through the facade", result is GitHubSourceOutcome.Success)
    }

    private fun awaitingTrust(coordinator: PackageManagementCoordinator): PackageManagementState.AwaitingTrust =
        coordinator.managementState.value.state as? PackageManagementState.AwaitingTrust
            ?: throw AssertionError("Expected trust confirmation, got ${coordinator.managementState.value.state}")

    private suspend fun <T> withTemporaryDirectory(block: suspend (File) -> T): T {
        val directory = createTempDirectory("package-management-coordinator-").toFile()
        return try {
            block(directory)
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun TestScope.harness(
        root: File,
        source: GitHubPackageSourceClient,
        clientBounds: GitHubClientBounds,
        onReconcile: suspend () -> Unit = {},
    ): Harness {
        val providers = ChannelImplementationProviderRegistry()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val installed = InstalledPackagesCoordinator(
            storeRoot = root,
            providerRegistry = providers,
            bridge = NoopLuaKernelBridge,
            logSink = NoOpPluginLogSink,
            onCatalogueReconcile = onReconcile,
            serviceScope = this,
            ioDispatcher = dispatcher,
        )
        val facade = InstalledPackagesFacade(installed)
        return Harness(
            facade = facade,
            management = PackageManagementCoordinator(
                facade = facade,
                sourceClient = source,
                providerRegistry = providers,
                storeRoot = root,
                serviceScope = this,
                ioDispatcher = dispatcher,
                clientBounds = clientBounds,
            ),
            installed = installed,
        )
    }

    private suspend fun Harness.shutdown() {
        management.shutdown()
        installed.shutdown()
    }

    private data class Harness(
        val facade: InstalledPackagesFacade,
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

    private fun bounds(maxInspectionFiles: Int, maxExactAssetBytes: Long): GitHubClientBounds = GitHubClientBounds(
        maxUrlBytes = 512,
        maxMetadataResponseBytes = 1_024,
        maxReleaseCandidates = 10,
        maxRedirects = 1,
        maxExactAssetBytes = maxExactAssetBytes,
        maxInspectionFiles = maxInspectionFiles,
        operationDurationSeconds = 30,
        maxRetainedFailureDetailBytes = 128,
    )

    private class RecordingSourceClient(
        private val repository: GitHubResolvedRepository,
        private val releases: List<Pair<GitHubPublishedRelease, GitHubReleaseAsset>>,
        private val archives: Map<String, ByteArray>,
    ) : GitHubPackageSourceClient {
        val downloads = mutableListOf<Download>()

        override suspend fun resolveRepository(coordinates: GitHubRepositoryCoordinates): GitHubSourceOutcome<GitHubResolvedRepository> =
            GitHubSourceOutcome.Success(repository)

        override suspend fun listStableReleaseAssets(coordinates: GitHubRepositoryCoordinates): GitHubSourceOutcome<List<Pair<GitHubPublishedRelease, GitHubReleaseAsset>>> =
            GitHubSourceOutcome.Success(releases)

        override suspend fun downloadAsset(
            coordinates: GitHubRepositoryCoordinates,
            asset: GitHubReleaseAsset,
            destination: OutputStream,
        ): GitHubSourceOutcome<Unit> {
            downloads += Download(coordinates, asset)
            val archive = archives[asset.assetId] ?: return GitHubSourceOutcome.Failure(GitHubSourceFailure.NetworkError)
            destination.write(archive)
            return GitHubSourceOutcome.Success(Unit)
        }
    }

    private class BlockingSourceClient(
        private val repository: GitHubResolvedRepository,
        private val release: Pair<GitHubPublishedRelease, GitHubReleaseAsset>,
    ) : GitHubPackageSourceClient {
        val downloadStarted = CompletableDeferred<Unit>()

        override suspend fun resolveRepository(coordinates: GitHubRepositoryCoordinates): GitHubSourceOutcome<GitHubResolvedRepository> =
            GitHubSourceOutcome.Success(repository)

        override suspend fun listStableReleaseAssets(coordinates: GitHubRepositoryCoordinates): GitHubSourceOutcome<List<Pair<GitHubPublishedRelease, GitHubReleaseAsset>>> =
            GitHubSourceOutcome.Success(listOf(release))

        override suspend fun downloadAsset(
            coordinates: GitHubRepositoryCoordinates,
            asset: GitHubReleaseAsset,
            destination: OutputStream,
        ): GitHubSourceOutcome<Unit> {
            downloadStarted.complete(Unit)
            awaitCancellation()
        }
    }

    private data class Download(val coordinates: GitHubRepositoryCoordinates, val asset: GitHubReleaseAsset)

    private fun packageArchive(repositoryId: String, version: String, marker: String, luaVersion: String = LUA_VERSION): ByteArray {
        val manifest = """{"manifestVersion":1,"repositoryId":"$repositoryId","packageVersion":"$version","entryModule":"plugin","presentation":{"label":"Coordinator package","summary":"Coordinator transaction fixture"},"runtime":{"luaVersion":"$luaVersion","apiVersion":"$API_VERSION"},"configuration":{"schemaVersion":1,"data":{"fields":[],"additionalProperties":false},"ui":{"fields":[]}},"resources":{"mounts":[]},"profileTypes":[],"choiceResolvers":[],"workQueues":[],"capabilities":[]}"""
        return strictUnixStoredZip(
            listOf(
                ZipFixtureEntry("manifest.json", manifest.toByteArray(UTF_8), 0b1000000110100100),
                ZipFixtureEntry("lua/", ByteArray(0), 0b0100000111101101),
                ZipFixtureEntry("lua/plugin.lua", "-- $marker\\nreturn { startup = function() end, handle_readiness = function() return { ready = true } end }".toByteArray(UTF_8), 0b1000000110100100),
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
