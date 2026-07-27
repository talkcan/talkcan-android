package io.talkcan.ui

import io.talkcan.model.ChannelConfigurationField
import io.talkcan.model.ChannelConfigurationMigrationStep
import io.talkcan.model.ChannelConfigurationProvider
import io.talkcan.model.ChannelImplementationDescriptor
import io.talkcan.model.ChannelImplementationId
import io.talkcan.model.ChannelPreparationTraits
import io.talkcan.model.ChannelPresentationMetadata
import io.talkcan.model.OpaqueJsonObject
import io.talkcan.model.ProviderConfigurationResult
import io.talkcan.model.ValidatedChannelConfiguration
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelConfigurationPayloadTest {
    private val fields = listOf(
        ChannelConfigurationField.BooleanField("enabled", "Enabled"),
        ChannelConfigurationField.TextField("label", "Label", required = false),
        ChannelConfigurationField.ChoiceField(
            "profile",
            "Profile",
            choices = listOf(
                ChannelConfigurationField.ChoiceField.Choice("alpha", "Alpha"),
                ChannelConfigurationField.ChoiceField.Choice("beta", "Beta"),
            ),
        ),
        ChannelConfigurationField.NumberField("retryLimit", "Retry limit", minimum = 0, maximum = 99),
        ChannelConfigurationField.DirectoryField("directory", "Directory", required = false),
    )

    private val allFieldTypes = listOf(
        ChannelConfigurationField.BooleanField("enabled", "Enabled"),
        ChannelConfigurationField.TextField("label", "Label", required = false),
        ChannelConfigurationField.ChoiceField(
            "profile",
            "Profile",
            choices = listOf(
                ChannelConfigurationField.ChoiceField.Choice("alpha", "Alpha"),
                ChannelConfigurationField.ChoiceField.Choice("beta", "Beta"),
            ),
        ),
        ChannelConfigurationField.DynamicChoiceField(
            "model",
            "Model",
            source = io.talkcan.model.DynamicConfigurationChoiceSourceId.KEYBOARD_OUTPUT_PROFILES,
            dependsOnFieldId = "profile",
        ),
        ChannelConfigurationField.NumberField("retryLimit", "Retry limit", minimum = 0, maximum = 99),
        ChannelConfigurationField.DirectoryField("directory", "Directory", required = false),
    )

    @Test
    fun `native form payload converts every declared field type while preserving opaque provider fields`() {
        val initialPayload = opaque(
            """{
                "enabled":false,
                "label":"old label",
                "profile":"alpha",
                "retryLimit":3,
                "directory":"/old",
                "providerExtension":{"keep":true},
                "futureScalar":"untouched"
            }""",
        )

        val result = payloadWithFieldValues(
            initialPayload = initialPayload,
            fields = fields,
            values = mapOf(
                "enabled" to "true",
                "label" to "new label",
                "profile" to "beta",
                "retryLimit" to "42",
                "directory" to "/new",
            ),
        ).toJsonObject()

        assertTrue(result.getBoolean("enabled"))
        assertEquals("new label", result.getString("label"))
        assertEquals("beta", result.getString("profile"))
        assertEquals(42L, result.getLong("retryLimit"))
        assertEquals("/new", result.getString("directory"))
        assertTrue(result.getJSONObject("providerExtension").getBoolean("keep"))
        assertEquals("untouched", result.getString("futureScalar"))
    }

    @Test
    fun `form extraction preserves typed values and treats mismatched optional values as absent`() {
        val payload = JSONObject(
            """{
                "enabled":true,
                "label":"usable text",
                "profile":"beta",
                "retryLimit":7,
                "directory":null,
                "wrongBoolean":"true",
                "wrongNumber":"7"
            }""",
        )

        val byId = fields.associateBy(ChannelConfigurationField::id)
        assertEquals("true", initialFieldValue(byId.getValue("enabled"), payload))
        assertEquals("usable text", initialFieldValue(byId.getValue("label"), payload))
        assertEquals("beta", initialFieldValue(byId.getValue("profile"), payload))
        assertEquals("7", initialFieldValue(byId.getValue("retryLimit"), payload))
        assertEquals(null, initialFieldValue(byId.getValue("directory"), payload))
        assertEquals(
            null,
            initialFieldValue(ChannelConfigurationField.BooleanField("wrongBoolean", "Wrong"), payload),
        )
        assertEquals(
            null,
            initialFieldValue(ChannelConfigurationField.NumberField("wrongNumber", "Wrong"), payload),
        )
    }

    @Test
    fun `optional nulls and invalid numeric text remain explicit for provider validation`() {
        val result = payloadWithFieldValues(
            initialPayload = opaque("""{"enabled":true,"providerExtension":"preserve"}"""),
            fields = fields,
            values = mapOf(
                "label" to null,
                "profile" to null,
                "retryLimit" to "not-a-number",
                "directory" to null,
            ),
        ).toJsonObject()

        assertTrue(result.isNull("label"))
        assertTrue(result.isNull("profile"))
        assertEquals("not-a-number", result.getString("retryLimit"))
        assertTrue(result.isNull("directory"))
        assertTrue(result.getBoolean("enabled"))
        assertEquals("preserve", result.getString("providerExtension"))
    }

    @Test
    fun `directory selections retain both owner and field identity for host routing`() {
        val first = DirectorySelection("instance-one", "directory", "/first")
        val second = DirectorySelection("instance-two", "directory", "/second")
        val differentField = DirectorySelection("instance-one", "archiveDirectory", "/archive")

        assertFalse(first == second)
        assertFalse(first == differentField)
        assertEquals("instance-one", first.ownerId)
        assertEquals("directory", first.fieldId)
    }

    @Test
    fun `all field types including dynamic choice are processed in declaration order`() {
        val initialPayload = opaque(
            """{
                "enabled":false,
                "label":"old",
                "profile":"alpha",
                "retryLimit":3,
                "directory":"/prev",
                "providerExtension":{"keep":true}
            }""",
        )

        val result = payloadWithFieldValues(
            initialPayload = initialPayload,
            fields = allFieldTypes,
            values = mapOf(
                "enabled" to "true",
                "label" to "new label",
                "profile" to "beta",
                "model" to "gpt-4",
                "retryLimit" to "7",
                "directory" to "/new",
            ),
        ).toJsonObject()

        // Verify every field type is correctly encoded
        assertTrue(result.getBoolean("enabled"))
        assertEquals("new label", result.getString("label"))
        assertEquals("beta", result.getString("profile"))
        assertEquals("gpt-4", result.getString("model"))
        assertEquals(7L, result.getLong("retryLimit"))
        assertEquals("/new", result.getString("directory"))
        assertTrue(result.getJSONObject("providerExtension").getBoolean("keep"))
        // Verify initialFieldValue correctly extracts all field types including dynamic choice
        val byId = allFieldTypes.associateBy(ChannelConfigurationField::id)
        assertEquals("true", initialFieldValue(byId.getValue("enabled"), result))
        assertEquals("new label", initialFieldValue(byId.getValue("label"), result))
        assertEquals("beta", initialFieldValue(byId.getValue("profile"), result))
        assertEquals("gpt-4", initialFieldValue(byId.getValue("model"), result))
        assertEquals("7", initialFieldValue(byId.getValue("retryLimit"), result))
        assertEquals("/new", initialFieldValue(byId.getValue("directory"), result))
    }
    
    @Test
    fun `initialFieldValue extracts dynamic choice field from payload`() {
        val field = ChannelConfigurationField.DynamicChoiceField(
            "model",
            "Model",
            source = io.talkcan.model.DynamicConfigurationChoiceSourceId.KEYBOARD_OUTPUT_PROFILES,
            dependsOnFieldId = "profile",
        )
        val payload = JSONObject("""{"model":"gpt-4","profile":"alpha"}""")
        assertEquals("gpt-4", initialFieldValue(field, payload))
    }
    
    @Test
    fun `initialFieldValue returns null for absent dynamic choice`() {
        val field = ChannelConfigurationField.DynamicChoiceField(
            "model",
            "Model",
            source = io.talkcan.model.DynamicConfigurationChoiceSourceId.KEYBOARD_OUTPUT_PROFILES,
            dependsOnFieldId = "profile",
        )
        val payload = JSONObject("""{"profile":"alpha"}""")
        assertEquals(null, initialFieldValue(field, payload))
    }

    @Test
    fun `empty field list preserves payload unchanged`() {
        val initialPayload = opaque("""{"key":"value"}""")
        val result = payloadWithFieldValues(
            initialPayload = initialPayload,
            fields = emptyList(),
            values = mapOf("key" to "replacement"),
        ).toJsonObject()

        assertEquals("value", result.getString("key"))
    }

    private fun opaque(encoded: String): OpaqueJsonObject = OpaqueJsonObject.parse(encoded).getOrThrow()

    @Test
    fun `OpaqueJsonObject parse-toJsonString round-trip preserves exact string`() {
        val original = opaque("""{"enabled":false,"nested":{"keep":true,"count":42},"future":"untouched"}""")
        val roundTripped = opaque(original.toJsonString())

        assertEquals(original.toJsonString(), roundTripped.toJsonString())
        assertEquals(original.toJsonObject().getBoolean("enabled"), roundTripped.toJsonObject().getBoolean("enabled"))
        assertTrue(roundTripped.toJsonObject().getJSONObject("nested").getBoolean("keep"))
        assertEquals(42, roundTripped.toJsonObject().getJSONObject("nested").getInt("count"))
        assertEquals("untouched", roundTripped.toJsonObject().getString("future"))
    }

    @Test
    fun `payload preserves untouched provider extension and future keys through repeated field edits`() {
        val initial = opaque(
            """{"enabled":true,"label":"v1","profile":"alpha","retryLimit":5,"directory":"/a","providerExtension":{"version":1,"sub":{"nested":true}},"futureKey":"original"}""",
        )

        val firstEdit = payloadWithFieldValues(
            initialPayload = initial,
            fields = fields,
            values = mapOf("label" to "v2", "retryLimit" to "10"),
        )
        val firstObj = firstEdit.toJsonObject()
        assertTrue(firstObj.getBoolean("enabled"))
        assertEquals("v2", firstObj.getString("label"))
        assertEquals(1, firstObj.getJSONObject("providerExtension").getInt("version"))
        assertTrue(firstObj.getJSONObject("providerExtension").getJSONObject("sub").getBoolean("nested"))
        assertEquals("original", firstObj.getString("futureKey"))

        val secondEdit = payloadWithFieldValues(
            initialPayload = firstEdit,
            fields = fields,
            values = mapOf("enabled" to "false", "profile" to "beta"),
        )
        val secondObj = secondEdit.toJsonObject()
        assertFalse(secondObj.getBoolean("enabled"))
        assertEquals("beta", secondObj.getString("profile"))
        assertEquals(1, secondObj.getJSONObject("providerExtension").getInt("version"))
        assertEquals("original", secondObj.getString("futureKey"))
    }

    @Test
    fun `empty payload round-trips through payloadWithFieldValues losslessly`() {
        val initial = opaque("""{}""")
        val result = payloadWithFieldValues(
            initialPayload = initial,
            fields = fields,
            values = mapOf("enabled" to "true", "label" to "text"),
        ).toJsonObject()

        assertTrue(result.getBoolean("enabled"))
        assertEquals("text", result.getString("label"))
    }

    @Test
    fun `OpaqueJsonObject parse rejects non object values`() {
        assertTrue(OpaqueJsonObject.parse("[]").isFailure)
        assertTrue(OpaqueJsonObject.parse("\"string\"").isFailure)
        assertTrue(OpaqueJsonObject.parse("42").isFailure)
        assertTrue(OpaqueJsonObject.parse("true").isFailure)
        assertTrue(OpaqueJsonObject.parse("null").isFailure)
    }

    @Test
    fun `default payload from generic descriptor populates all declared field types`() {
        val implementationId = ChannelImplementationId("test:generic-config")
        val genericFields = listOf(
            ChannelConfigurationField.BooleanField("enabled", "Enabled"),
            ChannelConfigurationField.TextField("name", "Name"),
            ChannelConfigurationField.NumberField("count", "Count", minimum = 0, maximum = 99),
            ChannelConfigurationField.ChoiceField("profile", "Profile", choices = listOf(
                ChannelConfigurationField.ChoiceField.Choice("alpha", "Alpha"),
                ChannelConfigurationField.ChoiceField.Choice("beta", "Beta"),
            )),
            ChannelConfigurationField.DynamicChoiceField(
                "model", "Model",
                source = io.talkcan.model.DynamicConfigurationChoiceSourceId.KEYBOARD_OUTPUT_PROFILES,
                dependsOnFieldId = "profile",
            ),
            ChannelConfigurationField.DirectoryField("directory", "Directory", required = false),
        )
        val provider = object : ChannelConfigurationProvider {
            override val implementationId = implementationId
            override val currentSchemaVersion = 1
            override fun defaultPayload(): OpaqueJsonObject = opaque(
                """{"enabled":true,"name":"default-name","count":42,"profile":"alpha","model":"default-model","directory":"/default","providerExtension":{"retained":true}}""",
            )
            override fun validate(schemaVersion: Int, payload: OpaqueJsonObject): ProviderConfigurationResult =
                ProviderConfigurationResult.Success(ValidatedChannelConfiguration(implementationId, schemaVersion, payload))
            override fun migrateStep(fromSchemaVersion: Int, payload: OpaqueJsonObject): ChannelConfigurationMigrationStep =
                ChannelConfigurationMigrationStep.Success(payload)
        }
        val descriptor = ChannelImplementationDescriptor(
            implementationId = implementationId,
            presentation = ChannelPresentationMetadata("Generic", "generic", "unavailable"),
            configuration = provider,
            configurationFields = genericFields,
            requiredCapabilities = emptySet(),
            preparationTraits = ChannelPreparationTraits(supportsRecoverablePreparation = false),
        )
        val defaultPayload = descriptor.configuration.defaultPayload()
        val defaultObj = defaultPayload.toJsonObject()

        assertEquals("true", initialFieldValue(genericFields[0], defaultObj))
        assertEquals("default-name", initialFieldValue(genericFields[1], defaultObj))
        assertEquals("42", initialFieldValue(genericFields[2], defaultObj))
        assertEquals("alpha", initialFieldValue(genericFields[3], defaultObj))
        assertEquals("default-model", initialFieldValue(genericFields[4], defaultObj))
        assertEquals("/default", initialFieldValue(genericFields[5], defaultObj))

        val edited = payloadWithFieldValues(
            initialPayload = defaultPayload,
            fields = descriptor.configurationFields,
            values = mapOf("count" to "77", "profile" to "beta"),
        ).toJsonObject()
        assertTrue(edited.getBoolean("enabled"))
        assertEquals("default-name", edited.getString("name"))
        assertEquals(77L, edited.getLong("count"))
        assertEquals("beta", edited.getString("profile"))
        assertEquals("default-model", edited.getString("model"))
        assertEquals("/default", edited.getString("directory"))
        assertTrue(edited.getJSONObject("providerExtension").getBoolean("retained"))
    }

}
