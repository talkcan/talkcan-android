package io.talkcan.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
            title = "TALKCAN SETUP",
            subtitle = "Grant permissions, allow storage access, download speech models, and install an offline navigation voice to continue.",
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
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("STEP 1 — PERMISSIONS", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            Text(
                if (done) "All runtime permissions granted." else "Bluetooth, microphone, and notification permissions are required.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(12.dp))
            if (!done) {
                Button(onClick = onRequest, modifier = Modifier.fillMaxWidth()) {
                    Text("Grant permissions")
                }
            } else {
                Text("✓ Granted", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
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
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("STEP 2 — STORAGE ACCESS", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            Text(
                "Journal writes to a user-selected filesystem directory. Android all-files access is required for these real paths.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(12.dp))
            if (done) {
                Text("✓ Granted", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
            } else {
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
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("STEP 3 — SPEECH MODELS", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            Text(
                "Download the Parakeet (STT) and Supertonic (TTS) models (~950 MB). A network connection is required.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(12.dp))

            if (done) {
                Text("✓ Models verified", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
            } else {
                Text("Model download or repair required.", style = MaterialTheme.typography.bodyMedium)
            }

            if (error != null) {
                Spacer(Modifier.height(8.dp))
                Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }

            if (!done) {
                Spacer(Modifier.height(12.dp))
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
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("STEP 4 — OFFLINE NAVIGATION VOICE", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            Text(
                "An installed offline English text-to-speech voice is required for spoken navigation feedback. No network connection is used during announcements.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(12.dp))

            if (!prerequisitesDone) {
                Text("Waiting for earlier setup steps to complete…", style = MaterialTheme.typography.bodyMedium)
            } else if (done) {
                Text("✓ Offline voice verified", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
            } else if (issue != null) {
                Text(
                    "Error: Offline voice verification failed",
                    color = MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.SemiBold,
                )
            } else {
                Text("Offline English voice not available.", style = MaterialTheme.typography.bodyMedium)
            }

            if (issue != null) {
                Spacer(Modifier.height(8.dp))
                Text(issue.diagnostic, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            if (issue != null && requiresManualNavigation) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "In Android Settings, open Text-to-speech output and install an offline English voice.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

            if (!done) {
                Spacer(Modifier.height(12.dp))
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