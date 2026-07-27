package io.talkcan.telecom

import android.content.Intent
import android.os.Build
import android.telecom.Connection
import android.telecom.ConnectionRequest
import android.telecom.ConnectionService
import android.telecom.DisconnectCause
import io.talkcan.service.PttForegroundService

class TalkcanConnectionService : ConnectionService() {
    override fun onCreateOutgoingConnection(
        connectionManagerPhoneAccount: android.telecom.PhoneAccountHandle?,
        request: ConnectionRequest?,
    ): Connection {
        val serviceIntent = Intent(this, PttForegroundService::class.java).apply {
            action = PttForegroundService.ACTION_START_MONITORING
        }
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(serviceIntent) else startService(serviceIntent)

        val expectedBluetoothDevice = TelecomCarPttCoordinator.claimExpectedBluetoothDevice()
            ?: run {
                TelecomCarPttCoordinator.forceAbort()
                return Connection.createFailedConnection(
                    DisconnectCause(DisconnectCause.ERROR, "Missing expected car Bluetooth route"),
                )
            }

        val connection = TalkcanConnection(expectedBluetoothDevice)
        if (!TelecomCarPttCoordinator.attachConnection(connection)) {
            TelecomCarPttCoordinator.forceAbort()
            return Connection.createFailedConnection(DisconnectCause(DisconnectCause.BUSY))
        }
        return connection
    }

    override fun onCreateIncomingConnection(
        connectionManagerPhoneAccount: android.telecom.PhoneAccountHandle?,
        request: ConnectionRequest?,
    ): Connection? = super.onCreateIncomingConnection(connectionManagerPhoneAccount, request)

    override fun onCreateIncomingConnectionFailed(
        connectionManagerPhoneAccount: android.telecom.PhoneAccountHandle?,
        request: ConnectionRequest?,
    ) {
        TelecomCarPttCoordinator.forceAbort()
    }
}