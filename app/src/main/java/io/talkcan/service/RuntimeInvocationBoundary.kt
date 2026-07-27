package io.talkcan.service

import io.talkcan.channel.capability.RuntimeGeneration
import java.io.Closeable
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CompletableDeferred
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.asCoroutineDispatcher

/**
 * Normalized result of a provider or runtime callback. Failure messages are
 * host-safe summaries and intentionally do not retain implementation throwables.
 */
sealed interface RuntimeInvocationOutcome<out T> {
    data class Success<T>(val value: T) : RuntimeInvocationOutcome<T>
    data object Busy : RuntimeInvocationOutcome<Nothing>
    data class Unavailable(val reason: String) : RuntimeInvocationOutcome<Nothing>
    data object Cancelled : RuntimeInvocationOutcome<Nothing>
    data object TimedOut : RuntimeInvocationOutcome<Nothing>
    data class ProviderFailure(val failure: RuntimeInvocationFailure) : RuntimeInvocationOutcome<Nothing>
    data class RuntimeFailure(val failure: RuntimeInvocationFailure) : RuntimeInvocationOutcome<Nothing>
    data object Closed : RuntimeInvocationOutcome<Nothing>
}

enum class RuntimeInvocationOrigin {
    PROVIDER,
    RUNTIME,
}

enum class RuntimeInvocationPhase {
    CONSTRUCT,
    STARTUP,
    PREPARE_INPUT,
    INPUT_STARTED,
    INPUT_RELEASED,
    INPUT_PLAYBACK_COMPLETED,
    INPUT_CANCELLED,
    INPUT_FAILED,
    READINESS_REFRESH,
    HANDLE_SOS,
    CLOSE,
}

data class RuntimeInvocationFailure(
    val instanceId: String,
    val generation: RuntimeGeneration,
    val phase: RuntimeInvocationPhase,
    val message: String,
)

data class RuntimeInvocationPolicy(
    val perGenerationQueueCapacity: Int = 16,
    val callbackTimeoutMillis: Long = 15_000,
    val inputReleasedTimeoutMillis: Long = 120_000,
    val closeTimeoutMillis: Long = 5_000,
    val prepareInputTimeoutMillis: Long = 20_000,
    val handleSosTimeoutMillis: Long = 15_000,
) {
    init {
        require(perGenerationQueueCapacity > 0) { "Per-generation queue capacity must be positive" }
        require(callbackTimeoutMillis > 0) { "Callback timeout must be positive" }
        require(inputReleasedTimeoutMillis > 0) { "Input release timeout must be positive" }
        require(closeTimeoutMillis > 0) { "Close timeout must be positive" }
        require(prepareInputTimeoutMillis > 0) { "Prepare-input timeout must be positive" }
        require(handleSosTimeoutMillis > 0) { "Handle-SOS timeout must be positive" }
    }

    fun timeoutFor(phase: RuntimeInvocationPhase): Long = when (phase) {
        RuntimeInvocationPhase.INPUT_RELEASED -> inputReleasedTimeoutMillis
        RuntimeInvocationPhase.PREPARE_INPUT -> prepareInputTimeoutMillis
        RuntimeInvocationPhase.HANDLE_SOS -> handleSosTimeoutMillis
        else -> callbackTimeoutMillis
    }
}

/**
 * A fixed-size host worker pool with a bounded executor queue. Runtime code is
 * only entered through [run], never on the caller's dispatcher.
 */
class RuntimeWorkerDispatcher private constructor(
    val dispatcher: CoroutineDispatcher,
    private val executor: ExecutorService?,
) : Closeable {
    suspend fun <T> run(block: suspend () -> T): T = withContext(dispatcher) { block() }

    override fun close() {
        executor?.shutdownNow()
    }

    companion object {
        fun create(
            workerCount: Int,
            queueCapacity: Int,
            threadNamePrefix: String = "channel-runtime",
        ): RuntimeWorkerDispatcher {
            require(workerCount > 0) { "Worker count must be positive" }
            require(queueCapacity > 0) { "Worker queue capacity must be positive" }
            require(threadNamePrefix.isNotBlank()) { "Thread name prefix must not be blank" }

            val threadNumber = AtomicInteger(0)
            val factory = ThreadFactory { runnable ->
                Thread(runnable, "$threadNamePrefix-${threadNumber.incrementAndGet()}").apply {
                    isDaemon = true
                }
            }
            val executor = ThreadPoolExecutor(
                workerCount,
                workerCount,
                0L,
                TimeUnit.MILLISECONDS,
                ArrayBlockingQueue(queueCapacity),
                factory,
                ThreadPoolExecutor.AbortPolicy(),
            )
            return RuntimeWorkerDispatcher(executor.asCoroutineDispatcher(), executor)
        }

        /** Test-only injection point; production must use [create]. */
        fun fromDispatcher(dispatcher: CoroutineDispatcher): RuntimeWorkerDispatcher =
            RuntimeWorkerDispatcher(dispatcher, null)
    }
}

/**
 * Explicit child ownership for all work started by one runtime generation.
 * The parent scope must itself be lifecycle-owned by the host.
 */
class RuntimeChildWork(parentScope: CoroutineScope) {
    private val parentJob = requireNotNull(parentScope.coroutineContext[Job]) {
        "Runtime child work requires a parent Job"
    }
    private val job = SupervisorJob(parentJob)
    val scope: CoroutineScope = CoroutineScope(parentScope.coroutineContext + job)

    fun cancel() {
        job.cancel()
    }

    suspend fun cancelAndJoinWithin(timeoutMillis: Long): Boolean = withContext(NonCancellable) {
        job.cancel()
        withTimeoutOrNull(timeoutMillis) {
            job.join()
            true
        } ?: false
    }
}

/**
 * A single accepted input target's authority to deliver terminal callbacks.
 * Only a runtime-generation gate can mint a conforming instance.
 */
interface RuntimeCommittedTarget {
    suspend fun <T> invoke(
        phase: RuntimeInvocationPhase,
        callback: suspend () -> T,
    ): RuntimeInvocationOutcome<T>

    suspend fun <T> invoke(
        phase: RuntimeInvocationPhase,
        timeoutMillis: Long,
        callback: suspend () -> T,
    ): RuntimeInvocationOutcome<T>

    fun release()
}

private class CommittedTarget(
    val gate: RuntimeGenerationInvocationGate,
) : RuntimeCommittedTarget {
    private val released = AtomicBoolean(false)

    val isActive: Boolean
        get() = !released.get()

    override suspend fun <T> invoke(
        phase: RuntimeInvocationPhase,
        callback: suspend () -> T,
    ): RuntimeInvocationOutcome<T> {
        if (!isActive) return RuntimeInvocationOutcome.Closed
        return gate.invokeCommittedTarget(this, phase, callback = callback)
    }

    override suspend fun <T> invoke(
        phase: RuntimeInvocationPhase,
        timeoutMillis: Long,
        callback: suspend () -> T,
    ): RuntimeInvocationOutcome<T> {
        if (!isActive) return RuntimeInvocationOutcome.Closed
        return gate.invokeCommittedTarget(this, phase, timeoutMillis, callback)
    }

    override fun release() {
        released.set(true)
    }
}

/** Opens one independently serialized, bounded runtime-generation invocation queue. */
class RuntimeInvocationBoundary(
    private val workers: RuntimeWorkerDispatcher,
    private val policy: RuntimeInvocationPolicy = RuntimeInvocationPolicy(),
) : Closeable {
    private val closed = AtomicBoolean(false)

    fun openGeneration(
        instanceId: String,
        generation: RuntimeGeneration,
        parentScope: CoroutineScope,
        closeScope: CoroutineScope = parentScope,
    ): RuntimeGenerationInvocationGate {
        check(!closed.get()) { "Runtime invocation boundary is closed" }
        return RuntimeGenerationInvocationGate(
            instanceId,
            generation,
            parentScope,
            closeScope,
            workers,
            policy,
        )
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            workers.close()
        }
    }
}

/**
 * The registry owns one gate per runtime generation. Admission is FIFO and
 * bounded; callback execution is serialized even when different gates run in
 * parallel on the host worker dispatcher.
 */
class RuntimeGenerationInvocationGate internal constructor(
    val instanceId: String,
    val generation: RuntimeGeneration,
    parentScope: CoroutineScope,
    closeScope: CoroutineScope,
    private val workers: RuntimeWorkerDispatcher,
    private val policy: RuntimeInvocationPolicy,
) {
    private val lifecycleJob = SupervisorJob(
        requireNotNull(parentScope.coroutineContext[Job]) {
            "Runtime invocation gate requires a parent Job"
        },
    )
    private val lifecycleScope = CoroutineScope(parentScope.coroutineContext + lifecycleJob)
    private val terminalJob = SupervisorJob(
        requireNotNull(closeScope.coroutineContext[Job]) {
            "Runtime close requires a host-owned close Job"
        },
    )
    private val terminalScope = CoroutineScope(closeScope.coroutineContext + terminalJob)
    val childWork = RuntimeChildWork(lifecycleScope)

    private val stateLock = Any()
    private val executionMutex = Mutex()
    private val queue = Channel<Request<*>>(policy.perGenerationQueueCapacity)
    private var admitting = true
    private var live = true
    private var active: Request<*>? = null
    private var closeResult: CompletableDeferred<RuntimeInvocationOutcome<Unit>>? = null

    private val processor = lifecycleScope.launch {
        for (request in queue) {
            process(request)
        }
    }

    val isLive: Boolean
        get() = synchronized(stateLock) { live }

    internal val isAdmissionStopped: Boolean
        get() = synchronized(stateLock) { !admitting }

    /** Stops new callbacks and rejects queued work while preserving a committed target until final close. */
    fun stopAdmission() {
        val cancelled: List<Request<*>>
        synchronized(stateLock) {
            if (!admitting) return
            admitting = false
            cancelled = drainQueuedLocked()
        }
        cancelled.forEach { it.complete(RuntimeInvocationOutcome.Closed) }
    }

    /** Invalidates this generation so no queued, running, or late work may commit an effect. */
    fun invalidate() {
        invalidateInternal(exemptRequest = null)
    }

    private fun invalidateForTimeout(request: Request<*>) {
        invalidateInternal(exemptRequest = request)
    }

    private fun invalidateInternal(exemptRequest: Request<*>?) {
        val cancelled: List<Request<*>>
        val running: Request<*>?
        synchronized(stateLock) {
            if (!live) return
            live = false
            admitting = false
            queue.close()
            cancelled = drainQueuedLocked()
            running = active
            if (running !== exemptRequest) {
                running?.cancel(RuntimeInvocationOutcome.Closed)
            }
        }
        cancelled.forEach { it.complete(RuntimeInvocationOutcome.Closed) }
        childWork.cancel()
    }

    /**
     * Atomically gates a host-owned publication/effect at the generation
     * boundary. The action must be brief and host-owned; it must not call
     * provider code or suspend while holding this state lock.
     */
    fun <T> commitIfLive(action: () -> T): RuntimeInvocationOutcome<T> = synchronized(stateLock) {
        if (!live) RuntimeInvocationOutcome.Closed else RuntimeInvocationOutcome.Success(action())
    }

    /** Creates the sole terminal-callback authority for one accepted target. */
    fun openCommittedTarget(): RuntimeCommittedTarget? = synchronized(stateLock) {
        if (!live || !admitting || closeResult != null) {
            null
        } else {
            CommittedTarget(this)
        }
    }

    suspend fun <T> invoke(
        phase: RuntimeInvocationPhase,
        origin: RuntimeInvocationOrigin = RuntimeInvocationOrigin.RUNTIME,
        timeoutMillis: Long = policy.timeoutFor(phase),
        callback: suspend () -> T,
    ): RuntimeInvocationOutcome<T> = invokeInternal(
        phase = phase,
        origin = origin,
        timeoutMillis = timeoutMillis,
        allowAfterAdmissionStopped = false,
        callback = callback,
    )

    internal suspend fun <T> invokeCommittedTarget(
        target: RuntimeCommittedTarget,
        phase: RuntimeInvocationPhase,
        timeoutMillis: Long = policy.timeoutFor(phase),
        callback: suspend () -> T,
    ): RuntimeInvocationOutcome<T> {
        val ownedTarget = target as? CommittedTarget ?: return RuntimeInvocationOutcome.Closed
        if (ownedTarget.gate !== this || !ownedTarget.isActive) {
            return RuntimeInvocationOutcome.Closed
        }
        require(phase.isCommittedTargetTerminal()) {
            "Only terminal input phases may run after admission stops"
        }
        return invokeInternal(
            phase = phase,
            origin = RuntimeInvocationOrigin.RUNTIME,
            timeoutMillis = timeoutMillis,
            allowAfterAdmissionStopped = true,
            callback = callback,
        )
    }

    private suspend fun <T> invokeInternal(
        phase: RuntimeInvocationPhase,
        origin: RuntimeInvocationOrigin,
        timeoutMillis: Long,
        allowAfterAdmissionStopped: Boolean,
        callback: suspend () -> T,
    ): RuntimeInvocationOutcome<T> {
        require(timeoutMillis > 0) { "Invocation timeout must be positive" }
        val request = Request(phase, origin, timeoutMillis, callback)
        val admitted = synchronized(stateLock) {
            if (!live || closeResult != null || (!admitting && !allowAfterAdmissionStopped)) {
                false
            } else {
                queue.trySend(request).isSuccess
            }
        }
        if (!admitted) {
            return synchronized(stateLock) {
                if (!live || closeResult != null || (!admitting && !allowAfterAdmissionStopped)) {
                    RuntimeInvocationOutcome.Closed
                } else {
                    RuntimeInvocationOutcome.Busy
                }
            }
        }

        return try {
            request.await()
        } catch (_: CancellationException) {
            request.cancel(RuntimeInvocationOutcome.Cancelled)
            RuntimeInvocationOutcome.Cancelled
        }
    }

    private fun RuntimeInvocationPhase.isCommittedTargetTerminal(): Boolean = when (this) {
        RuntimeInvocationPhase.INPUT_RELEASED,
        RuntimeInvocationPhase.INPUT_PLAYBACK_COMPLETED,
        RuntimeInvocationPhase.INPUT_CANCELLED,
        RuntimeInvocationPhase.INPUT_FAILED,
        -> true

        else -> false
    }

    /**
     * Atomically performs the bounded terminal sequence once. Repeated callers
     * join the first terminal result and never invoke another runtime close.
     */
    suspend fun close(
        terminalClose: suspend () -> Unit,
    ): RuntimeInvocationOutcome<Unit> {
        val (result, elected, cancelled) = synchronized(stateLock) {
            val existing = closeResult
            if (existing != null) {
                Triple(existing, false, emptyList<Request<*>>())
            } else {
                val created = CompletableDeferred<RuntimeInvocationOutcome<Unit>>()
                closeResult = created
                val drained = if (live) {
                    live = false
                    admitting = false
                    queue.close()
                    drainQueuedLocked().also {
                        active?.cancel(RuntimeInvocationOutcome.Closed)
                    }
                } else {
                    emptyList()
                }
                Triple(created, true, drained)
            }
        }
        cancelled.forEach { it.complete(RuntimeInvocationOutcome.Closed) }
        if (elected) {
            val outcome = withContext(NonCancellable) {
                try {
                    performClose(terminalClose)
                } catch (_: CancellationException) {
                    RuntimeInvocationOutcome.Cancelled
                } catch (failure: Throwable) {
                    failure.toOutcome(RuntimeInvocationPhase.CLOSE, RuntimeInvocationOrigin.RUNTIME)
                }
            }
            result.complete(outcome)
            lifecycleJob.cancel()
            terminalJob.cancel()
        }
        return withContext(NonCancellable) { result.await() }
    }

    private suspend fun performClose(terminalClose: suspend () -> Unit): RuntimeInvocationOutcome<Unit> {
        invalidate()
        processor.cancel()
        childWork.cancelAndJoinWithin(policy.closeTimeoutMillis)

        val terminalCloseClaimed = AtomicBoolean(false)
        val closeJob = terminalScope.async {
            workers.run {
                if (terminalCloseClaimed.compareAndSet(false, true)) {
                    terminalClose()
                }
            }
        }
        return try {
            withTimeout(policy.closeTimeoutMillis) {
                closeJob.await()
                RuntimeInvocationOutcome.Success(Unit)
            }
        } catch (_: TimeoutCancellationException) {
            // The timeout's successful claim prevents a queued worker from invoking the callback.
            // A worker claim owns the close, so it must finish before any fallback may proceed.
            if (terminalCloseClaimed.compareAndSet(false, true)) {
                closeJob.cancel(CancellationException("Runtime close timed out before starting"))
            } else {
                withContext(NonCancellable) { closeJob.join() }
            }
            RuntimeInvocationOutcome.TimedOut
        } catch (_: CancellationException) {
            if (terminalCloseClaimed.compareAndSet(false, true)) {
                closeJob.cancel()
            } else {
                withContext(NonCancellable) { closeJob.join() }
            }
            RuntimeInvocationOutcome.Cancelled
        } catch (failure: Throwable) {
            failure.toOutcome(RuntimeInvocationPhase.CLOSE, RuntimeInvocationOrigin.RUNTIME)
        }
    }

    private suspend fun process(request: Request<*>) {
        val shouldRun = synchronized(stateLock) {
            if (!live || closeResult != null || request.isCompleted) {
                false
            } else {
                active = request
                true
            }
        }
        if (!shouldRun) {
            request.complete(RuntimeInvocationOutcome.Closed)
            return
        }

        try {
            executionMutex.withLock {
                request.execute()
            }
        } finally {
            synchronized(stateLock) {
                if (active === request) active = null
            }
        }
    }

    /**
     * Runs a continuation under generation liveness and worker serialization without entering
     * the FIFO admission queue. Used by actor runtime to resume a yielded Lua slice under the
     * same per-generation execution serialization as host-admitted callbacks, but never as a
     * new host-admitted callback. Returns [RuntimeInvocationOutcome.Closed] if the generation
     * is no longer live; otherwise runs [action] on the worker dispatcher. Callers outside a
     * serialized request take [executionMutex]; continuations resumed from inside a request
     * callback are already covered by that lock and skip it to avoid self-deadlock.
     */
    internal suspend fun <T> invokeContinuation(action: suspend () -> T): RuntimeInvocationOutcome<T> {
        if (!isLive) return RuntimeInvocationOutcome.Closed
        // A continuation resumed from inside a serialized request callback is already
        // protected by the executionMutex held by process(); re-locking the
        // non-reentrant mutex would deadlock the whole generation (the processor
        // waits on the callback that waits on the lock it holds).
        if (coroutineContext[InsideGateExecution] != null) {
            return if (!isLive) RuntimeInvocationOutcome.Closed else runContinuationAction(action)
        }
        return try {
            executionMutex.withLock {
                if (!isLive) {
                    RuntimeInvocationOutcome.Closed
                } else {
                    runContinuationAction(action)
                }
            }
        } catch (_: CancellationException) {
            RuntimeInvocationOutcome.Cancelled
        }
    }

    private suspend fun <T> runContinuationAction(action: suspend () -> T): RuntimeInvocationOutcome<T> = try {
        RuntimeInvocationOutcome.Success(workers.run { action() })
    } catch (_: CancellationException) {
        RuntimeInvocationOutcome.Cancelled
    } catch (failure: Throwable) {
        failure.toOutcome(RuntimeInvocationPhase.CLOSE, RuntimeInvocationOrigin.RUNTIME)
    }

    private fun drainQueuedLocked(): List<Request<*>> {
        val drained = mutableListOf<Request<*>>()
        while (true) {
            val next = queue.tryReceive().getOrNull() ?: break
            drained += next
        }
        return drained
    }

    private inner class Request<T>(
        private val phase: RuntimeInvocationPhase,
        private val origin: RuntimeInvocationOrigin,
        private val timeoutMillis: Long,
        private val callback: suspend () -> T,
    ) {
        private val result = CompletableDeferred<RuntimeInvocationOutcome<T>>()
        private val executionLock = Any()
        private var execution: Deferred<T>? = null

        val isCompleted: Boolean
            get() = result.isCompleted

        suspend fun await(): RuntimeInvocationOutcome<T> = result.await()

        @Suppress("UNCHECKED_CAST")
        fun cancel(outcome: RuntimeInvocationOutcome<Nothing>) {
            val running = synchronized(executionLock) {
                result.complete(outcome as RuntimeInvocationOutcome<T>)
                execution
            }
            running?.cancel(CancellationException("Runtime invocation cancelled"))
        }

        @Suppress("UNCHECKED_CAST")
        fun complete(outcome: RuntimeInvocationOutcome<*>) {
            synchronized(executionLock) {
                result.complete(outcome as RuntimeInvocationOutcome<T>)
            }
        }

        suspend fun execute() {
            val running = synchronized(stateLock) {
                if (!live || closeResult != null) {
                    null
                } else {
                    synchronized(executionLock) {
                        if (result.isCompleted) {
                            null
                        } else {
                            childWork.scope.async(start = CoroutineStart.LAZY, context = InsideGateExecution.element) {
                                workers.run(callback)
                            }.also { execution = it }
                        }
                    }
                }
            }
            if (running == null) {
                complete(RuntimeInvocationOutcome.Closed)
                return
            }

            running.start()
            val outcome = try {
                withTimeout(timeoutMillis) {
                    RuntimeInvocationOutcome.Success(running.await())
                }
            } catch (_: TimeoutCancellationException) {
                invalidateForTimeout(this)
                complete(RuntimeInvocationOutcome.TimedOut)
                return
            } catch (_: CancellationException) {
                RuntimeInvocationOutcome.Cancelled
            } catch (failure: Throwable) {
                failure.toOutcome(phase, origin)
            } finally {
                synchronized(executionLock) {
                    if (execution === running) execution = null
                }
            }
            complete(outcome)
        }
    }

    private fun Throwable.toOutcome(
        phase: RuntimeInvocationPhase,
        origin: RuntimeInvocationOrigin,
    ): RuntimeInvocationOutcome<Nothing> {
        if (this is VirtualMachineError || this is ThreadDeath || this is LinkageError) throw this
        if (this is RejectedExecutionException) return RuntimeInvocationOutcome.Busy
        val message = when (origin) {
            RuntimeInvocationOrigin.PROVIDER -> "Provider callback failed"
            RuntimeInvocationOrigin.RUNTIME -> "Runtime callback failed"
        }
        val failure = RuntimeInvocationFailure(instanceId, generation, phase, message)
        return when (origin) {
            RuntimeInvocationOrigin.PROVIDER -> RuntimeInvocationOutcome.ProviderFailure(failure)
            RuntimeInvocationOrigin.RUNTIME -> RuntimeInvocationOutcome.RuntimeFailure(failure)
        }
    }
}

/**
 * Marks a coroutine running inside one serialized gate request execution.
 * Continuations resumed from that callback are already serialized by the
 * outer executionMutex; [RuntimeGenerationInvocationGate.invokeContinuation]
 * detects this marker and must not re-acquire the non-reentrant mutex.
 */
private class InsideGateExecution private constructor() : AbstractCoroutineContextElement(InsideGateExecution) {
    companion object Key : CoroutineContext.Key<InsideGateExecution> {
        val element: InsideGateExecution = InsideGateExecution()
    }
}
