package io.talkcan.lua

import org.junit.Assert.assertEquals
import org.junit.Test

class LuaKernelHandleContractTest {
    @Test
    fun `operation identity retains its state generation and coroutine owner`() {
        val owningState = LuaStateHandle(LuaStateId(41), LuaStateGeneration(3))
        val ownedOperation = LuaOperationHandle(
            stateHandle = owningState,
            coroutineId = LuaCoroutineId(17),
            operationId = LuaOperationId(29),
        )
        val foreignStateOperation = ownedOperation.copy(
            stateHandle = LuaStateHandle(LuaStateId(42), LuaStateGeneration(3)),
        )
        val staleGenerationOperation = ownedOperation.copy(
            stateHandle = LuaStateHandle(LuaStateId(41), LuaStateGeneration(4)),
        )
        val foreignCoroutineOperation = ownedOperation.copy(coroutineId = LuaCoroutineId(18))

        val distinctTerminalTargets = setOf(
            ownedOperation,
            foreignStateOperation,
            staleGenerationOperation,
            foreignCoroutineOperation,
        )

        assertEquals(
            "An operation identity can only identify a terminal target with its complete owner chain",
            4,
            distinctTerminalTargets.size,
        )
    }

    @Test
    fun `identifier and configuration boundaries reject invalid representations`() {
        val rejected = listOf(
            "zero state id" to { LuaStateId(0) },
            "negative state id" to { LuaStateId(-1) },
            "negative generation" to { LuaStateGeneration(-1) },
            "zero coroutine id" to { LuaCoroutineId(0) },
            "zero operation id" to { LuaOperationId(0) },
            "negative hook interval" to {
                LuaKernelConfig(-1, 1)
            },
            "zero hook interval" to {
                LuaKernelConfig(0, 1)
            },
            "negative instruction budget" to {
                LuaKernelConfig(1, -1)
            },
        )

        rejected.forEach { (name, buildInvalidValue) ->
            try {
                buildInvalidValue()
                throw AssertionError("$name must be rejected before a bridge call")
            } catch (_: IllegalArgumentException) {
                // Expected: Kotlin rejects an invalid opaque representation locally.
            }
        }
    }

}
