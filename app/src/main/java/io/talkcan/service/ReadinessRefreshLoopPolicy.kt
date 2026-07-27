package io.talkcan.service

enum class ReadinessRefreshLoopDecision {
    KeepCurrentLoop,
    StartRefreshLoop,
    StopRefreshLoop,
}

object ReadinessRefreshLoopPolicy {
    fun decide(
        refreshAllowed: Boolean,
        readyForMonitor: Boolean,
        refreshLoopActive: Boolean,
    ): ReadinessRefreshLoopDecision = when {
        refreshLoopActive && (!refreshAllowed || readyForMonitor) -> ReadinessRefreshLoopDecision.StopRefreshLoop
        !refreshLoopActive && refreshAllowed && !readyForMonitor -> ReadinessRefreshLoopDecision.StartRefreshLoop
        else -> ReadinessRefreshLoopDecision.KeepCurrentLoop
    }
}
