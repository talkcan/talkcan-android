package io.talkcan.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.talkcan.model.MonitorState
import io.talkcan.model.SttStatus
import io.talkcan.model.displayText

@Composable
internal fun SttPanel(state: MonitorState) {
    val statusText = state.sttStatus.displayText()
    val transcript = state.sttTranscript
    val boxText = when (state.sttStatus) {
        is SttStatus.Transcribed -> transcript.ifBlank { statusText }
        SttStatus.Idle -> if (transcript.isBlank()) "No transcript yet" else transcript
        else -> statusText
    }
    val tone = when (state.sttStatus) {
        SttStatus.Recording -> TalkcanStatusTone.Recording
        SttStatus.Transcribing -> TalkcanStatusTone.Active
        is SttStatus.Transcribed -> TalkcanStatusTone.Ready
        is SttStatus.Error -> TalkcanStatusTone.Error
        SttStatus.EmptyAudio -> TalkcanStatusTone.Error
        SttStatus.Cancelled -> TalkcanStatusTone.Neutral
        SttStatus.Idle -> TalkcanStatusTone.Neutral
        else -> TalkcanStatusTone.Attention
    }

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            TalkcanSectionHeader(title = "Speech to text")
            TalkcanStatusBadge(
                label = statusText,
                tone = tone,
            )
            Text(
                text = boxText,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}