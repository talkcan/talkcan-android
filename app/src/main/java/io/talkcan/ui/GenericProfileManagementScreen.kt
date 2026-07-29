package io.talkcan.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.talkcan.profile.ProfileFieldDeclaration
import io.talkcan.profile.ProfileScalarValue
import io.talkcan.profile.ProfileUiControl
import io.talkcan.profile.ProfileUiFieldDeclaration
import io.talkcan.profile.SecretReferenceState
import io.talkcan.service.GenericProfileManagementState
import io.talkcan.service.GenericProfileSummary
import io.talkcan.service.PublishedProfileTypeSummary

/**
 * 5.3/5.4: Generic profile-management surface.
 *
 * Lists the profile types published by installed packages (manifest metadata
 * only) with create entry points, and lists every retained profile with its
 * typed availability state. Create/edit forms render fields in the exact
 * declared UI order with text, multiline, toggle, number, choice, and
 * protected-secret controls. Secret plaintext is write-only: it is entered
 * once, committed to protected storage, and never redisplayed — edits show
 * only presence and offer retain/replace/clear.
 *
 * Deletion never rewrites channel configuration: referencing channels keep
 * their selected profile ID and surface their own typed unavailable state.
 */
@Composable
fun GenericProfileManagementScreen(
    state: GenericProfileManagementState,
    actions: PttUiActions,
    modifier: Modifier = Modifier,
) {
    var formTarget by remember { mutableStateOf<FormTarget?>(null) }
    var deleteTarget by remember { mutableStateOf<GenericProfileSummary?>(null) }

    val target = formTarget
    if (target != null) {
        ProfileForm(
            target = target,
            state = state,
            actions = actions,
            onClose = { formTarget = null },
            modifier = modifier,
        )
        return
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        TerminalHeader(
            title = "Manage profiles",
            subtitle = "Create, edit, and delete profiles for installed package types.",
        )

        state.failureMessage?.let { failure ->
            Text(
                failure,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            TalkcanSectionHeader(
                title = "Profile types",
                supportingText = "Configuration types published by installed packages.",
            )
            if (state.publishedTypes.isEmpty()) {
                Text(
                    "No installed package publishes profile types. Install a provider package first.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            state.publishedTypes.forEach { type ->
                ProfileTypeCard(
                    type = type,
                    operationActive = state.isOperationActive,
                    onCreate = { formTarget = FormTarget.Create(type) },
                )
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            TalkcanSectionHeader(
                title = "Profiles",
                supportingText = "Retained profiles and their current availability.",
            )
            if (state.profiles.isEmpty()) {
                Text(
                    "No profiles created yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            state.profiles.forEach { profile ->
                val type = state.publishedTypes.firstOrNull { it.identity == profile.identity }
                ProfileRowCard(
                    profile = profile,
                    type = type,
                    operationActive = state.isOperationActive,
                    onEdit = { formTarget = FormTarget.Edit(it, profile) },
                    onDelete = { deleteTarget = profile },
                )
            }
        }
    }

    deleteTarget?.let { profile ->
        ProfileDeleteDialog(
            profile = profile,
            operationActive = state.isOperationActive,
            onConfirm = {
                actions.deleteGenericProfile(profile.id)
                deleteTarget = null
            },
            onDismiss = { deleteTarget = null },
        )
    }
}

private sealed interface FormTarget {
    val type: PublishedProfileTypeSummary

    data class Create(override val type: PublishedProfileTypeSummary) : FormTarget
    data class Edit(
        override val type: PublishedProfileTypeSummary,
        val profile: GenericProfileSummary,
    ) : FormTarget
}

// ──────────────────────────────────────────────────────────────────────────
// Type and profile cards
// ──────────────────────────────────────────────────────────────────────────

@Composable
private fun ProfileTypeCard(
    type: PublishedProfileTypeSummary,
    operationActive: Boolean,
    onCreate: () -> Unit,
) {
    val hasExfiltrationRisk = type.declaresSecretsRead && type.declaresNetworkHttp

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        border = BorderStroke(
            1.dp,
            if (hasExfiltrationRisk || type.isSchemaIncompatible) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.outlineVariant
            },
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                "${type.label} (${type.identity.localTypeId})",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                "Source: ${type.canonicalOwner}/${type.canonicalRepository}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "Repository ID: ${type.identity.repositoryId.value}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            type.help?.let { help ->
                Text(help, style = MaterialTheme.typography.bodySmall)
            }
            if (type.hasSecretFields) {
                Text(
                    "Declares protected secret fields.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            // 5.5: disclose the combined exfiltration authority before profile binding.
            if (hasExfiltrationRisk) {
                TalkcanStatusBadge(
                    label = "Exfiltration risk",
                    tone = TalkcanStatusTone.Error,
                )
                Text(
                    "This package can read stored profile secrets and send them to any HTTPS origin.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            if (type.isSchemaIncompatible) {
                Text(
                    "Some retained profiles are incompatible with the current schema.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Button(
                onClick = onCreate,
                enabled = !operationActive,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("New profile")
            }
        }
    }
}

@Composable
private fun ProfileRowCard(
    profile: GenericProfileSummary,
    type: PublishedProfileTypeSummary?,
    operationActive: Boolean,
    onEdit: (PublishedProfileTypeSummary) -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        border = BorderStroke(
            1.dp,
            if (profile.isUnavailable) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.outlineVariant
            },
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                profile.displayName,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                buildString {
                    append(profile.typeLabel ?: profile.identity.localTypeId)
                    append(" — ")
                    append(profile.identity.repositoryId.value)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (profile.isUnavailable) {
                TalkcanStatusBadge(
                    label = "Unavailable: ${profile.unavailableReason}",
                    tone = TalkcanStatusTone.Error,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // Editing requires the declaring type to be published; a
                // missing-package or removed-type profile can only be deleted.
                OutlinedButton(
                    onClick = { type?.let(onEdit) },
                    enabled = type != null && !operationActive,
                ) {
                    Text("Edit")
                }
                OutlinedButton(
                    onClick = onDelete,
                    enabled = !operationActive,
                ) {
                    Text("Delete")
                }
            }
        }
    }
}

@Composable
private fun ProfileDeleteDialog(
    profile: GenericProfileSummary,
    operationActive: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete profile") },
        text = {
            Text(
                "Delete \"${profile.displayName}\"? Protected secrets are removed. " +
                    "Channels that selected this profile keep their selection and become " +
                    "unavailable until another profile is selected; their configuration is not rewritten.",
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = !operationActive) { Text("Delete") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

// ──────────────────────────────────────────────────────────────────────────
// Create / edit form (5.3)
// ──────────────────────────────────────────────────────────────────────────

@Composable
private fun ProfileForm(
    target: FormTarget,
    state: GenericProfileManagementState,
    actions: PttUiActions,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val type = target.type
    val schema = type.schema
    val existing = (target as? FormTarget.Edit)?.profile?.record
    val isEdit = existing != null

    var displayName by remember(target) { mutableStateOf(existing?.displayName ?: "") }
    var formError by remember(target) { mutableStateOf<String?>(null) }
    var submittedAt by remember(target) { mutableStateOf(-1L) }

    // Draft scalar state keyed by field ID, prefilled from the retained
    // record (edit) or declared defaults (create). Secret drafts hold only
    // NEW plaintext the user types — existing plaintext is never loaded.
    val textDrafts = remember(target) {
        mutableStateMapOf<String, String>().apply {
            for (ui in schema.uiFields) {
                when (val data = schema.fieldById(ui.field)) {
                    is ProfileFieldDeclaration.StringField ->
                        if (ui.control != ProfileUiControl.CHOICE) {
                            val retained = (existing?.scalarPayload?.get(ui.field) as? ProfileScalarValue.StringValue)?.value
                            put(ui.field, retained ?: (if (isEdit) "" else data.default ?: ""))
                        }
                    is ProfileFieldDeclaration.IntegerField -> {
                        val retained = (existing?.scalarPayload?.get(ui.field) as? ProfileScalarValue.IntegerValue)?.value
                        put(ui.field, retained?.toString() ?: (if (isEdit) "" else data.default?.toString() ?: ""))
                    }
                    is ProfileFieldDeclaration.SecretField -> put(ui.field, "")
                    else -> Unit
                }
            }
        }
    }
    val toggleDrafts = remember(target) {
        mutableStateMapOf<String, Boolean>().apply {
            for (ui in schema.uiFields) {
                val data = schema.fieldById(ui.field)
                if (data is ProfileFieldDeclaration.BooleanField) {
                    val retained = (existing?.scalarPayload?.get(ui.field) as? ProfileScalarValue.BooleanValue)?.value
                    put(ui.field, retained ?: data.default ?: false)
                }
            }
        }
    }
    val choiceDrafts = remember(target) {
        mutableStateMapOf<String, String?>().apply {
            for (ui in schema.uiFields) {
                if (ui.control == ProfileUiControl.CHOICE) {
                    val retained = (existing?.scalarPayload?.get(ui.field) as? ProfileScalarValue.StringValue)?.value
                    put(ui.field, retained?.takeIf { value -> ui.choices.orEmpty().any { it.value == value } })
                }
            }
        }
    }
    val clearDrafts = remember(target) { mutableStateMapOf<String, Boolean>() }
    // The form closes only after the coordinator reports a completed
    // operation (strictly greater completion count) and no failure was
    // recorded for it; a failure keeps the entered values and surfaces the
    // typed reason.
    LaunchedEffect(state.completedOperations) {
        val at = submittedAt
        if (at >= 0L && state.completedOperations > at) {
            val failure = state.failureMessage
            if (failure == null) {
                onClose()
            } else {
                formError = failure
                submittedAt = -1L
            }
        }
    }

    fun submit() {
        val candidate = ProfileFormCandidate(
            displayName = displayName,
            textValues = textDrafts.toMap(),
            toggleValues = toggleDrafts.toMap(),
            choiceSelections = choiceDrafts.toMap(),
            secretInputs = textDrafts.toMap(),
            secretDrafts = schema.secretFieldIds().associateWith { id ->
                SecretFieldDraft(
                    newPlaintext = textDrafts[id].orEmpty(),
                    clearRequested = clearDrafts[id] == true,
                )
            },
        )
        ProfileFormModel.validate(schema, candidate, existing).fold(
            onSuccess = { submission ->
                formError = null
                submittedAt = state.completedOperations
                when (submission) {
                    is ProfileFormSubmission.Create -> actions.createGenericProfile(
                        type.identity,
                        submission.displayName,
                        submission.scalars,
                        submission.secrets,
                    )
                    is ProfileFormSubmission.Edit -> {
                        val currentRecord = existing
                        if (currentRecord != null) {
                            actions.updateGenericProfile(
                                currentRecord.profileId,
                                submission.displayName,
                                submission.scalars,
                                submission.secretEdits,
                            )
                        }
                    }
                }
            },
            onFailure = { error -> formError = error.message },
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TerminalHeader(
            title = if (isEdit) "Edit profile" else "New profile",
            subtitle = "${type.label} — ${type.canonicalOwner}/${type.canonicalRepository}",
        )

        Text(
            "Repository ID: ${type.identity.repositoryId.value} · Type ID: ${type.identity.localTypeId}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // 5.5: disclose the combined exfiltration authority before profile binding.
        if (type.declaresSecretsRead && type.declaresNetworkHttp) {
            TalkcanStatusBadge(
                label = "Exfiltration risk",
                tone = TalkcanStatusTone.Error,
            )
            Text(
                "This package declares secrets.read and network.http. Secrets stored " +
                    "in this profile can be read by the package and sent to any HTTPS origin.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                fontWeight = FontWeight.SemiBold,
            )
        }

        OutlinedTextField(
            value = displayName,
            onValueChange = { displayName = it },
            label = { Text("Display name *") },
            singleLine = true,
            enabled = !state.isOperationActive,
            modifier = Modifier.fillMaxWidth(),
        )

        // Exact declared UI order.
        for (ui in schema.uiFields) {
            val data = schema.fieldById(ui.field) ?: continue
            when (data) {
                is ProfileFieldDeclaration.BooleanField -> {
                    ToggleControl(
                        ui = ui,
                        checked = toggleDrafts[ui.field] ?: false,
                        enabled = !state.isOperationActive,
                        onCheckedChange = { toggleDrafts[ui.field] = it },
                    )
                }
                is ProfileFieldDeclaration.SecretField -> {
                    val currentRecord = existing
                    if (currentRecord != null) {
                        SecretEditControl(
                            ui = ui,
                            present = currentRecord.secretReferences[ui.field] is SecretReferenceState.Present,
                            newText = textDrafts[ui.field].orEmpty(),
                            clearRequested = clearDrafts[ui.field] == true,
                            enabled = !state.isOperationActive,
                            onNewTextChange = { textDrafts[ui.field] = it },
                            onClearChange = { clearDrafts[ui.field] = it },
                        )
                    } else {
                        SecretCreateControl(
                            ui = ui,
                            required = data.required,
                            text = textDrafts[ui.field].orEmpty(),
                            enabled = !state.isOperationActive,
                            onTextChange = { textDrafts[ui.field] = it },
                        )
                    }
                }
                else -> {
                    val isChoice = ui.control == ProfileUiControl.CHOICE
                    val isNumber = data is ProfileFieldDeclaration.IntegerField
                    val isMultiline = ui.control == ProfileUiControl.MULTILINE
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        if (isChoice) {
                            ChoiceControl(
                                ui = ui,
                                selected = choiceDrafts[ui.field],
                                required = (data as ProfileFieldDeclaration.StringField).required,
                                enabled = !state.isOperationActive,
                                onSelected = { choiceDrafts[ui.field] = it },
                            )
                        } else {
                            OutlinedTextField(
                                value = textDrafts[ui.field].orEmpty(),
                                onValueChange = { textDrafts[ui.field] = it },
                                label = { Text(ProfileFormModel.fieldLabel(ui, data.required)) },
                                singleLine = !isMultiline,
                                minLines = if (isMultiline) 3 else 1,
                                maxLines = if (isMultiline) 8 else 1,
                                keyboardOptions = if (isNumber) {
                                    KeyboardOptions(keyboardType = KeyboardType.Number)
                                } else {
                                    KeyboardOptions.Default
                                },
                                supportingText = if (data is ProfileFieldDeclaration.IntegerField) {
                                    { Text(ProfileFormModel.numberRangeHint(data)) }
                                } else {
                                    null
                                },
                                enabled = !state.isOperationActive,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        ui.help?.let { help ->
                            Text(help, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }

        formError?.let { error ->
            Text(
                error,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(
                onClick = { submit() },
                enabled = submittedAt < 0L && !state.isOperationActive,
                modifier = Modifier.weight(1f),
            ) {
                Text(if (isEdit) "Save" else "Create")
            }
            OutlinedButton(
                onClick = onClose,
                enabled = submittedAt < 0L && !state.isOperationActive,
                modifier = Modifier.weight(1f),
            ) {
                Text("Cancel")
            }
            if (submittedAt >= 0L) {
                CircularProgressIndicator(modifier = Modifier.padding(4.dp))
            }
        }
    }
}


@Composable
private fun ToggleControl(
    ui: ProfileUiFieldDeclaration,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
            Text(ProfileFormModel.fieldLabel(ui, false), style = MaterialTheme.typography.bodyLarge)
        }
        ui.help?.let { help ->
            Text(help, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ChoiceControl(
    ui: ProfileUiFieldDeclaration,
    selected: String?,
    required: Boolean,
    enabled: Boolean,
    onSelected: (String?) -> Unit,
) {
    var showDialog by remember { mutableStateOf(false) }
    val selectedLabel = ui.choices.orEmpty().firstOrNull { it.value == selected }?.label

    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(ProfileFormModel.fieldLabel(ui, required), style = MaterialTheme.typography.bodyMedium)
        OutlinedButton(
            onClick = { showDialog = true },
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(selectedLabel ?: "Select…")
        }
        ui.help?.let { help ->
            Text(help, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text(ui.label) },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    ui.choices.orEmpty().forEach { choice ->
                        TextButton(
                            onClick = {
                                onSelected(choice.value)
                                showDialog = false
                            },
                        ) {
                            Text(choice.label)
                        }
                    }
                    if (!required) {
                        TextButton(
                            onClick = {
                                onSelected(null)
                                showDialog = false
                            },
                        ) {
                            Text("(none)")
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showDialog = false }) { Text("Cancel") }
            },
        )
    }
}

/**
 * 5.3: Create-mode protected-secret control. Plaintext is entered once and
 * masked; it is never redisplayed after commit.
 */
@Composable
private fun SecretCreateControl(
    ui: ProfileUiFieldDeclaration,
    required: Boolean,
    text: String,
    enabled: Boolean,
    onTextChange: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        OutlinedTextField(
            value = text,
            onValueChange = onTextChange,
            label = { Text(ProfileFormModel.fieldLabel(ui, required)) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            supportingText = { Text("Stored encrypted on this device. Never shown again.") },
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
        )
        ui.help?.let { help ->
            Text(help, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * 5.3/5.4: Edit-mode protected-secret control. Shows only presence — never
 * plaintext. A blank new value retains the stored secret exactly; entering a
 * value replaces it; clearing removes it.
 */
@Composable
private fun SecretEditControl(
    ui: ProfileUiFieldDeclaration,
    present: Boolean,
    newText: String,
    clearRequested: Boolean,
    enabled: Boolean,
    onNewTextChange: (String) -> Unit,
    onClearChange: (Boolean) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            buildString {
                append(ui.label)
                append(": ")
                append(if (present) "secret stored" else "not set")
            },
            style = MaterialTheme.typography.bodyMedium,
        )
        OutlinedTextField(
            value = newText,
            onValueChange = {
                onNewTextChange(it)
                if (it.isNotBlank()) onClearChange(false)
            },
            label = { Text(if (present) "New value (leave blank to keep)" else "New value (leave blank to keep unset)") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            supportingText = { Text("Entered values are write-only and never redisplayed.") },
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
        )
        if (present) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextButton(
                    onClick = {
                        val next = !clearRequested
                        onClearChange(next)
                        if (next) onNewTextChange("")
                    },
                    enabled = enabled,
                ) {
                    Text(if (clearRequested) "Keep secret" else "Clear secret")
                }
                if (clearRequested) {
                    Text(
                        "The stored secret will be removed.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
        ui.help?.let { help ->
            Text(help, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}