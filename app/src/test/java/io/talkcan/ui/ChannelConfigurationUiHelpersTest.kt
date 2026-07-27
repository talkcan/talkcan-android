package io.talkcan.ui

import io.talkcan.model.DynamicConfigurationChoiceSourceId
import io.talkcan.model.ChannelConfigurationField
import io.talkcan.model.DynamicConfigurationChoiceUnavailableReason
import io.talkcan.model.DynamicConfigurationChoiceResolution
import io.talkcan.model.OpaqueJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelConfigurationUiHelpersTest {
    private val fields = listOf(
        ChannelConfigurationField.DynamicChoiceField(
            id = "connectionProfileId",
            label = "Connection profile",
            source = DynamicConfigurationChoiceSourceId.KEYBOARD_OUTPUT_PROFILES,
        ),
        ChannelConfigurationField.DynamicChoiceField(
            id = "modelId",
            label = "Model",
            source = DynamicConfigurationChoiceSourceId.KEYBOARD_OUTPUT_PROFILES,
        ),
        ChannelConfigurationField.TextField(
            id = "systemPrompt",
            label = "System prompt",
            multiline = true,
        ),
        ChannelConfigurationField.BooleanField(
            id = "keyboardEnabled",
            label = "Keyboard Enabled",
        ),
        ChannelConfigurationField.DynamicChoiceField(
            id = "keyboardProfileId",
            label = "Keyboard Profile",
            source = DynamicConfigurationChoiceSourceId.KEYBOARD_OUTPUT_PROFILES,
            dependsOnFieldId = "keyboardEnabled",
            visibleWhenFieldId = "keyboardEnabled",
            visibleWhenValue = "true",
        ),
    )
    @Test
    fun `OpenAI edit preserves unavailable model selection and multiline prompt while updating declared values`() {
        val initial = OpaqueJsonObject.parse(
            """{
                "connectionProfileId":"old-profile",
                "modelId":"retired-model",
                "systemPrompt":"old prompt",
                "keyboardEnabled":false,
                "keyboardProfileId":null,
                "providerExtension":{"retained":true}
            }""",
        ).getOrThrow()

        val result = payloadWithFieldValues(
            initialPayload = initial,
            fields = fields,
            values = mapOf(
                "connectionProfileId" to "replacement-profile",
                "systemPrompt" to "Line one\nLine two\n\nLine four",
                "keyboardEnabled" to "true",
                "keyboardProfileId" to "keyboard-office",
            ),
        ).toJsonObject()

        assertEquals("replacement-profile", result.getString("connectionProfileId"))
        assertEquals("retired-model", result.getString("modelId"))
        assertEquals("Line one\nLine two\n\nLine four", result.getString("systemPrompt"))
        assertTrue(result.getBoolean("keyboardEnabled"))
        assertEquals("keyboard-office", result.getString("keyboardProfileId"))
        assertTrue(result.getJSONObject("providerExtension").getBoolean("retained"))
    }

    @Test
    fun `conditional dynamic choice visibility is driven only by its declared scalar dependency`() {
        val keyboardProfile = fields.filterIsInstance<ChannelConfigurationField.DynamicChoiceField>()
            .single { it.id == "keyboardProfileId" }
        val prompt = fields.filterIsInstance<ChannelConfigurationField.TextField>()
            .single { it.id == "systemPrompt" }

        assertFalse(keyboardProfile.isVisible(mapOf("keyboardEnabled" to "false")))
        assertTrue(keyboardProfile.isVisible(mapOf("keyboardEnabled" to "true")))
        assertTrue(prompt.isVisible(mapOf("keyboardEnabled" to "false")))
    }

    @Test
    fun `dynamic choice unavailability gives actionable messages without discarding a selected dependency`() {
        data class Case(
            val reason: DynamicConfigurationChoiceUnavailableReason,
            val dependency: String?,
            val expected: String,
        )
        val cases = listOf(
            Case(
                DynamicConfigurationChoiceUnavailableReason.DEPENDENCY_MISSING,
                null,
                "Choose the required dependency first.",
            ),
            Case(
                DynamicConfigurationChoiceUnavailableReason.DEPENDENCY_MISSING,
                "deleted-profile",
                "The selected dependency is unavailable.",
            ),
            Case(
                DynamicConfigurationChoiceUnavailableReason.SOURCE_UNAVAILABLE,
                "profile",
                "Choices are currently unavailable.",
            ),
            Case(
                DynamicConfigurationChoiceUnavailableReason.DISCOVERY_FAILED,
                "profile",
                "Could not load choices. Try again.",
            ),
            Case(
                DynamicConfigurationChoiceUnavailableReason.HOST_NOT_READY,
                "profile",
                "Choices are unavailable until the host is ready.",
            ),
        )

        cases.forEach { case ->
            assertEquals(
                case.expected,
                dynamicChoiceUnavailableMessage(
                    DynamicConfigurationChoiceResolution.Unavailable(case.reason),
                    case.dependency,
                ),
            )
        }
    }

    @Test
    fun `non dynamic choice fields are always visible regardless of values`() {
        val fields: List<ChannelConfigurationField> = listOf(
            ChannelConfigurationField.BooleanField("toggle", "Toggle"),
            ChannelConfigurationField.TextField("text", "Text"),
            ChannelConfigurationField.ChoiceField(
                "choice",
                "Choice",
                choices = listOf(ChannelConfigurationField.ChoiceField.Choice("a", "A")),
            ),
            ChannelConfigurationField.NumberField("number", "Number"),
            ChannelConfigurationField.DirectoryField("dir", "Directory"),
        )

        fields.forEach { field ->
            assertTrue("${field::class.simpleName} should be visible with empty values", field.isVisible(emptyMap()))
            assertTrue(
                "${field::class.simpleName} should be visible with arbitrary values",
                field.isVisible(mapOf("unrelated" to "x", field.id to "irrelevant")),
            )
        }
    }

    @Test
    fun `payload contains exactly the declared fields when starting from empty payload`() {
        val fields = listOf(
            ChannelConfigurationField.BooleanField("enabled", "Enabled"),
            ChannelConfigurationField.TextField("name", "Name", required = false),
            ChannelConfigurationField.NumberField("count", "Count"),
        )
        val result = payloadWithFieldValues(
            initialPayload = OpaqueJsonObject.parse("{}").getOrThrow(),
            fields = fields,
            values = mapOf("enabled" to "true", "name" to "test", "count" to "7"),
        ).toJsonObject()

        assertEquals(3, result.length())
        assertTrue(result.getBoolean("enabled"))
        assertEquals("test", result.getString("name"))
        assertEquals(7L, result.getLong("count"))
    }
}
