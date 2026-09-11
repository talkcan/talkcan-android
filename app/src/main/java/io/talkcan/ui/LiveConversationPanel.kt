package io.talkcan.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.talkcan.live.LiveConversationView
import io.talkcan.live.LiveSessionPhase

/**
 * Full-duplex live conversation control and transcript view.
 *
 * There is no push-to-talk here: the microphone stays open for the whole
 * session. Tapping transcript text only selects it for copy; it never
 * transmits. Transcript labels describe on-screen text only and never claim a
 * backend result was spoken aloud.
 */
@Composable
fun LiveConversationPanel(
    view: LiveConversationView,
    targetName: String?,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val phase = view.state.phase
    val running = view.isRunning
    // CONNECTING stays cancellable; CLOSING never interrupts a close.
    val toggleEnabled = when (phase) {
        LiveSessionPhase.CLOSING -> false
        else -> running || targetName != null
    }
    val toggleLabel = if (running) "End live" else "Start live"
    val channelLabel = (if (running) view.channelName else targetName) ?: "No channel selected"
    val statusLabel = when (phase) {
        LiveSessionPhase.IDLE -> "Idle"
        LiveSessionPhase.CONNECTING -> "Connecting…"
        LiveSessionPhase.ACTIVE -> "Live · full-duplex"
        LiveSessionPhase.CLOSING -> "Closing…"
        LiveSessionPhase.FAILED -> "Failed"
    }
    val statusTone = when (phase) {
        LiveSessionPhase.IDLE -> TalkcanStatusTone.Neutral
        LiveSessionPhase.CONNECTING -> TalkcanStatusTone.Active
        LiveSessionPhase.ACTIVE -> TalkcanStatusTone.Recording
        LiveSessionPhase.CLOSING -> TalkcanStatusTone.Attention
        LiveSessionPhase.FAILED -> TalkcanStatusTone.Error
    }
    val micLabel = when (phase) {
        LiveSessionPhase.ACTIVE -> "Full-duplex · microphone active"
        LiveSessionPhase.CONNECTING -> "Connecting · microphone starting"
        LiveSessionPhase.CLOSING -> "Closing live session…"
        LiveSessionPhase.FAILED -> "Microphone off"
        LiveSessionPhase.IDLE -> "Microphone off"
    }

    TalkcanInstrumentPanel(modifier = modifier) {
        TalkcanSectionHeader(
            title = "Live conversation",
            supportingText = channelLabel,
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            TalkcanStatusBadge(
                label = statusLabel,
                tone = statusTone,
                modifier = Modifier.semantics { contentDescription = "Live session status: $statusLabel" },
            )
            Text(
                text = micLabel,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
        }
        if (running) {
            Button(
                onClick = onToggle,
                enabled = toggleEnabled,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("liveToggleButton"),
            ) {
                Text(toggleLabel)
            }
        } else {
            OutlinedButton(
                onClick = onToggle,
                enabled = toggleEnabled,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("liveToggleButton"),
            ) {
                Text(toggleLabel)
            }
        }
        if (!running && targetName == null) {
            Text(
                text = "Choose an SOS live channel in Settings to start.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = "No push-to-talk: the microphone stays open during the session. " +
                "Tapping text selects it; it does not transmit.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (phase == LiveSessionPhase.FAILED) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                TalkcanStatusBadge(label = "Error", tone = TalkcanStatusTone.Error)
                Text(
                    text = view.state.message ?: "Live session failed.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("liveFailureMessage"),
                )
            }
        } else {
            view.state.message?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            TranscriptBlock(
                label = "You",
                text = view.state.inputTranscript.ifBlank { "Nothing heard yet." },
                testTag = "liveInputTranscript",
            )
            TranscriptBlock(
                label = "Assistant text",
                text = view.state.outputTranscript.ifBlank { "No assistant text yet." },
                testTag = "liveOutputTranscript",
            )
        }
    }
}

@Composable
private fun TranscriptBlock(
    label: String,
    text: String,
    testTag: String,
    modifier: Modifier = Modifier,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SelectionContainer {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 160.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(end = 4.dp)
                    .testTag(testTag),
            )
        }
    }
}
