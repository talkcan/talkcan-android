package io.talkcan.lua.actor

import io.talkcan.channel.capability.CapabilityScopeIdentity
import io.talkcan.channel.capability.RevocableChannelCapabilityScope
import io.talkcan.lua.LuaCoroutineId
import io.talkcan.lua.LuaKernelOutcome
import io.talkcan.lua.LuaKernelBridge
import io.talkcan.lua.LuaKernelConfig
import io.talkcan.lua.LuaOperationHandle
import io.talkcan.lua.LuaOperationId
import io.talkcan.lua.LuaStateGeneration
import io.talkcan.lua.LuaStateHandle
import io.talkcan.lua.LuaStateId
import io.talkcan.service.ChannelExecutionStatus
import io.talkcan.service.ChannelPreparationAvailability
import io.talkcan.service.ChannelPreparationReason
import io.talkcan.service.ChannelRuntimeSnapshot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import io.talkcan.lua.LuaSpawnAdmission
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One bounded, serialized Lua actor for one runtime generation.
 *
 * The actor owns one Lua state, one event mailbox, one ready-coroutine
 * scheduler, a set of suspended operation tokens, and a background-task
 * scope. All are bounded by the generation's lifetime and the generation's
 * invocation gate.
 *
 * The actor does not outlive its generation. It is not a process-global
 * singleton, not shared across instances, and not transferable between
 * generations. It creates no process-global scope or thread. It uses only
 * bounded structures.
 *
 * The actor does not expose raw Kotlin coroutine jobs, Android callbacks, or
 * SDK futures to Lua. Host callbacks and actor continuations both cross the
 * opaque bridge boundary as normalized outcomes and opaque handles.
 */
internal class ActorRuntime(
    private val scope: CapabilityScopeIdentity,
    private val bridge: LuaKernelBridge,
    private val gate: ActorGenerationGate,
    private val policy: ActorPolicy,
    private val capabilityScope: RevocableChannelCapabilityScope?,
    parentScope: CoroutineScope,
) {
    private val latch = ActorLifecycleLatch()
    private val mailbox = ActorMailbox(policy.mailboxCapacity)
    private val scheduler = ActorScheduler()
    private val diagnostics = ActorDiagnostics()
    private val capabilityMediator: ActorCapabilityMediator? =
        capabilityScope?.let { ActorCapabilityMediator(it) }
    private val parentScope = parentScope
    private val taskScope = ActorTaskScope(
        parentScope = parentScope,
        scopeIdentity = scope,
        maxConcurrentTasks = policy.maxConcurrentTasks,
        perTaskDeadlineMillis = policy.perTaskDeadlineMillis,
    )

    private val suspendedTokens = ConcurrentHashMap<ActorOperationId, ActorOperationToken>()
    private var stateHandle: LuaStateHandle? = null
    private val closeOwner = AtomicBoolean(false)
    private val closeFinished = CompletableDeferred<Unit>()
    private var startupOperationIdentity: ActorOperationIdentity? = null
    // Registration and release of direct gate continuations are synchronized
    // with close ownership. The completed signal represents zero admitted
    // continuations; it is reset on the 0→1 transition and completed on 1→0.
    private val gateContinuationLock = Any()
    private var activeGateContinuations = 0
    private var gateContinuationsIdle = CompletableDeferred<Unit>().apply { complete(Unit) }

    val lifecyclePhase: ActorLifecyclePhase
        get() = latch.phase

    val isReady: Boolean
        get() = latch.isReady

    val isFailed: Boolean
        get() = latch.isFailed

    val isClosed: Boolean
        get() = closeOwner.get() || latch.isClosed

    /** Marks a successfully constructed program-image actor as staged. */
    fun stageProgramImage(): Boolean = latch.stage()

    /** Claims the single startup transition for a program-image adapter. */
    fun claimProgramImageStartup(): Boolean = latch.start()

    /** Publishes a program-image actor only after all activation callbacks succeed. */
    fun publishProgramImageLive(): Boolean {
        if (!latch.publishReady()) return false
        mailbox.open()
        return true
    }

    /** Latches adapter-side activation or callback contract failure. */
    fun latchProgramImageFailure() {
        mailbox.retire()
        mailbox.invalidateQueued()
        latch.latchFailed()
    }

    /** Invokes startup with configuration while the program image is starting, fenced against actor close. */
    suspend fun invokeProgramImageStartup(
        callback: io.talkcan.lua.LuaCallbackHandle,
        config: io.talkcan.lua.LuaValue,
        spawnAdmission: LuaSpawnAdmission,
    ): ActorGateResult<LuaKernelOutcome> {
        val handle = stateHandle ?: return ActorGateResult.Closed
        if (latch.phase != ActorLifecyclePhase.STARTING || isClosed) return ActorGateResult.Closed
        return trackGateContinuation { bridge.invokeStartupCallback(handle, callback, config, spawnAdmission) }
    }

    /** Invokes a named program-image callback while the actor is starting or live. */
    suspend fun invokeProgramImageCallback(
        callback: io.talkcan.lua.LuaCallbackHandle,
        arguments: io.talkcan.lua.LuaValue,
        spawnAdmission: LuaSpawnAdmission,
    ): ActorGateResult<LuaKernelOutcome> {
        val handle = stateHandle ?: return ActorGateResult.Closed
        if ((latch.phase != ActorLifecyclePhase.STARTING && !latch.isLive) || isClosed) {
            return ActorGateResult.Closed
        }
        return trackGateContinuation { bridge.invokeCallback(handle, callback, arguments, spawnAdmission) }
    }

    /**
     * Invokes handle_input through the host-managed coroutine bridge. A yielded
     * native operation returns immediately, releasing the actor continuation
     * slot; only the exact owner may later call [resumeProgramImageCoroutine].
     */
    suspend fun invokeProgramImageInputCallback(
        callback: io.talkcan.lua.LuaCallbackHandle,
        arguments: io.talkcan.lua.LuaValue,
        capturedAudioToken: String,
        spawnAdmission: LuaSpawnAdmission,
    ): ActorGateResult<LuaKernelOutcome> {
        val handle = stateHandle ?: return ActorGateResult.Closed
        if (!latch.isLive || isClosed) return ActorGateResult.Closed
        return trackGateContinuation {
            bridge.invokeInputCallback(handle, callback, arguments, capturedAudioToken, spawnAdmission)
        }
    }
    /**
     * Invokes handle_sos in a bounded host-managed yield-capable coroutine
     * while the actor is live. A handle_sos that never yields completes in
     * one slice exactly like the synchronous path; a yielded keyboard-output
     * request surfaces as [LuaKernelOutcome.Yielded] and is driven by the
     * caller through the same claim/resume path used for input.
     */
    suspend fun invokeProgramImageSosCallback(
        callback: io.talkcan.lua.LuaCallbackHandle,
        arguments: io.talkcan.lua.LuaValue,
        spawnAdmission: LuaSpawnAdmission,
    ): ActorGateResult<LuaKernelOutcome> {
        val handle = stateHandle ?: return ActorGateResult.Closed
        if (!latch.isLive || isClosed) return ActorGateResult.Closed
        return trackGateContinuation { bridge.invokeSosCallback(handle, callback, arguments, spawnAdmission) }
    }

    /** Starts one host-admitted background coroutine through the generation gate. */
    suspend fun startProgramImageCoroutine(
        coroutineId: LuaCoroutineId,
        spawnAdmission: LuaSpawnAdmission,
    ): ActorGateResult<LuaKernelOutcome> {
        val handle = stateHandle ?: return ActorGateResult.Closed
        if (!latch.isLive || isClosed) return ActorGateResult.Closed
        return runGateContinuation { bridge.startCoroutine(handle, coroutineId, spawnAdmission) }
    }

    /** Resumes one exact background operation through the generation gate. */
    suspend fun resumeProgramImageCoroutine(
        operation: LuaOperationHandle,
        success: Boolean,
        value: String,
        spawnAdmission: LuaSpawnAdmission,
    ): ActorGateResult<LuaKernelOutcome> {
        if (!latch.isLive || isClosed) return ActorGateResult.Closed
        return runGateContinuation { bridge.resume(operation, success, value, spawnAdmission) }
    }

    /** Computes the operation-specific Lua sleep deadline from host policy. */
    fun calculateSleepDeadline(requestedMillis: Long): Long? =
        ActorPolicy.calculateSleepDeadline(requestedMillis, policy.timerSlackMillis)

    private sealed class ActorQuiescenceResult {
        data object Quiescent : ActorQuiescenceResult()
        data object TimedOut : ActorQuiescenceResult()
    }

    /**
     * Tracks a direct bridge entry already admitted by the registry execution
     * gate. It deliberately does not invoke [ActorGenerationGate] again:
     * synchronous runtime callbacks hold that non-reentrant gate already.
     */
    private suspend fun <T> trackGateContinuation(action: suspend () -> T): ActorGateResult<T> {
        val admitted = synchronized(gateContinuationLock) {
            if (closeOwner.get()) {
                false
            } else {
                if (activeGateContinuations == 0) {
                    gateContinuationsIdle = CompletableDeferred()
                }
                activeGateContinuations += 1
                true
            }
        }
        if (!admitted) return ActorGateResult.Closed
        return try {
            ActorGateResult.Success(action())
        } finally {
            val idle = synchronized(gateContinuationLock) {
                activeGateContinuations -= 1
                gateContinuationsIdle.takeIf { activeGateContinuations == 0 }
            }
            idle?.complete(Unit)
        }
    }

    /** Admits a background continuation through the generation gate once. */
    private suspend fun <T> runGateContinuation(action: suspend () -> T): ActorGateResult<T> = when (
        val tracked = trackGateContinuation { gate.runContinuation(action) }
    ) {
        is ActorGateResult.Success -> tracked.value
        ActorGateResult.Closed -> ActorGateResult.Closed
        ActorGateResult.Cancelled -> ActorGateResult.Cancelled
        is ActorGateResult.TimedOut -> tracked
        ActorGateResult.AlreadyCompleted -> ActorGateResult.AlreadyCompleted
    }

    /**
     * Wait for both the scheduler entry and all continuations admitted before
     * close. Each transition completes a resettable signal, so the deadline
     * uses coroutine time and never polls or performs overflow-prone nano-time
     * arithmetic.
     */
    private suspend fun awaitLuaQuiescence(timeoutMillis: Long): ActorQuiescenceResult {
        require(timeoutMillis > 0) { "Timeout must be positive" }
        return withTimeoutOrNull(timeoutMillis) {
            scheduler.awaitIdleSignal()
            val signal = synchronized(gateContinuationLock) { gateContinuationsIdle }
            signal.await()
            ActorQuiescenceResult.Quiescent
        } ?: ActorQuiescenceResult.TimedOut
    }

    val mailboxDepth: Int
        get() = mailbox.size

    val diagnosticsSnapshot: ActorDiagnosticsSnapshot
        get() = diagnostics.snapshot()

    /**
     * (7) Internal host projection seam mapping readiness/fatal latch to
     * [ChannelRuntimeSnapshot] semantics without registering a provider.
     * The host calls this to project actor state through the existing
     * snapshot mechanism.
     */
    fun projectSnapshot(
        id: String,
        name: String,
        implementationId: io.talkcan.model.ChannelImplementationId,
    ): ChannelRuntimeSnapshot {
        val preparation = when (latch.phase) {
            ActorLifecyclePhase.LIVE ->
                ChannelPreparationAvailability.Available
            ActorLifecyclePhase.FAILED ->
                ChannelPreparationAvailability.Unavailable(ChannelPreparationReason.RuntimeFailed())
            ActorLifecyclePhase.CLOSED ->
                ChannelPreparationAvailability.Unavailable(ChannelPreparationReason.RuntimeClosed)
            else ->
                ChannelPreparationAvailability.Recoverable(ChannelPreparationReason.ProviderInitialising)
        }
        val executionStatus = if (latch.isFailed) {
            ChannelExecutionStatus.FAILED
        } else {
            ChannelExecutionStatus.IDLE
        }
        return ChannelRuntimeSnapshot(
            id = id,
            name = name,
            implementationId = implementationId,
            enabled = latch.phase != ActorLifecyclePhase.CLOSED,
            preparation = preparation,
            executionStatus = executionStatus,
            summary = null,
            pendingCount = mailboxDepth,
            playbackPaused = false,
        )
    }

    /**
     * Construct the actor's independent Lua state. Called during
     * [ActorLifecyclePhase.CONSTRUCTING].
     */
    fun construct(): ActorConstructResult {
        if (latch.phase != ActorLifecyclePhase.CONSTRUCTING) {
            return ActorConstructResult.AlreadyConstructed
        }
        val config = LuaKernelConfig(
            hookInterval = policy.luaKernelConfig.hookInterval,
            instructionBudget = policy.luaKernelConfig.instructionBudget,
        )
        return when (val outcome = bridge.create(config)) {
            is LuaKernelOutcome.Created -> {
                val handle = LuaStateHandle(
                    stateId = LuaStateId(outcome.stateId),
                    generation = LuaStateGeneration(outcome.generation),
                )
                stateHandle = handle
                ActorConstructResult.Success(handle)
            }
            is LuaKernelOutcome.RuntimeFailure -> {
                latch.latchFailed()
                ActorConstructResult.FatalFailure("runtime failure during construction: ${outcome.diagnostic}")
            }
            else -> {
                latch.latchFailed()
                ActorConstructResult.FatalFailure("unexpected kernel outcome during construction")
            }
        }
    }

    /**
     * Load Lua source in text-only mode and validate the entrypoint. Called
     * during [ActorLifecyclePhase.CONSTRUCTING]; transitions to
     * [ActorLifecyclePhase.STAGED] on success.
     */
    fun loadSource(source: String, entrypoint: String): ActorLoadResult {
        val handle = stateHandle ?: return ActorLoadResult.NotConstructed
        return when (val outcome = bridge.load(handle, source, entrypoint)) {
            is LuaKernelOutcome.ValidationFailure -> {
                ActorLoadResult.ValidationFailure(outcome.diagnostic)
            }
            is LuaKernelOutcome.SyntaxFailure -> {
                ActorLoadResult.SyntaxFailure(outcome.diagnostic)
            }
            is LuaKernelOutcome.Completed -> {
                latch.stage()
                ActorLoadResult.Loaded
            }
            else -> {
                latch.latchFailed()
                ActorLoadResult.FatalFailure("unexpected kernel outcome during load")
            }
        }
    }

    /**
     * Execute the bounded protected startup entry authorized by the host.
     * Called during [ActorLifecyclePhase.STARTING]. If startup reports ready,
     * transitions to [ActorLifecyclePhase.LIVE] and opens the mailbox.
     *
     * A startup coroutine suspended on an operation remains initializing until
     * it resumes and reports ready. The coroutine ID from the yielded outcome
     * is retained on the token so [resumeStartup] can reconstruct the exact
     * [LuaOperationHandle] when resuming.
     */
    suspend fun startup(): ActorStartupResult {
        if (!latch.start()) return ActorStartupResult.NotStaged
        val handle = stateHandle ?: return ActorStartupResult.NotConstructed

        return when (val outcome = bridge.start(handle)) {
            is LuaKernelOutcome.Completed -> {
                latch.publishReady()
                mailbox.open()
                startupOperationIdentity = null
                ActorStartupResult.Ready
            }
            is LuaKernelOutcome.Yielded -> {
                // Startup suspension retains the exact kernel-issued handle.
                // The actor-facing identity remains a separate opaque host ID;
                // it is never converted into a native operation ID.
                val kernelHandle = LuaOperationHandle(
                    stateHandle = handle,
                    coroutineId = LuaCoroutineId(outcome.coroutineId),
                    operationId = LuaOperationId(outcome.operationId),
                )
                val opId = ActorOperationId.next()
                val identity = ActorOperationIdentity(
                    scope = scope,
                    taskId = ActorTaskIdentity(scope = scope, taskId = ActorTaskId.next()),
                    operationId = opId,
                )
                startupOperationIdentity = identity
                suspendedTokens[opId] = ActorOperationToken(identity, kernelHandle)
                ActorStartupResult.Suspended(identity)
            }
            is LuaKernelOutcome.RuntimeFailure -> {
                latch.latchFailed()
                ActorStartupResult.FatalFailure("startup runtime failure: ${outcome.diagnostic}")
            }
            is LuaKernelOutcome.Interrupted -> {
                latch.latchFailed()
                ActorStartupResult.FatalFailure("startup interrupted: ${outcome.diagnostic}")
            }
            else -> {
                latch.latchFailed()
                ActorStartupResult.FatalFailure("unexpected kernel outcome during startup")
            }
        }
    }

    /**
     * Resume a suspended startup coroutine. Called when the host completes,
     * cancels, or fails the startup operation.
     *
     * The bridge resume/cancel is invoked *before* publishing ready or failure,
     * using the exact coroutine ID retained from the original yield. If the
     * bridge reports that the startup coroutine yielded again, the startup
     * remains suspended with a fresh operation identity and token.
     */
    suspend fun resumeStartup(
        operationIdentity: ActorOperationIdentity,
        terminal: ActorTerminal,
    ): ActorStartupResult {
        if (!gate.isLive() || isClosed) return ActorStartupResult.FatalFailure("actor closed")
        if (startupOperationIdentity != operationIdentity) {
            return ActorStartupResult.FatalFailure("startup operation identity mismatch")
        }

        val token = suspendedTokens[operationIdentity.operationId]
            ?: return ActorStartupResult.FatalFailure("startup operation not found")

        // Terminal identity agreement
        val terminalIdentity = terminal.identity
        if (terminalIdentity != null && terminalIdentity != operationIdentity) {
            return ActorStartupResult.FatalFailure("startup terminal identity mismatch")
        }

        val result = token.complete(terminal)
        return when (result) {
            is ActorTerminalResult.Completed -> {
                suspendedTokens.remove(operationIdentity.operationId)
                startupOperationIdentity = null

                if (stateHandle == null) {
                    return ActorStartupResult.FatalFailure("startup state not constructed")
                }
                val operationHandle = token.kernelHandle

                // Invoke bridge.resume or bridge.cancel before publishing ready/failure
                val gateResult = runGateContinuation<LuaKernelOutcome> {
                    when (terminal) {
                        is ActorTerminal.Cancelled -> bridge.cancel(operationHandle)
                        is ActorTerminal.Completed -> bridge.resume(operationHandle, true, terminal.value)
                        is ActorTerminal.Failed -> bridge.resume(operationHandle, false, terminal.diagnostic)
                        is ActorTerminal.TimedOut -> bridge.resume(operationHandle, false, "startup timed out")
                        is ActorTerminal.Closed, is ActorTerminal.Stale -> bridge.cancel(operationHandle)
                    }
                }

                when (gateResult) {
                    is ActorGateResult.Success -> {
                        when (val outcome = gateResult.value) {
                            is LuaKernelOutcome.Completed -> {
                                latch.publishReady()
                                mailbox.open()
                                ActorStartupResult.Ready
                            }
                            is LuaKernelOutcome.Yielded -> {
                                // Startup coroutine re-suspended: retain the
                                // exact new kernel handle, while assigning a
                                // fresh opaque actor-facing identity.
                                val newKernelHandle = LuaOperationHandle(
                                    stateHandle = operationHandle.stateHandle,
                                    coroutineId = LuaCoroutineId(outcome.coroutineId),
                                    operationId = LuaOperationId(outcome.operationId),
                                )
                                val newOpId = ActorOperationId.next()
                                val newIdentity = ActorOperationIdentity(
                                    scope = scope,
                                    taskId = ActorTaskIdentity(scope = scope, taskId = ActorTaskId.next()),
                                    operationId = newOpId,
                                )
                                startupOperationIdentity = newIdentity
                                suspendedTokens[newOpId] = ActorOperationToken(newIdentity, newKernelHandle)
                                ActorStartupResult.Suspended(newIdentity)
                            }
                            is LuaKernelOutcome.Cancelled -> {
                                latch.latchFailed()
                                ActorStartupResult.FatalFailure("startup cancelled by kernel")
                            }
                            is LuaKernelOutcome.RuntimeFailure -> {
                                latch.latchFailed()
                                ActorStartupResult.FatalFailure("startup runtime failure: ${outcome.diagnostic}")
                            }
                            is LuaKernelOutcome.Interrupted -> {
                                latch.latchFailed()
                                ActorStartupResult.FatalFailure("startup interrupted")
                            }
                            is LuaKernelOutcome.Stale -> {
                                latch.latchFailed()
                                ActorStartupResult.FatalFailure("startup stale handle: ${outcome.diagnostic}")
                            }
                            else -> {
                                latch.latchFailed()
                                ActorStartupResult.FatalFailure("unexpected kernel outcome during startup resume")
                            }
                        }
                    }
                    ActorGateResult.Closed, ActorGateResult.Cancelled -> {
                        latch.latchFailed()
                        ActorStartupResult.FatalFailure("startup gate closed")
                    }
                    ActorGateResult.AlreadyCompleted -> {
                        latch.latchFailed()
                        ActorStartupResult.FatalFailure("startup gate already completed")
                    }
                    is ActorGateResult.TimedOut -> {
                        latch.latchFailed()
                        ActorStartupResult.FatalFailure("startup gate timed out")
                    }
                }
            }
            is ActorTerminalResult.AlreadyCompleted -> {
                ActorStartupResult.FatalFailure("startup operation already completed")
            }
        }
    }

    /**
     * Admit one ordinary mailbox event. Before readiness, refuses with a
     * typed not-ready result and does not queue. After retirement or closure,
     * rejects with [ActorMailboxResult.Closed].
     *
     * Both the envelope scope and a durable callback's embedded durable-run
     * scope must belong to this runtime. A foreign value is rejected before
     * mailbox admission, so it cannot reach a Lua slice.
     */
    fun admitEvent(envelope: ActorEventEnvelope): ActorMailboxResult {
        if (envelope.identity.scope != scope) return ActorMailboxResult.InvalidOwner
        if (envelope is ActorEventEnvelope.DurableRunCallback && envelope.durableRun.scope != scope) {
            return ActorMailboxResult.InvalidOwner
        }
        return mailbox.admit(envelope)
    }

    /**
     * Register one yielded operation token. Called when a Lua coroutine
     * yields an internal `yield_operation` and receives an opaque operation
     * token. The token is generation-safe and accepts at most one terminal
     * completion, cancellation, or close outcome.
     *
     * @param task the actor task identity that yielded.
     * @param kernelHandle the exact [LuaOperationHandle] from the bridge's
     *   [LuaKernelOutcome.Yielded], carrying the kernel-issued operation ID,
     *   coroutine ID, and owning state handle. Resume/cancel passes this
     *   identical handle to the bridge — the actor never fabricates a native
     *   operation ID.
     * @return the opaque [ActorOperationIdentity] the host uses to complete,
     *   cancel, or close the operation. The actor-facing identity is
     *   separate from the kernel handle and never exposes native IDs.
     */
    suspend fun yieldOperation(
        task: ActorTaskIdentity,
        kernelHandle: LuaOperationHandle,
        deadlineMillis: Long = policy.operationWaitDeadlineMillis,
    ): ActorOperationIdentity {
        require(deadlineMillis >= 0) { "Operation deadline must be non-negative" }
        val operationId = ActorOperationId.next()
        val identity = ActorOperationIdentity(
            scope = scope,
            taskId = task,
            operationId = operationId,
        )
        val token = ActorOperationToken(identity, kernelHandle)
        suspendedTokens[operationId] = token
        // (2) Schedule exactly-once typed timeout at deadlineMillis
        // without charging active budget. Uses the generation's timer scope
        // independent of max background-task capacity.
        taskScope.launchTimer {
            kotlinx.coroutines.delay(deadlineMillis)
            if (!token.isCompleted && gate.isLive() && !isClosed) {
                resumeOperation(identity, ActorTerminal.TimedOut(identity))
            }
        }
        return identity
    }

    /**
     * Resume one suspended operation token. The completion is delivered back
     * as an actor continuation under the generation's live-state gate.
     *
     * A completion, cancellation, or close request bearing a forged, stale,
     * foreign, or closed identifier returns a typed terminal outcome without
     * entering Lua or producing another native effect.
     *
     * The exact identity (scope, taskId, operationId) and terminal identity
     * agreement (when non-null) are verified before the token is terminalized.
     * Forged same-scope/different-task identities return [ActorOperationOutcome.InvalidOwner]
     * without consuming the token.
     */
    suspend fun resumeOperation(
        operationIdentity: ActorOperationIdentity,
        terminal: ActorTerminal,
    ): ActorOperationOutcome {
        // (1) Closed check before token lookup: post-close requests must
        // return Closed, not Stale.
        if (!gate.isLive() || isClosed) return ActorOperationOutcome.Closed

        val token = suspendedTokens[operationIdentity.operationId]
            ?: return ActorOperationOutcome.Stale

        // (6) Exact identity check: forged same-scope/different-task identity
        // must be rejected BEFORE the token is terminalized or Lua entered.
        // Identity includes scope, taskId, and operationId.
        if (token.identity != operationIdentity) return ActorOperationOutcome.InvalidOwner

        // Terminal identity agreement: the terminal's identity (when non-null)
        // must match the supplied operation identity.
        val terminalIdentity = terminal.identity
        if (terminalIdentity != null && terminalIdentity != operationIdentity) {
            return ActorOperationOutcome.InvalidOwner
        }

        val result = token.complete(terminal)
        return when (result) {
            is ActorTerminalResult.Completed -> {
                suspendedTokens.remove(operationIdentity.operationId)
                // (3) Winning completion/cancel must call LuaKernelBridge.resume
                // or cancel exactly once under the continuation gate, using the
                // exact kernel handle retained at yield time. The actor never
                // fabricates a native operation ID — the handle carries the
                // kernel-issued operation ID, coroutine ID, and state handle.
                //
                // Resumed is reported ONLY when the bridge resume/cancel
                // actually executed under a live gate (Success). If the gate
                // is Closed/Cancelled/TimedOut the native continuation never
                // ran; a dead/closed generation must not report a false
                // success, so the outcome is mapped to the typed terminal
                // (Closed/Stale) that preserves exactly-once ownership and
                // no-retry-for-closed-generation semantics. A missing state
                // handle likewise never ran and maps to Stale.
                if (stateHandle == null) {
                    return ActorOperationOutcome.Stale
                }
                val operationHandle = token.kernelHandle
                val gateResult = when (terminal) {
                    is ActorTerminal.Cancelled -> {
                        runGateContinuation<LuaKernelOutcome> {
                            bridge.cancel(operationHandle)
                        }
                    }
                    else -> {
                        val success = terminal is ActorTerminal.Completed
                        val value = when (terminal) {
                            is ActorTerminal.Completed -> terminal.value
                            is ActorTerminal.Failed -> terminal.diagnostic
                            is ActorTerminal.TimedOut -> "E_TIMEOUT"
                            else -> ""
                        }
                        runGateContinuation<LuaKernelOutcome> {
                            bridge.resume(operationHandle, success, value)
                        }
                    }
                }
                when (gateResult) {
                    is ActorGateResult.Success -> {
                        // A continuation may have entered before close won
                        // ownership. Its bridge call is serialized with close,
                        // but once the tombstone is published the host must
                        // not observe a late Resumed result/effect.
                        if (isClosed) ActorOperationOutcome.Closed
                        else ActorOperationOutcome.Resumed(result.terminal)
                    }
                    ActorGateResult.Closed -> {
                        // Generation closed under continuation: the bridge
                        // effect did not execute. No retry is permitted for a
                        // closed generation; report Closed.
                        ActorOperationOutcome.Closed
                    }
                    ActorGateResult.Cancelled -> {
                        // Continuation was cancelled (slot busy/foreign
                        // identity): native effect did not run. The token is
                        // already terminalized, so a retry cannot re-enter;
                        // report Stale so the host does not observe a false
                        // success.
                        ActorOperationOutcome.Stale
                    }
                    is ActorGateResult.TimedOut -> {
                        // Continuation deadline elapsed before the bridge
                        // effect ran. No native effect; report Stale.
                        ActorOperationOutcome.Stale
                    }
                    ActorGateResult.AlreadyCompleted -> {
                        // Gate observed a prior terminal for this generation;
                        // no second slice ran.
                        ActorOperationOutcome.AlreadyCompleted
                    }
                }
            }
            is ActorTerminalResult.AlreadyCompleted -> {
                ActorOperationOutcome.AlreadyCompleted
            }
        }
    }

    /**
     * Cancel one suspended operation token. Cancellation and completion race
     * deterministically; exactly one terminal outcome wins.
     */
    suspend fun cancelOperation(
        operationIdentity: ActorOperationIdentity,
    ): ActorOperationOutcome {
        return resumeOperation(operationIdentity, ActorTerminal.Cancelled(operationIdentity))
    }

    /**
     * Dispatch the next admitted mailbox event exactly once under the
     * generation's single-entry gate and scheduler.
     *
     * Polls the mailbox in FIFO order and, if the gate permits, creates an
     * [ActorCoroutine] for the envelope, enqueues it with the scheduler,
     * enters it under the generation's single-entry lock, and returns the
     * dispatch outcome. If the coroutine yields, the operation identity is
     * returned and the coroutine remains suspended for a future resume.
     *
     * Returns [ActorDispatchResult.Empty] when the mailbox is empty.
     * Returns [ActorDispatchResult.Closed] when the gate is not live.
     * Returns [ActorDispatchResult.InvalidOwner] when a dequeued envelope
     * belongs to a foreign scope; the slice is never invoked.
     */
    suspend fun dispatchNext(
        slice: suspend (ActorEventEnvelope) -> ActorCoroutineResult,
    ): ActorDispatchResult {
        val envelope = mailbox.poll() ?: return ActorDispatchResult.Empty
        // Revalidate both the envelope and embedded durable-run owners at
        // dequeue. This keeps a forged pre-existing entry from invoking Lua.
        if (envelope.identity.scope != scope) return ActorDispatchResult.InvalidOwner
        if (envelope is ActorEventEnvelope.DurableRunCallback && envelope.durableRun.scope != scope) {
            return ActorDispatchResult.InvalidOwner
        }
        if (!gate.isLive()) return ActorDispatchResult.Closed

        val taskIdentity = ActorTaskIdentity(scope = scope, taskId = ActorTaskId.next())
        val coroutine = ActorCoroutine(
            ownerTask = taskIdentity,
            owningOperation = null,
        ) {
            slice(envelope)
        }
        scheduler.ready(coroutine)

        return when (val gateResult = runGateContinuation<ActorCoroutineResult?> {
            if (!scheduler.tryEnterExact(coroutine)) {
                return@runGateContinuation null
            }
            try {
                coroutine.slice(coroutine)
            } finally {
                scheduler.release()
            }
        }) {
            is ActorGateResult.Success -> {
                when (val result = gateResult.value) {
                    is ActorCoroutineResult.Completed -> ActorDispatchResult.Completed(result.value)
                    is ActorCoroutineResult.Yielded -> ActorDispatchResult.Yielded(result.operation)
                    is ActorCoroutineResult.Failed -> {
                        if (result.fatal) ActorDispatchResult.FatalFailure(result.diagnostic)
                        else ActorDispatchResult.LocalFailure(result.diagnostic)
                    }
                    null -> ActorDispatchResult.Empty
                }
            }
            ActorGateResult.Closed, ActorGateResult.Cancelled -> ActorDispatchResult.Closed
            ActorGateResult.AlreadyCompleted -> ActorDispatchResult.Closed
            is ActorGateResult.TimedOut -> ActorDispatchResult.Closed
        }
    }

    /**
     * Classify and apply a failure outcome.
     *
     * Protected ordinary failures from an event handler or background task
     * terminate and report that event or task without latching an otherwise
     * sound actor. A failed yielded operation resumes its owning coroutine
     * with a normalized failure and does not by itself latch the actor.
     *
     * Startup, lifecycle-critical, instruction, ownership-integrity,
     * or another generation-fatal outcome latches the actor as failed,
     * prevents further Lua entry, projects the actor as unavailable, and
     * closes the failed generation deterministically without terminating
     * unrelated actors.
     *
     * The runtime makes no containment claim for native engine or bridge
     * defects, unprotected panic, native memory corruption, unrecoverable
     * process OOM, or Android process death. The actor treats unrecognized
     * native defects as generation-fatal and relies on the host to retire
     * and restart.
     */
    fun classifyFailure(failure: ActorFailureClassification): ActorFailureResult {
        return if (failure.fatal) {
            // (4) Fatal failure must atomically stop/invalidate mailbox
            // so admission returns Closed, not NotReady.
            mailbox.retire()
            mailbox.invalidateQueued()
            latch.latchFailed()
            ActorFailureResult.FatalLatched
        } else {
            ActorFailureResult.LocalContained
        }
    }

    /**
     * Interrupt active Lua execution via the instruction-count hook. The
     * hook normalizes the affected state as interrupted and permits
     * deterministic teardown. Not claimed to preempt a blocking C or JNI
     * function.
     */
    fun interrupt(): ActorInterruptResult {
        val handle = stateHandle ?: return ActorInterruptResult.NotConstructed
        return when (bridge.interrupt(handle)) {
            is LuaKernelOutcome.Interrupted -> ActorInterruptResult.Interrupted
            else -> ActorInterruptResult.Failed
        }
    }

    /**
     * Snapshot internal diagnostics observations (instructions, latency).
     */
    fun snapshotDiagnostics(): ActorDiagnosticsSnapshot = diagnostics.snapshot()

    /**
     * Retire the actor: stop admission, invalidate queued events, cancel
     * descendants, revoke scope. Called during replacement or shutdown.
     *
     * A late completion bearing this generation is classified as stale
     * without entering the successor's Lua state. Use-after-revocation
     * returns Closed/Cancelled without effect.
     */
    suspend fun retire() {
        latch.retire()
        // (5) Retire must return Closed for admission, not NotReady.
        mailbox.retire()
        mailbox.invalidateQueued()
        taskScope.cancelAll()
        suspendedTokens.values.forEach { token ->
            token.complete(ActorTerminal.Closed)
        }
        suspendedTokens.clear()
        capabilityMediator?.revoke()
    }

    /**
     * Drain the actor after retirement: join background tasks within the
     * close bound, then transition to drained.
     */
    suspend fun drain() {
        taskScope.joinAllWithin(policy.closeTimeoutMillis)
        latch.drain()
    }

    /**
     * Close the actor atomically.
     *
     * The winning caller transitions admission through RETIRED, cancels owned
     * descendants and tokens, then awaits all pre-close Lua entry claims. A
     * quiescent actor closes directly. On deadline expiry it transitions the
     * native state through INTERRUPTED before terminal close: the bridge
     * serializes each per-state operation, so the interrupted native action
     * returns before [LuaKernelBridge.close] releases memory. The close tombstone
     * rejects every late bridge effect, and [stateHandle] is cleared afterward.
     * No cleanup coroutine or process-global work is created.
     */
    suspend fun close(): ActorCloseResult {
        if (!closeOwner.compareAndSet(false, true)) {
            closeFinished.await()
            return ActorCloseResult.AlreadyClosed
        }

        synchronized(gateContinuationLock) {}
        return try {
            withContext(NonCancellable) {
                latch.retire()
                mailbox.stopAdmission()
                mailbox.invalidateQueued()
                scheduler.close()
                taskScope.cancelAll()
                taskScope.joinAllWithin(policy.closeTimeoutMillis)
                suspendedTokens.values.forEach { token -> token.complete(ActorTerminal.Closed) }
                suspendedTokens.clear()
                capabilityMediator?.revoke()
                val quiescence = awaitLuaQuiescence(policy.closeTimeoutMillis)
                stateHandle?.let { handle ->
                    if (quiescence is ActorQuiescenceResult.TimedOut) bridge.interrupt(handle)
                    bridge.close(handle)
                    stateHandle = null
                }
                latch.close()
                mailbox.close()
                when (quiescence) {
                    ActorQuiescenceResult.Quiescent -> ActorCloseResult.Closed
                    ActorQuiescenceResult.TimedOut -> ActorCloseResult.ClosedWithTimeout
                }
            }
        } finally {
            closeFinished.complete(Unit)
        }
    }

    /**
     * The authoritative [CapabilityScopeIdentity] for this actor.
     */
    val identity: CapabilityScopeIdentity
        get() = scope

    /**
     * The capability mediator for host-effect resolution, or null if no
     * capability scope was supplied. The host adapter uses this to mediate
     * yielded host operations through the existing
     * [io.talkcan.channel.capability.RevocableChannelCapabilityScope]
     * lease use.
     */
    val capabilities: ActorCapabilityMediator?
        get() = capabilityMediator

    /**
     * The cooperative scheduler. The host adapter uses this to resume
     * continuations under the generation's live-state gate.
     */
    val actorScheduler: ActorScheduler
        get() = scheduler
}

internal sealed class ActorConstructResult {
    data class Success(val stateHandle: LuaStateHandle) : ActorConstructResult()
    data object AlreadyConstructed : ActorConstructResult()
    data class FatalFailure(val diagnostic: String) : ActorConstructResult()
}

internal sealed class ActorLoadResult {
    data object Loaded : ActorLoadResult()
    data object NotConstructed : ActorLoadResult()
    data class ValidationFailure(val diagnostic: String) : ActorLoadResult()
    data class SyntaxFailure(val diagnostic: String) : ActorLoadResult()
    data class FatalFailure(val diagnostic: String) : ActorLoadResult()
}

internal sealed class ActorStartupResult {
    data object Ready : ActorStartupResult()
    data class Suspended(val operationIdentity: ActorOperationIdentity) : ActorStartupResult()
    data class FatalFailure(val diagnostic: String) : ActorStartupResult()
    data object NotStaged : ActorStartupResult()
    data object NotConstructed : ActorStartupResult()
}

internal sealed class ActorInterruptResult {
    data object Interrupted : ActorInterruptResult()
    data object Failed : ActorInterruptResult()
    data object NotConstructed : ActorInterruptResult()
}

internal sealed class ActorCloseResult {
    /** All Lua entry claims released before terminal close. */
    data object Closed : ActorCloseResult()

    /** Quiescence timed out; native state was interrupted then serially closed. */
    data object ClosedWithTimeout : ActorCloseResult()

    data object AlreadyClosed : ActorCloseResult()
}

/**
 * Typed outcome for a [ActorRuntime.dispatchNext] call.
 */
internal sealed class ActorDispatchResult {
    /** Mailbox was empty; no event to dispatch. */
    data object Empty : ActorDispatchResult()
    /** Event slice completed successfully. */
    data class Completed(val value: String?) : ActorDispatchResult()
    /** Event slice yielded; the operation is awaiting host completion. */
    data class Yielded(val operation: ActorOperationIdentity) : ActorDispatchResult()
    /** Event slice failed locally (non-fatal). */
    data class LocalFailure(val diagnostic: String) : ActorDispatchResult()
    /** Event slice produced a fatal failure. */
    data class FatalFailure(val diagnostic: String) : ActorDispatchResult()
    /** Actor is closed or gate is not live. */
    data object Closed : ActorDispatchResult()
    /** Dequeued envelope belongs to a foreign scope; slice never invoked. */
    data object InvalidOwner : ActorDispatchResult()
}

internal sealed class ActorFailureClassification {
    data class Instruction(val diagnostic: String) : ActorFailureClassification() {
        override val fatal: Boolean get() = true
    }

    data class Ownership(val diagnostic: String) : ActorFailureClassification() {
        override val fatal: Boolean get() = true
    }

    data class NativeEngine(val diagnostic: String) : ActorFailureClassification() {
        override val fatal: Boolean get() = true
    }

    data class Startup(val diagnostic: String) : ActorFailureClassification() {
        override val fatal: Boolean get() = true
    }

    data class OrdinaryEvent(val diagnostic: String) : ActorFailureClassification() {
        override val fatal: Boolean get() = false
    }

    abstract val fatal: Boolean
}

internal sealed class ActorFailureResult {
    data object FatalLatched : ActorFailureResult()
    data object LocalContained : ActorFailureResult()
}
