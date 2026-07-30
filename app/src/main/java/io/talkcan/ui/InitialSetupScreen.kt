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
import io.talkcan.service.RequiredPermissions
import io.talkcan.model.OfflineNavigationVoiceIssue
/**
 * Setup surface shown only when bootstrap has identified a concrete
 * user-resolvable prerequisite: missing runtime permissions or model assets
 * that require explicit download or repair.
 *
 * After the final setup action, the system automatically returns to loading.
 * There is no manual "Enter Talkcan" acknowledgement.
 */
@Composable
fun InitialSetupScreen(
    missingPermissions: List<String>,
    needsManageExternalStorage: Boolean,
    invalidModelSets: List<String>,
    error: String?,
    offlineNavigationVoiceIssue: OfflineNavigationVoiceIssue?,
    voiceSetupRequiresManualNavigation: Boolean,
    onGrantPermissions: () -> Unit,
    onGrantManageExternalStorage: () -> Unit,
    onStartModelDownload: () -> Unit,
    onResolveVoiceSetup: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val permissionsDone = missingPermissions.isEmpty()
    val storageDone = !needsManageExternalStorage
    val setupPrerequisitesDone = permissionsDone && storageDone
    val modelsDone = invalidModelSets.isEmpty()
    val voiceDone = offlineNavigationVoiceIssue == null
    val voicePrerequisitesDone = setupPrerequisitesDone && modelsDone

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        TerminalHeader(
            title = "Set up Talkcan",
            subtitle = "Complete four checks so Talkcan can hear, route, and speak.",
        )

        PermissionsStep(
            done = permissionsDone,
            onRequest = onGrantPermissions,
        )

        StorageAccessStep(
            done = storageDone,
            permissionsDone = permissionsDone,
            onRequest = onGrantManageExternalStorage,
        )

        ModelDownloadStep(
            done = modelsDone,
            error = error,
            prerequisitesDone = setupPrerequisitesDone,
            onStart = onStartModelDownload,
        )

        VoiceSetupStep(
            done = voiceDone,
            issue = offlineNavigationVoiceIssue,
            requiresManualNavigation = voiceSetupRequiresManualNavigation,
            prerequisitesDone = voicePrerequisitesDone,
            onResolve = onResolveVoiceSetup,
        )
    }
}

@Composable
private fun PermissionsStep(done: Boolean, onRequest: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        border = BorderStroke(
            1.dp,
            if (done) {
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
                label = if (done) "Completed" else "Needs setup",
                tone = if (done) {
                    TalkcanStatusTone.Ready
                } else {
                    TalkcanStatusTone.Attention
                },
            )
            Text(
                if (done) {
                    "All runtime permissions granted."
                } else {
                    "Bluetooth, microphone, and notification permissions are required."
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            if (!done) {
                Button(
                    onClick = onRequest,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Grant permissions")
                }
            }
        }
    }
}

@Composable
private fun StorageAccessStep(
    done: Boolean,
    permissionsDone: Boolean,
    onRequest: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        border = BorderStroke(
            1.dp,
            if (done) {
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
                label = if (done) "Completed" else "Needs setup",
                tone = if (done) {
                    TalkcanStatusTone.Ready
                } else {
                    TalkcanStatusTone.Attention
                },
            )
            Text(
                "Journal writes to a user-selected filesystem directory. Android all-files access is required for these real paths.",
                style = MaterialTheme.typography.bodyMedium,
            )
            if (!done) {
                Button(
                    onClick = onRequest,
                    enabled = permissionsDone,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Allow storage access")
                }
            }
        }
    }
}

@Composable
private fun ModelDownloadStep(
    done: Boolean,
    error: String?,
    prerequisitesDone: Boolean,
    onStart: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        border = BorderStroke(
            1.dp,
            if (done) {
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
                label = if (done) "Completed" else "Needs setup",
                tone = if (done) {
                    TalkcanStatusTone.Ready
                } else {
                    TalkcanStatusTone.Attention
                },
            )
            Text(
                "Download the Parakeet (STT) and Supertonic (TTS) models (~950 MB). A network connection is required.",
                style = MaterialTheme.typography.bodyMedium,
            )
            if (!done) {
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
            if (!done) {
                Button(
                    onClick = onStart,
                    enabled = prerequisitesDone,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Download models")
                }
            }
        }
    }
}

@Composable
private fun VoiceSetupStep(
    done: Boolean,
    issue: OfflineNavigationVoiceIssue?,
    requiresManualNavigation: Boolean,
    prerequisitesDone: Boolean,
    onResolve: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        border = BorderStroke(
            1.dp,
            if (done) {
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
                label = if (done) "Completed" else "Needs setup",
                tone = if (done) {
                    TalkcanStatusTone.Ready
                } else {
                    TalkcanStatusTone.Attention
                },
            )
            Text(
                "An installed offline English text-to-speech voice is required for spoken navigation feedback. No network connection is used during announcements.",
                style = MaterialTheme.typography.bodyMedium,
            )

            if (!prerequisitesDone) {
                Text(
                    "Waiting for earlier setup steps to complete…",
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else if (!done && issue != null) {
                Text(
                    "Offline voice verification failed",
                    color = MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.SemiBold,
                )
            } else if (!done) {
                Text(
                    "Offline English voice not available.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            if (issue != null) {
                Text(
                    issue.diagnostic,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (issue != null && requiresManualNavigation) {
                Text(
                    "In Android Settings, open Text-to-speech output and install an offline English voice.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            if (!done) {
                Button(
                    onClick = onResolve,
                    enabled = prerequisitesDone,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (issue == null) "Install offline voice" else "Retry offline voice setup")
                }
            }
        }
    }
}