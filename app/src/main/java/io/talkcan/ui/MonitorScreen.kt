package io.talkcan.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import io.talkcan.model.ButtonStates
import io.talkcan.model.ClickButtonState
import io.talkcan.model.EchoStatus
import io.talkcan.model.HardwareMode
import io.talkcan.model.MonitorState
import io.talkcan.model.ScoState
import io.talkcan.model.TwoStateButton
import io.talkcan.model.displayText

@Composable
fun MonitorScreen(
    state: MonitorState,
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
            title = "Hardware monitor",
            subtitle = "${state.hardwareMode.name.lowercase()} mode · echo ${state.echoStatus.displayText().lowercase()}",
        )

        PttStatusCard(state)
        ButtonTable(state.buttons)
        AudioStatus(state)

        OutlinedButton(onClick = actions::disconnectSerial, modifier = Modifier.fillMaxWidth()) {
            Text("Disconnect serial")
        }
    }
}

@Composable
private fun PttStatusCard(state: MonitorState) {
    val pttTone = when (state.buttons.ptt) {
        TwoStateButton.Pressed -> TalkcanStatusTone.Active
        TwoStateButton.Released -> TalkcanStatusTone.Neutral
    }
    val modeTone = when (state.hardwareMode) {
        HardwareMode.Active -> TalkcanStatusTone.Active
        HardwareMode.Control -> TalkcanStatusTone.Neutral
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(
            1.dp,
            if (state.buttons.ptt == TwoStateButton.Pressed) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.outlineVariant
            },
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            TalkcanSectionHeader(title = "PTT status")
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TalkcanStatusBadge(
                    label = state.buttons.ptt.name.lowercase(),
                    tone = pttTone,
                )
                TalkcanStatusBadge(
                    label = "${state.hardwareMode.name.lowercase()} mode",
                    tone = modeTone,
                )
                TalkcanStatusBadge(
                    label = "echo ${state.echoStatus.displayText().lowercase()}",
                    tone = echoTone(state.echoStatus),
                )
            }
            MonitorRow("Hardware mode", state.hardwareMode.name.lowercase())
        }
    }
}

@Composable
private fun ButtonTable(buttons: ButtonStates) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            TalkcanSectionHeader(title = "Button state")
            MonitorRow("PTT", buttons.ptt.name.lowercase())
            MonitorRow("SOS", buttons.sos.name.toDisplayToken())
            MonitorRow("Group", buttons.group.name.lowercase())
            MonitorRow("Volume up", buttons.volumeUp.displayText())
            MonitorRow("Volume down", buttons.volumeDown.displayText())
        }
    }
}

@Composable
private fun AudioStatus(state: MonitorState) {
    val scoTone = when (state.scoState) {
        ScoState.Inactive -> TalkcanStatusTone.Neutral
        ScoState.Starting -> TalkcanStatusTone.Attention
        ScoState.Active -> TalkcanStatusTone.Ready
        ScoState.Closing -> TalkcanStatusTone.Attention
        is ScoState.Failed -> TalkcanStatusTone.Error
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            TalkcanSectionHeader(title = "Audio route")
            TalkcanStatusBadge(label = state.scoState.displayText(), tone = scoTone)
            MonitorRow("SCO", state.scoState.displayText())
        }
    }
}

@Composable
private fun MonitorRow(label: String, value: String) {
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

private fun echoTone(status: EchoStatus): TalkcanStatusTone = when (status) {
    EchoStatus.Idle -> TalkcanStatusTone.Neutral
    EchoStatus.WaitingForAudio -> TalkcanStatusTone.Attention
    EchoStatus.Beeping -> TalkcanStatusTone.Attention
    EchoStatus.Recording -> TalkcanStatusTone.Recording
    EchoStatus.MaxDurationReached -> TalkcanStatusTone.Attention
    EchoStatus.Playback -> TalkcanStatusTone.Attention
    EchoStatus.Warm -> TalkcanStatusTone.Ready
    EchoStatus.Cancelled -> TalkcanStatusTone.Neutral
    is EchoStatus.Error -> TalkcanStatusTone.Error
}

private fun String.toDisplayToken(): String = when (this) {
    "LONGPRESSED" -> "long-pressed"
    else -> lowercase()
}

private fun ClickButtonState.displayText(): String = when (this) {
    ClickButtonState.Idle -> "idle"
    ClickButtonState.Clicked -> "clicked"
}