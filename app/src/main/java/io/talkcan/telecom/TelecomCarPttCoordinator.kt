package io.talkcan.telecom

import android.bluetooth.BluetoothDevice

import android.os.SystemClock

internal object TelecomCarPttCoordinator {
    private var listener: Listener? = null
    private var connection: TalkcanConnection? = null
    private var expectedBluetoothDevice: BluetoothDevice? = null
    private val lifecycle: TelecomCarPttLifecycle = TelecomCarPttLifecycle(object : TelecomCarPttLifecycle.Callbacks {
        override fun onCaptureStart() {
            listener?.onTelecomCaptureStart()
        }

        override fun onCaptureStop() {
            listener?.onTelecomCaptureStop()
        }

        override fun onRouteTimeout() {
            listener?.onTelecomRouteTimeout()
            connection?.disconnectFromCoordinator()
            connection = null
            releaseLifecycleAfterTeardown()
        }

        override fun onDisconnected() {
            connection?.destroyFromCoordinator()
            connection = null
            listener?.onTelecomConnectionEnded()
            releaseLifecycleAfterTeardown()
        }

        override fun onAborted() {
            connection?.destroyFromCoordinator()
            connection = null
            listener?.onTelecomConnectionEnded()
            releaseLifecycleAfterTeardown()
        }
    })

    private fun releaseLifecycleAfterTeardown() {
        expectedBluetoothDevice = null
        lifecycle.releaseAfterTeardown()
    }

    fun setListener(listener: Listener?) {
        this.listener = listener
    }

    fun prepareConnection(expectedBluetoothDevice: BluetoothDevice): Boolean {
        if (connection != null || this.expectedBluetoothDevice != null) return false
        if (!lifecycle.startRequest(SystemClock.elapsedRealtime())) return false
        this.expectedBluetoothDevice = expectedBluetoothDevice
        return true
    }

    fun claimExpectedBluetoothDevice(): BluetoothDevice? {
        if (connection != null) return null
        return expectedBluetoothDevice.also { expectedBluetoothDevice = null }
    }

    fun attachConnection(connection: TalkcanConnection): Boolean {
        if (this.connection != null || lifecycle.currentState != TelecomCarPttLifecycle.State.WaitingForRoute) {
            return false
        }
        this.connection = connection
        return true
    }

    fun onRouteChanged(acceptable: Boolean) {
        lifecycle.routeChanged(acceptable)
    }

    fun isCaptureRouteReady(): Boolean =
        lifecycle.currentState == TelecomCarPttLifecycle.State.Recording

    /** True while Telecom is still reserving or owning the capture route. */
    fun isCaptureActive(): Boolean = when (lifecycle.currentState) {
        TelecomCarPttLifecycle.State.WaitingForRoute,
        TelecomCarPttLifecycle.State.Recording,
        -> true
        TelecomCarPttLifecycle.State.Idle,
        TelecomCarPttLifecycle.State.Released,
        -> false
    }

    fun checkRouteTimeout() {
        lifecycle.checkTimeout(SystemClock.elapsedRealtime())
    }

    fun onDisconnect() {
        lifecycle.disconnect()
    }

    fun onAbort() {
        lifecycle.abort()
    }

    fun forceAbort() {
        expectedBluetoothDevice = null
        lifecycle.abort()
    }

    interface Listener {
        fun onTelecomCaptureStart()
        fun onTelecomCaptureStop()
        fun onTelecomRouteTimeout()
        fun onTelecomConnectionEnded()
    }
}
