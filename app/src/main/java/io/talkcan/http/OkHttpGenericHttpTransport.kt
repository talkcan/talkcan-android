package io.talkcan.http

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.CharacterCodingException
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

public class OkHttpGenericHttpTransport internal constructor(
    client: OkHttpClient,
    private val bounds: GenericHttpBounds,
) : GenericHttpTransport {
    public constructor(bounds: GenericHttpBounds = GenericHttpBounds()) :
        this(OkHttpClient.Builder().build(), bounds)

    private val client: OkHttpClient = client.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        // No client-level read timeout: the per-call deadline (call.timeout() from
        // request.timeoutMillis, clamped by GenericHttpBounds) is the sole authority, so
        // slow or streaming responses (e.g. LLM completions) are not aborted mid-read.
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    override suspend fun request(request: GenericHttpRequest): GenericHttpResult {
        val initial = validateRequest(request)
        if (initial is ValidatedRequest.Failure) {
            return GenericHttpResult.Failure(initial.failure)
        }
        initial as ValidatedRequest.Success

        var method = request.method
        var url = initial.url
        var headers = initial.headers
        var body = initial.body
        val visited = LinkedHashSet<String>(bounds.maxRedirects + 1)

        repeat(bounds.maxRedirects + 1) { redirectIndex ->
            if (!visited.add(url.toString())) {
                return GenericHttpResult.Failure(GenericHttpFailure.TransportUnavailable)
            }
            val step = execute(method, url, headers, body, request.timeoutMillis)
            when (step) {
                is TransportStep.Failure -> return GenericHttpResult.Failure(step.failure)
                is TransportStep.ResponseValue -> {
                    val redirect = redirectTarget(step.response, url)
                    if (redirect == null) {
                        return admitResponse(step.response, step.call)
                    }
                    if (redirectIndex == bounds.maxRedirects) {
                        step.response.close()
                        return GenericHttpResult.Failure(GenericHttpFailure.TransportUnavailable)
                    }
                    val target = normalizeHttpsUrl(redirect)
                    if (target == null ||
                        target.toString().utf8Size() > bounds.maxUrlBytes ||
                        target.toString() in visited
                    ) {
                        step.response.close()
                        return GenericHttpResult.Failure(GenericHttpFailure.TransportUnavailable)
                    }
                    if (!sameOrigin(url, target)) {
                        headers = headers.filterKeys { name ->
                            name.lowercase(Locale.ROOT) !in SENSITIVE_HEADERS
                        }
                    }
                    if (step.response.code == 303 ||
                        ((step.response.code == 301 || step.response.code == 302) && method == GenericHttpMethod.POST)
                    ) {
                        method = GenericHttpMethod.GET
                        body = null
                        headers = headers.filterKeys { name ->
                            val normalized = name.lowercase(Locale.ROOT)
                            normalized != "content-type" && normalized != "content-length"
                        }
                    }
                    step.response.close()
                    url = target
                }
            }
        }
        return GenericHttpResult.Failure(GenericHttpFailure.TransportUnavailable)
    }

    private fun validateRequest(request: GenericHttpRequest): ValidatedRequest {
        if (request.timeoutMillis !in bounds.minTimeoutMillis..bounds.maxTimeoutMillis) {
            return ValidatedRequest.Failure(GenericHttpFailure.InvalidArgument("timeout_ms"))
        }
        val urlBytes = encodeUtf8(request.url)
            ?: return ValidatedRequest.Failure(GenericHttpFailure.InvalidValue("url"))
        if (urlBytes.size > bounds.maxUrlBytes) {
            return ValidatedRequest.Failure(GenericHttpFailure.TooLarge)
        }
        val url = normalizeHttpsUrl(request.url)
            ?: return ValidatedRequest.Failure(GenericHttpFailure.InvalidArgument("url"))
        if (request.headers.size > bounds.maxHeaderCount) {
            return ValidatedRequest.Failure(GenericHttpFailure.TooLarge)
        }
        var aggregateHeaderBytes = 0
        val headersBuilder = Headers.Builder()
        for ((name, value) in request.headers) {
            val nameBytes = encodeUtf8(name)
                ?: return ValidatedRequest.Failure(GenericHttpFailure.InvalidValue("headers"))
            val valueBytes = encodeUtf8(value)
                ?: return ValidatedRequest.Failure(GenericHttpFailure.InvalidValue("headers"))
            val normalizedName = name.lowercase(Locale.ROOT)
            if (nameBytes.size > bounds.maxHeaderNameBytes ||
                valueBytes.size > bounds.maxHeaderValueBytes
            ) {
                return ValidatedRequest.Failure(GenericHttpFailure.TooLarge)
            }
            aggregateHeaderBytes += nameBytes.size + valueBytes.size
            if (aggregateHeaderBytes > bounds.maxRequestHeaderBytes || normalizedName in FORBIDDEN_REQUEST_HEADERS) {
                return ValidatedRequest.Failure(GenericHttpFailure.InvalidArgument("headers"))
            }
            try {
                headersBuilder.add(name, value)
            } catch (_: IllegalArgumentException) {
                return ValidatedRequest.Failure(GenericHttpFailure.InvalidArgument("headers"))
            }
        }
        val bodyBytes = request.body?.let(::encodeUtf8)
        if (request.body != null && bodyBytes == null) {
            return ValidatedRequest.Failure(GenericHttpFailure.InvalidValue("body"))
        }
        if (bodyBytes != null && bodyBytes.size > bounds.maxRequestBodyBytes) {
            return ValidatedRequest.Failure(GenericHttpFailure.TooLarge)
        }
        if (bodyBytes != null && request.method in setOf(GenericHttpMethod.GET, GenericHttpMethod.HEAD)) {
            return ValidatedRequest.Failure(GenericHttpFailure.InvalidArgument("body"))
        }
        return ValidatedRequest.Success(
            url = url,
            headers = request.headers.toMap(),
            body = bodyBytes,
        )
    }

    private suspend fun execute(
        method: GenericHttpMethod,
        url: HttpUrl,
        headers: Map<String, String>,
        body: ByteArray?,
        timeoutMillis: Long,
    ): TransportStep = suspendCancellableCoroutine { continuation ->
        val requestBody = when (method) {
            GenericHttpMethod.GET, GenericHttpMethod.HEAD -> null
            else -> (body ?: EMPTY_BODY).toRequestBody(null)
        }
        val builder = Request.Builder().url(url).method(method.name, requestBody)
        for ((name, value) in headers) {
            builder.addHeader(name, value)
        }
        val call = client.newCall(builder.build())
        call.timeout().timeout(timeoutMillis, TimeUnit.MILLISECONDS)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, exception: IOException) {
                if (!continuation.isActive) return
                val failure = when {
                    call.isCanceled() -> GenericHttpFailure.Cancelled
                    exception is SocketTimeoutException -> GenericHttpFailure.Timeout
                    else -> GenericHttpFailure.TransportUnavailable
                }
                continuation.resume(TransportStep.Failure(failure))
            }

            override fun onResponse(call: Call, response: Response) {
                if (!continuation.isActive) {
                    response.close()
                    return
                }
                continuation.resume(TransportStep.ResponseValue(response, call))
            }
        })
    }

    private fun redirectTarget(response: Response, base: HttpUrl): String? {
        if (response.code !in REDIRECT_STATUS_CODES) return null
        val location = response.header("Location") ?: return null
        if (location.utf8Size() > bounds.maxUrlBytes) return INVALID_REDIRECT
        return base.resolve(location)?.toString() ?: INVALID_REDIRECT
    }
    private suspend fun admitResponse(response: Response, call: Call): GenericHttpResult {
        // Close the response on coroutine cancellation so the connection is
        // released even if the body read is blocked. Cancellation surfaces via
        // call.cancel() closing the socket: the blocked read throws an
        // IOException, readBounded's ensureActive() rethrows it as
        // CancellationException, and withContext(Dispatchers.IO) unwinds. The
        // client read timeout is intentionally disabled (0 = infinite) so slow
        // or streaming responses are not aborted mid-read; the per-call
        // deadline (call.timeout() from request.timeoutMillis) bounds the whole
        // call and is the sole authority.
        val callerJob = currentCoroutineContext()[Job]
        val cancellationHandle = callerJob?.invokeOnCompletion { cause ->
            if (cause != null) {
                runCatching { call.cancel() }
                response.close()
            }
        }
        return withContext(Dispatchers.IO) {
            val context = currentCoroutineContext()
            try {
                response.use {
                    if (response.code !in 100..599) {
                        return@withContext GenericHttpResult.Failure(GenericHttpFailure.TransportUnavailable)
                    }
                    val normalizedHeaders = normalizeResponseHeaders(response.headers)
                        ?: return@withContext GenericHttpResult.Failure(GenericHttpFailure.TooLarge)
                    val bytes = try {
                        readBounded(response) { context.ensureActive() }
                    } catch (failure: IOException) {
                        context.ensureActive()
                        return@withContext GenericHttpResult.Failure(
                            if (failure is SocketTimeoutException) {
                                GenericHttpFailure.Timeout
                            } else {
                                GenericHttpFailure.TransportUnavailable
                            },
                        )
                    } ?: return@withContext GenericHttpResult.Failure(GenericHttpFailure.TooLarge)
                    context.ensureActive()
                    val body = decodeUtf8(bytes)
                        ?: return@withContext GenericHttpResult.Failure(GenericHttpFailure.InvalidValue("body"))
                    GenericHttpResult.Success(
                        GenericHttpResponse(
                            status = response.code,
                            headers = normalizedHeaders,
                            body = body,
                        ),
                    )
                }
            } finally {
                cancellationHandle?.dispose()
            }
        }
    }

    private fun normalizeResponseHeaders(headers: Headers): Map<String, String>? {
        val values = sortedMapOf<String, MutableList<String>>()
        var aggregateBytes = 0
        for (index in 0 until headers.size) {
            val name = headers.name(index).lowercase(Locale.ROOT)
            val value = headers.value(index)
            aggregateBytes += name.utf8Size() + value.utf8Size()
            if (aggregateBytes > bounds.maxResponseHeaderBytes) return null
            values.getOrPut(name) { ArrayList() }.add(value)
        }
        return values.mapValues { (_, entries) -> entries.joinToString(", ") }
    }
    private fun readBounded(response: Response, ensureActive: () -> Unit): ByteArray? {
        val stream = response.body?.byteStream() ?: return EMPTY_BODY
        val output = ByteArrayOutputStream(minOf(8192, bounds.maxResponseBodyBytes))
        val buffer = ByteArray(8192)
        var retained = 0
        while (true) {
            ensureActive()
            val read = stream.read(buffer)
            if (read < 0) break
            retained += read
            if (retained > bounds.maxResponseBodyBytes) return null
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    private fun decodeUtf8(bytes: ByteArray): String? = try {
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (_: CharacterCodingException) {
        null
    }

    private fun encodeUtf8(value: String): ByteArray? = try {
        val encoded = StandardCharsets.UTF_8.newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .encode(CharBuffer.wrap(value))
        ByteArray(encoded.remaining()).also(encoded::get)
    } catch (_: CharacterCodingException) {
        null
    }

    private fun normalizeHttpsUrl(value: String): HttpUrl? {
        val parsed = value.toHttpUrlOrNull() ?: return null
        if (parsed.scheme != "https" || parsed.username.isNotEmpty() || parsed.password.isNotEmpty()) return null
        if (parsed.fragment != null) return null
        return parsed
    }

    private fun sameOrigin(first: HttpUrl, second: HttpUrl): Boolean =
        first.scheme == second.scheme && first.host == second.host && first.port == second.port

    private sealed interface ValidatedRequest {
        data class Success(
            val url: HttpUrl,
            val headers: Map<String, String>,
            val body: ByteArray?,
        ) : ValidatedRequest

        data class Failure(val failure: GenericHttpFailure) : ValidatedRequest
    }

    private sealed interface TransportStep {
        data class ResponseValue(val response: Response, val call: Call) : TransportStep
        data class Failure(val failure: GenericHttpFailure) : TransportStep
    }

    private companion object {
        val EMPTY_BODY: ByteArray = ByteArray(0)
        const val INVALID_REDIRECT: String = "http://invalid.invalid/"
        val REDIRECT_STATUS_CODES: Set<Int> = setOf(301, 302, 303, 307, 308)
        val SENSITIVE_HEADERS: Set<String> = setOf("authorization", "proxy-authorization", "cookie")
        val FORBIDDEN_REQUEST_HEADERS: Set<String> = setOf(
            "connection",
            "content-length",
            "host",
            "keep-alive",
            "proxy-connection",
            "te",
            "trailer",
            "transfer-encoding",
            "upgrade",
        )
    }
}
