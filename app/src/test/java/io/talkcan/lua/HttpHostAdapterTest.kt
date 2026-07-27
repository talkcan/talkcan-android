package io.talkcan.lua

import io.talkcan.http.GenericHttpFailure
import io.talkcan.http.GenericHttpMethod
import io.talkcan.http.GenericHttpRequest
import io.talkcan.http.GenericHttpResponse
import io.talkcan.http.GenericHttpResult
import io.talkcan.http.GenericHttpTransport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpHostAdapterTest {

    private fun httpRequestClaim(
        method: String? = "GET",
        url: String? = "https://example.test/",
        headersJson: String? = null,
        body: String? = null,
        timeoutMs: Long = 0,
        kind: HostOperationKind = HostOperationKind.HTTP_REQUEST,
    ) = HostOperationClaim.Admitted(
        requestId = 1,
        kind = kind,
        audioToken = null,
        text = null,
        language = null,
        voice = null,
        speed = 1.0,
        delaySeconds = 0.0,
        method = method,
        url = url,
        headersJson = headersJson,
        body = body,
        timeoutMs = timeoutMs,
    )

    private class CapturingTransport(
        private val responder: (GenericHttpRequest) -> GenericHttpResult,
    ) : GenericHttpTransport {
        var invocations: Int = 0
            private set
        var lastRequest: GenericHttpRequest? = null
            private set

        override suspend fun request(request: GenericHttpRequest): GenericHttpResult {
            invocations += 1
            lastRequest = request
            return responder(request)
        }
    }

    private fun successResponder(status: Int = 200, body: String = "ok") =
        CapturingTransport {
            GenericHttpResult.Success(GenericHttpResponse(status, emptyMap(), body))
        }

    @Test
    fun successResponseSerializesToEnvelopeDocument() = runBlocking {
        val transport = CapturingTransport {
            GenericHttpResult.Success(GenericHttpResponse(201, mapOf("X-A" to "b"), "body"))
        }

        val completion = HttpHostAdapter(transport).complete(httpRequestClaim())

        assertTrue(completion.success)
        val doc = JSONObject(completion.value)
        assertEquals(201, doc.getInt("status"))
        val headers = doc.getJSONObject("headers")
        assertEquals(1, headers.length())
        assertEquals("b", headers.getString("X-A"))
        assertEquals("body", doc.getString("body"))
        assertEquals(1, transport.invocations)
    }

    @Test
    fun transportFailuresNormalizeToAllowlistedCodes() = runBlocking {
        val cases = listOf(
            GenericHttpFailure.InvalidArgument("url") to "E_INVALID_ARGUMENT",
            GenericHttpFailure.InvalidValue("url") to "E_INVALID_VALUE",
            GenericHttpFailure.TooLarge to "E_TOO_LARGE",
            GenericHttpFailure.Busy to "E_BUSY",
            GenericHttpFailure.Timeout to "E_TIMEOUT",
            GenericHttpFailure.Cancelled to "E_CANCELLED",
            GenericHttpFailure.TransportUnavailable to "E_TRANSPORT",
        )
        for ((failure, expectedCode) in cases) {
            val transport = CapturingTransport { GenericHttpResult.Failure(failure) }

            val completion = HttpHostAdapter(transport).complete(httpRequestClaim())

            assertFalse("$failure must not report success", completion.success)
            assertEquals("normalized code for $failure", expectedCode, completion.value)
        }
    }

    @Test
    fun unsupportedMethodRejectedWithoutTransport() = runBlocking {
        val transport = successResponder()

        val completion = HttpHostAdapter(transport).complete(httpRequestClaim(method = "FETCH"))

        assertFalse(completion.success)
        assertEquals("E_INVALID_ARGUMENT", completion.value)
        assertEquals(0, transport.invocations)
        assertNull(transport.lastRequest)
    }

    @Test
    fun blankUrlRejectedWithoutTransport() = runBlocking {
        val transport = successResponder()

        val completion = HttpHostAdapter(transport).complete(httpRequestClaim(url = "   "))

        assertFalse(completion.success)
        assertEquals("E_INVALID_ARGUMENT", completion.value)
        assertEquals(0, transport.invocations)
    }

    @Test
    fun malformedHeadersJsonRejectedWithoutTransport() = runBlocking {
        val transport = successResponder()

        val completion = HttpHostAdapter(transport)
            .complete(httpRequestClaim(headersJson = "not json"))

        assertFalse(completion.success)
        assertEquals("E_INVALID_ARGUMENT", completion.value)
        assertEquals(0, transport.invocations)
    }

    @Test
    fun nonStringHeaderValueRejectedWithoutTransport() = runBlocking {
        val transport = successResponder()

        // Non-string header values are an argument-shape violation of the
        // headersJson contract (a bounded object of string pairs) and are
        // rejected before the admitted transport ever sees the request.
        val completion = HttpHostAdapter(transport)
            .complete(httpRequestClaim(headersJson = """{"A":5}"""))

        assertFalse(completion.success)
        assertEquals("E_INVALID_ARGUMENT", completion.value)
        assertEquals(0, transport.invocations)
    }

    @Test
    fun zeroTimeoutAppliesCoercedDefault() = runBlocking {
        val transport = successResponder()

        HttpHostAdapter(transport).complete(httpRequestClaim(timeoutMs = 0))
        assertEquals(30_000L, transport.lastRequest?.timeoutMillis)

        HttpHostAdapter(transport).complete(httpRequestClaim(timeoutMs = -1))
        assertEquals(30_000L, transport.lastRequest?.timeoutMillis)
    }

    @Test
    fun nonzeroTimeoutForwardedUnmodifiedBelowBoundsMinimum() = runBlocking {
        // Bounds enforcement (minTimeoutMillis = 100 by default) is the
        // admitted transport's job; the adapter forwards nonzero values
        // verbatim so bounded validation produces the real failure code.
        val transport = successResponder()

        HttpHostAdapter(transport).complete(httpRequestClaim(timeoutMs = 50))

        assertEquals(50L, transport.lastRequest?.timeoutMillis)
    }

    @Test
    fun requestFieldsForwardExactly() = runBlocking {
        val transport = successResponder()
        val claim = httpRequestClaim(
            method = "POST",
            url = "https://api.example.test/v1/items",
            headersJson = """{"Content-Type":"application/json","X-Trace":"abc"}""",
            body = """{"k":"v"}""",
            timeoutMs = 1_234,
        )

        val completion = HttpHostAdapter(transport).complete(claim)

        assertTrue(completion.success)
        assertEquals(
            GenericHttpRequest(
                method = GenericHttpMethod.POST,
                url = "https://api.example.test/v1/items",
                headers = mapOf(
                    "Content-Type" to "application/json",
                    "X-Trace" to "abc",
                ),
                body = """{"k":"v"}""",
                timeoutMillis = 1_234,
            ),
            transport.lastRequest,
        )
    }

    @Test
    fun wrongClaimKindDeniedWithoutTransport() = runBlocking {
        val transport = successResponder()

        val completion = HttpHostAdapter(transport)
            .complete(httpRequestClaim(kind = HostOperationKind.SECRET_READ))

        assertFalse(completion.success)
        assertEquals("E_DENIED", completion.value)
        assertEquals(0, transport.invocations)
    }

    @Test
    fun cancellationPropagatesWithoutCompletion() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<GenericHttpResult>()
        val transport = GenericHttpTransport { _ ->
            entered.complete(Unit)
            release.await()
        }
        var completion: TypedHostCompletion? = null
        var thrown: Throwable? = null

        val job = launch {
            try {
                completion = HttpHostAdapter(transport).complete(httpRequestClaim())
            } catch (t: Throwable) {
                thrown = t
                throw t
            }
        }
        entered.await()

        job.cancel()
        job.join()

        assertTrue(job.isCancelled)
        assertTrue(
            "expected CancellationException, was $thrown",
            thrown is CancellationException,
        )
        assertNull("cancelled operation must not resume", completion)
        assertFalse("transport suspension must never be resumed", release.isCompleted)
    }
}
