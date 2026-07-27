package io.talkcan.lua.actor

import io.talkcan.channel.capability.CapabilityScopeIdentity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicLong

/**
 * Generation-owned background-task scope.
 *
 * Bounded by the generation's lifetime and invocation gate. Tracks every
 * actor task so that generation retirement cancels or drains all descendants
 * and no work escapes the generation to become process-global. Enforces
 * maximum concurrent tasks. A task remains live until its function returns,
 * fails, or the owning generation closes; no generic wall-clock expiry is
 * applied.
 *
 * The scope is a mechanism owned by the runtime. It does not create threads
 * and does not execute outside the generation's invocation gate. It is not a
 * process-global singleton.
 */
internal class ActorTaskScope(
    parentScope: CoroutineScope,
    private val scopeIdentity: CapabilityScopeIdentity,
    private val maxConcurrentTasks: Int,
    // Retained for source compatibility with existing host policy callers.
    // Generic task lifetime cutoffs are intentionally not applied; deadlines
    // belong to individual yielded operations.
    @Suppress("UNUSED_PARAMETER")
    private val perTaskDeadlineMillis: Long?,
) {
    init {
        require(maxConcurrentTasks > 0) { "Max concurrent tasks must be positive" }
    }

    private val supervisorJob = SupervisorJob(
        requireNotNull(parentScope.coroutineContext[Job]) {
            "Actor task scope requires a parent Job"
        },
    )
    private val scope = CoroutineScope(parentScope.coroutineContext + supervisorJob)

    private val stateLock = Any()
    private val tasks = mutableMapOf<ActorTaskId, ActorTaskHandle>()
    private var activeCount = 0
    private var admissionClosed = false
    private val taskCounter = AtomicLong(0L)

    init {
        supervisorJob.invokeOnCompletion {
            synchronized(stateLock) { admissionClosed = true }
        }
    }

    val isActive: Boolean
        get() = synchronized(stateLock) { !admissionClosed && supervisorJob.isActive }

    val activeTaskCount: Int
        get() = synchronized(stateLock) { activeCount }

    val totalTaskCount: Int
        get() = synchronized(stateLock) { tasks.size }

    /**
     * Linearizes capacity admission, task registration, and close against one
     * lock. Completion removes the registration even when cancellation wins
     * before the lazy child has entered its body.
     */
    fun launch(task: suspend (ActorTaskIdentity) -> Unit): ActorTaskIdentity? = synchronized(stateLock) {
        if (admissionClosed || !supervisorJob.isActive || activeCount >= maxConcurrentTasks) return@synchronized null
        val taskId = ActorTaskId(taskCounter.incrementAndGet())
        val identity = ActorTaskIdentity(scope = scopeIdentity, taskId = taskId)
        val job = scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            try {
                task(identity)
            } catch (_: CancellationException) {
                // generation cancelled — expected during retirement
            }
        }
        tasks[taskId] = ActorTaskHandle(taskId)
        activeCount += 1
        job.invokeOnCompletion {
            synchronized(stateLock) {
                if (tasks.remove(taskId) != null) activeCount -= 1
            }
        }
        job.start()
        identity
    }

    /**
     * Launch a timer coroutine for this generation without counting against
     * [maxConcurrentTasks] capacity. The timer is cancelled when the generation
     * retires or closes. Used for operation deadlines.
     */
    fun launchTimer(block: suspend CoroutineScope.() -> Unit) {
        scope.launch { block() }
    }

    fun cancelAll() {
        synchronized(stateLock) {
            admissionClosed = true
            supervisorJob.cancel()
        }
    }

    /**
     * Join all active tasks within a bounded timeout. Returns true if all
     * tasks completed or were cancelled within the bound.
     */
    suspend fun joinAllWithin(timeoutMillis: Long): Boolean {
        cancelAll()
        return withTimeoutOrNull(timeoutMillis) {
            supervisorJob.join()
            true
        } ?: false
    }

    private class ActorTaskHandle(val taskId: ActorTaskId)
}