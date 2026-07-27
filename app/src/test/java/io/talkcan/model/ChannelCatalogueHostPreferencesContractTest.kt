package io.talkcan.model

import android.content.SharedPreferences
import io.talkcan.voice.VoiceProfileId
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract coverage for catalogue document v3 host preferences (tasks 3.1-3.5).
 *
 * Host preferences are owned by the catalogue, never by a channel provider: they must survive
 * v1/v2 migration, rename/move/remove, provider configuration migration, and unavailable
 * providers, while never entering the opaque provider payload or provider migration/validation.
 */
class ChannelCatalogueHostPreferencesContractTest {

    private val testImplId = ChannelImplementationId("test:provider")
    private val otherImplId = ChannelImplementationId("test:other")

    // ──────────────────────────────────────────────────────────────
    //  3.2 — v3 is canonical; v1/v2 upgrade to empty preferences.
    // ──────────────────────────────────────────────────────────────

    @Test
    fun `v3 round-trip preserves synthesis voice profile id`() {
        val preferences = ChannelHostPreferences(VoiceProfileId("custom:profile-1"))
        val definition = ChannelDefinition(
            id = "a",
            name = "Alpha",
            implementationId = testImplId,
            enabled = true,
            configSchemaVersion = 1,
            configPayload = opaque("""{"mode":"alpha"}"""),
            hostPreferences = preferences,
        )
        val original = ChannelCatalogueSnapshot(listOf(definition), "a")

        val json = ChannelCatalogueCodec.toJson(original)
        val document = (ChannelCatalogueCodec.decode(json) as ChannelCatalogueDecodeResult.Success).document

        assertEquals(ChannelCatalogueCodec.CURRENT_DOCUMENT_VERSION, document.sourceDocumentVersion)
        assertEquals(3, document.sourceDocumentVersion)
        assertEquals(preferences, document.snapshot.definitions.single().hostPreferences)
        assertEquals(
            VoiceProfileId("custom:profile-1"),
            document.snapshot.definitions.single().hostPreferences.synthesisVoiceProfileId,
        )
    }

    @Test
    fun `v3 round-trip with null profile id encodes empty preferences object`() {
        val definition = ChannelDefinition(
            id = "a",
            name = "Alpha",
            implementationId = testImplId,
            enabled = true,
            configSchemaVersion = 1,
            configPayload = opaque("""{"mode":"alpha"}"""),
        )
        val original = ChannelCatalogueSnapshot(listOf(definition), "a")

        val decoded = (ChannelCatalogueCodec.decode(ChannelCatalogueCodec.toJson(original))
            as ChannelCatalogueDecodeResult.Success).document.snapshot

        assertNull(decoded.definitions.single().hostPreferences.synthesisVoiceProfileId)
        assertEquals(ChannelHostPreferences(), decoded.definitions.single().hostPreferences)
    }

    @Test
    fun `v2 document migrates to empty preferences preserving order active provider schema and payload`() {
        val v2 = """
            {
              "version": 2,
              "activeChannelId": "b",
              "definitions": [
                {"id":"a","name":"Alpha","implementationId":"test:provider","enabled":true,"configSchemaVersion":1,"config":{"mode":"alpha","keep":1}},
                {"id":"b","name":"Beta","implementationId":"test:other","enabled":false,"configSchemaVersion":2,"config":{"mode":"beta"}}
              ]
            }
        """.trimIndent()

        val document = (ChannelCatalogueCodec.decode(v2) as ChannelCatalogueDecodeResult.Success).document

        assertEquals(2, document.sourceDocumentVersion)
        val definitions = document.snapshot.definitions
        assertEquals(listOf("a", "b"), definitions.map { it.id })
        assertEquals("b", document.snapshot.activeChannelId)
        assertEquals("test:provider", definitions[0].implementationId.value)
        assertEquals("test:other", definitions[1].implementationId.value)
        assertEquals(1, definitions[0].configSchemaVersion)
        assertEquals(2, definitions[1].configSchemaVersion)
        assertTrue(definitions[0].enabled)
        assertFalse(definitions[1].enabled)
        assertEquals(opaque("""{"mode":"alpha","keep":1}""").toJsonString(), definitions[0].configPayload.toJsonString())
        assertEquals(opaque("""{"mode":"beta"}""").toJsonString(), definitions[1].configPayload.toJsonString())
        // v2 carries no host preferences: every definition upgrades to empty preferences.
        definitions.forEach { assertEquals(ChannelHostPreferences(), it.hostPreferences) }
    }

    @Test
    fun `v1 document migrates kind discriminators to provider ids with empty preferences`() {
        val v1 = """
            {
              "version": 1,
              "activeChannelId": "j",
              "definitions": [
                {"id":"j","name":"Journal","kind":"JOURNAL","enabled":true,"configSchemaVersion":1,"config":{"base":"/x"}},
                {"id":"k","name":"Keyboard","kind":"KEYBOARD","enabled":true,"configSchemaVersion":1,"config":{"layout":"us"}}
              ]
            }
        """.trimIndent()

        val document = (ChannelCatalogueCodec.decode(v1) as ChannelCatalogueDecodeResult.Success).document

        assertEquals(1, document.sourceDocumentVersion)
        val definitions = document.snapshot.definitions
        assertEquals(listOf("j", "k"), definitions.map { it.id })
        assertEquals("j", document.snapshot.activeChannelId)
        assertEquals("builtin:journal", definitions[0].implementationId.value)
        assertEquals("builtin:keyboard", definitions[1].implementationId.value)
        assertEquals(opaque("""{"base":"/x"}""").toJsonString(), definitions[0].configPayload.toJsonString())
        assertEquals(opaque("""{"layout":"us"}""").toJsonString(), definitions[1].configPayload.toJsonString())
        definitions.forEach { assertEquals(ChannelHostPreferences(), it.hostPreferences) }
    }

    @Test
    fun `unsupported document versions are rejected`() {
        assertTrue(
            ChannelCatalogueCodec.decode("""{"version":4,"definitions":[]}""")
                is ChannelCatalogueDecodeResult.Failure,
        )
        assertTrue(
            ChannelCatalogueCodec.decode("""{"version":0,"definitions":[]}""")
                is ChannelCatalogueDecodeResult.Failure,
        )
    }

    // ──────────────────────────────────────────────────────────────
    //  3.2 — malformed host preferences are rejected.
    // ──────────────────────────────────────────────────────────────

    @Test
    fun `malformed host preferences are rejected`() {
        fun v3With(hostPreferencesFragment: String) = """
            {"version":3,"activeChannelId":"a","definitions":[
              {"id":"a","name":"A","implementationId":"test:provider","enabled":true,"configSchemaVersion":1,"config":{},"hostPreferences":$hostPreferencesFragment}
            ]}
        """.trimIndent()

        // synthesisVoiceProfileId must be a string, not a number.
        assertTrue(ChannelCatalogueCodec.decode(v3With("""{"synthesisVoiceProfileId":42}"""))
            is ChannelCatalogueDecodeResult.Failure)
        // hostPreferences must be an object, not a string.
        assertTrue(ChannelCatalogueCodec.decode(v3With(""""nope""""))
            is ChannelCatalogueDecodeResult.Failure)
        // A blank profile ID violates VoiceProfileId identity bounds.
        assertTrue(ChannelCatalogueCodec.decode(v3With("""{"synthesisVoiceProfileId":""}"""))
            is ChannelCatalogueDecodeResult.Failure)
        // A profile ID exceeding the 64-byte bound is rejected.
        assertTrue(ChannelCatalogueCodec.decode(v3With("""{"synthesisVoiceProfileId":"${"x".repeat(65)}"}"""))
            is ChannelCatalogueDecodeResult.Failure)
    }

    @Test
    fun `absent or empty host preferences decode as empty`() {
        val absent = """
            {"version":3,"activeChannelId":"a","definitions":[
              {"id":"a","name":"A","implementationId":"test:provider","enabled":true,"configSchemaVersion":1,"config":{}}
            ]}
        """.trimIndent()
        val empty = """
            {"version":3,"activeChannelId":"a","definitions":[
              {"id":"a","name":"A","implementationId":"test:provider","enabled":true,"configSchemaVersion":1,"config":{},"hostPreferences":{}}
            ]}
        """.trimIndent()
        val explicitNull = """
            {"version":3,"activeChannelId":"a","definitions":[
              {"id":"a","name":"A","implementationId":"test:provider","enabled":true,"configSchemaVersion":1,"config":{},"hostPreferences":{"synthesisVoiceProfileId":null}}
            ]}
        """.trimIndent()

        listOf(absent, empty, explicitNull).forEach { json ->
            val decoded = (ChannelCatalogueCodec.decode(json) as ChannelCatalogueDecodeResult.Success).document.snapshot
            assertEquals(ChannelHostPreferences(), decoded.definitions.single().hostPreferences)
        }
    }

    // ──────────────────────────────────────────────────────────────
    //  3.3 — preferences survive mutations and sibling isolation.
    // ──────────────────────────────────────────────────────────────

    @Test
    fun `rename and move preserve host preferences`() {
        val preferences = ChannelHostPreferences(VoiceProfileId("custom:renamed"))
        val definitions = listOf(
            ChannelDefinition("a", "Alpha", testImplId, true, 1, opaque("""{"m":"a"}"""), preferences),
            ChannelDefinition("b", "Beta", testImplId, true, 1, opaque("""{"m":"b"}""")),
        )
        val snapshot = ChannelCatalogueSnapshot(definitions, "a")

        val renamed = (snapshot.updateChannel("a") { it.copy(name = "Renamed") }
            as ChannelCatalogueMutationResult.Success).snapshot
        assertEquals(preferences, renamed.definitions.single { it.id == "a" }.hostPreferences)
        assertEquals("Renamed", renamed.definitions.single { it.id == "a" }.name)

        val moved = (renamed.moveChannel("a", 1) as ChannelCatalogueMutationResult.Success).snapshot
        assertEquals(preferences, moved.definitions.single { it.id == "a" }.hostPreferences)
    }

    @Test
    fun `remove preserves remaining siblings preferences`() {
        val preferencesA = ChannelHostPreferences(VoiceProfileId("custom:a"))
        val preferencesB = ChannelHostPreferences(VoiceProfileId("custom:b"))
        val snapshot = ChannelCatalogueSnapshot(
            listOf(
                ChannelDefinition("a", "Alpha", testImplId, true, 1, opaque("""{"m":"a"}"""), preferencesA),
                ChannelDefinition("b", "Beta", testImplId, true, 1, opaque("""{"m":"b"}"""), preferencesB),
            ),
            "a",
        )

        val updated = (snapshot.removeChannel("a") as ChannelCatalogueMutationResult.Success).snapshot
        assertEquals(preferencesB, updated.definitions.single().hostPreferences)
    }

    @Test
    fun `two same-provider instances retain isolated preferences after updating sibling`() {
        val first = ChannelHostPreferences(VoiceProfileId("custom:first"))
        val second = ChannelHostPreferences(VoiceProfileId("custom:second"))
        val snapshot = ChannelCatalogueSnapshot(
            listOf(
                ChannelDefinition("one", "One", testImplId, true, 1, opaque("""{"i":"first"}"""), first),
                ChannelDefinition("two", "Two", testImplId, true, 1, opaque("""{"i":"second"}"""), second),
            ),
            "one",
        )

        val updated = (snapshot.updateChannel("one") {
            it.copy(hostPreferences = ChannelHostPreferences(VoiceProfileId("custom:changed")))
        } as ChannelCatalogueMutationResult.Success).snapshot

        assertEquals(
            VoiceProfileId("custom:changed"),
            updated.definitions[0].hostPreferences.synthesisVoiceProfileId,
        )
        // Sibling preference is untouched.
        assertEquals(second, updated.definitions[1].hostPreferences)
    }

    @Test
    fun `provider payload is isolated from preference changes and vice versa`() {
        val payload = opaque("""{"provider":"payload","nested":{"keep":true}}""")
        val preferences = ChannelHostPreferences(VoiceProfileId("custom:iso"))
        val definition = ChannelDefinition("a", "A", testImplId, true, 1, payload, preferences)
        val snapshot = ChannelCatalogueSnapshot(listOf(definition), "a")

        // Changing preferences leaves the provider payload byte-for-byte identical.
        val prefsChanged = (snapshot.updateChannel("a") {
            it.copy(hostPreferences = ChannelHostPreferences(VoiceProfileId("custom:other")))
        } as ChannelCatalogueMutationResult.Success).snapshot
        assertEquals(payload.toJsonString(), prefsChanged.definitions.single().configPayload.toJsonString())

        // Changing the provider payload leaves preferences identical.
        val payloadChanged = (snapshot.updateChannel("a") {
            it.copy(configPayload = opaque("""{"provider":"replaced"}"""))
        } as ChannelCatalogueMutationResult.Success).snapshot
        assertEquals(preferences, payloadChanged.definitions.single().hostPreferences)
    }

    // ──────────────────────────────────────────────────────────────
    //  3.3 — provider migration and unavailable providers.
    // ──────────────────────────────────────────────────────────────

    @Test
    fun `unavailable provider retention preserves preferences byte-for-byte`() {
        val preferences = ChannelHostPreferences(VoiceProfileId("custom:retained"))
        val definition = ChannelDefinition(
            "a", "A", ChannelImplementationId("missing:provider"), true, 1,
            opaque("""{"mode":"ECHO"}"""), preferences,
        )
        val snapshot = ChannelCatalogueSnapshot(listOf(definition), "a")

        val migration = ChannelCatalogueProviderMigrator.migrate(snapshot, resolverFor())
        val success = migration as ChannelCatalogueProviderMigrationResult.Success

        assertFalse(success.changed)
        assertEquals(definition, success.snapshot.definitions.single())
        assertEquals(preferences, success.snapshot.definitions.single().hostPreferences)
    }

    @Test
    fun `provider configuration migration preserves host preferences while rewriting payload`() {
        val preferences = ChannelHostPreferences(VoiceProfileId("custom:survivor"))
        val definition = ChannelDefinition("a", "A", testImplId, true, 1, opaque("""{"legacy":true}"""), preferences)
        val snapshot = ChannelCatalogueSnapshot(listOf(definition), "a")

        val migration = ChannelCatalogueProviderMigrator.migrate(snapshot, resolverFor(listOf(testImplId), rewriting = true))
        val success = migration as ChannelCatalogueProviderMigrationResult.Success

        assertTrue(success.changed)
        val migrated = success.snapshot.definitions.single()
        // The provider rewrote the opaque configuration...
        assertEquals(opaque("""{"migratedByProvider":true}""").toJsonString(), migrated.configPayload.toJsonString())
        // ...but the host preferences survived untouched.
        assertEquals(preferences, migrated.hostPreferences)
    }

    // ──────────────────────────────────────────────────────────────
    //  3.4 — atomic creation and preference-only repository update.
    // ──────────────────────────────────────────────────────────────

    @Test
    fun `repository creates channels atomically with preferences and removes preserving siblings`() {
        val repository = ChannelRepository(mutablePrefs(), tempFile(), resolverFor(listOf(testImplId)))

        val preferencesA = ChannelHostPreferences(VoiceProfileId("custom:a"))
        val createA = repository.addChannel(
            ChannelDefinition("a", "Alpha", testImplId, true, 1, opaque("""{"m":"a"}"""), preferencesA),
        )
        assertEquals(ChannelRepositoryMutationResult.Success, createA)

        val preferencesB = ChannelHostPreferences(VoiceProfileId("custom:b"))
        val createB = repository.addChannel(
            ChannelDefinition("b", "Beta", testImplId, true, 1, opaque("""{"m":"b"}"""), preferencesB),
        )
        assertEquals(ChannelRepositoryMutationResult.Success, createB)

        val afterCreate = repository.catalogueState.value
        assertEquals(preferencesA, afterCreate.definitions.single { it.id == "a" }.hostPreferences)
        assertEquals(preferencesB, afterCreate.definitions.single { it.id == "b" }.hostPreferences)

        assertEquals(ChannelRepositoryMutationResult.Success, repository.removeChannel("a"))
        val afterRemove = repository.catalogueState.value
        assertEquals(1, afterRemove.definitions.size)
        assertEquals(preferencesB, afterRemove.definitions.single().hostPreferences)
    }

    @Test
    fun `preference-only update bypasses provider migration and preserves payload byte-exact`() {
        val preferences = ChannelHostPreferences(VoiceProfileId("custom:before"))
        val payload = opaque("""{"provider":"payload","keep":42}""")
        val seeded = ChannelCatalogueSnapshot(
            listOf(ChannelDefinition("a", "Alpha", testImplId, true, 1, payload, preferences)),
            "a",
        )
        val file = tempFile()
        file.writeText(ChannelCatalogueCodec.toJson(seeded))

        // The provider actively rejects configuration validation; any provider migration would fail.
        val repository = ChannelRepository(mutablePrefs(), file, failingResolverFor(listOf(testImplId)))
        assertEquals(preferences, repository.catalogueState.value.definitions.single().hostPreferences)

        // The general update path runs provider migration and therefore fails against this provider.
        val generalUpdate = repository.updateChannel("a") { it.copy(name = "Renamed") }
        assertTrue(
            "general updateChannel must run provider migration and fail",
            generalUpdate is ChannelRepositoryMutationResult.Failure,
        )

        // The preference-only path bypasses provider migration entirely and succeeds.
        val newPreferences = ChannelHostPreferences(VoiceProfileId("custom:after"))
        val preferenceUpdate = repository.updateChannelHostPreferences("a", newPreferences)
        assertEquals(ChannelRepositoryMutationResult.Success, preferenceUpdate)

        val updated = repository.catalogueState.value.definitions.single()
        assertEquals(newPreferences, updated.hostPreferences)
        // The opaque provider payload and schema version are preserved byte-for-byte.
        assertEquals(payload.toJsonString(), updated.configPayload.toJsonString())
        assertEquals(1, updated.configSchemaVersion)
        // The definition name was not altered by the preference-only update.
        assertEquals("Alpha", updated.name)
    }

    @Test
    fun `preference-only update of one sibling leaves the other untouched`() {
        val preferencesA = ChannelHostPreferences(VoiceProfileId("custom:a"))
        val preferencesB = ChannelHostPreferences(VoiceProfileId("custom:b"))
        val seeded = ChannelCatalogueSnapshot(
            listOf(
                ChannelDefinition("a", "Alpha", testImplId, true, 1, opaque("""{"m":"a"}"""), preferencesA),
                ChannelDefinition("b", "Beta", testImplId, true, 1, opaque("""{"m":"b"}"""), preferencesB),
            ),
            "a",
        )
        val file = tempFile()
        file.writeText(ChannelCatalogueCodec.toJson(seeded))
        val repository = ChannelRepository(mutablePrefs(), file, resolverFor(listOf(testImplId)))

        assertEquals(
            ChannelRepositoryMutationResult.Success,
            repository.updateChannelHostPreferences("a", ChannelHostPreferences(VoiceProfileId("custom:changed"))),
        )

        val definitions = repository.catalogueState.value.definitions
        assertEquals(VoiceProfileId("custom:changed"), definitions.single { it.id == "a" }.hostPreferences.synthesisVoiceProfileId)
        assertEquals(preferencesB, definitions.single { it.id == "b" }.hostPreferences)
    }

    @Test
    fun `preference-only update of unknown channel fails typed`() {
        val repository = ChannelRepository(mutablePrefs(), tempFile(), resolverFor(listOf(testImplId)))
        repository.addChannel(ChannelDefinition("a", "Alpha", testImplId, true, 1, opaque("""{"m":"a"}""")))

        val result = repository.updateChannelHostPreferences("missing", ChannelHostPreferences(VoiceProfileId("custom:x")))
        assertTrue(result is ChannelRepositoryMutationResult.Failure)
    }

    // ──────────────────────────────────────────────────────────────
    //  3.2 — a loaded v2 catalogue is re-persisted as canonical v3.
    // ──────────────────────────────────────────────────────────────

    @Test
    fun `loading a v2 catalogue re-persists canonical v3 with empty preferences`() {
        val v2 = """
            {
              "version": 2,
              "activeChannelId": "a",
              "definitions": [
                {"id":"a","name":"Alpha","implementationId":"test:provider","enabled":true,"configSchemaVersion":1,"config":{"mode":"alpha"}}
              ]
            }
        """.trimIndent()
        val file = tempFile()
        file.writeText(v2)

        ChannelRepository(mutablePrefs(), file, resolverFor())

        val repersisted = (ChannelCatalogueCodec.decode(file.readText()) as ChannelCatalogueDecodeResult.Success).document
        assertEquals(3, repersisted.sourceDocumentVersion)
        assertEquals(ChannelHostPreferences(), repersisted.snapshot.definitions.single().hostPreferences)
        assertEquals(opaque("""{"mode":"alpha"}""").toJsonString(), repersisted.snapshot.definitions.single().configPayload.toJsonString())
    }

    // ──────────────────────────────────────────────────────────────
    //  Helpers
    // ──────────────────────────────────────────────────────────────

    private fun opaque(encoded: String): OpaqueJsonObject = OpaqueJsonObject.parse(encoded).getOrThrow()

    /**
     * Resolver returning an available stub descriptor for [available] implementation IDs and a
     * typed Missing resolution for everything else. When [rewriting] is set, the stub provider
     * rewrites the opaque payload on validation to simulate a real provider configuration migration.
     */
    private fun resolverFor(
        available: List<ChannelImplementationId> = emptyList(),
        rewriting: Boolean = false,
    ): ChannelImplementationDescriptorResolver {
        val ids = available.toSet()
        return object : ChannelImplementationDescriptorResolver {
            override fun resolveDescriptor(implementationId: ChannelImplementationId): ChannelDescriptorResolution =
                if (implementationId in ids) {
                    ChannelDescriptorResolution.Available(stubDescriptor(implementationId, rewriting))
                } else {
                    ChannelDescriptorResolution.Missing(ChannelProviderError.MissingProvider(implementationId))
                }
        }
    }

    /** Resolver whose available provider always fails configuration validation. */
    private fun failingResolverFor(available: List<ChannelImplementationId>): ChannelImplementationDescriptorResolver {
        val ids = available.toSet()
        return object : ChannelImplementationDescriptorResolver {
            override fun resolveDescriptor(implementationId: ChannelImplementationId): ChannelDescriptorResolution =
                if (implementationId in ids) {
                    ChannelDescriptorResolution.Available(failingDescriptor(implementationId))
                } else {
                    ChannelDescriptorResolution.Missing(ChannelProviderError.MissingProvider(implementationId))
                }
        }
    }

    private fun stubDescriptor(implId: ChannelImplementationId, rewriting: Boolean): ChannelImplementationDescriptor =
        ChannelImplementationDescriptor(
            implementationId = implId,
            presentation = ChannelPresentationMetadata("Stub", "STUB", "Stub unavailable"),
            configuration = object : ChannelConfigurationProvider {
                override val implementationId = implId
                override val currentSchemaVersion = 1
                override fun defaultPayload(): OpaqueJsonObject = opaque("""{"default":true}""")
                override fun validate(schemaVersion: Int, payload: OpaqueJsonObject): ProviderConfigurationResult {
                    val validated = if (rewriting) opaque("""{"migratedByProvider":true}""") else payload
                    return ProviderConfigurationResult.Success(ValidatedChannelConfiguration(implId, schemaVersion, validated))
                }
                override fun migrateStep(fromSchemaVersion: Int, payload: OpaqueJsonObject): ChannelConfigurationMigrationStep =
                    ChannelConfigurationMigrationStep.Failure(
                        ChannelProviderError.UnsupportedSchemaVersion(implId, fromSchemaVersion, 1),
                    )
            },
            configurationFields = emptyList(),
            requiredCapabilities = emptySet(),
            preparationTraits = ChannelPreparationTraits(supportsRecoverablePreparation = false),
        )

    private fun failingDescriptor(implId: ChannelImplementationId): ChannelImplementationDescriptor =
        ChannelImplementationDescriptor(
            implementationId = implId,
            presentation = ChannelPresentationMetadata("Failing", "FAIL", "Always fails"),
            configuration = object : ChannelConfigurationProvider {
                override val implementationId = implId
                override val currentSchemaVersion = 1
                override fun defaultPayload(): OpaqueJsonObject = opaque("""{"default":true}""")
                override fun validate(schemaVersion: Int, payload: OpaqueJsonObject): ProviderConfigurationResult =
                    ProviderConfigurationResult.Failure(
                        ChannelProviderError.UnsupportedSchemaVersion(implId, schemaVersion, 1),
                    )
                override fun migrateStep(fromSchemaVersion: Int, payload: OpaqueJsonObject): ChannelConfigurationMigrationStep =
                    ChannelConfigurationMigrationStep.Failure(
                        ChannelProviderError.UnsupportedSchemaVersion(implId, fromSchemaVersion, 1),
                    )
            },
            configurationFields = emptyList(),
            requiredCapabilities = emptySet(),
            preparationTraits = ChannelPreparationTraits(supportsRecoverablePreparation = false),
        )

    private fun tempFile(): File {
        val file = File.createTempFile("host-preferences-contract-test", ".json")
        file.delete()
        file.deleteOnExit()
        return file
    }

    private fun mutablePrefs(): MutableSharedPreferences = MutableSharedPreferences()

    /** Minimal mutable in-memory [SharedPreferences]. */
    private class MutableSharedPreferences : SharedPreferences {
        private val values = mutableMapOf<String, Any?>()

        override fun getAll(): MutableMap<String, *> = values.toMutableMap()
        override fun getString(key: String?, defValue: String?): String? = values[key] as? String ?: defValue
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
            values[key] as? MutableSet<String> ?: defValues
        override fun getInt(key: String?, defValue: Int): Int = values[key] as? Int ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = values[key] as? Long ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = values[key] as? Float ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = values[key] as? Boolean ?: defValue
        override fun contains(key: String?): Boolean = values.containsKey(key)
        override fun edit(): SharedPreferences.Editor = Editor()
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

        private inner class Editor : SharedPreferences.Editor {
            private val pending = mutableMapOf<String, Any?>()
            private val removals = mutableSetOf<String>()
            private var clearRequested = false

            override fun putString(key: String?, value: String?): SharedPreferences.Editor = apply { pending[key!!] = value }
            override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor = apply { pending[key!!] = values }
            override fun putInt(key: String?, value: Int): SharedPreferences.Editor = apply { pending[key!!] = value }
            override fun putLong(key: String?, value: Long): SharedPreferences.Editor = apply { pending[key!!] = value }
            override fun putFloat(key: String?, value: Float): SharedPreferences.Editor = apply { pending[key!!] = value }
            override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor = apply { pending[key!!] = value }
            override fun remove(key: String?): SharedPreferences.Editor = apply { removals += key!! }
            override fun clear(): SharedPreferences.Editor = apply { clearRequested = true }
            override fun commit(): Boolean { apply(); return true }
            override fun apply() {
                if (clearRequested) values.clear()
                removals.forEach(values::remove)
                values.putAll(pending)
            }
        }
    }
}
