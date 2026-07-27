package io.talkcan.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import io.talkcan.model.TARGET_DEVICE_NAME
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class DeviceScanner(
    private val context: Context,
    private val adapter: BluetoothAdapter?,
) {
    @SuppressLint("MissingPermission")
    fun bondedTarget(): BluetoothDevice? = adapter
        ?.bondedDevices
        ?.firstOrNull { it.name == TARGET_DEVICE_NAME }

    @SuppressLint("MissingPermission")
    suspend fun scanForTarget(timeoutMs: Long = 12_000): BluetoothDevice? {
        val bluetoothAdapter = adapter ?: return null
        bondedTarget()?.let { return it }

        return suspendCancellableCoroutine { continuation ->
            var cleanedUp = false
            lateinit var receiver: BroadcastReceiver

            fun cleanup() {
                if (cleanedUp) return
                cleanedUp = true
                runCatching { context.unregisterReceiver(receiver) }
                runCatching { bluetoothAdapter.cancelDiscovery() }
            }

            receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    when (intent.action) {
                        BluetoothDevice.ACTION_FOUND -> {
                            val device = intent.getParcelableExtraCompat<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                            if (device?.name == TARGET_DEVICE_NAME && continuation.isActive) {
                                cleanup()
                                continuation.resume(device)
                            }
                        }

                        BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                            if (continuation.isActive) {
                                cleanup()
                                continuation.resume(null)
                            }
                        }
                    }
                }
            }

            val filter = IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_FOUND)
                addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
            }
            context.registerReceiverCompat(receiver, filter)

            bluetoothAdapter.cancelDiscovery()
            val started = bluetoothAdapter.startDiscovery()
            if (!started && continuation.isActive) {
                cleanup()
                continuation.resume(null)
            }

            val timeoutJob = CoroutineScope(Dispatchers.Main.immediate).launch {
                delay(timeoutMs)
                if (continuation.isActive) {
                    cleanup()
                    continuation.resume(null)
                }
            }
            continuation.invokeOnCancellation {
                timeoutJob.cancel()
                cleanup()
            }
        }
    }

    @SuppressLint("MissingPermission")
    suspend fun createBondAndWait(device: BluetoothDevice, timeoutMs: Long = 30_000): Boolean {
        if (device.bondState == BluetoothDevice.BOND_BONDED) return true

        return suspendCancellableCoroutine { continuation ->
            var cleanedUp = false
            lateinit var receiver: BroadcastReceiver

            fun cleanup() {
                if (cleanedUp) return
                cleanedUp = true
                runCatching { context.unregisterReceiver(receiver) }
            }

            receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    if (intent.action != BluetoothDevice.ACTION_BOND_STATE_CHANGED) return
                    val changed = intent.getParcelableExtraCompat<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                    if (changed?.address != device.address) return

                    when (changed.bondState) {
                        BluetoothDevice.BOND_BONDED -> if (continuation.isActive) {
                            cleanup()
                            continuation.resume(true)
                        }

                        BluetoothDevice.BOND_NONE -> if (continuation.isActive) {
                            cleanup()
                            continuation.resume(false)
                        }
                    }
                }
            }

            context.registerReceiverCompat(receiver, IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED))
            val started = runCatching { device.createBond() }.getOrDefault(false)
            if (!started && continuation.isActive) {
                cleanup()
                continuation.resume(false)
            }

            val timeoutJob = CoroutineScope(Dispatchers.Main.immediate).launch {
                delay(timeoutMs)
                if (continuation.isActive) {
                    cleanup()
                    continuation.resume(false)
                }
            }
            continuation.invokeOnCancellation {
                timeoutJob.cancel()
                cleanup()
            }
        }
    }
}

private fun Context.registerReceiverCompat(receiver: BroadcastReceiver, filter: IntentFilter) {
    if (Build.VERSION.SDK_INT >= 33) {
        registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
    } else {
        @Suppress("DEPRECATION")
        registerReceiver(receiver, filter)
    }
}

private inline fun <reified T> Intent.getParcelableExtraCompat(name: String): T? =
    if (Build.VERSION.SDK_INT >= 33) {
        getParcelableExtra(name, T::class.java)
    } else {
        @Suppress("DEPRECATION")
        getParcelableExtra(name) as? T
    }
