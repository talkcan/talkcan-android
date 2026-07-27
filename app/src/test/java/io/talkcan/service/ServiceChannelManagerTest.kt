package io.talkcan.service

import android.content.SharedPreferences
import io.talkcan.model.ChannelCatalogueCodec
import io.talkcan.model.ChannelCatalogueSnapshot
import io.talkcan.model.ChannelConfigurationField
import io.talkcan.model.ChannelConfigurationMigrationStep
import io.talkcan.model.ChannelConfigurationProvider
import io.talkcan.model.ChannelDefinition
import io.talkcan.model.ChannelImplementationDescriptor
import io.talkcan.model.ChannelImplementationId
import io.talkcan.model.ChannelImplementationProvider
import io.talkcan.model.ChannelImplementationProviderRegistry
import io.talkcan.model.ChannelPreparationTraits
import io.talkcan.model.ChannelPresentationMetadata
import io.talkcan.model.ChannelProviderError
import io.talkcan.model.ChannelProviderRegistrationResult
import io.talkcan.model.ChannelRepository
import io.talkcan.model.ChannelRepositoryError
import io.talkcan.model.ChannelRepositoryMutationResult
import io.talkcan.model.ChannelRuntimeConstructionRequest
import io.talkcan.model.ChannelRuntimeConstructionResult
import io.talkcan.model.OpaqueJsonObject
import io.talkcan.model.ProviderConfigurationResult
import io.talkcan.model.ValidatedChannelConfiguration
import io.talkcan.model.ProviderRevisionFingerprint
import io.talkcan.model.ChannelHostPreferences
import io.talkcan.voice.VoiceProfileAvailability
import io.talkcan.voice.VoiceProfileCatalogue
import io.talkcan.voice.VoiceProfileCompatibility
import io.talkcan.voice.VoiceProfileId
import io.talkcan.voice.VoiceProfileKind
import io.talkcan.voice.VoiceProfileSummary
import io.talkcan.voice.VoiceProfileUnavailableReason
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ServiceChannelManagerTest {
    @Test
    fun `explicit create persists the requested channel payload unchanged`() = withFixture { fixture ->
        val payload = opaque("""{"mode":"explicit","future":{"kept":true}}""")

        val result = fixture.manager.createChannel(TEST_IMPLEMENTATION_ID, "Explicit", payload)

        assertEquals(ChannelRepositoryMutationResult.Success, result)
        assertEquals(
            ChannelDefinition(
                id = CREATED_ID,
                name = "Explicit",
                implementationId = TEST_IMPLEMENTATION_ID,
                enabled = true,
                configSchemaVersion = TestProvider.SCHEMA_VERSION,
                configPayload = payload,
            ),
            fixture.repository.catalogueState.value.definitions.last(),
        )
    }

    @Test
    fun `create without payload persists the provider supplied payload`() = withFixture { fixture ->
        val result = fixture.manager.createChannel(TEST_IMPLEMENTATION_ID, "Provider configured")

        assertEquals(ChannelRepositoryMutationResult.Success, result)
        assertEquals(
            fixture.providerDefaultPayload,
            fixture.repository.catalogueState.value.definitions.last().configPayload,
        )
    }

    @Test
    fun `create for an unregistered provider leaves the catalogue unchanged`() = withFixture(
        managerHasProvider = false,
    ) { fixture ->
        val before = fixture.repository.catalogueState.value

        val result = fixture.manager.createChannel(MISSING_IMPLEMENTATION_ID, "Unavailable", opaque("""{"mode":"new"}"""))

        val failure = assertProviderMigrationFailure(result)
        assertEquals("new", failure.definitionId)
        assertEquals(MISSING_IMPLEMENTATION_ID, failure.error.implementationId)
        assertEquals(before, fixture.repository.catalogueState.value)
    }

    @Test
    fun `configuration update for an unregistered provider leaves the channel unchanged`() = withFixture(
        managerHasProvider = false,
    ) { fixture ->
        val before = fixture.repository.catalogueState.value

        val result = fixture.manager.updateChannelConfiguration(PRIMARY_ID, opaque("""{"mode":"changed"}"""))

        val failure = assertProviderMigrationFailure(result)
        assertEquals(PRIMARY_ID, failure.definitionId)
        assertEquals(TEST_IMPLEMENTATION_ID, failure.error.implementationId)
        assertEquals(before, fixture.repository.catalogueState.value)
    }

    @Test
    fun `rejected configuration update leaves the existing definition unchanged`() = withFixture { fixture ->
        val before = fixture.repository.catalogueState.value

        val result = fixture.manager.updateChannelConfiguration(PRIMARY_ID, opaque("""{"reject":true}"""))

        val failure = assertProviderMigrationFailure(result)
        assertEquals(PRIMARY_ID, failure.definitionId)
        assertTrue(failure.error is ChannelProviderError.InvalidConfiguration)
        assertEquals(before, fixture.repository.catalogueState.value)
    }

    @Test
    fun `valid configuration update triggers onConfigurationCommitted`() {
        val commitEvents = mutableListOf<String>()
        withFixture(
            onConfigurationCommitted = { channelId -> commitEvents += channelId },
        ) { fixture ->
            assertEquals(
                ChannelRepositoryMutationResult.Success,
                fixture.manager.updateChannelConfiguration(PRIMARY_ID, opaque("""{"mode":"updated"}""")),
            )
            assertEquals(listOf(PRIMARY_ID), commitEvents)
        }
    }

    @Test
    fun `two same-provider instances retain independent payloads after create`() {
        var idCounter = 0
        withFixture(
            definitions = listOf(primaryDefinition()),
            newChannelId = { "created-${idCounter++}" },
        ) { fixture ->
            val firstPayload = opaque("""{"mode":"first-instance"}""")
            assertEquals(ChannelRepositoryMutationResult.Success, fixture.manager.createChannel(
                TEST_IMPLEMENTATION_ID, "First", firstPayload,
            ))
            val secondPayload = opaque("""{"mode":"second-instance"}""")
            assertEquals(ChannelRepositoryMutationResult.Success, fixture.manager.createChannel(
                TEST_IMPLEMENTATION_ID, "Second", secondPayload,
            ))

            val defs = fixture.repository.catalogueState.value.definitions
            assertEquals(3, defs.size)
            assertTrue(defs.any { it.configPayload.toJsonString() == firstPayload.toJsonString() })
            assertTrue(defs.any { it.configPayload.toJsonString() == secondPayload.toJsonString() })
        }
    }

    @Test
    fun `update one same-provider instance does not affect sibling payload`() = withFixture(
        definitions = listOf(primaryDefinition(), siblingDefinition()),
    ) { fixture ->
        val siblingPayloadBefore = fixture.repository.catalogueState.value.definitions
            .first { it.id == SIBLING_ID }.configPayload.toJsonString()

        assertEquals(
            ChannelRepositoryMutationResult.Success,
            fixture.manager.updateChannelConfiguration(PRIMARY_ID, opaque("""{"mode":"primary-updated"}""")),
        )

        val defs = fixture.repository.catalogueState.value.definitions
        val primary = defs.first { it.id == PRIMARY_ID }
        val sibling = defs.first { it.id == SIBLING_ID }
        assertEquals("""{"mode":"primary-updated"}""", primary.configPayload.toJsonString())
        assertEquals(siblingPayloadBefore, sibling.configPayload.toJsonString())
    }

    @Test
    fun `update one same-provider instance preserves catalogue order and active selection`() = withFixture(
        definitions = listOf(primaryDefinition(), siblingDefinition(), secondaryDefinition()),
    ) { fixture ->
        assertEquals(
            ChannelRepositoryMutationResult.Success,
            fixture.manager.updateChannelConfiguration(SIBLING_ID, opaque("""{"mode":"sibling-updated"}""")),
        )

        val snapshot = fixture.repository.catalogueState.value
        assertEquals(PRIMARY_ID, snapshot.activeChannelId)
        assertEquals(3, snapshot.definitions.size)
        assertEquals(listOf(PRIMARY_ID, SIBLING_ID, SECONDARY_ID), snapshot.definitions.map { it.id })
    }

    private fun withFixture(
        managerHasProvider: Boolean = true,
        definitions: List<ChannelDefinition> = listOf(primaryDefinition()),
        immediateSelection: (String) -> Unit = {},
        deferredSelection: (String) -> Unit = {},
        log: (String) -> Unit = {},
        newChannelId: () -> String = { CREATED_ID },
        onConfigurationCommitted: (String) -> Unit = {},
        voiceProfileCatalogue: () -> VoiceProfileCatalogue? = { null },
        block: (Fixture) -> Unit,
    ) {
        val provider = TestProvider(TEST_IMPLEMENTATION_ID, PROVIDER_DEFAULT_PAYLOAD)
        val repositoryRegistry = ChannelImplementationProviderRegistry().apply {
            assertEquals(ChannelProviderRegistrationResult.Registered, register(provider))
        }
        val managerRegistry = ChannelImplementationProviderRegistry().apply {
            if (managerHasProvider) {
                assertEquals(ChannelProviderRegistrationResult.Registered, register(provider))
            }
        }
        val catalogueFile = File.createTempFile("service-channel-manager", ".json")
        catalogueFile.writeText(
            ChannelCatalogueCodec.toJson(ChannelCatalogueSnapshot(definitions, PRIMARY_ID)),
        )

        try {
            val repository = ChannelRepository(InMemorySharedPreferences(), catalogueFile, repositoryRegistry)
            val manager = ServiceChannelManager(
                channelRepository = repository,
                providerRegistry = managerRegistry,
                immediateSelection = immediateSelection,
                deferredSelection = deferredSelection,
                newChannelId = newChannelId,
                log = log,
                onConfigurationCommitted = onConfigurationCommitted,
                voiceProfileCatalogue = voiceProfileCatalogue,
            )
            block(Fixture(repository, manager, PROVIDER_DEFAULT_PAYLOAD))
        } finally {
            catalogueFile.delete()
        }
    }

    private fun assertProviderMigrationFailure(
        result: ChannelRepositoryMutationResult,
    ): ChannelRepositoryError.ProviderMigration =
        ((result as? ChannelRepositoryMutationResult.Failure)?.error as? ChannelRepositoryError.ProviderMigration)
            ?: throw AssertionError("Expected provider migration failure, got $result")

    private data class Fixture(
        val repository: ChannelRepository,
        val manager: ServiceChannelManager,
        val providerDefaultPayload: OpaqueJsonObject,
    )

    private class TestProvider(
        implementationId: ChannelImplementationId,
        private val providerDefaultPayload: OpaqueJsonObject,
    ) : ChannelImplementationProvider {
        override val fingerprint = ProviderRevisionFingerprint("test-fingerprint")
        private val configuration = object : ChannelConfigurationProvider {
            override val implementationId = implementationId
            override val currentSchemaVersion = SCHEMA_VERSION

            override fun defaultPayload(): OpaqueJsonObject = providerDefaultPayload

            override fun validate(
                schemaVersion: Int,
                payload: OpaqueJsonObject,
            ): ProviderConfigurationResult = when {
                schemaVersion != currentSchemaVersion -> ProviderConfigurationResult.Failure(
                    ChannelProviderError.UnsupportedSchemaVersion(
                        implementationId,
                        schemaVersion,
                        currentSchemaVersion,
                    ),
                )
                payload.toJsonObject().optBoolean("reject") -> ProviderConfigurationResult.Failure(
                    ChannelProviderError.InvalidConfiguration(
                        implementationId,
                        schemaVersion,
                        "reject is reserved for the failure case",
                    ),
                )
                else -> ProviderConfigurationResult.Success(
                    ValidatedChannelConfiguration(implementationId, schemaVersion, payload),
                )
            }

            override fun migrateStep(
                fromSchemaVersion: Int,
                payload: OpaqueJsonObject,
            ): ChannelConfigurationMigrationStep = ChannelConfigurationMigrationStep.Failure(
                ChannelProviderError.UnsupportedSchemaVersion(
                    implementationId,
                    fromSchemaVersion,
                    currentSchemaVersion,
                ),
            )
        }

        override val descriptor = ChannelImplementationDescriptor(
            implementationId = implementationId,
            presentation = ChannelPresentationMetadata("Test", "TEST", "Unavailable"),
            configuration = configuration,
            configurationFields = listOf(ChannelConfigurationField.TextField("mode", "Mode")),
            requiredCapabilities = emptySet(),
            preparationTraits = ChannelPreparationTraits(supportsRecoverablePreparation = false),
        )

        override suspend fun constructRuntime(
            request: ChannelRuntimeConstructionRequest,
        ): ChannelRuntimeConstructionResult = ChannelRuntimeConstructionResult.Failure(
            ChannelProviderError.RuntimeConstructionFailed(
                descriptor.implementationId,
                "Runtime construction is outside channel management coverage",
            ),
        )

        companion object {
            const val SCHEMA_VERSION = 7
        }
    }

    private class InMemorySharedPreferences : SharedPreferences {
        override fun getAll(): MutableMap<String, *> = mutableMapOf<String, Any?>()
        override fun getString(key: String?, defValue: String?): String? = defValue
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = defValues
        override fun getInt(key: String?, defValue: Int): Int = defValue
        override fun getLong(key: String?, defValue: Long): Long = defValue
        override fun getFloat(key: String?, defValue: Float): Float = defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = defValue
        override fun contains(key: String?): Boolean = false
        override fun edit(): SharedPreferences.Editor = Editor()
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

        private class Editor : SharedPreferences.Editor {
            override fun putString(key: String?, value: String?): SharedPreferences.Editor = this
            override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor = this
            override fun putInt(key: String?, value: Int): SharedPreferences.Editor = this
            override fun putLong(key: String?, value: Long): SharedPreferences.Editor = this
            override fun putFloat(key: String?, value: Float): SharedPreferences.Editor = this
            override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor = this
            override fun remove(key: String?): SharedPreferences.Editor = this
            override fun clear(): SharedPreferences.Editor = this
            override fun commit(): Boolean = true
            override fun apply() = Unit
        }
    }

    // ── Voice preference validation ────────────────────────────────────────

    @Test
    fun `create with voice commits provider payload and hostPreferences atomically`() = withFixture(
        voiceProfileCatalogue = { verifiedCatalogue() },
    ) { fixture ->
        val payload = opaque("""{"mode":"voiced"}""")

        val result = fixture.manager.createChannelWithVoice(
            TEST_IMPLEMENTATION_ID, "Voiced", payload, VOICE_VERIFIED_ID,
        )

        assertEquals(ChannelVoicePreferenceMutation.Committed, result)
        val created = fixture.repository.catalogueState.value.definitions.last()
        assertEquals(CREATED_ID, created.id)
        assertEquals(payload, created.configPayload)
        assertEquals(VoiceProfileId(VOICE_VERIFIED_ID), created.hostPreferences.synthesisVoiceProfileId)
    }

    @Test
    fun `create with null voice commits default empty hostPreferences`() = withFixture(
        voiceProfileCatalogue = { verifiedCatalogue() },
    ) { fixture ->
        val result = fixture.manager.createChannelWithVoice(TEST_IMPLEMENTATION_ID, "NoVoice")

        assertEquals(ChannelVoicePreferenceMutation.Committed, result)
        val created = fixture.repository.catalogueState.value.definitions.last()
        assertNull(created.hostPreferences.synthesisVoiceProfileId)
    }

    @Test
    fun `preference-only update bypasses provider migration and onConfigurationCommitted`() {
        val commitEvents = mutableListOf<String>()
        withFixture(
            voiceProfileCatalogue = { verifiedCatalogue() },
            onConfigurationCommitted = { commitEvents += it },
        ) { fixture ->
            val payloadBefore = fixture.repository.catalogueState.value.definitions
                .first { it.id == PRIMARY_ID }.configPayload

            val result = fixture.manager.updateChannelVoicePreference(PRIMARY_ID, VOICE_VERIFIED_ID)

            assertEquals(ChannelVoicePreferenceMutation.Committed, result)
            val updated = fixture.repository.catalogueState.value.definitions.first { it.id == PRIMARY_ID }
            assertEquals(VoiceProfileId(VOICE_VERIFIED_ID), updated.hostPreferences.synthesisVoiceProfileId)
            assertEquals(payloadBefore, updated.configPayload)
            assertTrue(commitEvents.isEmpty())
        }
    }

    @Test
    fun `preference update to null clears assignment without validation`() = withFixture(
        definitions = listOf(primaryDefinition().copy(
            hostPreferences = ChannelHostPreferences(VoiceProfileId(VOICE_VERIFIED_ID)),
        )),
        voiceProfileCatalogue = { verifiedCatalogue() },
    ) { fixture ->
        val result = fixture.manager.updateChannelVoicePreference(PRIMARY_ID, null)

        assertEquals(ChannelVoicePreferenceMutation.Committed, result)
        assertNull(
            fixture.repository.catalogueState.value.definitions
                .first { it.id == PRIMARY_ID }.hostPreferences.synthesisVoiceProfileId,
        )
    }

    @Test
    fun `first unverified assignment without acknowledgement is refused`() = withFixture(
        voiceProfileCatalogue = { unverifiedCatalogue() },
    ) { fixture ->
        val before = fixture.repository.catalogueState.value

        val result = fixture.manager.updateChannelVoicePreference(PRIMARY_ID, VOICE_UNVERIFIED_ID)

        val refused = result as ChannelVoicePreferenceMutation.VoiceRefused
        val failure = refused.failure as ChannelVoicePreferenceFailure.UnverifiedRequiresAcknowledgement
        assertEquals(VOICE_UNVERIFIED_ID, failure.profileId)
        assertEquals("Untagged Import", failure.displayName)
        assertEquals(before, fixture.repository.catalogueState.value)
    }

    @Test
    fun `first unverified assignment with acknowledgement commits`() = withFixture(
        voiceProfileCatalogue = { unverifiedCatalogue() },
    ) { fixture ->
        val result = fixture.manager.updateChannelVoicePreference(
            PRIMARY_ID, VOICE_UNVERIFIED_ID, acknowledgeUnverified = true,
        )

        assertEquals(ChannelVoicePreferenceMutation.Committed, result)
        assertEquals(
            VoiceProfileId(VOICE_UNVERIFIED_ID),
            fixture.repository.catalogueState.value.definitions
                .first { it.id == PRIMARY_ID }.hostPreferences.synthesisVoiceProfileId,
        )
    }

    @Test
    fun `unavailable profile assignment is rejected and catalogue unchanged`() = withFixture(
        voiceProfileCatalogue = { unavailableCatalogue() },
    ) { fixture ->
        val before = fixture.repository.catalogueState.value

        val result = fixture.manager.updateChannelVoicePreference(PRIMARY_ID, VOICE_UNAVAILABLE_ID)

        val refused = result as ChannelVoicePreferenceMutation.VoiceRefused
        assertTrue(refused.failure is ChannelVoicePreferenceFailure.UnavailableProfile)
        assertEquals(before, fixture.repository.catalogueState.value)
    }

    @Test
    fun `unknown profile assignment is rejected`() = withFixture(
        voiceProfileCatalogue = { verifiedCatalogue() },
    ) { fixture ->
        val result = fixture.manager.updateChannelVoicePreference(PRIMARY_ID, "nonexistent-profile")

        val refused = result as ChannelVoicePreferenceMutation.VoiceRefused
        assertTrue(refused.failure is ChannelVoicePreferenceFailure.UnknownProfile)
    }

    @Test
    fun `incompatible profile assignment is rejected`() = withFixture(
        voiceProfileCatalogue = { incompatibleCatalogue() },
    ) { fixture ->
        val result = fixture.manager.updateChannelVoicePreference(PRIMARY_ID, VOICE_INCOMPATIBLE_ID)

        val refused = result as ChannelVoicePreferenceMutation.VoiceRefused
        assertTrue(refused.failure is ChannelVoicePreferenceFailure.IncompatibleProfile)
    }

    @Test
    fun `persisted unavailable selection remains readable at load`() {
        val definitionWithUnavailable = primaryDefinition().copy(
            hostPreferences = ChannelHostPreferences(VoiceProfileId(VOICE_UNAVAILABLE_ID)),
        )
        withFixture(
            definitions = listOf(definitionWithUnavailable),
            voiceProfileCatalogue = { unavailableCatalogue() },
        ) { fixture ->
            val loaded = fixture.repository.catalogueState.value.definitions.first { it.id == PRIMARY_ID }
            assertEquals(
                VoiceProfileId(VOICE_UNAVAILABLE_ID),
                loaded.hostPreferences.synthesisVoiceProfileId,
            )
        }
    }

    @Test
    fun `create with unavailable voice is refused and catalogue unchanged`() = withFixture(
        voiceProfileCatalogue = { unavailableCatalogue() },
    ) { fixture ->
        val before = fixture.repository.catalogueState.value

        val result = fixture.manager.createChannelWithVoice(
            TEST_IMPLEMENTATION_ID, "BadVoice", voiceProfileId = VOICE_UNAVAILABLE_ID,
        )

        assertTrue(result is ChannelVoicePreferenceMutation.VoiceRefused)
        assertEquals(before, fixture.repository.catalogueState.value)
    }

    private companion object {

        const val VOICE_VERIFIED_ID = "builtin:F1"
        const val VOICE_UNVERIFIED_ID = "custom-untagged"
        const val VOICE_UNAVAILABLE_ID = "custom-missing"
        const val VOICE_INCOMPATIBLE_ID = "custom-incompatible"

        fun voiceSummary(
            id: String,
            name: String,
            availability: VoiceProfileAvailability = VoiceProfileAvailability.Available,
            compatibility: VoiceProfileCompatibility = VoiceProfileCompatibility.VERIFIED,
        ): VoiceProfileSummary = VoiceProfileSummary(
            id = VoiceProfileId(id),
            displayName = name,
            kind = if (id.startsWith("builtin:")) VoiceProfileKind.BUILT_IN else VoiceProfileKind.IMPORTED,
            availability = availability,
            compatibility = compatibility,
            readOnly = id.startsWith("builtin:"),
        )

        fun verifiedCatalogue(): VoiceProfileCatalogue = VoiceProfileCatalogue(
            builtIn = listOf(voiceSummary(VOICE_VERIFIED_ID, "F1")),
            custom = emptyList(),
        )

        fun unverifiedCatalogue(): VoiceProfileCatalogue = VoiceProfileCatalogue(
            builtIn = emptyList(),
            custom = listOf(voiceSummary(VOICE_UNVERIFIED_ID, "Untagged Import", compatibility = VoiceProfileCompatibility.UNVERIFIED)),
        )

        fun unavailableCatalogue(): VoiceProfileCatalogue = VoiceProfileCatalogue(
            builtIn = emptyList(),
            custom = listOf(voiceSummary(
                VOICE_UNAVAILABLE_ID, "Missing Profile",
                availability = VoiceProfileAvailability.Unavailable(
                    VoiceProfileUnavailableReason.MISSING_FILE, "Document is missing",
                ),
            )),
        )

        fun incompatibleCatalogue(): VoiceProfileCatalogue = VoiceProfileCatalogue(
            builtIn = emptyList(),
            custom = listOf(voiceSummary(
                VOICE_INCOMPATIBLE_ID, "Old Model Profile",
                availability = VoiceProfileAvailability.Unavailable(
                    VoiceProfileUnavailableReason.INCOMPATIBLE_MODEL, "Model mismatch",
                ),
                compatibility = VoiceProfileCompatibility.INCOMPATIBLE,
            )),
        )

        val TEST_IMPLEMENTATION_ID = ChannelImplementationId("test:managed")
        val MISSING_IMPLEMENTATION_ID = ChannelImplementationId("test:missing")
        const val PRIMARY_ID = "primary"
        const val SECONDARY_ID = "secondary"
        const val SIBLING_ID = "sibling"
        const val CREATED_ID = "created"
        val PROVIDER_DEFAULT_PAYLOAD = opaque("""{"mode":"provider-default"}""")

        fun primaryDefinition(): ChannelDefinition = ChannelDefinition(
            id = PRIMARY_ID,
            name = "Primary",
            implementationId = TEST_IMPLEMENTATION_ID,
            enabled = true,
            configSchemaVersion = TestProvider.SCHEMA_VERSION,
            configPayload = opaque("""{"mode":"primary"}"""),
        )

        fun secondaryDefinition(): ChannelDefinition = ChannelDefinition(
            id = SECONDARY_ID,
            name = "Secondary",
            implementationId = TEST_IMPLEMENTATION_ID,
            enabled = true,
            configSchemaVersion = TestProvider.SCHEMA_VERSION,
            configPayload = opaque("""{"mode":"secondary"}"""),
        )

        fun siblingDefinition(): ChannelDefinition = ChannelDefinition(
            id = SIBLING_ID,
            name = "Sibling",
            implementationId = TEST_IMPLEMENTATION_ID,
            enabled = true,
            configSchemaVersion = TestProvider.SCHEMA_VERSION,
            configPayload = opaque("""{"mode":"sibling"}"""),
        )

        fun opaque(encoded: String): OpaqueJsonObject = OpaqueJsonObject.parse(encoded).getOrThrow()
    }
}
