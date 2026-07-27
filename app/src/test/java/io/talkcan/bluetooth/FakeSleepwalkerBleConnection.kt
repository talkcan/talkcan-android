package io.talkcan.bluetooth

import android.bluetooth.BluetoothAdapter
import android.content.Context
import io.talkcan.model.TextOutputTransportState
import io.sleepwalker.core.hid.LowLevelOp

class FakeSleepwalkerBleConnection : SleepwalkerBleConnection() {
    val sentOps = mutableListOf<LowLevelOp>()
    var shouldThrowOnSend = false

    fun setConnectionState(state: TextOutputTransportState) {
        _connectionState.value = state
    }

    override fun connect(adapter: BluetoothAdapter?, context: Context) {
        // No-op or test-driven
    }

    override fun disconnect() {
        _connectionState.value = TextOutputTransportState.Disconnected
    }

    override suspend fun sendOp(op: LowLevelOp) {
        if (shouldThrowOnSend) {
            throw Exception("Fake connection send failed")
        }
        if (_connectionState.value == TextOutputTransportState.Connected) {
            sentOps.add(op)
        }
    }

    override suspend fun awaitAck(seqId: Int, timeoutMs: Long): Boolean {
        return true
    }
}
