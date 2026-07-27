package io.talkcan.service

import io.talkcan.audio.ResolvedAudioRoute
import kotlinx.coroutines.CancellationException

internal suspend fun playRouteErrorBeepIfAcquired(route: ResolvedAudioRoute): Boolean {
    val gateReady = try {
        route.routeGate.await().isSuccess
    } catch (error: Throwable) {
        if (error is CancellationException) throw error
        false
    }
    if (!gateReady) {
        try {
            route.output.releaseRoute()
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
        }
        return false
    }
    if (!route.sco.acquire()) return false
    return try {
        route.output.playErrorBeep(route.sco.coldStart)
        true
    } finally {
        route.output.releaseRoute()
    }
}
