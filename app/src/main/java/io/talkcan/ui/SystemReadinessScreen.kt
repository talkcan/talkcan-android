package io.talkcan.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.talkcan.model.OfflineNavigationVoiceIssue
import io.talkcan.model.ModelAcquisitionProgress
import io.talkcan.ui.theme.SignalAmber
import io.talkcan.ui.theme.StatusCyan
import io.talkcan.ui.theme.TalkcanError
import io.talkcan.ui.theme.WarmAluminum

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

    TalkcanInstrumentBackdrop(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
        TerminalHeader(
            title = "System readiness",
            subtitle = "Verify and configure core device check categories.",
        )

        // Step 1: Permissions
        TalkcanInstrumentPanel(
            borderColor = if (permissionsDone) StatusCyan else WarmAluminum,
            contentPadding = 18.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
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
        TalkcanInstrumentPanel(
            borderColor = if (storageDone) StatusCyan else WarmAluminum,
            contentPadding = 18.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
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
        val modelsActive = modelProgress.totalBytes > 0 && !modelsDone
        TalkcanInstrumentPanel(
            borderColor = when {
                modelsDone -> StatusCyan
                modelsActive -> SignalAmber
                else -> WarmAluminum
            },
            contentPadding = 18.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
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
                        color = TalkcanError,
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
        TalkcanInstrumentPanel(
            borderColor = when {
                voiceDone -> StatusCyan
                !voiceDone && voicePrerequisitesDone -> WarmAluminum
                else -> MaterialTheme.colorScheme.outlineVariant
            },
            contentPadding = 18.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
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
                        color = TalkcanError,
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
                        color = TalkcanError,
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
}
