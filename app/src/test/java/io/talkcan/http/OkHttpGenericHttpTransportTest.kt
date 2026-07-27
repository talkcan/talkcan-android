package io.talkcan.http

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class OkHttpGenericHttpTransportTest {
    private lateinit var certificate: HeldCertificate
    private lateinit var serverCertificates: HandshakeCertificates
    private lateinit var client: OkHttpClient
    private val servers = ArrayList<MockWebServer>()

    @Before
    fun setUp() {
        certificate = HeldCertificate.Builder()
            .addSubjectAlternativeName("localhost")
            .build()
        serverCertificates = HandshakeCertificates.Builder()
            .heldCertificate(certificate)
            .build()
        val clientCertificates = HandshakeCertificates.Builder()
            .addTrustedCertificate(certificate.certificate)
            .build()
        client = OkHttpClient.Builder()
            .sslSocketFactory(clientCertificates.sslSocketFactory(), clientCertificates.trustManager)
            .build()
    }

    @After
    fun tearDown() {
        servers.forEach { server ->
            runCatching { server.shutdown() }
        }
    }

    @Test
    fun non2xxIsCompleteTransportSuccess() = runBlocking {
        val server = httpsServer()
        server.enqueue(
            MockResponse()
                .setResponseCode(401)
                .addHeader("X-Trace", "one")
                .addHeader("X-Trace", "two")
                .setBody("denied")
        )

        val result = transport().request(
            GenericHttpRequest(
                method = GenericHttpMethod.POST,
                url = server.url("/completion").toString(),
                headers = mapOf("Content-Type" to "application/json"),
                body = "{}",
                timeoutMillis = 2_000,
            )
        )

        require(result is GenericHttpResult.Success)
        assertEquals(401, result.response.status)
        assertEquals("one, two", result.response.headers["x-trace"])
        assertEquals("denied", result.response.body)
        assertEquals("{}", server.takeRequest().body.readUtf8())
    }

    @Test
    fun insecureAndCredentialBearingUrlsFailBeforeTransport() = runBlocking {
        val adapter = transport()
        val insecure = adapter.request(request("http://example.test/value"))
        val credentialBearing = adapter.request(request("https://user:secret@example.test/value"))

        assertTrue(insecure is GenericHttpResult.Failure)
        assertTrue(credentialBearing is GenericHttpResult.Failure)
        assertEquals(
            GenericHttpFailure.InvalidArgument("url"),
            (insecure as GenericHttpResult.Failure).failure,
        )
    }

    @Test
    fun crossOriginRedirectRemovesSensitiveHeaders() = runBlocking {
        val first = httpsServer()
        val second = httpsServer()
        first.enqueue(
            MockResponse()
                .setResponseCode(307)
                .addHeader("Location", second.url("/target"))
        )
        second.enqueue(MockResponse().setBody("ok"))

        val result = transport().request(
            GenericHttpRequest(
                method = GenericHttpMethod.GET,
                url = first.url("/start").toString(),
                headers = mapOf(
                    "Authorization" to "Bearer secret",
                    "Cookie" to "session=secret",
                    "X-Public" to "retained",
                ),
                timeoutMillis = 2_000,
            )
        )

        assertTrue(result is GenericHttpResult.Success)
        first.takeRequest()
        val redirected = second.takeRequest()
        assertFalse(redirected.headers.names().any { it.equals("authorization", ignoreCase = true) })
        assertFalse(redirected.headers.names().any { it.equals("cookie", ignoreCase = true) })
        assertEquals("retained", redirected.getHeader("X-Public"))
    }

    @Test
    fun binaryAndOversizedResponsesFailAtomically() = runBlocking {
        val server = httpsServer()
        server.enqueue(MockResponse().setBody(okio.Buffer().write(byteArrayOf(0xC3.toByte(), 0x28))))
        server.enqueue(MockResponse().setBody("12345"))
        val adapter = transport(GenericHttpBounds(maxResponseBodyBytes = 4))

        val binary = adapter.request(request(server.url("/binary").toString()))
        val oversized = adapter.request(request(server.url("/large").toString()))

        assertEquals(
            GenericHttpFailure.InvalidValue("body"),
            (binary as GenericHttpResult.Failure).failure,
        )
        assertEquals(GenericHttpFailure.TooLarge, (oversized as GenericHttpResult.Failure).failure)
    }

    @Test
    fun timeoutCancelsCallWithoutLateSuccess() = runBlocking {
        val server = httpsServer()
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val result = transport().request(
            request(server.url("/wait").toString(), timeoutMillis = 100)
        )
        assertTrue(result is GenericHttpResult.Failure)
        val failure = (result as GenericHttpResult.Failure).failure
        assertTrue(failure == GenericHttpFailure.Timeout || failure == GenericHttpFailure.Cancelled)
    }

    @Test
    fun everySupportedMethodIsTransmittedExactly() = runBlocking {
        val server = httpsServer()
        for (method in GenericHttpMethod.entries) {
            server.enqueue(MockResponse().setResponseCode(204))
            val body = if (method == GenericHttpMethod.GET || method == GenericHttpMethod.HEAD) null else "payload"
            val result = transport().request(
                GenericHttpRequest(
                    method = method,
                    url = server.url("/${method.name.lowercase()}").toString(),
                    body = body,
                    timeoutMillis = 2_000,
                )
            )
            assertTrue("$method failed: $result", result is GenericHttpResult.Success)
            val observed = server.takeRequest()
            assertEquals(method.name, observed.method)
            if (body != null) assertEquals(body, observed.body.readUtf8())
        }
    }

    @Test
    fun malformedHeadersBodiesAndRedirectLoopsFailWithoutPartialSuccess() = runBlocking {
        val server = httpsServer()
        server.enqueue(
            MockResponse()
                .setResponseCode(302)
                .addHeader("Location", server.url("/loop"))
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(302)
                .addHeader("Location", server.url("/loop"))
        )
        val bounded = transport(GenericHttpBounds(maxRequestBodyBytes = 4, maxRedirects = 1))

        val malformedHeader = bounded.request(
            request(server.url("/header").toString()).copy(
                headers = mapOf("X-Test" to "value\ninjected")
            )
        )
        val invalidGetBody = bounded.request(
            request(server.url("/body").toString()).copy(body = "body")
        )
        val oversizedBody = bounded.request(
            request(server.url("/large").toString()).copy(
                method = GenericHttpMethod.POST,
                body = "12345",
            )
        )
        val loop = bounded.request(request(server.url("/loop").toString()))

        assertEquals(
            GenericHttpFailure.InvalidArgument("headers"),
            (malformedHeader as GenericHttpResult.Failure).failure,
        )
        assertEquals(
            GenericHttpFailure.InvalidArgument("body"),
            (invalidGetBody as GenericHttpResult.Failure).failure,
        )
        assertEquals(GenericHttpFailure.TooLarge, (oversizedBody as GenericHttpResult.Failure).failure)
        assertTrue(loop is GenericHttpResult.Failure)
    }

    @Test
    fun platformTrustRejectsUntrustedTls() = runBlocking {
        val server = httpsServer()
        server.enqueue(MockResponse().setBody("must not be admitted"))
        val untrusted = OkHttpGenericHttpTransport(OkHttpClient(), GenericHttpBounds())

        val result = untrusted.request(request(server.url("/tls").toString()))

        assertEquals(
            GenericHttpFailure.TransportUnavailable,
            (result as GenericHttpResult.Failure).failure,
        )
    }

    @Test
    fun callerCancellationCancelsTransport() = runBlocking {
        val server = httpsServer()
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val job = launch {
            transport().request(request(server.url("/cancel").toString(), timeoutMillis = 5_000))
        }
        delay(100)

        withTimeout(1_000) { job.cancelAndJoin() }

        assertTrue(job.isCancelled)
    }

    @Test
    fun resolvedRedirectUrlIsRecheckedAgainstFullBound() = runBlocking {
        val server = httpsServer()
        val longRelativePath = "/" + "a".repeat(70)
        server.enqueue(
            MockResponse()
                .setResponseCode(307)
                .addHeader("Location", longRelativePath)
        )
        val adapter = transport(GenericHttpBounds(maxUrlBytes = 80))

        val result = adapter.request(request(server.url("/short").toString()))

        assertTrue(result is GenericHttpResult.Failure)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun malformedUtf16RequestBodyIsRejectedRatherThanRewritten() = runBlocking {
        val result = transport().request(
            GenericHttpRequest(
                method = GenericHttpMethod.POST,
                url = "https://example.test/",
                body = "\uD800",
                timeoutMillis = 2_000,
            )
        )

        assertEquals(
            GenericHttpFailure.InvalidValue("body"),
            (result as GenericHttpResult.Failure).failure,
        )
    }

    @Test
    fun cancellationAfterHeadersClosesSlowResponseBodyPromptly() = runBlocking {
        val server = httpsServer()
        server.enqueue(
            MockResponse()
                .setBody("slow-body")
                .throttleBody(1, 5, TimeUnit.SECONDS)
        )
        val job = launch {
            transport().request(request(server.url("/slow-body").toString(), timeoutMillis = 10_000))
        }
        delay(200)

        withTimeout(5_000) { job.cancelAndJoin() }

        assertTrue(job.isCancelled)
    }

    private fun request(url: String, timeoutMillis: Long = 2_000): GenericHttpRequest =
        GenericHttpRequest(
            method = GenericHttpMethod.GET,
            url = url,
            timeoutMillis = timeoutMillis,
        )

    private fun transport(bounds: GenericHttpBounds = GenericHttpBounds()): GenericHttpTransport =
        OkHttpGenericHttpTransport(client, bounds)

    private fun httpsServer(): MockWebServer {
        val server = MockWebServer()
        server.useHttps(serverCertificates.sslSocketFactory(), false)
        server.start()
        servers += server
        return server
    }
}
