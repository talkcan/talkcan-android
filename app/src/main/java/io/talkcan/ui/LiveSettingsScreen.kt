package io.talkcan.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.talkcan.live.LiveSettingsState
import io.talkcan.model.ChannelDefinition

/** Priority-channel setup, or the account section embedded in a GPT-Live channel. */
@Composable
fun LiveSettingsScreen(
    state: LiveSettingsState,
    channels: List<ChannelDefinition>,
    onSave: (apiKey: String, sosChannelId: String?) -> Unit,
    onClearKey: () -> Unit,
    modifier: Modifier = Modifier,
    keyOnly: Boolean = false,
) {
    // Deliberately NOT rememberSaveable: the key must not survive process death
    // in saved instance state.
    var apiKey by remember { mutableStateOf("") }
    var selectedId by remember(state.sosChannelId) { mutableStateOf(state.sosChannelId) }
    var expanded by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }

    val liveChoices = remember(channels) {
        channels.filter { it.enabled }
    }
    val missingSavedId = state.sosChannelId?.takeIf { saved ->
        liveChoices.none { it.id == saved }
    }
    val selectedLabel = when {
        selectedId == null -> "None"
        else -> liveChoices.firstOrNull { it.id == selectedId }?.name
            ?: "Unavailable: $selectedId"
    }
    Column(
        modifier = modifier
            .then(if (keyOnly) Modifier else Modifier.fillMaxSize().verticalScroll(rememberScrollState()))
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        TerminalHeader(
            title = if (keyOnly) "OpenAI account" else "Priority channel",
            subtitle = if (keyOnly) "Used by GPT-Live channels." else "Hold SOS to talk without changing your regular channel.",
        )

        if (keyOnly) TalkcanInstrumentPanel {
            TalkcanSectionHeader(
                title = "Voice API key",
                supportingText = "Use your own key. It is stored encrypted on this device and never bundled or shared.",
            )
            TalkcanStatusBadge(
                label = if (state.keyConfigured) "Key stored" else "No key",
                tone = if (state.keyConfigured) TalkcanStatusTone.Ready else TalkcanStatusTone.Neutral,
                modifier = Modifier.semantics { contentDescription = if (state.keyConfigured) "Voice API key stored" else "No voice API key stored" },
            )
            Text(
                text = "Live voice sends the microphone continuously, but only during a live session. " +
                    "Voice cost is \$0.05/min plus the backend model cost.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it },
                label = { Text("API key (leave blank to keep current)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                supportingText = { Text("Blank keeps the current key. Cleared after saving.") },
                enabled = !state.saving,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("liveApiKeyField"),
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Button(
                    onClick = {
                        onSave(apiKey, selectedId)
                        apiKey = ""
                    },
                    enabled = !state.saving,
                    modifier = Modifier.testTag("liveSaveButton"),
                ) {
                    Text(if (state.saving) "Saving…" else "Save")
                }
                if (state.keyConfigured) {
                    TextButton(
                        onClick = { confirmClear = true },
                        enabled = !state.saving,
                        modifier = Modifier.testTag("liveClearKeyButton"),
                    ) {
                        Text("Clear key")
                    }
                }
            }
        }

        if (!keyOnly) TalkcanInstrumentPanel {
            TalkcanSectionHeader(
                title = "SOS priority channel",
                supportingText = "Long-press SOS to talk to this channel. Release to finish.",
            )
            Text(
                text = "SOS ends any running regular conversation. Releasing SOS does not restart it.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Priority channel", style = MaterialTheme.typography.bodyLarge)
                OutlinedButton(
                    onClick = { expanded = true },
                    enabled = !state.saving,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("liveSosChannelButton")
                        .semantics { contentDescription = "Priority channel: $selectedLabel" },
                ) {
                    Text(selectedLabel)
                }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    DropdownMenuItem(
                        text = { Text("None") },
                        onClick = {
                            selectedId = null
                            expanded = false
                        },
                    )
                    liveChoices.forEach { choice ->
                        DropdownMenuItem(
                            text = { Text(choice.name) },
                            onClick = {
                                selectedId = choice.id
                                expanded = false
                            },
                        )
                    }
                    if (missingSavedId != null && selectedId == missingSavedId) {
                        DropdownMenuItem(
                            text = { Text("Unavailable: $missingSavedId") },
                            enabled = false,
                            onClick = {},
                        )
                    }
                }
                if (missingSavedId != null && selectedId == missingSavedId) {
                    Text(
                        text = "The saved channel is unavailable (removed or disabled). Pick another channel or None.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag("liveSosUnavailableNote"),
                    )
                } else if (liveChoices.isEmpty()) {
                    Text(
                        text = "No enabled channels yet. Add one in Channel management.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Button(onClick = { onSave("", selectedId) }, enabled = !state.saving) {
                Text(if (state.saving) "Saving…" else "Save")
            }
        }

        state.error?.let { error ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                TalkcanStatusBadge(label = "Error", tone = TalkcanStatusTone.Error)
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("liveSettingsError"),
                )
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear voice API key?") },
            text = { Text("The stored key is removed from this device. Live voice stops working until you save a new key.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmClear = false
                        onClearKey()
                    },
                    modifier = Modifier.testTag("liveConfirmClearKey"),
                ) {
                    Text("Clear")
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("Cancel") }
            },
        )
    }
}
