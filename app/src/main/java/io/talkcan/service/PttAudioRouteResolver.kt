package io.talkcan.service

import android.media.AudioManager
import io.talkcan.service.TalkcanLogger as Log
import io.talkcan.audio.AudioRouteEndpoint
import io.talkcan.audio.CaptureSource
import io.talkcan.audio.PcmOutput
import io.talkcan.audio.ROUTE_LOG_TAG
import io.talkcan.audio.ResolvedAudioRoute
import io.talkcan.audio.ResponsePlayer
import io.talkcan.audio.ScoRoute
import io.talkcan.audio.TelecomCallScoRoute
import io.talkcan.audio.TelecomCapturePcmOutput
import io.talkcan.audio.RouteGateResult
import io.talkcan.audio.resolveLocalAudioRoute
import io.talkcan.audio.resolveScoAudioRoute
import io.talkcan.model.InputMode
import io.talkcan.telecom.TelecomCarPttCoordinator

/**
 * Resolve the audio route for a given [mode].
 *
 * Extracted from [PttForegroundService] to reduce the god-service scope.
 * All parameters that were previously closed-over service fields are passed
 * explicitly.
 */
internal fun ResolvedAudioRoute.routeDebugString(): String =
    "endpoint=$endpoint sco=${sco.javaClass.simpleName} output=${output.javaClass.simpleName} " +
        "source=${source.sourceId}"

internal fun resolvePttAudioRoute(
    mode: InputMode,
    sco: ScoRoute,
    telecomCaptureOutput: PcmOutput,
    mediaResponsePlayer: ResponsePlayer,
    voiceCommunicationSource: CaptureSource,
    localOutput: PcmOutput,
    micSource: CaptureSource,
    pcmOutput: PcmOutput,
    awaitTelecomDisconnected: suspend () -> Unit?,
    releaseTelecomCaptureRoute: () -> Unit,
    releaseStaleWorkRoute: suspend (String) -> RouteGateResult = { reason -> RouteGateResult.Success(reason) },
    logAudioRouteSnapshot: (String) -> Unit,
): ResolvedAudioRoute {
    val route = when (mode) {
        InputMode.OnTheRoad -> ResolvedAudioRoute(
            sco = TelecomCallScoRoute(TelecomCarPttCoordinator::isCaptureRouteReady),
            output = TelecomCapturePcmOutput(
                captureOutput = telecomCaptureOutput,
                mediaResponsePlayer = mediaResponsePlayer,
                releaseCaptureRoute = { releaseTelecomCaptureRoute() },
                awaitTelecomDisconnected = awaitTelecomDisconnected,
            ),
            source = voiceCommunicationSource,
            endpoint = AudioRouteEndpoint.Car,
            routeGate = io.talkcan.audio.releaseWorkRouteGate(
                name = "release-work-before-car",
                release = releaseStaleWorkRoute,
            ),
        )
        InputMode.Work -> resolveScoAudioRoute(
            scoRoute = sco,
            scoOutput = pcmOutput,
            scoSource = voiceCommunicationSource,
            endpoint = AudioRouteEndpoint.Rsm,
        )
        InputMode.OnAPinch -> {
            logAudioRouteSnapshot("route-resolve-local-before")
            resolveLocalAudioRoute(
                localOutput,
                micSource,
                routeGate = io.talkcan.audio.releaseWorkRouteGate(
                    name = "release-work-before-local",
                    release = releaseStaleWorkRoute,
                ),
            )
        }
    }
    Log.d(ROUTE_LOG_TAG, "ROUTE_RESOLVE mode=$mode ${route.routeDebugString()}")
    return route
}

/**
 * Release the telecom capture route.
 *
 * Extracted from [PttForegroundService]; callers pass the needed
 * dependencies explicitly.
 */
internal fun releaseTelecomCaptureRoute(
    audioManager: AudioManager,
    logAudioRouteSnapshot: (String) -> Unit,
) {
    logAudioRouteSnapshot("telecom-release-before")
    audioManager.clearCommunicationDevice()
    audioManager.mode = AudioManager.MODE_NORMAL
    logAudioRouteSnapshot("telecom-release-after")
}
