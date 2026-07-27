package io.talkcan.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.talkcan.voice.VoiceProfileAvailability
import io.talkcan.voice.VoiceProfileCatalogue
import io.talkcan.voice.VoiceProfileCompatibility
import io.talkcan.voice.VoiceProfileId

/**
 * Public scalar-only choice item for the clearly host-owned synthesis voice selector (Task 6.1).
 * Kept separate from provider configuration fields and payload.
 */
data class ChannelSynthesisVoiceChoice(
    val profileId: String?,
    val displayName: String,
    val isUnavailable: Boolean = false,
    val isUnverified: Boolean = false,
    val diagnostic: String? = null,
    val readOnly: Boolean = false,
)

/**
 * Result of committing a channel configuration that includes a synthesis voice preference (Tasks 6.2-6.3).
 */
sealed interface ChannelConfigurationSubmitResult {
    data object Success : ChannelConfigurationSubmitResult
    data class Error(val message: String) : ChannelConfigurationSubmitResult
    data class UnverifiedAcknowledgementRequired(
        val profileId: String,
        val displayName: String,
        val diagnostic: String,
    ) : ChannelConfigurationSubmitResult
}

internal fun synthesisVoiceChoicesFor(
    catalogue: VoiceProfileCatalogue,
    currentSelectionId: VoiceProfileId?,
): List<ChannelSynthesisVoiceChoice> {
    val choices = mutableListOf<ChannelSynthesisVoiceChoice>()
    choices.add(
        ChannelSynthesisVoiceChoice(
            profileId = null,
            displayName = "Application default",
            readOnly = true,
        )
    )
    catalogue.selectable.forEach { summary ->
        choices.add(
            ChannelSynthesisVoiceChoice(
                profileId = summary.id.value,
                displayName = summary.displayName,
                isUnavailable = false,
                isUnverified = summary.compatibility == VoiceProfileCompatibility.UNVERIFIED,
                diagnostic = null,
                readOnly = summary.readOnly,
            )
        )
    }
    if (currentSelectionId != null && choices.none { it.profileId == currentSelectionId.value }) {
        val summary = catalogue.summaryFor(currentSelectionId)
        if (summary != null) {
            val diagnostic = when (val avail = summary.availability) {
                is VoiceProfileAvailability.Unavailable -> avail.diagnostic
                else -> if (summary.compatibility == VoiceProfileCompatibility.INCOMPATIBLE) {
                    "Voice profile '${summary.displayName}' is incompatible with the current model"
                } else {
                    "Unavailable profile"
                }
            }
            choices.add(
                ChannelSynthesisVoiceChoice(
                    profileId = currentSelectionId.value,
                    displayName = summary.displayName,
                    isUnavailable = true,
                    isUnverified = false,
                    diagnostic = diagnostic,
                    readOnly = summary.readOnly,
                )
            )
        } else {
            choices.add(
                ChannelSynthesisVoiceChoice(
                    profileId = currentSelectionId.value,
                    displayName = currentSelectionId.value,
                    isUnavailable = true,
                    isUnverified = false,
                    diagnostic = "Voice profile '${currentSelectionId.value}' is not in the catalogue",
                    readOnly = false,
                )
            )
        }
    }
    return choices
}

internal fun voiceChoiceAccessibilityDescription(choice: ChannelSynthesisVoiceChoice): String =
    buildString {
        append(choice.displayName)
        if (choice.profileId == null) {
            append(", Default built-in voice profile")
        } else if (choice.isUnavailable) {
            append(", unavailable")
            choice.diagnostic?.let { append(", ").append(it) }
        } else if (choice.isUnverified) {
            append(", unverified compatibility")
        } else if (choice.readOnly) {
            append(", read-only")
        } else {
            append(", editable")
        }
    }

internal fun formatVoiceChoiceLabel(choice: ChannelSynthesisVoiceChoice): String =
    buildString {
        append(choice.displayName)
        if (choice.isUnavailable) {
            append(" (unavailable")
            choice.diagnostic?.let { append(" — ").append(it) }
            append(")")
        } else if (choice.isUnverified) {
            append(" (unverified compatibility)")
        } else if (choice.readOnly && choice.profileId != null) {
            append(" (read-only)")
        }
    }

@Composable
internal fun SynthesisVoiceSelectorCard(
    choices: List<ChannelSynthesisVoiceChoice>,
    selectedProfileId: String?,
    onSelectProfileId: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedChoice = choices.firstOrNull { it.profileId == selectedProfileId }
        ?: choices.firstOrNull { it.profileId == null }
        ?: ChannelSynthesisVoiceChoice(null, "Application default", readOnly = true)

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "Synthesis voice",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = "Host preference — independent of provider configuration",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            val buttonLabel = formatVoiceChoiceLabel(selectedChoice)
            val buttonDescription = voiceChoiceAccessibilityDescription(selectedChoice)

            OutlinedButton(
                onClick = { expanded = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = buttonDescription },
            ) {
                Text(
                    text = buttonLabel,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
            ) {
                choices.forEach { choice ->
                    val label = formatVoiceChoiceLabel(choice)
                    val itemDescription = voiceChoiceAccessibilityDescription(choice)
                    DropdownMenuItem(
                        text = { Text(label) },
                        enabled = !choice.isUnavailable,
                        onClick = {
                            onSelectProfileId(choice.profileId)
                            expanded = false
                        },
                        modifier = Modifier.semantics { contentDescription = itemDescription },
                    )
                }
            }
        }
    }
}
