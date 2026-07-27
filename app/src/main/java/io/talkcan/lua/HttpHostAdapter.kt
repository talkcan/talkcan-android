package io.talkcan.lua

import io.talkcan.http.GenericHttpBounds
import io.talkcan.http.GenericHttpFailure
import io.talkcan.http.GenericHttpMethod
import io.talkcan.http.GenericHttpRequest
import io.talkcan.http.GenericHttpResult
import io.talkcan.http.GenericHttpTransport
import org.json.JSONObject

/**
 * Typed actor suspension for `talkcan.http.request` (task 9.2).
 *
 * Revalidates the native-checked claim into one [GenericHttpRequest] and
 * drives the supplied admitted transport (quota lease, HTTPS policy,
 * redirects, and bounds all remain host-owned). Any complete response —
 * including non-2xx status — is a transport success serialized as
 * `{"status": <int>, "headers": {...}, "body": <utf8>}`; every failure maps
 * to one normalized `E_*` code. Cancellation propagates as structured
 * coroutine cancellation so the operation terminal gate decides the race,
 * and late completions after close are suppressed by the caller's resume
 * path, never by adapter state.
 */
internal class HttpHostAdapter(
    private val transport: GenericHttpTransport,
    private val bounds: GenericHttpBounds = GenericHttpBounds(),
    private val defaultTimeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
) : TypedHostOperationAdapter {

    override suspend fun complete(claim: HostOperationClaim.Admitted): TypedHostCompletion {
        if (claim.kind != HostOperationKind.HTTP_REQUEST) {
            return TypedHostCompletion.failure("E_DENIED")
        }
        val request = claim.toGenericHttpRequest() ?: return TypedHostCompletion.failure("E_INVALID_ARGUMENT")
        return when (val result = transport.request(request)) {
            is GenericHttpResult.Success -> TypedHostCompletion(
                success = true,
                value = JSONObject().apply {
                    put("status", result.response.status)
                    put("headers", JSONObject().apply {
                        result.response.headers.forEach { (name, headerValue) -> put(name, headerValue) }
                    })
                    put("body", result.response.body)
                }.toString(),
            )
            is GenericHttpResult.Failure ->
                TypedHostCompletion.failure(result.failure.normalizedCode())
        }
    }

    /**
     * Revalidate claim fields into a normalized request. Argument-shape
     * problems map to `null` (E_INVALID_ARGUMENT); value problems are left
     * to the admitted transport's bounded validation.
     */
    private fun HostOperationClaim.Admitted.toGenericHttpRequest(): GenericHttpRequest? {
        val parsedMethod = GenericHttpMethod.entries.firstOrNull { it.name == method }
            ?: return null
        val requestUrl = url?.takeIf { it.isNotBlank() } ?: return null
        val headers = LinkedHashMap<String, String>()
        headersJson?.let { raw ->
            val parsed = try {
                JSONObject(raw)
            } catch (_: Exception) {
                return null
            }
            val keys = parsed.keys()
            while (keys.hasNext()) {
                val name = keys.next()
                val value = parsed.opt(name)
                if (value !is String) return null
                headers[name] = value
            }
        }
        val timeout = if (timeoutMs <= 0L) {
            defaultTimeoutMillis.coerceIn(bounds.minTimeoutMillis, bounds.maxTimeoutMillis)
        } else {
            timeoutMs
        }
        return GenericHttpRequest(
            method = parsedMethod,
            url = requestUrl,
            headers = headers,
            body = body,
            timeoutMillis = timeout,
        )
    }

    companion object {
        const val DEFAULT_TIMEOUT_MILLIS: Long = 30_000L
    }
}

/** Normalization into the HTTP_REQUEST allowlist (default E_TRANSPORT). */
internal fun GenericHttpFailure.normalizedCode(): String = when (this) {
    is GenericHttpFailure.InvalidArgument -> "E_INVALID_ARGUMENT"
    is GenericHttpFailure.InvalidValue -> "E_INVALID_VALUE"
    GenericHttpFailure.TooLarge -> "E_TOO_LARGE"
    GenericHttpFailure.Busy -> "E_BUSY"
    GenericHttpFailure.Timeout -> "E_TIMEOUT"
    GenericHttpFailure.Cancelled -> "E_CANCELLED"
    GenericHttpFailure.TransportUnavailable -> "E_TRANSPORT"
}
