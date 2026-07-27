package io.talkcan.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 3.2: Shared exact profile-schema validator contract tests. */
class ProfileSchemaValidatorTest {

    private val schema = openAiSchema()

    private fun validSecrets(): Map<String, SecretReferenceState> =
        mapOf("api_key" to SecretReferenceState.Present("ref-1"))

    @Test
    fun validPayloadPasses() {
        assertNull(ProfileSchemaValidator.validatePayload(schema, basePayload(), validSecrets()))
    }

    @Test
    fun missingRequiredScalarFails() {
        val failure = ProfileSchemaValidator.validatePayload(schema, emptyMap(), validSecrets())
        assertNotNull(failure)
        assertTrue(failure is ProfileFailure.ValidationFailed)
    }

    @Test
    fun missingRequiredSecretFails() {
        val failure = ProfileSchemaValidator.validatePayload(schema, basePayload(), emptyMap())
        assertNotNull(failure)
        assertTrue(failure is ProfileFailure.ValidationFailed)
    }

    @Test
    fun absentRequiredSecretFails() {
        val failure = ProfileSchemaValidator.validatePayload(
            schema,
            basePayload(),
            mapOf("api_key" to SecretReferenceState.Absent),
        )
        assertNotNull(failure)
    }

    @Test
    fun optionalSecretMayBeAbsent() {
        val optional = openAiSchema(apiKeyRequired = false)
        assertNull(ProfileSchemaValidator.validatePayload(optional, basePayload(), emptyMap()))
        assertNull(
            ProfileSchemaValidator.validatePayload(
                optional,
                basePayload(),
                mapOf("api_key" to SecretReferenceState.Absent),
            )
        )
    }

    @Test
    fun wrongScalarTypeFails() {
        val payload = mapOf<String, ProfileScalarValue>("base_url" to ProfileScalarValue.IntegerValue(5))
        val failure = ProfileSchemaValidator.validatePayload(schema, payload, validSecrets())
        assertNotNull(failure)
    }

    @Test
    fun unknownScalarFieldFails() {
        val payload = basePayload() + ("bogus" to ProfileScalarValue.StringValue("x"))
        val failure = ProfileSchemaValidator.validatePayload(schema, payload, validSecrets())
        assertNotNull(failure)
    }

    @Test
    fun unknownSecretFieldFails() {
        val failure = ProfileSchemaValidator.validatePayload(
            schema,
            basePayload(),
            validSecrets() + ("bogus" to SecretReferenceState.Present("r")),
        )
        assertNotNull(failure)
    }

    @Test
    fun scalarValueForSecretFieldFails() {
        // A secret field id must never carry a scalar value.
        val payload = basePayload() + ("api_key" to ProfileScalarValue.StringValue("leak"))
        val failure = ProfileSchemaValidator.validatePayload(schema, payload, validSecrets())
        assertNotNull(failure)
    }

    @Test
    fun secretReferenceForScalarFieldFails() {
        val failure = ProfileSchemaValidator.validatePayload(
            schema,
            basePayload(),
            validSecrets() + ("base_url" to SecretReferenceState.Present("r")),
        )
        assertNotNull(failure)
    }

    @Test
    fun stringAllowedValuesEnforced() {
        val constrained = openAiSchema(
            extraFields = listOf(
                ProfileFieldDeclaration.StringField("region", true, "us", listOf("us", "eu")),
            ),
        )
        val ok = basePayload() + ("region" to ProfileScalarValue.StringValue("eu"))
        assertNull(ProfileSchemaValidator.validatePayload(constrained, ok, validSecrets()))
        val bad = basePayload() + ("region" to ProfileScalarValue.StringValue("mars"))
        assertNotNull(ProfileSchemaValidator.validatePayload(constrained, bad, validSecrets()))
    }

    @Test
    fun integerRangeEnforced() {
        val ranged = openAiSchema(
            extraFields = listOf(
                ProfileFieldDeclaration.IntegerField("timeout", true, 30L, 1L, 60L),
            ),
        )
        val ok = basePayload() + ("timeout" to ProfileScalarValue.IntegerValue(45))
        assertNull(ProfileSchemaValidator.validatePayload(ranged, ok, validSecrets()))
        val tooLow = basePayload() + ("timeout" to ProfileScalarValue.IntegerValue(0))
        assertNotNull(ProfileSchemaValidator.validatePayload(ranged, tooLow, validSecrets()))
        val tooHigh = basePayload() + ("timeout" to ProfileScalarValue.IntegerValue(61))
        assertNotNull(ProfileSchemaValidator.validatePayload(ranged, tooHigh, validSecrets()))
    }

    @Test
    fun revalidatePreservesPayloadVerdictOnly() {
        val record = ProfileRecord(
            profileId = ProfileId("p1"),
            typeIdentity = TYPE_A,
            displayName = "Profile",
            schemaVersion = ProfileLimits.SCHEMA_VERSION,
            scalarPayload = basePayload(),
            secretReferences = validSecrets(),
            revision = 3L,
            availability = ProfileAvailability.AVAILABLE,
        )
        // Compatible schema: revalidation passes and payload is untouched.
        assertNull(ProfileSchemaValidator.revalidateRecord(openAiSchema(), record))
        assertEquals(basePayload(), record.scalarPayload)

        // Incompatible schema (new required field): revalidation fails, payload preserved.
        val incompatible = openAiSchema(
            extraFields = listOf(ProfileFieldDeclaration.StringField("org_id", true, null, null)),
        )
        assertNotNull(ProfileSchemaValidator.revalidateRecord(incompatible, record))
        assertEquals(basePayload(), record.scalarPayload)
        assertEquals(3L, record.revision)
    }
}
