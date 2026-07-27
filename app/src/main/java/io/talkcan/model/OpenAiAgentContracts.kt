package io.talkcan.model

@JvmInline
value class AgentRunId(val value: String) {
    init {
        require(value.isNotBlank()) { "Agent run ID must not be blank" }
    }
}

@JvmInline
value class AgentOperationId(val value: String) {
    init {
        require(value.isNotBlank()) { "Agent operation ID must not be blank" }
    }
}

@JvmInline
value class DelayedPlaybackOperationId(val value: String) {
    init {
        require(value.isNotBlank()) { "Delayed playback operation ID must not be blank" }
    }
}

sealed interface DelayedPlaybackOutcome {
    data class Pending(val operationId: DelayedPlaybackOperationId) : DelayedPlaybackOutcome
    data class Playing(val operationId: DelayedPlaybackOperationId) : DelayedPlaybackOutcome
    data class Heard(val operationId: DelayedPlaybackOperationId) : DelayedPlaybackOutcome
    data class Failed(val reason: DelayedPlaybackFailureReason) : DelayedPlaybackOutcome
    data object Cancelled : DelayedPlaybackOutcome
    data object Stale : DelayedPlaybackOutcome
    data object Busy : DelayedPlaybackOutcome
}

enum class DelayedPlaybackFailureReason {
    SYNTHESIS_FAILED,
    PLAYBACK_FAILED,
    HOST_FAILURE,
    TIMED_OUT,
}
