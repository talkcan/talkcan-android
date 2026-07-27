package io.talkcan.ui

import android.graphics.Bitmap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import io.talkcan.service.VoiceProfileEditorFailure
import io.talkcan.service.VoiceProfileEditorState
import io.talkcan.service.VoiceProfilePreviewPhase
import io.talkcan.voice.VoiceProfileAvailability
import io.talkcan.voice.VoiceProfileCatalogue
import io.talkcan.voice.VoiceProfileCompatibility
import io.talkcan.voice.VoiceProfileId
import io.talkcan.voice.VoiceProfileKind
import io.talkcan.voice.VoiceProfileSummary
import io.talkcan.voice.VoiceProfileTtlOperation

@Composable
internal fun VoiceProfileManagementScreen(
    catalogue: VoiceProfileCatalogue,
    editorState: VoiceProfileEditorState,
    onSelectSources: (List<VoiceProfileId>, Boolean) -> Unit,
    onSetEqualWeights: () -> Unit,
    onSetManualWeights: (List<Double>) -> Unit,
    onSetRandomWeights: (Long) -> Unit,
    onApplyOperation: (VoiceProfileTtlOperation) -> Unit,
    onUndoOperation: () -> Unit,
    onResetDraft: () -> Unit,
    onAcknowledgeFailure: () -> Unit,
    onSaveDraftAsNew: (String) -> Unit,
    onRenameProfile: (VoiceProfileId, String) -> Unit,
    onDeleteProfile: (VoiceProfileId) -> Unit,
    onImportProfile: (String) -> Unit,
    onExportProfile: (String, String) -> Unit,
    onPreviewDraft: (String) -> Unit,
    onCancelPreview: () -> Unit,
    onExitEditor: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP || event == Lifecycle.Event.ON_DESTROY) {
                onExitEditor()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            onExitEditor()
        }
    }

    var pendingSourceSelection by remember { mutableStateOf<List<VoiceProfileId>?>(null) }
    var pendingWeightAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    var importDialogOpen by remember { mutableStateOf(false) }
    var saveAsNewDialogOpen by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<VoiceProfileSummary?>(null) }
    var deleteTarget by remember { mutableStateOf<VoiceProfileSummary?>(null) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "VOICE PROFILE MIXER & CATALOGUE",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )

        editorState.failure?.let { failure ->
            FailureBanner(
                failure = failure,
                onDismiss = onAcknowledgeFailure,
            )
        }

        MixerSection(
            editorState = editorState,
            catalogue = catalogue,
            onSourceSelectionRequested = { newIds ->
                if (VoiceProfileManagementUiHelpers.requiresResetConfirmation(
                        editorState.draft,
                        isChangingSources = true,
                    )
                ) {
                    pendingSourceSelection = newIds
                } else {
                    onSelectSources(newIds, false)
                }
            },
            onEqualWeightsRequested = {
                if (VoiceProfileManagementUiHelpers.requiresResetConfirmation(
                        editorState.draft,
                        isChangingWeights = true,
                    )
                ) {
                    pendingWeightAction = {
                        onResetDraft()
                        onSetEqualWeights()
                    }
                } else {
                    onSetEqualWeights()
                }
            },
            onManualWeightsRequested = { rawWeights ->
                if (VoiceProfileManagementUiHelpers.requiresResetConfirmation(
                        editorState.draft,
                        isChangingWeights = true,
                    )
                ) {
                    pendingWeightAction = {
                        onResetDraft()
                        onSetManualWeights(rawWeights)
                    }
                } else {
                    onSetManualWeights(rawWeights)
                }
            },
            onRandomWeightsRequested = { seed ->
                if (VoiceProfileManagementUiHelpers.requiresResetConfirmation(
                        editorState.draft,
                        isChangingWeights = true,
                    )
                ) {
                    pendingWeightAction = {
                        onResetDraft()
                        onSetRandomWeights(seed)
                    }
                } else {
                    onSetRandomWeights(seed)
                }
            },
        )

        editorState.draft?.let { draft ->
            HeatmapSection(
                ttl = draft.ttl,
                onApplyOperation = onApplyOperation,
            )

            OperationsSection(
                undoDepth = draft.undoDepth,
                canUndo = draft.canUndo,
                hasEdits = draft.hasEdits,
                onApplyOperation = onApplyOperation,
                onUndoOperation = onUndoOperation,
                onResetDraft = onResetDraft,
            )
        }

        PreviewSection(
            editorState = editorState,
            catalogue = catalogue,
            onPreviewDraft = onPreviewDraft,
            onCancelPreview = onCancelPreview,
            onOpenSaveAsNew = { saveAsNewDialogOpen = true },
        )

        CatalogueSection(
            catalogue = catalogue,
            selectedSourceIds = editorState.selectedSourceIds,
            onSourceToggled = { summaryId, checked ->
                val current = editorState.selectedSourceIds
                val newList = if (checked) {
                    (current + summaryId).distinct()
                } else {
                    current.filterNot { it == summaryId }
                }
                if (VoiceProfileManagementUiHelpers.requiresResetConfirmation(
                        editorState.draft,
                        isChangingSources = true,
                    )
                ) {
                    pendingSourceSelection = newList
                } else {
                    onSelectSources(newList, false)
                }
            },
            onOpenRename = { renameTarget = it },
            onOpenDelete = { deleteTarget = it },
            onExportProfile = onExportProfile,
            onOpenImport = { importDialogOpen = true },
        )
    }

    pendingSourceSelection?.let { newIds ->
        AlertDialog(
            onDismissRequest = { pendingSourceSelection = null },
            title = { Text("Discard latent edits?") },
            text = { Text("Changing mix sources will reset the draft to a new baseline and discard latent operations.") },
            confirmButton = {
                Button(onClick = {
                    val targetIds = newIds
                    pendingSourceSelection = null
                    onSelectSources(targetIds, true)
                }) {
                    Text("Discard & Continue")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingSourceSelection = null }) {
                    Text("Cancel")
                }
            },
        )
    }

    pendingWeightAction?.let { action ->
        AlertDialog(
            onDismissRequest = { pendingWeightAction = null },
            title = { Text("Discard latent edits?") },
            text = { Text("Changing weights will reset the draft to a new baseline and discard latent operations.") },
            confirmButton = {
                Button(onClick = {
                    val invokeAction = action
                    pendingWeightAction = null
                    invokeAction()
                }) {
                    Text("Discard & Continue")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingWeightAction = null }) {
                    Text("Cancel")
                }
            },
        )
    }

    if (importDialogOpen) {
        var importName by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { importDialogOpen = false },
            title = { Text("Import Voice Profile") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Enter a display name for the imported JSON document.")
                    OutlinedTextField(
                        value = importName,
                        onValueChange = { importName = it },
                        label = { Text("Display Name") },
                        singleLine = true,
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (importName.isNotBlank()) {
                            importDialogOpen = false
                            onImportProfile(importName.trim())
                        }
                    },
                    enabled = importName.isNotBlank(),
                ) {
                    Text("Select JSON File")
                }
            },
            dismissButton = {
                TextButton(onClick = { importDialogOpen = false }) {
                    Text("Cancel")
                }
            },
        )
    }

    if (saveAsNewDialogOpen) {
        var newName by remember { mutableStateOf("") }
        val sources = editorState.selectedSourceIds
        val normalized = editorState.normalizedWeights
        val summaryLines = sources.mapIndexed { index, id ->
            val summary = catalogue.summaryFor(id)
            val pct = normalized.getOrNull(index)?.let {
                VoiceProfileManagementUiHelpers.formatNormalizedPercentage(it)
            } ?: ""
            "${summary?.displayName ?: id.value} ($pct)"
        }
        AlertDialog(
            onDismissRequest = { saveAsNewDialogOpen = false },
            title = { Text("Save Custom Profile As New") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Immediate sources: ${summaryLines.joinToString(", ")}")
                    Text("Latent operations applied: ${editorState.draft?.operationCount ?: 0}")
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        label = { Text("Display Name") },
                        singleLine = true,
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (newName.isNotBlank()) {
                            saveAsNewDialogOpen = false
                            onSaveDraftAsNew(newName.trim())
                        }
                    },
                    enabled = newName.isNotBlank(),
                ) {
                    Text("Save Profile")
                }
            },
            dismissButton = {
                TextButton(onClick = { saveAsNewDialogOpen = false }) {
                    Text("Cancel")
                }
            },
        )
    }

    renameTarget?.let { target ->
        var renameText by remember(target) { mutableStateOf(target.displayName) }
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("Rename Profile") },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    label = { Text("New display name") },
                    singleLine = true,
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (renameText.isNotBlank()) {
                            val id = target.id
                            val name = renameText.trim()
                            renameTarget = null
                            onRenameProfile(id, name)
                        }
                    },
                    enabled = renameText.isNotBlank(),
                ) {
                    Text("Rename")
                }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) {
                    Text("Cancel")
                }
            },
        )
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete Profile '${target.displayName}'?") },
            text = { Text("This will remove the custom profile from storage. If channels depend on it, deletion will be refused.") },
            confirmButton = {
                Button(onClick = {
                    val id = target.id
                    deleteTarget = null
                    onDeleteProfile(id)
                }) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text("Cancel")
                }
            },
        )
    }
}

@Composable
private fun FailureBanner(
    failure: VoiceProfileEditorFailure,
    onDismiss: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = failure.diagnostic,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onDismiss) {
                Text("Dismiss", color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun MixerSection(
    editorState: VoiceProfileEditorState,
    catalogue: VoiceProfileCatalogue,
    onSourceSelectionRequested: (List<VoiceProfileId>) -> Unit,
    onEqualWeightsRequested: () -> Unit,
    onManualWeightsRequested: (List<Double>) -> Unit,
    onRandomWeightsRequested: (Long) -> Unit,
) {
    var randomSeedInput by remember { mutableStateOf("1234") }
    val selectedIds = editorState.selectedSourceIds
    val rawWeights = editorState.rawWeights
    val normalizedWeights = editorState.normalizedWeights

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "PRIMARY MIXER SOURCES (${selectedIds.size} selected)",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )

            if (selectedIds.size < 2) {
                Text(
                    text = "At least 2 sources required. Select sources from the catalogue below.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            } else {
                selectedIds.forEachIndexed { index, id ->
                    val summary = catalogue.summaryFor(id)
                    val rawWeight = rawWeights.getOrNull(index) ?: 1.0
                    val normWeight = normalizedWeights.getOrNull(index) ?: 0.0
                    val pct = VoiceProfileManagementUiHelpers.formatNormalizedPercentage(normWeight)

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "${summary?.displayName ?: id.value} ($pct)",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            OutlinedButton(
                                onClick = {
                                    val newWeights = rawWeights.toMutableList()
                                    if (index in newWeights.indices) {
                                        newWeights[index] = (newWeights[index] - 0.5).coerceAtLeast(0.1)
                                        onManualWeightsRequested(newWeights)
                                    }
                                },
                            ) { Text("-") }

                            Text(
                                text = String.format("%.1f", rawWeight),
                                style = MaterialTheme.typography.bodyMedium,
                            )

                            OutlinedButton(
                                onClick = {
                                    val newWeights = rawWeights.toMutableList()
                                    if (index in newWeights.indices) {
                                        newWeights[index] = (newWeights[index] + 0.5).coerceAtMost(10.0)
                                        onManualWeightsRequested(newWeights)
                                    }
                                },
                            ) { Text("+") }
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedButton(
                        onClick = onEqualWeightsRequested,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Equalize")
                    }

                    OutlinedTextField(
                        value = randomSeedInput,
                        onValueChange = { randomSeedInput = it.filter(Char::isDigit) },
                        label = { Text("Seed") },
                        modifier = Modifier.width(80.dp),
                        singleLine = true,
                    )

                    OutlinedButton(
                        onClick = {
                            val seed = randomSeedInput.toLongOrNull() ?: 1234L
                            onRandomWeightsRequested(seed)
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Randomize")
                    }
                }
            }
        }
    }
}

@Composable
private fun HeatmapSection(
    ttl: io.talkcan.voice.VoiceTensor,
    onApplyOperation: (VoiceProfileTtlOperation) -> Unit,
) {
    val bitmap = remember(ttl) {
        val pixels = VoiceProfileManagementUiHelpers.generateHeatmapPixels(ttl)
        val bmp = Bitmap.createBitmap(256, 50, Bitmap.Config.ARGB_8888)
        bmp.setPixels(pixels, 0, 256, 0, 0, 256, 50)
        bmp.asImageBitmap()
    }
    val (min, max) = remember(ttl) { VoiceProfileManagementUiHelpers.heatmapMinMax(ttl) }
    val contentDesc = remember(ttl) { VoiceProfileManagementUiHelpers.ttlHeatmapAccessibilityDescription(ttl) }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "TTL HEATMAP [1, 50, 256]",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = String.format("Min: %.3f | Max: %.3f", min, max),
                style = MaterialTheme.typography.bodySmall,
            )

            Image(
                bitmap = bitmap,
                contentDescription = contentDesc,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(80.dp),
                filterQuality = FilterQuality.None,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = { onApplyOperation(VoiceProfileTtlOperation.SeededFeatureRoll(1001L)) },
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Shift Feature (+10)")
                }
                OutlinedButton(
                    onClick = { onApplyOperation(VoiceProfileTtlOperation.SeededTimeRoll(2002L)) },
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Shift Time (+5)")
                }
            }
        }
    }
}

@Composable
private fun OperationsSection(
    undoDepth: Int,
    canUndo: Boolean,
    hasEdits: Boolean,
    onApplyOperation: (VoiceProfileTtlOperation) -> Unit,
    onUndoOperation: () -> Unit,
    onResetDraft: () -> Unit,
) {
    var addVal by remember { mutableStateOf("0.1") }
    var multVal by remember { mutableStateOf("1.2") }
    var featRollSeed by remember { mutableStateOf("1001") }
    var timeRollSeed by remember { mutableStateOf("2002") }
    var sharpenStrength by remember { mutableStateOf("1.5") }
    var quantizeFactor by remember { mutableStateOf("5.0") }
    var echoDelay by remember { mutableStateOf("2") }
    var echoDecay by remember { mutableStateOf("0.5") }
    var tremoloDepth by remember { mutableStateOf("0.5") }
    var jitterAmount by remember { mutableStateOf("0.2") }
    var jitterSeed by remember { mutableStateOf("3003") }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "ADVANCED LATENT OPERATIONS",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "Operations modify only the TTL tensor while retaining the mixed DP tensor.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = onUndoOperation,
                    enabled = canUndo,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Undo ($undoDepth / 20)")
                }
                OutlinedButton(
                    onClick = onResetDraft,
                    enabled = hasEdits,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Reset Baseline")
                }
            }

            // Simple buttons
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { onApplyOperation(VoiceProfileTtlOperation.FeatureMirror) },
                    modifier = Modifier.weight(1f),
                ) { Text("Feature Mirror") }
                OutlinedButton(
                    onClick = { onApplyOperation(VoiceProfileTtlOperation.TimeMirror) },
                    modifier = Modifier.weight(1f),
                ) { Text("Time Mirror") }
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { onApplyOperation(VoiceProfileTtlOperation.Invert) },
                    modifier = Modifier.weight(1f),
                ) { Text("Invert") }
                OutlinedButton(
                    onClick = { onApplyOperation(VoiceProfileTtlOperation.TimeDerivative) },
                    modifier = Modifier.weight(1f),
                ) { Text("Derivative") }
            }

            // Parametric operations
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = addVal,
                    onValueChange = { addVal = it },
                    label = { Text("Add") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                )
                OutlinedButton(onClick = {
                    addVal.toDoubleOrNull()?.let { onApplyOperation(VoiceProfileTtlOperation.ScalarAdd(it)) }
                }) { Text("+ Add") }

                OutlinedTextField(
                    value = multVal,
                    onValueChange = { multVal = it },
                    label = { Text("Mult") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                )
                OutlinedButton(onClick = {
                    multVal.toDoubleOrNull()?.let { onApplyOperation(VoiceProfileTtlOperation.ScalarMultiply(it)) }
                }) { Text("* Mult") }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = sharpenStrength,
                    onValueChange = { sharpenStrength = it },
                    label = { Text("Sharpen") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                )
                OutlinedButton(onClick = {
                    sharpenStrength.toDoubleOrNull()?.let {
                        onApplyOperation(VoiceProfileTtlOperation.FeatureSharpen(it))
                    }
                }) { Text("Sharpen") }

                OutlinedTextField(
                    value = quantizeFactor,
                    onValueChange = { quantizeFactor = it },
                    label = { Text("Quant") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                )
                OutlinedButton(onClick = {
                    quantizeFactor.toDoubleOrNull()?.let {
                        onApplyOperation(VoiceProfileTtlOperation.Quantize(it))
                    }
                }) { Text("Quantize") }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = featRollSeed,
                    onValueChange = { featRollSeed = it },
                    label = { Text("Feat Roll") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                )
                OutlinedButton(onClick = {
                    featRollSeed.toLongOrNull()?.let {
                        onApplyOperation(VoiceProfileTtlOperation.SeededFeatureRoll(it))
                    }
                }) { Text("F. Roll") }

                OutlinedTextField(
                    value = timeRollSeed,
                    onValueChange = { timeRollSeed = it },
                    label = { Text("Time Roll") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                )
                OutlinedButton(onClick = {
                    timeRollSeed.toLongOrNull()?.let {
                        onApplyOperation(VoiceProfileTtlOperation.SeededTimeRoll(it))
                    }
                }) { Text("T. Roll") }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = echoDelay,
                    onValueChange = { echoDelay = it },
                    label = { Text("Delay") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = echoDecay,
                    onValueChange = { echoDecay = it },
                    label = { Text("Decay") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                )
                OutlinedButton(onClick = {
                    val delay = echoDelay.toIntOrNull() ?: 2
                    val decay = echoDecay.toDoubleOrNull() ?: 0.5
                    onApplyOperation(VoiceProfileTtlOperation.FeatureEcho(delay, decay))
                }) { Text("Echo") }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = tremoloDepth,
                    onValueChange = { tremoloDepth = it },
                    label = { Text("Trem Depth") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                )
                OutlinedButton(onClick = {
                    tremoloDepth.toDoubleOrNull()?.let {
                        onApplyOperation(VoiceProfileTtlOperation.FeatureTremolo(it))
                    }
                }) { Text("Tremolo") }

                OutlinedTextField(
                    value = jitterAmount,
                    onValueChange = { jitterAmount = it },
                    label = { Text("Amt") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = jitterSeed,
                    onValueChange = { jitterSeed = it },
                    label = { Text("Seed") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                )
                OutlinedButton(onClick = {
                    val amt = jitterAmount.toDoubleOrNull() ?: 0.2
                    val seed = jitterSeed.toLongOrNull() ?: 3003L
                    onApplyOperation(VoiceProfileTtlOperation.SeededJitter(amt, seed))
                }) { Text("Jitter") }
            }
        }
    }
}

@Composable
private fun PreviewSection(
    editorState: VoiceProfileEditorState,
    catalogue: VoiceProfileCatalogue,
    onPreviewDraft: (String) -> Unit,
    onCancelPreview: () -> Unit,
    onOpenSaveAsNew: () -> Unit,
) {
    var previewText by remember { mutableStateOf("Talkcan field operations online.") }
    val previewState = editorState.preview

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "PREVIEW & SAVE DRAFT",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )

            val statusLabel = when (previewState.phase) {
                VoiceProfilePreviewPhase.IDLE -> "IDLE"
                VoiceProfilePreviewPhase.SYNTHESIZING -> "SYNTHESIZING"
                VoiceProfilePreviewPhase.PLAYING -> "PLAYING"
                VoiceProfilePreviewPhase.ERROR -> "ERROR"
            }

            val routeLabel = if (previewState.targetMode != null && previewState.targetEndpoint != null) {
                "${previewState.targetMode} -> ${previewState.targetEndpoint}"
            } else {
                "Default route"
            }

            Text(
                text = "Status: $statusLabel | Active Route: $routeLabel",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                color = if (previewState.active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )

            previewState.diagnostic?.let { diag ->
                Text(
                    text = diag,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            OutlinedTextField(
                value = previewText,
                onValueChange = { previewText = it },
                label = { Text("Preview text") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = false,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = { onPreviewDraft(previewText) },
                    enabled = editorState.draft != null && !previewState.active,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Preview Draft")
                }
                OutlinedButton(
                    onClick = onCancelPreview,
                    enabled = previewState.active,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Cancel Preview")
                }
            }

            Button(
                onClick = onOpenSaveAsNew,
                enabled = editorState.draft != null,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Save as New Custom Profile")
            }
        }
    }
}

@Composable
private fun CatalogueSection(
    catalogue: VoiceProfileCatalogue,
    selectedSourceIds: List<VoiceProfileId>,
    onSourceToggled: (VoiceProfileId, Boolean) -> Unit,
    onOpenRename: (VoiceProfileSummary) -> Unit,
    onOpenDelete: (VoiceProfileSummary) -> Unit,
    onExportProfile: (String, String) -> Unit,
    onOpenImport: () -> Unit,
) {
    val groups = remember(catalogue) { VoiceProfileManagementUiHelpers.groupCatalogue(catalogue) }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "VOICE PROFILE CATALOGUE",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )

            OutlinedButton(onClick = onOpenImport) {
                Text("Import Profile (JSON)")
            }
        }

        groups.visibleSections.forEach { (sectionTitle, profiles) ->
            Text(
                text = sectionTitle,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
            )

            profiles.forEach { summary ->
                ProfileCard(
                    summary = summary,
                    isSelected = selectedSourceIds.contains(summary.id),
                    canDeselect = VoiceProfileManagementUiHelpers.canDeselectSource(summary.id, selectedSourceIds),
                    onSourceToggled = { checked -> onSourceToggled(summary.id, checked) },
                    onRename = { onOpenRename(summary) },
                    onDelete = { onOpenDelete(summary) },
                    onExport = { onExportProfile(summary.id.value, "${summary.displayName}.json") },
                )
            }
        }
    }
}

@Composable
private fun ProfileCard(
    summary: VoiceProfileSummary,
    isSelected: Boolean,
    canDeselect: Boolean,
    onSourceToggled: (Boolean) -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onExport: () -> Unit,
) {
    val isSelectable = VoiceProfileManagementUiHelpers.isProfileSelectable(summary)
    val desc = remember(summary) { VoiceProfileManagementUiHelpers.profileAccessibilityDescription(summary) }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = desc },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f),
                ) {
                    Checkbox(
                        checked = isSelected,
                        onCheckedChange = { checked -> onSourceToggled(checked) },
                        enabled = isSelectable && (isSelected && canDeselect || !isSelected),
                    )

                    Column {
                        Text(
                            text = summary.displayName,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = "ID: ${summary.id.value} | Kind: ${summary.kind} | Compat: ${summary.compatibility}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.secondary,
                        )
                    }
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (!summary.readOnly) {
                        IconButton(onClick = onRename) {
                            Icon(imageVector = Icons.Default.Edit, contentDescription = "Rename profile")
                        }
                        IconButton(onClick = onDelete) {
                            Icon(imageVector = Icons.Default.Delete, contentDescription = "Delete profile")
                        }
                    }

                    OutlinedButton(
                        onClick = onExport,
                        enabled = summary.availability is VoiceProfileAvailability.Available,
                    ) {
                        Text("Export")
                    }
                }
            }

            if (isSelected && !canDeselect) {
                Text(
                    text = "At least 2 sources required",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            if (summary.availability is VoiceProfileAvailability.Unavailable) {
                Text(
                    text = (summary.availability as VoiceProfileAvailability.Unavailable).diagnostic,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}
