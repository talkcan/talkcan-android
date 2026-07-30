package io.talkcan.ui

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.talkcan.dependency.PackageConfigurationLimits
import io.talkcan.channel.capability.ChannelCapability
import io.talkcan.model.ChannelConfigurationField
import io.talkcan.model.ChannelImplementationDescriptor
import io.talkcan.model.DynamicConfigurationChoiceRequest
import io.talkcan.model.DynamicConfigurationChoice
import io.talkcan.model.DynamicConfigurationChoiceResolution
import io.talkcan.model.DynamicConfigurationChoiceResolver
import io.talkcan.model.OpaqueJsonObject
import io.talkcan.resource.MountAvailability
import org.json.JSONObject

/**
 * Native, host-owned editor for one descriptor configuration. Providers describe values only;
 * this component owns every Compose control, navigation action, and directory picker request.
 */
/** A host-produced directory selection addressed by one configuration owner and field ID. */
data class DirectorySelection(val ownerId: String, val fieldId: String, val path: String)

@Composable
fun ChannelConfigurationScreen(
    title: String,
    configurationOwnerId: String,
    descriptor: ChannelImplementationDescriptor,
    initialPayload: OpaqueJsonObject,
    submitLabel: String,
    onSubmit: (OpaqueJsonObject) -> String?,
    choiceResolver: DynamicConfigurationChoiceResolver = DynamicConfigurationChoiceResolver {
        DynamicConfigurationChoiceResolution.Unavailable(
            io.talkcan.model.DynamicConfigurationChoiceUnavailableReason.HOST_NOT_READY,
        )
    },
    directorySelection: DirectorySelection?,
    onPickDirectory: (String, String) -> Unit,
    mountEntries: List<MountEditorEntry> = emptyList(),
    onPickMount: (MountSelectionRequest) -> Unit = {},
    modifier: Modifier = Modifier,
    synthesisVoiceChoices: List<ChannelSynthesisVoiceChoice> = emptyList(),
    initialSynthesisVoiceProfileId: String? = null,
    onCommitWithVoice: ((OpaqueJsonObject, String?, Boolean) -> ChannelConfigurationSubmitResult)? = null,
) {
    val initialValues = remember(descriptor, initialPayload) {
        val payload = initialPayload.toJsonObject()
        descriptor.configurationFields.mapNotNull { field ->
            if (payload.has(field.id)) field.id to initialFieldValue(field, payload) else null
        }.toMap()
    }
    val values = remember(descriptor, initialPayload) {
        mutableStateMapOf<String, String?>().apply { putAll(initialValues) }
    }
    var submissionError by remember(descriptor, initialPayload) { mutableStateOf<String?>(null) }
    var selectedSynthesisVoiceId by rememberSaveable(
        descriptor.implementationId,
        initialSynthesisVoiceProfileId,
    ) {
        mutableStateOf(initialSynthesisVoiceProfileId)
    }
    var unverifiedDialogState by remember(descriptor, initialPayload) {
        mutableStateOf<ChannelConfigurationSubmitResult.UnverifiedAcknowledgementRequired?>(null)
    }
    LaunchedEffect(directorySelection, configurationOwnerId, descriptor.implementationId) {
        directorySelection?.takeIf { it.ownerId == configurationOwnerId }?.let { selection ->
            if (descriptor.configurationFields.any { it.id == selection.fieldId }) {
                values[selection.fieldId] = selection.path
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        TerminalHeader(
            title = title,
            subtitle = "Review and adjust the settings below, then save your changes.",
        )

        if (descriptor.configurationFields.isEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            ) {
                Text(
                    text = "No configuration required",
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    TalkcanSectionHeader(
                        title = "Channel settings",
                        supportingText = "Values required by this channel provider.",
                    )
                    descriptor.configurationFields.forEach { field ->
                        if (field.isVisible(values)) {
                            ChannelConfigurationFieldEditor(
                                field = field,
                                configurationOwnerId = configurationOwnerId,
                                value = values[field.id],
                                dependencyValue = (field as? ChannelConfigurationField.DynamicChoiceField)
                                    ?.dependsOnFieldId
                                    ?.let(values::get),
                                choiceResolver = choiceResolver,
                                onValueChange = { newValue ->
                                    applyExplicitFieldEdit(
                                        descriptor.configurationFields,
                                        values,
                                        field.id,
                                        newValue,
                                    )
                                    submissionError = null
                                },
                                onPickDirectory = onPickDirectory,
                            )
                        }
                    }
                }
            }
        }

        if (descriptor.requiredCapabilities.contains(ChannelCapability.Synthesis) && synthesisVoiceChoices.isNotEmpty()) {
            SynthesisVoiceSelectorCard(
                choices = synthesisVoiceChoices,
                selectedProfileId = selectedSynthesisVoiceId,
                onSelectProfileId = { newId ->
                    selectedSynthesisVoiceId = newId
                    submissionError = null
                },
            )
        }

        if (mountEntries.isNotEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    TalkcanSectionHeader(
                        title = "Resource directories",
                        supportingText = "Folders this channel needs to read from.",
                    )
                    mountEntries.forEach { entry ->
                        ResourceMountEditorRow(
                            entry = entry,
                            onPickMount = {
                                onPickMount(
                                    MountSelectionRequest(
                                        ownerInstanceId = configurationOwnerId,
                                        implementationId = descriptor.implementationId,
                                        declarationId = entry.declaration.declarationId,
                                    ),
                                )
                            },
                        )
                    }
                }
            }
        }

        submissionError?.let { error ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                ),
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TalkcanStatusBadge(
                        label = "Error",
                        tone = TalkcanStatusTone.Error,
                    )
                    Text(
                        text = error,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }

        Button(
            onClick = {
                val payload = payloadWithFieldValues(initialPayload, descriptor.configurationFields, values)
                if (descriptor.requiredCapabilities.contains(ChannelCapability.Synthesis) && onCommitWithVoice != null) {
                    when (val result = onCommitWithVoice(payload, selectedSynthesisVoiceId, false)) {
                        is ChannelConfigurationSubmitResult.Success -> {
                            submissionError = null
                        }
                        is ChannelConfigurationSubmitResult.Error -> {
                            submissionError = result.message
                        }
                        is ChannelConfigurationSubmitResult.UnverifiedAcknowledgementRequired -> {
                            unverifiedDialogState = result
                        }
                    }
                } else {
                    submissionError = onSubmit(payload)
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(submitLabel, fontWeight = FontWeight.Bold)
        }
    }
    unverifiedDialogState?.let { dialogState ->
        AlertDialog(
            onDismissRequest = {
                unverifiedDialogState = null
                selectedSynthesisVoiceId = initialSynthesisVoiceProfileId
            },
            title = { Text("Unverified voice profile") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Voice profile '${dialogState.displayName}' is compatibility-unverified.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = dialogState.diagnostic,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val targetVoiceId = dialogState.profileId
                        unverifiedDialogState = null
                        val payload = payloadWithFieldValues(initialPayload, descriptor.configurationFields, values)
                        when (val result = onCommitWithVoice!!(payload, targetVoiceId, true)) {
                            is ChannelConfigurationSubmitResult.Success -> {
                                submissionError = null
                            }
                            is ChannelConfigurationSubmitResult.Error -> {
                                submissionError = result.message
                            }
                            is ChannelConfigurationSubmitResult.UnverifiedAcknowledgementRequired -> {
                                submissionError = result.diagnostic
                            }
                        }
                    },
                ) {
                    Text("Confirm")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        unverifiedDialogState = null
                        selectedSynthesisVoiceId = initialSynthesisVoiceProfileId
                    },
                ) {
                    Text("Cancel")
                }
            },
        )
    }
}

@Composable
private fun ChannelConfigurationFieldEditor(
    field: ChannelConfigurationField,
    value: String?,
    onValueChange: (String?) -> Unit,
    dependencyValue: String?,
    choiceResolver: DynamicConfigurationChoiceResolver,
    configurationOwnerId: String,
    onPickDirectory: (String, String) -> Unit,
) {
    when (field) {
        is ChannelConfigurationField.BooleanField -> Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(field.label, style = MaterialTheme.typography.bodyLarge)
            Switch(
                checked = value == "true",
                onCheckedChange = { onValueChange(it.toString()) },
            )
        }

        is ChannelConfigurationField.TextField -> OutlinedTextField(
            value = value.orEmpty(),
            onValueChange = onValueChange,
            label = { Text(requiredLabel(field)) },
            minLines = if (field.multiline) 3 else 1,
            modifier = Modifier.fillMaxWidth(),
        )

        is ChannelConfigurationField.NumberField -> OutlinedTextField(
            value = value.orEmpty(),
            onValueChange = onValueChange,
            label = { Text(requiredLabel(field)) },
            supportingText = {
                field.minimum?.let { minimum ->
                    Text("Enter a number from $minimum" + field.maximum?.let { " to $it" }.orEmpty())
                } ?: field.maximum?.let { maximum -> Text("Enter a number up to $maximum") }
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
            modifier = Modifier.fillMaxWidth(),
        )

        is ChannelConfigurationField.ChoiceField -> ChoiceEditor(field, value, onValueChange)

        is ChannelConfigurationField.DynamicChoiceField -> DynamicChoiceEditor(
            field = field,
            selectedValue = value,
            dependencyValue = dependencyValue,
            choiceResolver = choiceResolver,
            onValueChange = onValueChange,
        )

        is ChannelConfigurationField.DirectoryField -> Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(requiredLabel(field), style = MaterialTheme.typography.bodyLarge)
            Text(
                value ?: "No directory selected",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(
                onClick = { onPickDirectory(configurationOwnerId, field.id) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (value == null) "Select directory" else "Change directory")
            }
        }
    }
}

@Composable
private fun ChoiceEditor(
    field: ChannelConfigurationField.ChoiceField,
    value: String?,
    onValueChange: (String?) -> Unit,
) {
    var expanded by remember(field.id) { mutableStateOf(false) }
    val selected = field.choices.firstOrNull { it.id == value }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(requiredLabel(field), style = MaterialTheme.typography.bodyLarge)
        OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(selected?.label ?: "Select ${field.label}")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            field.choices.forEach { choice ->
                DropdownMenuItem(
                    text = { Text(choice.label) },
                    onClick = {
                        onValueChange(choice.id)
                        expanded = false
                    },
                )
            }
        }
    }
}

internal fun ChannelConfigurationField.isVisible(values: Map<String, String?>): Boolean = when (this) {
    is ChannelConfigurationField.DynamicChoiceField ->
        visibleWhenFieldId == null || values[visibleWhenFieldId] == visibleWhenValue
    else -> true
}

@Composable
private fun DynamicChoiceEditor(
    field: ChannelConfigurationField.DynamicChoiceField,
    selectedValue: String?,
    dependencyValue: String?,
    choiceResolver: DynamicConfigurationChoiceResolver,
    onValueChange: (String?) -> Unit,
) {
    var expanded by remember(field.id) { mutableStateOf(false) }
    var editorState by remember(field.id) {
        mutableStateOf(
            DynamicChoiceEditorState(
                activeRequest = null,
                resolution = DynamicConfigurationChoiceResolution.Loading,
            ),
        )
    }
    var retryNonce by remember(field.id) { mutableStateOf(0) }
    LaunchedEffect(field.source, dependencyValue, choiceResolver, retryNonce) {
        val request = DynamicConfigurationChoiceRequest(
            source = field.source,
            dependencyValue = dependencyValue,
            sourceKind = field.effectiveSourceKind,
            requestingFieldId = field.id,
            dependencyFieldId = field.dependsOnFieldId,
        )
        editorState = editorState.onRequestStarted(request)
        val resolved = try {
            choiceResolver.resolve(request)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            DynamicConfigurationChoiceResolution.Unavailable(
                io.talkcan.model.DynamicConfigurationChoiceUnavailableReason.DISCOVERY_FAILED,
            )
        }
        editorState = editorState.onResultArrived(request, resolved)
    }
    val presentation = dynamicChoicePresentation(field.label, editorState.resolution, selectedValue, dependencyValue)

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(requiredLabel(field), style = MaterialTheme.typography.bodyLarge)
        presentation.statusText?.let { message ->
            if (presentation.statusIsError) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TalkcanStatusBadge(
                        label = "Error",
                        tone = TalkcanStatusTone.Error,
                    )
                    Text(
                        message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            } else {
                Text(
                    message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (editorState.resolution is DynamicConfigurationChoiceResolution.Unavailable) {
            TextButton(onClick = { retryNonce += 1 }) {
                Text("Retry")
            }
        }
        OutlinedButton(
            enabled = presentation.selectionEnabled,
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(presentation.selectedLabel ?: "Select ${field.label}")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            presentation.choices.forEach { choice ->
                DropdownMenuItem(
                    text = { Text(choice.label) },
                    onClick = {
                        onValueChange(choice.id)
                        expanded = false
                    },
                )
            }
        }
    }
}

/**
 * Editor resolution state for one dynamic-choice field (task 6.3). [activeRequest] is the request
 * the editor most recently started; a result is published only when it was produced for that exact
 * request, so a late predecessor result — e.g. models resolved for a profile the user already
 * changed — is suppressed instead of populating the current field.
 */
internal data class DynamicChoiceEditorState(
    val activeRequest: DynamicConfigurationChoiceRequest?,
    val resolution: DynamicConfigurationChoiceResolution,
)

/** Marks [request] as the editor's active resolution target and returns the field to Loading. */
internal fun DynamicChoiceEditorState.onRequestStarted(
    request: DynamicConfigurationChoiceRequest,
): DynamicChoiceEditorState = copy(
    activeRequest = request,
    resolution = DynamicConfigurationChoiceResolution.Loading,
)

/**
 * Publishes [result] only if it was produced for the editor's current [activeRequest]; a result for
 * a superseded request is dropped unchanged, preserving the current Loading or successor state.
 */
internal fun DynamicChoiceEditorState.onResultArrived(
    request: DynamicConfigurationChoiceRequest,
    result: DynamicConfigurationChoiceResolution,
): DynamicChoiceEditorState = if (request == activeRequest) copy(resolution = result) else this

internal fun dynamicChoiceUnavailableMessage(
    resolution: DynamicConfigurationChoiceResolution.Unavailable,
    dependencyValue: String?,
): String = when (resolution.reason) {
    io.talkcan.model.DynamicConfigurationChoiceUnavailableReason.DEPENDENCY_MISSING ->
        if (dependencyValue == null) "Choose the required dependency first." else "The selected dependency is unavailable."
    io.talkcan.model.DynamicConfigurationChoiceUnavailableReason.SOURCE_UNAVAILABLE ->
        "Choices are currently unavailable."
    io.talkcan.model.DynamicConfigurationChoiceUnavailableReason.DISCOVERY_FAILED ->
        "Could not load choices. Try again."
    io.talkcan.model.DynamicConfigurationChoiceUnavailableReason.HOST_NOT_READY ->
        "Choices are unavailable until the host is ready."
    io.talkcan.model.DynamicConfigurationChoiceUnavailableReason.RESOLUTION_TIMED_OUT ->
        "Loading choices timed out. Try again."
}

/** Pure editor projection for one dynamic choice field; the composable renders this verbatim. */
internal data class DynamicChoicePresentation(
    val choices: List<DynamicConfigurationChoice>,
    val selectedLabel: String?,
    val selectionEnabled: Boolean,
    val statusText: String?,
    val statusIsError: Boolean,
)

internal fun dynamicChoicePresentation(
    fieldLabel: String,
    resolution: DynamicConfigurationChoiceResolution,
    selectedValue: String?,
    dependencyValue: String?,
): DynamicChoicePresentation {
    val selectedLabel = (resolution as? DynamicConfigurationChoiceResolution.Available)
        ?.choices
        ?.firstOrNull { it.id == selectedValue }
        ?.label
        ?: selectedValue?.let { "Unavailable: $it" }
    return when (resolution) {
        DynamicConfigurationChoiceResolution.Loading -> DynamicChoicePresentation(
            choices = emptyList(),
            selectedLabel = selectedLabel,
            selectionEnabled = false,
            statusText = "Loading ${fieldLabel.lowercase()}…",
            statusIsError = false,
        )
        is DynamicConfigurationChoiceResolution.Unavailable -> DynamicChoicePresentation(
            choices = emptyList(),
            selectedLabel = selectedLabel,
            selectionEnabled = false,
            statusText = dynamicChoiceUnavailableMessage(resolution, dependencyValue),
            statusIsError = true,
        )
        is DynamicConfigurationChoiceResolution.Available -> DynamicChoicePresentation(
            choices = resolution.choices,
            selectedLabel = selectedLabel,
            selectionEnabled = true,
            statusText = null,
            statusIsError = false,
        )
    }
}

private fun requiredLabel(field: ChannelConfigurationField): String =
    if (field.required) field.label else "${field.label} (optional)"

internal fun initialFieldValue(field: ChannelConfigurationField, payload: JSONObject): String? {
    val raw = payload.opt(field.id)
    if (raw == null || raw == JSONObject.NULL) return null
    return when (field) {
        is ChannelConfigurationField.BooleanField -> (raw as? Boolean)?.toString()
        is ChannelConfigurationField.TextField,
        is ChannelConfigurationField.ChoiceField,
        is ChannelConfigurationField.DynamicChoiceField,
        is ChannelConfigurationField.DirectoryField,
        -> raw as? String
        is ChannelConfigurationField.NumberField -> (raw as? Number)?.toString()
    }
}

/**
 * Applies one explicit user edit to [fieldId] on [values], clearing every transitive dynamic-choice
 * descendant whose [ChannelConfigurationField.DynamicChoiceField.dependsOnFieldId] chain eventually
 * reaches [fieldId]. Cleared descendants are nulled, never auto-selected, so the immediate child
 * re-renders "Select <label>" and grandchildren fall back to dependency guidance instead of a stale
 * "Unavailable: <id>". Unrelated values are left untouched.
 *
 * Resolver Loading/Unavailable states and initial rendering never flow through here, so passive
 * unavailability preserves persisted scalars. Generic and cycle-safe: dependents derive from [fields]
 * alone and each declared field is visited at most once, bounded by [PackageConfigurationLimits.MAX_FIELDS].
 */
internal fun applyExplicitFieldEdit(
    fields: List<ChannelConfigurationField>,
    values: MutableMap<String, String?>,
    fieldId: String,
    newValue: String?,
) {
    for (dependentId in transitiveDynamicDependents(fields, fieldId)) {
        if (values.containsKey(dependentId)) values[dependentId] = null
    }
    values[fieldId] = newValue
}

/**
 * IDs of every [ChannelConfigurationField.DynamicChoiceField] whose dependency chain transitively
 * reaches [rootId]. Each field is collected at most once; bounded by [PackageConfigurationLimits.MAX_FIELDS].
 */
private fun transitiveDynamicDependents(
    fields: List<ChannelConfigurationField>,
    rootId: String,
): Set<String> {
    val childrenByParent = HashMap<String, MutableList<String>>()
    for (field in fields) {
        if (field is ChannelConfigurationField.DynamicChoiceField) {
            field.dependsOnFieldId?.let { parent ->
                childrenByParent.getOrPut(parent) { mutableListOf() }.add(field.id)
            }
        }
    }
    val dependents = LinkedHashSet<String>()
    val frontier = ArrayDeque<String>()
    frontier.addLast(rootId)
    var visited = 0
    while (frontier.isNotEmpty() && visited <= PackageConfigurationLimits.MAX_FIELDS) {
        val current = frontier.removeFirst()
        visited++
        childrenByParent[current]?.forEach { child ->
            if (dependents.add(child)) frontier.addLast(child)
        }
    }
    return dependents
}

/**
 * Only declared field IDs are changed. All unknown keys remain in the copied opaque object so
 * newer providers can survive a host edit without data loss.
 */
internal fun payloadWithFieldValues(
    initialPayload: OpaqueJsonObject,
    fields: List<ChannelConfigurationField>,
    values: Map<String, String?>,
): OpaqueJsonObject {
    val payload = initialPayload.toJsonObject()
    fields.forEach { field ->
        if (!values.containsKey(field.id)) return@forEach
        val value = values[field.id]
        when (field) {
            is ChannelConfigurationField.BooleanField -> payload.put(field.id, value == "true")
            is ChannelConfigurationField.TextField,
            is ChannelConfigurationField.ChoiceField,
            is ChannelConfigurationField.DynamicChoiceField,
            is ChannelConfigurationField.DirectoryField,
            -> payload.put(field.id, value ?: JSONObject.NULL)
            is ChannelConfigurationField.NumberField -> {
                val parsed = value?.toLongOrNull()
                payload.put(field.id, parsed ?: value ?: JSONObject.NULL)
            }
        }
    }
    return OpaqueJsonObject.fromJsonObject(payload)
}

/**
 * 2.6/2.7: Host-rendered editor row for one declared resource mount. Shows only
 * package-provided label/help and the portable status; the pick button launches
 * the generic SAF directory-tree selection keyed by owner instance, provider
 * implementation, and declaration ID. Never renders a grant blob, URI, or path.
 */
@Composable
private fun ResourceMountEditorRow(
    entry: MountEditorEntry,
    onPickMount: () -> Unit,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = if (entry.declaration.required) entry.declaration.label else "${entry.declaration.label} (optional)",
            style = MaterialTheme.typography.bodyLarge,
        )
        entry.declaration.help?.let { help ->
            Text(
                text = help,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val (badgeLabel, badgeTone) = when (entry.availability) {
                is MountAvailability.Available -> "Available" to TalkcanStatusTone.Ready
                is MountAvailability.Unavailable -> if (entry.isBlocking) {
                    "Action needed" to TalkcanStatusTone.Error
                } else {
                    "Not set" to TalkcanStatusTone.Neutral
                }
            }
            TalkcanStatusBadge(
                label = badgeLabel,
                tone = badgeTone,
            )
            Text(
                text = entry.statusPortable,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        OutlinedButton(
            onClick = onPickMount,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                when (entry.availability) {
                    is MountAvailability.Available -> "Change directory"
                    is MountAvailability.Unavailable -> "Select directory"
                },
            )
        }
    }
}