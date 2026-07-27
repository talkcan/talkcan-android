package io.talkcan.http

import java.util.concurrent.atomic.AtomicBoolean

public data class GenericHttpOwner(
    val packageId: String,
    val instanceId: String,
    val generationId: String,
    val executionOwnerId: String,
) {
    init {
        require(packageId.isNotBlank())
        require(instanceId.isNotBlank())
        require(generationId.isNotBlank())
        require(executionOwnerId.isNotBlank())
    }
}

public data class GenericHttpAdmissionLimits(
    val maxPerOwner: Int = 2,
    val maxPerGeneration: Int = 4,
    val maxPerPackage: Int = 8,
    val maxProcess: Int = 16,
    val maxRetainedRequestBytes: Long = 8L * 1024 * 1024,
) {
    init {
        require(maxPerOwner > 0)
        require(maxPerGeneration >= maxPerOwner)
        require(maxPerPackage >= maxPerGeneration)
        require(maxProcess >= maxPerPackage)
        require(maxRetainedRequestBytes > 0)
    }
}

public class GenericHttpAdmissionController(
    private val limits: GenericHttpAdmissionLimits = GenericHttpAdmissionLimits(),
) {
    private val lock = Any()
    private val ownerCounts: MutableMap<GenericHttpOwner, Int> = HashMap()
    private val generationCounts: MutableMap<Pair<String, String>, Int> = HashMap()
    private val packageCounts: MutableMap<String, Int> = HashMap()
    private var processCount: Int = 0
    private var retainedRequestBytes: Long = 0

    public fun acquire(owner: GenericHttpOwner, requestBytes: Long): Lease? = synchronized(lock) {
        require(requestBytes >= 0)
        val generationKey = owner.packageId to owner.generationId
        val ownerCount = ownerCounts[owner] ?: 0
        val generationCount = generationCounts[generationKey] ?: 0
        val packageCount = packageCounts[owner.packageId] ?: 0
        if (ownerCount >= limits.maxPerOwner ||
            generationCount >= limits.maxPerGeneration ||
            packageCount >= limits.maxPerPackage ||
            processCount >= limits.maxProcess ||
            requestBytes > limits.maxRetainedRequestBytes - retainedRequestBytes
        ) {
            return@synchronized null
        }
        ownerCounts[owner] = ownerCount + 1
        generationCounts[generationKey] = generationCount + 1
        packageCounts[owner.packageId] = packageCount + 1
        processCount += 1
        retainedRequestBytes += requestBytes
        Lease(this, owner, generationKey, requestBytes)
    }

    private fun release(
        owner: GenericHttpOwner,
        generationKey: Pair<String, String>,
        requestBytes: Long,
    ) {
        synchronized(lock) {
            decrement(ownerCounts, owner)
            decrement(generationCounts, generationKey)
            decrement(packageCounts, owner.packageId)
            processCount -= 1
            retainedRequestBytes -= requestBytes
            check(processCount >= 0)
            check(retainedRequestBytes >= 0)
        }
    }

    private fun <K> decrement(counts: MutableMap<K, Int>, key: K) {
        val next = checkNotNull(counts[key]) - 1
        if (next == 0) counts.remove(key) else counts[key] = next
    }

    public class Lease internal constructor(
        private val controller: GenericHttpAdmissionController,
        private val owner: GenericHttpOwner,
        private val generationKey: Pair<String, String>,
        private val requestBytes: Long,
    ) : AutoCloseable {
        private val closed = AtomicBoolean(false)

        override fun close() {
            if (closed.compareAndSet(false, true)) {
                controller.release(owner, generationKey, requestBytes)
            }
        }
    }
}

public class AdmittedGenericHttpTransport(
    private val owner: GenericHttpOwner,
    private val controller: GenericHttpAdmissionController,
    private val delegate: GenericHttpTransport,
) : GenericHttpTransport {
    override suspend fun request(request: GenericHttpRequest): GenericHttpResult {
        val retainedBytes = request.url.utf8Size().toLong() +
            request.headers.entries.sumOf { (name, value) -> name.utf8Size().toLong() + value.utf8Size() } +
            (request.body?.utf8Size()?.toLong() ?: 0L)
        val lease = controller.acquire(owner, retainedBytes)
            ?: return GenericHttpResult.Failure(GenericHttpFailure.Busy)
        lease.use {
            return delegate.request(request)
        }
    }
}
