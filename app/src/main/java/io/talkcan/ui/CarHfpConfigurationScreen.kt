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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CarHfpConfigurationScreen(
    state: CarHfpConfigurationState,
    actions: PttUiActions,
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(Unit) { actions.refreshCarHfpConfiguration() }
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("Car headset configuration") },
                navigationIcon = {
                    IconButton(onClick = actions::navigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ConfiguredCarCard(state)
            state.selectionFailure?.let { failure ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = failure.message(),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
            Text("CONNECTED HFP DEVICES", style = MaterialTheme.typography.titleMedium)
            InspectionGuidance(state)
            state.candidates.forEach { candidate ->
                CandidateRow(candidate, actions::selectCarHfpCandidate)
            }
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
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("CONFIGURED CAR", style = MaterialTheme.typography.titleMedium)
            if (configured == null) {
                Text("No car configured", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "Connect the car's call-audio profile, then select it below.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(configured.label, style = MaterialTheme.typography.titleLarge)
                Text(
                    when (configured.status) {
                        ConfiguredCarStatus.Connected -> "Connected for HFP calls"
                        ConfiguredCarStatus.Unavailable -> "Configured, but currently unavailable"
                        ConfiguredCarStatus.TargetRsmConflict -> "Invalid: this device is the target RSM"
                    },
                    color = when (configured.status) {
                        ConfiguredCarStatus.Connected -> MaterialTheme.colorScheme.primary
                        ConfiguredCarStatus.Unavailable,
                        ConfiguredCarStatus.TargetRsmConflict -> MaterialTheme.colorScheme.error
                    },
                )
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
        Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun CandidateRow(candidate: CarHfpCandidate, onSelect: (String) -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
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
                Text(candidate.label, style = MaterialTheme.typography.titleMedium)
                if (candidate.selected) {
                    Text(
                        "Selected car",
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
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
