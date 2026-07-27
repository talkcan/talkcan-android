package io.talkcan.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.talkcan.model.ConnectionState
import io.talkcan.model.DevicePresence
import io.talkcan.model.HeadsetAudioState
import io.talkcan.model.SppState
import io.talkcan.model.TARGET_DEVICE_NAME

@Composable
fun ConnectionScreen(
    state: ConnectionState,
    actions: PttUiActions,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        TerminalHeader(
            title = "TALKCAN LINK",
            subtitle = "Bluetooth Classic SPP + HFP/SCO validation terminal",
        )

        StatusPanel(state)
        GuidancePanel(state)
        ActionPanel(state, actions)
    }
}

@Composable
private fun StatusPanel(state: ConnectionState) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("READINESS MATRIX", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(12.dp))
            StatusRow("Android Bluetooth", if (state.bluetoothEnabled) "on" else "off")
            StatusRow("Target device", state.devicePresence.displayText())
            StatusRow("Serial control channel", state.spp.displayText(state.sppError))
            StatusRow("Headset audio capability", state.headsetAudio.displayText())
            StatusRow("Overall readiness", if (state.readyForMonitor) "ready" else "not ready")
        }
    }
}

@Composable
private fun GuidancePanel(state: ConnectionState) {
    val text = when {
        !state.bluetoothEnabled ->
            "Enable Bluetooth in Android settings."
        state.devicePresence == DevicePresence.NotFound ->
            "Put $TARGET_DEVICE_NAME in pairing mode, then scan."
        state.devicePresence == DevicePresence.Found ->
            "Device found. Pair it before opening the serial channel."
        state.devicePresence == DevicePresence.PairingFailed ->
            "Pair your device in Android Bluetooth settings, then retry."
        state.spp == SppState.Failed ->
            "Serial connection failed. Retry after confirming the device is still powered on."
        state.spp == SppState.Connected && state.headsetAudio == HeadsetAudioState.Unavailable ->
            "Headset audio is not exposed as Bluetooth SCO. Open Bluetooth settings and connect the headset profile."
        else -> "Ready checks update continuously while the service is bound."
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(16.dp),
        )
    }
}

@Composable
private fun ActionPanel(state: ConnectionState, actions: PttUiActions) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (!state.bluetoothEnabled || state.devicePresence == DevicePresence.PairingFailed ||
            state.headsetAudio == HeadsetAudioState.Unavailable
        ) {
            OutlinedButton(onClick = actions::openBluetoothSettings, modifier = Modifier.fillMaxWidth()) {
                Text("Open Bluetooth settings")
            }
        }

        if (state.bluetoothEnabled &&
            state.devicePresence != DevicePresence.Bonded
        ) {
            Button(onClick = actions::scanForDevice, modifier = Modifier.fillMaxWidth()) {
                Text(if (state.devicePresence == DevicePresence.Scanning) "Scanning..." else "Scan for device")
            }
        }

        if (state.devicePresence == DevicePresence.Found || state.devicePresence == DevicePresence.PairingFailed) {
            Button(onClick = actions::pairTarget, modifier = Modifier.fillMaxWidth()) {
                Text(if (state.devicePresence == DevicePresence.Pairing) "Pairing..." else "Pair device")
            }
        }

        if (state.devicePresence == DevicePresence.Bonded && state.spp != SppState.Connected) {
            Button(onClick = actions::connectSerial, modifier = Modifier.fillMaxWidth()) {
                Text(if (state.spp == SppState.Connecting) "Connecting serial..." else "Connect serial")
            }
        }

        OutlinedButton(onClick = actions::retry, modifier = Modifier.fillMaxWidth()) {
            Text("Retry readiness checks")
        }
    }
}

@Composable
private fun StatusRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TerminalHeader(title: String, subtitle: String, onLongPress: (() -> Unit)? = null) {
    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = if (onLongPress != null) {
            Modifier.combinedClickable(
                onClick = {},
                onLongClick = onLongPress,
            )
        } else {
            Modifier
        },
    ) {
        Text(title, style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.primary)
        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun DevicePresence.displayText(): String = when (this) {
    DevicePresence.NotFound -> "not found"
    DevicePresence.Scanning -> "scanning"
    DevicePresence.Found -> "found"
    DevicePresence.Pairing -> "pairing"
    DevicePresence.Bonded -> "bonded"
    DevicePresence.PairingFailed -> "pairing failed"
}

private fun SppState.displayText(error: String?): String = when (this) {
    SppState.Disconnected -> "disconnected"
    SppState.Connecting -> "connecting"
    SppState.Connected -> "connected"
    SppState.Failed -> error ?: "failed"
}

private fun HeadsetAudioState.displayText(): String = when (this) {
    HeadsetAudioState.Unavailable -> "unavailable"
    HeadsetAudioState.Available -> "available"
}
