package io.talkcan.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import io.talkcan.service.TalkcanLogger as Log
import io.talkcan.model.TextOutputTransportState
import io.sleepwalker.core.ble.BleUuids
import io.sleepwalker.core.ble.BleWriter
import io.sleepwalker.core.hid.LowLevelOp
import io.sleepwalker.core.hid.SessionStatusParser
import io.sleepwalker.core.hid.SessionStatus
import io.sleepwalker.core.hid.toFrameBytes
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

sealed interface SleepwalkerConnectionResult {
    data object Connected : SleepwalkerConnectionResult
    data class Failed(val reason: String) : SleepwalkerConnectionResult
    data object TimedOut : SleepwalkerConnectionResult
}

open class SleepwalkerBleConnection {
    companion object {
        private const val TAG = "TalkcanRoute"
        private const val CCCD_UUID = "00002902-0000-1000-8000-00805f9b34fb"
        const val CONNECTION_TIMEOUT_MS = 10_000L
        private const val INTER_WRITE_DELAY_MS = 15L
    }

    protected val _connectionState = MutableStateFlow(TextOutputTransportState.Disconnected)
    open val connectionState: StateFlow<TextOutputTransportState> = _connectionState.asStateFlow()

    private val _statusFlow = MutableSharedFlow<SessionStatus>(extraBufferCapacity = 64)
    val statusFlow: SharedFlow<SessionStatus> = _statusFlow.asSharedFlow()

    private var gatt: BluetoothGatt? = null
    private var rxChar: BluetoothGattCharacteristic? = null
    private var txChar: BluetoothGattCharacteristic? = null
    private var negotiatedMtu = 23
    private val gattLock = Any()
    private var scanCallback: ScanCallback? = null
    private var bluetoothAdapter: BluetoothAdapter? = null
    private val connectionScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var connectionAttempt: CompletableDeferred<SleepwalkerConnectionResult>? = null
    private var connectionTimeoutJob: Job? = null

    open suspend fun ensureConnected(
        adapter: BluetoothAdapter?,
        context: Context,
        timeoutMs: Long = CONNECTION_TIMEOUT_MS,
    ): SleepwalkerConnectionResult {
        val (attempt, shouldStart) = synchronized(gattLock) {
            if (_connectionState.value == TextOutputTransportState.Connected) {
                Log.d(TAG, "BLE_RECOVERY_RESULT result=connected source=existing")
                return SleepwalkerConnectionResult.Connected
            }
            connectionAttempt?.takeIf { it.isActive }?.let {
                Log.d(TAG, "BLE_RECOVERY_JOIN state=${_connectionState.value}")
                return@synchronized it to false
            }

            val created = CompletableDeferred<SleepwalkerConnectionResult>()
            connectionAttempt = created
            val start = _connectionState.value == TextOutputTransportState.Disconnected
            Log.d(TAG, "BLE_RECOVERY_${if (start) "START" else "JOIN"} state=${_connectionState.value}")
            connectionTimeoutJob = connectionScope.launch {
                delay(timeoutMs)
                val isCurrent = synchronized(gattLock) {
                    connectionAttempt === created && created.isActive
                }
                if (isCurrent) {
                    Log.d(TAG, "BLE_RECOVERY_TIMEOUT timeout_ms=$timeoutMs")
                    finishConnectionAttempt(
                        SleepwalkerConnectionResult.TimedOut,
                        closeTransport = true,
                        expectedAttempt = created,
                    )
                }
            }
            created to start
        }

        if (shouldStart) startConnection(adapter, context, expectedAttempt = attempt)
        return attempt.await().also { result ->
            val resultName = when (result) {
                SleepwalkerConnectionResult.Connected -> "connected"
                is SleepwalkerConnectionResult.Failed -> "failed reason=${result.reason}"
                SleepwalkerConnectionResult.TimedOut -> "timed_out"
            }
            Log.d(TAG, "BLE_RECOVERY_RESULT result=$resultName")
        }
    }

    private fun failConnection(
        reason: String,
        expectedAttempt: CompletableDeferred<SleepwalkerConnectionResult>? = null,
        expectedGatt: BluetoothGatt? = null,
        expectedScanCallback: ScanCallback? = null,
    ) {
        Log.d(TAG, "BLE_RECOVERY_FAILURE reason=$reason")
        finishConnectionAttempt(
            SleepwalkerConnectionResult.Failed(reason),
            closeTransport = true,
            expectedAttempt = expectedAttempt,
            expectedGatt = expectedGatt,
            expectedScanCallback = expectedScanCallback,
        )
    }

    @SuppressLint("MissingPermission")
    private fun finishConnectionAttempt(
        result: SleepwalkerConnectionResult,
        closeTransport: Boolean,
        expectedAttempt: CompletableDeferred<SleepwalkerConnectionResult>? = null,
        expectedGatt: BluetoothGatt? = null,
        expectedScanCallback: ScanCallback? = null,
    ): Boolean {
        val attempt = synchronized(gattLock) {
            if (expectedAttempt != null && connectionAttempt !== expectedAttempt) return false
            if (expectedGatt != null && gatt !== expectedGatt) return false
            if (expectedScanCallback != null && scanCallback !== expectedScanCallback) return false
            if (closeTransport) {
                scanCallback?.let { callback ->
                    try {
                        bluetoothAdapter?.bluetoothLeScanner?.stopScan(callback)
                    } catch (_: Exception) {
                    }
                }
                gatt?.disconnect()
                gatt?.close()
                gatt = null
                rxChar = null
                txChar = null
                negotiatedMtu = 23
                _connectionState.value = TextOutputTransportState.Disconnected
            } else {
                scanCallback = null
                _connectionState.value = TextOutputTransportState.Connected
            }
            scanCallback = null
            connectionTimeoutJob?.cancel()
            connectionTimeoutJob = null
            connectionAttempt.also { connectionAttempt = null }
        }
        attempt?.complete(result)
        return true
    }

    @SuppressLint("MissingPermission")
    open fun connect(adapter: BluetoothAdapter?, context: Context) {
        startConnection(adapter, context, expectedAttempt = null)
    }

    @SuppressLint("MissingPermission")
    private fun startConnection(
        adapter: BluetoothAdapter?,
        context: Context,
        expectedAttempt: CompletableDeferred<SleepwalkerConnectionResult>?,
    ) {
        if (
            expectedAttempt != null &&
            synchronized(gattLock) { connectionAttempt !== expectedAttempt }
        ) {
            return
        }
        if (adapter == null) {
            failConnection("Bluetooth adapter unavailable", expectedAttempt = expectedAttempt)
            return
        }
        bluetoothAdapter = adapter
        synchronized(gattLock) {
            if (expectedAttempt != null && connectionAttempt !== expectedAttempt) return
            val currentState = _connectionState.value
            if (currentState != TextOutputTransportState.Disconnected) {
                Log.d(TAG, "BLE_CONNECT_SKIP current_state=$currentState")
                return
            }
            _connectionState.value = TextOutputTransportState.Scanning
        }

        Log.d(TAG, "BLE_SCAN_START")
        val scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            failConnection("BLE scanner unavailable", expectedAttempt = expectedAttempt)
            return
        }

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val dev = result.device
                val name = dev.name ?: ""
                if (!name.contains("sleepwalker", ignoreCase = true)) return

                val attemptAtDiscovery = synchronized(gattLock) {
                    if (
                        scanCallback !== this ||
                        _connectionState.value != TextOutputTransportState.Scanning
                    ) {
                        return
                    }
                    _connectionState.value = TextOutputTransportState.Connecting
                    scanCallback = null
                    connectionAttempt
                }

                Log.d(TAG, "BLE_SCAN_FOUND name=$name")
                try {
                    scanner.stopScan(this)
                } catch (error: Exception) {
                    Log.d(TAG, "BLE_SCAN_STOP_ERROR type=${error::class.simpleName}")
                }
                connectToDevice(dev, context, attemptAtDiscovery)
            }

            override fun onScanFailed(errorCode: Int) {
                Log.d(TAG, "BLE_SCAN_FAILED error_code=$errorCode")
                val isCurrent = synchronized(gattLock) {
                    scanCallback === this &&
                        _connectionState.value == TextOutputTransportState.Scanning
                }
                if (isCurrent) {
                    failConnection(
                        "BLE scan failed ($errorCode)",
                        expectedScanCallback = this,
                    )
                }
            }
        }
        synchronized(gattLock) {
            if (_connectionState.value != TextOutputTransportState.Scanning) return
            scanCallback = callback
        }

        val filter = ScanFilter.Builder()
            .setDeviceName("sleepwalker")
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        try {
            synchronized(gattLock) {
                if (
                    scanCallback !== callback ||
                    _connectionState.value != TextOutputTransportState.Scanning
                ) {
                    return
                }
                scanner.startScan(listOf(filter), settings, callback)
            }
        } catch (_: SecurityException) {
            failConnection(
                "Bluetooth scan permission denied",
                expectedScanCallback = callback,
            )
        }
    }

    @SuppressLint("MissingPermission")
    private fun connectToDevice(
        device: BluetoothDevice,
        context: Context,
        expectedAttempt: CompletableDeferred<SleepwalkerConnectionResult>?,
    ) {
        Log.d(TAG, "BLE_CONNECT_TO_DEVICE")
        try {
            val created = synchronized(gattLock) {
                if (_connectionState.value != TextOutputTransportState.Connecting) return
                if (expectedAttempt != null && connectionAttempt !== expectedAttempt) return
                val connection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
                } else {
                    device.connectGatt(context, false, gattCallback)
                }
                gatt = connection
                connection
            }
            if (created == null) {
                failConnection(
                    "GATT connection could not start",
                    expectedAttempt = expectedAttempt,
                )
            }
        } catch (_: SecurityException) {
            failConnection(
                "Bluetooth connection permission denied",
                expectedAttempt = expectedAttempt,
            )
        }
    }

    @SuppressLint("MissingPermission")
    open fun disconnect() {
        finishConnectionAttempt(
            SleepwalkerConnectionResult.Failed("Sleepwalker bridge disconnected"),
            closeTransport = true,
        )
        Log.d(TAG, "BLE_DISCONNECTED_DONE")
    }

    @SuppressLint("MissingPermission")
    open suspend fun sendOp(op: LowLevelOp) {
        val (g, rx) = synchronized(gattLock) {
            val currentGatt = gatt
            val currentRx = rxChar
            if (
                _connectionState.value != TextOutputTransportState.Connected ||
                currentGatt == null ||
                currentRx == null
            ) {
                Log.i(TAG, "BLE_WRITE_SKIP opcode=${op.opcode} reason=not_connected")
                return
            }
            currentGatt to currentRx
        }
        val frame = op.toFrameBytes()
        val chunks = BleWriter.chunkFrame(frame, negotiatedMtu)
        for (chunk in chunks) {
            synchronized(gattLock) {
                if (
                    _connectionState.value != TextOutputTransportState.Connected ||
                    gatt !== g ||
                    rxChar !== rx
                ) {
                    Log.i(TAG, "BLE_WRITE_SKIP opcode=${op.opcode} reason=disconnected_during_write")
                    return
                }
                @Suppress("DEPRECATION")
                rx.value = chunk
                rx.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                g.writeCharacteristic(rx)
            }
            delay(INTER_WRITE_DELAY_MS)
        }
    }

    open suspend fun awaitAck(seqId: Int, timeoutMs: Long = 3000): Boolean {
        if (_connectionState.value != TextOutputTransportState.Connected) {
            Log.i(TAG, "BLE_ACK_RESULT seqId=$seqId result=disconnected")
            return false
        }
        val acknowledged = withTimeoutOrNull(timeoutMs) {
            statusFlow.first { it.seqId == seqId && it.status == io.sleepwalker.core.protocol.Status.SENT_TO_USB }
            true
        } ?: false
        Log.i(TAG, "BLE_ACK_RESULT seqId=$seqId result=${if (acknowledged) "acknowledged" else "timed_out"}")
        return acknowledged
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            Log.d(TAG, "BLE_CONN_STATE status=$status newState=$newState")
            val isCurrent = synchronized(gattLock) {
                this@SleepwalkerBleConnection.gatt === gatt
            }
            if (!isCurrent) {
                Log.d(TAG, "BLE_CALLBACK_SKIP callback=connection_state reason=stale_gatt")
                gatt.close()
                return
            }
            if (status != BluetoothGatt.GATT_SUCCESS) {
                failConnection("GATT connection failed ($status)", expectedGatt = gatt)
                return
            }
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    if (!gatt.discoverServices()) {
                        failConnection(
                            "GATT service discovery could not start",
                            expectedGatt = gatt,
                        )
                    }
                }
                BluetoothProfile.STATE_DISCONNECTED ->
                    failConnection("GATT disconnected", expectedGatt = gatt)
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            Log.d(TAG, "BLE_SERVICES_DISCOVERED status=$status")
            if (synchronized(gattLock) { this@SleepwalkerBleConnection.gatt !== gatt }) {
                Log.d(TAG, "BLE_CALLBACK_SKIP callback=services_discovered reason=stale_gatt")
                return
            }
            if (status != BluetoothGatt.GATT_SUCCESS) {
                failConnection("GATT service discovery failed ($status)", expectedGatt = gatt)
                return
            }
            val service = gatt.getService(UUID.fromString(BleUuids.SERVICE))
                ?: run {
                    failConnection("Sleepwalker BLE service not found", expectedGatt = gatt)
                    return
                }
            val rx = service.getCharacteristic(UUID.fromString(BleUuids.RX_CHARACTERISTIC))
            val tx = service.getCharacteristic(UUID.fromString(BleUuids.TX_CHARACTERISTIC))
            if (rx == null || tx == null) {
                failConnection("Sleepwalker BLE characteristics not found", expectedGatt = gatt)
                return
            }
            synchronized(gattLock) {
                if (this@SleepwalkerBleConnection.gatt !== gatt) return
                rxChar = rx
                txChar = tx
            }
            if (!gatt.requestMtu(247)) {
                failConnection("BLE MTU request could not start", expectedGatt = gatt)
            }
        }

        @SuppressLint("MissingPermission")
        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            Log.d(TAG, "BLE_MTU_CHANGED mtu=$mtu status=$status")
            if (synchronized(gattLock) { this@SleepwalkerBleConnection.gatt !== gatt }) {
                Log.d(TAG, "BLE_CALLBACK_SKIP callback=mtu_changed reason=stale_gatt")
                return
            }
            if (status != BluetoothGatt.GATT_SUCCESS) {
                failConnection("BLE MTU negotiation failed ($status)", expectedGatt = gatt)
                return
            }
            negotiatedMtu = mtu
            val tx = synchronized(gattLock) { txChar }
                ?: run {
                    failConnection("Sleepwalker TX characteristic unavailable", expectedGatt = gatt)
                    return
                }
            if (!gatt.setCharacteristicNotification(tx, true)) {
                failConnection("BLE notifications could not be enabled", expectedGatt = gatt)
                return
            }
            val cccd = tx.getDescriptor(UUID.fromString(CCCD_UUID))
                ?: run {
                    failConnection("BLE notification descriptor not found", expectedGatt = gatt)
                    return
                }
            @Suppress("DEPRECATION")
            cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            if (!gatt.writeDescriptor(cccd)) {
                failConnection(
                    "BLE notification descriptor write could not start",
                    expectedGatt = gatt,
                )
            }
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int,
        ) {
            Log.d(TAG, "BLE_DESCRIPTOR_WRITE status=$status")
            if (synchronized(gattLock) { this@SleepwalkerBleConnection.gatt !== gatt }) {
                Log.d(TAG, "BLE_CALLBACK_SKIP callback=descriptor_write reason=stale_gatt")
                return
            }
            if (status != BluetoothGatt.GATT_SUCCESS) {
                failConnection(
                    "BLE notification descriptor write failed ($status)",
                    expectedGatt = gatt,
                )
                return
            }
            finishConnectionAttempt(
                SleepwalkerConnectionResult.Connected,
                closeTransport = false,
                expectedGatt = gatt,
            )
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            if (synchronized(gattLock) { this@SleepwalkerBleConnection.gatt !== gatt }) return
            @Suppress("DEPRECATION")
            handleNotification(characteristic.value)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            if (synchronized(gattLock) { this@SleepwalkerBleConnection.gatt !== gatt }) return
            handleNotification(value)
        }
    }

    private fun handleNotification(data: ByteArray?) {
        if (data == null) return
        val status = SessionStatusParser.parse(data)
        if (status != null) {
            Log.i(TAG, "BLE_STATUS seqId=${status.seqId} statusName=${status.statusName}")
            _statusFlow.tryEmit(status)
        }
    }
}
