package io.talkcan.dependency

import java.security.MessageDigest
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Black-box contracts for the anonymous GitHub source client. The transport is deliberately a
 * local scripted boundary: every assertion concerns an observable request, decoded value, or
 * typed failure without contacting GitHub.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GitHubSourceContractTest {
    @Test
    fun `repository URL grammar accepts canonical mixed-case host and rejects every noncanonical form`() {
        val accepted = GitHubUrlParser.parse("https://GitHuB.cOm/Owner-1/repo._-")
        assertEquals(
            GitHubRepositoryCoordinates("Owner-1", "repo._-"),
            success(accepted),
        )

        val malformed = listOf(
            "non-HTTPS scheme" to "http://github.com/owner/repository",
            "uppercase scheme" to "HTTPS://github.com/owner/repository",
            "authority missing" to "https:/owner/repository",
            "user info" to "https://user@github.com/owner/repository",
            "explicit port" to "https://github.com:443/owner/repository",
            "query" to "https://github.com/owner/repository?ref=main",
            "fragment" to "https://github.com/owner/repository#readme",
            "trailing slash" to "https://github.com/owner/repository/",
            "percent encoding" to "https://github.com/owner%2Frepository",
            "unicode" to "https://github.com/ownér/repository",
            "traversal" to "https://github.com/owner/../repository",
            "repository traversal" to "https://github.com/owner/..",
            "extra path segment" to "https://github.com/owner/repository/releases",
            "empty owner" to "https://github.com//repository",
            "empty repository" to "https://github.com/owner/",
            "owner leading hyphen" to "https://github.com/-owner/repository",
            "owner trailing hyphen" to "https://github.com/owner-/repository",
            "dot owner" to "https://github.com/./repository",
            "git suffix" to "https://github.com/owner/repository.GIT",
            "space" to "https://github.com/owner/repo name",
        )
        malformed.forEach { (name, url) ->
            assertFailure(GitHubUrlParser.parse(url), GitHubSourceFailure.MalformedUrl, name)
        }
        assertFailure(
            GitHubUrlParser.parse("https://example.com/owner/repository"),
            GitHubSourceFailure.UnsupportedHostPath,
            "non-GitHub host",
        )

        val tightUrlLimit = bounds(maxUrlBytes = 32)
        assertFailure(
            GitHubUrlParser.parse("https://github.com/owner/repository", tightUrlLimit),
            GitHubSourceFailure.BoundsExceeded,
            "UTF-8 URL byte bound",
        )
    }

    @Test
    fun `source domain values reject noncanonical identities and incomplete metadata`() {
        listOf("", "0", "01", "-1", "1.0", "one").forEach { invalid ->
            assertIllegalArgument("repository identity $invalid") { GitHubRepositoryIdentity(invalid) }
            assertIllegalArgument("repository owner $invalid") { GitHubRepositoryOwner(invalid, "owner", "User") }
            assertIllegalArgument("release identity $invalid") { GitHubReleaseIdentity(invalid, "v1", false) }
            assertIllegalArgument("asset identity $invalid") { GitHubAssetIdentity(invalid, "asset.zip") }
        }
        assertIllegalArgument("empty repository owner coordinates") { GitHubRepositoryCoordinates("", "repository") }
        assertIllegalArgument("repository path separator") { GitHubRepositoryCoordinates("owner", "a/b") }
        assertIllegalArgument("empty release tag") { GitHubReleaseIdentity("1", " ", false) }
        assertIllegalArgument("zero asset size") {
            GitHubReleaseAsset("1", "asset.zip", "uploaded", "application/zip", 0, "https://download.example/asset")
        }
    }

    @Test
    fun `repository resolution sends anonymous GitHub API request and preserves exact large decimal identities`() = runTest {
        val enormousId = "9223372036854775800"
        val transport = QueueTransport(
            response(200, repositoryJson(id = enormousId, ownerId = enormousId, ownerLogin = "independent-owner")),
        )
        val result = client(transport).resolveRepository(coordinates)

        val repository = success(result)
        assertEquals(enormousId, repository.id.value)
        assertEquals(enormousId, repository.owner.ownerId)
        assertEquals("independent-owner", repository.owner.login)
        assertEquals("Owner/Repository", repository.fullName)
        assertEquals(1, transport.requests.size)
        assertEquals("https://api.github.com/repos/Owner/Repository", transport.requests.single().url)
        assertEquals(
            mapOf(
                "Accept" to "application/vnd.github+json",
                "X-GitHub-Api-Version" to GitHubSourceConfiguration.REST_API_VERSION,
                "User-Agent" to "Talkcan/0.7.0",
            ),
            transport.requests.single().headers,
        )
        assertFalse(
            "anonymous source resolution must not send an Authorization header",
            transport.requests.single().headers.keys.any { it.equals("Authorization", ignoreCase = true) },
        )
    }

    @Test
    fun `repository visibility archive and disabled states remain distinct typed rejections`() = runTest {
        val cases = listOf(
            RepositoryFailureCase("private", repositoryJson(visibility = "private"), GitHubSourceFailure.PrivateRepository),
            RepositoryFailureCase("archived", repositoryJson(archived = true), GitHubSourceFailure.ArchivedRepository),
            RepositoryFailureCase("disabled", repositoryJson(disabled = true), GitHubSourceFailure.DisabledRepository),
        )
        cases.forEach { case ->
            assertFailure(
                client(QueueTransport(response(200, case.json))).resolveRepository(coordinates),
                case.expected,
                case.name,
            )
        }
    }

    @Test
    fun `repository decoding rejects malformed payloads and enforces configured metadata byte bound`() = runTest {
        val malformedCases = listOf(
            "invalid JSON" to "{not-json}",
            "missing owner" to """{"id":123,"full_name":"Owner/Repository","archived":false,"disabled":false,"visibility":"public"}""",
            "noncanonical id" to repositoryJson(id = "0"),
        )
        malformedCases.forEach { (name, json) ->
            assertFailure(
                client(QueueTransport(response(200, json))).resolveRepository(coordinates),
                GitHubSourceFailure.MalformedResponse,
                name,
            )
        }

        val json = repositoryJson()
        assertFailure(
            client(QueueTransport(response(200, json)), bounds(maxMetadataResponseBytes = json.toByteArray().size.toLong() - 1))
                .resolveRepository(coordinates),
            GitHubSourceFailure.BoundsExceeded,
            "repository metadata beyond configured byte bound",
        )
    }

    @Test
    fun `release decoding keeps only bounded stable canonical assets and orders newest release without tag version semantics`() = runTest {
        val hugeEarlierId = "9223372036854775807"
        val payload = "[" + listOf(
            releaseJson("2", "2025-01-01T00:00:00Z", tag = "not-a-version", assetId = "12"),
            releaseJson(hugeEarlierId, "2024-12-31T23:59:59Z", tag = "zebra", assetId = hugeEarlierId),
            releaseJson("3", "2024-01-01T00:00:00Z", tag = "v999", assetId = "13"),
        ).joinToString(",") + "]"
        val transport = QueueTransport(response(200, payload))

        val releases = success(client(transport, bounds(maxReleaseCandidates = 2)).listStableReleaseAssets(coordinates))

        assertEquals(2, releases.size)
        assertEquals("2", releases[0].first.releaseId)
        assertEquals("not-a-version", releases[0].first.tag)
        assertEquals(hugeEarlierId, releases[1].first.releaseId)
        assertEquals(hugeEarlierId, releases[1].second.assetId)
        assertEquals("https://api.github.com/repos/Owner/Repository/releases", transport.requests.single().url)
        assertEquals("application/vnd.github+json", transport.requests.single().headers["Accept"])
        assertFalse(
            "anonymous release enumeration must not send authorization",
            transport.requests.single().headers.keys.any { it.equals("Authorization", ignoreCase = true) },
        )
    }

    @Test
    fun `release ordering uses publication time then large decimal release identity`() = runTest {
        val lowerId = "9223372036854775806"
        val higherId = "9223372036854775807"
        val sameTime = "2025-01-01T00:00:00Z"
        val payload = "[${releaseJson(lowerId, sameTime, assetId = "4")},${releaseJson(higherId, sameTime, assetId = "5")}]"

        val releases = success(client(QueueTransport(response(200, payload))).listStableReleaseAssets(coordinates))

        assertEquals(listOf(higherId, lowerId), releases.map { it.first.releaseId })
    }

    @Test
    fun `release list distinguishes no stable release from missing or ambiguous canonical assets`() = runTest {
        val noStable = "[${releaseJson("1", "2025-01-01T00:00:00Z", draft = true)}]"
        val noCanonical = "[${releaseJson("1", "2025-01-01T00:00:00Z", assetName = "other.zip")}]"
        val duplicateCanonical = "[${releaseJson("1", "2025-01-01T00:00:00Z", duplicateCanonicalAsset = true)}]"
        val oversizedAsset = "[${releaseJson("1", "2025-01-01T00:00:00Z", assetSize = 5)}]"

        assertFailure(
            client(QueueTransport(response(200, noStable))).listStableReleaseAssets(coordinates),
            GitHubSourceFailure.NoStableRelease,
            "draft-only releases",
        )
        assertFailure(
            client(QueueTransport(response(200, noCanonical))).listStableReleaseAssets(coordinates),
            GitHubSourceFailure.NoCanonicalAsset,
            "missing canonical asset",
        )
        assertFailure(
            client(QueueTransport(response(200, duplicateCanonical))).listStableReleaseAssets(coordinates),
            GitHubSourceFailure.NoCanonicalAsset,
            "ambiguous canonical asset",
        )
        assertFailure(
            client(QueueTransport(response(200, oversizedAsset)), bounds(maxExactAssetBytes = 4))
                .listStableReleaseAssets(coordinates),
            GitHubSourceFailure.NoCanonicalAsset,
            "asset metadata beyond exact-byte cap",
        )
    }

    @Test
    fun `release decoding rejects malformed JSON and bounded metadata`() = runTest {
        assertFailure(
            client(QueueTransport(response(200, "[{\"id\":1"))).listStableReleaseAssets(coordinates),
            GitHubSourceFailure.MalformedResponse,
            "truncated releases JSON",
        )
        val json = "[${releaseJson("1", "2025-01-01T00:00:00Z")}]"
        assertFailure(
            client(QueueTransport(response(200, json)), bounds(maxMetadataResponseBytes = json.toByteArray().size.toLong() - 1))
                .listStableReleaseAssets(coordinates),
            GitHubSourceFailure.BoundsExceeded,
            "release metadata beyond configured byte bound",
        )
    }

    @Test
    fun `public Diagnostics v1_3_1 provenance resolves downloads and preserves exact bytes`() = runTest {
        val archive = fixture("diagnostics-channel/talkcan-channel-v1.3.1.zip")
        assertEquals(DIAGNOSTICS_SIZE, archive.size.toLong())
        assertEquals(DIAGNOSTICS_SHA256, sha256(archive))
        val transport = QueueTransport(
            response(200, repositoryJson(id = DIAGNOSTICS_REPOSITORY_ID, ownerId = OFFICIAL_OWNER_ID, ownerLogin = "talkcan")),
            response(200, "[${releaseJson(DIAGNOSTICS_RELEASE_ID, DIAGNOSTICS_PUBLISHED_AT, tag = "v1.3.1", assetId = DIAGNOSTICS_ASSET_ID, assetSize = DIAGNOSTICS_SIZE)}]"),
            response(200, archive, mapOf("Content-Length" to DIAGNOSTICS_SIZE.toString())),
        )
        val source = client(transport, bounds(maxExactAssetBytes = DIAGNOSTICS_SIZE))
        val repository = success(source.resolveRepository(DIAGNOSTICS_COORDINATES))
        assertEquals(DIAGNOSTICS_REPOSITORY_ID, repository.id.value)
        assertEquals(OFFICIAL_OWNER_ID, repository.owner.ownerId)
        val release = success(source.listStableReleaseAssets(DIAGNOSTICS_COORDINATES)).single()
        assertEquals(DIAGNOSTICS_RELEASE_ID, release.first.releaseId)
        assertEquals("v1.3.1", release.first.tag)
        assertEquals(DIAGNOSTICS_PUBLISHED_EPOCH, release.first.publishedAtEpochSeconds)
        assertEquals(DIAGNOSTICS_ASSET_ID, release.second.assetId)
        assertEquals(GitHubSourceConfiguration.CANONICAL_ASSET_NAME, release.second.name)
        assertEquals(DIAGNOSTICS_SIZE, release.second.size)
        val downloaded = ByteArrayOutputStream()
        assertEquals(Unit, success(source.downloadAsset(DIAGNOSTICS_COORDINATES, release.second, downloaded)))
        assertEquals(DIAGNOSTICS_SIZE, downloaded.size().toLong())
        assertEquals(DIAGNOSTICS_SHA256, sha256(downloaded.toByteArray()))
        assertTrue(archive.contentEquals(downloaded.toByteArray()))
        assertEquals(
            listOf(
                "https://api.github.com/repos/talkcan/diagnostics-channel",
                "https://api.github.com/repos/talkcan/diagnostics-channel/releases",
                "https://api.github.com/repos/talkcan/diagnostics-channel/releases/assets/$DIAGNOSTICS_ASSET_ID",
            ),
            transport.requests.map { it.url },
        )
    }

    @Test
    fun `public Debug v1_3_0 provenance resolves downloads and preserves exact bytes`() = runTest {
        val archive = fixture("debug-channel/talkcan-channel.zip")
        assertEquals(DEBUG_SIZE, archive.size.toLong())
        assertEquals(DEBUG_SHA256, sha256(archive))
        val transport = QueueTransport(
            response(200, repositoryJson(id = DEBUG_REPOSITORY_ID, ownerId = OFFICIAL_OWNER_ID, ownerLogin = "talkcan")),
            response(200, "[${releaseJson(DEBUG_RELEASE_ID, DEBUG_PUBLISHED_AT, tag = "v1.3.0", assetId = DEBUG_ASSET_ID, assetSize = DEBUG_SIZE)}]"),
            response(200, archive, mapOf("Content-Length" to DEBUG_SIZE.toString())),
        )
        val source = client(transport, bounds(maxExactAssetBytes = DEBUG_SIZE))
        val repository = success(source.resolveRepository(DEBUG_COORDINATES))
        assertEquals(DEBUG_REPOSITORY_ID, repository.id.value)
        assertEquals(OFFICIAL_OWNER_ID, repository.owner.ownerId)
        val release = success(source.listStableReleaseAssets(DEBUG_COORDINATES)).single()
        assertEquals(DEBUG_RELEASE_ID, release.first.releaseId)
        assertEquals("v1.3.0", release.first.tag)
        assertEquals(DEBUG_PUBLISHED_EPOCH, release.first.publishedAtEpochSeconds)
        assertEquals(DEBUG_ASSET_ID, release.second.assetId)
        assertEquals(GitHubSourceConfiguration.CANONICAL_ASSET_NAME, release.second.name)
        assertEquals(DEBUG_SIZE, release.second.size)
        val downloaded = ByteArrayOutputStream()
        assertEquals(Unit, success(source.downloadAsset(DEBUG_COORDINATES, release.second, downloaded)))
        assertEquals(DEBUG_SIZE, downloaded.size().toLong())
        assertEquals(DEBUG_SHA256, sha256(downloaded.toByteArray()))
        assertTrue(archive.contentEquals(downloaded.toByteArray()))
        assertEquals(
            listOf(
                "https://api.github.com/repos/talkcan/debug-channel",
                "https://api.github.com/repos/talkcan/debug-channel/releases",
                "https://api.github.com/repos/talkcan/debug-channel/releases/assets/$DEBUG_ASSET_ID",
            ),
            transport.requests.map { it.url },
        )
    }

    @Test
    fun `rate-limit exhaustion is surfaced while inaccessible HTTP failures remain distinct`() = runTest {
        val limited = GitHubRateLimit(limit = 60, remaining = 0, resetTimeEpochSeconds = 1_800_000_000)
        val cases = listOf(
            HttpFailureCase("403 exhausted", 403, mapOf("x-ratelimit-limit" to "60", "x-ratelimit-remaining" to "0", "x-ratelimit-reset" to "1800000000"), GitHubSourceFailure.RateLimitExhausted(limited)),
            HttpFailureCase("429 exhausted", 429, mapOf("x-ratelimit-limit" to "60", "x-ratelimit-remaining" to "0", "x-ratelimit-reset" to "1800000000"), GitHubSourceFailure.RateLimitExhausted(limited)),
            HttpFailureCase("403 not exhausted", 403, mapOf("x-ratelimit-limit" to "60", "x-ratelimit-remaining" to "1", "x-ratelimit-reset" to "1800000000"), GitHubSourceFailure.InaccessibleRepository),
            HttpFailureCase("401", 401, emptyMap(), GitHubSourceFailure.InaccessibleRepository),
        )
        cases.forEach { case ->
            assertFailure(
                client(QueueTransport(response(case.code, "", case.headers))).resolveRepository(coordinates),
                case.expected,
                case.name,
            )
        }
    }

    @Test
    fun `asset download streams exact bytes directly with anonymous binary policy`() = runTest {
        val bytes = "zip!".toByteArray()
        val transport = QueueTransport(response(200, bytes, mapOf("Content-Length" to bytes.size.toString())))
        val destination = ByteArrayOutputStream()

        assertEquals(Unit, success(client(transport, bounds(maxExactAssetBytes = bytes.size.toLong())).downloadAsset(coordinates, asset(bytes.size.toLong()), destination)))
        assertTrue(bytes.contentEquals(destination.toByteArray()))
        assertEquals(
            "https://api.github.com/repos/Owner/Repository/releases/assets/99",
            transport.requests.single().url,
        )
        assertEquals("application/octet-stream", transport.requests.single().headers["Accept"])
        assertFalse(
            "anonymous binary downloads must not send authorization",
            transport.requests.single().headers.keys.any { it.equals("Authorization", ignoreCase = true) },
        )
    }

    @Test
    fun `asset download rejects declared and streamed size disagreement`() = runTest {
        val declaredMismatch = QueueTransport(response(200, "data", mapOf("Content-Length" to "3")))
        assertFailure(
            client(declaredMismatch, bounds(maxExactAssetBytes = 4)).downloadAsset(coordinates, asset(4), ByteArrayOutputStream()),
            GitHubSourceFailure.BoundsExceeded,
            "Content-Length must match selected asset metadata",
        )

        val streamedMismatch = QueueTransport(response(200, "abc"))
        assertFailure(
            client(streamedMismatch, bounds(maxExactAssetBytes = 4)).downloadAsset(coordinates, asset(4), ByteArrayOutputStream()),
            GitHubSourceFailure.NetworkError,
            "stream must contain exactly the selected asset size",
        )
    }

    @Test
    fun `asset redirects reject downgrade missing location loops and excess hops`() = runTest {
        val initial = "https://api.github.com/repos/Owner/Repository/releases/assets/99"
        val cases = listOf(
            DownloadCase("protocol downgrade", listOf(response(302, "", mapOf("Location" to "http://downloads.example/asset"))), bounds(), GitHubSourceFailure.RedirectFailure),
            DownloadCase("missing location", listOf(response(302, "")), bounds(), GitHubSourceFailure.RedirectFailure),
            DownloadCase("redirect loop", listOf(response(302, "", mapOf("Location" to initial))), bounds(), GitHubSourceFailure.RedirectFailure),
            DownloadCase(
                "redirect cap",
                listOf(
                    response(302, "", mapOf("Location" to "https://downloads.example/first")),
                    response(302, "", mapOf("Location" to "https://downloads.example/second")),
                ),
                bounds(maxRedirects = 1),
                GitHubSourceFailure.RedirectFailure,
            ),
        )
        cases.forEach { case ->
            assertFailure(
                client(QueueTransport(*case.responses.toTypedArray()), case.bounds)
                    .downloadAsset(coordinates, asset(4), ByteArrayOutputStream()),
                case.expected,
                case.name,
            )
        }
    }

    @Test
    fun `HTTPS redirect retains direct stream semantics`() = runTest {
        val bytes = "data".toByteArray()
        val transport = QueueTransport(
            response(307, "", mapOf("Location" to "https://downloads.example/channel.zip")),
            response(200, bytes, mapOf("Content-Length" to "4")),
        )
        val destination = ByteArrayOutputStream()

        assertEquals(Unit, success(client(transport, bounds(maxRedirects = 1, maxExactAssetBytes = 4)).downloadAsset(coordinates, asset(4), destination)))
        assertTrue(bytes.contentEquals(destination.toByteArray()))
        assertEquals(
            listOf(
                "https://api.github.com/repos/Owner/Repository/releases/assets/99",
                "https://downloads.example/channel.zip",
            ),
            transport.requests.map { it.url },
        )
    }

    @Test
    fun `transport cancellation is surfaced as typed cancellation rather than a network error`() = runTest {
        val transport = ThrowingTransport(CancellationException("caller cancelled"))

        assertFailure(
            client(transport).resolveRepository(coordinates),
            GitHubSourceFailure.Cancelled,
            "transport cancellation",
        )
    }

    @Test
    fun `operation timeout is surfaced without entering transport`() = runTest {
        val transport = QueueTransport()
        val source = client(transport)
        source.timeoutRunner = { GitHubSourceOutcome.Failure(GitHubSourceFailure.Timeout) }

        assertFailure(
            source.resolveRepository(coordinates),
            GitHubSourceFailure.Timeout,
            "timeout runner",
        )
        assertTrue("timed-out operation must not start a transport request", transport.requests.isEmpty())
    }

    private fun fixture(path: String): ByteArray = requireNotNull(javaClass.classLoader?.getResourceAsStream(path)) {
        "Missing immutable public release fixture $path"
    }.use { it.readBytes() }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun client(transport: GitHubTransport, bounds: GitHubClientBounds = bounds()): RealGitHubPackageSourceClient =
        RealGitHubPackageSourceClient(transport, bounds)

    private fun bounds(
        maxUrlBytes: Int = 512,
        maxMetadataResponseBytes: Long = 8_192,
        maxReleaseCandidates: Int = 8,
        maxRedirects: Int = 2,
        maxExactAssetBytes: Long = 1_024,
    ): GitHubClientBounds = GitHubClientBounds(
        maxUrlBytes = maxUrlBytes,
        maxMetadataResponseBytes = maxMetadataResponseBytes,
        maxReleaseCandidates = maxReleaseCandidates,
        maxRedirects = maxRedirects,
        maxExactAssetBytes = maxExactAssetBytes,
        maxInspectionFiles = 8,
        operationDurationSeconds = 5,
        maxRetainedFailureDetailBytes = 128,
    )

    private fun repositoryJson(
        id: String = "123",
        ownerId: String = "123",
        ownerLogin: String = "owner",
        visibility: String = "public",
        archived: Boolean = false,
        disabled: Boolean = false,
    ): String = """{"id":$id,"full_name":"Owner/Repository","archived":$archived,"disabled":$disabled,"visibility":"$visibility","owner":{"id":$ownerId,"login":"$ownerLogin","type":"User"}}"""

    private fun releaseJson(
        id: String,
        publishedAt: String,
        tag: String = "release",
        assetId: String = "99",
        assetName: String = GitHubSourceConfiguration.CANONICAL_ASSET_NAME,
        assetSize: Long = 4,
        draft: Boolean = false,
        prerelease: Boolean = false,
        duplicateCanonicalAsset: Boolean = false,
    ): String {
        val firstAsset = assetJson(assetId, assetName, assetSize)
        val assets = if (duplicateCanonicalAsset) "$firstAsset,${assetJson("100", GitHubSourceConfiguration.CANONICAL_ASSET_NAME, assetSize)}" else firstAsset
        return """{"id":$id,"tag_name":"$tag","name":"display only","draft":$draft,"prerelease":$prerelease,"published_at":"$publishedAt","assets":[$assets]}"""
    }

    private fun assetJson(id: String, name: String, size: Long): String =
        """{"id":$id,"name":"$name","state":"uploaded","content_type":"application/zip","size":$size,"browser_download_url":"https://downloads.example/channel.zip"}"""

    private fun asset(size: Long): GitHubReleaseAsset = GitHubReleaseAsset(
        assetId = "99",
        name = GitHubSourceConfiguration.CANONICAL_ASSET_NAME,
        state = "uploaded",
        contentType = "application/zip",
        size = size,
        browserDownloadUrl = "https://downloads.example/channel.zip",
    )

    private fun <T> success(outcome: GitHubSourceOutcome<T>): T = when (outcome) {
        is GitHubSourceOutcome.Success -> outcome.value
        is GitHubSourceOutcome.Failure -> throw AssertionError("Expected success, got ${outcome.error}")
    }

    private fun assertFailure(outcome: GitHubSourceOutcome<*>, expected: GitHubSourceFailure, name: String) {
        assertEquals("$name must return $expected", expected, (outcome as? GitHubSourceOutcome.Failure)?.error)
    }

    private fun assertIllegalArgument(name: String, body: () -> Unit) {
        try {
            body()
            throw AssertionError("$name must reject an invalid domain value")
        } catch (_: IllegalArgumentException) {
            // Expected public constructor invariant.
        }
    }

    private data class RepositoryFailureCase(
        val name: String,
        val json: String,
        val expected: GitHubSourceFailure,
    )

    private data class HttpFailureCase(
        val name: String,
        val code: Int,
        val headers: Map<String, String>,
        val expected: GitHubSourceFailure,
    )

    private data class DownloadCase(
        val name: String,
        val responses: List<FakeResponse>,
        val bounds: GitHubClientBounds,
        val expected: GitHubSourceFailure,
    )

    private data class RecordedRequest(val url: String, val headers: Map<String, String>)

    private class QueueTransport(vararg scripted: FakeResponse) : GitHubTransport {
        private val responses = scripted.toMutableList()
        val requests = mutableListOf<RecordedRequest>()

        override fun executeGet(url: String, headers: Map<String, String>): GitHubHttpResponse {
            requests += RecordedRequest(url, headers.toMap())
            if (responses.isEmpty()) throw IOException("unexpected request to $url")
            return responses.removeAt(0)
        }
    }

    private class ThrowingTransport(private val failure: Exception) : GitHubTransport {
        override fun executeGet(url: String, headers: Map<String, String>): GitHubHttpResponse = throw failure
    }
    private class FakeResponse(
        override val code: Int,
        private val bytes: ByteArray,
        private val headers: Map<String, String>,
    ) : GitHubHttpResponse {
        private var closed = false

        override fun header(name: String): String? = headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

        override fun bodyStream(): InputStream {
            check(!closed) { "body requested after response closure" }
            return ByteArrayInputStream(bytes)
        }

        override fun close() {
            closed = true
        }
    }

    private companion object {
        private val coordinates = GitHubRepositoryCoordinates("Owner", "Repository")
        private val DIAGNOSTICS_COORDINATES = GitHubRepositoryCoordinates("talkcan", "diagnostics-channel")
        private val DEBUG_COORDINATES = GitHubRepositoryCoordinates("talkcan", "debug-channel")
        private const val OFFICIAL_OWNER_ID = "1224006"
        private const val DIAGNOSTICS_REPOSITORY_ID = "1305223892"
        private const val DIAGNOSTICS_RELEASE_ID = "359168468"
        private const val DIAGNOSTICS_ASSET_ID = "488185292"
        private const val DIAGNOSTICS_PUBLISHED_AT = "2026-07-24T08:22:32Z"
        private const val DIAGNOSTICS_PUBLISHED_EPOCH = 1784881352L
        private const val DIAGNOSTICS_SIZE = 5230L
        private const val DIAGNOSTICS_SHA256 = "41e5d8f5e5ad49e321ab4a48c2794e795934bd7d64af78ab9f6e982949444cd0"
        private const val DEBUG_REPOSITORY_ID = "1306065111"
        private const val DEBUG_RELEASE_ID = "359174403"
        private const val DEBUG_ASSET_ID = "488198107"
        private const val DEBUG_PUBLISHED_AT = "2026-07-24T08:36:00Z"
        private const val DEBUG_PUBLISHED_EPOCH = 1784882160L
        private const val DEBUG_SIZE = 7178L
        private const val DEBUG_SHA256 = "960dedc7bf3eb1bbb2d29f465acc049671c22593611726a6f66951a977cc5b10"

        private fun response(code: Int, body: String, headers: Map<String, String> = emptyMap()): FakeResponse =
            FakeResponse(code, body.toByteArray(), headers)

        private fun response(code: Int, body: ByteArray, headers: Map<String, String> = emptyMap()): FakeResponse =
            FakeResponse(code, body, headers)
    }
}
