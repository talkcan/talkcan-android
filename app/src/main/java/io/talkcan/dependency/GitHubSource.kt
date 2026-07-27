package io.talkcan.dependency

import io.talkcan.model.ChannelImplementationId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.CancellationException
import java.io.InputStream
import java.io.IOException
import java.io.OutputStream
import java.io.ByteArrayOutputStream
import java.net.URI
import java.time.Instant
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.CookieJar

/**
 * 1.5: Host-owned configuration boundary.
 */
public object GitHubSourceConfiguration {
    public const val CANONICAL_ASSET_NAME = "talkcan-channel.zip"
    public const val REST_API_VERSION = "2022-11-28"
    public const val OFFICIAL_PUBLISHER_ID = "1224006"
}

private fun isPositiveDecimal(value: String): Boolean {
    if (value.isEmpty()) return false
    if (value[0] == '0') return false
    return value.all { it in '0'..'9' }
}

/**
 * 2.1: Platform-neutral immutable domain values.
 */
public data class GitHubRepositoryOwner(
    val ownerId: String,
    val login: String,
    val type: String
) {
    init {
        require(isPositiveDecimal(ownerId)) { "Owner ID must be a positive canonical decimal string: $ownerId" }
        require(login.isNotBlank() && login.length <= 100) { "Owner login must be non-blank and <= 100 characters" }
        require(type.isNotBlank() && type.length <= 50) { "Owner type must be non-blank and <= 50 characters" }
    }
}

public data class GitHubResolvedRepository(
    val id: GitHubRepositoryIdentity,
    val fullName: String,
    val archived: Boolean,
    val disabled: Boolean,
    val visibility: String,
    val owner: GitHubRepositoryOwner
) {
    init {
        require(fullName.isNotBlank() && fullName.length <= 256) { "fullName must be non-blank and <= 256 characters" }
        require(visibility.isNotBlank() && visibility.length <= 64) { "visibility must be non-blank and <= 64 characters" }
    }
    val isPublic: Boolean get() = visibility.equals("public", ignoreCase = true)
}

public data class GitHubPublishedRelease(
    val releaseId: String,
    val tag: String,
    val name: String?,
    val isDraft: Boolean,
    val isPrerelease: Boolean,
    val publishedAtEpochSeconds: Long
) {
    init {
        require(isPositiveDecimal(releaseId)) { "Release ID must be a positive canonical decimal string: $releaseId" }
        require(tag.isNotBlank() && tag.length <= 100) { "Release tag must be non-blank and <= 100 characters" }
        require(publishedAtEpochSeconds > 0) { "Published timestamp must be positive: $publishedAtEpochSeconds" }
    }
}

public data class GitHubReleaseAsset(
    val assetId: String,
    val name: String,
    val state: String,
    val contentType: String,
    val size: Long,
    val browserDownloadUrl: String
) {
    init {
        require(isPositiveDecimal(assetId)) { "Asset ID must be a positive canonical decimal string: $assetId" }
        require(name.isNotBlank() && name.length <= 256) { "Asset name must be non-blank and <= 256 characters" }
        require(state.isNotBlank() && state.length <= 50) { "Asset state must be non-blank and <= 50 characters" }
        require(contentType.isNotBlank() && contentType.length <= 128) { "Asset content type must be non-blank and <= 128 characters" }
        require(size > 0) { "Asset size must be positive: $size" }
        require(browserDownloadUrl.isNotBlank() && browserDownloadUrl.length <= 2048) { "Browser download URL must be non-blank and <= 2048 characters" }
    }
}

public enum class GitHubPublisherTier {
    OFFICIAL,
    COMMUNITY
}

public data class GitHubCompatibleCandidate(
    val release: GitHubPublishedRelease,
    val asset: GitHubReleaseAsset,
    val digest: ArtifactDigest,
    val stagedFileToken: String
) {
    init {
        require(stagedFileToken.isNotBlank() && stagedFileToken.length <= 128) { "stagedFileToken must be non-blank and <= 128 characters" }
    }
}

public data class GitHubRateLimit(
    val limit: Int,
    val remaining: Int,
    val resetTimeEpochSeconds: Long
) {
    init {
        require(limit >= 0) { "limit must be non-negative" }
        require(remaining >= 0) { "remaining must be non-negative" }
        require(resetTimeEpochSeconds > 0) { "resetTimeEpochSeconds must be positive" }
    }
}


/**
 * 2.2: Sealed typed outcomes covering the full failure space.
 */
public sealed interface GitHubSourceOutcome<out T> {
    public data class Success<out T>(val value: T) : GitHubSourceOutcome<T>
    public data class Failure(val error: GitHubSourceFailure) : GitHubSourceOutcome<Nothing>
}

public sealed interface GitHubSourceFailure {
    public object MalformedUrl : GitHubSourceFailure
    public object UnsupportedHostPath : GitHubSourceFailure
    public object InaccessibleRepository : GitHubSourceFailure
    public object PrivateRepository : GitHubSourceFailure
    public object ArchivedRepository : GitHubSourceFailure
    public object DisabledRepository : GitHubSourceFailure
    public object MalformedResponse : GitHubSourceFailure
    public object BoundsExceeded : GitHubSourceFailure
    public object NoStableRelease : GitHubSourceFailure
    public object NoCanonicalAsset : GitHubSourceFailure
    public object IncompatiblePackage : GitHubSourceFailure
    public object InvalidPackage : GitHubSourceFailure
    public data class RateLimitExhausted(val rateLimit: GitHubRateLimit) : GitHubSourceFailure
    public object RedirectFailure : GitHubSourceFailure
    public object NetworkError : GitHubSourceFailure
    public object Timeout : GitHubSourceFailure
    public object Cancelled : GitHubSourceFailure
    public object StaleOperation : GitHubSourceFailure
    public object LifecycleClosed : GitHubSourceFailure
    public object StagingError : GitHubSourceFailure
    public object TrustRefused : GitHubSourceFailure
    public data class Format(val detail: io.talkcan.dependency.PackageFailure.FormatDetail) : GitHubSourceFailure
    public data class Identity(val detail: io.talkcan.dependency.PackageFailure.IdentityDetail) : GitHubSourceFailure
    public data class Compatibility(val detail: io.talkcan.dependency.PackageFailure.CompatibilityDetail) : GitHubSourceFailure
    public data class Integrity(val detail: io.talkcan.dependency.PackageFailure.IntegrityDetail) : GitHubSourceFailure
    public data class Storage(val detail: io.talkcan.dependency.PackageFailure.StorageDetail) : GitHubSourceFailure
    public data class Mutation(val detail: io.talkcan.dependency.PackageFailure.MutationDetail) : GitHubSourceFailure
    public data class Rollback(val detail: io.talkcan.dependency.PackageFailure.RollbackDetail) : GitHubSourceFailure
}

/**
 * 2.3: Finite host-configured bounds.
 */
public data class GitHubClientBounds(
    val maxUrlBytes: Int,
    val maxMetadataResponseBytes: Long,
    val maxReleaseCandidates: Int,
    val maxRedirects: Int,
    val maxExactAssetBytes: Long,
    val maxInspectionFiles: Int,
    val operationDurationSeconds: Long,
    val maxRetainedFailureDetailBytes: Int
) {
    init {
        require(maxUrlBytes > 0) { "maxUrlBytes must be positive" }
        require(maxMetadataResponseBytes > 0) { "maxMetadataResponseBytes must be positive" }
        require(maxReleaseCandidates > 0) { "maxReleaseCandidates must be positive" }
        require(maxRedirects >= 0) { "maxRedirects must be non-negative" }
        require(maxExactAssetBytes > 0) { "maxExactAssetBytes must be positive" }
        require(maxInspectionFiles > 0) { "maxInspectionFiles must be positive" }
        require(operationDurationSeconds > 0) { "operationDurationSeconds must be positive" }
        require(maxRetainedFailureDetailBytes > 0) { "maxRetainedFailureDetailBytes must be positive" }
    }

    public companion object {
        public val DEFAULT = GitHubClientBounds(
            maxUrlBytes = 2048,
            maxMetadataResponseBytes = 512 * 1024L, // 512 KB
            maxReleaseCandidates = 100,
            maxRedirects = 3,
            maxExactAssetBytes = 4 * 1024 * 1024L, // 4 MB
            maxInspectionFiles = 10,
            operationDurationSeconds = 60L,
            maxRetainedFailureDetailBytes = 1024
        )
    }
}

/**
 * 2.4: Strict canonical repository URL parser.
 */
public object GitHubUrlParser {
    public fun parse(url: String, bounds: GitHubClientBounds = GitHubClientBounds.DEFAULT): GitHubSourceOutcome<GitHubRepositoryCoordinates> {
        val urlBytes = try {
            url.toByteArray(Charsets.UTF_8)
        } catch (e: Exception) {
            return GitHubSourceOutcome.Failure(GitHubSourceFailure.MalformedUrl)
        }
        if (urlBytes.size > bounds.maxUrlBytes) {
            return GitHubSourceOutcome.Failure(GitHubSourceFailure.BoundsExceeded)
        }

        // Reject trailing slash
        if (url.endsWith("/")) {
            return GitHubSourceOutcome.Failure(GitHubSourceFailure.MalformedUrl)
        }

        // Reject percent encoding
        if (url.contains("%")) {
            return GitHubSourceOutcome.Failure(GitHubSourceFailure.MalformedUrl)
        }

        // Reject Unicode / non-ASCII
        if (url.any { it.code !in 32..126 }) {
            return GitHubSourceOutcome.Failure(GitHubSourceFailure.MalformedUrl)
        }

        try {
            val uri = URI(url)
            if (uri.scheme != "https") {
                return GitHubSourceOutcome.Failure(GitHubSourceFailure.MalformedUrl)
            }
            if (uri.userInfo != null) {
                return GitHubSourceOutcome.Failure(GitHubSourceFailure.MalformedUrl)
            }
            if (uri.query != null) {
                return GitHubSourceOutcome.Failure(GitHubSourceFailure.MalformedUrl)
            }
            if (uri.fragment != null) {
                return GitHubSourceOutcome.Failure(GitHubSourceFailure.MalformedUrl)
            }
            if (uri.port != -1) {
                return GitHubSourceOutcome.Failure(GitHubSourceFailure.MalformedUrl)
            }
            val host = uri.host ?: return GitHubSourceOutcome.Failure(GitHubSourceFailure.MalformedUrl)
            if (!host.equals("github.com", ignoreCase = true)) {
                return GitHubSourceOutcome.Failure(GitHubSourceFailure.UnsupportedHostPath)
            }
            val path = uri.path ?: return GitHubSourceOutcome.Failure(GitHubSourceFailure.MalformedUrl)

            // Reject empty/dot/traversal and noncanonical GitHub segment chars
            val rawSegments = path.split("/")
            if (rawSegments.size != 3 || rawSegments[0].isNotEmpty()) {
                return GitHubSourceOutcome.Failure(GitHubSourceFailure.MalformedUrl)
            }

            val owner = rawSegments[1]
            val repo = rawSegments[2]

            if (owner.isEmpty() || repo.isEmpty()) {
                return GitHubSourceOutcome.Failure(GitHubSourceFailure.MalformedUrl)
            }

            val ownerRegex = Regex("^[a-zA-Z0-9](?:[a-zA-Z0-9-]*[a-zA-Z0-9])?$")
            val repoRegex = Regex("^[a-zA-Z0-9._-]+$")

            if (!ownerRegex.matches(owner) || !repoRegex.matches(repo)) {
                return GitHubSourceOutcome.Failure(GitHubSourceFailure.MalformedUrl)
            }

            if (owner == "." || owner == ".." || repo == "." || repo == "..") {
                return GitHubSourceOutcome.Failure(GitHubSourceFailure.MalformedUrl)
            }

            if (repo.endsWith(".git", ignoreCase = true)) {
                return GitHubSourceOutcome.Failure(GitHubSourceFailure.MalformedUrl)
            }

            return GitHubSourceOutcome.Success(
                GitHubRepositoryCoordinates(owner, repo)
            )
        } catch (e: Exception) {
            return GitHubSourceOutcome.Failure(GitHubSourceFailure.MalformedUrl)
        }
    }
}

/**
 * 3.1: Small transport abstraction.
 */
public interface GitHubHttpResponse : AutoCloseable {
    val code: Int
    fun header(name: String): String?
    fun bodyStream(): InputStream
}

public interface GitHubTransport {
    @Throws(IOException::class)
    fun executeGet(url: String, headers: Map<String, String>): GitHubHttpResponse
}

public class OkHttpGitHubTransport(client: OkHttpClient) : GitHubTransport {
    // Explicitly configure transport to not follow redirects, reject cookies, and never send auth.
    private val innerClient = client.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .cookieJar(CookieJar.NO_COOKIES)
        .build()

    override fun executeGet(url: String, headers: Map<String, String>): GitHubHttpResponse {
        val requestBuilder = Request.Builder()
            .url(url)
            .get()
        headers.forEach { (name, value) ->
            // Never send auth
            if (!name.equals("Authorization", ignoreCase = true)) {
                requestBuilder.header(name, value)
            }
        }
        val call = innerClient.newCall(requestBuilder.build())
        val response = call.execute()
        return OkHttpGitHubResponse(response)
    }
}

private class OkHttpGitHubResponse(private val response: Response) : GitHubHttpResponse {
    override val code: Int get() = response.code

    override fun header(name: String): String? {
        return response.header(name)
    }

    override fun bodyStream(): InputStream {
        val body = response.body ?: throw IOException("Empty response body")
        return body.byteStream()
    }

    override fun close() {
        response.close()
    }
}

/**
 * 3.2 - 3.9: Bounded Anonymous GitHub REST Client.
 */
public interface GitHubPackageSourceClient {
    public suspend fun resolveRepository(
        coordinates: GitHubRepositoryCoordinates
    ): GitHubSourceOutcome<GitHubResolvedRepository>

    public suspend fun listStableReleaseAssets(
        coordinates: GitHubRepositoryCoordinates
    ): GitHubSourceOutcome<List<Pair<GitHubPublishedRelease, GitHubReleaseAsset>>>

    public suspend fun downloadAsset(
        coordinates: GitHubRepositoryCoordinates,
        asset: GitHubReleaseAsset,
        destination: OutputStream
    ): GitHubSourceOutcome<Unit>
}

private fun Any.toDecimalStringFromJson(): String {
    return when (this) {
        is Number -> {
            val str = this.toString()
            // Double values from JSON (e.g. "1.0") need the fractional suffix dropped.
            // Int/Long.toString() is exact — never strip trailing digits.
            if (this is Double && str.endsWith(".0")) {
                str.substring(0, str.length - 2)
            } else {
                str
            }
        }
        is String -> this
        else -> toString()
    }
}

public class RealGitHubPackageSourceClient(
    private val transport: GitHubTransport,
    private val bounds: GitHubClientBounds = GitHubClientBounds.DEFAULT
) : GitHubPackageSourceClient {

    private class BoundsExceededException(message: String) : IOException(message)

    /**
     * Internal timeout seam for deterministic test control.
     * Production defaults to withTimeout(bounds.operationDurationSeconds * 1000).
     * Tests replace this with runBlocking/suspend { … } or TestDispatcher-based launch.
     */
    @Volatile
    internal var timeoutRunner: suspend ((suspend () -> GitHubSourceOutcome<Any?>)) -> GitHubSourceOutcome<Any?> =
        { block ->
            try {
                withTimeout(bounds.operationDurationSeconds * 1000) { block() }
            } catch (e: TimeoutCancellationException) {
                GitHubSourceOutcome.Failure(GitHubSourceFailure.Timeout)
            }
        }

    private fun extractRateLimit(response: GitHubHttpResponse): GitHubRateLimit? {
        val limitStr = response.header("x-ratelimit-limit") ?: return null
        val remainingStr = response.header("x-ratelimit-remaining") ?: return null
        val resetStr = response.header("x-ratelimit-reset") ?: return null
        val limit = limitStr.toIntOrNull() ?: return null
        val remaining = remainingStr.toIntOrNull() ?: return null
        val reset = resetStr.toLongOrNull() ?: return null
        return try {
            GitHubRateLimit(limit, remaining, reset)
        } catch (e: Exception) {
            null
        }
    }

    private fun handleResponseFailure(response: GitHubHttpResponse): GitHubSourceOutcome.Failure {
        val rateLimit = extractRateLimit(response)
        if (response.code == 403 || response.code == 429) {
            if (rateLimit != null && rateLimit.remaining == 0) {
                return GitHubSourceOutcome.Failure(GitHubSourceFailure.RateLimitExhausted(rateLimit))
            }
        }
        if (response.code == 404 || response.code == 401 || response.code == 403) {
            return GitHubSourceOutcome.Failure(GitHubSourceFailure.InaccessibleRepository)
        }
        return GitHubSourceOutcome.Failure(GitHubSourceFailure.NetworkError)
    }

    private fun readStreamToString(stream: InputStream, limit: Long): String {
        val bos = ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        var totalRead = 0L
        while (true) {
            val read = stream.read(buffer)
            if (read == -1) break
            totalRead += read
            if (totalRead > limit) {
                throw BoundsExceededException("Metadata response exceeded size limit of $limit bytes")
            }
            bos.write(buffer, 0, read)
        }
        return bos.toString("UTF-8")
    }

    override suspend fun resolveRepository(
        coordinates: GitHubRepositoryCoordinates
    ): GitHubSourceOutcome<GitHubResolvedRepository> = withContext(Dispatchers.IO) {
        try {
            @Suppress("UNCHECKED_CAST")
            val result = timeoutRunner {
                val url = "https://api.github.com/repos/${coordinates.owner}/${coordinates.repository}"
                val responseHeaders = mapOf(
                    "Accept" to "application/vnd.github+json",
                    "X-GitHub-Api-Version" to GitHubSourceConfiguration.REST_API_VERSION,
                    "User-Agent" to "Talkcan/0.7.0"
                )

                val response = try {
                    transport.executeGet(url, responseHeaders)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    return@timeoutRunner GitHubSourceOutcome.Failure(GitHubSourceFailure.NetworkError)
                }

                response.use { resp ->
                    if (resp.code != 200) {
                        return@timeoutRunner handleResponseFailure(resp)
                    }

                    val json = try {
                        readStreamToString(resp.bodyStream(), bounds.maxMetadataResponseBytes)
                    } catch (e: BoundsExceededException) {
                        return@timeoutRunner GitHubSourceOutcome.Failure(GitHubSourceFailure.BoundsExceeded)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        return@timeoutRunner GitHubSourceOutcome.Failure(GitHubSourceFailure.NetworkError)
                    }

                    val parsed = try {
                        PackageValidator.StrictJsonParser(json).parse()
                    } catch (e: Exception) {
                        return@timeoutRunner GitHubSourceOutcome.Failure(GitHubSourceFailure.MalformedResponse)
                    }

                    val idVal = parsed["id"] ?: return@timeoutRunner GitHubSourceOutcome.Failure(GitHubSourceFailure.MalformedResponse)
                    val idStr = idVal.toDecimalStringFromJson()
                    val id = try {
                        GitHubRepositoryIdentity(idStr)
                    } catch (e: Exception) {
                        return@timeoutRunner GitHubSourceOutcome.Failure(GitHubSourceFailure.MalformedResponse)
                    }

                    val fullName = parsed["full_name"] as? String ?: return@timeoutRunner GitHubSourceOutcome.Failure(GitHubSourceFailure.MalformedResponse)

                    val archived = parsed["archived"] as? Boolean ?: return@timeoutRunner GitHubSourceOutcome.Failure(GitHubSourceFailure.MalformedResponse)
                    val disabled = parsed["disabled"] as? Boolean ?: return@timeoutRunner GitHubSourceOutcome.Failure(GitHubSourceFailure.MalformedResponse)
                    val visibility = parsed["visibility"] as? String ?: return@timeoutRunner GitHubSourceOutcome.Failure(GitHubSourceFailure.MalformedResponse)

                    val ownerMap = parsed["owner"] as? Map<*, *> ?: return@timeoutRunner GitHubSourceOutcome.Failure(GitHubSourceFailure.MalformedResponse)
                    val ownerIdVal = ownerMap["id"] ?: return@timeoutRunner GitHubSourceOutcome.Failure(GitHubSourceFailure.MalformedResponse)
                    val ownerIdStr = ownerIdVal.toDecimalStringFromJson()
                    val ownerLogin = ownerMap["login"] as? String ?: return@timeoutRunner GitHubSourceOutcome.Failure(GitHubSourceFailure.MalformedResponse)
                    val ownerType = ownerMap["type"] as? String ?: return@timeoutRunner GitHubSourceOutcome.Failure(GitHubSourceFailure.MalformedResponse)

                    if (!visibility.equals("public", ignoreCase = true)) {
                        return@timeoutRunner GitHubSourceOutcome.Failure(GitHubSourceFailure.PrivateRepository)
                    }
                    if (archived) {
                        return@timeoutRunner GitHubSourceOutcome.Failure(GitHubSourceFailure.ArchivedRepository)
                    }
                    if (disabled) {
                        return@timeoutRunner GitHubSourceOutcome.Failure(GitHubSourceFailure.DisabledRepository)
                    }

                    val owner = try {
                        GitHubRepositoryOwner(ownerIdStr, ownerLogin, ownerType)
                    } catch (e: Exception) {
                        return@timeoutRunner GitHubSourceOutcome.Failure(GitHubSourceFailure.MalformedResponse)
                    }

                    val repo = GitHubResolvedRepository(id, fullName, archived, disabled, visibility, owner)
                    GitHubSourceOutcome.Success(repo)
                }
            } as GitHubSourceOutcome<GitHubResolvedRepository>
            result
        } catch (e: CancellationException) {
            GitHubSourceOutcome.Failure(GitHubSourceFailure.Cancelled)
        } catch (e: Exception) {
            GitHubSourceOutcome.Failure(GitHubSourceFailure.NetworkError)
        }
    }

    override suspend fun listStableReleaseAssets(
        coordinates: GitHubRepositoryCoordinates
    ): GitHubSourceOutcome<List<Pair<GitHubPublishedRelease, GitHubReleaseAsset>>> = withContext(Dispatchers.IO) {
        try {
            @Suppress("UNCHECKED_CAST")
            val result = timeoutRunner {
                val url = "https://api.github.com/repos/${coordinates.owner}/${coordinates.repository}/releases"
                val responseHeaders = mapOf(
                    "Accept" to "application/vnd.github+json",
                    "X-GitHub-Api-Version" to GitHubSourceConfiguration.REST_API_VERSION,
                    "User-Agent" to "Talkcan/0.7.0"
                )

                val response = try {
                    transport.executeGet(url, responseHeaders)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    return@timeoutRunner GitHubSourceOutcome.Failure(GitHubSourceFailure.NetworkError)
                }

                response.use { resp ->
                    if (resp.code != 200) {
                        return@timeoutRunner handleResponseFailure(resp)
                    }

                    val json = try {
                        readStreamToString(resp.bodyStream(), bounds.maxMetadataResponseBytes)
                    } catch (e: BoundsExceededException) {
                        return@timeoutRunner GitHubSourceOutcome.Failure(GitHubSourceFailure.BoundsExceeded)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        return@timeoutRunner GitHubSourceOutcome.Failure(GitHubSourceFailure.NetworkError)
                    }

                    val wrappedJson = "{\"releases\":$json}"
                    val parsed = try {
                        PackageValidator.StrictJsonParser(wrappedJson).parse()
                    } catch (e: Exception) {
                        return@timeoutRunner GitHubSourceOutcome.Failure(GitHubSourceFailure.MalformedResponse)
                    }

                    val releasesList = parsed["releases"] as? List<*> ?: return@timeoutRunner GitHubSourceOutcome.Failure(GitHubSourceFailure.MalformedResponse)

                    var hasStableRelease = false
                    for (releaseItem in releasesList) {
                        val releaseMap = releaseItem as? Map<*, *> ?: continue
                        val draft = releaseMap["draft"] as? Boolean ?: true
                        val prerelease = releaseMap["prerelease"] as? Boolean ?: true
                        if (!draft && !prerelease) {
                            hasStableRelease = true
                            break
                        }
                    }

                    if (!hasStableRelease) {
                        return@timeoutRunner GitHubSourceOutcome.Failure(GitHubSourceFailure.NoStableRelease)
                    }

                    val eligibleCandidates = mutableListOf<Pair<GitHubPublishedRelease, GitHubReleaseAsset>>()

                    for (releaseItem in releasesList) {
                        if (eligibleCandidates.size >= bounds.maxReleaseCandidates) {
                            break
                        }
                        val releaseMap = releaseItem as? Map<*, *> ?: continue
                        val draft = releaseMap["draft"] as? Boolean ?: true
                        val prerelease = releaseMap["prerelease"] as? Boolean ?: true
                        if (draft || prerelease) {
                            continue
                        }

                        val idVal = releaseMap["id"] ?: continue
                        val idStr = idVal.toDecimalStringFromJson()
                        if (!isPositiveDecimal(idStr)) {
                            continue
                        }

                        val tagName = releaseMap["tag_name"] as? String ?: continue
                        if (tagName.isNotBlank()) {
                            val nameStr = releaseMap["name"] as? String
                            val publishedAtStr = releaseMap["published_at"] as? String ?: continue
                            if (publishedAtStr.isNotBlank()) {
                                val publishedAtEpoch = try {
                                    Instant.parse(publishedAtStr).epochSecond
                                } catch (e: Exception) {
                                    continue
                                }

                                val assetsList = releaseMap["assets"] as? List<*> ?: continue
                                val matchingAssets = mutableListOf<GitHubReleaseAsset>()

                                for (assetItem in assetsList) {
                                    val assetMap = assetItem as? Map<*, *> ?: continue
                                    val assetName = assetMap["name"] as? String ?: continue
                                    if (assetName != GitHubSourceConfiguration.CANONICAL_ASSET_NAME) {
                                        continue
                                    }
                                    val assetState = assetMap["state"] as? String ?: continue
                                    if (assetState != "uploaded") {
                                        continue
                                    }
                                    val assetIdVal = assetMap["id"] ?: continue
                                    val assetIdStr = assetIdVal.toDecimalStringFromJson()
                                    if (!isPositiveDecimal(assetIdStr)) {
                                        continue
                                    }
                                    val assetContentType = assetMap["content_type"] as? String ?: continue
                                    val assetSize = (assetMap["size"] as? Number)?.toLong() ?: continue
                                    if (assetSize <= 0 || assetSize > bounds.maxExactAssetBytes) {
                                        continue
                                    }
                                    val browserDownloadUrl = assetMap["browser_download_url"] as? String ?: continue

                                    val assetInstance = try {
                                        GitHubReleaseAsset(
                                            assetId = assetIdStr,
                                            name = assetName,
                                            state = assetState,
                                            contentType = assetContentType,
                                            size = assetSize,
                                            browserDownloadUrl = browserDownloadUrl
                                        )
                                    } catch (e: Exception) {
                                        continue
                                    }
                                    matchingAssets.add(assetInstance)
                                }

                                if (matchingAssets.size == 1) {
                                    val release = try {
                                        GitHubPublishedRelease(
                                            releaseId = idStr,
                                            tag = tagName,
                                            name = nameStr,
                                            isDraft = draft,
                                            isPrerelease = prerelease,
                                            publishedAtEpochSeconds = publishedAtEpoch
                                        )
                                    } catch (e: Exception) {
                                        continue
                                    }
                                    eligibleCandidates.add(Pair(release, matchingAssets[0]))
                                }
                            }
                        }
                    }

                    if (eligibleCandidates.isEmpty()) {
                        return@timeoutRunner GitHubSourceOutcome.Failure(GitHubSourceFailure.NoCanonicalAsset)
                    }

                    val sorted = eligibleCandidates.sortedWith { a, b ->
                        val timeA = a.first.publishedAtEpochSeconds
                        val timeB = b.first.publishedAtEpochSeconds
                        if (timeA != timeB) {
                            timeB.compareTo(timeA)
                        } else {
                            val idA = a.first.releaseId
                            val idB = b.first.releaseId
                            // Durable decimal-string ordering without Long conversion
                            if (idA.length != idB.length) {
                                idB.length.compareTo(idA.length)
                            } else {
                                idB.compareTo(idA)
                            }
                        }
                    }

                    GitHubSourceOutcome.Success(sorted)
                }
            } as GitHubSourceOutcome<List<Pair<GitHubPublishedRelease, GitHubReleaseAsset>>>
            result
        } catch (e: CancellationException) {
            GitHubSourceOutcome.Failure(GitHubSourceFailure.Cancelled)
        } catch (e: Exception) {
            GitHubSourceOutcome.Failure(GitHubSourceFailure.NetworkError)
        }
    }

    override suspend fun downloadAsset(
        coordinates: GitHubRepositoryCoordinates,
        asset: GitHubReleaseAsset,
        destination: OutputStream
    ): GitHubSourceOutcome<Unit> = withContext(Dispatchers.IO) {
        try {
            @Suppress("UNCHECKED_CAST")
            val result = timeoutRunner {
                var currentUrl = "https://api.github.com/repos/${coordinates.owner}/${coordinates.repository}/releases/assets/${asset.assetId}"
                var redirectCount = 0
                val visitedUrls = mutableSetOf<String>()
                visitedUrls.add(currentUrl)

                while (true) {
                    val headers = mapOf(
                        "Accept" to "application/octet-stream",
                        "X-GitHub-Api-Version" to GitHubSourceConfiguration.REST_API_VERSION,
                        "User-Agent" to "Talkcan/0.7.0"
                    )

                    val response = try {
                        transport.executeGet(currentUrl, headers)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        return@timeoutRunner GitHubSourceOutcome.Failure(GitHubSourceFailure.NetworkError)
                    }

                    val code = response.code
                    if (code == 200) {
                        val contentLength = response.header("Content-Length")?.toLongOrNull()
                        if (contentLength != null && (contentLength > bounds.maxExactAssetBytes || contentLength != asset.size)) {
                            response.close()
                            return@timeoutRunner GitHubSourceOutcome.Failure(GitHubSourceFailure.BoundsExceeded)
                        }

                        try {
                            response.bodyStream().use { input ->
                                val buffer = ByteArray(8192)
                                var totalBytesWritten = 0L
                                while (true) {
                                    val read = input.read(buffer)
                                    if (read == -1) break
                                    totalBytesWritten += read
                                    if (totalBytesWritten > bounds.maxExactAssetBytes) {
                                        return@timeoutRunner GitHubSourceOutcome.Failure(GitHubSourceFailure.BoundsExceeded)
                                    }
                                    destination.write(buffer, 0, read)
                                }
                                if (totalBytesWritten != asset.size) {
                                    return@timeoutRunner GitHubSourceOutcome.Failure(GitHubSourceFailure.NetworkError)
                                }
                            }
                            response.close()
                            return@timeoutRunner GitHubSourceOutcome.Success(Unit)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            response.close()
                            return@timeoutRunner GitHubSourceOutcome.Failure(GitHubSourceFailure.NetworkError)
                        }
                    } else if (code == 301 || code == 302 || code == 307 || code == 308) {
                        val location = response.header("Location")
                        response.close()

                        if (location == null || !location.startsWith("https://")) {
                            return@timeoutRunner GitHubSourceOutcome.Failure(GitHubSourceFailure.RedirectFailure)
                        }

                        redirectCount++
                        if (redirectCount > bounds.maxRedirects) {
                            return@timeoutRunner GitHubSourceOutcome.Failure(GitHubSourceFailure.RedirectFailure)
                        }

                        if (!visitedUrls.add(location)) {
                            return@timeoutRunner GitHubSourceOutcome.Failure(GitHubSourceFailure.RedirectFailure)
                        }

                        currentUrl = location
                    } else {
                        val failure = handleResponseFailure(response)
                        response.close()
                        return@timeoutRunner failure
                    }
                }
                @Suppress("UNREACHABLE_CODE")
                GitHubSourceOutcome.Failure(GitHubSourceFailure.NetworkError)
            } as GitHubSourceOutcome<Unit>
            result
        } catch (e: CancellationException) {
            GitHubSourceOutcome.Failure(GitHubSourceFailure.Cancelled)
        } catch (e: Exception) {
            GitHubSourceOutcome.Failure(GitHubSourceFailure.NetworkError)
        }
    }
}
