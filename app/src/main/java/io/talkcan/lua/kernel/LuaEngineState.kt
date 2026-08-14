package io.talkcan.lua.kernel

import io.talkcan.lua.HostOperationClaim
import io.talkcan.lua.LuaKernelConfig
import io.talkcan.lua.LuaKernelOutcome
import io.talkcan.lua.LuaStateId
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import party.iroiro.luajava.lua54.Lua54

/**
 * One confined Lua kernel state and the single dedicated thread that owns it.
 *
 * The thread is the only code that ever constructs, opens, uses, or closes the
 * [Lua54] interpreter; the interpreter reference never leaves [runLoop], so
 * cross-thread Lua access is impossible by construction. Commands enter through
 * a fixed bounded [inbox]; host-operation output flows through a separate
 * bounded typed [outbox]. Every command executes serially on the owner thread
 * behind [LuaStateThreadGuard].
 *
 * Lifecycle: [start] launches the owner thread, which creates and opens the
 * interpreter and then publishes readiness or a creation failure through a
 * latch. [close] is idempotent (compare-and-set) and always closes the
 * interpreter on the owner thread, never on the caller's thread, waiting a
 * bounded time for the thread to terminate.
 */
internal class LuaEngineState(
    val stateId: LuaStateId,
    val config: LuaKernelConfig,
    val resolverMode: Boolean,
) {

    private val generation = AtomicLong(0L)
    private val inbox = ArrayBlockingQueue<EngineCommand>(INBOX_CAPACITY)
    private val outbox = HostOperationOutbox(OUTBOX_CAPACITY)

    /** Watchdog flag; set atomically by `interrupt` from any thread. The trusted
     *  bootstrap's instruction-count hook (installed by a later kernel task)
     *  observes it and unwinds pure-Lua execution to the protected boundary. */
    private val interruptFlag = AtomicBoolean(false)

    private val shutdownRequested = AtomicBoolean(false)
    private val closedFlag = AtomicBoolean(false)
    private val readinessLatch = CountDownLatch(1)
    private val creationFailure = AtomicReference<String?>(null)
    private val createdAtNanos = AtomicLong(System.nanoTime())

    @Volatile
    private var thread: Thread? = null

    fun generation(): Long = generation.get()

    fun isClosed(): Boolean = closedFlag.get()

    fun elapsedNanos(): Long = System.nanoTime() - createdAtNanos.get()

    /** Immediate, atomic interrupt request; safe from any thread. */
    fun requestInterrupt() {
        interruptFlag.set(true)
    }

    fun interruptRequested(): Boolean = interruptFlag.get()
    fun resetInterruptRequest() {
        interruptFlag.set(false)
    }

    /**
     * Launch the owner thread and wait a bounded time for the interpreter to be
     * created and opened (or for creation to fail). The engine is registered by
     * the bridge only after this reports [EngineStartResult.Ready].
     */
    fun start(): EngineStartResult {
        val worker = Thread({ runLoop() }, "talkcan-lua-engine-${stateId.value}").apply {
            isDaemon = true
        }
        thread = worker
        worker.start()
        if (!readinessLatch.await(START_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            shutdownRequested.set(true)
            worker.interrupt()
            return EngineStartResult.Failed("engine readiness timed out after ${START_TIMEOUT_MS}ms")
        }
        val failure = creationFailure.get()
        return if (failure == null) EngineStartResult.Ready else EngineStartResult.Failed(failure)
    }

    /**
     * Enqueue one command for owner-thread execution and block a bounded time
     * for its normalized outcome. A saturated inbox is rejected with a typed
     * [LuaKernelOutcome.RuntimeFailure] rather than blocking unboundedly.
     */
    fun submit(command: EngineCommand): LuaKernelOutcome {
        if (closedFlag.get()) {
            return LuaKernelOutcome.Closed(stateId.value, generation())
        }
        if (!inbox.offer(command)) {
            return LuaKernelOutcome.RuntimeFailure(
                stateId = stateId.value,
                generation = generation(),
                diagnostic = "engine mailbox is saturated",
            )
        }
        return command.awaitReply(REPLY_TIMEOUT_MS)
            ?: LuaKernelOutcome.RuntimeFailure(
                stateId = stateId.value,
                generation = generation(),
                diagnostic = "engine did not answer before deadline",
            )
    }

    /**
     * Claim one yielded host-operation request. The bounded typed [outbox] is
     * the seam the host round-trip task populates and drains; until that task
     * installs the typed claim mapping, every claim is rejected before any host
     * effect.
     */
    fun claimHostOperation(requestId: Long): HostOperationClaim =
        outbox.claim(requestId)?.claim ?: HostOperationClaim.Rejected("E_HOST_FAILURE")

    fun registerHostOperation(request: HostOperationRequest): Boolean =
        outbox.offer(request)

    fun hasHostOperation(requestId: Long): Boolean = outbox.peek(requestId) != null
    fun hostOperationKind(requestId: Long): io.talkcan.lua.HostOperationKind? =
        outbox.peek(requestId)?.kind
    fun hostOperationClaim(requestId: Long): io.talkcan.lua.HostOperationClaim.Admitted? =
        outbox.peek(requestId)?.claim

    fun discardHostOperation(requestId: Long) {
        outbox.claim(requestId)
    }

    /**
     * Idempotently close the state. The first caller wins the compare-and-set,
     * signals the owner thread, and waits a bounded time for it to close the
     * interpreter and exit; later callers observe the flag and return
     * [LuaKernelOutcome.Closed] immediately. Lua is never closed on the caller
     * thread.
     */
    fun close(): LuaKernelOutcome {
        if (!closedFlag.compareAndSet(false, true)) {
            return LuaKernelOutcome.Closed(stateId.value, generation())
        }
        shutdownRequested.set(true)
        val worker = thread
        worker?.interrupt()
        try {
            worker?.join(SHUTDOWN_JOIN_MS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        return LuaKernelOutcome.Closed(stateId.value, generation())
    }

    /** Owner-thread run loop: create and open the interpreter, then drain commands. */
    private fun runLoop() {
        val ownerGuard = LuaStateThreadGuard.capture()
        val lua = try {
            Lua54().apply { openLibraries() }
        } catch (t: Throwable) {
            creationFailure.set("lua state creation failed: ${t.message ?: t.javaClass.simpleName}")
            readinessLatch.countDown()
            return
        }
        val runtime = try {
            LuaEngineRuntime(lua, this).also { engineRuntime ->
                LuaBootstrapInstaller.install(lua, this, engineRuntime)
            }
        } catch (t: Throwable) {
            creationFailure.set(
                "trusted Lua bootstrap failed: ${t.message ?: t.javaClass.simpleName}",
            )
            runCatching { lua.close() }
            readinessLatch.countDown()
            return
        }
        createdAtNanos.set(System.nanoTime())
        readinessLatch.countDown()
        try {
            while (!shutdownRequested.get()) {
                val command = try {
                    inbox.poll(POLL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                } catch (e: InterruptedException) {
                    // Woken for shutdown (or spuriously); the loop condition
                    // rechecks shutdownRequested on the next iteration.
                    null
                }
                if (command == null) continue
                val outcome = try {
                    dispatch(command, ownerGuard, runtime)
                } catch (t: Throwable) {
                    LuaKernelOutcome.RuntimeFailure(
                        stateId = stateId.value,
                        generation = generation(),
                        diagnostic = "engine dispatch failure: ${t.message ?: t.javaClass.simpleName}",
                    )
                }
                command.complete(outcome)
            }
        } finally {
            ownerGuard.checkOwner()
            // Reject every still-queued command so no caller blocks on a reply.
            while (true) {
                val pending = inbox.poll()
                if (pending == null) break
                pending.complete(LuaKernelOutcome.Closed(stateId.value, generation()))
            }
            outbox.clear()
            runtime.releaseReferences()
            runCatching { lua.close() }
        }
    }

    /**
     * Execute one command on the owner thread. The guard check makes owner-thread
     * execution an invariant of every command; later kernel tasks replace the
     * per-command handlers below with real protected Lua execution without
     * changing the inbox/dispatch/reply topology.
     */
    private fun dispatch(
        command: EngineCommand,
        ownerGuard: LuaStateThreadGuard,
        runtime: LuaEngineRuntime,
    ): LuaKernelOutcome {
        ownerGuard.checkOwner()
        return when (command) {
            is EngineCommand.Load -> runtime.load(command.source, command.entrypoint)
            is EngineCommand.Start -> runtime.start()
            is EngineCommand.Resume ->
                runtime.resume(command.operation, command.success, command.value, command.spawnAdmission)
            is EngineCommand.Cancel -> runtime.cancel(command.operation)
            is EngineCommand.Interrupt -> runtime.interruptSuspended()
            is EngineCommand.LoadProgramImage ->
                runtime.loadProgramImage(command.entryPoint, command.sourceMap)
            is EngineCommand.InvokeStartupCallback ->
                runtime.invokeCallback(
                    command.callbackName,
                    command.configJson,
                    command.spawnAdmission,
                    SchedulerContext.STARTUP,
                )
            is EngineCommand.InvokeCallback ->
                runtime.invokeCallback(
                    command.callbackName,
                    command.argumentsJson,
                    command.spawnAdmission,
                    when (command.callbackName) {
                        "startup" -> SchedulerContext.STARTUP
                        "handle_input" -> SchedulerContext.INPUT
                        "handle_capture_lifecycle" -> SchedulerContext.CAPTURE_LIFECYCLE
                        "handle_sos" -> SchedulerContext.SOS
                        else -> SchedulerContext.OTHER
                    },
                )
            is EngineCommand.InvokeInputCallback ->
                runtime.invokeCallback(
                    command.callbackName,
                    command.argumentsJson,
                    command.spawnAdmission,
                    SchedulerContext.INPUT,
                    command.capturedAudioToken,
                )
            is EngineCommand.InvokeSosCallback ->
                runtime.invokeCallback(
                    command.callbackName,
                    command.argumentsJson,
                    command.spawnAdmission,
                    SchedulerContext.SOS,
                )
            is EngineCommand.StartCoroutine ->
                runtime.startCoroutine(command.coroutineId, command.spawnAdmission)
            is EngineCommand.SetResourceContext ->
                runtime.installResourceContext(command.resourceContextJson)
            is EngineCommand.SetProfileGrants ->
                runtime.installProfileGrants(command.grantsJson)
            is EngineCommand.InvokeResolver -> runtime.invokeResolver(command.invocationJson)
        }
    }

    private fun unhandled(operation: String): LuaKernelOutcome =
        LuaKernelOutcome.RuntimeFailure(
            stateId = stateId.value,
            generation = generation(),
            diagnostic = "$operation requires the Kotlin Lua execution layer",
        )

    companion object {
        /** Fixed bounded command inbox capacity per state. */
        const val INBOX_CAPACITY = 256

        /** Fixed bounded host-operation outbox capacity per state. */
        const val OUTBOX_CAPACITY = 64

        private const val START_TIMEOUT_MS = 30_000L
        private const val REPLY_TIMEOUT_MS = 30_000L
        private const val SHUTDOWN_JOIN_MS = 5_000L
        private const val POLL_TIMEOUT_MS = 100L
    }
}

/** Result of [LuaEngineState.start]: interpreter ready, or a creation failure. */
internal sealed class EngineStartResult {
    object Ready : EngineStartResult()
    data class Failed(val diagnostic: String) : EngineStartResult()
}
