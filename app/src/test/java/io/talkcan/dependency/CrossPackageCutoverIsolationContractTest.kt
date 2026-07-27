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
import io.talkcan.lua.LuaSpawnAdmission
import io.talkcan.lua.LuaStateHandle
import io.talkcan.lua.LuaValue
import io.talkcan.model.ChannelConfigurationField
import io.talkcan.model.ChannelImplementationId
import io.talkcan.model.ChannelImplementationProviderRegistry
import io.talkcan.model.ChannelPresentationMetadata
import io.talkcan.model.ChannelProviderResolution
import io.talkcan.model.InstalledProvidersPublicationResult
import io.talkcan.model.ProviderRevisionFingerprint
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Task 2.6: one cross-package integration contract proving that the ordinary
 * generic install -> compatible-update -> rollback path works for Debug,
 * Diagnostics, Journal, and Keyboard simultaneously, and that every mutation of
 * one package leaves each sibling's repository identity, digest, source
 * provenance, and published authority (profile/resolver/work declarations,
 * capability set, configuration UI/data, and mounts) byte-for-byte unchanged.
 *
 * The four exact published release artifacts traverse only the generic host
 * machinery — [PackageValidator] -> [InstalledPackageStore] ->
 * [io.talkcan.lua.LuaPackageMaterializer] -> provider registry —
 * with no repository-name dispatch and no test-only provider registration. Each
 * compatible successor is a deterministic, length-stable recarriage of the
 * published artifact that advances only `packageVersion`, so the canonical
 * STORED ZIP shape and the source map are preserved exactly under the validator.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CrossPackageCutoverIsolationContractTest {

    @Test
    fun `generic install update and rollback for all four packages preserve sibling identity and authority byte for byte`() =
        runTest {
            withTemporaryDirectory { root ->
                val bridge = CountingBridge()
                val providers = ChannelImplementationProviderRegistry()
                val repository = repository(root, bridge, providers)
                val specs = packageSpecs()

                // ── Phase 1: install the exact published release of every package ──
                val publishedDigest = LinkedHashMap<String, String>()
                val publishedFingerprint = LinkedHashMap<String, ProviderRevisionFingerprint>()
                for (spec in specs) {
                    val bytes = fixture(spec.resourcePath)
                    val id = implementationId(spec)
                    assertFalse(
                        "An external package must not be a built-in channel",
                        id.value.startsWith("builtin:"),
                    )
                    assertEquals(
                        "Install must traverse the generic transaction",
                        MutationResult.Installed(id),
                        success(repository.installOrUpdate(ByteArrayInputStream(bytes), publishedSourceRecord(spec))),
                    )
                    assertTrue(providers.resolve(id) is ChannelProviderResolution.Available)

                    val record = indexRecord(root, spec.repositoryId)
                    assertNull("A fresh install carries no rollback generation", record.rollback)
                    assertEquals(
                        "Installed digest must be the exact published artifact digest",
                        sha256(bytes),
                        record.active.digest.value,
                    )
                    assertEquals(
                        "Installed source provenance must be the exact published release record",
                        publishedSourceRecord(spec),
                        record.active.sourceRecord,
                    )
                    assertPublishedAuthority(spec, record.active.manifest)

                    publishedDigest[spec.key] = record.active.digest.value
                    publishedFingerprint[spec.key] = fingerprintOf(providers, id)
                }
                assertEquals("All four packages must publish repository-derived providers", 4, providers.descriptors().size)

                // Baseline authority for every package, captured once after install.
                val baselineStored = specs.associate { it.key to snapshotStored(providers, root, it.repositoryId) }.toMutableMap()
                val baselineDescriptor = specs.associate { it.key to snapshotDescriptor(providers, it.repositoryId) }

                // ── Phase 2: per-package update + rollback under sibling isolation ──
                for (spec in specs) {
                    val id = implementationId(spec)
                    val digestBefore = publishedDigest.getValue(spec.key)
                    val fingerprintBefore = publishedFingerprint.getValue(spec.key)
                    val publishedSource = publishedSourceRecord(spec)
                    val publishedManifest = indexRecord(root, spec.repositoryId).active.manifest

                    // (a) Install a deterministic compatible successor (packageVersion only).
                    val carrier = successorOf(fixture(spec.resourcePath))
                    val successorSource = successorSourceRecord(spec, carrier.successorVersion)
                    assertEquals(
                        MutationResult.Updated(id),
                        success(repository.installOrUpdate(ByteArrayInputStream(carrier.bytes), successorSource)),
                    )
                    assertTrue(providers.resolve(id) is ChannelProviderResolution.Available)

                    val updated = indexRecord(root, spec.repositoryId)
                    assertEquals(carrier.successorVersion, updated.active.manifest.packageVersion)
                    assertNotEquals("Compatible update must publish a fresh digest", digestBefore, updated.active.digest.value)
                    assertEquals("Fresh digest must be the exact successor artifact digest", sha256(carrier.bytes), updated.active.digest.value)
                    assertEquals("Compatible update must carry the distinct successor source record", successorSource, updated.active.sourceRecord)
                    assertEquals("Update must demote the published revision to the rollback slot", publishedManifest, updated.rollback!!.manifest)
                    assertEquals("Rollback slot must retain the exact published digest", digestBefore, updated.rollback!!.digest.value)
                    assertEquals("Rollback slot must retain the exact published source record", publishedSource, updated.rollback!!.sourceRecord)

                    val fingerprintAfter = fingerprintOf(providers, id)
                    assertNotEquals("Compatible update must publish a fresh immutable revision", fingerprintBefore, fingerprintAfter)
                    assertEquals(ProviderRevisionFingerprint.fromDigest(updated.active.digest), fingerprintAfter)

                    // Authority preserved: the successor manifest differs only by packageVersion.
                    assertEquals(
                        "Compatible update must preserve the entire published authority (profile/resolver/work, capabilities, configuration, resources)",
                        publishedManifest.copy(packageVersion = carrier.successorVersion),
                        updated.active.manifest,
                    )
                    // Published descriptor authority is unchanged by a version-only bump.
                    assertEquals(
                        "Compatible update must not alter the published descriptor authority",
                        baselineDescriptor.getValue(spec.key),
                        snapshotDescriptor(providers, spec.repositoryId),
                    )
                    // Every sibling remains byte-for-byte unchanged by this package's update.
                    assertSiblingsUnchanged(providers, root, specs, baselineStored, baselineDescriptor, mutatedKey = spec.key)

                    // (b) Explicit rollback restores the exact published revision.
                    assertEquals(
                        MutationResult.RolledBack(id),
                        success(repository.rollback(publishedSource.repositoryId)),
                    )
                    assertTrue(providers.resolve(id) is ChannelProviderResolution.Available)

                    val rolledBack = indexRecord(root, spec.repositoryId)
                    assertEquals("Rollback must restore the exact published packageVersion", carrier.publishedVersion, rolledBack.active.manifest.packageVersion)
                    assertEquals("Rollback must restore the exact published manifest", publishedManifest, rolledBack.active.manifest)
                    assertEquals("Rollback must restore the exact published digest", digestBefore, rolledBack.active.digest.value)
                    assertEquals("Rollback must restore the exact published source record", publishedSource, rolledBack.active.sourceRecord)
                    assertEquals("Rollback must keep the successor in the rollback slot", carrier.successorVersion, rolledBack.rollback!!.manifest.packageVersion)
                    assertEquals("Rollback must restore the exact published fingerprint", fingerprintBefore, fingerprintOf(providers, id))
                    assertEquals(
                        "Rollback must not alter the published descriptor authority",
                        baselineDescriptor.getValue(spec.key),
                        snapshotDescriptor(providers, spec.repositoryId),
                    )
                    // Every sibling remains byte-for-byte unchanged by this package's rollback.
                    assertSiblingsUnchanged(providers, root, specs, baselineStored, baselineDescriptor, mutatedKey = spec.key)
                    // This package's own rollback slot now contains its successor.
                    // Preserve that post-cycle state when later sibling cycles run.
                    baselineStored[spec.key] = snapshotStored(providers, root, spec.repositoryId)
                }

                // ── Phase 3: after all four cycles, every package is restored to its
                //    exact published revision and no sibling ever drifted. ──
                for (spec in specs) {
                    val record = indexRecord(root, spec.repositoryId)
                    assertEquals(publishedDigest.getValue(spec.key), record.active.digest.value)
                    assertEquals(publishedSourceRecord(spec), record.active.sourceRecord)
                    assertEquals(publishedFingerprint.getValue(spec.key), fingerprintOf(providers, implementationId(spec)))
                    assertEquals(baselineStored.getValue(spec.key), snapshotStored(providers, root, spec.repositoryId))
                    assertEquals(baselineDescriptor.getValue(spec.key), snapshotDescriptor(providers, spec.repositoryId))
                }

                repository.requestClose()
            }
        }

    // ── sibling isolation ──────────────────────────────────────────────────

    private fun assertSiblingsUnchanged(
        providers: ChannelImplementationProviderRegistry,
        root: File,
        specs: List<PackageSpec>,
        baselineStored: Map<String, StoredAuthority>,
        baselineDescriptor: Map<String, DescriptorAuthority>,
        mutatedKey: String,
    ) {
        for (spec in specs) {
            if (spec.key == mutatedKey) continue
            assertEquals(
                "Sibling '${spec.key}' stored identity/digest/source/manifest authority must remain byte-for-byte unchanged",
                baselineStored.getValue(spec.key),
                snapshotStored(providers, root, spec.repositoryId),
            )
            assertEquals(
                "Sibling '${spec.key}' published descriptor authority must remain unchanged",
                baselineDescriptor.getValue(spec.key),
                snapshotDescriptor(providers, spec.repositoryId),
            )
        }
    }

    private fun snapshotStored(
        providers: ChannelImplementationProviderRegistry,
        root: File,
        repositoryId: String,
    ): StoredAuthority {
        val id = InstalledProviderId.derive(GitHubRepositoryIdentity(repositoryId))
        return StoredAuthority(
            providerId = id.value,
            fingerprint = fingerprintOf(providers, id),
            stored = indexRecord(root, repositoryId),
        )
    }

    private fun snapshotDescriptor(
        providers: ChannelImplementationProviderRegistry,
        repositoryId: String,
    ): DescriptorAuthority {
        val id = InstalledProviderId.derive(GitHubRepositoryIdentity(repositoryId))
        val descriptor = descriptorOf(providers, id)
        return DescriptorAuthority(
            providerId = id.value,
            presentation = descriptor.presentation,
            requiredCapabilities = descriptor.requiredCapabilities,
            configurationFields = descriptor.configurationFields,
            mounts = descriptor.resourceDeclarations.mounts,
        )
    }

    /** Storage/identity layer: provider id, immutable revision fingerprint, and the
     *  full stored record (digest + manifest authority + source provenance, active +
     *  rollback). Data-class equality makes this a byte-meaningful comparison. */
    private data class StoredAuthority(
        val providerId: String,
        val fingerprint: ProviderRevisionFingerprint,
        val stored: StoredProviderRecord,
    )

    /** Published provider layer derived from the manifest: presentation, capability
     *  set, configuration UI/data fields, and mount declarations. */
    private data class DescriptorAuthority(
        val providerId: String,
        val presentation: ChannelPresentationMetadata,
        val requiredCapabilities: Set<ChannelCapability>,
        val configurationFields: List<ChannelConfigurationField>,
        val mounts: List<PackageMountDeclaration>,
    )

    // ── package-specific published authority anchors ───────────────────────

    private fun assertPublishedAuthority(spec: PackageSpec, manifest: PackageManifest) {
        assertEquals(GitHubRepositoryIdentity(spec.repositoryId), manifest.repositoryId)
        assertEquals("plugin", manifest.entryModule)
        assertEquals(LUA_VERSION, manifest.runtime.luaVersion)
        assertEquals(API_VERSION, manifest.runtime.apiVersion)
        assertTrue("No profile type declarations expected", manifest.profileTypes.isEmpty())
        assertTrue("No choice resolver declarations expected", manifest.choiceResolvers.isEmpty())
        assertTrue("No work queue declarations expected", manifest.workQueues.isEmpty())

        when (spec.key) {
            "debug" -> {
                assertTrue("Debug declares no mounts", manifest.resources.mounts.isEmpty())
                assertEquals(
                    setOf("audio.transcription", "audio.synthesis", "audio.playback"),
                    manifest.capabilities,
                )
                assertEquals(listOf("mode"), manifest.configuration.data.fields.map { it.id })
            }
            "diagnostics" -> {
                assertTrue("Diagnostics is source-only: no mounts", manifest.resources.mounts.isEmpty())
                assertTrue("Diagnostics declares no capabilities", manifest.capabilities.isEmpty())
                assertTrue("Diagnostics declares no configuration data fields", manifest.configuration.data.fields.isEmpty())
                assertTrue("Diagnostics declares no configuration UI fields", manifest.configuration.ui.fields.isEmpty())
            }
            "journal" -> {
                assertEquals(
                    setOf("storage.files", "audio.files", "audio.transcription"),
                    manifest.capabilities,
                )
                assertEquals(listOf("output_mode"), manifest.configuration.data.fields.map { it.id })
                val mount = manifest.resources.mounts.single()
                assertEquals("output", mount.id)
                assertEquals(PackageMountKind.DIRECTORY_TREE, mount.kind)
                assertEquals(PackageMountAccess.READ_WRITE, mount.access)
                assertTrue("Journal output mount must be required", mount.required)
            }
            "keyboard" -> {
                assertTrue("Keyboard declares no mounts", manifest.resources.mounts.isEmpty())
                assertTrue("Keyboard must declare keyboard.output", manifest.capabilities.contains("keyboard.output"))
                assertTrue("Keyboard must declare audio.transcription", manifest.capabilities.contains("audio.transcription"))
                assertEquals(
                    listOf("host_os", "host_layout", "host_profile"),
                    manifest.configuration.ui.fields.map { it.field },
                )
                assertTrue(
                    "Keyboard configuration must remain dynamic-choice",
                    manifest.configuration.ui.fields.all { it.control == UiControl.DYNAMIC_CHOICE },
                )
            }
            else -> error("Unknown package key: ${spec.key}")
        }
    }

    // ── deterministic compatible successor generation ──────────────────────

    private data class SuccessorCarrier(
        val bytes: ByteArray,
        val publishedVersion: String,
        val successorVersion: String,
    )

    /** Derives a compatible successor from the exact published artifact by advancing
     *  only `packageVersion` with a length-stable rewrite, preserving the canonical
     *  STORED ZIP shape and the Lua source map byte-for-byte. Not a published release. */
    private fun successorOf(artifact: ByteArray): SuccessorCarrier {
        val published = publishedVersionOf(artifact)
        val successor = successorVersion(published)
        val bytes = repackaged(artifact) { manifest ->
            manifest.replace("\"packageVersion\":\"$published\"", "\"packageVersion\":\"$successor\"")
        }
        return SuccessorCarrier(bytes, published, successor)
    }

    private fun publishedVersionOf(artifact: ByteArray): String =
        requireNotNull(Regex("\"packageVersion\":\"([^\"]*)\"").find(manifestJson(artifact))) {
            "Fixture manifest has no packageVersion"
        }.groupValues[1]

    /** Same-length, guaranteed-distinct successor version: bump the major digit. */
    private fun successorVersion(published: String): String =
        (if (published.first() != '9') '9' else '8') + published.substring(1)

    private fun manifestJson(artifact: ByteArray): String {
        ZipInputStream(ByteArrayInputStream(artifact)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.name == "manifest.json") return String(zip.readBytes(), Charsets.UTF_8)
            }
        }
        error("Fixture has no manifest.json")
    }

    /**
     * Patches the STORED manifest payload in place while preserving the fixture's
     * exact Unix local and central ZIP metadata; only the manifest CRC is recomputed.
     * The transform must be length-stable so all offsets remain valid.
     */
    private fun repackaged(artifact: ByteArray, transformManifest: (String) -> String): ByteArray {
        val originalManifest = ZipInputStream(ByteArrayInputStream(artifact)).use { zip ->
            var found: ByteArray? = null
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.name == "manifest.json") {
                    require(entry.method == ZipEntry.STORED) { "Manifest fixture must be STORED" }
                    found = zip.readBytes()
                    break
                }
            }
            requireNotNull(found) { "Fixture has no manifest.json" }
        }
        val transformed = transformManifest(String(originalManifest, Charsets.UTF_8))
            .toByteArray(Charsets.UTF_8)
        require(!transformed.contentEquals(originalManifest)) {
            "Carrier transform did not modify the manifest"
        }
        require(transformed.size == originalManifest.size) {
            "Carrier transform must preserve manifest byte length"
        }

        val rewritten = artifact.copyOf()
        val local = findZipHeader(rewritten, 0x04034b50L, 30, 26, "manifest.json")
        val localNameLength = readZipU16(rewritten, local + 26)
        val localExtraLength = readZipU16(rewritten, local + 28)
        val payloadOffset = local + 30 + localNameLength + localExtraLength
        require(
            rewritten.copyOfRange(payloadOffset, payloadOffset + originalManifest.size)
                .contentEquals(originalManifest),
        ) { "Local manifest payload does not match extracted bytes" }
        transformed.copyInto(rewritten, payloadOffset)

        val crc = CRC32().apply { update(transformed) }.value
        writeZipU32(rewritten, local + 14, crc)
        val central = findZipHeader(rewritten, 0x02014b50L, 46, 28, "manifest.json")
        writeZipU32(rewritten, central + 16, crc)
        return rewritten
    }

    private fun findZipHeader(
        archive: ByteArray,
        signature: Long,
        fixedSize: Int,
        nameLengthOffset: Int,
        name: String,
    ): Int {
        val encodedName = name.toByteArray(Charsets.UTF_8)
        for (offset in 0..archive.size - fixedSize - encodedName.size) {
            if (readZipU32(archive, offset) != signature) continue
            if (readZipU16(archive, offset + nameLengthOffset) != encodedName.size) continue
            val nameOffset = offset + fixedSize
            if (encodedName.indices.all { archive[nameOffset + it] == encodedName[it] }) {
                return offset
            }
        }
        error("ZIP header not found for $name")
    }

    private fun readZipU16(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or
            ((bytes[offset + 1].toInt() and 0xff) shl 8)

    private fun readZipU32(bytes: ByteArray, offset: Int): Long =
        (0 until 4).fold(0L) { value, index ->
            value or ((bytes[offset + index].toLong() and 0xff) shl (index * 8))
        }

    private fun writeZipU32(bytes: ByteArray, offset: Int, value: Long) {
        for (index in 0 until 4) {
            bytes[offset + index] = (value ushr (index * 8)).toByte()
        }
    }

    // ── source records (canonical published release facts) ─────────────────

    private fun publishedSourceRecord(spec: PackageSpec): PackageSourceRecord = PackageSourceRecord(
        repositoryId = GitHubRepositoryIdentity(spec.repositoryId),
        coordinates = GitHubRepositoryCoordinates(spec.ownerLogin, spec.repositoryName),
        release = GitHubReleaseIdentity(spec.releaseId, spec.releaseTag, false),
        asset = GitHubAssetIdentity(spec.assetId, spec.assetName),
        ownerId = spec.ownerId,
    )

    private fun successorSourceRecord(spec: PackageSpec, successorVersion: String): PackageSourceRecord = PackageSourceRecord(
        repositoryId = GitHubRepositoryIdentity(spec.repositoryId),
        coordinates = GitHubRepositoryCoordinates(spec.ownerLogin, spec.repositoryName),
        release = GitHubReleaseIdentity(SYNTHETIC_SUCCESSOR_RELEASE_ID, "v$successorVersion", false),
        asset = GitHubAssetIdentity(SYNTHETIC_SUCCESSOR_ASSET_ID, spec.assetName),
        ownerId = spec.ownerId,
    )

    // ── generic host plumbing (mirrors the per-package contract tests) ─────

    private fun repository(
        root: File,
        bridge: LuaKernelBridge,
        providers: ChannelImplementationProviderRegistry,
    ): InstalledPackageRepository = InstalledPackageRepository(
        store = InstalledPackageStore(root),
        bridge = bridge,
        publisher = { materialized ->
            when (providers.publishInstalledProviders(
                materialized.bindings,
                materialized.failures.mapValues { (id, failure) -> failure.toPackageUnavailable(id) },
            )) {
                is InstalledProvidersPublicationResult.Success -> PackageOutcome.Success(Unit)
                is InstalledProvidersPublicationResult.Rejected -> PackageOutcome.Failure(
                    PackageFailure.Loading(PackageFailure.LoadingDetail.PUBLICATION_REJECTED),
                )
            }
        },
        dispatcher = Dispatchers.Unconfined,
    )

    private fun implementationId(spec: PackageSpec): ChannelImplementationId =
        InstalledProviderId.derive(GitHubRepositoryIdentity(spec.repositoryId))

    private fun indexRecord(root: File, repositoryId: String): StoredProviderRecord =
        success(InstalledPackageStore(root).loadIndex()).index.providers.getValue(GitHubRepositoryIdentity(repositoryId))

    private fun descriptorOf(providers: ChannelImplementationProviderRegistry, id: ChannelImplementationId) =
        (providers.resolve(id) as ChannelProviderResolution.Available).provider.descriptor

    private fun fingerprintOf(providers: ChannelImplementationProviderRegistry, id: ChannelImplementationId) =
        (providers.resolve(id) as ChannelProviderResolution.Available).provider.fingerprint

    private fun fixture(resourcePath: String): ByteArray =
        requireNotNull(javaClass.classLoader?.getResourceAsStream(resourcePath)) {
            "Missing package fixture: $resourcePath"
        }.use { it.readBytes() }

    private fun <T> success(outcome: PackageOutcome<T>): T = when (outcome) {
        is PackageOutcome.Success -> outcome.value
        is PackageOutcome.Failure -> throw AssertionError("Expected success, got ${outcome.error}")
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    private suspend fun <T> withTemporaryDirectory(block: suspend (File) -> T): T {
        val root = createTempDirectory("cross-package-cutover-isolation-").toFile()
        return try {
            block(root)
        } finally {
            root.deleteRecursively()
        }
    }

    /** Benign recording bridge: install/update/rollback materialize descriptors only,
     *  so no kernel callback is expected; counting guards against accidental Lua entry. */
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

    private class PackageSpec(
        val key: String,
        val resourcePath: String,
        val repositoryId: String,
        val ownerLogin: String,
        val repositoryName: String,
        val releaseId: String,
        val releaseTag: String,
        val assetId: String,
        val assetName: String,
        val ownerId: String,
    )

    private fun packageSpecs(): List<PackageSpec> = listOf(
        PackageSpec(
            key = "debug",
            resourcePath = "debug-channel/talkcan-channel.zip",
            repositoryId = "1306065111",
            ownerLogin = "talkcan",
            repositoryName = "debug-channel",
            releaseId = "359174403",
            releaseTag = "v1.3.0",
            assetId = "488198107",
            assetName = "talkcan-channel.zip",
            ownerId = OFFICIAL_OWNER_ID,
        ),
        PackageSpec(
            key = "diagnostics",
            resourcePath = "diagnostics-channel/talkcan-channel-v1.3.1.zip",
            repositoryId = "1305223892",
            ownerLogin = "talkcan",
            repositoryName = "diagnostics-channel",
            releaseId = "359168468",
            releaseTag = "v1.3.1",
            assetId = "488185292",
            assetName = "talkcan-channel.zip",
            ownerId = OFFICIAL_OWNER_ID,
        ),
        PackageSpec(
            key = "journal",
            resourcePath = "journal-channel/talkcan-channel.zip",
            repositoryId = "1309332087",
            ownerLogin = "talkcan",
            repositoryName = "journal-channel",
            releaseId = "360293138",
            releaseTag = "v2.0.0",
            assetId = "491214391",
            assetName = "talkcan-channel.zip",
            ownerId = OFFICIAL_OWNER_ID,
        ),
        PackageSpec(
            key = "keyboard",
            resourcePath = "keyboard-channel/talkcan-channel.zip",
            repositoryId = "1310281239",
            ownerLogin = "talkcan",
            repositoryName = "keyboard-channel",
            releaseId = "359168730",
            releaseTag = "v1.1.0",
            assetId = "488185797",
            assetName = "talkcan-channel.zip",
            ownerId = OFFICIAL_OWNER_ID,
        ),
    )

    private companion object {
        const val OFFICIAL_OWNER_ID = "1224006"

        // Synthetic, canonical positive-decimal provenance IDs for the test-only
        // successor carrier; distinct from every published release/asset ID above.
        const val SYNTHETIC_SUCCESSOR_RELEASE_ID = "900000001"
        const val SYNTHETIC_SUCCESSOR_ASSET_ID = "900000002"
    }
}
