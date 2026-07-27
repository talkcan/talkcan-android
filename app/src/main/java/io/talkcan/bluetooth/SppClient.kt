package io.talkcan.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import io.talkcan.model.RawButtonEvent
import io.talkcan.model.SPP_UUID
import io.talkcan.model.SppState
import io.talkcan.protocol.ButtonParser
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class SppClient(
    private val adapter: BluetoothAdapter,
    private val parser: ButtonParser,
) {
    private val _state = MutableStateFlow(SppState.Disconnected)
    val state: StateFlow<SppState> = _state.asStateFlow()
    private var socket: BluetoothSocket? = null

    @SuppressLint("MissingPermission")
    fun events(device: BluetoothDevice): Flow<RawButtonEvent> = channelFlow {
        val readJob = launch(Dispatchers.IO) {
            try {
                _state.value = SppState.Connecting
                adapter.cancelDiscovery()

                val connectedSocket = device.createRfcommSocketToServiceRecord(SPP_UUID)
                connectedSocket.connect()
                socket = connectedSocket
                _state.value = SppState.Connected

                val input = connectedSocket.inputStream
                val buffer = ByteArray(256)
                while (isActive) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    val events = parser.push(buffer.copyOf(read))
                    for (event in events) {
                        send(event)
                    }
                }
            } catch (error: IOException) {
                if (isActive) _state.value = SppState.Failed
            } catch (error: SecurityException) {
                if (isActive) _state.value = SppState.Failed
            } finally {
                closeSocket()
                if (_state.value == SppState.Connecting || _state.value == SppState.Connected) {
                    _state.value = SppState.Disconnected
                }
                close()
            }
        }

        awaitClose {
            readJob.cancel()
            closeSocket()
        }
    }

    fun disconnect() {
        closeSocket()
        _state.value = SppState.Disconnected
    }

    private fun closeSocket() {
        runCatching { socket?.close() }
        socket = null
    }
}