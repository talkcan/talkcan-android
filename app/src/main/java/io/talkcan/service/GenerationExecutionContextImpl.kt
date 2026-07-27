package io.talkcan.model

import io.talkcan.channel.capability.CapabilityScopeIdentity
import io.talkcan.lua.actor.ActorGateCommit
import io.talkcan.lua.actor.ActorGateResult
import io.talkcan.lua.actor.ActorGenerationGate
import io.talkcan.service.RuntimeGenerationInvocationGate
import io.talkcan.service.RuntimeInvocationOutcome
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*

internal class GenerationExecutionContextImpl(
    override val instanceId: String,
    private val runtimeGate: RuntimeGenerationInvocationGate,
    parentScope: CoroutineScope,
    private val timerDelay: suspend (Long) -> Unit = { delay(it) },
) : ActorRuntimeHostContext {

    private enum class State {
        STAGED,
        ACTIVE,
        CLOSED
    }

    private val lock = Any()
    private var state = State.STAGED

    // Context-owned work is a child of the registry generation lifecycle.
    private val supervisorJob = SupervisorJob(parentScope.coroutineContext[Job])
    private val contextScope = CoroutineScope(parentScope.coroutineContext + supervisorJob)

    override val actorIdentity = CapabilityScopeIdentity(instanceId, runtimeGate.generation)
    override val actorParentScope: CoroutineScope = contextScope
    override val actorGate: ActorGenerationGate = object : ActorGenerationGate {
        override fun isLive(): Boolean = isActive() && runtimeGate.isLive

        override fun <T> commitIfLive(action: () -> T): ActorGateCommit<T> = when {
            !isActive() -> ActorGateCommit.Closed
            else -> when (val outcome = runtimeGate.commitIfLive(action)) {
                is RuntimeInvocationOutcome.Success -> ActorGateCommit.Success(outcome.value)
                else -> ActorGateCommit.Closed
            }
        }

        override suspend fun <T> runContinuation(action: suspend () -> T): ActorGateResult<T> = when {
            !isActive() -> ActorGateResult.Closed
            else -> when (val outcome = runtimeGate.invokeContinuation(action)) {
                is RuntimeInvocationOutcome.Success -> ActorGateResult.Success(outcome.value)
                RuntimeInvocationOutcome.Closed -> ActorGateResult.Closed
                RuntimeInvocationOutcome.Cancelled -> ActorGateResult.Cancelled
                RuntimeInvocationOutcome.TimedOut -> ActorGateResult.TimedOut(0)
                else -> ActorGateResult.Closed
            }
        }

        override fun isAdmissionStopped(): Boolean = !isActive() || runtimeGate.isAdmissionStopped
    }

    private val stagedTasks = mutableListOf<suspend () -> Unit>()
    private val activeTasks = mutableSetOf<Job>()
    private val activeTimers = mutableMapOf<Disposable, Job>()

    companion object {
        private const val MAX_CONCURRENT_TIMERS = 64
        private const val MAX_CONCURRENT_TASKS = 64
    }

    override fun isActive(): Boolean = synchronized(lock) {
        state != State.CLOSED && runtimeGate.isLive && !runtimeGate.isAdmissionStopped
    }

    override fun scheduleTimer(
        delaySeconds: Double,
        callback: suspend () -> Unit,
    ): GenerationAdmission<Disposable> {
        synchronized(lock) {
            if (!isActive()) {
                return GenerationAdmission.Rejected(GenerationAdmissionRejection.CLOSED)
            }
            if (activeTimers.size >= MAX_CONCURRENT_TIMERS) {
                return GenerationAdmission.Rejected(GenerationAdmissionRejection.CAPACITY_EXHAUSTED)
            }

            val fired = AtomicBoolean(false)
            val disposable = object : Disposable {
                private val disposed = AtomicBoolean(false)
                override fun dispose() {
                    if (disposed.compareAndSet(false, true)) {
                        cancelTimer(this)
                    }
                }
            }

            val delayMillis = (delaySeconds * 1000).toLong().coerceAtLeast(0L)
            val job = contextScope.launch(start = CoroutineStart.LAZY) {
                try {
                    timerDelay(delayMillis)
                    if (fired.compareAndSet(false, true)) {
                        val shouldRun = synchronized(lock) {
                            state == State.ACTIVE && isActive()
                        }
                        if (shouldRun) {
                            callback()
                        }
                    }
                } finally {
                    synchronized(lock) {
                        activeTimers.remove(disposable)
                    }
                }
            }
            activeTimers[disposable] = job
            job.start()
            return GenerationAdmission.Accepted(disposable)
        }
    }

    override fun admitTask(task: suspend () -> Unit): GenerationAdmission<Unit> {
        synchronized(lock) {
            if (!isActive()) {
                return GenerationAdmission.Rejected(GenerationAdmissionRejection.CLOSED)
            }
            when (state) {
                State.CLOSED -> error("inactive generation context cannot be active")
                State.STAGED -> {
                    if (stagedTasks.size + activeTasks.size >= MAX_CONCURRENT_TASKS) {
                        return GenerationAdmission.Rejected(GenerationAdmissionRejection.CAPACITY_EXHAUSTED)
                    }
                    stagedTasks.add(task)
                    return GenerationAdmission.Accepted(Unit)
                }
                State.ACTIVE -> {
                    if (activeTasks.size >= MAX_CONCURRENT_TASKS) {
                        return GenerationAdmission.Rejected(GenerationAdmissionRejection.CAPACITY_EXHAUSTED)
                    }
                    launchTaskLocked(task).start()
                    return GenerationAdmission.Accepted(Unit)
                }
            }
        }
    }

    private fun launchTaskLocked(task: suspend () -> Unit): Job {
        val job = contextScope.launch(start = CoroutineStart.LAZY) {
            try {
                task()
            } finally {
                removeActiveTask(this.coroutineContext[Job]!!)
            }
        }
        activeTasks.add(job)
        return job
    }

    private fun removeActiveTask(job: Job) {
        synchronized(lock) {
            activeTasks.remove(job)
        }
    }

    private fun cancelTimer(disposable: Disposable) {
        val job = synchronized(lock) {
            activeTimers.remove(disposable)
        }
        job?.cancel()
    }

    /**
     * Atomically transitions staged work to active and returns already-tracked,
     * lazy jobs. The registry starts them after releasing its structural lock;
     * a concurrent retirement cancels these jobs before they can enter task code.
     */
    fun authorizeStagedTasksAfterReady(): List<Job> = synchronized(lock) {
        if (state != State.STAGED) return@synchronized emptyList()
        state = State.ACTIVE
        stagedTasks.map(::launchTaskLocked).also { stagedTasks.clear() }
    }

    override fun discardActorStagedTasks() {
        synchronized(lock) {
            stagedTasks.clear()
        }
    }
    /** Stops task/timer admission synchronously while the registry retires its entry. */
    fun stopAdmission() {
        synchronized(lock) {
            if (state == State.CLOSED) return
            state = State.CLOSED
            stagedTasks.clear()
            supervisorJob.cancel()
        }
    }


    fun discardStagedTasks() = discardActorStagedTasks()

    suspend fun closeAndDrain() {
        val jobsToJoin = synchronized(lock) {
            state = State.CLOSED
            stagedTasks.clear()
            val timersJobs = activeTimers.values.toList()
            activeTimers.clear()
            val tasksJobs = activeTasks.toList()
            activeTasks.clear()
            supervisorJob.cancel()
            timersJobs + tasksJobs
        }
        withContext(NonCancellable) {
            jobsToJoin.forEach { it.join() }
        }
    }
}
