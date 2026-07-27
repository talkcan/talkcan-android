package io.talkcan.http

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GenericHttpAdmissionControllerTest {
    private val owner = GenericHttpOwner(
        packageId = "package",
        instanceId = "instance",
        generationId = "generation",
        executionOwnerId = "owner",
    )

    @Test
    fun quotasRejectBeforeMutationAndReleaseExactlyOnce() {
        val controller = GenericHttpAdmissionController(
            GenericHttpAdmissionLimits(
                maxPerOwner = 1,
                maxPerGeneration = 1,
                maxPerPackage = 1,
                maxProcess = 1,
                maxRetainedRequestBytes = 16,
            )
        )
        val lease = controller.acquire(owner, 8)
        assertNotNull(lease)
        assertNull(controller.acquire(owner, 1))
        assertNull(controller.acquire(owner.copy(executionOwnerId = "sibling"), 9))

        lease!!.close()
        lease.close()

        assertNotNull(controller.acquire(owner, 16)?.also { it.close() })
        assertNull(controller.acquire(owner, 17))
    }

    @Test
    fun admittedTransportReturnsBusyWhileSameOwnerIsActive() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val delegate = GenericHttpTransport {
            entered.complete(Unit)
            release.await()
            GenericHttpResult.Success(GenericHttpResponse(200, emptyMap(), "ok"))
        }
        val admitted = AdmittedGenericHttpTransport(
            owner = owner,
            controller = GenericHttpAdmissionController(
                GenericHttpAdmissionLimits(
                    maxPerOwner = 1,
                    maxPerGeneration = 1,
                    maxPerPackage = 1,
                    maxProcess = 1,
                    maxRetainedRequestBytes = 1024,
                )
            ),
            delegate = delegate,
        )
        val request = GenericHttpRequest(
            method = GenericHttpMethod.GET,
            url = "https://example.test/",
            timeoutMillis = 1_000,
        )
        val first = async { admitted.request(request) }
        entered.await()

        val second = admitted.request(request)
        assertEquals(GenericHttpFailure.Busy, (second as GenericHttpResult.Failure).failure)

        release.complete(Unit)
        assertTrue(first.await() is GenericHttpResult.Success)
        assertTrue(admitted.request(request) is GenericHttpResult.Success)
    }
}
