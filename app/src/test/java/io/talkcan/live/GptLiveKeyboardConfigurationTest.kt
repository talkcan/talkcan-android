package io.talkcan.live

import io.talkcan.model.OpaqueJsonObject
import io.talkcan.model.ProviderConfigurationResult
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GptLiveKeyboardConfigurationTest {
    private val configuration = GptLiveChannelProvider().descriptor.configuration

    @Test
    fun upgradingExistingChannelPreservesSettingsWithoutGrantingKeyboardAuthority() {
        val old = JSONObject()
            .put("backend_model", "gpt-5.6-luna").put("voice", "marin")
            .put("instructions", "Speak Spanish.")
            .put("allow_channel_control", true).put("allow_channel_read", false)
        val migrated = configuration.migrateAndValidate(1, OpaqueJsonObject.fromJsonObject(old))
        assertTrue(migrated is ProviderConfigurationResult.Success)
        val payload = (migrated as ProviderConfigurationResult.Success).configuration.payload.toJsonObject()
        assertFalse(payload.getBoolean("allow_keyboard"))
        assertTrue(payload.getBoolean("allow_channel_control"))
        assertEquals("Speak Spanish.", payload.getString("instructions"))
        assertTrue(payload.isNull("keyboard_profile"))
    }

    @Test
    fun enablingKeyboardRequiresProfileSelectionButDisablingItDoesNot() {
        val payload = configuration.defaultPayload().toJsonObject().put("allow_keyboard", true)
        assertTrue(configuration.validate(2, OpaqueJsonObject.fromJsonObject(payload)) is ProviderConfigurationResult.Failure)
        payload.put("keyboard_platform", "linux").put("keyboard_layout", "linux:us").put("keyboard_profile", "linux:us")
        assertTrue(configuration.validate(2, OpaqueJsonObject.fromJsonObject(payload)) is ProviderConfigurationResult.Success)
        payload.put("allow_keyboard", false).put("keyboard_profile", JSONObject.NULL)
        assertTrue(configuration.validate(2, OpaqueJsonObject.fromJsonObject(payload)) is ProviderConfigurationResult.Success)
    }

    @Test
    fun invalidLegacyPayloadCannotAcquireAuthorityThroughMigration() {
        val payload = configuration.defaultPayload().toJsonObject()
        assertTrue(configuration.migrateAndValidate(1, OpaqueJsonObject.fromJsonObject(payload)) is ProviderConfigurationResult.Failure)
    }
}
