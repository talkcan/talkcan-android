package io.talkcan.audio

import android.bluetooth.BluetoothDevice
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import io.talkcan.model.InputMode
import io.talkcan.service.TalkcanLogger as Log

/** Observable host request used to construct one semantic playback route. */
internal data class PlaybackRouteRequest(
    val mode: InputMode,
    val endpoint: AudioRouteEndpoint,
    val preferredDevice: AudioDeviceInfo?,
    val owner: BluetoothDevice? = null,
    val usage: Int,
    val audioMode: Int,
    /** Named alias retained for route-observation consumers. */
    val preferredBluetoothDevice: BluetoothDevice? = owner,
)

/**
 * Host-only output policy for channel content. This deliberately does not reuse
 * [ResolvedAudioRoute], which owns an input source and a PTT-session release policy.
 *
 * Work acquisition trusts the target-RSM SCO subsystem as the authoritative ownership
 * proof: [workSco] performs the target-specific voice-recognition start, target HFP audio
 * connection polling, and transport selection under that proof. Endpoint product-name or
 * address metadata is corroboration at most and never a veto, because some OEMs label the
 * SCO communication endpoint with the phone's own identity. [awaitTelecomCaptureRelease]
 * is called before any On-the-road mode/device query.
 *
 * On-the-road playback targets the car media path (Bluetooth A2DP or wired Android Auto
 * output) after the Telecom SCO call has dropped, and holds transient media audio focus
 * for the duration of playback so the head unit routes media audio to the speakers.
 */
internal class ModePlaybackRouteResolver(
    private val audioManager: AudioManager,
    private val workSco: ScoAudioController,
    private val targetRsmDevice: () -> BluetoothDevice? = { null },
    /** BluetoothHeadset/startVoiceRecognition ownership proof for the resolved target. */
    private val targetRsmOwnershipProof: (BluetoothDevice) -> Boolean = { true },
    private val awaitTelecomCaptureRelease: suspend () -> Boolean = { false },
    private val carMediaDevice: () -> AudioDeviceInfo? = {
        audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .firstOrNull { it.type in CAR_OUTPUT_TYPES }
    },
    private val carMediaFocus: CarMediaFocus = CarMediaFocus(audioManager),
    private val routeFactory: (PlaybackRouteRequest) -> AcquiredPlaybackRoute = { request ->
        StreamPlaybackRoute(
            preferredDevice = request.preferredDevice,
            usage = request.usage,
            routeEndpoint = request.endpoint,
        )
    },
) {
    fun strategyFor(mode: InputMode): PlaybackRouteStrategy = PlaybackRouteStrategy {
        when (mode) {
            InputMode.Work -> acquireWork()
            InputMode.OnTheRoad -> acquireCar()
            InputMode.OnAPinch -> acquirePhone()
        }
    }

    private suspend fun acquireWork(): PlaybackRouteAcquisition {
        val owner = targetRsmDevice()
            ?: return PlaybackRouteAcquisition.Unavailable("Target RSM Bluetooth device unavailable")
        if (!workSco.acquire()) return PlaybackRouteAcquisition.Unavailable("Target RSM SCO unavailable")
        val ownershipProven = runCatching { targetRsmOwnershipProof(owner) }.getOrDefault(false)
        if (!ownershipProven) {
            workSco.release()
            return PlaybackRouteAcquisition.Unavailable("Target RSM Bluetooth ownership unavailable")
        }
        val device = workSco.selectedCommunicationDevice()
        if (device == null || device.type != AudioDeviceInfo.TYPE_BLUETOOTH_SCO) {
            workSco.release()
            return PlaybackRouteAcquisition.Unavailable("Target RSM SCO transport unavailable")
        }
        val route = try {
            routeFactory(
                PlaybackRouteRequest(
                    mode = InputMode.Work,
                    endpoint = AudioRouteEndpoint.Rsm,
                    preferredDevice = device,
                    owner = owner,
                    usage = AudioAttributes.USAGE_VOICE_COMMUNICATION,
                    audioMode = AudioManager.MODE_IN_COMMUNICATION,
                ),
            )
        } catch (error: Exception) {
            workSco.release()
            return PlaybackRouteAcquisition.Failed(error.message ?: "Unable to construct RSM playback route")
        }
        return PlaybackRouteAcquisition.Acquired(
            ReleasingPlaybackRoute(route, releaseUnderlying = workSco::release),
        )
    }

    private suspend fun acquireCar(): PlaybackRouteAcquisition {
        // A stale Work (RSM) SCO lease or keep-warm holds MODE_IN_COMMUNICATION and an SCO
        // communication device, which would block car media admission. Release it, mirroring
        // the capture-path release-work-before-car gate.
        if (workSco.isActive()) {
            workSco.releaseImmediately("car-playback-acquire")
        }
        if (!awaitTelecomCaptureRelease()) {
            Log.d(ROUTE_LOG_TAG, "CAR_PLAYBACK_ACQUIRE result=telecom-not-released")
            return PlaybackRouteAcquisition.Unavailable("Telecom capture route unavailable")
        }
        if (audioManager.mode != AudioManager.MODE_NORMAL ||
            audioManager.communicationDevice?.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
        ) {
            Log.d(ROUTE_LOG_TAG,
                "CAR_PLAYBACK_ACQUIRE result=busy mode=${audioManager.mode} " +
                    "comm=${audioManager.communicationDevice?.type}",)
            return PlaybackRouteAcquisition.Busy
        }
        // Prefer a validated car media output (A2DP / wired Android Auto) when one is
        // enumerable; otherwise fall back to unpinned USAGE_MEDIA (default media routing,
        // which reaches the car's A2DP sink) instead of failing closed — mirroring the
        // proven pre-host-owned MediaResponsePlayer path. Some OEMs do not expose a
        // routable car output device to getDevices(), and requiring one strands responses.
        val device = carMediaDevice()?.takeIf { it.type in CAR_OUTPUT_TYPES }
        Log.d(ROUTE_LOG_TAG, "CAR_PLAYBACK_ACQUIRE result=acquired device=${device?.type ?: "unpinned"}")
        val route = routeFactory(
            PlaybackRouteRequest(
                mode = InputMode.OnTheRoad,
                endpoint = AudioRouteEndpoint.Car,
                preferredDevice = device,
                usage = AudioAttributes.USAGE_MEDIA,
                audioMode = AudioManager.MODE_NORMAL,
            ),
        )
        return PlaybackRouteAcquisition.Acquired(
            MediaFocusPlaybackRoute(carMediaFocus::request, carMediaFocus::abandon, route),
        )
    }

    private fun acquirePhone(): PlaybackRouteAcquisition {
        // The app's own target-RSM SCO lease or keep-warm residue never blocks phone
        // speaker playback: host admission already serializes capture/playback, and the
        // speaker track's preferred device keeps audio off the work route. Only a
        // communication route this app does not own (e.g. an unrelated telecom call)
        // makes phone acquisition busy.
        if (!workSco.isActive() &&
            (audioManager.mode != AudioManager.MODE_NORMAL ||
                audioManager.communicationDevice?.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO)
        ) {
            return PlaybackRouteAcquisition.Busy
        }
        val device = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
            ?: return PlaybackRouteAcquisition.Unavailable("Phone speaker output unavailable")
        return PlaybackRouteAcquisition.Acquired(
            routeFactory(
                PlaybackRouteRequest(
                    mode = InputMode.OnAPinch,
                    endpoint = AudioRouteEndpoint.Local,
                    preferredDevice = device,
                    usage = AudioAttributes.USAGE_MEDIA,
                    audioMode = AudioManager.MODE_NORMAL,
                ),
            ),
        )
    }

    private class ReleasingPlaybackRoute(
        private val delegate: AcquiredPlaybackRoute,
        private val releaseUnderlying: () -> Unit,
    ) : AcquiredPlaybackRoute {
        private var released = false

        override val endpoint: AudioRouteEndpoint
            get() = delegate.endpoint

        override suspend fun start(recording: RecordedPcm): ActivePcmPlayback = delegate.start(recording)

        override suspend fun release() {
            if (released) return
            released = true
            try {
                delegate.release()
            } finally {
                releaseUnderlying.invoke()
            }
        }
    }

    private companion object {
        val CAR_OUTPUT_TYPES = setOf(
            AudioDeviceInfo.TYPE_AUX_LINE,
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BUS,
            AudioDeviceInfo.TYPE_HDMI,
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_HEADSET,
        )
    }
}

/**
 * Transient `USAGE_MEDIA` audio focus for car (On-the-road) response playback.
 *
 * After the Telecom SCO call drops, response audio travels the car's media path (A2DP or
 * wired Android Auto). The head unit routes media audio to the speakers only while the
 * phone holds media audio focus, mirroring the proven [MediaResponsePlayer] behavior.
 */
internal class CarMediaFocus(private val audioManager: AudioManager) {
    private var focusRequest: AudioFocusRequest? = null

    fun request(): Boolean {
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .build()
        val granted = runCatching { audioManager.requestAudioFocus(request) }
            .getOrDefault(AudioManager.AUDIOFOCUS_REQUEST_FAILED) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        focusRequest = if (granted) request else null
        Log.d(ROUTE_LOG_TAG, "CAR_MEDIA_FOCUS granted=$granted")
        return granted
    }

    fun abandon() {
        focusRequest?.let { runCatching { audioManager.abandonAudioFocusRequest(it) } }
        focusRequest = null
    }
}

/**
 * Playback route decorator that holds media audio focus for the duration of one playback.
 * Focus is requested (best-effort) before the delegate starts and abandoned on release.
 * Focus denial does not block playback: the on-the-road spec requires requesting focus
 * AND playing the response, and a head unit still renders an unpinned USAGE_MEDIA A2DP
 * stream when transient focus is unavailable.
 */
internal class MediaFocusPlaybackRoute(
    private val requestFocus: () -> Boolean,
    private val abandonFocus: () -> Unit,
    private val delegate: AcquiredPlaybackRoute,
) : AcquiredPlaybackRoute {
    private var focusHeld = false

    override val endpoint: AudioRouteEndpoint
        get() = delegate.endpoint

    override suspend fun start(recording: RecordedPcm): ActivePcmPlayback {
        focusHeld = requestFocus()
        return delegate.start(recording)
    }

    override suspend fun release() {
        if (focusHeld) {
            focusHeld = false
            abandonFocus()
        }
        delegate.release()
    }
}
