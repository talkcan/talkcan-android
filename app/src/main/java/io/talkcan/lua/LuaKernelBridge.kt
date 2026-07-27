package io.talkcan.lua

/**
 * Semantic bridge interface for the Lua actor kernel. All kernel states use
 * the JVM-owned ownership model, which implements these semantic operations.
 *
 * No method throws for expected input. Every method returns a normalized
 * [LuaKernelOutcome]. Malformed source, invalid handles, stale generations,
 * duplicate completions, and post-close operations return typed outcomes
 * without entering Lua.
 *
 * No native pointer or Lua registry index crosses this boundary. State,
 * coroutine, and operation references are represented by opaque
 * [LuaStateHandle], [LuaCoroutineHandle], and [LuaOperationHandle] validated
 * against their owning state generation.
 *
 * This interface is internal. It is not registered as a provider, does not
 * participate in application startup, and does not alter existing channel
 * behavior.
 */
internal interface LuaKernelBridge {

    /**
     * Create an independent Lua kernel state with the given [config]. Returns
     * [LuaKernelOutcome.Created] on success. The state has its own global
     * environment and module cache.
     */
    fun create(config: LuaKernelConfig): LuaKernelOutcome

    /**
     * Load Lua [source] in text-only mode and validate the named
     * [entrypoint]. Rejects binary chunks, `package.loadlib`, C-module
     * searchers, JNI, FFI, and plugin-provided shared libraries. Returns
     * [LuaKernelOutcome.SyntaxFailure] for malformed source or
     * [LuaKernelOutcome.ValidationFailure] for invalid entrypoint.
     */
    fun load(handle: LuaStateHandle, source: String, entrypoint: String): LuaKernelOutcome

    /**
     * Start the entrypoint of a loaded state under protected execution. The
     * entrypoint may complete, fail, or call internal
     * `talkcan.yield_operation(label)` and yield an opaque operation token.
     */
    fun start(handle: LuaStateHandle): LuaKernelOutcome

    /**
     * Resume a yielded coroutine exactly once with a normalized success or
     * failure value. Duplicate, foreign, stale, and post-close completions
     * return typed outcomes without resuming Lua.
     */
    fun resume(
        operation: LuaOperationHandle,
        success: Boolean,
        value: String,
        spawnAdmission: LuaSpawnAdmission = LuaSpawnAdmission.rejecting(),
    ): LuaKernelOutcome

    /**
     * Cancel a suspended operation token. Cancellation and completion race
     * deterministically; exactly one terminal outcome wins.
     */
    fun cancel(operation: LuaOperationHandle): LuaKernelOutcome

    /**
     * Interrupt active Lua execution via the instruction-count hook. The hook
     * normalizes the affected state as interrupted and permits deterministic
     * teardown. Not claimed to preempt a blocking C or JNI function.
     */
    fun interrupt(handle: LuaStateHandle): LuaKernelOutcome

    /**
     * Snapshot detached state evidence: identity, elapsed execution time,
     * Lua/binding versions, and topology. No allocator accounting is claimed.
     */
    fun snapshot(handle: LuaStateHandle): LuaKernelOutcome

    /**
     * Close a state idempotently. Invalidates all owned coroutine and
     * operation handles atomically before native memory is released. Late
     * completions are rejected as [LuaKernelOutcome.Closed] or
     * [LuaKernelOutcome.Stale].
     */
    fun close(handle: LuaStateHandle): LuaKernelOutcome

    /**
     * Load a Lua program image containing multiple modules, evaluate modules under
     * an effect guard, validate the returned callback table, and return a Completed
     * outcome containing the list of available callback names on success.
     */
    fun loadProgramImage(
        handle: LuaStateHandle,
        entryPoint: String,
        sourceMap: Map<String, String>
    ): LuaKernelOutcome
    /**
     * Invoke the startup callback with one normalized configuration argument.
     * The [config] represents the detached snapshot of the package declaration's
     * validated configuration (`{schema_version = 1, values = {...}}`).
     */
    fun invokeStartupCallback(
        handle: LuaStateHandle,
        callbackHandle: LuaCallbackHandle,
        config: LuaValue,
        spawnAdmission: LuaSpawnAdmission = LuaSpawnAdmission.rejecting(),
    ): LuaKernelOutcome

    /**
     * Invoke a callback function synchronously with the given event arguments.
     * Spawn admission is scoped to this native execution slice.
     */
    fun invokeCallback(
        handle: LuaStateHandle,
        callbackHandle: LuaCallbackHandle,
        arguments: LuaValue,
        spawnAdmission: LuaSpawnAdmission = LuaSpawnAdmission.rejecting(),
    ): LuaKernelOutcome

    /**
     * Invoke handle_input in a host-managed coroutine. Native bridges override
     * this with a coroutine entrypoint; the default keeps lightweight bridges
     * source-compatible while preserving synchronous behavior for other calls.
     */
    fun invokeInputCallback(
        handle: LuaStateHandle,
        callbackHandle: LuaCallbackHandle,
        arguments: LuaValue,
        capturedAudioToken: String,
        spawnAdmission: LuaSpawnAdmission = LuaSpawnAdmission.rejecting(),
    ): LuaKernelOutcome = LuaKernelOutcome.RuntimeFailure(
        stateId = handle.stateId.value,
        generation = handle.generation.value,
        diagnostic = "opaque audio input requires a native kernel bridge",
    )
    /**
     * Invoke handle_sos in a bounded host-managed yield-capable coroutine.
     * A handle_sos that never yields completes in one slice exactly like the
     * synchronous path; a yielded keyboard-output operation surfaces as
     * [LuaKernelOutcome.Yielded] carrying only the opaque request identity,
     * claimed and completed through the same [claimHostOperation]/[resume]
     * path. Sleep, spawn, defer, and raw yields remain rejected for the SOS
     * owner. Lightweight bridges default to a fail-closed runtime failure.
     */
    fun invokeSosCallback(
        handle: LuaStateHandle,
        callbackHandle: LuaCallbackHandle,
        arguments: LuaValue,
        spawnAdmission: LuaSpawnAdmission = LuaSpawnAdmission.rejecting(),
    ): LuaKernelOutcome = LuaKernelOutcome.RuntimeFailure(
        stateId = handle.stateId.value,
        generation = handle.generation.value,
        diagnostic = "yield-capable SOS requires a native kernel bridge",
    )
    /**
     * Start a spawned background coroutine.
     */
    fun startCoroutine(
        handle: LuaStateHandle,
        coroutineId: LuaCoroutineId,
        spawnAdmission: LuaSpawnAdmission = LuaSpawnAdmission.rejecting(),
    ): LuaKernelOutcome
    /**
     * Claim one yielded host-operation request exactly once, returning its typed
     * kind and payload. The [requestId] is the opaque identity the kernel yielded.
     * Unknown, duplicate, stale, cancelled, and closed claims are
     * [HostOperationClaim.Rejected] before any host effect. Lightweight bridges
     * default to rejecting; the native bridge decodes the typed claim result.
     */
    fun claimHostOperation(
        handle: LuaStateHandle,
        requestId: Long,
    ): HostOperationClaim = HostOperationClaim.Rejected("E_HOST_FAILURE")

    /**
     * Install the package resource context: declared `storage.files` capability
     * eligibility and declared mount authority with resolved live status. Called
     * once after construction, before any filesystem operation. Replacing the
     * context invalidates outstanding mount leases in the native kernel.
     * Resource-capable bridges must implement this operation; the default fails
     * closed. The native bridge forwards the JSON to the kernel.
     */
    fun setResourceContext(
        handle: LuaStateHandle,
        resourceContextJson: String,
    ): LuaKernelOutcome = LuaKernelOutcome.RuntimeFailure(
        stateId = handle.stateId.value,
        generation = handle.generation.value,
        diagnostic = "resource context requires a native kernel bridge",
    )

    /**
     * Install the detached selected-profile grant snapshot (task 8.1/8.2):
     * per-profile scalar field values plus opaque Kotlin-minted secret
     * reference tokens. No plaintext, keystore alias, or mutable repository
     * object crosses this boundary. Replacing grants invalidates outstanding
     * secret references (later reads fail stale). Grant-capable bridges must
     * implement this operation; the default fails closed.
     */
    fun setProfileGrants(
        handle: LuaStateHandle,
        grantsJson: String,
    ): LuaKernelOutcome = LuaKernelOutcome.RuntimeFailure(
        stateId = handle.stateId.value,
        generation = handle.generation.value,
        diagnostic = "profile grants require a native kernel bridge",
    )

    /**
     * Create an independent one-shot resolver-mode kernel state (task 10.1).
     * Resolver states bypass channel startup/mailbox/readiness, deny
     * spawn/defer/sleep/work/audio/filesystem/keyboard/lifecycle authority,
     * and always close after one terminal resolver result.
     */
    fun createResolver(config: LuaKernelConfig): LuaKernelOutcome = LuaKernelOutcome.RuntimeFailure(
        stateId = null,
        generation = null,
        diagnostic = "resolver states require a native kernel bridge",
    )

    /**
     * Invoke the declared resolver module exactly once in a resolver-mode
     * state (task 10.2/10.3). The [invocationJson] carries the immutable
     * package source map, the entry module id, the declared capability
     * subset (`secretsRead`, `networkHttp`), and the detached request
     * (`schema_version`, `resolver`, optional `dependency` field/value,
     * optional `profile` id). The invocation may yield SECRET_READ and
     * HTTP_REQUEST host operations, claimed and resumed normally, before
     * completing with choices or a package error.
     */
    fun invokeResolver(
        handle: LuaStateHandle,
        invocationJson: String,
    ): LuaKernelOutcome = LuaKernelOutcome.RuntimeFailure(
        stateId = handle.stateId.value,
        generation = handle.generation.value,
        diagnostic = "resolver invocation requires a native kernel bridge",
    )
}

/** Native callback port: 0 accepted, 1 closed, 2 capacity exhausted. */
internal interface LuaSpawnAdmission {
    fun admitTask(coroutineId: Long): Int

    companion object {
        fun rejecting(): LuaSpawnAdmission = object : LuaSpawnAdmission {
            override fun admitTask(coroutineId: Long): Int = 1
        }
    }
}

/** Generic logical host-operation request kinds claimed from the kernel. */
internal enum class HostOperationKind {
    TRANSCRIBE, SYNTHESIZE, PLAYBACK, AUDIO_OPEN, AUDIO_EXPORT,
    FS_MKDIR, FS_STAT, FS_LIST, FS_READ_TEXT, FS_WRITE_TEXT, FS_REMOVE,
    KEYBOARD_SEND_TEXT, KEYBOARD_SEND_KEY,
    SECRET_READ, HTTP_REQUEST,
    WORK_SUBMIT, WORK_RECEIVE, WORK_BEGIN_EFFECT, WORK_COMMIT_EFFECT,
    WORK_COMPLETE, WORK_FAIL,
}

/**
 * Typed result of claiming one yielded host-operation request. The claim is
 * exactly-once: a request identity that is unknown, duplicate, stale, cancelled,
 * or closed is [Rejected] before any host effect. Admitted claims carry the
 * bounded typed payload fields (never a concatenated label) for the kind.
 */
internal sealed interface HostOperationClaim {
    data class Admitted(
        val requestId: Long,
        val kind: HostOperationKind,
        val audioToken: String?,
        val text: String?,
        val language: String?,
        val voice: String?,
        val speed: Double,
        val delaySeconds: Double,
        // Filesystem payload fields (present only for FS_* kinds).
        val declarationId: String? = null,
        val mountToken: String? = null,
        val path: String? = null,
        val parents: Boolean = false,
        val limit: Long = 0,
        val cursor: String? = null,
        val maxBytes: Long = 0,
        val mode: String? = null,
        val missingOk: Boolean = false,
        val format: String? = null,
        // Keyboard-output payload fields (present only for KEYBOARD_* kinds).
        // `text` above carries the bounded UTF-8 text for KEYBOARD_SEND_TEXT;
        // `profile` is the bounded logical profile id; `key` is exactly
        // "enter" or "escape" for KEYBOARD_SEND_KEY.
        val profile: String? = null,
        val key: String? = null,
        // Protected-secret payload field (present only for SECRET_READ).
        // Kotlin-minted opaque token echoed from state-local SecretReference
        // userdata; never a keystore alias, path, or platform object.
        val referenceToken: String? = null,
        // Generic HTTP payload fields (present only for HTTP_REQUEST).
        // `headersJson` is a bounded JSON object of string header pairs;
        // `body` is the optional bounded UTF-8 request body.
        val method: String? = null,
        val url: String? = null,
        val headersJson: String? = null,
        val body: String? = null,
        val timeoutMs: Long = 0,
        // Durable work payload fields (present only for WORK_* kinds).
        // `queue` is the declared package-local queue id (already Lua-visible
        // as the work.open argument); `job` is an opaque Kotlin-minted token
        // that never appears in Lua-visible data. `payloadJson`, `resultJson`,
        // and `reasonJson` carry the shared tagged WorkValue encoding.
        val queue: String? = null,
        val payloadJson: String? = null,
        val job: String? = null,
        val effectKey: String? = null,
        val fingerprint: String? = null,
        val resultOk: Boolean = false,
        val resultJson: String? = null,
        val reasonJson: String? = null,
    ) : HostOperationClaim

    data class Rejected(val errorCode: String) : HostOperationClaim
}