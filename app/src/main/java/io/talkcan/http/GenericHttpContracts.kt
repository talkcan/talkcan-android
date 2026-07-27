package io.talkcan.http

import java.nio.charset.StandardCharsets

public enum class GenericHttpMethod {
    GET,
    HEAD,
    POST,
    PUT,
    PATCH,
    DELETE,
}

public data class GenericHttpRequest(
    val method: GenericHttpMethod,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val body: String? = null,
    val timeoutMillis: Long,
)

public data class GenericHttpResponse(
    val status: Int,
    val headers: Map<String, String>,
    val body: String,
)

public sealed interface GenericHttpFailure {
    public data class InvalidArgument(val field: String) : GenericHttpFailure
    public data class InvalidValue(val field: String) : GenericHttpFailure
    public data object TooLarge : GenericHttpFailure
    public data object Busy : GenericHttpFailure
    public data object Timeout : GenericHttpFailure
    public data object Cancelled : GenericHttpFailure
    public data object TransportUnavailable : GenericHttpFailure
}

public sealed interface GenericHttpResult {
    public data class Success(val response: GenericHttpResponse) : GenericHttpResult
    public data class Failure(val failure: GenericHttpFailure) : GenericHttpResult
}

public data class GenericHttpBounds(
    val maxUrlBytes: Int = 4 * 1024,
    val maxHeaderCount: Int = 64,
    val maxHeaderNameBytes: Int = 256,
    val maxHeaderValueBytes: Int = 8 * 1024,
    val maxRequestHeaderBytes: Int = 32 * 1024,
    val maxRequestBodyBytes: Int = 1024 * 1024,
    val maxResponseHeaderBytes: Int = 64 * 1024,
    val maxResponseBodyBytes: Int = 4 * 1024 * 1024,
    val maxRedirects: Int = 5,
    val minTimeoutMillis: Long = 100,
    val maxTimeoutMillis: Long = 120_000,
) {
    init {
        require(maxUrlBytes > 0)
        require(maxHeaderCount > 0)
        require(maxHeaderNameBytes > 0)
        require(maxHeaderValueBytes > 0)
        require(maxRequestHeaderBytes >= maxHeaderNameBytes + maxHeaderValueBytes)
        require(maxRequestBodyBytes > 0)
        require(maxResponseHeaderBytes > 0)
        require(maxResponseBodyBytes > 0)
        require(maxRedirects >= 0)
        require(minTimeoutMillis > 0)
        require(maxTimeoutMillis >= minTimeoutMillis)
    }
}

internal fun String.utf8Size(): Int = toByteArray(StandardCharsets.UTF_8).size

public fun interface GenericHttpTransport {
    public suspend fun request(request: GenericHttpRequest): GenericHttpResult
}
