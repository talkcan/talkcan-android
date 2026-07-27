package io.talkcan.service

import io.talkcan.dependency.GitHubAssetIdentity
import io.talkcan.dependency.GitHubClientBounds
import io.talkcan.dependency.GitHubHttpResponse
import io.talkcan.dependency.GitHubPublisherTier
import io.talkcan.dependency.GitHubReleaseIdentity
import io.talkcan.dependency.GitHubRepositoryCoordinates
import io.talkcan.dependency.GitHubRepositoryIdentity
import io.talkcan.dependency.GitHubSourceConfiguration
import io.talkcan.dependency.GitHubSourceFailure
import io.talkcan.dependency.GitHubSourceOutcome
import io.talkcan.dependency.GitHubTransport
import io.talkcan.dependency.InstalledProviderId
import io.talkcan.dependency.MutationResult
import io.talkcan.dependency.PackageCapability
import io.talkcan.dependency.PackageSourceRecord
import io.talkcan.dependency.RealGitHubPackageSourceClient
import io.talkcan.lua.LuaCallbackHandle
import io.talkcan.lua.LuaCoroutineId
import io.talkcan.lua.LuaKernelBridge
import io.talkcan.lua.LuaKernelConfig
import io.talkcan.lua.LuaKernelOutcome
import io.talkcan.lua.LuaOperationHandle
import io.talkcan.lua.LuaSpawnAdmission
import io.talkcan.lua.LuaStateHandle
import io.talkcan.lua.LuaValue
import io.talkcan.lua.NoOpPluginLogSink
import io.talkcan.model.ChannelImplementationId
import io.talkcan.model.ChannelImplementationProviderRegistry
import io.talkcan.model.ChannelProviderResolution
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.time.Instant
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Byte-pinned production installation contract for the exact published OpenAI Agent
 * v1.0.7 artifact. The real [RealGitHubPackageSourceClient] runs against a bounded
 * scripted transport serving the published repository metadata, the single stable
 * release/asset, and the exact 173159-byte archive through the production
 * asset-API → redirect → object-store download path. Every mutation then traverses
 * the real [PackageManagementCoordinator] → [InstalledPackagesFacade] →
 * InstalledPackageStore → [ChannelImplementationProviderRegistry] chain.
 *
 * Provenance is the published talkcan-channels/openai-agent v1.0.7 release:
 * repository 1313913383, owner 1224006/talkcan-channels, release 359437600, asset
 * 488626975. Official tier derives from exact owner-ID equality only; the provider
 * ID is derived from the numeric repository identity — there is no OpenAI-name
 * dispatch, no repository-name special case, no built-in collision, no automatic
 * channel instance, no Lua entry, and no bundled artifact source beside the
 * scripted transport.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OpenAiProductionPackageInstallTest {
    @Test
    fun `exact published OpenAI v1_0_7 artifact installs through the production coordinator with immutable provenance and no builtin special case`() = runTest {
        // Exact published bytes, pinned before anything touches the coordinator.
        val artifact = fixture()
        assertEquals(ARTIFACT_SHA256, sha256(artifact))
        assertEquals(ARTIFACT_SIZE, artifact.size)
        val publishedEpoch = Instant.parse(PUBLISHED_AT).epochSecond

        withTemporaryDirectory { root ->
            val transport = PublishedAssetTransport(artifact)
            val harness = harness(root, transport)
            try {
                // 1. Resolve + inspect through the production REST client. The submitted
                // URL carries a mixed-case owner; every later exchange must use the
                // canonical coordinates decoded from the resolved repository metadata.
                harness.management.resolveRepository("https://github.com/Talkcan-channels/openai-agent")
                val selection = awaitSelection(harness.management)

                assertEquals(
                    listOf(SUBMITTED_REPOSITORY_URL, RELEASES_URL, ASSET_API_URL, ASSET_OBJECT_URL),
                    transport.requests.map { it.url },
                )
                assertFalse(
                    "anonymous production discovery must never send authorization",
                    transport.requests.any { request -> request.headers.keys.any { it.equals("Authorization", ignoreCase = true) } },
                )
                assertEquals("application/vnd.github+json", transport.requests[0].headers["Accept"])
                assertEquals("application/vnd.github+json", transport.requests[1].headers["Accept"])
                assertEquals("application/octet-stream", transport.requests[2].headers["Accept"])
                assertEquals("application/octet-stream", transport.requests[3].headers["Accept"])
                assertEquals(
                    "the scripted transport must be the sole source of artifact bytes",
                    1,
                    transport.servedBodies,
                )

                // Single compatible candidate: the exact published release, asset, and digest.
                assertEquals(GitHubRepositoryIdentity(REPOSITORY_ID), selection.repository.id)
                assertEquals("talkcan-channels/openai-agent", selection.repository.fullName)
                assertEquals(OFFICIAL_OWNER_ID, selection.repository.owner.ownerId)
                assertEquals("talkcan-channels", selection.repository.owner.login)
                assertTrue("the published release must pass inspection with no ineligible siblings", selection.ineligible.isEmpty())
                val candidate = selection.candidates.single()
                assertEquals(RELEASE_ID, candidate.release.releaseId)
                assertEquals(RELEASE_TAG, candidate.release.tag)
                assertEquals(false, candidate.release.isDraft)
                assertEquals(false, candidate.release.isPrerelease)
                assertEquals(publishedEpoch, candidate.release.publishedAtEpochSeconds)
                assertEquals(ASSET_ID, candidate.asset.assetId)
                assertEquals(GitHubSourceConfiguration.CANONICAL_ASSET_NAME, candidate.asset.name)
                assertEquals("uploaded", candidate.asset.state)
                assertEquals("application/zip", candidate.asset.contentType)
                assertEquals(ARTIFACT_SIZE.toLong(), candidate.asset.size)
                assertEquals(ARTIFACT_SHA256, candidate.digest.value)

                // 2. Select the published release and inspect the trust disclosure.
                assertEquals(GitHubSourceOutcome.Success(Unit), harness.management.selectRelease(RELEASE_ID))
                val trust = awaitingTrust(harness.management)
                assertEquals(GitHubPublisherTier.OFFICIAL, trust.tier)
                assertEquals(GitHubPublisherTier.OFFICIAL, trust.confirmation.publisherTier)
                assertEquals("https://github.com/talkcan-channels/openai-agent", trust.confirmation.canonicalRepositoryUrl)
                assertEquals("talkcan-channels", trust.confirmation.canonicalOwner)
                assertEquals("openai-agent", trust.confirmation.canonicalRepository)
                assertEquals("OpenAI Agent", trust.confirmation.packageLabel)
                assertEquals(
                    "OpenAI-compatible agent channel with tool calling and voice output",
                    trust.confirmation.packageSummary,
                )
                assertEquals(RELEASE_TAG, trust.confirmation.releaseTag)
                assertEquals(publishedEpoch, trust.confirmation.publicationTimeEpochSeconds)
                assertEquals(GitHubSourceConfiguration.CANONICAL_ASSET_NAME, trust.confirmation.assetName)
                assertEquals(ARTIFACT_SIZE.toLong(), trust.confirmation.assetSize)
                assertEquals(ARTIFACT_SHA256, trust.confirmation.inspectedDigest.value)
                assertEquals(true, trust.confirmation.declaresNetworkHttp)
                assertEquals(true, trust.confirmation.declaresSecretsRead)
                assertEquals(
                    listOf(
                        PackageCapability.AUDIO_TRANSCRIPTION,
                        PackageCapability.AUDIO_SYNTHESIS,
                        PackageCapability.AUDIO_PLAYBACK,
                        PackageCapability.KEYBOARD_OUTPUT,
                        PackageCapability.NETWORK_HTTP,
                        PackageCapability.PROFILES_READ,
                        PackageCapability.SECRETS_READ,
                        PackageCapability.WORK_QUEUE,
                    ),
                    trust.confirmation.capabilities,
                )
                assertEquals(
                    listOf(PackageConfirmationProfileType("openai_compatible", "OpenAI Compatible", true)),
                    trust.confirmation.profileTypes,
                )
                assertEquals(
                    listOf(
                        PackageConfirmationResolver(
                            "models",
                            listOf(
                                PackageCapability.NETWORK_HTTP,
                                PackageCapability.PROFILES_READ,
                                PackageCapability.SECRETS_READ,
                            ),
                        ),
                    ),
                    trust.confirmation.choiceResolvers,
                )
                assertEquals(listOf("turns"), trust.confirmation.workQueues)
                assertTrue(trust.confirmation.operationGeneration > 0)

                // 3. Official installation requires the explicit current acknowledgement.
                assertEquals(
                    GitHubSourceOutcome.Failure(GitHubSourceFailure.TrustRefused),
                    harness.management.confirmTrustAndInstall(false),
                )
                assertTrue("refusing confirmation must not mutate committed state", harness.management.managementState.value.installedPackages.isEmpty())
                assertTrue("refusing confirmation must not commit an index entry", harness.facade.committedSnapshot().isEmpty())

                // 4. Confirmed acknowledgement commits the exact inspected bytes.
                val providerId = InstalledProviderId.derive(GitHubRepositoryIdentity(REPOSITORY_ID))
                assertEquals("github-repository:$REPOSITORY_ID", providerId.value)
                assertEquals(
                    GitHubSourceOutcome.Success(MutationResult.Installed(providerId)),
                    harness.management.confirmTrustAndInstall(true),
                )
                assertTrue(
                    "committed install must clear the private inspection staging",
                    File(root, "inspect_staging").listFiles().orEmpty().isEmpty(),
                )

                // 5. Store: exact published bytes under the immutable content digest and
                // the complete immutable provenance record.
                assertTrue(
                    "committed content must be the byte-exact published artifact",
                    File(root, "content/sha256/$ARTIFACT_SHA256").readBytes().contentEquals(artifact),
                )
                val record = harness.facade.committedSnapshot().getValue(GitHubRepositoryIdentity(REPOSITORY_ID))
                assertEquals(
                    PackageSourceRecord(
                        repositoryId = GitHubRepositoryIdentity(REPOSITORY_ID),
                        coordinates = GitHubRepositoryCoordinates("talkcan-channels", "openai-agent"),
                        release = GitHubReleaseIdentity(RELEASE_ID, RELEASE_TAG, false),
                        asset = GitHubAssetIdentity(ASSET_ID, GitHubSourceConfiguration.CANONICAL_ASSET_NAME),
                        ownerId = OFFICIAL_OWNER_ID,
                    ),
                    record.active.sourceRecord,
                )
                assertEquals(ARTIFACT_SHA256, record.active.digest.value)
                assertEquals(PACKAGE_VERSION, record.active.manifest.packageVersion)
                assertNull("a fresh official install carries no rollback generation", record.rollback)

                // 6. Provider registry: one repository-derived provider bound to the
                // exact digest fingerprint. No built-in collision, no automatic instance,
                // no bundled OpenAI source resolving beside it, and no Lua entry.
                val resolution = harness.providers.resolve(providerId)
                assertTrue(resolution is ChannelProviderResolution.Available)
                val provider = (resolution as ChannelProviderResolution.Available).provider
                assertEquals(providerId, provider.descriptor.implementationId)
                assertEquals(ARTIFACT_SHA256, provider.fingerprint.value)
                assertEquals("OpenAI Agent", provider.descriptor.presentation.label)
                assertEquals(
                    "the registry must publish exactly the one installed package provider",
                    listOf(providerId),
                    harness.providers.descriptors().map { it.implementationId },
                )
                assertFalse(
                    "the installed external package must not collide with a built-in identity",
                    providerId.value.startsWith("builtin:"),
                )
                assertTrue(
                    "no bundled built-in OpenAI source may resolve beside the installed provider",
                    harness.providers.resolve(ChannelImplementationId("builtin:openai-agent")) is ChannelProviderResolution.Missing,
                )
                assertEquals(0, harness.bridge.calls)

                // 7. Public summary: canonical identity and official tier derived from the
                // committed owner-ID record, never from the repository name.
                assertEquals(PackageManagementState.Ready, harness.management.managementState.value.state)
                val summary = harness.management.managementState.value.installedPackages.single()
                assertEquals(GitHubRepositoryIdentity(REPOSITORY_ID), summary.repositoryId)
                assertEquals("talkcan-channels", summary.canonicalOwner)
                assertEquals("openai-agent", summary.canonicalRepository)
                assertEquals(GitHubPublisherTier.OFFICIAL, summary.trustTier)
                assertEquals(PACKAGE_VERSION, summary.packageVersion)
                assertEquals(RELEASE_TAG, summary.releaseTag)
                assertEquals(RELEASE_ID, summary.releaseId)
                assertEquals(ASSET_ID, summary.assetId)
                assertEquals(PackageManagementStatus.AVAILABLE, summary.status)
                assertFalse(summary.hasRollback)
                assertNull(summary.failureCategory)
                assertNull(summary.failureDetail)
            } finally {
                harness.shutdown()
            }
        }
    }

    private suspend fun awaitSelection(coordinator: PackageManagementCoordinator): PackageManagementState.AwaitingSelection {
        val terminal = coordinator.managementState.first {
            it.state is PackageManagementState.AwaitingSelection || it.state is PackageManagementState.Failed
        }
        return terminal.state as? PackageManagementState.AwaitingSelection
            ?: throw AssertionError("Expected inspected candidates, got ${terminal.state}")
    }

    private fun awaitingTrust(coordinator: PackageManagementCoordinator): PackageManagementState.AwaitingTrust =
        coordinator.managementState.value.state as? PackageManagementState.AwaitingTrust
            ?: throw AssertionError("Expected trust confirmation, got ${coordinator.managementState.value.state}")

    private suspend fun <T> withTemporaryDirectory(block: suspend (File) -> T): T {
        val directory = createTempDirectory("openai-production-install-").toFile()
        return try {
            block(directory)
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun TestScope.harness(root: File, transport: GitHubTransport): Harness {
        val providers = ChannelImplementationProviderRegistry()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val bridge = UnusedBridge()
        val installed = InstalledPackagesCoordinator(
            storeRoot = root,
            providerRegistry = providers,
            bridge = bridge,
            logSink = NoOpPluginLogSink,
            onCatalogueReconcile = {},
            serviceScope = this,
            ioDispatcher = dispatcher,
        )
        val facade = InstalledPackagesFacade(installed)
        return Harness(
            facade = facade,
            management = PackageManagementCoordinator(
                facade = facade,
                sourceClient = RealGitHubPackageSourceClient(transport, clientBounds()),
                providerRegistry = providers,
                storeRoot = root,
                serviceScope = this,
                ioDispatcher = dispatcher,
                clientBounds = clientBounds(),
            ),
            installed = installed,
            providers = providers,
            bridge = bridge,
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
        val providers: ChannelImplementationProviderRegistry,
        val bridge: UnusedBridge,
    )

    private fun clientBounds(): GitHubClientBounds = GitHubClientBounds(
        maxUrlBytes = 512,
        maxMetadataResponseBytes = 65_536,
        maxReleaseCandidates = 8,
        maxRedirects = 2,
        maxExactAssetBytes = ARTIFACT_SIZE.toLong(),
        maxInspectionFiles = 1,
        operationDurationSeconds = 30,
        maxRetainedFailureDetailBytes = 128,
    )

    private fun fixture(): ByteArray =
        requireNotNull(javaClass.classLoader?.getResourceAsStream(RESOURCE_PATH)) {
            "Missing OpenAI channel fixture: $RESOURCE_PATH"
        }.use { it.readBytes() }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /**
     * Bounded scripted transport serving the exact published GitHub metadata and the
     * exact published artifact bytes. Every request is recorded; any unexpected URL is
     * a hard failure so no undisclosed source can contribute bytes.
     */
    private class PublishedAssetTransport(private val artifact: ByteArray) : GitHubTransport {
        val requests = mutableListOf<RecordedRequest>()
        var servedBodies = 0
            private set

        override fun executeGet(url: String, headers: Map<String, String>): GitHubHttpResponse {
            requests += RecordedRequest(url, headers.toMap())
            return when (url) {
                SUBMITTED_REPOSITORY_URL -> jsonResponse(REPOSITORY_JSON)
                RELEASES_URL -> jsonResponse(RELEASES_JSON)
                ASSET_API_URL -> ScriptedResponse(302, ByteArray(0), mapOf("Location" to ASSET_OBJECT_URL))
                ASSET_OBJECT_URL -> {
                    servedBodies++
                    ScriptedResponse(200, artifact, mapOf("Content-Length" to artifact.size.toString()))
                }
                else -> throw IOException("unexpected request to $url")
            }
        }

        private fun jsonResponse(body: String): ScriptedResponse =
            ScriptedResponse(200, body.toByteArray(Charsets.UTF_8), emptyMap())
    }

    private data class RecordedRequest(val url: String, val headers: Map<String, String>)

    private class ScriptedResponse(
        override val code: Int,
        private val bytes: ByteArray,
        private val headers: Map<String, String>,
    ) : GitHubHttpResponse {
        private var closed = false

        override fun header(name: String): String? =
            headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

        override fun bodyStream(): InputStream {
            check(!closed) { "body requested after response closure" }
            return ByteArrayInputStream(bytes)
        }

        override fun close() {
            closed = true
        }
    }

    /** Inspection, validation, storage, and materialization must never enter the Lua kernel. */
    private class UnusedBridge : LuaKernelBridge {
        var calls: Int = 0
            private set

        private fun unused(): Nothing {
            calls += 1
            error("Package installation must not enter the Lua kernel")
        }

        override fun create(config: LuaKernelConfig): LuaKernelOutcome = unused()
        override fun load(handle: LuaStateHandle, source: String, entrypoint: String): LuaKernelOutcome = unused()
        override fun start(handle: LuaStateHandle): LuaKernelOutcome = unused()
        override fun resume(operation: LuaOperationHandle, success: Boolean, value: String, spawnAdmission: LuaSpawnAdmission): LuaKernelOutcome = unused()
        override fun cancel(operation: LuaOperationHandle): LuaKernelOutcome = unused()
        override fun interrupt(handle: LuaStateHandle): LuaKernelOutcome = unused()
        override fun snapshot(handle: LuaStateHandle): LuaKernelOutcome = unused()
        override fun close(handle: LuaStateHandle): LuaKernelOutcome = unused()
        override fun loadProgramImage(handle: LuaStateHandle, entryPoint: String, sourceMap: Map<String, String>): LuaKernelOutcome = unused()
        override fun invokeStartupCallback(handle: LuaStateHandle, callbackHandle: LuaCallbackHandle, config: LuaValue, spawnAdmission: LuaSpawnAdmission): LuaKernelOutcome = unused()
        override fun invokeCallback(handle: LuaStateHandle, callbackHandle: LuaCallbackHandle, arguments: LuaValue, spawnAdmission: LuaSpawnAdmission): LuaKernelOutcome = unused()
        override fun startCoroutine(handle: LuaStateHandle, coroutineId: LuaCoroutineId, spawnAdmission: LuaSpawnAdmission): LuaKernelOutcome = unused()
    }

    private companion object {
        const val RESOURCE_PATH = "openai-agent-channel/talkcan-channel.zip"
        const val REPOSITORY_ID = "1313913383"
        const val OFFICIAL_OWNER_ID = "1224006"
        const val RELEASE_ID = "359437600"
        const val RELEASE_TAG = "v1.0.7"
        const val ASSET_ID = "488626975"
        const val PACKAGE_VERSION = "1.0.7"
        const val PUBLISHED_AT = "2026-07-24T17:08:37Z"
        const val ARTIFACT_SIZE = 173132
        const val ARTIFACT_SHA256 = "602cbf54008e5e204de08118b6a2131bb5f5fac120f0a42ff5eb0e529a00d9f3"

        // Mixed-case owner in the submitted URL proves every later exchange uses the
        // canonical coordinates decoded from the resolved repository metadata.
        const val SUBMITTED_REPOSITORY_URL = "https://api.github.com/repos/Talkcan-channels/openai-agent"
        const val RELEASES_URL = "https://api.github.com/repos/talkcan-channels/openai-agent/releases"
        const val ASSET_API_URL = "https://api.github.com/repos/talkcan-channels/openai-agent/releases/assets/$ASSET_ID"
        const val ASSET_OBJECT_URL =
            "https://objects.githubusercontent.com/github-production-release-asset-2e65be/$REPOSITORY_ID/$ASSET_ID.zip" +
                "?response-content-disposition=attachment%3B%20filename%3Dtalkcan-channel.zip" +
                "&response-content-type=application%2Fzip"

        const val REPOSITORY_JSON =
            "{\"id\":$REPOSITORY_ID,\"full_name\":\"talkcan-channels/openai-agent\",\"archived\":false," +
                "\"disabled\":false,\"visibility\":\"public\"," +
                "\"owner\":{\"id\":$OFFICIAL_OWNER_ID,\"login\":\"talkcan-channels\",\"type\":\"User\"}}"

        const val RELEASES_JSON =
            "[{\"id\":$RELEASE_ID,\"tag_name\":\"$RELEASE_TAG\",\"name\":\"$RELEASE_TAG\",\"draft\":false," +
                "\"prerelease\":false,\"published_at\":\"$PUBLISHED_AT\"," +
                "\"assets\":[{\"id\":$ASSET_ID,\"name\":\"${GitHubSourceConfiguration.CANONICAL_ASSET_NAME}\"," +
                "\"state\":\"uploaded\",\"content_type\":\"application/zip\",\"size\":$ARTIFACT_SIZE," +
                "\"browser_download_url\":\"https://github.com/talkcan-channels/openai-agent/releases/download/$RELEASE_TAG/${GitHubSourceConfiguration.CANONICAL_ASSET_NAME}\"}]}]"
    }
}
