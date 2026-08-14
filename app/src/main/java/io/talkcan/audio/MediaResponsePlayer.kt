package io.talkcan.audio

import android.media.AudioAttributes
import io.talkcan.service.CaptureFeedbackTone
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.SystemClock
import kotlinx.coroutines.delay

private const val MEDIA_ROUTE_STABILITY_MS = 500L
private const val MEDIA_ROUTE_POLL_MS = 50L
private const val MEDIA_ROUTE_TIMEOUT_MS = 3_000L

internal suspend fun awaitStableMediaRoute(
    isReady: () -> Boolean,
    nowMs: () -> Long,
    delayMs: suspend (Long) -> Unit,
    stableForMs: Long = MEDIA_ROUTE_STABILITY_MS,
    pollMs: Long = MEDIA_ROUTE_POLL_MS,
    timeoutMs: Long = MEDIA_ROUTE_TIMEOUT_MS,
): Boolean {
    val deadlineMs = nowMs() + timeoutMs
    var stableSinceMs: Long? = null
    while (true) {
        val now = nowMs()
        if (isReady()) {
            if (stableSinceMs == null) stableSinceMs = now
            if (now - stableSinceMs >= stableForMs) return true
        } else {
            stableSinceMs = null
        }
        if (now >= deadlineMs) return false
        delayMs(minOf(pollMs, deadlineMs - now))
    }
}

internal class MediaRoutePlaybackGate(
    private val awaitRouteReady: suspend () -> Boolean,
    private val requestFocus: () -> Boolean,
    private val abandonFocus: () -> Unit,
) {
    suspend fun play(recording: RecordedPcm, output: PcmOutput): Boolean {
        if (!awaitRouteReady() || !requestFocus()) return false
        return try {
            output.play(recording)
            true
        } finally {
            abandonFocus()
        }
    }
}

interface ResponsePlayer {
    suspend fun play(recording: RecordedPcm)
}

internal class MediaResponsePcmOutput(
    private val localOutput: PcmOutput,
    private val responsePlayer: ResponsePlayer,
) : PcmOutput {
    override suspend fun playReadyBeep(coldStart: Boolean) {
        localOutput.playReadyBeep(coldStart)
    }

    override suspend fun playErrorBeep(coldStart: Boolean) {
        localOutput.playErrorBeep(coldStart)
    }

    override suspend fun play(recording: RecordedPcm) {
        responsePlayer.play(recording)
    }

    override suspend fun playCaptureFeedback(tone: CaptureFeedbackTone) {
        localOutput.playCaptureFeedback(tone)
    }
}

class MediaResponsePlayer(
    private val audioManager: AudioManager,
    private val output: PcmOutput,
) : ResponsePlayer {
    private var focusRequest: AudioFocusRequest? = null
    private val playbackGate = MediaRoutePlaybackGate(
        awaitRouteReady = ::waitForMediaRoute,
        requestFocus = ::requestFocus,
        abandonFocus = ::abandonFocus,
    )

    override suspend fun play(recording: RecordedPcm) {
        if (recording.isEmpty) return
        playbackGate.play(recording, output)
    }

    private suspend fun waitForMediaRoute(): Boolean =
        awaitStableMediaRoute(
            isReady = {
                val communicationDevice = audioManager.communicationDevice
                audioManager.mode == AudioManager.MODE_NORMAL &&
                    communicationDevice?.type != AudioDeviceInfo.TYPE_BLUETOOTH_SCO
            },
            nowMs = SystemClock::elapsedRealtime,
            delayMs = { delay(it) },
        )

    private fun requestFocus(): Boolean {
        return if (Build.VERSION.SDK_INT >= 26) {
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                .build()
            focusRequest = request
            audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK) ==
                AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
    }

    private fun abandonFocus() {
        if (Build.VERSION.SDK_INT >= 26) {
            focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
            focusRequest = null
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(null)
        }
    }
}
