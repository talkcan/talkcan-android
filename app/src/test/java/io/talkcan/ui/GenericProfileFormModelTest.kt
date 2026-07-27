package io.talkcan.ui

import io.talkcan.dependency.GitHubRepositoryIdentity
import io.talkcan.profile.ProfileAvailability
import io.talkcan.profile.ProfileFieldDeclaration
import io.talkcan.profile.ProfileFieldType
import io.talkcan.profile.ProfileId
import io.talkcan.profile.ProfileRecord
import io.talkcan.profile.ProfileScalarValue
import io.talkcan.profile.ProfileSchema
import io.talkcan.profile.ProfileTypeIdentity
import io.talkcan.profile.ProfileUiChoice
import io.talkcan.profile.ProfileUiControl
import io.talkcan.profile.ProfileUiFieldDeclaration
import io.talkcan.profile.SecretEditAction
import io.talkcan.profile.SecretReferenceState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 5.2–5.4 presenter/model coverage for the generic profile form: exact
 * schema render order and control mapping, multiline losslessness, numeric
 * bounds, choice semantics, and write-only secret retain/replace/set/clear
 * mapping — all pure JVM, no Compose runtime.
 */
class GenericProfileFormModelTest {

    private fun fullSchema(apiKeyRequired: Boolean = true): ProfileSchema = ProfileSchema(
        dataFields = listOf(
            ProfileFieldDeclaration.StringField("endpoint", true, null, null),
            ProfileFieldDeclaration.StringField("system_prompt", false, null, null),
            ProfileFieldDeclaration.BooleanField("streaming", true, false),
            ProfileFieldDeclaration.IntegerField("max_tokens", false, 1024L, 1L, 8192L),
            ProfileFieldDeclaration.StringField("model_tier", true, null, listOf("small", "large")),
            ProfileFieldDeclaration.SecretField("api_key", apiKeyRequired),
        ),
        uiFields = listOf(
            ProfileUiFieldDeclaration("endpoint", ProfileUiControl.TEXT, "Endpoint", null, null),
            ProfileUiFieldDeclaration("system_prompt", ProfileUiControl.MULTILINE, "System Prompt", null, null),
            ProfileUiFieldDeclaration("streaming", ProfileUiControl.TOGGLE, "Streaming", null, null),
            ProfileUiFieldDeclaration("max_tokens", ProfileUiControl.NUMBER, "Max Tokens", null, null),
            ProfileUiFieldDeclaration(
                "model_tier",
                ProfileUiControl.CHOICE,
                "Model Tier",
                null,
                listOf(ProfileUiChoice("small", "Small"), ProfileUiChoice("large", "Large")),
            ),
            ProfileUiFieldDeclaration("api_key", ProfileUiControl.SECRET, "API Key", null, null),
        ),
    )

    private fun secretSchema(): ProfileSchema = ProfileSchema(
        dataFields = listOf(
            ProfileFieldDeclaration.StringField("base_url", true, null, null),
            ProfileFieldDeclaration.SecretField("api_key", true),
        ),
        uiFields = listOf(
            ProfileUiFieldDeclaration("base_url", ProfileUiControl.TEXT, "Base URL", null, null),
            ProfileUiFieldDeclaration("api_key", ProfileUiControl.SECRET, "API Key", null, null),
        ),
    )

    private fun existingRecord(secretState: SecretReferenceState): ProfileRecord = ProfileRecord(
        profileId = ProfileId("profile-1"),
        typeIdentity = ProfileTypeIdentity(GitHubRepositoryIdentity("123456"), "openai_compatible"),
        displayName = "Old Name",
        schemaVersion = 1,
        scalarPayload = mapOf("base_url" to ProfileScalarValue.StringValue("https://old.example/v1")),
        secretReferences = mapOf("api_key" to secretState),
        revision = 3L,
        availability = ProfileAvailability.AVAILABLE,
    )

    private fun fullCandidate(
        displayName: String = "Work Profile",
        endpoint: String = "https://api.example/v1",
        prompt: String = "",
        streaming: Boolean = false,
        maxTokens: String = "",
        modelTier: String? = "small",
        apiKey: String = "sk-abc",
    ) = ProfileFormCandidate(
        displayName = displayName,
        textValues = mapOf(
            "endpoint" to endpoint,
            "system_prompt" to prompt,
            "max_tokens" to maxTokens,
        ),
        toggleValues = mapOf("streaming" to streaming),
        choiceSelections = mapOf("model_tier" to modelTier),
        secretInputs = mapOf("api_key" to apiKey),
        secretDrafts = emptyMap(),
    )

    @Test
    fun `render sequence follows exact UI order with resolved data types`() {
        val sequence = ProfileFormModel.renderSequence(fullSchema())

        assertEquals(
            listOf("endpoint", "system_prompt", "streaming", "max_tokens", "model_tier", "api_key"),
            sequence.map { it.first.field },
        )
        assertEquals(
            listOf(
                ProfileUiControl.TEXT,
                ProfileUiControl.MULTILINE,
                ProfileUiControl.TOGGLE,
                ProfileUiControl.NUMBER,
                ProfileUiControl.CHOICE,
                ProfileUiControl.SECRET,
            ),
            sequence.map { it.first.control },
        )
        assertEquals(
            listOf(
                ProfileFieldType.STRING,
                ProfileFieldType.STRING,
                ProfileFieldType.BOOLEAN,
                ProfileFieldType.INTEGER,
                ProfileFieldType.STRING,
                ProfileFieldType.SECRET,
            ),
            sequence.map { it.second.type },
        )
    }

    @Test
    fun `create submission maps every control to its exact scalar value`() {
        val candidate = fullCandidate(
            prompt = "line one\nline two\n",
            streaming = true,
            maxTokens = "4096",
            modelTier = "large",
        )
        val submission = ProfileFormModel.validate(fullSchema(), candidate, existing = null)
            .getOrThrow() as ProfileFormSubmission.Create

        assertEquals("Work Profile", submission.displayName)
        assertEquals(
            "https://api.example/v1",
            (submission.scalars["endpoint"] as ProfileScalarValue.StringValue).value,
        )
        // Multiline text is preserved verbatim, newlines included.
        assertEquals(
            "line one\nline two\n",
            (submission.scalars["system_prompt"] as ProfileScalarValue.StringValue).value,
        )
        assertEquals(true, (submission.scalars["streaming"] as ProfileScalarValue.BooleanValue).value)
        assertEquals(4096L, (submission.scalars["max_tokens"] as ProfileScalarValue.IntegerValue).value)
        assertEquals("large", (submission.scalars["model_tier"] as ProfileScalarValue.StringValue).value)
        assertEquals("sk-abc", submission.secrets["api_key"].toString())
    }

    @Test
    fun `blank optional inputs omit keys while toggles always commit`() {
        val candidate = fullCandidate(apiKey = "")
        val submission = ProfileFormModel.validate(fullSchema(apiKeyRequired = false), candidate, existing = null)
            .getOrThrow() as ProfileFormSubmission.Create

        assertTrue(submission.scalars.containsKey("endpoint"))
        assertFalse(submission.scalars.containsKey("system_prompt"))
        assertFalse(submission.scalars.containsKey("max_tokens"))
        assertEquals(false, (submission.scalars["streaming"] as ProfileScalarValue.BooleanValue).value)
        // Optional blank secret is omitted entirely.
        assertFalse(submission.secrets.containsKey("api_key"))
    }

    @Test
    fun `required blanks fail with the field label`() {
        val schema = fullSchema()

        val blankName = ProfileFormModel.validate(
            schema,
            fullCandidate(displayName = "   "),
            existing = null,
        ).exceptionOrNull()
        assertEquals("Display name is required.", blankName?.message)

        val blankEndpoint = ProfileFormModel.validate(
            schema,
            fullCandidate(endpoint = ""),
            existing = null,
        ).exceptionOrNull()
        assertEquals("Endpoint is required.", blankEndpoint?.message)

        val noSelection = ProfileFormModel.validate(
            schema,
            fullCandidate(modelTier = null),
            existing = null,
        ).exceptionOrNull()
        assertEquals("Model Tier: a selection is required.", noSelection?.message)

        val blankSecret = ProfileFormModel.validate(
            schema,
            fullCandidate(apiKey = ""),
            existing = null,
        ).exceptionOrNull()
        assertEquals("API Key is required.", blankSecret?.message)
    }

    @Test
    fun `number inputs enforce whole-number parsing and declared bounds`() {
        val schema = fullSchema()

        val notANumber = ProfileFormModel.validate(
            schema,
            fullCandidate(maxTokens = "abc"),
            existing = null,
        ).exceptionOrNull()
        assertEquals("Max Tokens must be a whole number.", notANumber?.message)

        val belowMinimum = ProfileFormModel.validate(
            schema,
            fullCandidate(maxTokens = "0"),
            existing = null,
        ).exceptionOrNull()
        assertEquals("Max Tokens must be at least 1.", belowMinimum?.message)

        val aboveMaximum = ProfileFormModel.validate(
            schema,
            fullCandidate(maxTokens = "9000"),
            existing = null,
        ).exceptionOrNull()
        assertEquals("Max Tokens must be at most 8192.", aboveMaximum?.message)
    }

    @Test
    fun `edit secret drafts map to retain replace set and clear`() {
        val schema = secretSchema()
        val baseScalars = mapOf("base_url" to "https://old.example/v1")

        // Retain: blank input without clear leaves the edit map empty.
        val retained = ProfileFormModel.validate(
            schema,
            ProfileFormCandidate(
                displayName = "Name",
                textValues = baseScalars,
                toggleValues = emptyMap(),
                choiceSelections = emptyMap(),
                secretInputs = emptyMap(),
                secretDrafts = mapOf("api_key" to SecretFieldDraft()),
            ),
            existing = existingRecord(SecretReferenceState.Present("ref-1")),
        ).getOrThrow() as ProfileFormSubmission.Edit
        assertFalse(retained.secretEdits.containsKey("api_key"))

        // Replace: new plaintext over a present reference.
        val replaced = ProfileFormModel.validate(
            schema,
            ProfileFormCandidate(
                displayName = "Name",
                textValues = baseScalars,
                toggleValues = emptyMap(),
                choiceSelections = emptyMap(),
                secretInputs = emptyMap(),
                secretDrafts = mapOf("api_key" to SecretFieldDraft(newPlaintext = "sk-new")),
            ),
            existing = existingRecord(SecretReferenceState.Present("ref-1")),
        ).getOrThrow() as ProfileFormSubmission.Edit
        val replace = replaced.secretEdits["api_key"]
        assertTrue(replace is SecretEditAction.Replace)
        assertEquals("sk-new", (replace as SecretEditAction.Replace).plaintext.toString())

        // Set: new plaintext where none was stored.
        val set = ProfileFormModel.validate(
            schema,
            ProfileFormCandidate(
                displayName = "Name",
                textValues = baseScalars,
                toggleValues = emptyMap(),
                choiceSelections = emptyMap(),
                secretInputs = emptyMap(),
                secretDrafts = mapOf("api_key" to SecretFieldDraft(newPlaintext = "sk-set")),
            ),
            existing = existingRecord(SecretReferenceState.Absent),
        ).getOrThrow() as ProfileFormSubmission.Edit
        val setAction = set.secretEdits["api_key"]
        assertTrue(setAction is SecretEditAction.Set)
        assertEquals("sk-set", (setAction as SecretEditAction.Set).plaintext.toString())

        // Clear: explicit clear over a present reference.
        val cleared = ProfileFormModel.validate(
            schema,
            ProfileFormCandidate(
                displayName = "Name",
                textValues = baseScalars,
                toggleValues = emptyMap(),
                choiceSelections = emptyMap(),
                secretInputs = emptyMap(),
                secretDrafts = mapOf("api_key" to SecretFieldDraft(clearRequested = true)),
            ),
            existing = existingRecord(SecretReferenceState.Present("ref-1")),
        ).getOrThrow() as ProfileFormSubmission.Edit
        assertEquals(SecretEditAction.Clear, cleared.secretEdits["api_key"])

        // Plaintext wins over a stale clear toggle.
        val plaintextWins = ProfileFormModel.validate(
            schema,
            ProfileFormCandidate(
                displayName = "Name",
                textValues = baseScalars,
                toggleValues = emptyMap(),
                choiceSelections = emptyMap(),
                secretInputs = emptyMap(),
                secretDrafts = mapOf("api_key" to SecretFieldDraft(newPlaintext = "sk-win", clearRequested = true)),
            ),
            existing = existingRecord(SecretReferenceState.Present("ref-1")),
        ).getOrThrow() as ProfileFormSubmission.Edit
        val winAction = plaintextWins.secretEdits["api_key"]
        assertTrue(winAction is SecretEditAction.Replace)
        assertEquals("sk-win", (winAction as SecretEditAction.Replace).plaintext.toString())
    }

    @Test
    fun `edit submission trims the display name and never carries plaintext of stored secrets`() {
        val submission = ProfileFormModel.validate(
            secretSchema(),
            ProfileFormCandidate(
                displayName = "  New Name  ",
                textValues = mapOf("base_url" to "https://new.example/v1"),
                toggleValues = emptyMap(),
                choiceSelections = emptyMap(),
                secretInputs = emptyMap(),
                secretDrafts = mapOf("api_key" to SecretFieldDraft()),
            ),
            existing = existingRecord(SecretReferenceState.Present("ref-1")),
        ).getOrThrow() as ProfileFormSubmission.Edit

        assertEquals("New Name", submission.displayName)
        assertEquals(
            "https://new.example/v1",
            (submission.scalars["base_url"] as ProfileScalarValue.StringValue).value,
        )
        // Retained secret produces no edit and no plaintext anywhere.
        assertTrue(submission.secretEdits.isEmpty())
        assertNull(submission.scalars["api_key"])
    }

    @Test
    fun `labels and range hints render declared metadata`() {
        val ui = ProfileUiFieldDeclaration("endpoint", ProfileUiControl.TEXT, "Endpoint", null, null)
        assertEquals("Endpoint *", ProfileFormModel.fieldLabel(ui, required = true))
        assertEquals("Endpoint", ProfileFormModel.fieldLabel(ui, required = false))

        assertEquals(
            "Range: 1 to 8192",
            ProfileFormModel.numberRangeHint(ProfileFieldDeclaration.IntegerField("n", false, null, 1L, 8192L)),
        )
        assertEquals(
            "Minimum: 1",
            ProfileFormModel.numberRangeHint(ProfileFieldDeclaration.IntegerField("n", false, null, 1L, null)),
        )
        assertEquals(
            "Maximum: 8192",
            ProfileFormModel.numberRangeHint(ProfileFieldDeclaration.IntegerField("n", false, null, null, 8192L)),
        )
        assertEquals(
            "Whole number",
            ProfileFormModel.numberRangeHint(ProfileFieldDeclaration.IntegerField("n", false, null, null, null)),
        )
    }
}
