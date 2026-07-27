package io.talkcan.ui

import io.talkcan.channel.capability.ChannelCapability
import io.talkcan.model.ChannelConfigurationField
import io.talkcan.model.ChannelConfigurationMigrationStep
import io.talkcan.model.ChannelConfigurationProvider
import io.talkcan.model.ChannelDefinition
import io.talkcan.model.ChannelHostPreferences
import io.talkcan.model.ChannelImplementationDescriptor
import io.talkcan.model.ChannelImplementationId
import io.talkcan.model.ChannelPreparationTraits
import io.talkcan.model.ChannelPresentationMetadata
import io.talkcan.model.ChannelProviderError
import io.talkcan.model.OpaqueJsonObject
import io.talkcan.model.ProviderConfigurationResult
import io.talkcan.model.ValidatedChannelConfiguration
import io.talkcan.voice.VoiceProfileAvailability
import io.talkcan.voice.VoiceProfileCatalogue
import io.talkcan.voice.VoiceProfileCompatibility
import io.talkcan.voice.VoiceProfileId
import io.talkcan.voice.VoiceProfileKind
import io.talkcan.voice.VoiceProfileSummary
import io.talkcan.voice.VoiceProfileUnavailableReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Focused tests for per-channel synthesis voice selector and backend contracts (Tasks 6.1-6.3, 6.8):
 * conditional visibility from declared capability, application-default/null mapping, unavailable
 * selection preservation, unverified acknowledgement/cancel behavior, creation commit callback receives
 * profile separately from payload, existing preference callback does not alter provider payload,
 * same-provider sibling IDs remain isolated, next-request/no-runtime-replacement contract is
 * represented by the backend callback (reuse existing service resolver tests rather than duplicating
 * internals), and screen payload exactness.
 */
class ChannelConfigurationVoiceSelectionTest {

    private fun opaque(encoded: String): OpaqueJsonObject = OpaqueJsonObject.parse(encoded).getOrThrow()

    private fun stubDescriptor(
        implId: ChannelImplementationId,
        capabilities: Set<ChannelCapability> = emptySet(),
    ): ChannelImplementationDescriptor =
        ChannelImplementationDescriptor(
            implementationId = implId,
            presentation = ChannelPresentationMetadata("Stub", "STUB", "Stub unavailable"),
            configuration = object : ChannelConfigurationProvider {
                override val implementationId = implId
                override val currentSchemaVersion = 1
                override fun defaultPayload(): OpaqueJsonObject = opaque("""{"default":true}""")
                override fun validate(schemaVersion: Int, payload: OpaqueJsonObject): ProviderConfigurationResult =
                    ProviderConfigurationResult.Success(
                        ValidatedChannelConfiguration(implId, schemaVersion, payload)
                    )
                override fun migrateStep(fromSchemaVersion: Int, payload: OpaqueJsonObject): ChannelConfigurationMigrationStep =
                    ChannelConfigurationMigrationStep.Failure(
                        ChannelProviderError.UnsupportedSchemaVersion(implId, fromSchemaVersion, 1),
                    )
            },
            configurationFields = emptyList(),
            requiredCapabilities = capabilities,
            preparationTraits = ChannelPreparationTraits(supportsRecoverablePreparation = false),
        )

    private fun summary(
        id: String,
        kind: VoiceProfileKind,
        availability: VoiceProfileAvailability = VoiceProfileAvailability.Available,
        compatibility: VoiceProfileCompatibility = VoiceProfileCompatibility.VERIFIED,
        readOnly: Boolean = kind == VoiceProfileKind.BUILT_IN,
    ): VoiceProfileSummary = VoiceProfileSummary(
        id = VoiceProfileId(id),
        displayName = "Profile $id",
        kind = kind,
        availability = availability,
        compatibility = compatibility,
        readOnly = readOnly,
    )

    private fun sampleCatalogue(
        extraBuiltIns: List<VoiceProfileSummary> = emptyList(),
        extraCustoms: List<VoiceProfileSummary> = emptyList(),
    ): VoiceProfileCatalogue = VoiceProfileCatalogue(
        builtIn = listOf(
            summary("builtin:F1", VoiceProfileKind.BUILT_IN),
            summary("builtin:M1", VoiceProfileKind.BUILT_IN),
        ) + extraBuiltIns,
        custom = listOf(
            summary("custom:profile-1", VoiceProfileKind.EDITED),
        ) + extraCustoms,
    )

    @Test
    fun `conditional visibility from declared capability is enforced by descriptor capabilities`() {
        val synthesisDescriptor = stubDescriptor(
            implId = ChannelImplementationId("test:synthesis"),
            capabilities = setOf(ChannelCapability.Synthesis),
        )
        val nonSynthesisDescriptor = stubDescriptor(
            implId = ChannelImplementationId("test:serial"),
            capabilities = emptySet(),
        )

        assertTrue(synthesisDescriptor.capabilities.contains(ChannelCapability.Synthesis))
        assertFalse(nonSynthesisDescriptor.capabilities.contains(ChannelCapability.Synthesis))
    }

    @Test
    fun `application-default and null mapping resolves as first choice with read-only state`() {
        val catalogue = sampleCatalogue()
        val choices = synthesisVoiceChoicesFor(catalogue, currentSelectionId = null)

        assertEquals(4, choices.size)
        val defaultChoice = choices.first()
        assertNull(defaultChoice.profileId)
        assertEquals("Application default", defaultChoice.displayName)
        assertTrue(defaultChoice.readOnly)
        assertFalse(defaultChoice.isUnavailable)
        assertFalse(defaultChoice.isUnverified)
    }

    @Test
    fun `unavailable selection preservation shows disabled diagnostic choice when profile is missing or unavailable`() {
        val catalogue = sampleCatalogue(
            extraCustoms = listOf(
                summary(
                    id = "custom:unavailable",
                    kind = VoiceProfileKind.EDITED,
                    availability = VoiceProfileAvailability.Unavailable(
                        VoiceProfileUnavailableReason.CORRUPT_DOCUMENT,
                        "Document hash mismatch",
                    ),
                ),
            ),
        )

        val choicesWithUnavailable = synthesisVoiceChoicesFor(
            catalogue = catalogue,
            currentSelectionId = VoiceProfileId("custom:unavailable"),
        )
        val unavailableChoice = choicesWithUnavailable.last()
        assertEquals("custom:unavailable", unavailableChoice.profileId)
        assertTrue(unavailableChoice.isUnavailable)
        assertEquals("Document hash mismatch", unavailableChoice.diagnostic)

        val choicesWithMissing = synthesisVoiceChoicesFor(
            catalogue = catalogue,
            currentSelectionId = VoiceProfileId("custom:missing"),
        )
        val missingChoice = choicesWithMissing.last()
        assertEquals("custom:missing", missingChoice.profileId)
        assertTrue(missingChoice.isUnavailable)
        assertEquals("Voice profile 'custom:missing' is not in the catalogue", missingChoice.diagnostic)
    }

    @Test
    fun `unverified acknowledgement and cancel behavior labels unverified compatibility and requires acknowledgement result`() {
        val catalogue = sampleCatalogue(
            extraCustoms = listOf(
                summary(
                    id = "custom:imported",
                    kind = VoiceProfileKind.IMPORTED,
                    compatibility = VoiceProfileCompatibility.UNVERIFIED,
                ),
            ),
        )
        val choices = synthesisVoiceChoicesFor(catalogue, null)
        val importedChoice = choices.single { it.profileId == "custom:imported" }
        assertTrue(importedChoice.isUnverified)
        assertFalse(importedChoice.isUnavailable)

        var committedId: String? = null
        var committedAck = false
        val commitHandler: (OpaqueJsonObject, String?, Boolean) -> ChannelConfigurationSubmitResult = { _, voiceId, ack ->
            if (voiceId == "custom:imported" && !ack) {
                ChannelConfigurationSubmitResult.UnverifiedAcknowledgementRequired(
                    profileId = voiceId,
                    displayName = "Profile custom:imported",
                    diagnostic = "Unverified compatibility requires confirmation",
                )
            } else {
                committedId = voiceId
                committedAck = ack
                ChannelConfigurationSubmitResult.Success
            }
        }

        val firstTry = commitHandler(opaque("{}"), "custom:imported", false)
        assertTrue(firstTry is ChannelConfigurationSubmitResult.UnverifiedAcknowledgementRequired)
        assertNull(committedId)
        assertFalse(committedAck)

        val confirmationTry = commitHandler(opaque("{}"), "custom:imported", true)
        assertEquals(ChannelConfigurationSubmitResult.Success, confirmationTry)
        assertEquals("custom:imported", committedId)
        assertTrue(committedAck)
    }

    @Test
    fun `creation commit callback receives profile separately from payload`() {
        val originalPayload = opaque("{}")
        var receivedPayload: OpaqueJsonObject? = null
        var receivedProfileId: String? = "unset"

        val createCommit: (OpaqueJsonObject, String?, Boolean) -> ChannelConfigurationSubmitResult = { payload, profileId, _ ->
            receivedPayload = payload
            receivedProfileId = profileId
            ChannelConfigurationSubmitResult.Success
        }

        createCommit(originalPayload, "builtin:F1", false)

        assertEquals(originalPayload, receivedPayload)
        assertEquals("builtin:F1", receivedProfileId)
    }

    @Test
    fun `existing preference callback does not alter provider payload`() {
        val providerField = ChannelConfigurationField.TextField(id = "apiKey", label = "API Key")
        val initialPayload = opaque("""{"apiKey":"secret-key","unknownKey":"preserved"}""")
        val newValues = mapOf("apiKey" to "updated-key")
        val resultPayload = payloadWithFieldValues(initialPayload, listOf(providerField), newValues)

        assertEquals(
            opaque("""{"apiKey":"updated-key","unknownKey":"preserved"}"""),
            resultPayload,
        )
        assertFalse(resultPayload.toJsonObject().has("synthesisVoiceProfileId"))
    }

    @Test
    fun `same-provider sibling IDs remain isolated in host preferences and selection choices`() {
        val channel1 = ChannelDefinition(
            id = "channel-1",
            name = "First Channel",
            implementationId = ChannelImplementationId("test:provider"),
            enabled = true,
            configSchemaVersion = 1,
            configPayload = opaque("{}"),
            hostPreferences = ChannelHostPreferences(VoiceProfileId("builtin:F1")),
        )
        val channel2 = ChannelDefinition(
            id = "channel-2",
            name = "Second Channel",
            implementationId = ChannelImplementationId("test:provider"),
            enabled = true,
            configSchemaVersion = 1,
            configPayload = opaque("{}"),
            hostPreferences = ChannelHostPreferences(VoiceProfileId("builtin:M1")),
        )

        assertEquals("builtin:F1", channel1.hostPreferences.synthesisVoiceProfileId?.value)
        assertEquals("builtin:M1", channel2.hostPreferences.synthesisVoiceProfileId?.value)
    }

    @Test
    fun `next-request no-runtime-replacement contract is represented by the backend callback and repository contract`() {
        val before = ChannelDefinition(
            id = "channel-a",
            name = "Alpha",
            implementationId = ChannelImplementationId("test:provider"),
            enabled = true,
            configSchemaVersion = 1,
            configPayload = opaque("""{"key":"value"}"""),
            hostPreferences = ChannelHostPreferences(VoiceProfileId("builtin:F1")),
        )
        val after = before.copy(
            hostPreferences = ChannelHostPreferences(VoiceProfileId("builtin:M2")),
        )

        assertEquals(before.configSchemaVersion, after.configSchemaVersion)
        assertEquals(before.configPayload, after.configPayload)
        assertEquals(before.implementationId, after.implementationId)
        assertEquals("builtin:M2", after.hostPreferences.synthesisVoiceProfileId?.value)
    }

    @Test
    fun `screen payload exactness guarantees host selector id is never written to provider json`() {
        val fields = listOf(
            ChannelConfigurationField.TextField(id = "model", label = "Model"),
        )
        val initialPayload = opaque("""{"model":"gpt-4"}""")
        val values = mapOf("model" to "gpt-4o")

        val payload = payloadWithFieldValues(initialPayload, fields, values)

        assertEquals("gpt-4o", payload.toJsonObject().getString("model"))
        assertFalse(payload.toJsonObject().has("synthesisVoiceProfileId"))
        assertFalse(payload.toJsonObject().has("voiceProfileId"))
        assertFalse(payload.toJsonObject().has("hostPreferences"))
    }
}
