package io.talkcan.lua

import java.util.concurrent.atomic.AtomicLong

/**
 * Opaque identifier for a Lua kernel state.
 *
 * Carries no native pointer or Lua registry index. The underlying [value] is a
 * process-local monotonic `Long` assigned by the bridge; it is never derived
 * from a `lua_State*` or native allocation address. Inline so that no boxing
 * or wrapper allocation crosses the JNI boundary.
 */
@JvmInline
internal value class LuaStateId(val value: Long) {
    init {
        require(value > 0L) { "State id must be positive" }
    }

    companion object {
        private val counter = AtomicLong(0L)

        fun next(): LuaStateId = LuaStateId(counter.incrementAndGet())
    }
}

/**
 * Opaque generation of a Lua kernel state. Every mutation that invalidates
 * descendant coroutine and operation handles increments the generation.
 * Stale-generation completions return a typed [LuaKernelOutcome.Stale] without
 * entering Lua.
 */
@JvmInline
internal value class LuaStateGeneration(val value: Long) {
    init {
        require(value >= 0L) { "State generation must be non-negative" }
    }

    companion object {
        fun initial(): LuaStateGeneration = LuaStateGeneration(0L)
    }
}

/**
 * Opaque identifier for a Lua kernel coroutine. Carries its owning [stateId]
 * so the bridge can reject foreign-state handles without dereferencing native
 * memory.
 */
@JvmInline
internal value class LuaCoroutineId(val value: Long) {
    init {
        require(value > 0L) { "Coroutine id must be positive" }
    }

    companion object {
        private val counter = AtomicLong(0L)

        fun next(): LuaCoroutineId = LuaCoroutineId(counter.incrementAndGet())
    }
}

/**
 * Opaque identifier for a suspended kernel operation token. Each token accepts
 * at most one terminal resume, cancellation, or close outcome.
 */
@JvmInline
internal value class LuaOperationId(val value: Long) {
    init {
        require(value > 0L) { "Operation id must be positive" }
    }

    companion object {
        private val counter = AtomicLong(0L)

        fun next(): LuaOperationId = LuaOperationId(counter.incrementAndGet())
    }
}