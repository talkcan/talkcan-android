package io.talkcan.service

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothProfile
import android.content.Context
import io.talkcan.bluetooth.DeviceScanner
import io.talkcan.model.DevicePresence
import io.talkcan.model.HeadsetAudioState
import io.talkcan.model.PermissionState

/**
 * Encapsulates the device-readiness check previously inlined in
 * [PttForegroundService.refreshReadiness].
 *
 * All dependencies that were formerly service fields are passed via
 * constructor parameters.
 */
class ReadinessProbe(
    private val context: Context,
    private val scanner: DeviceScanner,
    private val bluetoothAdapter: BluetoothAdapter?,
    private val headsetProxyProvider: () -> BluetoothHeadset?,
) {
    /**
     * Compute the current readiness snapshot.
     *
     * @param previousDevicePresence  the [DevicePresence] from the previous
     *   connection state, used to preserve transient states (Scanning,
     *   Found, etc.) across refresh cycles.
     * @param previousTargetDevice  the [Device] previously set as target,
     *   used to detect a bond state change when no fresh scan result is
     *   available.
     */
    @SuppressLint("MissingPermission")
    fun refresh(
        previousDevicePresence: DevicePresence,
        previousTargetDevice: BluetoothDevice?,
    ): ReadinessSnapshot {
        val missing = RequiredPermissions.missing(context)
        val permissions = if (missing.isEmpty()) PermissionState.Granted else PermissionState.Missing
        val canInspectBluetooth = RequiredPermissions.hasBluetoothConnect(context)
        val bluetoothEnabled = runCatching { bluetoothAdapter?.isEnabled == true }.getOrDefault(false)

        val bonded = if (canInspectBluetooth && bluetoothEnabled) {
            runCatching { scanner.bondedTarget() }.getOrNull()
        } else {
            null
        }

        val devicePresence = when {
            bonded != null -> DevicePresence.Bonded
            previousTargetDevice?.bondState == BluetoothDevice.BOND_BONDED -> DevicePresence.Bonded
            previousDevicePresence in setOf(
                DevicePresence.Scanning,
                DevicePresence.Found,
                DevicePresence.Pairing,
                DevicePresence.PairingFailed,
            ) -> previousDevicePresence
            else -> DevicePresence.NotFound
        }

        val headsetAudio = if (permissions == PermissionState.Granted && bluetoothEnabled) {
            if (isRsmHfpConnected()) HeadsetAudioState.Available else HeadsetAudioState.Unavailable
        } else {
            HeadsetAudioState.Unavailable
        }

        return ReadinessSnapshot(
            permissions = permissions,
            missingPermissions = missing,
            bluetoothEnabled = bluetoothEnabled,
            devicePresence = devicePresence,
            bondedDevice = bonded,
            headsetAudio = headsetAudio,
        )
    }

    /**
     * Whether the target RSM device has HFP audio connected.
     *
     * Mirrors the pre-extraction [PttForegroundService.isRsmHfpConnected].
     */
    @SuppressLint("MissingPermission")
    fun isRsmHfpConnected(): Boolean {
        val proxy = headsetProxyProvider() ?: return false
        val rsm = runCatching { scanner.bondedTarget() }.getOrNull() ?: return false
        return runCatching {
            proxy.getConnectionState(rsm) == BluetoothProfile.STATE_CONNECTED
        }.getOrDefault(false)
    }
}

/**
 * Snapshot of the device-readiness state, returned by
 * [ReadinessProbe.refresh].
 *
 * The caller ([PttForegroundService.refreshReadiness]) applies this to its
 * own state model.
 */
data class ReadinessSnapshot(
    val permissions: PermissionState,
    val missingPermissions: List<String>,
    val bluetoothEnabled: Boolean,
    val devicePresence: DevicePresence,
    val bondedDevice: BluetoothDevice?,
    val headsetAudio: HeadsetAudioState,
)
