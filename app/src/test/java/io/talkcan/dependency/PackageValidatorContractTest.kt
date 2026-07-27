package io.talkcan.dependency

import io.talkcan.lua.API_VERSION
import io.talkcan.lua.LUA_VERSION
import io.talkcan.lua.ImmutableProgramImage
import io.talkcan.model.ProviderRevisionFingerprint
import io.talkcan.lua.LuaProgramRequirements
import io.talkcan.lua.ProgramImageCreationResult
import io.talkcan.profile.ProfileFieldDeclaration
import io.talkcan.profile.ProfileLimits
import io.talkcan.profile.ProfileSchema
import io.talkcan.profile.ProfileUiControl
import io.talkcan.profile.ProfileUiFieldDeclaration
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.Deflater
import kotlin.io.path.createTempDirectory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract tests for the public static package-validation boundary.  The ZIP writer deliberately
 * emits Unix central/local metadata, while its independent central and local fields allow each
 * raw-parser consistency check to be targeted without testing implementation details.
 */
class PackageValidatorContractTest {
    @Test
    fun `valid source-only package derives canonical modules retains source provenance and hashes exact bytes`() = withTemporaryDirectory { root ->
        val source = sourceRecord()
        val archive = archiveBytes(
            modules = mapOf(
                "main" to SOURCE,
                "client.http" to "return {}",
            ),
        )

        val revision = expectSuccess(validate(root, archive, source))

        assertEquals(setOf("main", "client.http"), revision.sourceMap.keys)
        assertEquals(SOURCE, revision.sourceMap.getValue("main"))
        assertEquals("main", revision.programImage.entryPoint)
        assertEquals("github-repository:123", InstalledProviderId.derive(source.repositoryId).value)
        assertEquals(source, revision.sourceRecord)
        assertEquals(sha256(archive), revision.digest.value)
        assertEquals(revision.digest.value, revision.fingerprint.value)
    }

    @Test
    fun `manifest shape rejects missing unknown duplicate mistyped blank and oversized declarations`() = withTemporaryDirectory { root ->
        val base = manifest()
        val cases = listOf(
            ManifestCase("missing root field", base.replace("\"packageVersion\":\"1.0.0\",", ""), PackageFailure.FormatDetail.MALFORMED_MANIFEST),
            ManifestCase("missing nested field", base.replace("\"summary\":\"Package summary\"", ""), PackageFailure.FormatDetail.MALFORMED_MANIFEST),
            ManifestCase("unknown root field", base.dropLast(1) + ",\"extraField\":true}", PackageFailure.FormatDetail.UNKNOWN_FIELDS),
            ManifestCase("unknown nested field", base.replace("\"summary\":\"Package summary\"", "\"summary\":\"Package summary\",\"extra\":true"), PackageFailure.FormatDetail.UNKNOWN_FIELDS),
            ManifestCase("duplicate key", base.replace("\"repositoryId\":\"123\"", "\"repositoryId\":\"123\",\"repositoryId\":\"123\""), PackageFailure.FormatDetail.DUPLICATE_KEYS),
            ManifestCase("mistyped manifest version", base.replace("\"manifestVersion\":1", "\"manifestVersion\":\"1\""), PackageFailure.FormatDetail.MALFORMED_MANIFEST),
            ManifestCase("mistyped repository identity", base.replace("\"repositoryId\":\"123\"", "\"repositoryId\":123"), PackageFailure.FormatDetail.MALFORMED_MANIFEST),
            ManifestCase("mistyped presentation", "{\"manifestVersion\":1,\"repositoryId\":\"123\",\"packageVersion\":\"1.0.0\",\"entryModule\":\"main\",\"presentation\":[],\"runtime\":{\"luaVersion\":\"$LUA_VERSION\",\"apiVersion\":\"$API_VERSION\"},\"configuration\":{\"data\":{\"fields\":[]},\"ui\":{\"fields\":[]}},\"resources\":{\"mounts\":[]},\"capabilities\":[]}", PackageFailure.FormatDetail.MALFORMED_MANIFEST),
            ManifestCase("blank package version", base.replace("\"packageVersion\":\"1.0.0\"", "\"packageVersion\":\" \\t\""), PackageFailure.FormatDetail.MALFORMED_MANIFEST),
            ManifestCase("blank label", base.replace("\"label\":\"Package\"", "\"label\":\"\""), PackageFailure.FormatDetail.MALFORMED_MANIFEST),
            ManifestCase("blank summary", base.replace("\"summary\":\"Package summary\"", "\"summary\":\" \\n\""), PackageFailure.FormatDetail.MALFORMED_MANIFEST),
        )
        cases.forEach { case ->
            assertFormat(
                validate(root, archiveBytes(manifest = case.json), sourceRecord()),
                case.expected as PackageFailure.FormatDetail,
                case.name,
            )
        }

        val largeManifest = manifest(packageVersion = "v".repeat(400))
        val archive = archiveBytes(manifest = largeManifest)
        val bounds = bounds(maxManifestBytes = largeManifest.toByteArray(UTF_8).size - 1)
        assertFormat(validate(root, archive, sourceRecord(), bounds), PackageFailure.FormatDetail.BOUNDS_EXCEEDED, "oversized manifest")
    }

    @Test
    fun `manifest version runtime and repository assertion fail with typed compatibility or identity outcomes`() = withTemporaryDirectory { root ->
        val cases = listOf(
            ManifestCase("unsupported manifest version", manifest().replace("\"manifestVersion\":1", "\"manifestVersion\":2"), PackageFailure.CompatibilityDetail.UNSUPPORTED_MANIFEST_VERSION),
            ManifestCase("incompatible Lua version", manifest().replace(LUA_VERSION, "Lua 5.3"), PackageFailure.CompatibilityDetail.LUA_VERSION_INCOMPATIBLE),
            ManifestCase("incompatible API version", manifest().replace(API_VERSION, "talkcan-lua-v2"), PackageFailure.CompatibilityDetail.API_VERSION_INCOMPATIBLE),
            ManifestCase("repository identity mismatch", manifest(repositoryId = "124"), PackageFailure.IdentityDetail.REPOSITORY_ID_MISMATCH),
        )
        cases.forEach { case ->
            val outcome = validate(root, archiveBytes(manifest = case.json), sourceRecord())
            when (case.expected) {
                is PackageFailure.CompatibilityDetail -> assertCompatibility(outcome, case.expected, case.name)
                is PackageFailure.IdentityDetail -> assertIdentity(outcome, case.expected, case.name)
                else -> error("wrong case type")
            }
        }

        val renamedCoordinates = sourceRecord(coordinates = GitHubRepositoryCoordinates("renamed-owner", "renamed-repository"))
        val revision = expectSuccess(validate(root, archiveBytes(), renamedCoordinates))
        assertEquals("github-repository:123", InstalledProviderId.derive(revision.sourceRecord.repositoryId).value)
        assertEquals(renamedCoordinates.coordinates, revision.sourceRecord.coordinates)
    }

    @Test
    fun `canonical path grammar rejects traversal aliases separators and collisions`() = withTemporaryDirectory { root ->
        val invalidPaths = listOf(
            "../main.lua", "lua/../main.lua", "/lua/main.lua", "lua//main.lua", "lua\\main.lua",
            "lua/%2fmain.lua", "lua/%5Cmain.lua", "lua/ma%69n.lua", "lua/main\u0000.lua",
            "lua/main.lua/", "lua/Main.lua", "lua/e\u0301.lua",
        )
        invalidPaths.forEach { path ->
            assertFormat(
                validate(root, archiveBytes(entries = validEntries() + FixtureEntry(path, SOURCE.toByteArray(UTF_8))), sourceRecord()),
                if (path == "lua/Main.lua") PackageFailure.FormatDetail.COLLISION else if (path == "lua/main.lua/") PackageFailure.FormatDetail.UNEXPECTED_ENTRY else PackageFailure.FormatDetail.INVALID_MODULE_GRAMMAR,
                path,
            )
        }

        listOf(
            listOf("lua/main.lua", "lua/main.lua") to "exact duplicate",
            listOf("lua/main.lua", "lua/MAIN.lua") to "case collision",
            listOf("lua/e.lua", "lua/e\u0301.lua") to "non-NFC collision input",
        ).forEach { (paths, name) ->
            val entries = listOf(
                FixtureEntry("manifest.json", manifest().toByteArray(UTF_8)),
                FixtureEntry("lua/", ByteArray(0), directory = true),
            ) + paths.map { FixtureEntry(it, SOURCE.toByteArray(UTF_8)) }
            val expected = if (name == "non-NFC collision input") PackageFailure.FormatDetail.INVALID_MODULE_GRAMMAR else PackageFailure.FormatDetail.COLLISION
            assertFormat(validate(root, strictZip(entries), sourceRecord()), expected, name)
        }
    }

    @Test
    fun `archive layout rejects missing manifest unexpected files and unsupported Unix entry kinds`() = withTemporaryDirectory { root ->
        assertFormat(
            validate(root, strictZip(listOf(FixtureEntry("lua/", ByteArray(0), directory = true), FixtureEntry("lua/main.lua", SOURCE.toByteArray(UTF_8)))), sourceRecord()),
            PackageFailure.FormatDetail.MISSING_MANIFEST,
            "missing manifest",
        )

        val cases = listOf(
            EntryCase("asset", FixtureEntry("assets/icon.png", byteArrayOf(1)), PackageFailure.FormatDetail.UNEXPECTED_ENTRY),
            EntryCase("bytecode path", FixtureEntry("lua/main.luac", byteArrayOf(1)), PackageFailure.FormatDetail.UNEXPECTED_ENTRY),
            EntryCase("symlink", FixtureEntry("lua/main.lua", SOURCE.toByteArray(UTF_8), unixMode = 0xA1FF), PackageFailure.FormatDetail.UNEXPECTED_ENTRY),
            EntryCase("executable", FixtureEntry("lua/main.lua", SOURCE.toByteArray(UTF_8), unixMode = 0x81ED), PackageFailure.FormatDetail.UNEXPECTED_ENTRY),
            EntryCase("non Unix host", FixtureEntry("lua/main.lua", SOURCE.toByteArray(UTF_8), versionMadeBy = 0x0014), PackageFailure.FormatDetail.INVALID_ZIP),
            EntryCase("unsupported compression", FixtureEntry("lua/main.lua", SOURCE.toByteArray(UTF_8), method = 12), PackageFailure.FormatDetail.UNSUPPORTED_COMPRESSION),
            EntryCase("encrypted", FixtureEntry("lua/main.lua", SOURCE.toByteArray(UTF_8), flags = 1), PackageFailure.FormatDetail.ENCRYPTED_ENTRY),
            EntryCase("data descriptor", FixtureEntry("lua/main.lua", SOURCE.toByteArray(UTF_8), flags = 8), PackageFailure.FormatDetail.INVALID_ZIP),
        )
        cases.forEach { case ->
            assertFormat(validate(root, strictZip(validEntries(module = case.entry)), sourceRecord()), case.expected, case.name)
        }
    }

    @Test
    fun `raw ZIP parser rejects multi disk and every local central consistency mismatch`() = withTemporaryDirectory { root ->
        val archive = strictZip(validEntries())
        assertFormat(validate(root, mutateEocd(archive) { it[4] = 1 }, sourceRecord()), PackageFailure.FormatDetail.INVALID_ZIP, "multi disk")

        val mutations = listOf(
            "version" to { bytes: ByteArray -> mutateLocal(bytes, 4) { it + 1 } },
            "flags" to { bytes: ByteArray -> mutateLocal(bytes, 6) { it xor 0x0800 } },
            "modification time" to { bytes: ByteArray -> mutateLocal(bytes, 10) { it + 1 } },
            "modification date" to { bytes: ByteArray -> mutateLocal(bytes, 12) { it + 1 } },
            "method" to { bytes: ByteArray -> mutateLocal(bytes, 8) { 8 } },
            "CRC" to { bytes: ByteArray -> mutateLocal32(bytes, 14) { it xor 1 } },
            "compressed size" to { bytes: ByteArray -> mutateLocal32(bytes, 18) { it + 1 } },
            "uncompressed size" to { bytes: ByteArray -> mutateLocal32(bytes, 22) { it + 1 } },
            "name" to { bytes: ByteArray -> mutateLocalName(bytes) },
            "extra" to { bytes: ByteArray -> mutateLocalExtra(bytes) },
        )
        mutations.forEach { (name, mutate) ->
            assertFormat(validate(root, mutate(archive), sourceRecord()), PackageFailure.FormatDetail.INVALID_ZIP, "local central $name")
        }
    }

    @Test
    fun `strict content decoding rejects invalid UTF8 Lua binary chunks and corrupted content`() = withTemporaryDirectory { root ->
        val cases = listOf(
            "invalid manifest UTF8" to strictZip(validEntries(manifest = byteArrayOf(0xC3.toByte(), 0x28))),
            "invalid Lua UTF8" to strictZip(validEntries(module = FixtureEntry("lua/main.lua", byteArrayOf(0xC3.toByte(), 0x28)))),
            "Lua bytecode" to strictZip(validEntries(module = FixtureEntry("lua/main.lua", byteArrayOf(0x1B, 0x4C, 0x75, 0x61)))),
            "CRC corrupt payload" to corruptFirstPayload(strictZip(validEntries())),
        )
        assertFormat(validate(root, cases[0].second, sourceRecord()), PackageFailure.FormatDetail.MALFORMED_MANIFEST, cases[0].first)
        assertFormat(validate(root, cases[1].second, sourceRecord()), PackageFailure.FormatDetail.INVALID_ZIP, cases[1].first)
        assertFormat(validate(root, cases[2].second, sourceRecord()), PackageFailure.FormatDetail.BYTECODE_PROHIBITED, cases[2].first)
        assertIntegrity(validate(root, cases[3].second, sourceRecord()), PackageFailure.IntegrityDetail.CORRUPTED_ARCHIVE, cases[3].first)
    }

    @Test
    fun `every configured archive resource bound accepts exact edge and rejects one beyond`() = withTemporaryDirectory { root ->
        val baseline = archiveBytes()
        assertBoundsEdge(root, "artifact", baseline, bounds(maxArtifactBytes = baseline.size.toLong()), bounds(maxArtifactBytes = baseline.size.toLong() - 1))

        val entryEdge = strictZip(validEntries())
        assertBoundsEdge(root, "entry count", entryEdge, bounds(maxEntryCount = 3), bounds(maxEntryCount = 2))

        val manifestText = manifest()
        val manifestEdge = archiveBytes(manifest = manifestText)
        assertBoundsEdge(root, "manifest bytes", manifestEdge, bounds(maxManifestBytes = manifestText.toByteArray(UTF_8).size), bounds(maxManifestBytes = manifestText.toByteArray(UTF_8).size - 1))

        val path = "lua/long_name.lua"
        val pathEdge = strictZip(validEntries(
            manifest = manifest(entryModule = "long_name").toByteArray(UTF_8),
            module = FixtureEntry(path, SOURCE.toByteArray(UTF_8)),
        ))
        assertBoundsEdge(root, "path bytes", pathEdge, bounds(maxPathBytes = path.toByteArray(UTF_8).size), bounds(maxPathBytes = path.toByteArray(UTF_8).size - 1))

        val module = "x".repeat(32)
        val moduleEdge = strictZip(validEntries(module = FixtureEntry("lua/main.lua", module.toByteArray(UTF_8))))
        assertBoundsEdge(root, "per source bytes", moduleEdge, bounds(maxPerModuleBytes = module.length, maxTotalSourceBytes = module.length), bounds(maxPerModuleBytes = module.length - 1, maxTotalSourceBytes = module.length))

        val totalEntries = listOf(
            FixtureEntry("manifest.json", manifest().toByteArray(UTF_8)),
            FixtureEntry("lua/", ByteArray(0), directory = true),
            FixtureEntry("lua/main.lua", "a".repeat(16).toByteArray(UTF_8)),
            FixtureEntry("lua/client.lua", "b".repeat(16).toByteArray(UTF_8)),
        )
        val totalEdge = strictZip(totalEntries)
        assertBoundsEdge(root, "total source bytes", totalEdge, bounds(maxPerModuleBytes = 16, maxTotalSourceBytes = 32), bounds(maxPerModuleBytes = 16, maxTotalSourceBytes = 31))

        val expanded = "z".repeat(1_024).toByteArray(UTF_8)
        val deflated = strictZip(validEntries(module = FixtureEntry("lua/main.lua", expanded, method = 8)))
        assertBoundsEdge(root, "compression expansion", deflated, bounds(maxExpansionRatio = expansionRatio(deflated, "lua/main.lua")), bounds(maxExpansionRatio = expansionRatio(deflated, "lua/main.lua") - 0.01))
    }

    @Test
    fun `static validation does not execute an adversarial Lua payload`() = withTemporaryDirectory { root ->
        val marker = File(root, "executed")
        val payload = "error('package payload must not execute during validation'); os.execute([[touch ${marker.absolutePath}]])"

        expectSuccess(validate(root, archiveBytes(modules = mapOf("main" to payload)), sourceRecord()))

        assertFalse("validation must not create a Lua runtime or execute the package payload", marker.exists())
    }

    private fun assertBoundsEdge(root: File, name: String, archive: ByteArray, atLimit: PackageValidationBounds, overLimit: PackageValidationBounds) {
        expectSuccess(validate(root, archive, sourceRecord(), atLimit))
        assertFormat(validate(root, archive, sourceRecord(), overLimit), PackageFailure.FormatDetail.BOUNDS_EXCEEDED, "$name beyond limit")
    }

    private fun validate(root: File, archive: ByteArray, source: PackageSourceRecord, bounds: PackageValidationBounds = generousBounds()): PackageOutcome<ValidatedPackageRevision> =
        PackageValidator.validatePackage(ByteArrayInputStream(archive), source, File(root, "${System.nanoTime()}.zip"), bounds)

    private fun expectSuccess(outcome: PackageOutcome<ValidatedPackageRevision>): ValidatedPackageRevision = when (outcome) {
        is PackageOutcome.Success -> outcome.value
        is PackageOutcome.Failure -> throw AssertionError("Expected success, got ${outcome.error}")
    }

    private fun assertFormat(outcome: PackageOutcome<*>, detail: PackageFailure.FormatDetail, case: String) {
        val failure = (outcome as? PackageOutcome.Failure)?.error
        assertTrue("$case should fail as FORMAT/$detail but was $failure", failure is PackageFailure.Format && failure.detail == detail)
    }

    private fun assertCapability(outcome: PackageOutcome<*>, detail: PackageFailure.CapabilityDetail, case: String) {
        val failure = (outcome as? PackageOutcome.Failure)?.error
        assertTrue("$case should fail as CAPABILITY/$detail but was $failure", failure is PackageFailure.Capability && failure.detail == detail)
    }

    private fun assertCompatibility(outcome: PackageOutcome<*>, detail: PackageFailure.CompatibilityDetail, case: String) {
        val failure = (outcome as? PackageOutcome.Failure)?.error
        assertTrue("$case should fail as COMPATIBILITY/$detail but was $failure", failure is PackageFailure.Compatibility && failure.detail == detail)
    }

    private fun assertIdentity(outcome: PackageOutcome<*>, detail: PackageFailure.IdentityDetail, case: String) {
        val failure = (outcome as? PackageOutcome.Failure)?.error
        assertTrue("$case should fail as IDENTITY/$detail but was $failure", failure is PackageFailure.Identity && failure.detail == detail)
    }

    private fun assertIntegrity(outcome: PackageOutcome<*>, detail: PackageFailure.IntegrityDetail, case: String) {
        val failure = (outcome as? PackageOutcome.Failure)?.error
        assertTrue("$case should fail as INTEGRITY/$detail but was $failure", failure is PackageFailure.Integrity && failure.detail == detail)
    }

    private fun sourceRecord(coordinates: GitHubRepositoryCoordinates = GitHubRepositoryCoordinates("owner", "repository")) = PackageSourceRecord(
        repositoryId = GitHubRepositoryIdentity("123"),
        coordinates = coordinates,
        release = GitHubReleaseIdentity("456", "v1.0.0", false),
        asset = GitHubAssetIdentity("789", "package.zip"),
        ownerId = "9000001",
    )

    private fun manifest(
        repositoryId: String = "123",
        packageVersion: String = "1.0.0",
        entryModule: String = "main",
        configuration: String = """{"schemaVersion":1,"data":{"fields":[],"additionalProperties":false},"ui":{"fields":[]}}""",
        resources: String = """{"mounts":[]}""",
        profileTypes: String = "[]",
        choiceResolvers: String = "[]",
        workQueues: String = "[]",
        capabilities: String = "[]",
        injectAdditionalProperties: Boolean = true,
    ): String {
        var resolvedConfig = configuration
        if (injectAdditionalProperties && resolvedConfig.contains("\"data\":{") && !resolvedConfig.contains("\"additionalProperties\"")) {
            resolvedConfig = resolvedConfig.replace("\"data\":{", "\"data\":{\"additionalProperties\":false,")
        }
        return """{"manifestVersion":1,"repositoryId":"$repositoryId","packageVersion":"$packageVersion","entryModule":"$entryModule","presentation":{"label":"Package","summary":"Package summary"},"runtime":{"luaVersion":"$LUA_VERSION","apiVersion":"$API_VERSION"},"configuration":$resolvedConfig,"resources":$resources,"profileTypes":$profileTypes,"choiceResolvers":$choiceResolvers,"workQueues":$workQueues,"capabilities":$capabilities}"""
    }

    private fun archiveBytes(
        manifest: String = manifest(),
        modules: Map<String, String> = mapOf("main" to SOURCE),
        entries: List<FixtureEntry>? = null,
    ): ByteArray {
        val resolvedEntries = entries ?: buildList {
            add(FixtureEntry("manifest.json", manifest.toByteArray(UTF_8)))
            add(FixtureEntry("lua/", ByteArray(0), directory = true))
            modules.forEach { (module, source) ->
                add(FixtureEntry("lua/${module.replace('.', '/')}.lua", source.toByteArray(UTF_8)))
            }
        }
        return strictZip(resolvedEntries)
    }

    private fun validEntries(
        manifest: ByteArray = manifest().toByteArray(UTF_8),
        module: FixtureEntry = FixtureEntry("lua/main.lua", SOURCE.toByteArray(UTF_8)),
    ): List<FixtureEntry> = listOf(
        FixtureEntry("manifest.json", manifest),
        FixtureEntry("lua/", ByteArray(0), directory = true),
        module,
    )

    private fun strictZip(entries: List<FixtureEntry>): ByteArray {
        val out = ByteArrayOutputStream()
        val central = entries.map { entry ->
            val localName = entry.localName.toByteArray(UTF_8)
            val localData = encoded(entry.bytes, entry.localMethod)
            val crc = CRC32().apply { update(entry.bytes) }.value
            val offset = out.size().toLong()
            out.u32(LOCAL_SIGNATURE)
            out.u16(entry.localVersionNeeded)
            out.u16(entry.localFlags)
            out.u16(entry.localMethod)
            out.u16(0)
            out.u16(0)
            out.u32(crc)
            out.u32(localData.size.toLong())
            out.u32(entry.bytes.size.toLong())
            out.u16(localName.size)
            out.u16(entry.localExtra.size)
            out.write(localName)
            out.write(entry.localExtra)
            out.write(localData)
            CentralEntry(entry, crc, localData.size, offset)
        }
        val centralOffset = out.size().toLong()
        central.forEach { item ->
            val entry = item.entry
            val centralName = entry.name.toByteArray(UTF_8)
            out.u32(CENTRAL_SIGNATURE)
            out.u16(entry.versionMadeBy)
            out.u16(entry.versionNeeded)
            out.u16(entry.flags)
            out.u16(entry.method)
            out.u16(0)
            out.u16(0)
            out.u32(item.crc)
            out.u32(if (entry.method == 0) entry.bytes.size.toLong() else item.compressedSize.toLong())
            out.u32(entry.bytes.size.toLong())
            out.u16(centralName.size)
            out.u16(entry.extra.size)
            out.u16(0)
            out.u16(0)
            out.u16(0)
            out.u32(entry.unixMode.toLong() shl 16)
            out.u32(item.localOffset)
            out.write(centralName)
            out.write(entry.extra)
        }
        val centralSize = out.size().toLong() - centralOffset
        out.u32(EOCD_SIGNATURE)
        out.u16(0)
        out.u16(0)
        out.u16(entries.size)
        out.u16(entries.size)
        out.u32(centralSize)
        out.u32(centralOffset)
        out.u16(0)
        return out.toByteArray()
    }

    private fun encoded(data: ByteArray, method: Int): ByteArray = when (method) {
        0, 12 -> data
        8 -> {
            val deflater = Deflater(Deflater.DEFAULT_COMPRESSION, true)
            try {
                deflater.setInput(data)
                deflater.finish()
                val compressed = ByteArrayOutputStream()
                val buffer = ByteArray(512)
                while (!deflater.finished()) {
                    compressed.write(buffer, 0, deflater.deflate(buffer))
                }
                compressed.toByteArray()
            } finally {
                deflater.end()
            }
        }
        else -> data
    }

    private fun mutateEocd(archive: ByteArray, change: (ByteArray) -> Unit): ByteArray = archive.copyOf().also { bytes ->
        val offset = bytes.size - 22
        val eocd = bytes.copyOfRange(offset, bytes.size)
        change(eocd)
        eocd.copyInto(bytes, offset)
    }

    private fun mutateLocal(archive: ByteArray, offset: Int, change: (Int) -> Int): ByteArray = archive.copyOf().also { bytes ->
        val value = u16(bytes, offset)
        put16(bytes, offset, change(value))
    }

    private fun mutateLocal32(archive: ByteArray, offset: Int, change: (Long) -> Long): ByteArray = archive.copyOf().also { bytes ->
        val value = u32(bytes, offset)
        put32(bytes, offset, change(value))
    }

    private fun mutateLocalName(archive: ByteArray): ByteArray = archive.copyOf().also { bytes ->
        val nameOffset = 30
        bytes[nameOffset] = 'M'.code.toByte()
    }

    private fun mutateLocalExtra(archive: ByteArray): ByteArray = archive.copyOf().also { bytes ->
        val extraLengthOffset = 28
        put16(bytes, extraLengthOffset, 4)
        val nameLength = u16(bytes, 26)
        val extraOffset = 30 + nameLength
        bytes[extraOffset] = 0x55
        bytes[extraOffset + 1] = 0x54
        bytes[extraOffset + 2] = 0
        bytes[extraOffset + 3] = 0
    }

    private fun corruptFirstPayload(archive: ByteArray): ByteArray = archive.copyOf().also { bytes ->
        val nameLength = u16(bytes, 26)
        bytes[30 + nameLength] = (bytes[30 + nameLength].toInt() xor 0x01).toByte()
    }

    private fun expansionRatio(archive: ByteArray, target: String): Double {
        var position = 0
        while (u32(archive, position) != CENTRAL_SIGNATURE) {
            val nameLength = u16(archive, position + 26)
            val extraLength = u16(archive, position + 28)
            val compressed = u32(archive, position + 18).toInt()
            val name = archive.copyOfRange(position + 30, position + 30 + nameLength).toString(UTF_8)
            if (name == target) return u32(archive, position + 22).toDouble() / compressed
            position += 30 + nameLength + extraLength + compressed
        }
        error("missing $target")
    }

    private fun bounds(
        maxArtifactBytes: Long = 1_000_000,
        maxEntryCount: Int = 32,
        maxManifestBytes: Int = 100_000,
        maxPathBytes: Int = 512,
        maxPerModuleBytes: Int = 100_000,
        maxTotalSourceBytes: Int = 200_000,
        maxExpansionRatio: Double = 100.0,
    ) = PackageValidationBounds(maxArtifactBytes, maxEntryCount, maxManifestBytes, maxPathBytes, maxPerModuleBytes, maxTotalSourceBytes, maxExpansionRatio)

    private fun generousBounds() = bounds()

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun ByteArrayOutputStream.u16(value: Int) {
        write(value and 0xFF)
        write((value ushr 8) and 0xFF)
    }

    private fun ByteArrayOutputStream.u32(value: Long) {
        write((value and 0xFF).toInt())
        write(((value ushr 8) and 0xFF).toInt())
        write(((value ushr 16) and 0xFF).toInt())
        write(((value ushr 24) and 0xFF).toInt())
    }

    private fun u16(bytes: ByteArray, offset: Int): Int = (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)
    private fun u32(bytes: ByteArray, offset: Int): Long = (bytes[offset].toLong() and 0xFF) or ((bytes[offset + 1].toLong() and 0xFF) shl 8) or ((bytes[offset + 2].toLong() and 0xFF) shl 16) or ((bytes[offset + 3].toLong() and 0xFF) shl 24)
    private fun put16(bytes: ByteArray, offset: Int, value: Int) { bytes[offset] = value.toByte(); bytes[offset + 1] = (value ushr 8).toByte() }
    private fun put32(bytes: ByteArray, offset: Int, value: Long) { bytes[offset] = value.toByte(); bytes[offset + 1] = (value ushr 8).toByte(); bytes[offset + 2] = (value ushr 16).toByte(); bytes[offset + 3] = (value ushr 24).toByte() }

    private fun <T> withTemporaryDirectory(block: (File) -> T): T {
        val root = createTempDirectory("package-validator-contract-").toFile()
        return try { block(root) } finally { root.deleteRecursively() }
    }

    private data class ManifestCase(val name: String, val json: String, val expected: Any)
    private data class EntryCase(val name: String, val entry: FixtureEntry, val expected: PackageFailure.FormatDetail)
    private data class CentralEntry(val entry: FixtureEntry, val crc: Long, val compressedSize: Int, val localOffset: Long)
    private data class FixtureEntry(
        val name: String,
        val bytes: ByteArray,
        val directory: Boolean = false,
        val method: Int = 0,
        val flags: Int = 0,
        val versionMadeBy: Int = 0x0314,
        val versionNeeded: Int = 20,
        val unixMode: Int = if (directory) 0x41ED else 0x81A4,
        val extra: ByteArray = ByteArray(0),
        val localName: String = name,
        val localMethod: Int = method,
        val localFlags: Int = flags,
        val localVersionNeeded: Int = versionNeeded,
        val localExtra: ByteArray = extra,
    )

    private inline fun <reified T : Throwable> assertThrows(block: () -> Unit) {
        try {
            block()
            org.junit.Assert.fail("Expected ${T::class.java.simpleName} to be thrown")
        } catch (e: Throwable) {
            if (e !is T) {
                throw AssertionError("Expected ${T::class.java.simpleName} but got ${e::class.java.simpleName}", e)
            }
        }
    }

    @Test
    fun `package capabilities define stable identifiers with defensive immutability`() {
        assertEquals("audio.transcription", PackageCapability.AUDIO_TRANSCRIPTION)
        assertEquals("audio.synthesis", PackageCapability.AUDIO_SYNTHESIS)
        assertEquals("audio.playback", PackageCapability.AUDIO_PLAYBACK)
        assertEquals("storage.files", PackageCapability.STORAGE_FILES)
        assertEquals("audio.files", PackageCapability.AUDIO_FILES)
        assertEquals("keyboard.output", PackageCapability.KEYBOARD_OUTPUT)
        assertEquals("network.http", PackageCapability.NETWORK_HTTP)
        assertEquals("profiles.read", PackageCapability.PROFILES_READ)
        assertEquals("secrets.read", PackageCapability.SECRETS_READ)
        assertEquals("work.queue", PackageCapability.WORK_QUEUE)

        assertEquals(
            setOf("audio.transcription", "audio.synthesis", "audio.playback", "storage.files", "audio.files", "keyboard.output", "network.http", "profiles.read", "secrets.read", "work.queue"),
            PackageCapability.ALL
        )
        assertEquals(
            setOf("network.http", "profiles.read", "secrets.read"),
            PackageCapability.RESOLVER_ELIGIBLE
        )

        assertThrows<UnsupportedOperationException> {
            (PackageCapability.ALL as MutableSet<String>).add("audio.extra")
        }
    }

    @Test
    fun `package configuration limits define finite limits`() {
        assertEquals(32, PackageConfigurationLimits.MAX_FIELDS)
        assertEquals(64, PackageConfigurationLimits.MAX_FIELD_ID_BYTES)
        assertEquals(128, PackageConfigurationLimits.MAX_LABEL_BYTES)
        assertEquals(512, PackageConfigurationLimits.MAX_HELP_BYTES)
        assertEquals(16384, PackageConfigurationLimits.MAX_STRING_VALUE_BYTES)
        assertEquals(65536, PackageConfigurationLimits.MAX_PAYLOAD_BYTES)
    }

    @Test
    fun `configuration field declaration string field constructor invariants and immutability`() {
        val valid = ConfigurationFieldDeclaration.StringField("valid_id", "default", listOf("default", "other"))
        assertEquals("valid_id", valid.id)
        assertEquals("default", valid.default)
        assertEquals(listOf("default", "other"), valid.allowedValues)

        assertThrows<IllegalArgumentException> {
            ConfigurationFieldDeclaration.StringField("Invalid_Id", "default", null)
        }
        assertThrows<IllegalArgumentException> {
            ConfigurationFieldDeclaration.StringField("1id", "default", null)
        }
        assertThrows<IllegalArgumentException> {
            ConfigurationFieldDeclaration.StringField("", "default", null)
        }
        val tooLongId = "a".repeat(65)
        assertThrows<IllegalArgumentException> {
            ConfigurationFieldDeclaration.StringField(tooLongId, "default", null)
        }

        val largeDefault = "a".repeat(16385)
        assertThrows<IllegalArgumentException> {
            ConfigurationFieldDeclaration.StringField("id", largeDefault, null)
        }

        assertThrows<IllegalArgumentException> {
            ConfigurationFieldDeclaration.StringField("id", "default", emptyList())
        }
        assertThrows<IllegalArgumentException> {
            ConfigurationFieldDeclaration.StringField("id", "default", listOf("other"))
        }
        assertThrows<IllegalArgumentException> {
            ConfigurationFieldDeclaration.StringField("id", "default", listOf("default", "default"))
        }
        assertThrows<IllegalArgumentException> {
            ConfigurationFieldDeclaration.StringField("id", "default", listOf("default", "a".repeat(16385)))
        }

        val mutableInput = mutableListOf("default", "other")
        val field = ConfigurationFieldDeclaration.StringField("id", "default", mutableInput)
        mutableInput.add("third")
        assertEquals(2, field.allowedValues?.size)
        assertFalse(field.allowedValues!!.contains("third"))

        assertThrows<UnsupportedOperationException> {
            (field.allowedValues as MutableList<String>).add("third")
        }
    }

    @Test
    fun `configuration field declaration boolean field constructor invariants`() {
        val valid = ConfigurationFieldDeclaration.BooleanField("valid_id", true)
        assertEquals("valid_id", valid.id)
        assertTrue(valid.default)

        assertThrows<IllegalArgumentException> {
            ConfigurationFieldDeclaration.BooleanField("Invalid_Id", true)
        }
        assertThrows<IllegalArgumentException> {
            ConfigurationFieldDeclaration.BooleanField("", true)
        }
    }

    @Test
    fun `configuration field declaration integer field constructor invariants`() {
        val valid = ConfigurationFieldDeclaration.IntegerField("valid_id", 10L, 0L, 20L)
        assertEquals("valid_id", valid.id)
        assertEquals(10L, valid.default)
        assertEquals(0L, valid.minimum)
        assertEquals(20L, valid.maximum)

        assertThrows<IllegalArgumentException> {
            ConfigurationFieldDeclaration.IntegerField("Invalid_Id", 10L, null, null)
        }

        assertThrows<IllegalArgumentException> {
            ConfigurationFieldDeclaration.IntegerField("id", 10L, 20L, 10L)
        }

        assertThrows<IllegalArgumentException> {
            ConfigurationFieldDeclaration.IntegerField("id", 5L, 10L, 20L)
        }

        assertThrows<IllegalArgumentException> {
            ConfigurationFieldDeclaration.IntegerField("id", 25L, 10L, 20L)
        }
    }

    @Test
    fun `configuration data declaration constructor invariants and immutability`() {
        val fields = listOf(
            ConfigurationFieldDeclaration.StringField("s", "val", null),
            ConfigurationFieldDeclaration.BooleanField("b", true),
            ConfigurationFieldDeclaration.IntegerField("i", 42L, null, null)
        )
        val decl = ConfigurationDataDeclaration(fields)
        assertEquals(3, decl.fields.size)

        assertThrows<IllegalArgumentException> {
            ConfigurationDataDeclaration(
                listOf(
                    ConfigurationFieldDeclaration.StringField("id", "a", null),
                    ConfigurationFieldDeclaration.BooleanField("id", true)
                )
            )
        }

        val oversizedFields = (1..33).map { i ->
            ConfigurationFieldDeclaration.BooleanField("b_$i", true)
        }
        assertThrows<IllegalArgumentException> {
            ConfigurationDataDeclaration(oversizedFields)
        }

        val mutableInput = fields.toMutableList()
        val dataDecl = ConfigurationDataDeclaration(mutableInput)
        mutableInput.add(ConfigurationFieldDeclaration.BooleanField("extra", false))
        assertEquals(3, dataDecl.fields.size)

        assertThrows<UnsupportedOperationException> {
            (dataDecl.fields as MutableList<ConfigurationFieldDeclaration>).add(
                ConfigurationFieldDeclaration.BooleanField("extra", false)
            )
        }
    }

    @Test
    fun `ui choice constructor invariants`() {
        val valid = UiChoice("val", "label")
        assertEquals("val", valid.value)
        assertEquals("label", valid.label)

        assertThrows<IllegalArgumentException> {
            UiChoice("a".repeat(16385), "label")
        }

        assertThrows<IllegalArgumentException> {
            UiChoice("val", "   ")
        }
        assertThrows<IllegalArgumentException> {
            UiChoice("val", "")
        }

        assertThrows<IllegalArgumentException> {
            UiChoice("val", "a".repeat(129))
        }
    }

    @Test
    fun `ui field declaration constructor invariants and immutability`() {
        val choices = listOf(UiChoice("v1", "L1"), UiChoice("v2", "L2"))
        val validChoice = UiFieldDeclaration("choice_field", UiControl.CHOICE, "Select option", "Help message", choices)
        assertEquals("choice_field", validChoice.field)
        assertEquals(UiControl.CHOICE, validChoice.control)
        assertEquals("Select option", validChoice.label)
        assertEquals("Help message", validChoice.help)
        assertEquals(choices, validChoice.choices)

        assertThrows<IllegalArgumentException> {
            UiFieldDeclaration("ChoiceField", UiControl.CHOICE, "Select option", null, choices)
        }

        assertThrows<IllegalArgumentException> {
            UiFieldDeclaration("choice_field", UiControl.CHOICE, "", null, choices)
        }

        assertThrows<IllegalArgumentException> {
            UiFieldDeclaration("choice_field", UiControl.CHOICE, "a".repeat(129), null, choices)
        }

        assertThrows<IllegalArgumentException> {
            UiFieldDeclaration("choice_field", UiControl.CHOICE, "Select", "a".repeat(513), choices)
        }

        assertThrows<IllegalArgumentException> {
            UiFieldDeclaration("choice_field", UiControl.CHOICE, "Select", null, null)
        }
        assertThrows<IllegalArgumentException> {
            UiFieldDeclaration("choice_field", UiControl.CHOICE, "Select", null, emptyList())
        }

        val manyChoices = (1..257).map { i -> UiChoice("v_$i", "L_$i") }
        val accepted = UiFieldDeclaration("choice_field", UiControl.CHOICE, "Select", null, manyChoices)
        assertEquals(257, accepted.choices!!.size)

        assertThrows<IllegalArgumentException> {
            UiFieldDeclaration(
                "choice_field",
                UiControl.CHOICE,
                "Select",
                null,
                listOf(UiChoice("v1", "L1"), UiChoice("v1", "L2"))
            )
        }

        assertThrows<IllegalArgumentException> {
            UiFieldDeclaration(
                "choice_field",
                UiControl.CHOICE,
                "Select",
                null,
                listOf(UiChoice("v1", "L1"), UiChoice("v2", "L1"))
            )
        }

        assertThrows<IllegalArgumentException> {
            UiFieldDeclaration("text_field", UiControl.TEXT, "Label", null, choices)
        }

        val mutableInput = choices.toMutableList()
        val uiField = UiFieldDeclaration("choice_field", UiControl.CHOICE, "Select", null, mutableInput)
        mutableInput.add(UiChoice("v3", "L3"))
        assertEquals(2, uiField.choices?.size)

        assertThrows<UnsupportedOperationException> {
            (uiField.choices as MutableList<UiChoice>).add(UiChoice("v3", "L3"))
        }
    }

    @Test
    fun `configuration ui declaration constructor invariants and immutability`() {
        val fields = listOf(
            UiFieldDeclaration("text_field", UiControl.TEXT, "Text label", null, null),
            UiFieldDeclaration("toggle_field", UiControl.TOGGLE, "Toggle label", null, null)
        )
        val valid = ConfigurationUiDeclaration(fields)
        assertEquals(2, valid.fields.size)

        assertThrows<IllegalArgumentException> {
            ConfigurationUiDeclaration(
                listOf(
                    UiFieldDeclaration("field_1", UiControl.TEXT, "Label 1", null, null),
                    UiFieldDeclaration("field_1", UiControl.TOGGLE, "Label 2", null, null)
                )
            )
        }

        val oversizedFields = (1..33).map { i ->
            UiFieldDeclaration("f_$i", UiControl.TOGGLE, "L_$i", null, null)
        }
        assertThrows<IllegalArgumentException> {
            ConfigurationUiDeclaration(oversizedFields)
        }

        val mutableInput = fields.toMutableList()
        val uiDecl = ConfigurationUiDeclaration(mutableInput)
        mutableInput.add(UiFieldDeclaration("extra", UiControl.TOGGLE, "Extra", null, null))
        assertEquals(2, uiDecl.fields.size)

        assertThrows<UnsupportedOperationException> {
            (uiDecl.fields as MutableList<UiFieldDeclaration>).add(
                UiFieldDeclaration("extra", UiControl.TOGGLE, "Extra", null, null)
            )
        }
    }

    @Test
    fun `package configuration declaration constructor invariants`() {
        val data = ConfigurationDataDeclaration(
            listOf(
                ConfigurationFieldDeclaration.StringField("str_unconstrained", "default", null),
                ConfigurationFieldDeclaration.StringField("str_constrained", "v1", listOf("v1", "v2")),
                ConfigurationFieldDeclaration.BooleanField("bool", false),
                ConfigurationFieldDeclaration.IntegerField("int", 10L, 0L, 100L)
            )
        )
        val ui = ConfigurationUiDeclaration(
            listOf(
                UiFieldDeclaration("str_unconstrained", UiControl.TEXT, "Text label", null, null),
                UiFieldDeclaration("str_constrained", UiControl.CHOICE, "Choice label", null, listOf(UiChoice("v1", "L1"), UiChoice("v2", "L2"))),
                UiFieldDeclaration("bool", UiControl.TOGGLE, "Toggle label", null, null),
                UiFieldDeclaration("int", UiControl.NUMBER, "Number label", null, null)
            )
        )

        val config = PackageConfigurationDeclaration(data, ui)
        assertEquals(data, config.data)
        assertEquals(ui, config.ui)

        val mismatchedUi1 = ConfigurationUiDeclaration(
            ui.fields + UiFieldDeclaration("extra_ui", UiControl.TEXT, "Extra label", null, null)
        )
        assertThrows<IllegalArgumentException> {
            PackageConfigurationDeclaration(data, mismatchedUi1)
        }

        val mismatchedData1 = ConfigurationDataDeclaration(
            data.fields + ConfigurationFieldDeclaration.BooleanField("extra_data", true)
        )
        assertThrows<IllegalArgumentException> {
            PackageConfigurationDeclaration(mismatchedData1, ui)
        }

        val badData1 = ConfigurationDataDeclaration(
            listOf(ConfigurationFieldDeclaration.StringField("f", "v", null))
        )
        val badUi1 = ConfigurationUiDeclaration(
            listOf(UiFieldDeclaration("f", UiControl.CHOICE, "Label", null, listOf(UiChoice("v", "L"))))
        )
        assertThrows<IllegalArgumentException> {
            PackageConfigurationDeclaration(badData1, badUi1)
        }

        val badData2 = ConfigurationDataDeclaration(
            listOf(ConfigurationFieldDeclaration.StringField("f", "v", listOf("v", "other")))
        )
        val badUi2 = ConfigurationUiDeclaration(
            listOf(UiFieldDeclaration("f", UiControl.TEXT, "Label", null, null))
        )
        assertThrows<IllegalArgumentException> {
            PackageConfigurationDeclaration(badData2, badUi2)
        }

        val badData3 = ConfigurationDataDeclaration(
            listOf(ConfigurationFieldDeclaration.BooleanField("f", true))
        )
        val badUi3 = ConfigurationUiDeclaration(
            listOf(UiFieldDeclaration("f", UiControl.NUMBER, "Label", null, null))
        )
        assertThrows<IllegalArgumentException> {
            PackageConfigurationDeclaration(badData3, badUi3)
        }

        val badData4 = ConfigurationDataDeclaration(
            listOf(ConfigurationFieldDeclaration.IntegerField("f", 10L, null, null))
        )
        val badUi4 = ConfigurationUiDeclaration(
            listOf(UiFieldDeclaration("f", UiControl.TOGGLE, "Label", null, null))
        )
        assertThrows<IllegalArgumentException> {
            PackageConfigurationDeclaration(badData4, badUi4)
        }

        val badUiChoice1 = ConfigurationUiDeclaration(
            listOf(
                UiFieldDeclaration(
                    "str_constrained",
                    UiControl.CHOICE,
                    "Choice label",
                    null,
                    listOf(UiChoice("v1", "L1"))
                ),
                UiFieldDeclaration("str_unconstrained", UiControl.TEXT, "Text label", null, null),
                UiFieldDeclaration("bool", UiControl.TOGGLE, "Toggle label", null, null),
                UiFieldDeclaration("int", UiControl.NUMBER, "Number label", null, null)
            )
        )
        assertThrows<IllegalArgumentException> {
            PackageConfigurationDeclaration(data, badUiChoice1)
        }

        val badUiChoice2 = ConfigurationUiDeclaration(
            listOf(
                UiFieldDeclaration(
                    "str_constrained",
                    UiControl.CHOICE,
                    "Choice label",
                    null,
                    listOf(UiChoice("v1", "L1"), UiChoice("v2", "L2"), UiChoice("v3", "L3"))
                ),
                UiFieldDeclaration("str_unconstrained", UiControl.TEXT, "Text label", null, null),
                UiFieldDeclaration("bool", UiControl.TOGGLE, "Toggle label", null, null),
                UiFieldDeclaration("int", UiControl.NUMBER, "Number label", null, null)
            )
        )
        assertThrows<IllegalArgumentException> {
            PackageConfigurationDeclaration(data, badUiChoice2)
        }
    }

    @Test
    fun `package manifest and validated revision retain explicit configuration and capabilities with no defaults`() {
        val identity = GitHubRepositoryIdentity("123")
        val config = PackageConfigurationDeclaration(
            ConfigurationDataDeclaration(emptyList()),
            ConfigurationUiDeclaration(emptyList())
        )
        val capabilities = setOf(
            PackageCapability.AUDIO_TRANSCRIPTION,
            PackageCapability.NETWORK_HTTP,
            PackageCapability.PROFILES_READ,
            PackageCapability.SECRETS_READ,
            PackageCapability.WORK_QUEUE,
        )
        val profileSchema = ProfileSchema(
            listOf(
                ProfileFieldDeclaration.StringField("base_url", true, "https://api.openai.com", null),
                ProfileFieldDeclaration.SecretField("api_key", true),
            ),
            listOf(
                ProfileUiFieldDeclaration("base_url", ProfileUiControl.TEXT, "Base URL", null, null),
                ProfileUiFieldDeclaration("api_key", ProfileUiControl.SECRET, "API key", null, null),
            ),
        )
        val profileTypes = listOf(
            ProfileTypeDeclaration("openai_compatible", "OpenAI-compatible", null, ProfileLimits.SCHEMA_VERSION, profileSchema)
        )
        val choiceResolvers = listOf(
            ChoiceResolverDeclaration(
                "models",
                "openai.model_choices",
                setOf(PackageCapability.NETWORK_HTTP, PackageCapability.PROFILES_READ, PackageCapability.SECRETS_READ)
            )
        )
        val workQueues = listOf(WorkQueueDeclaration("turns"))

        val manifest = PackageManifest(
            manifestVersion = 1,
            repositoryId = identity,
            packageVersion = "1.0.0",
            entryModule = "main",
            presentation = PackagePresentation("Label", "Summary"),
            runtime = RuntimeRequirements("lua-test", "api-test"),
            configuration = config,
            resources = PackageResourcesDeclaration(emptyList()),
            profileTypes = profileTypes,
            choiceResolvers = choiceResolvers,
            workQueues = workQueues,
            capabilities = capabilities
        )

        // Verify manifest retains explicit values
        assertEquals(config, manifest.configuration)
        assertEquals(capabilities, manifest.capabilities)
        assertEquals(profileTypes, manifest.profileTypes)
        assertEquals(choiceResolvers, manifest.choiceResolvers)
        assertEquals(workQueues, manifest.workQueues)
        assertEquals(setOf("api_key"), manifest.profileTypes.single().schema.secretFieldIds())

        // Verify validated revision retains explicit values via manifest
        val digest = ArtifactDigest("a".repeat(64))
        val imageResult = ImmutableProgramImage.create(
            entryPoint = "main",
            sourceMap = mapOf("main" to SOURCE),
            requirements = LuaProgramRequirements(LUA_VERSION, API_VERSION)
        ) as ProgramImageCreationResult.Success

        val revision = ValidatedPackageRevision(
            digest = digest,
            manifest = manifest,
            sourceRecord = sourceRecord(),
            sourceMap = mapOf("main" to SOURCE),
            programImage = imageResult.image,
            fingerprint = ProviderRevisionFingerprint.fromDigest(digest)
        )
        assertEquals(manifest, revision.manifest)
        assertEquals(config, revision.manifest.configuration)
        assertEquals(capabilities, revision.manifest.capabilities)

        // Assert no defaults via reflection (proving compile-time property)
        val declaredConstructors = PackageManifest::class.java.declaredConstructors
        assertTrue("PackageManifest must define at least one constructor", declaredConstructors.isNotEmpty())
        for (constructor in declaredConstructors) {
            val types = constructor.parameterTypes.toList()
            assertTrue("Constructor must require PackageConfigurationDeclaration: $constructor", types.contains(PackageConfigurationDeclaration::class.java))
            assertTrue("Constructor must require Set: $constructor", types.contains(java.util.Set::class.java))
        }
    }

    @Test
    fun `strict manifest decoding for configuration and capabilities validation`() = withTemporaryDirectory { root ->
        val validManifest = manifest()
        expectSuccess(validate(root, archiveBytes(manifest = validManifest), sourceRecord()))

        val defaultConfigStr = """{"schemaVersion":1,"data":{"fields":[],"additionalProperties":false},"ui":{"fields":[]}}"""
        val replaced = validManifest.replace("\"configuration\":$defaultConfigStr,", "")
        val invalidRootCases = listOf(
            ManifestCase("missing configuration", replaced, PackageFailure.FormatDetail.MALFORMED_MANIFEST),
            ManifestCase("missing capabilities", validManifest.replace(",\"capabilities\":[]", ""), PackageFailure.FormatDetail.MALFORMED_MANIFEST),
            ManifestCase("null configuration", validManifest.replace(defaultConfigStr, "null"), PackageFailure.FormatDetail.MALFORMED_MANIFEST),
            ManifestCase("null capabilities", validManifest.replace("[]}", "null}"), PackageFailure.FormatDetail.MALFORMED_MANIFEST),
            ManifestCase("mistyped configuration", validManifest.replace(defaultConfigStr, "[]"), PackageFailure.FormatDetail.MALFORMED_MANIFEST),
            ManifestCase("mistyped capabilities", validManifest.replace("[]}", "{} }"), PackageFailure.FormatDetail.MALFORMED_MANIFEST),
            ManifestCase("duplicate configuration", validManifest.replace("\"configuration\":", "\"configuration\":$defaultConfigStr,\"configuration\":"), PackageFailure.FormatDetail.DUPLICATE_KEYS),
            ManifestCase("duplicate capabilities", validManifest.replace("\"capabilities\":", "\"capabilities\":[],\"capabilities\":"), PackageFailure.FormatDetail.DUPLICATE_KEYS),
        )

        invalidRootCases.forEach { case ->
            assertFormat(
                validate(root, archiveBytes(manifest = case.json), sourceRecord()),
                case.expected as PackageFailure.FormatDetail,
                case.name
            )
        }

        // Capabilities validation
        assertCapability(
            validate(root, archiveBytes(manifest = manifest(capabilities = "[\"audio.nonexistent\"]")), sourceRecord()),
            PackageFailure.CapabilityDetail.UNKNOWN_CAPABILITY_ID,
            "unknown capability"
        )
        assertCapability(
            validate(root, archiveBytes(manifest = manifest(capabilities = "[\"audio.playback\",\"audio.playback\"]")), sourceRecord()),
            PackageFailure.CapabilityDetail.DUPLICATE_CAPABILITY_ID,
            "duplicate capability values"
        )
        assertFormat(
            validate(root, archiveBytes(manifest = manifest(capabilities = "[123]")), sourceRecord()),
            PackageFailure.FormatDetail.MALFORMED_MANIFEST,
            "non-string in capabilities"
        )
        assertFormat(
            validate(root, archiveBytes(manifest = manifest(capabilities = "[null]")), sourceRecord()),
            PackageFailure.FormatDetail.MALFORMED_MANIFEST,
            "null inside capabilities"
        )
        assertFormat(
            validate(root, archiveBytes(manifest = manifest(capabilities = "[[\"audio.playback\"]]")), sourceRecord()),
            PackageFailure.FormatDetail.MALFORMED_MANIFEST,
            "nested array in capabilities"
        )

        // Configuration level validations
        val configCases = listOf(
            // configuration.data level
            ManifestCase("missing data in configuration", manifest(configuration = """{"schemaVersion":1,"ui":{"fields":[]}}"""), PackageFailure.FormatDetail.MALFORMED_MANIFEST),
            ManifestCase("missing ui in configuration", manifest(configuration = """{"schemaVersion":1,"data":{"fields":[]}}"""), PackageFailure.FormatDetail.MALFORMED_MANIFEST),
            ManifestCase("unknown key in configuration", manifest(configuration = """{"schemaVersion":1,"data":{"fields":[]},"ui":{"fields":[]},"extra":1}"""), PackageFailure.FormatDetail.UNKNOWN_FIELDS),
            ManifestCase("null data in configuration", manifest(configuration = """{"schemaVersion":1,"data":null,"ui":{"fields":[]}}"""), PackageFailure.FormatDetail.MALFORMED_MANIFEST),
            ManifestCase("mistyped data in configuration", manifest(configuration = """{"schemaVersion":1,"data":[],"ui":{"fields":[]}}"""), PackageFailure.FormatDetail.MALFORMED_MANIFEST),
            
            // data.fields level
            ManifestCase("missing fields in data", manifest(configuration = """{"schemaVersion":1,"data":{},"ui":{"fields":[]}}"""), PackageFailure.FormatDetail.MALFORMED_MANIFEST),
            ManifestCase("unknown key in data", manifest(configuration = """{"schemaVersion":1,"data":{"fields":[],"extra":1},"ui":{"fields":[]}}"""), PackageFailure.FormatDetail.UNKNOWN_FIELDS),
            ManifestCase("null fields in data", manifest(configuration = """{"schemaVersion":1,"data":{"fields":null},"ui":{"fields":[]}}"""), PackageFailure.FormatDetail.MALFORMED_MANIFEST),
            ManifestCase("mistyped fields in data", manifest(configuration = """{"schemaVersion":1,"data":{"fields":{}},"ui":{"fields":[]}}"""), PackageFailure.FormatDetail.MALFORMED_MANIFEST),
            ManifestCase("missing additionalProperties in data", manifest(configuration = """{"schemaVersion":1,"data":{"fields":[]},"ui":{"fields":[]}}""", injectAdditionalProperties = false), PackageFailure.FormatDetail.MALFORMED_MANIFEST),
            ManifestCase("additionalProperties true in data", manifest(configuration = """{"schemaVersion":1,"data":{"fields":[],"additionalProperties":true},"ui":{"fields":[]}}""", injectAdditionalProperties = false), PackageFailure.FormatDetail.MALFORMED_MANIFEST),

            // ui.fields level
            ManifestCase("missing fields in ui", manifest(configuration = """{"schemaVersion":1,"data":{"fields":[]},"ui":{}}"""), PackageFailure.FormatDetail.MALFORMED_MANIFEST),
            ManifestCase("unknown key in ui", manifest(configuration = """{"schemaVersion":1,"data":{"fields":[]},"ui":{"fields":[],"extra":1}}"""), PackageFailure.FormatDetail.UNKNOWN_FIELDS),
            ManifestCase("null fields in ui", manifest(configuration = """{"schemaVersion":1,"data":{"fields":[]},"ui":{"fields":null}}"""), PackageFailure.FormatDetail.MALFORMED_MANIFEST),
            ManifestCase("mistyped fields in ui", manifest(configuration = """{"schemaVersion":1,"data":{"fields":[]},"ui":{"fields":{}}}"""), PackageFailure.FormatDetail.MALFORMED_MANIFEST),
        )

        configCases.forEach { case ->
            assertFormat(
                validate(root, archiveBytes(manifest = case.json), sourceRecord()),
                case.expected as PackageFailure.FormatDetail,
                case.name
            )
        }

        // Configuration field validations
        val fieldCases = listOf(
            // Missing, unknown, null, mistyped in field declaration
            ManifestCase("missing id in field", manifest(configuration = """{"schemaVersion":1,"data":{"fields":[{"type":"boolean","default":true}]},"ui":{"fields":[{"field":"foo","control":"toggle","label":"Foo"}]}}"""), PackageFailure.FormatDetail.MALFORMED_MANIFEST),
            ManifestCase("missing type in field", manifest(configuration = """{"schemaVersion":1,"data":{"fields":[{"id":"foo","default":true}]},"ui":{"fields":[{"field":"foo","control":"toggle","label":"Foo"}]}}"""), PackageFailure.FormatDetail.MALFORMED_MANIFEST),
            ManifestCase("missing default in field", manifest(configuration = """{"schemaVersion":1,"data":{"fields":[{"id":"foo","type":"boolean"}]},"ui":{"fields":[{"field":"foo","control":"toggle","label":"Foo"}]}}"""), PackageFailure.FormatDetail.MALFORMED_MANIFEST),
            ManifestCase("unknown key in field object", manifest(configuration = """{"schemaVersion":1,"data":{"fields":[{"id":"foo","type":"boolean","default":true,"extra":true}]},"ui":{"fields":[{"field":"foo","control":"toggle","label":"Foo"}]}}"""), PackageFailure.FormatDetail.UNKNOWN_FIELDS),
            ManifestCase("null field id", manifest(configuration = """{"schemaVersion":1,"data":{"fields":[{"id":null,"type":"boolean","default":true}]},"ui":{"fields":[{"field":"foo","control":"toggle","label":"Foo"}]}}"""), PackageFailure.FormatDetail.MALFORMED_MANIFEST),
            ManifestCase("null field type", manifest(configuration = """{"schemaVersion":1,"data":{"fields":[{"id":"foo","type":null,"default":true}]},"ui":{"fields":[{"field":"foo","control":"toggle","label":"Foo"}]}}"""), PackageFailure.FormatDetail.MALFORMED_MANIFEST),
            ManifestCase("null field default", manifest(configuration = """{"schemaVersion":1,"data":{"fields":[{"id":"foo","type":"boolean","default":null}]},"ui":{"fields":[{"field":"foo","control":"toggle","label":"Foo"}]}}"""), PackageFailure.FormatDetail.MALFORMED_MANIFEST),
            ManifestCase("invalid field id pattern", manifest(configuration = """{"schemaVersion":1,"data":{"fields":[{"id":"Foo","type":"boolean","default":true}]},"ui":{"fields":[{"field":"Foo","control":"toggle","label":"Foo"}]}}"""), PackageFailure.FormatDetail.MALFORMED_MANIFEST),
            ManifestCase("invalid type value", manifest(configuration = """{"schemaVersion":1,"data":{"fields":[{"id":"foo","type":"float","default":1.0}]},"ui":{"fields":[{"field":"foo","control":"toggle","label":"Foo"}]}}"""), PackageFailure.FormatDetail.MALFORMED_MANIFEST),
            ManifestCase("duplicate field id", manifest(configuration = """{"schemaVersion":1,"data":{"fields":[{"id":"foo","type":"boolean","default":true},{"id":"foo","type":"string","default":"bar"}]},"ui":{"fields":[{"field":"foo","control":"toggle","label":"Foo"}]}}"""), PackageFailure.FormatDetail.MALFORMED_MANIFEST),
            ManifestCase("unknown key minimum in StringField", manifest(configuration = """{"schemaVersion":1,"data":{"fields":[{"id":"foo","type":"string","default":"bar","minimum":1}]},"ui":{"fields":[{"field":"foo","control":"text","label":"Foo"}]}}"""), PackageFailure.FormatDetail.MALFORMED_MANIFEST),
            ManifestCase("unknown key allowedValues in BooleanField", manifest(configuration = """{"schemaVersion":1,"data":{"fields":[{"id":"foo","type":"boolean","default":true,"allowedValues":[]}]},"ui":{"fields":[{"field":"foo","control":"toggle","label":"Foo"}]}}"""), PackageFailure.FormatDetail.MALFORMED_MANIFEST),
            ManifestCase("duplicate key inside field object", manifest(configuration = """{"schemaVersion":1,"data":{"fields":[{"id":"foo","id":"foo","type":"boolean","default":true}]},"ui":{"fields":[{"field":"foo","control":"toggle","label":"Foo"}]}}"""), PackageFailure.FormatDetail.DUPLICATE_KEYS),

            // type mismatch
            ManifestCase("boolean type mismatch", manifest(configuration = """{"schemaVersion":1,"data":{"fields":[{"id":"foo","type":"boolean","default":"true"}]},"ui":{"fields":[{"field":"foo","control":"toggle","label":"Foo"}]}}"""), PackageFailure.FormatDetail.MALFORMED_MANIFEST),
            ManifestCase("string type mismatch", manifest(configuration = """{"schemaVersion":1,"data":{"fields":[{"id":"foo","type":"string","default":123}]},"ui":{"fields":[{"field":"foo","control":"text","label":"Foo"}]}}"""), PackageFailure.FormatDetail.MALFORMED_MANIFEST),
            ManifestCase("integer type mismatch", manifest(configuration = """{"schemaVersion":1,"data":{"fields":[{"id":"foo","type":"integer","default":true}]},"ui":{"fields":[{"field":"foo","control":"number","label":"Foo"}]}}"""), PackageFailure.FormatDetail.MALFORMED_MANIFEST),

            // String allowedValues
            ManifestCase("empty allowedValues", manifest(configuration = """{"schemaVersion":1,"data":{"fields":[{"id":"foo","type":"string","default":"bar","allowedValues":[]}]},"ui":{"fields":[{"field":"foo","control":"choice","label":"Foo","choices":[]}]}}"""), PackageFailure.FormatDetail.MALFORMED_MANIFEST),
            ManifestCase("default not in allowedValues", manifest(configuration = """{"schemaVersion":1,"data":{"fields":[{"id":"foo","type":"string","default":"bar","allowedValues":["baz"]}]},"ui":{"fields":[{"field":"foo","control":"choice","label":"Foo","choices":[{"value":"baz","label":"Baz"}]}]}}"""), PackageFailure.FormatDetail.MALFORMED_MANIFEST),
            ManifestCase("duplicate allowedValues", manifest(configuration = """{"schemaVersion":1,"data":{"fields":[{"id":"foo","type":"string","default":"bar","allowedValues":["bar","bar"]}]},"ui":{"fields":[{"field":"foo","control":"choice","label":"Foo","choices":[{"value":"bar","label":"Bar"}]}]}}"""), PackageFailure.FormatDetail.MALFORMED_MANIFEST),
            ManifestCase("null inside allowedValues", manifest(configuration = """{"schemaVersion":1,"data":{"fields":[{"id":"foo","type":"string","default":"bar","allowedValues":["bar",null]}]},"ui":{"fields":[{"field":"foo","control":"choice","label":"Foo","choices":[{"value":"bar","label":"Bar"}]}]}}"""), PackageFailure.FormatDetail.MALFORMED_MANIFEST),

            // Integer bounds
            ManifestCase("minimum greater than maximum", manifest(configuration = """{"schemaVersion":1,"data":{"fields":[{"id":"foo","type":"integer","default":5,"minimum":10,"maximum":2}]},"ui":{"fields":[{"field":"foo","control":"number","label":"Foo"}]}}"""), PackageFailure.FormatDetail.MALFORMED_MANIFEST),
            ManifestCase("default less than minimum", manifest(configuration = """{"schemaVersion":1,"data":{"fields":[{"id":"foo","type":"integer","default":1,"minimum":2}]},"ui":{"fields":[{"field":"foo","control":"number","label":"Foo"}]}}"""), PackageFailure.FormatDetail.MALFORMED_MANIFEST),
            ManifestCase("default greater than maximum", manifest(configuration = """{"schemaVersion":1,"data":{"fields":[{"id":"foo","type":"integer","default":10,"maximum":5}]},"ui":{"fields":[{"field":"foo","control":"number","label":"Foo"}]}}"""), PackageFailure.FormatDetail.MALFORMED_MANIFEST),
        )

        fieldCases.forEach { case ->
            assertFormat(
                validate(root, archiveBytes(manifest = case.json), sourceRecord()),
                case.expected as PackageFailure.FormatDetail,
                case.name
            )
        }

        // Configuration UI field validations
        val uiFieldCases = listOf(
            ManifestCase("missing field in ui field", manifest(configuration = """{"schemaVersion":1,"data":{"fields":[{"id":"foo","type":"boolean","default":true}]},"ui":{"fields":[{"control":"toggle","label":"Foo"}]}}"""), PackageFailure.FormatDetail.MALFORMED_MANIFEST),
            ManifestCase("missing control in ui field", manifest(configuration = """{"schemaVersion":1,"data":{"fields":[{"id":"foo","type":"boolean","default":true}]},"ui":{"fields":[{"field":"foo","label":"Foo"}]}}"""), PackageFailure.FormatDetail.MALFORMED_MANIFEST),
            ManifestCase("missing label in ui field", manifest(configuration = """{"schemaVersion":1,"data":{"fields":[{"id":"foo","type":"boolean","default":true}]},"ui":{"fields":[{"field":"foo","control":"toggle"}]}}"""), PackageFailure.FormatDetail.MALFORMED_MANIFEST),
            ManifestCase("unknown key in ui field", manifest(configuration = """{"schemaVersion":1,"data":{"fields":[{"id":"foo","type":"boolean","default":true}]},"ui":{"fields":[{"field":"foo","control":"toggle","label":"Foo","extra":1}]}}"""), PackageFailure.FormatDetail.UNKNOWN_FIELDS),
            ManifestCase("null field in ui field", manifest(configuration = """{"schemaVersion":1,"data":{"fields":[{"id":"foo","type":"boolean","default":true}]},"ui":{"fields":[{"field":null,"control":"toggle","label":"Foo"}]}}"""), PackageFailure.FormatDetail.MALFORMED_MANIFEST),
            ManifestCase("null control in ui field", manifest(configuration = """{"schemaVersion":1,"data":{"fields":[{"id":"foo","type":"boolean","default":true}]},"ui":{"fields":[{"field":"foo","control":null,"label":"Foo"}]}}"""), PackageFailure.FormatDetail.MALFORMED_MANIFEST),
            ManifestCase("null label in ui field", manifest(configuration = """{"schemaVersion":1,"data":{"fields":[{"id":"foo","type":"boolean","default":true}]},"ui":{"fields":[{"field":"foo","control":"toggle","label":null}]}}"""), PackageFailure.FormatDetail.MALFORMED_MANIFEST),
            ManifestCase("explicit null help in ui field", manifest(configuration = """{"schemaVersion":1,"data":{"fields":[{"id":"foo","type":"boolean","default":true}]},"ui":{"fields":[{"field":"foo","control":"toggle","label":"Foo","help":null}]}}"""), PackageFailure.FormatDetail.MALFORMED_MANIFEST),
            ManifestCase("invalid field reference pattern in ui field", manifest(configuration = """{"schemaVersion":1,"data":{"fields":[{"id":"foo","type":"boolean","default":true}]},"ui":{"fields":[{"field":"Foo","control":"toggle","label":"Foo"}]}}"""), PackageFailure.FormatDetail.MALFORMED_MANIFEST),
            ManifestCase("invalid control value in ui field", manifest(configuration = """{"schemaVersion":1,"data":{"fields":[{"id":"foo","type":"boolean","default":true}]},"ui":{"fields":[{"field":"foo","control":"checkbox","label":"Foo"}]}}"""), PackageFailure.FormatDetail.MALFORMED_MANIFEST),
            ManifestCase("blank label in ui field", manifest(configuration = """{"schemaVersion":1,"data":{"fields":[{"id":"foo","type":"boolean","default":true}]},"ui":{"fields":[{"field":"foo","control":"toggle","label":"   "}]}}"""), PackageFailure.FormatDetail.MALFORMED_MANIFEST),
            ManifestCase("duplicate field in ui fields", manifest(configuration = """{"schemaVersion":1,"data":{"fields":[{"id":"foo","type":"boolean","default":true}]},"ui":{"fields":[{"field":"foo","control":"toggle","label":"Foo"},{"field":"foo","control":"toggle","label":"Foo"}]}}"""), PackageFailure.FormatDetail.MALFORMED_MANIFEST),

            // Choices in UI field
            ManifestCase("choices missing for choice control", manifest(configuration = """{"schemaVersion":1,"data":{"fields":[{"id":"foo","type":"string","default":"a","allowedValues":["a"]}]},"ui":{"fields":[{"field":"foo","control":"choice","label":"Foo"}]}}"""), PackageFailure.FormatDetail.MALFORMED_MANIFEST),
            ManifestCase("choices present for toggle control", manifest(configuration = """{"schemaVersion":1,"data":{"fields":[{"id":"foo","type":"boolean","default":true}]},"ui":{"fields":[{"field":"foo","control":"toggle","label":"Foo","choices":[]}]}}"""), PackageFailure.FormatDetail.MALFORMED_MANIFEST),
            ManifestCase("empty choices for choice control", manifest(configuration = """{"schemaVersion":1,"data":{"fields":[{"id":"foo","type":"string","default":"a","allowedValues":["a"]}]},"ui":{"fields":[{"field":"foo","control":"choice","label":"Foo","choices":[]}]}}"""), PackageFailure.FormatDetail.MALFORMED_MANIFEST),
            ManifestCase("duplicate choice value", manifest(configuration = """{"schemaVersion":1,"data":{"fields":[{"id":"foo","type":"string","default":"a","allowedValues":["a","b"]}]},"ui":{"fields":[{"field":"foo","control":"choice","label":"Foo","choices":[{"value":"a","label":"A"},{"value":"a","label":"B"}]}]}}"""), PackageFailure.FormatDetail.MALFORMED_MANIFEST),
            ManifestCase("duplicate choice label", manifest(configuration = """{"schemaVersion":1,"data":{"fields":[{"id":"foo","type":"string","default":"a","allowedValues":["a","b"]}]},"ui":{"fields":[{"field":"foo","control":"choice","label":"Foo","choices":[{"value":"a","label":"A"},{"value":"b","label":"A"}]}]}}"""), PackageFailure.FormatDetail.MALFORMED_MANIFEST),
            ManifestCase("blank choice label", manifest(configuration = """{"schemaVersion":1,"data":{"fields":[{"id":"foo","type":"string","default":"a","allowedValues":["a"]}]},"ui":{"fields":[{"field":"foo","control":"choice","label":"Foo","choices":[{"value":"a","label":""}]}]}}"""), PackageFailure.FormatDetail.MALFORMED_MANIFEST),
            ManifestCase("null inside choices", manifest(configuration = """{"schemaVersion":1,"data":{"fields":[{"id":"foo","type":"string","default":"a","allowedValues":["a"]}]},"ui":{"fields":[{"field":"foo","control":"choice","label":"Foo","choices":[null]}]}}"""), PackageFailure.FormatDetail.MALFORMED_MANIFEST),
            ManifestCase("missing value in choice object", manifest(configuration = """{"schemaVersion":1,"data":{"fields":[{"id":"foo","type":"string","default":"a","allowedValues":["a"]}]},"ui":{"fields":[{"field":"foo","control":"choice","label":"Foo","choices":[{"label":"A"}]}]}}"""), PackageFailure.FormatDetail.MALFORMED_MANIFEST),
            ManifestCase("unknown key in choice object", manifest(configuration = """{"schemaVersion":1,"data":{"fields":[{"id":"foo","type":"string","default":"a","allowedValues":["a"]}]},"ui":{"fields":[{"field":"foo","control":"choice","label":"Foo","choices":[{"value":"a","label":"A","extra":1}]}]}}"""), PackageFailure.FormatDetail.UNKNOWN_FIELDS),
            ManifestCase("explicit null choices in ui field", manifest(configuration = """{"schemaVersion":1,"data":{"fields":[{"id":"foo","type":"string","default":"a","allowedValues":["a"]}]},"ui":{"fields":[{"field":"foo","control":"choice","label":"Foo","choices":null}]}}"""), PackageFailure.FormatDetail.MALFORMED_MANIFEST),
        )

        uiFieldCases.forEach { case ->
            assertFormat(
                validate(root, archiveBytes(manifest = case.json), sourceRecord()),
                case.expected as PackageFailure.FormatDetail,
                case.name
            )
        }

        // Data / UI matching validation
        val matchingCases = listOf(
            ManifestCase("extra field in UI not in data", manifest(configuration = """{"schemaVersion":1,"data":{"fields":[{"id":"foo","type":"boolean","default":true}]},"ui":{"fields":[{"field":"foo","control":"toggle","label":"Foo"},{"field":"bar","control":"text","label":"Bar"}]}}"""), PackageFailure.FormatDetail.MALFORMED_MANIFEST),
            ManifestCase("extra field in data not in UI", manifest(configuration = """{"schemaVersion":1,"data":{"fields":[{"id":"foo","type":"boolean","default":true},{"id":"bar","type":"string","default":"x"}]},"ui":{"fields":[{"field":"foo","control":"toggle","label":"Foo"}]}}"""), PackageFailure.FormatDetail.MALFORMED_MANIFEST),
            ManifestCase("control mismatch: boolean-text", manifest(configuration = """{"schemaVersion":1,"data":{"fields":[{"id":"foo","type":"boolean","default":true}]},"ui":{"fields":[{"field":"foo","control":"text","label":"Foo"}]}}"""), PackageFailure.FormatDetail.MALFORMED_MANIFEST),
            ManifestCase("control mismatch: string-choice without allowedValues", manifest(configuration = """{"schemaVersion":1,"data":{"fields":[{"id":"foo","type":"string","default":"a"}]},"ui":{"fields":[{"field":"foo","control":"choice","label":"Foo","choices":[{"value":"a","label":"A"}]}]}}"""), PackageFailure.FormatDetail.MALFORMED_MANIFEST),
            ManifestCase("control mismatch: string-text with allowedValues", manifest(configuration = """{"schemaVersion":1,"data":{"fields":[{"id":"foo","type":"string","default":"a","allowedValues":["a"]}]},"ui":{"fields":[{"field":"foo","control":"text","label":"Foo"}]}}"""), PackageFailure.FormatDetail.MALFORMED_MANIFEST),
            ManifestCase("control mismatch: integer-toggle", manifest(configuration = """{"schemaVersion":1,"data":{"fields":[{"id":"foo","type":"integer","default":1}]},"ui":{"fields":[{"field":"foo","control":"toggle","label":"Foo"}]}}"""), PackageFailure.FormatDetail.MALFORMED_MANIFEST),
            ManifestCase("choices mismatch: allowedValues-choices set inequality", manifest(configuration = """{"schemaVersion":1,"data":{"fields":[{"id":"foo","type":"string","default":"a","allowedValues":["a","b"]}]},"ui":{"fields":[{"field":"foo","control":"choice","label":"Foo","choices":[{"value":"a","label":"A"}]}]}}"""), PackageFailure.FormatDetail.MALFORMED_MANIFEST),
        )

        matchingCases.forEach { case ->
            assertFormat(
                validate(root, archiveBytes(manifest = case.json), sourceRecord()),
                case.expected as PackageFailure.FormatDetail,
                case.name
            )
        }
    }

    @Test
    fun `enforce configuration bounds and capabilities validation limits`() = withTemporaryDirectory { root ->
        // 1. Field ID limit (64 UTF-8 bytes)
        // Boundary (64 bytes)
        val validFieldId = "a" + "0".repeat(63)
        val validFieldIdManifest = manifest(configuration = """{"schemaVersion":1,"data":{"fields":[{"id":"$validFieldId","type":"boolean","default":true}],"additionalProperties":false},"ui":{"fields":[{"field":"$validFieldId","control":"toggle","label":"label"}]}}""")
        expectSuccess(validate(root, archiveBytes(manifest = validFieldIdManifest), sourceRecord()))

        // Over limit (65 bytes)
        val invalidFieldId = "a" + "0".repeat(64)
        val invalidFieldIdManifest = manifest(configuration = """{"schemaVersion":1,"data":{"fields":[{"id":"$invalidFieldId","type":"boolean","default":true}],"additionalProperties":false},"ui":{"fields":[{"field":"$invalidFieldId","control":"toggle","label":"label"}]}}""")
        assertFormat(validate(root, archiveBytes(manifest = invalidFieldIdManifest), sourceRecord()), PackageFailure.FormatDetail.MALFORMED_MANIFEST, "Field ID over 64 bytes")

        // 2. Label limit (128 UTF-8 bytes)
        // Boundary (128 bytes)
        val validLabel = "a".repeat(128)
        val validLabelManifest = manifest(configuration = """{"schemaVersion":1,"data":{"fields":[{"id":"foo","type":"boolean","default":true}],"additionalProperties":false},"ui":{"fields":[{"field":"foo","control":"toggle","label":"$validLabel"}]}}""")
        expectSuccess(validate(root, archiveBytes(manifest = validLabelManifest), sourceRecord()))

        // Over limit (129 bytes)
        val invalidLabel = "a".repeat(129)
        val invalidLabelManifest = manifest(configuration = """{"schemaVersion":1,"data":{"fields":[{"id":"foo","type":"boolean","default":true}],"additionalProperties":false},"ui":{"fields":[{"field":"foo","control":"toggle","label":"$invalidLabel"}]}}""")
        assertFormat(validate(root, archiveBytes(manifest = invalidLabelManifest), sourceRecord()), PackageFailure.FormatDetail.MALFORMED_MANIFEST, "Label over 128 bytes")

        // Non-ASCII label boundary (e.g. multi-byte characters: each is 3 bytes, so 42 chars = 126 bytes; 43 chars = 129 bytes)
        val validMultiByteLabel = "中".repeat(42) // 126 bytes
        val validMultiByteLabelManifest = manifest(configuration = """{"schemaVersion":1,"data":{"fields":[{"id":"foo","type":"boolean","default":true}],"additionalProperties":false},"ui":{"fields":[{"field":"foo","control":"toggle","label":"$validMultiByteLabel"}]}}""")
        expectSuccess(validate(root, archiveBytes(manifest = validMultiByteLabelManifest), sourceRecord()))

        val invalidMultiByteLabel = "中".repeat(43) // 129 bytes
        val invalidMultiByteLabelManifest = manifest(configuration = """{"schemaVersion":1,"data":{"fields":[{"id":"foo","type":"boolean","default":true}],"additionalProperties":false},"ui":{"fields":[{"field":"foo","control":"toggle","label":"$invalidMultiByteLabel"}]}}""")
        assertFormat(validate(root, archiveBytes(manifest = invalidMultiByteLabelManifest), sourceRecord()), PackageFailure.FormatDetail.MALFORMED_MANIFEST, "Multi-byte label over 128 bytes")

        // 3. Help string limit (512 UTF-8 bytes)
        // Boundary (512 bytes)
        val validHelp = "a".repeat(512)
        val validHelpManifest = manifest(configuration = """{"schemaVersion":1,"data":{"fields":[{"id":"foo","type":"boolean","default":true}],"additionalProperties":false},"ui":{"fields":[{"field":"foo","control":"toggle","label":"label","help":"$validHelp"}]}}""")
        expectSuccess(validate(root, archiveBytes(manifest = validHelpManifest), sourceRecord()))

        // Over limit (513 bytes)
        val invalidHelp = "a".repeat(513)
        val invalidHelpManifest = manifest(configuration = """{"schemaVersion":1,"data":{"fields":[{"id":"foo","type":"boolean","default":true}],"additionalProperties":false},"ui":{"fields":[{"field":"foo","control":"toggle","label":"label","help":"$invalidHelp"}]}}""")
        assertFormat(validate(root, archiveBytes(manifest = invalidHelpManifest), sourceRecord()), PackageFailure.FormatDetail.MALFORMED_MANIFEST, "Help over 512 bytes")

        // 4. Choice count has no maximum — large lists are accepted.
        val manyChoicesList = (1..257).map { """{"value":"v$it","label":"L$it"}""" }.joinToString(",")
        val manyAllowedValuesList = (1..257).map { "\"v$it\"" }.joinToString(",")
        val manyChoiceManifest = manifest(configuration = """{"schemaVersion":1,"data":{"fields":[{"id":"foo","type":"string","default":"v1","allowedValues":[$manyAllowedValuesList]}],"additionalProperties":false},"ui":{"fields":[{"field":"foo","control":"choice","label":"label","choices":[$manyChoicesList]}]}}""")
        expectSuccess(validate(root, archiveBytes(manifest = manyChoiceManifest), sourceRecord()))

        // 5. Individual string limit (16 KiB = 16384 bytes)
        // Boundary (16384 bytes)
        val validStringVal = "a".repeat(16384)
        val validStringManifest = manifest(configuration = """{"schemaVersion":1,"data":{"fields":[{"id":"foo","type":"string","default":"$validStringVal"}],"additionalProperties":false},"ui":{"fields":[{"field":"foo","control":"text","label":"label"}]}}""")
        expectSuccess(validate(root, archiveBytes(manifest = validStringManifest), sourceRecord()))

        // Over limit (16385 bytes)
        val invalidStringVal = "a".repeat(16385)
        val invalidStringManifest = manifest(configuration = """{"schemaVersion":1,"data":{"fields":[{"id":"foo","type":"string","default":"$invalidStringVal"}],"additionalProperties":false},"ui":{"fields":[{"field":"foo","control":"text","label":"label"}]}}""")
        assertFormat(validate(root, archiveBytes(manifest = invalidStringManifest), sourceRecord()), PackageFailure.FormatDetail.MALFORMED_MANIFEST, "Individual string over 16 KiB")

        // 6. Canonical default payload limit (64 KiB = 65536 bytes)
        val s16376 = "a".repeat(16376)
        val s16375 = "a".repeat(16375)
        val payloadBoundaryManifest = manifest(configuration = """{"schemaVersion":1,"data":{"fields":[{"id":"f1","type":"string","default":"$s16376"},{"id":"f2","type":"string","default":"$s16376"},{"id":"f3","type":"string","default":"$s16376"},{"id":"f4","type":"string","default":"$s16375"}],"additionalProperties":false},"ui":{"fields":[{"field":"f1","control":"text","label":"l1"},{"field":"f2","control":"text","label":"l2"},{"field":"f3","control":"text","label":"l3"},{"field":"f4","control":"text","label":"l4"}]}}""")
        expectSuccess(validate(root, archiveBytes(manifest = payloadBoundaryManifest), sourceRecord()))

        // Over limit (65537 bytes)
        val payloadOverLimitManifest = manifest(configuration = """{"schemaVersion":1,"data":{"fields":[{"id":"f1","type":"string","default":"$s16376"},{"id":"f2","type":"string","default":"$s16376"},{"id":"f3","type":"string","default":"$s16376"},{"id":"f4","type":"string","default":"$s16376"}],"additionalProperties":false},"ui":{"fields":[{"field":"f1","control":"text","label":"l1"},{"field":"f2","control":"text","label":"l2"},{"field":"f3","control":"text","label":"l3"},{"field":"f4","control":"text","label":"l4"}]}}""")
        assertFormat(validate(root, archiveBytes(manifest = payloadOverLimitManifest), sourceRecord()), PackageFailure.FormatDetail.MALFORMED_MANIFEST, "Canonical payload over 64 KiB")
    }

    @Test
    fun `package capabilities preserves order in manifest`() = withTemporaryDirectory { root ->
        val manifest1 = manifest(capabilities = "[\"audio.playback\",\"audio.synthesis\"]")
        val revision1 = expectSuccess(validate(root, archiveBytes(manifest = manifest1), sourceRecord()))
        assertEquals(listOf("audio.playback", "audio.synthesis"), revision1.manifest.capabilities.toList())

        val manifest2 = manifest(capabilities = "[\"audio.transcription\",\"audio.playback\"]")
        val revision2 = expectSuccess(validate(root, archiveBytes(manifest = manifest2), sourceRecord()))
        assertEquals(listOf("audio.transcription", "audio.playback"), revision2.manifest.capabilities.toList())
    }

    @Test
    fun `duplicate keys at every nesting level are rejected`() = withTemporaryDirectory { root ->
        val base = manifest()
        val cases = listOf(
            ManifestCase("duplicate label in presentation",
                base.replace("\"label\":\"Package\",\"summary\"", "\"label\":\"Package\",\"label\":\"Package\",\"summary\""),
                PackageFailure.FormatDetail.DUPLICATE_KEYS),
            ManifestCase("duplicate luaVersion in runtime",
                base.replace("\"luaVersion\":\"$LUA_VERSION\",\"apiVersion\"",
                    "\"luaVersion\":\"$LUA_VERSION\",\"luaVersion\":\"$LUA_VERSION\",\"apiVersion\""),
                PackageFailure.FormatDetail.DUPLICATE_KEYS),
            ManifestCase("duplicate data key in configuration",
                base.replace("\"data\":{\"fields\":[],\"additionalProperties\":false},\"ui\"",
                    "\"data\":{\"fields\":[],\"additionalProperties\":false},\"data\":{\"fields\":[],\"additionalProperties\":false},\"ui\""),
                PackageFailure.FormatDetail.DUPLICATE_KEYS),
            ManifestCase("duplicate fields key in data",
                base.replace("\"data\":{\"fields\":[],\"additionalProperties\":false}",
                    "\"data\":{\"fields\":[],\"fields\":[],\"additionalProperties\":false}"),
                PackageFailure.FormatDetail.DUPLICATE_KEYS),
            ManifestCase("duplicate fields key in ui",
                base.replace("\"ui\":{\"fields\":[]}", "\"ui\":{\"fields\":[],\"fields\":[]}"),
                PackageFailure.FormatDetail.DUPLICATE_KEYS),
            ManifestCase("duplicate field key in ui field object",
                manifest(configuration = """{"schemaVersion":1,"data":{"fields":[{"id":"foo","type":"boolean","default":true}],"additionalProperties":false},"ui":{"fields":[{"field":"foo","field":"foo","control":"toggle","label":"Foo"}]}}""", injectAdditionalProperties = false),
                PackageFailure.FormatDetail.DUPLICATE_KEYS),
            ManifestCase("duplicate value key in choice object",
                manifest(configuration = """{"schemaVersion":1,"data":{"fields":[{"id":"foo","type":"string","default":"a","allowedValues":["a"]}],"additionalProperties":false},"ui":{"fields":[{"field":"foo","control":"choice","label":"Foo","choices":[{"value":"a","value":"a","label":"A"}]}]}}""", injectAdditionalProperties = false),
                PackageFailure.FormatDetail.DUPLICATE_KEYS),
        )
        cases.forEach { case ->
            assertFormat(
                validate(root, archiveBytes(manifest = case.json), sourceRecord()),
                case.expected as PackageFailure.FormatDetail,
                case.name,
            )
        }
    }

    @Test
    fun `valid empty diagnostics declarations validate and produce empty revision`() = withTemporaryDirectory { root ->
        val revision = expectSuccess(validate(root, archiveBytes(manifest = manifest()), sourceRecord()))

        assertTrue("data fields should be empty for diagnostics-style declaration", revision.manifest.configuration.data.fields.isEmpty())
        assertTrue("ui fields should be empty for diagnostics-style declaration", revision.manifest.configuration.ui.fields.isEmpty())
        assertTrue("capabilities should be empty for diagnostics-style declaration", revision.manifest.capabilities.isEmpty())
    }

    @Test
    fun `old artifact without evolved declarations is rejected`() = withTemporaryDirectory { root ->
        val oldManifest = """{"manifestVersion":1,"repositoryId":"123","packageVersion":"1.0.0","entryModule":"main","presentation":{"label":"Package","summary":"Package summary"},"runtime":{"luaVersion":"$LUA_VERSION","apiVersion":"$API_VERSION"}}"""

        assertFormat(
            validate(root, archiveBytes(manifest = oldManifest), sourceRecord()),
            PackageFailure.FormatDetail.MALFORMED_MANIFEST,
            "old artifact without configuration and capabilities",
        )
    }

    @Test
    fun `resources declaration is required strictly validated and retained`() = withTemporaryDirectory { root ->
        // Empty mounts array is valid and retained.
        val emptyRevision = expectSuccess(validate(root, archiveBytes(manifest = manifest()), sourceRecord()))
        assertTrue("empty mounts should be retained", emptyRevision.manifest.resources.mounts.isEmpty())

        // A single canonical directory-tree mount is retained exactly.
        val mountedJson = manifest(
            resources = """{"mounts":[{"id":"output","kind":"directory-tree","access":"read-write","required":true,"label":"Output","help":"Where recordings are written"}]}"""
        )
        val mountedRevision = expectSuccess(validate(root, archiveBytes(manifest = mountedJson), sourceRecord()))
        assertEquals(1, mountedRevision.manifest.resources.mounts.size)
        val mount = mountedRevision.manifest.resources.mounts.single()
        assertEquals("output", mount.id)
        assertEquals(PackageMountKind.DIRECTORY_TREE, mount.kind)
        assertEquals(PackageMountAccess.READ_WRITE, mount.access)
        assertTrue("v1 mounts must be required", mount.required)
        assertEquals("Output", mount.label)
        assertEquals("Where recordings are written", mount.help)

        val validMount = """{"id":"output","kind":"directory-tree","access":"read-write","required":true,"label":"Output"}"""
        val resourceCases = listOf(
            ManifestCase("omitted resources", manifest().replace(",\"resources\":{\"mounts\":[]}", ""), PackageFailure.FormatDetail.MALFORMED_MANIFEST),
            ManifestCase("unknown resources key", manifest(resources = """{"mounts":[],"extra":1}"""), PackageFailure.FormatDetail.UNKNOWN_FIELDS),
            ManifestCase("mistyped resources", manifest(resources = "[]"), PackageFailure.FormatDetail.MALFORMED_MANIFEST),
            ManifestCase("mistyped mounts", manifest(resources = """{"mounts":{}}"""), PackageFailure.FormatDetail.MALFORMED_MANIFEST),
            ManifestCase("unknown mount key", manifest(resources = """{"mounts":[{"id":"output","kind":"directory-tree","access":"read-write","required":true,"label":"Output","extra":1}]}"""), PackageFailure.FormatDetail.UNKNOWN_FIELDS),
            ManifestCase("missing mount label", manifest(resources = """{"mounts":[{"id":"output","kind":"directory-tree","access":"read-write","required":true}]}"""), PackageFailure.FormatDetail.MALFORMED_MANIFEST),
            ManifestCase("unsupported mount kind", manifest(resources = """{"mounts":[{"id":"output","kind":"file","access":"read-write","required":true,"label":"Output"}]}"""), PackageFailure.FormatDetail.MALFORMED_MANIFEST),
            ManifestCase("unsupported mount access", manifest(resources = """{"mounts":[{"id":"output","kind":"directory-tree","access":"read-only","required":true,"label":"Output"}]}"""), PackageFailure.FormatDetail.MALFORMED_MANIFEST),
            ManifestCase("optional mount rejected", manifest(resources = """{"mounts":[{"id":"output","kind":"directory-tree","access":"read-write","required":false,"label":"Output"}]}"""), PackageFailure.FormatDetail.MALFORMED_MANIFEST),
            ManifestCase("non-canonical mount id", manifest(resources = """{"mounts":[{"id":"Output-Dir","kind":"directory-tree","access":"read-write","required":true,"label":"Output"}]}"""), PackageFailure.FormatDetail.MALFORMED_MANIFEST),
            ManifestCase("blank mount label", manifest(resources = """{"mounts":[{"id":"output","kind":"directory-tree","access":"read-write","required":true,"label":" "}]}"""), PackageFailure.FormatDetail.MALFORMED_MANIFEST),
            ManifestCase("duplicate mount id", manifest(resources = """{"mounts":[$validMount,{"id":"output","kind":"directory-tree","access":"read-write","required":true,"label":"Second"}]}"""), PackageFailure.FormatDetail.MALFORMED_MANIFEST),
        )

        resourceCases.forEach { case ->
            assertFormat(
                validate(root, archiveBytes(manifest = case.json), sourceRecord()),
                case.expected as PackageFailure.FormatDetail,
                case.name
            )
        }
    }

    @Test
    fun `failed validation cleans up staging file with no partial retention`() = withTemporaryDirectory { root ->
        // Invalid ZIP content
        val stagingFile1 = File(root, "staging1.zip")
        val outcome1 = PackageValidator.validatePackage(
            ByteArrayInputStream(ByteArray(10)),
            sourceRecord(),
            stagingFile1,
            generousBounds(),
        )
        assertTrue("invalid ZIP should fail", outcome1 is PackageOutcome.Failure)
        assertFalse("staging file must be deleted after invalid ZIP failure", stagingFile1.exists())

        // Valid ZIP but malformed manifest
        val stagingFile2 = File(root, "staging2.zip")
        val badManifestArchive = strictZip(listOf(
            FixtureEntry("manifest.json", "not json".toByteArray(UTF_8)),
            FixtureEntry("lua/", ByteArray(0), directory = true),
            FixtureEntry("lua/main.lua", SOURCE.toByteArray(UTF_8)),
        ))
        val outcome2 = PackageValidator.validatePackage(
            ByteArrayInputStream(badManifestArchive),
            sourceRecord(),
            stagingFile2,
            generousBounds(),
        )
        assertTrue("malformed manifest should fail", outcome2 is PackageOutcome.Failure)
        assertFalse("staging file must be deleted after malformed manifest failure", stagingFile2.exists())

        // Valid ZIP and manifest but incompatible runtime
        val stagingFile3 = File(root, "staging3.zip")
        val incompatibleArchive = archiveBytes(manifest = manifest().replace(API_VERSION, "talkcan-lua-v2"))
        val outcome3 = PackageValidator.validatePackage(
            ByteArrayInputStream(incompatibleArchive),
            sourceRecord(),
            stagingFile3,
            generousBounds(),
        )
        assertTrue("incompatible runtime should fail", outcome3 is PackageOutcome.Failure)
        assertFalse("staging file must be deleted after compatibility failure", stagingFile3.exists())

        // Artifact exceeds size bound
        val stagingFile4 = File(root, "staging4.zip")
        val validArchive = archiveBytes()
        val outcome4 = PackageValidator.validatePackage(
            ByteArrayInputStream(validArchive),
            sourceRecord(),
            stagingFile4,
            bounds(maxArtifactBytes = validArchive.size.toLong() - 1),
        )
        assertTrue("oversized artifact should fail", outcome4 is PackageOutcome.Failure)
        assertFalse("staging file must be deleted after bounds failure", stagingFile4.exists())
    }

    @Test
    fun `integer field accepts exact boundary defaults and rejects non-integer values`() = withTemporaryDirectory { root ->
        // Default at minimum boundary (should pass)
        expectSuccess(validate(root, archiveBytes(manifest = manifest(configuration = """{"schemaVersion":1,"data":{"fields":[{"id":"foo","type":"integer","default":5,"minimum":5,"maximum":10}],"additionalProperties":false},"ui":{"fields":[{"field":"foo","control":"number","label":"Foo"}]}}""", injectAdditionalProperties = false)), sourceRecord()))

        // Default at maximum boundary (should pass)
        expectSuccess(validate(root, archiveBytes(manifest = manifest(configuration = """{"schemaVersion":1,"data":{"fields":[{"id":"foo","type":"integer","default":10,"minimum":5,"maximum":10}],"additionalProperties":false},"ui":{"fields":[{"field":"foo","control":"number","label":"Foo"}]}}""", injectAdditionalProperties = false)), sourceRecord()))

        // Non-integer default (1.5) should fail
        assertFormat(
            validate(root, archiveBytes(manifest = manifest(configuration = """{"schemaVersion":1,"data":{"fields":[{"id":"foo","type":"integer","default":1.5,"minimum":0,"maximum":10}],"additionalProperties":false},"ui":{"fields":[{"field":"foo","control":"number","label":"Foo"}]}}""", injectAdditionalProperties = false)), sourceRecord()),
            PackageFailure.FormatDetail.MALFORMED_MANIFEST,
            "non-integer default 1.5",
        )

        // Non-integer minimum (2.5) should fail
        assertFormat(
            validate(root, archiveBytes(manifest = manifest(configuration = """{"schemaVersion":1,"data":{"fields":[{"id":"foo","type":"integer","default":5,"minimum":2.5,"maximum":10}],"additionalProperties":false},"ui":{"fields":[{"field":"foo","control":"number","label":"Foo"}]}}""", injectAdditionalProperties = false)), sourceRecord()),
            PackageFailure.FormatDetail.MALFORMED_MANIFEST,
            "non-integer minimum 2.5",
        )

        // Non-integer maximum (7.5) should fail
        assertFormat(
            validate(root, archiveBytes(manifest = manifest(configuration = """{"schemaVersion":1,"data":{"fields":[{"id":"foo","type":"integer","default":5,"minimum":0,"maximum":7.5}],"additionalProperties":false},"ui":{"fields":[{"field":"foo","control":"number","label":"Foo"}]}}""", injectAdditionalProperties = false)), sourceRecord()),
            PackageFailure.FormatDetail.MALFORMED_MANIFEST,
            "non-integer maximum 7.5",
        )

        // Int64 min boundary with no bounds (should pass)
        expectSuccess(validate(root, archiveBytes(manifest = manifest(configuration = """{"schemaVersion":1,"data":{"fields":[{"id":"foo","type":"integer","default":-9223372036854775808}],"additionalProperties":false},"ui":{"fields":[{"field":"foo","control":"number","label":"Foo"}]}}""", injectAdditionalProperties = false)), sourceRecord()))

        // Int64 max boundary with no bounds (should pass)
        expectSuccess(validate(root, archiveBytes(manifest = manifest(configuration = """{"schemaVersion":1,"data":{"fields":[{"id":"foo","type":"integer","default":9223372036854775807}],"additionalProperties":false},"ui":{"fields":[{"field":"foo","control":"number","label":"Foo"}]}}""", injectAdditionalProperties = false)), sourceRecord()))
        Unit
    }

    @Test
    fun `configuration schema version requires exactly integer one`() = withTemporaryDirectory { root ->
        assertFormat(
            validate(root, archiveBytes(manifest = manifest(configuration = """{"schemaVersion":2,"data":{"fields":[],"additionalProperties":false},"ui":{"fields":[]}}""", injectAdditionalProperties = false)), sourceRecord()),
            PackageFailure.FormatDetail.MALFORMED_MANIFEST,
            "schema version 2",
        )
        assertFormat(
            validate(root, archiveBytes(manifest = manifest(configuration = """{"schemaVersion":"1","data":{"fields":[],"additionalProperties":false},"ui":{"fields":[]}}""", injectAdditionalProperties = false)), sourceRecord()),
            PackageFailure.FormatDetail.MALFORMED_MANIFEST,
            "string schema version",
        )
        assertFormat(
            validate(root, archiveBytes(manifest = manifest(configuration = """{"data":{"fields":[],"additionalProperties":false},"ui":{"fields":[]}}""", injectAdditionalProperties = false)), sourceRecord()),
            PackageFailure.FormatDetail.MALFORMED_MANIFEST,
            "missing schema version",
        )
        assertFormat(
            validate(root, archiveBytes(manifest = manifest(configuration = """{"schemaVersion":null,"data":{"fields":[],"additionalProperties":false},"ui":{"fields":[]}}""", injectAdditionalProperties = false)), sourceRecord()),
            PackageFailure.FormatDetail.MALFORMED_MANIFEST,
            "null schema version",
        )
    }

    @Test
    fun `entry module validation rejects blank invalid and missing entry modules`() = withTemporaryDirectory { root ->
        assertFormat(
            validate(root, archiveBytes(manifest = manifest().replace("\"entryModule\":\"main\"", "\"entryModule\":\"\"")), sourceRecord()),
            PackageFailure.FormatDetail.INVALID_MODULE_GRAMMAR,
            "blank entry module",
        )
        assertFormat(
            validate(root, archiveBytes(manifest = manifest().replace("\"entryModule\":\"main\"", "\"entryModule\":\"Main\"")), sourceRecord()),
            PackageFailure.FormatDetail.INVALID_MODULE_GRAMMAR,
            "uppercase entry module",
        )
        assertFormat(
            validate(root, archiveBytes(manifest = manifest().replace("\"entryModule\":\"main\"", "\"entryModule\":\"1invalid\"")), sourceRecord()),
            PackageFailure.FormatDetail.INVALID_MODULE_GRAMMAR,
            "entry module starting with digit",
        )

        // Entry module not in source map
        val entries = listOf(
            FixtureEntry("manifest.json", manifest().toByteArray(UTF_8)),
            FixtureEntry("lua/", ByteArray(0), directory = true),
            FixtureEntry("lua/other.lua", SOURCE.toByteArray(UTF_8)),
        )
        assertFormat(
            validate(root, strictZip(entries), sourceRecord()),
            PackageFailure.FormatDetail.INVALID_ENTRY_MODULE,
            "entry module not in source map",
        )
    }

    @Test
    fun `additionalProperties must be exactly boolean false`() = withTemporaryDirectory { root ->
        assertFormat(
            validate(root, archiveBytes(manifest = manifest(configuration = """{"schemaVersion":1,"data":{"fields":[],"additionalProperties":"false"},"ui":{"fields":[]}}""", injectAdditionalProperties = false)), sourceRecord()),
            PackageFailure.FormatDetail.MALFORMED_MANIFEST,
            "string additionalProperties",
        )
        assertFormat(
            validate(root, archiveBytes(manifest = manifest(configuration = """{"schemaVersion":1,"data":{"fields":[],"additionalProperties":0},"ui":{"fields":[]}}""", injectAdditionalProperties = false)), sourceRecord()),
            PackageFailure.FormatDetail.MALFORMED_MANIFEST,
            "integer additionalProperties",
        )
        assertFormat(
            validate(root, archiveBytes(manifest = manifest(configuration = """{"schemaVersion":1,"data":{"fields":[],"additionalProperties":null},"ui":{"fields":[]}}""", injectAdditionalProperties = false)), sourceRecord()),
            PackageFailure.FormatDetail.MALFORMED_MANIFEST,
            "null additionalProperties",
        )
    }

    @Test
    fun `keyboard output capability is accepted and preserved through validation`() = withTemporaryDirectory { root ->
        val single = expectSuccess(validate(root, archiveBytes(manifest = manifest(capabilities = "[\"keyboard.output\"]")), sourceRecord()))
        assertEquals(setOf("keyboard.output"), single.manifest.capabilities)
        assertTrue(single.manifest.capabilities.contains(PackageCapability.KEYBOARD_OUTPUT))

        val combined = expectSuccess(validate(root, archiveBytes(manifest = manifest(capabilities = "[\"audio.transcription\",\"keyboard.output\"]")), sourceRecord()))
        assertEquals(listOf("audio.transcription", "keyboard.output"), combined.manifest.capabilities.toList())
    }

    @Test
    fun `keyboard output capability rejects duplicate near-miss and mistyped declarations`() = withTemporaryDirectory { root ->
        assertCapability(
            validate(root, archiveBytes(manifest = manifest(capabilities = "[\"keyboard.output\",\"keyboard.output\"]")), sourceRecord()),
            PackageFailure.CapabilityDetail.DUPLICATE_CAPABILITY_ID,
            "duplicate keyboard.output",
        )
        assertCapability(
            validate(root, archiveBytes(manifest = manifest(capabilities = "[\"keyboard.outputs\"]")), sourceRecord()),
            PackageFailure.CapabilityDetail.UNKNOWN_CAPABILITY_ID,
            "near-miss keyboard.outputs",
        )
        assertFormat(
            validate(root, archiveBytes(manifest = manifest(capabilities = "[\"keyboard.output\",123]")), sourceRecord()),
            PackageFailure.FormatDetail.MALFORMED_MANIFEST,
            "mistyped member beside keyboard.output",
        )
    }

    @Test
    fun `dynamic choice source identifiers are bounded and immutable`() {
        assertEquals("keyboard-output-platforms", DynamicChoiceSource.KEYBOARD_OUTPUT_PLATFORMS)
        assertEquals("keyboard-output-layouts", DynamicChoiceSource.KEYBOARD_OUTPUT_LAYOUTS)
        assertEquals("keyboard-output-profiles", DynamicChoiceSource.KEYBOARD_OUTPUT_PROFILES)
        assertEquals(setOf("keyboard-output-platforms", "keyboard-output-layouts", "keyboard-output-profiles"), DynamicChoiceSource.ALL)
        assertThrows<UnsupportedOperationException> {
            (DynamicChoiceSource.ALL as MutableSet<String>).add("extra.source")
        }
        assertEquals("dynamic-choice", UiControl.DYNAMIC_CHOICE.value)
    }

    @Test
    fun `dynamic choice ui field declaration constructor invariants equality and immutability`() {
        val valid = UiFieldDeclaration("host_profile", UiControl.DYNAMIC_CHOICE, "Host profile", "Help", null, DynamicChoiceSource.KEYBOARD_OUTPUT_PROFILES)
        assertEquals("host_profile", valid.field)
        assertEquals(UiControl.DYNAMIC_CHOICE, valid.control)
        assertEquals("Host profile", valid.label)
        assertEquals("Help", valid.help)
        assertNull(valid.choices)
        assertEquals(DynamicChoiceSource.KEYBOARD_OUTPUT_PROFILES, valid.source)

        assertThrows<IllegalArgumentException> {
            UiFieldDeclaration("host_profile", UiControl.DYNAMIC_CHOICE, "Host profile", null, null, null)
        }
        assertThrows<IllegalArgumentException> {
            UiFieldDeclaration("host_profile", UiControl.DYNAMIC_CHOICE, "Host profile", null, null, "bogus-source")
        }
        assertThrows<IllegalArgumentException> {
            UiFieldDeclaration("host_profile", UiControl.DYNAMIC_CHOICE, "Host profile", null, listOf(UiChoice("a", "A")), DynamicChoiceSource.KEYBOARD_OUTPUT_PROFILES)
        }
        assertThrows<IllegalArgumentException> {
            UiFieldDeclaration("name", UiControl.TEXT, "Name", null, null, DynamicChoiceSource.KEYBOARD_OUTPUT_PROFILES)
        }
        assertThrows<IllegalArgumentException> {
            UiFieldDeclaration("opt", UiControl.CHOICE, "Opt", null, listOf(UiChoice("a", "A")), DynamicChoiceSource.KEYBOARD_OUTPUT_PROFILES)
        }

        val twin = UiFieldDeclaration("host_profile", UiControl.DYNAMIC_CHOICE, "Host profile", "Help", null, DynamicChoiceSource.KEYBOARD_OUTPUT_PROFILES)
        assertEquals(valid, twin)
        assertEquals(valid.hashCode(), twin.hashCode())
        val differentField = UiFieldDeclaration("other_profile", UiControl.DYNAMIC_CHOICE, "Host profile", "Help", null, DynamicChoiceSource.KEYBOARD_OUTPUT_PROFILES)
        assertNotEquals(valid, differentField)
    }

    @Test
    fun `package configuration declaration accepts dynamic choice only for unconstrained string targets`() {
        val dynamicField = UiFieldDeclaration("host_profile", UiControl.DYNAMIC_CHOICE, "Host profile", null, null, DynamicChoiceSource.KEYBOARD_OUTPUT_PROFILES)

        val valid = PackageConfigurationDeclaration(
            ConfigurationDataDeclaration(listOf(ConfigurationFieldDeclaration.StringField("host_profile", "linux:us", null))),
            ConfigurationUiDeclaration(listOf(dynamicField))
        )
        assertEquals(UiControl.DYNAMIC_CHOICE, valid.ui.fields.single().control)
        assertEquals(DynamicChoiceSource.KEYBOARD_OUTPUT_PROFILES, valid.ui.fields.single().source)

        assertThrows<IllegalArgumentException> {
            PackageConfigurationDeclaration(
                ConfigurationDataDeclaration(listOf(ConfigurationFieldDeclaration.StringField("host_profile", "linux:us", listOf("linux:us")))),
                ConfigurationUiDeclaration(listOf(dynamicField))
            )
        }
        assertThrows<IllegalArgumentException> {
            PackageConfigurationDeclaration(
                ConfigurationDataDeclaration(listOf(ConfigurationFieldDeclaration.BooleanField("host_profile", true))),
                ConfigurationUiDeclaration(listOf(dynamicField))
            )
        }
        assertThrows<IllegalArgumentException> {
            PackageConfigurationDeclaration(
                ConfigurationDataDeclaration(listOf(ConfigurationFieldDeclaration.IntegerField("host_profile", 1L, null, null))),
                ConfigurationUiDeclaration(listOf(dynamicField))
            )
        }
    }

    @Test
    fun `manifest decoder accepts valid dynamic choice and preserves source without resolution`() = withTemporaryDirectory { root ->
        val config = """{"schemaVersion":1,"data":{"fields":[{"id":"host_profile","type":"string","default":"linux:us"}],"additionalProperties":false},"ui":{"fields":[{"field":"host_profile","control":"dynamic-choice","source":"keyboard-output-profiles","label":"Host profile","help":"Select host profile"}]}}"""
        val revision = expectSuccess(validate(root, archiveBytes(manifest = manifest(configuration = config, capabilities = "[\"keyboard.output\"]", injectAdditionalProperties = false)), sourceRecord()))

        val uiField = revision.manifest.configuration.ui.fields.single()
        assertEquals("host_profile", uiField.field)
        assertEquals(UiControl.DYNAMIC_CHOICE, uiField.control)
        assertEquals("keyboard-output-profiles", uiField.source)
        assertNull(uiField.choices)
        assertEquals(setOf("keyboard.output"), revision.manifest.capabilities)
    }

    @Test
    fun `manifest decoder rejects malformed dynamic choice declarations`() = withTemporaryDirectory { root ->
        val unconstrained = """{"id":"host_profile","type":"string","default":"linux:us"}"""
        val validUi = """{"field":"host_profile","control":"dynamic-choice","source":"keyboard-output-profiles","label":"Host profile"}"""
        fun dynConfig(uiField: String, dataField: String = unconstrained) =
            """{"schemaVersion":1,"data":{"fields":[$dataField],"additionalProperties":false},"ui":{"fields":[$uiField]}}"""

        val cases = listOf(
            ManifestCase("static choices on dynamic-choice", dynConfig("""{"field":"host_profile","control":"dynamic-choice","source":"keyboard-output-profiles","label":"Host profile","choices":[{"value":"linux:us","label":"US"}]}"""), PackageFailure.FormatDetail.UNKNOWN_FIELDS),
            ManifestCase("missing source on dynamic-choice", dynConfig("""{"field":"host_profile","control":"dynamic-choice","label":"Host profile"}"""), PackageFailure.FormatDetail.MALFORMED_MANIFEST),
            ManifestCase("extra key on dynamic-choice", dynConfig("""{"field":"host_profile","control":"dynamic-choice","source":"keyboard-output-profiles","label":"Host profile","extra":1}"""), PackageFailure.FormatDetail.UNKNOWN_FIELDS),
            ManifestCase("unknown source", dynConfig("""{"field":"host_profile","control":"dynamic-choice","source":"bogus-source","label":"Host profile"}"""), PackageFailure.FormatDetail.MALFORMED_MANIFEST),
            ManifestCase("non-string source", dynConfig("""{"field":"host_profile","control":"dynamic-choice","source":123,"label":"Host profile"}"""), PackageFailure.FormatDetail.MALFORMED_MANIFEST),
            ManifestCase("null source", dynConfig("""{"field":"host_profile","control":"dynamic-choice","source":null,"label":"Host profile"}"""), PackageFailure.FormatDetail.MALFORMED_MANIFEST),
            ManifestCase("source on static choice control", dynConfig("""{"field":"host_profile","control":"choice","source":"keyboard-output-profiles","label":"Host profile","choices":[{"value":"linux:us","label":"US"}]}""", dataField = """{"id":"host_profile","type":"string","default":"linux:us","allowedValues":["linux:us"]}"""), PackageFailure.FormatDetail.UNKNOWN_FIELDS),
            ManifestCase("dynamic-choice targeting constrained string", dynConfig(validUi, dataField = """{"id":"host_profile","type":"string","default":"linux:us","allowedValues":["linux:us"]}"""), PackageFailure.FormatDetail.MALFORMED_MANIFEST),
            ManifestCase("dynamic-choice targeting boolean", dynConfig(validUi, dataField = """{"id":"host_profile","type":"boolean","default":true}"""), PackageFailure.FormatDetail.MALFORMED_MANIFEST),
            ManifestCase("dynamic-choice targeting integer", dynConfig(validUi, dataField = """{"id":"host_profile","type":"integer","default":1}"""), PackageFailure.FormatDetail.MALFORMED_MANIFEST),
        )
        cases.forEach { case ->
            assertFormat(
                validate(root, archiveBytes(manifest = manifest(configuration = case.json, injectAdditionalProperties = false)), sourceRecord()),
                case.expected as PackageFailure.FormatDetail,
                case.name,
            )
        }
    }
    @Test
    fun `manifest decoder accepts and retains a three stage dependsOn hierarchy and rejects its violations`() = withTemporaryDirectory { root ->
        // Acceptance: host_os -> host_layout -> host_profile validates and every
        // dependsOn edge plus all three source identifiers are retained verbatim.
        val hierarchy = """{"schemaVersion":1,"data":{"fields":[{"id":"host_os","type":"string","default":"linux"},{"id":"host_layout","type":"string","default":"linux:us"},{"id":"host_profile","type":"string","default":"linux:us"}],"additionalProperties":false},"ui":{"fields":[{"field":"host_os","control":"dynamic-choice","label":"Host OS","source":"keyboard-output-platforms"},{"field":"host_layout","control":"dynamic-choice","label":"Host layout","source":"keyboard-output-layouts","dependsOn":"host_os"},{"field":"host_profile","control":"dynamic-choice","label":"Host profile","source":"keyboard-output-profiles","dependsOn":"host_layout"}]}}"""
        val revision = expectSuccess(validate(root, archiveBytes(manifest = manifest(configuration = hierarchy, capabilities = "[\"keyboard.output\"]", injectAdditionalProperties = false)), sourceRecord()))
        val uiFields = revision.manifest.configuration.ui.fields
        assertEquals(listOf("host_os", "host_layout", "host_profile"), uiFields.map { it.field })
        assertNull(uiFields[0].dependsOnFieldId)
        assertEquals(DynamicChoiceSource.KEYBOARD_OUTPUT_PLATFORMS, uiFields[0].source)
        assertEquals("host_os", uiFields[1].dependsOnFieldId)
        assertEquals(DynamicChoiceSource.KEYBOARD_OUTPUT_LAYOUTS, uiFields[1].source)
        assertEquals("host_layout", uiFields[2].dependsOnFieldId)
        assertEquals(DynamicChoiceSource.KEYBOARD_OUTPUT_PROFILES, uiFields[2].source)

        // Rejection: dependsOn is exact-key gated to dynamic-choice and must name an
        // earlier unconstrained string data field (not self, later, unknown, or misshapen).
        val twoStrings = """{"id":"host_os","type":"string","default":"linux"},{"id":"host_layout","type":"string","default":"linux:us"}"""
        fun config(uiFields: String, data: String = twoStrings) =
            """{"schemaVersion":1,"data":{"fields":[$data],"additionalProperties":false},"ui":{"fields":[$uiFields]}}"""
        val platform = """{"field":"host_os","control":"dynamic-choice","label":"Host OS","source":"keyboard-output-platforms"}"""
        val cases = listOf(
            ManifestCase("dependsOn on a static text control", config("""{"field":"host_os","control":"text","label":"Host OS","dependsOn":"host_os"}""", data = """{"id":"host_os","type":"string","default":"linux"}"""), PackageFailure.FormatDetail.UNKNOWN_FIELDS),
            ManifestCase("dependsOn references an unknown field", config("""$platform,{"field":"host_layout","control":"dynamic-choice","label":"Host layout","source":"keyboard-output-layouts","dependsOn":"missing"}"""), PackageFailure.FormatDetail.MALFORMED_MANIFEST),
            ManifestCase("dependsOn references itself", config("""$platform,{"field":"host_layout","control":"dynamic-choice","label":"Host layout","source":"keyboard-output-layouts","dependsOn":"host_layout"}"""), PackageFailure.FormatDetail.MALFORMED_MANIFEST),
            ManifestCase("dependsOn references a later field", config("""{"field":"host_layout","control":"dynamic-choice","label":"Host layout","source":"keyboard-output-layouts","dependsOn":"host_os"},$platform"""), PackageFailure.FormatDetail.MALFORMED_MANIFEST),
            ManifestCase("blank dependsOn", config("""$platform,{"field":"host_layout","control":"dynamic-choice","label":"Host layout","source":"keyboard-output-layouts","dependsOn":""}"""), PackageFailure.FormatDetail.MALFORMED_MANIFEST),
            ManifestCase("dependsOn references a constrained string field", config("""{"field":"host_os","control":"choice","label":"Host OS","choices":[{"value":"linux","label":"Linux"}]},{"field":"host_layout","control":"dynamic-choice","label":"Host layout","source":"keyboard-output-layouts","dependsOn":"host_os"}""", data = """{"id":"host_os","type":"string","default":"linux","allowedValues":["linux"]},{"id":"host_layout","type":"string","default":"linux:us"}"""), PackageFailure.FormatDetail.MALFORMED_MANIFEST),
            ManifestCase("dependsOn references a non-string field", config("""{"field":"host_os","control":"toggle","label":"Host OS"},{"field":"host_layout","control":"dynamic-choice","label":"Host layout","source":"keyboard-output-layouts","dependsOn":"host_os"}""", data = """{"id":"host_os","type":"boolean","default":false},{"id":"host_layout","type":"string","default":"linux:us"}"""), PackageFailure.FormatDetail.MALFORMED_MANIFEST),
        )
        cases.forEach { case ->
            assertFormat(
                validate(root, archiveBytes(manifest = manifest(configuration = case.json, injectAdditionalProperties = false)), sourceRecord()),
                case.expected as PackageFailure.FormatDetail,
                case.name,
            )
        }
    }

    @Test
    fun `keyboard output eligibility and dynamic source declarations are deterministic across revalidation`() = withTemporaryDirectory { root ->
        val config = """{"schemaVersion":1,"data":{"fields":[{"id":"host_profile","type":"string","default":"linux:us"}],"additionalProperties":false},"ui":{"fields":[{"field":"host_profile","control":"dynamic-choice","source":"keyboard-output-profiles","label":"Host profile"}]}}"""
        val archive = archiveBytes(manifest = manifest(configuration = config, capabilities = "[\"audio.transcription\",\"keyboard.output\"]", injectAdditionalProperties = false))

        val first = expectSuccess(validate(root, archive, sourceRecord()))
        val second = expectSuccess(validate(root, archive, sourceRecord()))

        assertEquals(first.manifest.capabilities, second.manifest.capabilities)
        assertEquals(listOf("audio.transcription", "keyboard.output"), second.manifest.capabilities.toList())
        assertEquals(first.manifest.configuration, second.manifest.configuration)
        assertEquals(DynamicChoiceSource.KEYBOARD_OUTPUT_PROFILES, second.manifest.configuration.ui.fields.single().source)

        assertThrows<UnsupportedOperationException> {
            (first.sourceMap as MutableMap<String, String>)["injected"] = "tampered"
        }
    }

    @Test
    fun `revised manifest requires explicit profile resolver and queue declaration arrays`() = withTemporaryDirectory { root ->
        val revision = expectSuccess(validate(root, archiveBytes(), sourceRecord()))
        assertTrue("profileTypes must be retained empty", revision.manifest.profileTypes.isEmpty())
        assertTrue("choiceResolvers must be retained empty", revision.manifest.choiceResolvers.isEmpty())
        assertTrue("workQueues must be retained empty", revision.manifest.workQueues.isEmpty())

        val base = manifest()
        val cases = listOf(
            ManifestCase("missing profileTypes", base.replace("\"profileTypes\":[],", ""), PackageFailure.FormatDetail.MALFORMED_MANIFEST),
            ManifestCase("missing choiceResolvers", base.replace("\"choiceResolvers\":[],", ""), PackageFailure.FormatDetail.MALFORMED_MANIFEST),
            ManifestCase("missing workQueues", base.replace("\"workQueues\":[],", ""), PackageFailure.FormatDetail.MALFORMED_MANIFEST),
            ManifestCase("null profileTypes", base.replace("\"profileTypes\":[]", "\"profileTypes\":null"), PackageFailure.FormatDetail.MALFORMED_MANIFEST),
            ManifestCase("null choiceResolvers", base.replace("\"choiceResolvers\":[]", "\"choiceResolvers\":null"), PackageFailure.FormatDetail.MALFORMED_MANIFEST),
            ManifestCase("null workQueues", base.replace("\"workQueues\":[]", "\"workQueues\":null"), PackageFailure.FormatDetail.MALFORMED_MANIFEST),
            ManifestCase("mistyped profileTypes", base.replace("\"profileTypes\":[]", "\"profileTypes\":{}"), PackageFailure.FormatDetail.MALFORMED_MANIFEST),
            ManifestCase("mistyped choiceResolvers", base.replace("\"choiceResolvers\":[]", "\"choiceResolvers\":{}"), PackageFailure.FormatDetail.MALFORMED_MANIFEST),
            ManifestCase("mistyped workQueues", base.replace("\"workQueues\":[]", "\"workQueues\":{}"), PackageFailure.FormatDetail.MALFORMED_MANIFEST),
            ManifestCase("non-string work queue id", base.replace("\"workQueues\":[]", "\"workQueues\":[1]"), PackageFailure.FormatDetail.MALFORMED_MANIFEST),
            ManifestCase("unknown root field beside declarations", base.dropLast(1) + ",\"declarations\":[]}", PackageFailure.FormatDetail.UNKNOWN_FIELDS),
        )
        cases.forEach { case ->
            assertFormat(validate(root, archiveBytes(manifest = case.json), sourceRecord()), case.expected as PackageFailure.FormatDetail, case.name)
        }
    }

    @Test
    fun `valid new declarations are retained exactly through validation`() = withTemporaryDirectory { root ->
        val declared = manifest(
            profileTypes = """[{"id":"openai_compatible","label":"OpenAI-compatible","help":"Provider profile","schemaVersion":1,"data":{"additionalProperties":false,"fields":[{"id":"base_url","type":"string","required":true,"default":"https://api.openai.com"},{"id":"api_key","type":"secret","required":true}]},"ui":{"fields":[{"field":"base_url","control":"text","label":"Base URL"},{"field":"api_key","control":"secret","label":"API key"}]}}]""",
            choiceResolvers = """[{"id":"models","module":"choices.models","capabilities":["network.http","profiles.read","secrets.read"]}]""",
            workQueues = """["turns"]""",
            capabilities = """["network.http","profiles.read","secrets.read","work.queue"]"""
        )
        val revision = expectSuccess(
            validate(
                root,
                archiveBytes(manifest = declared, modules = mapOf("main" to SOURCE, "choices.models" to "return { resolve = function() end }")),
                sourceRecord(),
            )
        )

        val profileType = revision.manifest.profileTypes.single()
        assertEquals("openai_compatible", profileType.id)
        assertEquals("OpenAI-compatible", profileType.label)
        assertEquals("Provider profile", profileType.help)
        assertEquals(1, profileType.schemaVersion)
        assertEquals(setOf("api_key"), profileType.schema.secretFieldIds())
        assertEquals(setOf("base_url"), profileType.schema.scalarFieldIds())
        assertEquals(listOf("base_url", "api_key"), profileType.schema.uiFields.map { it.field })

        val resolver = revision.manifest.choiceResolvers.single()
        assertEquals("models", resolver.id)
        assertEquals("choices.models", resolver.module)
        assertEquals(listOf("network.http", "profiles.read", "secrets.read"), resolver.capabilities.toList())

        assertEquals(listOf("turns"), revision.manifest.workQueues.map { it.id })
        assertEquals(listOf("network.http", "profiles.read", "secrets.read", "work.queue"), revision.manifest.capabilities.toList())
    }

    @Test
    fun `profile type declarations reject malformed schemas unknown keys duplicates and bounds violations`() = withTemporaryDirectory { root ->
        val validField = """{"id":"base_url","type":"string","required":true,"default":"x"}"""
        val validUi = """{"field":"base_url","control":"text","label":"Base URL"}"""
        val validType = """{"id":"provider","label":"Provider","schemaVersion":1,"data":{"additionalProperties":false,"fields":[$validField]},"ui":{"fields":[$validUi]}}"""
        val oversizedId = "a".repeat(129)
        val nineTypes = (1..9).joinToString(",") { validType.replace("\"id\":\"provider\"", "\"id\":\"t$it\"") }
        val cases = listOf(
            ManifestCase("duplicate profile type id", "[$validType,$validType]", PackageFailure.FormatDetail.MALFORMED_MANIFEST),
            ManifestCase("invalid profile type id pattern", """[{"id":"Provider","label":"Provider","schemaVersion":1,"data":{"additionalProperties":false,"fields":[$validField]},"ui":{"fields":[$validUi]}}]""", PackageFailure.FormatDetail.MALFORMED_MANIFEST),
            ManifestCase("oversized profile type id", """[{"id":"$oversizedId","label":"Provider","schemaVersion":1,"data":{"additionalProperties":false,"fields":[$validField]},"ui":{"fields":[$validUi]}}]""", PackageFailure.FormatDetail.MALFORMED_MANIFEST),
            ManifestCase("missing schemaVersion", """[{"id":"provider","label":"Provider","data":{"additionalProperties":false,"fields":[$validField]},"ui":{"fields":[$validUi]}}]""", PackageFailure.FormatDetail.MALFORMED_MANIFEST),
            ManifestCase("unsupported schemaVersion", """[{"id":"provider","label":"Provider","schemaVersion":2,"data":{"additionalProperties":false,"fields":[$validField]},"ui":{"fields":[$validUi]}}]""", PackageFailure.FormatDetail.MALFORMED_MANIFEST),
            ManifestCase("unknown key in profile type", """[{"id":"provider","label":"Provider","schemaVersion":1,"extra":1,"data":{"additionalProperties":false,"fields":[$validField]},"ui":{"fields":[$validUi]}}]""", PackageFailure.FormatDetail.UNKNOWN_FIELDS),
            ManifestCase("blank profile type label", """[{"id":"provider","label":"  ","schemaVersion":1,"data":{"additionalProperties":false,"fields":[$validField]},"ui":{"fields":[$validUi]}}]""", PackageFailure.FormatDetail.MALFORMED_MANIFEST),
            ManifestCase("missing additionalProperties in profile data", """[{"id":"provider","label":"Provider","schemaVersion":1,"data":{"fields":[$validField]},"ui":{"fields":[$validUi]}}]""", PackageFailure.FormatDetail.MALFORMED_MANIFEST),
            ManifestCase("additionalProperties true in profile data", """[{"id":"provider","label":"Provider","schemaVersion":1,"data":{"additionalProperties":true,"fields":[$validField]},"ui":{"fields":[$validUi]}}]""", PackageFailure.FormatDetail.MALFORMED_MANIFEST),
            ManifestCase("secret field with plaintext default", """[{"id":"provider","label":"Provider","schemaVersion":1,"data":{"additionalProperties":false,"fields":[{"id":"api_key","type":"secret","required":true,"default":"sk-123"}]},"ui":{"fields":[{"field":"api_key","control":"secret","label":"API key"}]}}]""", PackageFailure.FormatDetail.UNKNOWN_FIELDS),
            ManifestCase("secret field with allowedValues", """[{"id":"provider","label":"Provider","schemaVersion":1,"data":{"additionalProperties":false,"fields":[{"id":"api_key","type":"secret","required":true,"allowedValues":["a"]}]},"ui":{"fields":[{"field":"api_key","control":"secret","label":"API key"}]}}]""", PackageFailure.FormatDetail.UNKNOWN_FIELDS),
            ManifestCase("missing required on profile field", """[{"id":"provider","label":"Provider","schemaVersion":1,"data":{"additionalProperties":false,"fields":[{"id":"base_url","type":"string","default":"x"}]},"ui":{"fields":[$validUi]}}]""", PackageFailure.FormatDetail.MALFORMED_MANIFEST),
            ManifestCase("secret field with non-secret control", """[{"id":"provider","label":"Provider","schemaVersion":1,"data":{"additionalProperties":false,"fields":[{"id":"api_key","type":"secret","required":true}]},"ui":{"fields":[{"field":"api_key","control":"text","label":"API key"}]}}]""", PackageFailure.FormatDetail.MALFORMED_MANIFEST),
            ManifestCase("secret control with choices", """[{"id":"provider","label":"Provider","schemaVersion":1,"data":{"additionalProperties":false,"fields":[{"id":"api_key","type":"secret","required":true}]},"ui":{"fields":[{"field":"api_key","control":"secret","label":"API key","choices":[{"value":"a","label":"A"}]}]}}]""", PackageFailure.FormatDetail.UNKNOWN_FIELDS),
            ManifestCase("ui missing a data field", """[{"id":"provider","label":"Provider","schemaVersion":1,"data":{"additionalProperties":false,"fields":[$validField,{"id":"extra","type":"boolean","required":false,"default":false}]},"ui":{"fields":[$validUi]}}]""", PackageFailure.FormatDetail.MALFORMED_MANIFEST),
            ManifestCase("nine profile types exceed bound", "[$nineTypes]", PackageFailure.FormatDetail.MALFORMED_MANIFEST),
        )
        cases.forEach { case ->
            assertFormat(validate(root, archiveBytes(manifest = manifest(profileTypes = case.json)), sourceRecord()), case.expected as PackageFailure.FormatDetail, case.name)
        }
    }

    @Test
    fun `choice resolver declarations enforce module presence capability subsets and uniqueness`() = withTemporaryDirectory { root ->
        val modules = mapOf("main" to SOURCE, "choices.models" to "return { resolve = function() end }")
        val validResolver = """{"id":"models","module":"choices.models","capabilities":[]}"""

        val okRevision = expectSuccess(validate(root, archiveBytes(manifest = manifest(choiceResolvers = "[$validResolver]"), modules = modules), sourceRecord()))
        assertEquals(emptySet<String>(), okRevision.manifest.choiceResolvers.single().capabilities)

        assertFormat(
            validate(root, archiveBytes(manifest = manifest(choiceResolvers = """[{"id":"models","module":"choices.missing","capabilities":[]}]"""), modules = modules), sourceRecord()),
            PackageFailure.FormatDetail.MISSING_RESOLVER_MODULE,
            "resolver module absent from source map",
        )
        assertCapability(
            validate(root, archiveBytes(manifest = manifest(choiceResolvers = """[{"id":"models","module":"choices.models","capabilities":["network.http"]}]"""), modules = modules), sourceRecord()),
            PackageFailure.CapabilityDetail.RESOLVER_CAPABILITY_UNDECLARED,
            "resolver capability not declared by package",
        )
        assertCapability(
            validate(
                root,
                archiveBytes(
                    manifest = manifest(
                        choiceResolvers = """[{"id":"models","module":"choices.models","capabilities":["keyboard.output"]}]""",
                        capabilities = """["keyboard.output"]"""
                    ),
                    modules = modules
                ),
                sourceRecord()
            ),
            PackageFailure.CapabilityDetail.RESOLVER_CAPABILITY_INELIGIBLE,
            "resolver capability outside resolver allowlist",
        )

        val nineResolvers = (1..9).joinToString(",") { """{"id":"r$it","module":"choices.models","capabilities":[]}""" }
        val cases = listOf(
            ManifestCase("duplicate resolver id", "[$validResolver,$validResolver]", PackageFailure.FormatDetail.MALFORMED_MANIFEST),
            ManifestCase("missing capabilities key", """[{"id":"models","module":"choices.models"}]""", PackageFailure.FormatDetail.MALFORMED_MANIFEST),
            ManifestCase("missing module key", """[{"id":"models","capabilities":[]}]""", PackageFailure.FormatDetail.MALFORMED_MANIFEST),
            ManifestCase("unknown resolver key", """[{"id":"models","module":"choices.models","capabilities":[],"extra":1}]""", PackageFailure.FormatDetail.UNKNOWN_FIELDS),
            ManifestCase("invalid resolver id pattern", """[{"id":"Models","module":"choices.models","capabilities":[]}]""", PackageFailure.FormatDetail.MALFORMED_MANIFEST),
            ManifestCase("invalid module grammar", """[{"id":"models","module":"Choices.Models","capabilities":[]}]""", PackageFailure.FormatDetail.MALFORMED_MANIFEST),
            ManifestCase("nine resolvers exceed bound", "[$nineResolvers]", PackageFailure.FormatDetail.MALFORMED_MANIFEST),
        )
        cases.forEach { case ->
            assertFormat(validate(root, archiveBytes(manifest = manifest(choiceResolvers = case.json), modules = modules), sourceRecord()), case.expected as PackageFailure.FormatDetail, case.name)
        }
        // Duplicate capability entries are malformed even when the package
        // declares the capability: the duplicate itself is the violation.
        assertFormat(
            validate(
                root,
                archiveBytes(
                    manifest = manifest(
                        choiceResolvers = """[{"id":"models","module":"choices.models","capabilities":["network.http","network.http"]}]""",
                        capabilities = """["network.http"]"""
                    ),
                    modules = modules
                ),
                sourceRecord()
            ),
            PackageFailure.FormatDetail.MALFORMED_MANIFEST,
            "duplicate resolver capability",
        )
    }

    @Test
    fun `work queue declarations require work capability uniqueness and canonical grammar`() = withTemporaryDirectory { root ->
        val capOnly = expectSuccess(validate(root, archiveBytes(manifest = manifest(capabilities = """["work.queue"]""")), sourceRecord()))
        assertTrue("work.queue without queues grants no usable queue", capOnly.manifest.workQueues.isEmpty())

        val withQueue = expectSuccess(validate(root, archiveBytes(manifest = manifest(workQueues = """["turns"]""", capabilities = """["work.queue"]""")), sourceRecord()))
        assertEquals(listOf("turns"), withQueue.manifest.workQueues.map { it.id })

        assertCapability(
            validate(root, archiveBytes(manifest = manifest(workQueues = """["turns"]""")), sourceRecord()),
            PackageFailure.CapabilityDetail.WORK_QUEUE_MISSING_CAPABILITY,
            "queue declaration without work.queue capability",
        )

        val cases = listOf(
            ManifestCase("duplicate queue id", """["turns","turns"]""", PackageFailure.FormatDetail.MALFORMED_MANIFEST),
            ManifestCase("invalid queue id grammar", """["Turns"]""", PackageFailure.FormatDetail.MALFORMED_MANIFEST),
            ManifestCase("nine queues exceed bound", "[" + (1..9).joinToString(",") { "\"q$it\"" } + "]", PackageFailure.FormatDetail.MALFORMED_MANIFEST),
        )
        cases.forEach { case ->
            assertFormat(
                validate(root, archiveBytes(manifest = manifest(workQueues = case.json, capabilities = """["work.queue"]""")), sourceRecord()),
                case.expected as PackageFailure.FormatDetail,
                case.name,
            )
        }
    }

    @Test
    fun `secret profile fields require declared secret read capability`() = withTemporaryDirectory { root ->
        val secretType = """[{"id":"provider","label":"Provider","schemaVersion":1,"data":{"additionalProperties":false,"fields":[{"id":"api_key","type":"secret","required":true}]},"ui":{"fields":[{"field":"api_key","control":"secret","label":"API key"}]}}]"""
        assertCapability(
            validate(root, archiveBytes(manifest = manifest(profileTypes = secretType)), sourceRecord()),
            PackageFailure.CapabilityDetail.SECRET_MISSING_CAPABILITY,
            "secret field without secrets.read capability",
        )
        val eligible = expectSuccess(
            validate(root, archiveBytes(manifest = manifest(profileTypes = secretType, capabilities = """["secrets.read"]""")), sourceRecord())
        )
        assertEquals(setOf("api_key"), eligible.manifest.profileTypes.single().schema.secretFieldIds())
    }

    @Test
    fun `dynamic choice sources distinguish host profile and resolver references and reject unknown targets`() = withTemporaryDirectory { root ->
        val modules = mapOf("main" to SOURCE, "choices.models" to "return { resolve = function() end }")
        val declared = manifest(
            configuration = """{"schemaVersion":1,"data":{"fields":[{"id":"profile_id","type":"string","default":""},{"id":"model","type":"string","default":""}],"additionalProperties":false},"ui":{"fields":[{"field":"profile_id","control":"dynamic-choice","label":"Profile","source":"profile:provider"},{"field":"model","control":"dynamic-choice","label":"Model","source":"resolver:models","dependsOn":"profile_id"}]}}""",
            profileTypes = """[{"id":"provider","label":"Provider","schemaVersion":1,"data":{"additionalProperties":false,"fields":[{"id":"api_key","type":"secret","required":true}]},"ui":{"fields":[{"field":"api_key","control":"secret","label":"API key"}]}}]""",
            choiceResolvers = """[{"id":"models","module":"choices.models","capabilities":["network.http","secrets.read"]}]""",
            capabilities = """["network.http","secrets.read"]""",
            injectAdditionalProperties = false
        )
        val revision = expectSuccess(validate(root, archiveBytes(manifest = declared, modules = modules), sourceRecord()))
        val uiFields = revision.manifest.configuration.ui.fields
        assertEquals("profile:provider", uiFields[0].source)
        assertTrue(uiFields[0].sourceReference is DynamicChoiceSourceReference.ProfileType)
        assertEquals("provider", (uiFields[0].sourceReference as DynamicChoiceSourceReference.ProfileType).typeId)
        assertEquals("resolver:models", uiFields[1].source)
        assertTrue(uiFields[1].sourceReference is DynamicChoiceSourceReference.PackageResolver)
        assertEquals("models", (uiFields[1].sourceReference as DynamicChoiceSourceReference.PackageResolver).resolverId)
        assertEquals("profile_id", uiFields[1].dependsOnFieldId)

        fun configFor(source: String) =
            """{"schemaVersion":1,"data":{"fields":[{"id":"model","type":"string","default":""}],"additionalProperties":false},"ui":{"fields":[{"field":"model","control":"dynamic-choice","label":"Model","source":"$source"}]}}"""
        val cases = listOf(
            ManifestCase("unknown profile type reference", configFor("profile:absent"), PackageFailure.FormatDetail.MALFORMED_MANIFEST),
            ManifestCase("unknown resolver reference", configFor("resolver:absent"), PackageFailure.FormatDetail.MALFORMED_MANIFEST),
            ManifestCase("malformed profile prefix", configFor("profile:"), PackageFailure.FormatDetail.MALFORMED_MANIFEST),
            ManifestCase("malformed resolver prefix", configFor("resolver:"), PackageFailure.FormatDetail.MALFORMED_MANIFEST),
            ManifestCase("unknown host source", configFor("keyboard-output-missing"), PackageFailure.FormatDetail.MALFORMED_MANIFEST),
        )
        cases.forEach { case ->
            assertFormat(validate(root, archiveBytes(manifest = manifest(configuration = case.json, injectAdditionalProperties = false), modules = modules), sourceRecord()), case.expected as PackageFailure.FormatDetail, case.name)
        }
    }

    @Test
    fun `multiline control is accepted for unconstrained strings and rejected elsewhere`() = withTemporaryDirectory { root ->
        val multilineConfig = """{"schemaVersion":1,"data":{"fields":[{"id":"prompt","type":"string","default":"line one\nline two"}],"additionalProperties":false},"ui":{"fields":[{"field":"prompt","control":"multiline","label":"Prompt"}]}}"""
        val revision = expectSuccess(validate(root, archiveBytes(manifest = manifest(configuration = multilineConfig, injectAdditionalProperties = false)), sourceRecord()))
        val uiField = revision.manifest.configuration.ui.fields.single()
        assertEquals(UiControl.MULTILINE, uiField.control)
        assertEquals("line one\nline two", (revision.manifest.configuration.data.fields.single() as ConfigurationFieldDeclaration.StringField).default)

        val cases = listOf(
            ManifestCase("multiline on constrained string", """{"schemaVersion":1,"data":{"fields":[{"id":"mode","type":"string","default":"a","allowedValues":["a"]}],"additionalProperties":false},"ui":{"fields":[{"field":"mode","control":"multiline","label":"Mode"}]}}""", PackageFailure.FormatDetail.MALFORMED_MANIFEST),
            ManifestCase("multiline on boolean", """{"schemaVersion":1,"data":{"fields":[{"id":"flag","type":"boolean","default":true}],"additionalProperties":false},"ui":{"fields":[{"field":"flag","control":"multiline","label":"Flag"}]}}""", PackageFailure.FormatDetail.MALFORMED_MANIFEST),
            ManifestCase("multiline on integer", """{"schemaVersion":1,"data":{"fields":[{"id":"count","type":"integer","default":1}],"additionalProperties":false},"ui":{"fields":[{"field":"count","control":"multiline","label":"Count"}]}}""", PackageFailure.FormatDetail.MALFORMED_MANIFEST),
        )
        cases.forEach { case ->
            assertFormat(validate(root, archiveBytes(manifest = manifest(configuration = case.json, injectAdditionalProperties = false)), sourceRecord()), case.expected as PackageFailure.FormatDetail, case.name)
        }
    }

    private companion object {
        private const val LOCAL_SIGNATURE = 0x04034b50L
        private const val CENTRAL_SIGNATURE = 0x02014b50L
        private const val EOCD_SIGNATURE = 0x06054b50L
        private const val SOURCE = "return { startup = function() end, handle_readiness = function() return { ready = true } end }"
    }
}
