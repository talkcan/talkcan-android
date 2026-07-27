package io.talkcan.ui

import io.talkcan.profile.ProfileFieldDeclaration
import io.talkcan.profile.ProfileRecord
import io.talkcan.profile.ProfileScalarValue
import io.talkcan.profile.ProfileSchema
import io.talkcan.profile.ProfileUiControl
import io.talkcan.profile.ProfileUiFieldDeclaration
import io.talkcan.profile.SecretEditAction
import io.talkcan.profile.SecretReferenceState

/**
 * 5.3/5.4: Pure form model shared by the Compose surface and JVM tests.
 *
 * Validation and payload mapping are independent of Compose state so the
 * exact render order, control mapping, multiline losslessness, numeric
 * bounds, choice semantics, and write-only secret retain/replace/clear
 * behavior are unit-testable without a device.
 */

/** Draft of one protected-secret field in the edit form. */
public data class SecretFieldDraft(
    val newPlaintext: String = "",
    val clearRequested: Boolean = false,
)

/**
 * Candidate form contents at submission time. Secret plaintext appears only
 * as transient input ([secretInputs] for create, [SecretFieldDraft.newPlaintext]
 * for edit); retained secrets are never loaded into the candidate.
 */
public data class ProfileFormCandidate(
    val displayName: String,
    val textValues: Map<String, String>,
    val toggleValues: Map<String, Boolean>,
    val choiceSelections: Map<String, String?>,
    val secretInputs: Map<String, String>,
    val secretDrafts: Map<String, SecretFieldDraft>,
)

/** Validated submission payload routed to create or edit actions. */
public sealed interface ProfileFormSubmission {
    public data class Create(
        val displayName: String,
        val scalars: Map<String, ProfileScalarValue>,
        val secrets: Map<String, CharSequence>,
    ) : ProfileFormSubmission

    public data class Edit(
        val displayName: String,
        val scalars: Map<String, ProfileScalarValue>,
        val secretEdits: Map<String, SecretEditAction>,
    ) : ProfileFormSubmission
}

public object ProfileFormModel {

    /**
     * The exact render sequence: schema UI field order, each UI declaration
     * paired with its resolved data declaration. The Compose surface renders
     * controls in this order and never reorders fields.
     */
    public fun renderSequence(schema: ProfileSchema): List<Pair<ProfileUiFieldDeclaration, ProfileFieldDeclaration>> =
        schema.uiFields.mapNotNull { ui -> schema.fieldById(ui.field)?.let { ui to it } }

    public fun fieldLabel(ui: ProfileUiFieldDeclaration, required: Boolean): String =
        if (required) "${ui.label} *" else ui.label

    public fun numberRangeHint(field: ProfileFieldDeclaration.IntegerField): String = when {
        field.minimum != null && field.maximum != null -> "Range: ${field.minimum} to ${field.maximum}"
        field.minimum != null -> "Minimum: ${field.minimum}"
        field.maximum != null -> "Maximum: ${field.maximum}"
        else -> "Whole number"
    }

    /**
     * 5.3: Validate one candidate against the declared schema in exact UI
     * field order and map it to a submission payload:
     *
     * - blank optional text/multiline/number/choice inputs omit the key;
     *   blank required inputs fail with the field label;
     * - multiline text is preserved verbatim (newlines are significant);
     * - number inputs must parse to a whole number within declared bounds;
     * - toggle values always commit an exact boolean;
     * - create mode collects nonblank secret inputs as plaintext to store;
     * - edit mode maps nonblank input to Replace (present) / Set (absent),
     *   explicit clear to Clear, and blank-without-clear to exact retain
     *   (absent from the edit map); existing plaintext is never required or
     *   redisplayed.
     */
    public fun validate(
        schema: ProfileSchema,
        candidate: ProfileFormCandidate,
        existing: ProfileRecord?,
    ): Result<ProfileFormSubmission> = runCatching {
        val name = candidate.displayName.trim()
        require(name.isNotEmpty()) { "Display name is required." }

        val scalars = LinkedHashMap<String, ProfileScalarValue>()
        val secrets = LinkedHashMap<String, CharSequence>()
        val secretEdits = LinkedHashMap<String, SecretEditAction>()

        for (ui in schema.uiFields) {
            when (val data = schema.fieldById(ui.field) ?: continue) {
                is ProfileFieldDeclaration.StringField -> {
                    if (ui.control == ProfileUiControl.CHOICE) {
                        val selection = candidate.choiceSelections[ui.field]
                        if (selection == null) {
                            require(!data.required) { "${ui.label}: a selection is required." }
                        } else {
                            scalars[ui.field] = ProfileScalarValue.StringValue(selection)
                        }
                    } else {
                        val text = candidate.textValues[ui.field].orEmpty()
                        if (text.isBlank()) {
                            require(!data.required) { "${ui.label} is required." }
                        } else {
                            scalars[ui.field] = ProfileScalarValue.StringValue(text)
                        }
                    }
                }
                is ProfileFieldDeclaration.BooleanField -> {
                    scalars[ui.field] = ProfileScalarValue.BooleanValue(candidate.toggleValues[ui.field] ?: false)
                }
                is ProfileFieldDeclaration.IntegerField -> {
                    val text = candidate.textValues[ui.field].orEmpty().trim()
                    if (text.isEmpty()) {
                        require(!data.required || data.default != null) { "${ui.label} is required." }
                    } else {
                        val value = text.toLongOrNull()
                            ?: throw IllegalArgumentException("${ui.label} must be a whole number.")
                        if (data.minimum != null) {
                            require(value >= data.minimum) { "${ui.label} must be at least ${data.minimum}." }
                        }
                        if (data.maximum != null) {
                            require(value <= data.maximum) { "${ui.label} must be at most ${data.maximum}." }
                        }
                        scalars[ui.field] = ProfileScalarValue.IntegerValue(value)
                    }
                }
                is ProfileFieldDeclaration.SecretField -> {
                    if (existing == null) {
                        val plaintext = candidate.secretInputs[ui.field].orEmpty()
                        if (plaintext.isBlank()) {
                            require(!data.required) { "${ui.label} is required." }
                        } else {
                            secrets[ui.field] = plaintext
                        }
                    } else {
                        val draft = candidate.secretDrafts[ui.field] ?: SecretFieldDraft()
                        val present = existing.secretReferences[ui.field] is SecretReferenceState.Present
                        when {
                            draft.newPlaintext.isNotBlank() ->
                                secretEdits[ui.field] =
                                    if (present) SecretEditAction.Replace(draft.newPlaintext)
                                    else SecretEditAction.Set(draft.newPlaintext)
                            draft.clearRequested ->
                                secretEdits[ui.field] = SecretEditAction.Clear
                            // Blank without clear: retain the stored reference exactly.
                        }
                    }
                }
            }
        }

        if (existing == null) {
            ProfileFormSubmission.Create(name, scalars, secrets)
        } else {
            ProfileFormSubmission.Edit(name, scalars, secretEdits)
        }
    }
}
