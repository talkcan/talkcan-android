package io.talkcan.telecom

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.os.Handler
import android.os.Looper
import android.telecom.CallAudioState
import io.talkcan.service.TalkcanLogger as Log
import io.talkcan.audio.ROUTE_LOG_TAG
import android.telecom.Connection
import android.telecom.DisconnectCause

private const val TELECOM_CAPTURE_ROUTE_STABILITY_MS = 500L
private const val TELECOM_BLUETOOTH_ROUTE_RETRY_MS = 250L

internal fun isAcceptableTelecomCaptureRoute(
    route: Int,
    activeBluetoothDeviceMatchesExpected: Boolean,
): Boolean =
    route == CallAudioState.ROUTE_BLUETOOTH && activeBluetoothDeviceMatchesExpected

internal class TelecomBluetoothRouteController<D : Any>(
    private val expectedDevice: D,
    private val requestBluetoothAudio: (D) -> Unit,
    private val retryDelayMs: Long,
    private val postDelayed: (Runnable, Long) -> Unit,
    private val removeCallbacks: (Runnable) -> Unit,
    private val onRouteChanged: (Boolean) -> Unit,
) {
    private var routeAcceptable = false
    private var expectedDeviceSupported = false
    private var retryPending = false
    private val retryRequest = Runnable {
        retryPending = false
        if (!routeAcceptable && expectedDeviceSupported) {
            requestBluetoothAudio(expectedDevice)
            scheduleRetry()
        }
    }

    fun routeChanged(
        route: Int,
        activeDevice: D?,
        supportedDevices: Collection<D>,
    ) {
        routeAcceptable = isAcceptableTelecomCaptureRoute(
            route = route,
            activeBluetoothDeviceMatchesExpected = activeDevice == expectedDevice,
        )
        expectedDeviceSupported = expectedDevice in supportedDevices
        if (routeAcceptable || !expectedDeviceSupported) {
            cancelRetry()
        } else if (!retryPending) {
            requestBluetoothAudio(expectedDevice)
            scheduleRetry()
        }
        onRouteChanged(routeAcceptable)
    }

    fun cancel() {
        routeAcceptable = false
        expectedDeviceSupported = false
        cancelRetry()
    }

    private fun scheduleRetry() {
        retryPending = true
        postDelayed(retryRequest, retryDelayMs)
    }

    private fun cancelRetry() {
        if (retryPending) removeCallbacks(retryRequest)
        retryPending = false
    }
}

internal class TelecomCaptureRouteStabilizer(
    private val stabilityDelayMs: Long,
    private val postDelayed: (Runnable, Long) -> Unit,
    private val removeCallbacks: (Runnable) -> Unit,
    private val onRouteChanged: (Boolean) -> Unit,
) {
    private var pending = false
    private var stable = false
    private val publishStableRoute = Runnable {
        pending = false
        stable = true
        onRouteChanged(true)
    }

    fun routeChanged(acceptable: Boolean) {
        if (acceptable) {
            if (pending || stable) return
            pending = true
            postDelayed(publishStableRoute, stabilityDelayMs)
            return
        }

        if (pending) removeCallbacks(publishStableRoute)
        pending = false
        stable = false
        onRouteChanged(false)
    }

    fun cancel() {
        if (pending) removeCallbacks(publishStableRoute)
        pending = false
        stable = false
    }
}

internal class TalkcanConnection(
    private val expectedBluetoothDevice: BluetoothDevice,
) : Connection() {
    private val handler = Handler(Looper.getMainLooper())
    private val timeout = Runnable { TelecomCarPttCoordinator.checkRouteTimeout() }
    private val routeStabilizer = TelecomCaptureRouteStabilizer(
        stabilityDelayMs = TELECOM_CAPTURE_ROUTE_STABILITY_MS,
        postDelayed = handler::postDelayed,
        removeCallbacks = handler::removeCallbacks,
        onRouteChanged = TelecomCarPttCoordinator::onRouteChanged,
    )
    private val bluetoothRouteController = TelecomBluetoothRouteController(
        expectedDevice = expectedBluetoothDevice,
        requestBluetoothAudio = ::requestExpectedBluetoothRoute,
        retryDelayMs = TELECOM_BLUETOOTH_ROUTE_RETRY_MS,
        postDelayed = handler::postDelayed,
        removeCallbacks = handler::removeCallbacks,
        onRouteChanged = routeStabilizer::routeChanged,
    )
    private var coordinatorDestroy = false
    private var coordinatorDisconnect = false

    init {
        audioModeIsVoip = true
        setInitialized()
        setActive()
        handler.postDelayed(timeout, TelecomCarPttLifecycle.DEFAULT_ROUTE_TIMEOUT_MS)
    }

    override fun onCallAudioStateChanged(state: CallAudioState) {
        handleCallAudioState(state)
    }

    override fun onDisconnect() {
        handler.removeCallbacks(timeout)
        routeStabilizer.cancel()
        bluetoothRouteController.cancel()
        if (!coordinatorDisconnect) {
            setDisconnected(DisconnectCause(DisconnectCause.LOCAL))
            TelecomCarPttCoordinator.onDisconnect()
        }
    }

    override fun onAbort() {
        abortFromTelecom()
    }

    override fun onReject() {
        abortFromTelecom()
    }

    override fun onPlayDtmfTone(c: Char) {
        super.onPlayDtmfTone(c)
    }

    override fun onHold() {
        super.onHold()
    }

    override fun onUnhold() {
        super.onUnhold()
    }

    override fun onAnswer() {
        super.onAnswer()
    }

    override fun onAnswer(videoState: Int) {
        super.onAnswer(videoState)
    }

    override fun onSilence() {
        super.onSilence()
    }

    fun disconnectFromCoordinator() {
        coordinatorDisconnect = true
        handler.removeCallbacks(timeout)
        routeStabilizer.cancel()
        bluetoothRouteController.cancel()
        setDisconnected(DisconnectCause(DisconnectCause.ERROR, "No usable car call audio route"))
        destroyFromCoordinator()
    }

    fun destroyFromCoordinator() {
        coordinatorDestroy = true
        routeStabilizer.cancel()
        bluetoothRouteController.cancel()
        destroy()
    }

    private fun abortFromTelecom() {
        handler.removeCallbacks(timeout)
        routeStabilizer.cancel()
        bluetoothRouteController.cancel()
        setDisconnected(DisconnectCause(DisconnectCause.CANCELED))
        TelecomCarPttCoordinator.onAbort()
    }

    @SuppressLint("MissingPermission")
    private fun handleCallAudioState(state: CallAudioState) {
        val activeDevice = state.activeBluetoothDevice
        val activeName = runCatching { activeDevice?.name }.getOrNull()
        val expectedName = runCatching { expectedBluetoothDevice.name }.getOrNull()
        val activeMatchesExpected = activeDevice == expectedBluetoothDevice
        val expectedSupported = expectedBluetoothDevice in state.supportedBluetoothDevices
        val acceptable = isAcceptableTelecomCaptureRoute(
            route = state.route,
            activeBluetoothDeviceMatchesExpected = activeMatchesExpected,
        )
        Log.d(
            ROUTE_LOG_TAG,
            "TELECOM_CALL_AUDIO route=${state.route} supported=${state.supportedRouteMask} " +
                "activeBtName=${activeName?.let { "'$it'" } ?: "none"} " +
                "expectedBtName=${expectedName?.let { "'$it'" } ?: "none"} " +
                "activeMatchesExpected=$activeMatchesExpected expectedSupported=$expectedSupported " +
                "acceptable=$acceptable",
        )
        bluetoothRouteController.routeChanged(
            route = state.route,
            activeDevice = activeDevice,
            supportedDevices = state.supportedBluetoothDevices,
        )
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private fun requestExpectedBluetoothRoute(device: BluetoothDevice) {
        Log.d(
            ROUTE_LOG_TAG,
            "TELECOM_REQUEST_BLUETOOTH target='${runCatching { device.name }.getOrNull()}'",
        )
        runCatching { requestBluetoothAudio(device) }
            .onFailure {
                Log.d(
                    ROUTE_LOG_TAG,
                    "TELECOM_REQUEST_BLUETOOTH_THROW error=${it.javaClass.simpleName}",
                )
            }
    }
}
