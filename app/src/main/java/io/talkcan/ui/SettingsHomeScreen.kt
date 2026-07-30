package io.talkcan.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.testTag

@Composable
fun SettingsHomeScreen(
    onRsmClick: () -> Unit,
    onCarClick: () -> Unit,
    onChannelManagementClick: () -> Unit,
    onInstalledProvidersClick: () -> Unit,
    onProviderProfilesClick: () -> Unit,
    onVoiceProfilesClick: () -> Unit,
    onLogsClick: () -> Unit,
    onSystemReadinessClick: () -> Unit,
    permissionsReady: Boolean,
    modelsReady: Boolean,
    voiceReady: Boolean,
    storageReady: Boolean,
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
            title = "Settings",
            subtitle = "Configure devices, channels, integrations, and system defaults.",
        )

        // Group 1: Devices and audio
        SettingsGroup(
            title = "Devices and audio",
            supportingText = "Manage radio hardware, headset connections, and output endpoints."
        ) {
            SettingsRow(
                label = "Radio",
                statusText = "Setup and monitor target RSM hardware",
                enabled = true,
                onClick = onRsmClick
            )
            SettingsRow(
                label = "Car",
                statusText = "Configure call-audio profile candidates",
                enabled = true,
                onClick = onCarClick
            )
            SettingsRow(
                label = "Phone",
                statusText = "Integrated speaker and microphone",
                enabled = false,
                onClick = null
            )
        }

        // Group 2: Channels
        SettingsGroup(
            title = "Channels",
            supportingText = "Manage voice channel assignments and catalogue definitions."
        ) {
            SettingsRow(
                label = "Channel management",
                statusText = "Add, rename, reorder, and remove channels",
                enabled = true,
                onClick = onChannelManagementClick
            )
        }

        // Group 3: Integrations and profiles
        SettingsGroup(
            title = "Integrations and profiles",
            supportingText = "Manage third-party provider packages, metadata profiles, and speech synthesizers."
        ) {
            SettingsRow(
                label = "Installed providers",
                statusText = "Install and inspect GitHub provider packages",
                enabled = true,
                onClick = onInstalledProvidersClick
            )
            SettingsRow(
                label = "Provider profiles",
                statusText = "Configure global integration identities and secrets",
                enabled = true,
                onClick = onProviderProfilesClick
            )
            SettingsRow(
                label = "Voice profiles",
                statusText = "Manage text-to-speech voice profiles and mixer settings",
                enabled = true,
                onClick = onVoiceProfilesClick
            )
        }

        // Group 4: System
        SettingsGroup(
            title = "System",
            supportingText = "Verify runtime host environment prerequisites."
        ) {
            SettingsRow(
                label = "Permissions",
                statusText = if (permissionsReady) "All required application permissions granted" else "Permissions missing or not fully granted",
                enabled = true,
                onClick = onSystemReadinessClick
            )
            SettingsRow(
                label = "Models",
                statusText = if (modelsReady) "Local speech translation and recognition models up to date" else "Speech models download or repair required",
                enabled = true,
                onClick = onSystemReadinessClick
            )
            SettingsRow(
                label = "Offline voice",
                statusText = if (voiceReady) "System text-to-speech fallback ready" else "Offline voice setup or verification required",
                enabled = true,
                onClick = onSystemReadinessClick
            )
            SettingsRow(
                label = "Storage",
                statusText = if (storageReady) "Local directory and file mount points ready" else "Durable storage access required",
                enabled = true,
                onClick = onSystemReadinessClick
            )
        }

        // Group 5: Advanced
        SettingsGroup(
            title = "Advanced",
            supportingText = "Developer troubleshooting and diagnostics."
        ) {
            SettingsRow(
                label = "Diagnostic logs",
                statusText = "Browse and configure running service activity logs",
                enabled = true,
                onClick = onLogsClick
            )
        }
    }
}

@Composable
private fun SettingsGroup(
    title: String,
    supportingText: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            TalkcanSectionHeader(
                title = title,
                supportingText = supportingText,
            )
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                content()
            }
        }
    }
}

@Composable
private fun ColumnScope.SettingsRow(
    label: String,
    statusText: String?,
    enabled: Boolean,
    onClick: (() -> Unit)?,
) {
    val contentAlpha = if (enabled) 1f else 0.38f
    val rowModifier = Modifier
        .fillMaxWidth()
        .then(
            if (enabled && onClick != null) {
                Modifier.clickable(onClick = onClick)
            } else {
                Modifier
            }
        )
        .testTag("settings-row-${label}")
        .padding(vertical = 12.dp, horizontal = 4.dp)

    Row(
        modifier = rowModifier,
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = contentAlpha)
            )
            if (statusText != null) {
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = contentAlpha)
                )
            }
        }
        if (enabled && onClick != null) {
            Text(
                text = "→",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
}
