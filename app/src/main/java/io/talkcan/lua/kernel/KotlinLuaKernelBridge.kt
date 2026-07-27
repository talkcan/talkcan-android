package io.talkcan.lua.kernel

import io.talkcan.lua.HostOperationClaim
import io.talkcan.lua.JsonEncodingResult
import io.talkcan.lua.LuaCallbackHandle
import io.talkcan.lua.LuaCoroutineId
import io.talkcan.lua.LuaKernelBridge
import io.talkcan.lua.LuaKernelConfig
import io.talkcan.lua.LuaKernelOutcome
import io.talkcan.lua.LuaOperationHandle
import io.talkcan.lua.LuaSpawnAdmission
import io.talkcan.lua.LuaStateHandle
import io.talkcan.lua.LuaStateId
import io.talkcan.lua.LuaValue
import java.util.concurrent.ConcurrentHashMap

/**
 * Pure-Kotlin [LuaKernelBridge] backed by one confined [LuaEngineState] per Lua
 * state. Each state owns exactly one `Lua54` interpreter that is created,
 * opened, used, and closed on its own dedicated thread; the bridge never touches
 * an interpreter directly and no native pointer or Lua registry index crosses
 * this boundary.
 *
 * Command flow: state-mutating/observing operations are normalized into typed
 * [EngineCommand]s and submitted to the owning engine's bounded inbox, executing
 * serially on the engine thread. `interrupt` sets an immediate atomic flag (so it
 * can fire while the engine is busy in Lua), and `snapshot` answers from
 * thread-safe engine telemetry without entering Lua. `close` is idempotent and
 * always closes the interpreter on the owner thread.
 *
 * Ownership gate: every operation validates the opaque [LuaStateHandle] against
 * the registry before queueing — unknown states yield
 * [LuaKernelOutcome.InvalidOwnership], generation mismatches and operations on a
 * closed state yield [LuaKernelOutcome.Stale] or [LuaKernelOutcome.Closed]
 * without entering Lua.
 *
 * Zero-arg constructible; instantiated reflectively by the conformance harness.
 */
internal class KotlinLuaKernelBridge : LuaKernelBridge {

    private val states = ConcurrentHashMap<Long, LuaEngineState>()

    private val tombstoneLock = Any()
    private val tombstones = LinkedHashMap<Long, Long>()

    // ------------------------------------------------------------------
    // Creation
    // ------------------------------------------------------------------

    override fun create(config: LuaKernelConfig): LuaKernelOutcome = spawn(config, resolverMode = false)

    override fun createResolver(config: LuaKernelConfig): LuaKernelOutcome = spawn(config, resolverMode = true)

    private fun spawn(config: LuaKernelConfig, resolverMode: Boolean): LuaKernelOutcome {
        val stateId = LuaStateId.next()
        val engine = LuaEngineState(stateId, config, resolverMode)
        return when (val result = engine.start()) {
            is EngineStartResult.Ready -> {
                states[stateId.value] = engine
                LuaKernelOutcome.Created(
                    stateId = stateId.value,
                    generation = engine.generation(),
                    luaVersion = LUA_VERSION,
                    bindingVersion = BINDING_VERSION,
                    topology = TOPOLOGY,
                )
            }
            is EngineStartResult.Failed ->
                LuaKernelOutcome.RuntimeFailure(
                    stateId = null,
                    generation = null,
                    diagnostic = result.diagnostic,
                )
        }
    }

    // ------------------------------------------------------------------
    // Command-routed operations (serialized on the owner thread)
    // ------------------------------------------------------------------

    override fun load(handle: LuaStateHandle, source: String, entrypoint: String): LuaKernelOutcome =
        onLiveState(handle) { it.submit(EngineCommand.Load(source, entrypoint)) }

    override fun start(handle: LuaStateHandle): LuaKernelOutcome =
        onLiveState(handle) { it.submit(EngineCommand.Start()) }

    override fun resume(
        operation: LuaOperationHandle,
        success: Boolean,
        value: String,
        spawnAdmission: LuaSpawnAdmission,
    ): LuaKernelOutcome =
        onOperationState(operation) {
            it.submit(EngineCommand.Resume(operation, success, value, spawnAdmission))
        }

    override fun cancel(operation: LuaOperationHandle): LuaKernelOutcome =
        onOperationState(operation) { it.submit(EngineCommand.Cancel(operation)) }

    override fun loadProgramImage(
        handle: LuaStateHandle,
        entryPoint: String,
        sourceMap: Map<String, String>,
    ): LuaKernelOutcome =
        onLiveState(handle) { it.submit(EngineCommand.LoadProgramImage(entryPoint, sourceMap)) }

    override fun invokeStartupCallback(
        handle: LuaStateHandle,
        callbackHandle: LuaCallbackHandle,
        config: LuaValue,
        spawnAdmission: LuaSpawnAdmission,
    ): LuaKernelOutcome {
        if (callbackHandle.stateHandle != handle) return invalidCallbackOwnership(handle, callbackHandle)
        val configJson = when (val encoding = config.toJsonString()) {
            is JsonEncodingResult.Success -> encoding.json
            is JsonEncodingResult.Failure -> return LuaKernelOutcome.ValidationFailure(
                stateId = handle.stateId.value,
                generation = handle.generation.value,
                diagnostic = encoding.diagnostic,
            )
        }
        return onLiveState(handle) {
            it.submit(EngineCommand.InvokeStartupCallback(callbackHandle.name, configJson, spawnAdmission))
        }
    }

    override fun invokeCallback(
        handle: LuaStateHandle,
        callbackHandle: LuaCallbackHandle,
        arguments: LuaValue,
        spawnAdmission: LuaSpawnAdmission,
    ): LuaKernelOutcome {
        if (callbackHandle.stateHandle != handle) return invalidCallbackOwnership(handle, callbackHandle)
        val argumentsJson = when (val encoding = arguments.toJsonString()) {
            is JsonEncodingResult.Success -> encoding.json
            is JsonEncodingResult.Failure -> return LuaKernelOutcome.ValidationFailure(
                stateId = handle.stateId.value,
                generation = handle.generation.value,
                diagnostic = encoding.diagnostic,
            )
        }
        return onLiveState(handle) {
            it.submit(EngineCommand.InvokeCallback(callbackHandle.name, argumentsJson, spawnAdmission))
        }
    }

    override fun invokeInputCallback(
        handle: LuaStateHandle,
        callbackHandle: LuaCallbackHandle,
        arguments: LuaValue,
        capturedAudioToken: String,
        spawnAdmission: LuaSpawnAdmission,
    ): LuaKernelOutcome {
        if (callbackHandle.stateHandle != handle || callbackHandle.name != "handle_input") {
            return invalidCallbackOwnership(handle, callbackHandle)
        }
        val argumentsJson = when (val encoding = arguments.toJsonString()) {
            is JsonEncodingResult.Success -> encoding.json
            is JsonEncodingResult.Failure -> return LuaKernelOutcome.ValidationFailure(
                stateId = handle.stateId.value,
                generation = handle.generation.value,
                diagnostic = encoding.diagnostic,
            )
        }
        return onLiveState(handle) {
            it.submit(
                EngineCommand.InvokeInputCallback(callbackHandle.name, argumentsJson, capturedAudioToken, spawnAdmission),
            )
        }
    }

    override fun invokeSosCallback(
        handle: LuaStateHandle,
        callbackHandle: LuaCallbackHandle,
        arguments: LuaValue,
        spawnAdmission: LuaSpawnAdmission,
    ): LuaKernelOutcome {
        if (callbackHandle.stateHandle != handle || callbackHandle.name != "handle_sos") {
            return invalidCallbackOwnership(handle, callbackHandle)
        }
        val argumentsJson = when (val encoding = arguments.toJsonString()) {
            is JsonEncodingResult.Success -> encoding.json
            is JsonEncodingResult.Failure -> return LuaKernelOutcome.ValidationFailure(
                stateId = handle.stateId.value,
                generation = handle.generation.value,
                diagnostic = encoding.diagnostic,
            )
        }
        return onLiveState(handle) {
            it.submit(EngineCommand.InvokeSosCallback(callbackHandle.name, argumentsJson, spawnAdmission))
        }
    }

    override fun startCoroutine(
        handle: LuaStateHandle,
        coroutineId: LuaCoroutineId,
        spawnAdmission: LuaSpawnAdmission,
    ): LuaKernelOutcome =
        onLiveState(handle) { it.submit(EngineCommand.StartCoroutine(coroutineId, spawnAdmission)) }

    override fun setResourceContext(handle: LuaStateHandle, resourceContextJson: String): LuaKernelOutcome =
        onLiveState(handle) { it.submit(EngineCommand.SetResourceContext(resourceContextJson)) }

    override fun setProfileGrants(handle: LuaStateHandle, grantsJson: String): LuaKernelOutcome =
        onLiveState(handle) { it.submit(EngineCommand.SetProfileGrants(grantsJson)) }

    override fun invokeResolver(handle: LuaStateHandle, invocationJson: String): LuaKernelOutcome =
        onLiveState(handle) { it.submit(EngineCommand.InvokeResolver(invocationJson)) }

    // ------------------------------------------------------------------
    // Direct (non-queued) operations
    // ------------------------------------------------------------------

    override fun interrupt(handle: LuaStateHandle): LuaKernelOutcome {
        val id = handle.stateId.value
        val engine = states[id] ?: return closedOrUnknown(id, handle.generation.value)
        if (engine.isClosed()) return LuaKernelOutcome.Stale(id, handle.generation.value, "state is closed")
        if (engine.generation() != handle.generation.value) {
            return LuaKernelOutcome.Stale(id, handle.generation.value, "generation mismatch")
        }
        // Set the watchdog immediately, then serialize suspended-continuation
        // terminalization on the state owner thread.
        engine.requestInterrupt()
        return engine.submit(EngineCommand.Interrupt())
    }

    override fun snapshot(handle: LuaStateHandle): LuaKernelOutcome {
        val id = handle.stateId.value
        val engine = states[id]
            ?: return if (isTombstoned(id)) {
                LuaKernelOutcome.Stale(id, handle.generation.value, "state is closed")
            } else {
                LuaKernelOutcome.InvalidOwnership(id, handle.generation.value, "unknown state")
            }
        if (engine.isClosed()) return LuaKernelOutcome.Stale(id, handle.generation.value, "state is closed")
        if (engine.generation() != handle.generation.value) {
            return LuaKernelOutcome.Stale(id, handle.generation.value, "generation mismatch")
        }
        // Only identity, versions, and elapsed-time evidence are published:
        // the binding exposes no per-state allocator hook, so no allocator
        // accounting is claimed.
        return LuaKernelOutcome.Snapshot(
            stateId = id,
            generation = engine.generation(),
            elapsedNanos = engine.elapsedNanos(),
            luaVersion = LUA_VERSION,
            bindingVersion = BINDING_VERSION,
            topology = TOPOLOGY,
        )
    }

    override fun close(handle: LuaStateHandle): LuaKernelOutcome {
        val id = handle.stateId.value
        val engine = states[id]
        if (engine != null) {
            if (engine.generation() != handle.generation.value) {
                // A stale-generation close must not tombstone the live engine.
                return LuaKernelOutcome.Stale(id, handle.generation.value, "stale-generation close")
            }
            val outcome = engine.close()
            // Record the tombstone before removing the live engine so concurrent
            // observers always resolve the id to either a closed engine or a
            // tombstone, never to "unknown".
            recordTombstone(id, engine.generation())
            states.remove(id)
            return outcome
        }
        if (isTombstoned(id)) return LuaKernelOutcome.Closed(id, handle.generation.value)
        return LuaKernelOutcome.InvalidOwnership(id, handle.generation.value, "unknown state")
    }

    override fun claimHostOperation(handle: LuaStateHandle, requestId: Long): HostOperationClaim {
        val id = handle.stateId.value
        val engine = states[id] ?: return HostOperationClaim.Rejected("E_HOST_FAILURE")
        if (engine.isClosed()) return HostOperationClaim.Rejected("E_CLOSED")
        if (engine.generation() != handle.generation.value) return HostOperationClaim.Rejected("E_STALE")
        return engine.claimHostOperation(requestId)
    }

    // ------------------------------------------------------------------
    // Ownership gate
    // ------------------------------------------------------------------

    private fun onOperationState(
        operation: LuaOperationHandle,
        block: (LuaEngineState) -> LuaKernelOutcome,
    ): LuaKernelOutcome {
        val handle = operation.stateHandle
        val id = handle.stateId.value
        val engine = states[id]
            ?: return if (isTombstoned(id)) {
                LuaKernelOutcome.Stale(id, handle.generation.value, "operation state is closed")
            } else {
                LuaKernelOutcome.InvalidOwnership(id, handle.generation.value, "unknown state")
            }
        if (engine.isClosed() || engine.generation() != handle.generation.value) {
            return LuaKernelOutcome.Stale(id, handle.generation.value, "operation state is stale")
        }
        return block(engine)
    }

    private fun onLiveState(
        handle: LuaStateHandle,
        block: (LuaEngineState) -> LuaKernelOutcome,
    ): LuaKernelOutcome {
        val id = handle.stateId.value
        val engine = states[id] ?: return closedOrUnknown(id, handle.generation.value)
        if (engine.isClosed()) return LuaKernelOutcome.Closed(id, handle.generation.value)
        if (engine.generation() != handle.generation.value) {
            return LuaKernelOutcome.Stale(id, handle.generation.value, "generation mismatch")
        }
        return block(engine)
    }

    private fun closedOrUnknown(id: Long, generation: Long): LuaKernelOutcome =
        if (isTombstoned(id)) {
            LuaKernelOutcome.Closed(id, generation)
        } else {
            LuaKernelOutcome.InvalidOwnership(id, generation, "unknown state")
        }

    private fun invalidCallbackOwnership(
        handle: LuaStateHandle,
        callbackHandle: LuaCallbackHandle,
    ): LuaKernelOutcome.InvalidOwnership =
        LuaKernelOutcome.InvalidOwnership(
            stateId = handle.stateId.value,
            generation = handle.generation.value,
            diagnostic = "callback '${callbackHandle.name}' belongs to a different state handle",
        )

    private fun isTombstoned(id: Long): Boolean = synchronized(tombstoneLock) { tombstones.containsKey(id) }

    private fun recordTombstone(id: Long, generation: Long) {
        synchronized(tombstoneLock) {
            tombstones[id] = generation
            while (tombstones.size > TOMBSTONE_CAPACITY) {
                tombstones.remove(tombstones.keys.iterator().next())
            }
        }
    }

    companion object {
        const val TOPOLOGY = "jvm_owned"
        const val BINDING_VERSION = "4.1.0"
        const val LUA_VERSION = "5.4.8"

        private const val TOMBSTONE_CAPACITY = 4096
    }
}
