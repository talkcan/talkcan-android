package io.talkcan.lua

/**
 * Normalized sealed outcome for every kernel bridge operation. Every JNI
 * function returns a JSON object whose `kind` field maps to one of these
 * variants. Malformed or unknown JSON normalizes to
 * [RuntimeFailure] rather than throwing.
 *
 * Required `kind` values from the shared contract:
 * - `created` — state created successfully
 * - `completed` — entrypoint or resume completed
 * - `yielded` — coroutine yielded an opaque operation token
 * - `syntax_failure` — source loading found a syntax error
 * - `validation_failure` — entrypoint or config validation failed
 * - `runtime_failure` — protected Lua callback raised an error
 * - `interrupted` — instruction hook interrupted uncooperative execution
 * - `cancelled` — operation token was cancelled
 * - `invalid_ownership` — handle belongs to another state or unknown handle
 * - `stale` — generation mismatch or duplicate terminal completion
 * - `closed` — state was closed; late completion rejected
 */
internal sealed class LuaKernelOutcome {
    abstract val stateId: Long?
    abstract val generation: Long?

    /** State created successfully. */
    data class Created(
        override val stateId: Long,
        override val generation: Long,
        val luaVersion: String,
        val bindingVersion: String,
        val topology: String,
    ) : LuaKernelOutcome()

    /** Entrypoint or resume completed. */
    data class Completed(
        override val stateId: Long,
        override val generation: Long,
        val coroutineId: Long?,
        val value: String?,
        val elapsedNanos: Long?,
        val luaVersion: String?,
        val bindingVersion: String?,
        val topology: String?,
        val spawnedCoroutines: List<Long>? = null,
        val logs: List<String>? = null,
    ) : LuaKernelOutcome()

    /** Coroutine yielded an opaque operation token and may have admitted ordered child coroutines. */
    data class Yielded(
        override val stateId: Long,
        override val generation: Long,
        val coroutineId: Long,
        val operationId: Long,
        val value: String?,
        val spawnedCoroutines: List<Long>? = null,
        val logs: List<String>? = null,
    ) : LuaKernelOutcome()

    /** Source loading found a syntax error. State remains closable. */
    data class SyntaxFailure(
        override val stateId: Long,
        override val generation: Long,
        val diagnostic: String,
    ) : LuaKernelOutcome()

    /** Entrypoint or config validation failed. State remains closable. */
    data class ValidationFailure(
        override val stateId: Long,
        override val generation: Long,
        val diagnostic: String,
    ) : LuaKernelOutcome()

    /** Protected Lua callback raised an error. Process remains alive. */
    data class RuntimeFailure(
        override val stateId: Long?,
        override val generation: Long?,
        val diagnostic: String,
    ) : LuaKernelOutcome()

    /** Instruction hook interrupted uncooperative pure-Lua execution. */
    data class Interrupted(
        override val stateId: Long,
        override val generation: Long,
        val diagnostic: String?,
        val elapsedNanos: Long?,
    ) : LuaKernelOutcome()

    /** Operation token was cancelled. */
    data class Cancelled(
        override val stateId: Long,
        override val generation: Long,
        val operationId: Long,
    ) : LuaKernelOutcome()

    /** Handle belongs to another state, is unknown, or ownership is invalid. */
    data class InvalidOwnership(
        override val stateId: Long?,
        override val generation: Long?,
        val diagnostic: String,
    ) : LuaKernelOutcome()

    /** Generation mismatch or duplicate terminal completion. No Lua effect. */
    data class Stale(
        override val stateId: Long?,
        override val generation: Long?,
        val diagnostic: String,
    ) : LuaKernelOutcome()

    /**
     * Detached state evidence: identity, elapsed execution time, Lua/binding
     * versions, and topology. Returned by the `snapshot` bridge operation for
     * instrumentation. No per-state allocator accounting is claimed: the
     * LuaJava 4.1.0 binding exposes no per-state allocator hook, and runaway
     * allocation fails as an ordinary engine failure.
     */
    data class Snapshot(
        override val stateId: Long,
        override val generation: Long,
        val elapsedNanos: Long?,
        val luaVersion: String?,
        val bindingVersion: String?,
        val topology: String?,
    ) : LuaKernelOutcome()

    /** State was closed; late completion rejected idempotently. */
    data class Closed(
        override val stateId: Long,
        override val generation: Long,
    ) : LuaKernelOutcome()
}