package io.talkcan.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.talkcan.model.OfflineNavigationVoiceIssue
import io.talkcan.model.ModelAcquisitionProgress

@Composable
fun SystemReadinessScreen(
    permissionsReady: Boolean,
    missingPermissions: List<String>,
    storageReady: Boolean,
    modelsReady: Boolean,
    invalidModelSets: List<String>,
    voiceReady: Boolean,
    offlineNavigationVoiceIssue: OfflineNavigationVoiceIssue?,
    voiceSetupRequiresManualNavigation: Boolean,
    error: String?,
    modelProgress: ModelAcquisitionProgress,
    onGrantPermissions: () -> Unit,
    onGrantManageExternalStorage: () -> Unit,
    onStartModelDownload: () -> Unit,
    onResolveVoiceSetup: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val permissionsDone = permissionsReady
    val storageDone = storageReady
    val setupPrerequisitesDone = permissionsDone && storageDone
    val modelsDone = modelsReady
    val voiceDone = voiceReady
    val voicePrerequisitesDone = setupPrerequisitesDone && modelsDone

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        TerminalHeader(
            title = "System readiness",
            subtitle = "Verify and configure core device check categories.",
        )

        // Step 1: Permissions
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface,
            ),
            border = BorderStroke(
                1.dp,
                if (permissionsDone) {
                    MaterialTheme.colorScheme.tertiary
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
                TalkcanSectionHeader(
                    title = "Permissions",
                    supportingText = "Bluetooth, microphone, and notifications.",
                )
                TalkcanStatusBadge(
                    label = if (permissionsDone) "Completed" else "Needs setup",
                    tone = if (permissionsDone) {
                        TalkcanStatusTone.Ready
                    } else {
                        TalkcanStatusTone.Attention
                    },
                )
                Text(
                    if (permissionsDone) {
                        "All runtime permissions granted."
                    } else {
                        "Bluetooth, microphone, and notification permissions are required."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (!permissionsDone) {
                    Button(
                        onClick = onGrantPermissions,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Grant permissions")
                    }
                }
            }
        }

        // Step 2: Storage Access
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface,
            ),
            border = BorderStroke(
                1.dp,
                if (storageDone) {
                    MaterialTheme.colorScheme.tertiary
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
                TalkcanSectionHeader(
                    title = "Storage access",
                    supportingText = "Filesystem paths for journal writes.",
                )
                TalkcanStatusBadge(
                    label = if (storageDone) "Completed" else "Needs setup",
                    tone = if (storageDone) {
                        TalkcanStatusTone.Ready
                    } else {
                        TalkcanStatusTone.Attention
                    },
                )
                Text(
                    "Journal writes to a user-selected filesystem directory. Android all-files access is required for these real paths.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (!storageDone) {
                    Button(
                        onClick = onGrantManageExternalStorage,
                        enabled = permissionsDone,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Allow storage access")
                    }
                }
            }
        }

        // Step 3: Speech Models
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface,
            ),
            border = BorderStroke(
                1.dp,
                if (modelsDone) {
                    MaterialTheme.colorScheme.tertiary
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
                TalkcanSectionHeader(
                    title = "Speech models",
                    supportingText = "Parakeet and Supertonic downloads.",
                )
                TalkcanStatusBadge(
                    label = if (modelsDone) "Completed" else "Needs setup",
                    tone = if (modelsDone) {
                        TalkcanStatusTone.Ready
                    } else {
                        TalkcanStatusTone.Attention
                    },
                )
                Text(
                    "Download the Parakeet (STT) and Supertonic (TTS) models (~950 MB). A network connection is required.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (!modelsDone) {
                    Text(
                        "Model download or repair required.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                if (error != null) {
                    Text(
                        error,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (!modelsDone) {
                    Button(
                        onClick = onStartModelDownload,
                        enabled = setupPrerequisitesDone,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Download models")
                    }
                }
            }
        }

        // Step 4: Offline Navigation Voice
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface,
            ),
            border = BorderStroke(
                1.dp,
                if (voiceDone) {
                    MaterialTheme.colorScheme.tertiary
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
                TalkcanSectionHeader(
                    title = "Offline navigation voice",
                    supportingText = "Spoken feedback without a network.",
                )
                TalkcanStatusBadge(
                    label = if (voiceDone) "Completed" else "Needs setup",
                    tone = if (voiceDone) {
                        TalkcanStatusTone.Ready
                    } else {
                        TalkcanStatusTone.Attention
                    },
                )
                Text(
                    "An installed offline English text-to-speech voice is required for spoken navigation feedback. No network connection is used during announcements.",
                    style = MaterialTheme.typography.bodyMedium,
                )

                if (!voicePrerequisitesDone) {
                    Text(
                        "Waiting for earlier setup steps to complete…",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else if (!voiceDone && offlineNavigationVoiceIssue != null) {
                    Text(
                        "Offline voice verification failed",
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.SemiBold,
                    )
                } else if (!voiceDone) {
                    Text(
                        "Offline English voice not available.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }

                if (offlineNavigationVoiceIssue != null) {
                    Text(
                        offlineNavigationVoiceIssue.diagnostic,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (offlineNavigationVoiceIssue != null && voiceSetupRequiresManualNavigation) {
                    Text(
                        "In Android Settings, open Text-to-speech output and install an offline English voice.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                if (!voiceDone) {
                    Button(
                        onClick = onResolveVoiceSetup,
                        enabled = voicePrerequisitesDone,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(if (offlineNavigationVoiceIssue == null) "Install offline voice" else "Retry offline voice setup")
                    }
                }
            }
        }
    }
}
