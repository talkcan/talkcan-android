package io.talkcan.lua

/**
 * Configuration for one Lua kernel state. All fields are internal kernel
 * parameters and MUST NOT become a public plugin compatibility promise.
 *
 * No per-state memory ceiling exists: the LuaJava 4.1.0 binding exposes no
 * per-state allocator hook, so runaway allocation fails as an ordinary
 * engine failure rather than a classified denial.
 *
 * @param hookInterval instruction-count hook interval. The hook interrupts
 *   pure-Lua execution that exceeds [instructionBudget]. Not a public limit.
 * @param instructionBudget total instruction budget for active execution.
 *   Exceeding it normalizes the affected state as interrupted.
 */
internal data class LuaKernelConfig(
    val hookInterval: Int,
    val instructionBudget: Long,
    val maxConcurrentTasks: Int = 16,
    val maxTimerSlots: Int = 16,
) {
    init {
        require(hookInterval > 0) { "Hook interval must be positive" }
        require(instructionBudget >= 0L) { "Instruction budget must be non-negative" }
    }
}