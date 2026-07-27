package io.talkcan.lua.actor

/**
 * Internal configurable resource policy supplied at actor construction.
 *
 * This is host-owned internal configuration, not plugin configuration. All
 * values are internal policy and MUST NOT become a public plugin compatibility
 * promise. Policy values are evidence-derived and may be remeasured against
 * real internal workloads.
 *
 * @param luaKernelConfig per-state Lua kernel configuration (hook interval,
 *   instruction budget).
 * @param mailboxCapacity bounded event mailbox capacity; reject with Busy when
 *   full, matching the gate's bounded queue.
 * @param activeSliceBudgetMillis active execution budget for pure-Lua
 *   computation while the VM is entered. Not charged for yielded wait time.
 * @param operationWaitDeadlineMillis deadline for a yielded operation before it
 *   is completed with a typed timed-out result.
 * @param callbackTimeoutMillis callback deadline for one host-callback Lua
 *   slice.
 * @param closeTimeoutMillis bound for terminal actor close including joining
 *   background tasks and invoking terminal Lua close.
 * @param maxConcurrentTasks maximum concurrent background tasks in the
 *   generation-owned task scope.
 * @param perTaskDeadlineMillis retained for source compatibility with older
 *   host policy construction; generic task wall-clock expiry is not applied.
 * @param timerSlackMillis host-configured non-negative timer margin (slack)
 *   in milliseconds used for operation-specific sleep deadlines.
 */
internal data class ActorPolicy(
    val luaKernelConfig: ActorKernelConfig,
    val mailboxCapacity: Int,
    val activeSliceBudgetMillis: Long,
    val operationWaitDeadlineMillis: Long,
    val callbackTimeoutMillis: Long,
    val closeTimeoutMillis: Long,
    val maxConcurrentTasks: Int,
    val perTaskDeadlineMillis: Long?,
    val timerSlackMillis: Long = 0L,
) {
    init {
        require(mailboxCapacity > 0) { "Mailbox capacity must be positive" }
        require(activeSliceBudgetMillis > 0) { "Active slice budget must be positive" }
        require(operationWaitDeadlineMillis > 0) { "Operation wait deadline must be positive" }
        require(callbackTimeoutMillis > 0) { "Callback timeout must be positive" }
        require(closeTimeoutMillis > 0) { "Close timeout must be positive" }
        require(maxConcurrentTasks > 0) { "Max concurrent tasks must be positive" }
        require(perTaskDeadlineMillis == null || perTaskDeadlineMillis > 0) { "Per-task deadline must be positive" }
        require(timerSlackMillis >= 0) { "Timer slack must be non-negative" }
    }

    companion object {
        /**
         * Representative starting policy derived from the proof's evidence.
         * These are NOT public compatibility promises; they are starting
         * evidence that may be remeasured against real workloads.
         */
        fun startingEvidence(
            hookInterval: Int = DEFAULT_HOOK_INTERVAL,
            instructionBudget: Long = DEFAULT_INSTRUCTION_BUDGET,
            mailboxCapacity: Int = DEFAULT_MAILBOX_CAPACITY,
        ): ActorPolicy = ActorPolicy(
            luaKernelConfig = ActorKernelConfig(
                hookInterval = hookInterval,
                instructionBudget = instructionBudget,
            ),
            mailboxCapacity = mailboxCapacity,
            activeSliceBudgetMillis = DEFAULT_ACTIVE_SLICE_BUDGET_MILLIS,
            operationWaitDeadlineMillis = DEFAULT_OPERATION_WAIT_DEADLINE_MILLIS,
            callbackTimeoutMillis = DEFAULT_CALLBACK_TIMEOUT_MILLIS,
            closeTimeoutMillis = DEFAULT_CLOSE_TIMEOUT_MILLIS,
            maxConcurrentTasks = DEFAULT_MAX_CONCURRENT_TASKS,
            // Kept as a compatibility value; ActorTaskScope intentionally ignores it.
            perTaskDeadlineMillis = DEFAULT_PER_TASK_DEADLINE_MILLIS,
            timerSlackMillis = DEFAULT_TIMER_SLACK_MILLIS,
        )

        // Starting evidence — NOT normative limits.
        private const val DEFAULT_HOOK_INTERVAL = 500
        private const val DEFAULT_INSTRUCTION_BUDGET = 1_000_000L
        private const val DEFAULT_MAILBOX_CAPACITY = 16
        private const val DEFAULT_ACTIVE_SLICE_BUDGET_MILLIS = 5_000L
        private const val DEFAULT_OPERATION_WAIT_DEADLINE_MILLIS = 30_000L
        private const val DEFAULT_CALLBACK_TIMEOUT_MILLIS = 15_000L
        private const val DEFAULT_CLOSE_TIMEOUT_MILLIS = 5_000L
        private const val DEFAULT_MAX_CONCURRENT_TASKS = 4
        private const val DEFAULT_PER_TASK_DEADLINE_MILLIS = 60_000L
        private const val DEFAULT_TIMER_SLACK_MILLIS = 100L

        /**
         * Calculate an overflow-safe sleep-operation deadline from requested
         * delay and bounded slack. A null result rejects an unrepresentable
         * duration rather than encoding it as a sentinel.
         */
        fun calculateSleepDeadline(delayMillis: Long, slackMillis: Long): Long? {
            require(delayMillis >= 0) { "Delay must be non-negative" }
            require(slackMillis >= 0) { "Slack must be non-negative" }
            return try {
                Math.addExact(delayMillis, slackMillis)
            } catch (_: ArithmeticException) {
                null
            }
        }
    }
}

/**
 * Per-state Lua kernel configuration.
 */
internal data class ActorKernelConfig(
    val hookInterval: Int,
    val instructionBudget: Long,
) {
    init {
        require(hookInterval > 0) { "Hook interval must be positive" }
        require(instructionBudget >= 0L) { "Instruction budget must be non-negative" }
    }
}