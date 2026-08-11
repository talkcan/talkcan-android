package io.talkcan.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.testTag
import io.talkcan.ui.theme.ControlSteel
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
    TalkcanInstrumentBackdrop(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            TerminalHeader(
                title = "Settings",
                subtitle = "Application configuration",
                compact = true
            )

        // Group 1: Devices and audio
        SettingsGroup(
            title = "Devices and audio",
            supportingText = "Radio, car, and phone audio routes."
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
                statusText = "Built in · no setup required",
                enabled = false,
                onClick = null
            )
        }

        // Group 2: Channels
        SettingsGroup(
            title = "Channels",
            supportingText = "Channel catalogue and assignments."
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
            supportingText = "Provider packages, profiles, and voices."
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
            supportingText = "Permissions, models, voice, and storage."
        ) {
            SettingsRow(
                label = "Permissions",
                statusText = if (permissionsReady) "All required application permissions granted" else "Permissions missing or not fully granted",
                statusTone = if (permissionsReady) {
                    TalkcanStatusTone.Ready
                } else {
                    TalkcanStatusTone.Attention
                },
                enabled = true,
                onClick = onSystemReadinessClick
            )
            SettingsRow(
                label = "Models",
                statusText = if (modelsReady) "Local speech translation and recognition models up to date" else "Speech models download or repair required",
                statusTone = if (modelsReady) {
                    TalkcanStatusTone.Ready
                } else {
                    TalkcanStatusTone.Attention
                },
                enabled = true,
                onClick = onSystemReadinessClick
            )
            SettingsRow(
                label = "Offline voice",
                statusText = if (voiceReady) "System text-to-speech fallback ready" else "Offline voice setup or verification required",
                statusTone = if (voiceReady) {
                    TalkcanStatusTone.Ready
                } else {
                    TalkcanStatusTone.Attention
                },
                enabled = true,
                onClick = onSystemReadinessClick
            )
            SettingsRow(
                label = "Storage",
                statusText = if (storageReady) "Local directory and file mount points ready" else "Durable storage access required",
                statusTone = if (storageReady) {
                    TalkcanStatusTone.Ready
                } else {
                    TalkcanStatusTone.Attention
                },
                enabled = true,
                onClick = onSystemReadinessClick
            )
        }

        // Group 5: Advanced
        SettingsGroup(
            title = "Advanced",
            supportingText = "Diagnostics and troubleshooting."
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
}

@Composable
private fun SettingsGroup(
    title: String,
    supportingText: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    TalkcanInstrumentPanel(
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            TalkcanSectionHeader(
                title = title,
                supportingText = supportingText,
                compact = true,
            )
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(0.dp)
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
    statusTone: TalkcanStatusTone? = null,
    enabled: Boolean,
    onClick: (() -> Unit)?,
) {
    val rowModifier = Modifier
        .fillMaxWidth()
        .then(
            if (enabled && onClick != null) {
                Modifier.clickable(onClick = onClick)
            } else {
                Modifier
            }
        )
        .semantics {
            if (!enabled) {
                disabled()
            }
        }
        .testTag("settings-row-${label}")
        .padding(vertical = 8.dp, horizontal = 2.dp)

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
                color = if (enabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            if (statusText != null) {
                if (statusTone != null) {
                    TalkcanStatusBadge(
                        label = statusText,
                        tone = statusTone,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                } else {
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (enabled && onClick != null) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .border(1.dp, ControlSteel, RoundedCornerShape(2.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}
