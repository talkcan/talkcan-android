package io.talkcan.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.talkcan.model.AppState
import io.talkcan.model.ChannelImplementationDescriptor

/**
 * Settings-owned channel catalogue management: add, rename, reorder, remove,
 * and per-channel configuration access. Mutation semantics are identical to
 * the former dashboard management panel — same [PttUiActions] methods, same
 * provider descriptor lookup, same minimum-one-channel deletion guard.
 */
@Composable
fun ChannelManagementScreen(
    appState: AppState,
    providerDescriptors: List<ChannelImplementationDescriptor>,
    actions: PttUiActions,
    modifier: Modifier = Modifier,
) {
    var newName by remember { mutableStateOf("") }
    var renameTargetId by remember { mutableStateOf<String?>(null) }
    var renameText by remember { mutableStateOf("") }

    TalkcanInstrumentBackdrop(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
        appState.channels.forEachIndexed { index, channel ->
            val descriptor = providerDescriptors.firstOrNull {
                it.implementationId == channel.implementationId
            }
            TalkcanInstrumentPanel(
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (renameTargetId == channel.id) {
                            OutlinedTextField(
                                value = renameText,
                                onValueChange = { renameText = it },
                                label = { Text("Channel name") },
                                modifier = Modifier.weight(1f),
                            )
                            Button(
                                onClick = {
                                    if (renameText.isNotBlank()) {
                                        actions.renameChannel(channel.id, renameText)
                                    }
                                    renameTargetId = null
                                },
                            ) { Text("Save") }
                        } else {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    channel.name,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                )
                                val label = descriptor?.presentation?.label
                                    ?: channel.implementationId.value
                                TalkcanStatusBadge(
                                    label = label,
                                    tone = TalkcanStatusTone.Ready,
                                )
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(onClick = {
                                    renameTargetId = channel.id
                                    renameText = channel.name
                                }) {
                                    Icon(
                                        Icons.Filled.Edit,
                                        contentDescription = "Rename",
                                    )
                                }
                                IconButton(
                                    onClick = {
                                        actions.moveChannel(channel.id, index - 1)
                                    },
                                    enabled = index > 0,
                                ) { Text("Up") }
                                IconButton(
                                    onClick = {
                                        actions.moveChannel(channel.id, index + 1)
                                    },
                                    enabled = index < appState.channels.lastIndex,
                                ) { Text("Down") }
                                IconButton(
                                    onClick = { actions.removeChannel(channel.id) },
                                    enabled = appState.channels.size > 1,
                                ) {
                                    Icon(
                                        Icons.Filled.Delete,
                                        contentDescription = "Delete",
                                    )
                                }
                            }
                        }
                    }
                    OutlinedButton(
                        onClick = {
                            actions.navigateToChannelConfiguration(channel.id)
                        },
                        enabled = descriptor != null,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Settings,
                            contentDescription = null,
                        )
                        Text("  Configure")
                    }
                }
            }
        }

        TalkcanInstrumentPanel(
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                TalkcanSectionHeader(
                    title = "Add a channel",
                    supportingText = "Name the route, then choose what it connects to.",
                )
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text("Display name") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("Choose a provider", style = MaterialTheme.typography.bodyMedium)
                providerDescriptors.forEach { descriptor ->
                    OutlinedButton(
                        onClick = {
                            actions.navigateToChannelCreation(
                                descriptor.implementationId,
                                newName,
                            )
                        },
                        enabled = newName.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                descriptor.presentation.label,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                descriptor.presentation.summary,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
                if (providerDescriptors.isEmpty()) {
                    Text(
                        "No channel providers are currently available.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}
}