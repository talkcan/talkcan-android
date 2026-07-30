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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.talkcan.model.CarHfpCandidate
import io.talkcan.model.CarHfpConfigurationState
import io.talkcan.model.CarHfpInspectionStatus
import io.talkcan.model.CarHfpSelectionFailure
import io.talkcan.model.ConfiguredCarStatus

@Composable
fun CarHfpConfigurationScreen(
    state: CarHfpConfigurationState,
    actions: PttUiActions,
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(Unit) { actions.refreshCarHfpConfiguration() }
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        TerminalHeader(
            title = "Connect your car",
            subtitle = "Select your car's call-audio profile so Talkcan routes voice through it.",
        )

        ConfiguredCarCard(state)
        state.selectionFailure?.let { failure ->
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    TalkcanSectionHeader(title = "Selection failed")
                    Text(
                        text = failure.message(),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
        }

        TalkcanSectionHeader(
            title = "Connected devices",
            supportingText = "Pick the car's headset profile from devices currently paired over Bluetooth.",
        )
        InspectionGuidance(state)
        state.candidates.forEach { candidate ->
            CandidateRow(candidate, actions::selectCarHfpCandidate)
        }

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(
                onClick = actions::refreshCarHfpConfiguration,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Retry device inspection")
            }
            OutlinedButton(
                onClick = actions::openBluetoothSettings,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Open Bluetooth settings")
            }
        }
    }
}

@Composable
private fun ConfiguredCarCard(state: CarHfpConfigurationState) {
    val configured = state.configuredCar
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            TalkcanSectionHeader(title = "Current car")
            if (configured == null) {
                Text(
                    "No car configured",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "Connect the car's call-audio profile, then select it below.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    configured.label,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                val tone = when (configured.status) {
                    ConfiguredCarStatus.Connected -> TalkcanStatusTone.Ready
                    ConfiguredCarStatus.Unavailable -> TalkcanStatusTone.Attention
                    ConfiguredCarStatus.TargetRsmConflict -> TalkcanStatusTone.Error
                }
                val statusLabel = when (configured.status) {
                    ConfiguredCarStatus.Connected -> "Connected for HFP calls"
                    ConfiguredCarStatus.Unavailable -> "Configured, but currently unavailable"
                    ConfiguredCarStatus.TargetRsmConflict -> "Invalid: this device is the target RSM"
                }
                TalkcanStatusBadge(label = statusLabel, tone = tone)
            }
        }
    }
}

@Composable
private fun InspectionGuidance(state: CarHfpConfigurationState) {
    val guidance = when (state.inspectionStatus) {
        CarHfpInspectionStatus.Available -> if (state.candidates.isEmpty()) {
            "No eligible connected HFP devices. Connect the car's calls profile in Bluetooth settings."
        } else {
            null
        }
        CarHfpInspectionStatus.PermissionUnavailable ->
            "Bluetooth device inspection is unavailable because Bluetooth permission is missing."
        CarHfpInspectionStatus.ProfileUnavailable ->
            "The Android headset profile is not available yet. Retry after Bluetooth is enabled."
        CarHfpInspectionStatus.InspectionFailed ->
            "Android could not inspect connected headset devices. Retry or reopen Bluetooth settings."
    }
    guidance?.let {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
            ),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = it,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.padding(18.dp),
            )
        }
    }
}

@Composable
private fun CandidateRow(candidate: CarHfpCandidate, onSelect: (String) -> Unit) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    candidate.label,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                if (candidate.selected) {
                    Text(
                        "Selected car",
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            OutlinedButton(
                onClick = { onSelect(candidate.selectionId) },
                enabled = !candidate.selected,
            ) {
                Text(if (candidate.selected) "Selected" else "Select")
            }
        }
    }
}

private fun CarHfpSelectionFailure.message(): String = when (this) {
    CarHfpSelectionFailure.CandidateUnavailable -> "That device is no longer available. The previous car was kept."
    CarHfpSelectionFailure.CandidateDisconnected -> "That device disconnected before selection. The previous car was kept."
    CarHfpSelectionFailure.TargetRsmConflict -> "The target RSM cannot be configured as the car."
    CarHfpSelectionFailure.PermissionUnavailable -> "Bluetooth permission is unavailable. The previous car was kept."
    CarHfpSelectionFailure.ProfileUnavailable -> "The headset profile is unavailable. The previous car was kept."
    CarHfpSelectionFailure.InspectionFailed -> "Device inspection failed. The previous car was kept."
    CarHfpSelectionFailure.PersistenceFailed -> "The car configuration could not be saved. The previous car was kept."
}