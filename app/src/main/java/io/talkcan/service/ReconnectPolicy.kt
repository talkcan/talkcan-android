package io.talkcan.service

data class ReconnectPrerequisites(
    val permissionsGranted: Boolean,
    val bluetoothEnabled: Boolean,
    val bondedTargetAvailable: Boolean,
)

enum class ReconnectBlockReason {
    MonitoringNotRequested,
    MissingPermissions,
    BluetoothDisabled,
    TargetUnavailable,
}

sealed interface ReconnectDecision {
    data object NoAction : ReconnectDecision
    data class Schedule(val attemptAtMillis: Long) : ReconnectDecision
    data class Wait(val attemptAtMillis: Long) : ReconnectDecision
    data object StartAttempt : ReconnectDecision
    data object AlreadyInProgress : ReconnectDecision
    data class Blocked(val reason: ReconnectBlockReason) : ReconnectDecision
}

internal enum class RsmReconnectDisposition {
    Scheduled,
    Stopped,
    BlockedMissingPermissions,
    BlockedBluetoothDisabled,
    BlockedTargetUnavailable,
    NoAction,
    StartAttempt,
    AlreadyInProgress,
}

internal fun ReconnectDecision.toRsmReconnectDisposition(): RsmReconnectDisposition = when (this) {
    is ReconnectDecision.Schedule,
    is ReconnectDecision.Wait,
    -> RsmReconnectDisposition.Scheduled
    ReconnectDecision.NoAction -> RsmReconnectDisposition.NoAction
    ReconnectDecision.StartAttempt -> RsmReconnectDisposition.StartAttempt
    ReconnectDecision.AlreadyInProgress -> RsmReconnectDisposition.AlreadyInProgress
    is ReconnectDecision.Blocked -> when (reason) {
        ReconnectBlockReason.MonitoringNotRequested -> RsmReconnectDisposition.Stopped
        ReconnectBlockReason.MissingPermissions -> RsmReconnectDisposition.BlockedMissingPermissions
        ReconnectBlockReason.BluetoothDisabled -> RsmReconnectDisposition.BlockedBluetoothDisabled
        ReconnectBlockReason.TargetUnavailable -> RsmReconnectDisposition.BlockedTargetUnavailable
    }
}

class ReconnectPolicy(
    private val retryDelayMs: Long = DEFAULT_RECONNECT_RETRY_DELAY_MS,
) {
    var monitoringRequested: Boolean = false
        private set
    var attemptInProgress: Boolean = false
        private set
    var nextAttemptAtMillis: Long? = null
        private set

    fun startMonitoring() {
        monitoringRequested = true
        attemptInProgress = false
        nextAttemptAtMillis = null
    }

    fun clearMonitoring() {
        monitoringRequested = false
        attemptInProgress = false
        nextAttemptAtMillis = null
    }

    fun scheduleInitialConnection(
        nowMillis: Long,
        prerequisites: ReconnectPrerequisites,
    ): ReconnectDecision = scheduleAt(nowMillis, prerequisites)

    fun scheduleAfterUnexpectedLoss(
        nowMillis: Long,
        prerequisites: ReconnectPrerequisites,
    ): ReconnectDecision = scheduleAt(nowMillis, prerequisites)

    fun beginAttempt(
        nowMillis: Long,
        prerequisites: ReconnectPrerequisites,
    ): ReconnectDecision {
        blockedDecision(prerequisites)?.let { return it }
        if (attemptInProgress) return ReconnectDecision.AlreadyInProgress

        val scheduledAt = nextAttemptAtMillis ?: nowMillis
        if (nowMillis < scheduledAt) return ReconnectDecision.Wait(scheduledAt)

        nextAttemptAtMillis = null
        attemptInProgress = true
        return ReconnectDecision.StartAttempt
    }

    fun finishAttempt(
        success: Boolean,
        nowMillis: Long,
        prerequisites: ReconnectPrerequisites,
    ): ReconnectDecision {
        attemptInProgress = false
        if (success) {
            nextAttemptAtMillis = null
            return ReconnectDecision.NoAction
        }
        return scheduleAt(nowMillis + retryDelayMs, prerequisites)
    }

    fun cancelAttempt() {
        attemptInProgress = false
    }

    private fun scheduleAt(
        attemptAtMillis: Long,
        prerequisites: ReconnectPrerequisites,
    ): ReconnectDecision {
        blockedDecision(prerequisites)?.let {
            nextAttemptAtMillis = null
            attemptInProgress = false
            return it
        }
        if (attemptInProgress) return ReconnectDecision.AlreadyInProgress

        nextAttemptAtMillis = attemptAtMillis
        return ReconnectDecision.Schedule(attemptAtMillis)
    }

    private fun blockedDecision(prerequisites: ReconnectPrerequisites): ReconnectDecision.Blocked? = when {
        !monitoringRequested -> ReconnectDecision.Blocked(ReconnectBlockReason.MonitoringNotRequested)
        !prerequisites.permissionsGranted -> ReconnectDecision.Blocked(ReconnectBlockReason.MissingPermissions)
        !prerequisites.bluetoothEnabled -> ReconnectDecision.Blocked(ReconnectBlockReason.BluetoothDisabled)
        !prerequisites.bondedTargetAvailable -> ReconnectDecision.Blocked(ReconnectBlockReason.TargetUnavailable)
        else -> null
    }

    companion object {
        const val DEFAULT_RECONNECT_RETRY_DELAY_MS = 5_000L
    }
}
