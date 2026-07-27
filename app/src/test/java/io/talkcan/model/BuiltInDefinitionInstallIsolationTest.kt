package io.talkcan.model

import android.content.SharedPreferences
import io.talkcan.dependency.ArtifactDigest
import io.talkcan.dependency.GitHubRepositoryIdentity
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract coverage for the removal of the built-in channel providers: installing an external
 * Lua package provider and adding a channel instance of it never rebinds, mutates, removes, or
 * copies configuration from a persisted `builtin:*` catalogue definition.
 *
 * The removed built-in is addressed by the string literal `builtin:journal` (never the deleted
 * `BuiltInChannelImplementationIds` constants) and resolves unavailable through the generic
 * missing-provider path. The external package is simulated through the ordinary
 * [ChannelImplementationProviderRegistry.publishInstalledProviders] path under a canonical
 * `github-repository:<id>` identity — the same composition the side-by-side integration tests
 * use — while no built-in provider is registered, so `builtin:journal` stays Missing.
 */
class BuiltInDefinitionInstallIsolationTest {

    private val builtinJournalId = ChannelImplementationId("builtin:journal")

    // Canonical installed-package identity derived from a GitHub repository — NOT a builtin id.
    private val packageRepositoryId = GitHubRepositoryIdentity("123456")
    private val packageImplId = ChannelImplementationId("github-repository:123456")

    private val builtinPayloadJson =
        """{"baseDirectory":"/storage/journal","saveText":false,"saveVoice":true}"""

    // ──────────────────────────────────────────────────────────────
    //  A persisted builtin:journal definition resolves unavailable and
    //  survives byte-for-byte.
    // ──────────────────────────────────────────────────────────────

    @Test
    fun `persisted builtin journal definition resolves unavailable and is preserved byte-exact`() {
        val catalogueFile = seedCatalogueFile()
        val registry = installedPackageRegistry()

        // The builtin journal provider resolves unavailable via the generic missing-provider path.
        assertTrue(
            "builtin:journal descriptor must resolve Missing",
            registry.resolveDescriptor(builtinJournalId) is ChannelDescriptorResolution.Missing,
        )
        assertTrue(
            "builtin:journal provider must resolve Missing",
            registry.resolve(builtinJournalId) is ChannelProviderResolution.Missing,
        )

        val repository = ChannelRepository(mutablePrefs(), catalogueFile, registry)
        val snapshot = repository.catalogueState.value

        assertEquals(1, snapshot.definitions.size)
        val definition = snapshot.definitions.single()
        assertEquals("captains-log", definition.id)
        assertEquals("Journal", definition.name)
        assertEquals("builtin:journal", definition.implementationId.value)
        assertTrue(definition.enabled)
        assertEquals(1, definition.configSchemaVersion)
        // The persisted payload survives byte-for-byte even though the provider is unavailable.
        assertEquals(builtinPayloadJson, definition.configPayload.toJsonString())
    }

    // ──────────────────────────────────────────────────────────────
    //  Installing an external package and adding an instance does not
    //  rebind / mutate / remove / copy the builtin definition.
    // ──────────────────────────────────────────────────────────────

    @Test
    fun `installing external package and adding an instance does not rebind mutate remove or copy builtin journal`() {
        val catalogueFile = seedCatalogueFile()
        val registry = installedPackageRegistry()
        val repository = ChannelRepository(mutablePrefs(), catalogueFile, registry)

        val before = repository.catalogueState.value
        val beforeBuiltin = before.definitions.single { it.id == "captains-log" }
        val beforePayloadJson = beforeBuiltin.configPayload.toJsonString()
        val beforeCount = before.definitions.size

        // The installed package provider resolves available under its own id only.
        assertTrue(registry.resolve(packageImplId) is ChannelProviderResolution.Available)

        // Add a NEW channel instance of the installed package.
        val packagePayload = OpaqueJsonObject.parse("""{"mode":"PKG","source":"external"}""").getOrThrow()
        val result = repository.addChannel(
            ChannelDefinition(
                id = "package-journal-instance",
                name = "Package Journal",
                implementationId = packageImplId,
                enabled = true,
                configSchemaVersion = 1,
                configPayload = packagePayload,
            ),
        )
        assertEquals(ChannelRepositoryMutationResult.Success, result)

        val after = repository.catalogueState.value

        // Definition count grew by exactly one — the builtin was not removed or replaced.
        assertEquals(beforeCount + 1, after.definitions.size)

        // NO REBIND / NO MUTATE: the builtin definition is still present, field-for-field and
        // byte-for-byte identical to before the package install/add.
        val afterBuiltin = after.definitions.single { it.id == "captains-log" }
        assertEquals("captains-log", afterBuiltin.id)
        assertEquals("Journal", afterBuiltin.name)
        assertEquals("builtin:journal", afterBuiltin.implementationId.value)
        assertEquals(beforeBuiltin.enabled, afterBuiltin.enabled)
        assertEquals(beforeBuiltin.configSchemaVersion, afterBuiltin.configSchemaVersion)
        assertEquals(
            "builtin:journal payload must be byte-for-byte identical after package install/add",
            beforePayloadJson,
            afterBuiltin.configPayload.toJsonString(),
        )

        // NO REUSE: the new instance carries a DISTINCT id and a DISTINCT (package) impl id.
        val afterPackage = after.definitions.single { it.id == "package-journal-instance" }
        assertNotEquals("captains-log", afterPackage.id)
        assertEquals("github-repository:123456", afterPackage.implementationId.value)
        assertNotEquals(afterBuiltin.implementationId, afterPackage.implementationId)

        // NO COPY: the package instance payload is not cloned from the builtin definition.
        assertNotEquals(
            afterBuiltin.configPayload.toJsonString(),
            afterPackage.configPayload.toJsonString(),
        )

        // NO SELECT: the active channel was not switched by the package install/add.
        assertEquals("captains-log", after.activeChannelId)

        // The builtin still resolves unavailable — it was not rebound to the package provider.
        assertTrue(registry.resolve(builtinJournalId) is ChannelProviderResolution.Missing)
        assertTrue(registry.resolveDescriptor(builtinJournalId) is ChannelDescriptorResolution.Missing)
    }

    // ──────────────────────────────────────────────────────────────
    //  The package install path cannot re-add or claim the builtin id.
    // ──────────────────────────────────────────────────────────────

    @Test
    fun `package install path cannot re-add or rebind the builtin journal implementation id`() {
        val catalogueFile = seedCatalogueFile()
        val registry = installedPackageRegistry()
        val repository = ChannelRepository(mutablePrefs(), catalogueFile, registry)

        val beforeBuiltin = repository.catalogueState.value.definitions.single { it.id == "captains-log" }
        val beforePayloadJson = beforeBuiltin.configPayload.toJsonString()

        // Attempting to add a definition under the builtin implementation id fails: the builtin
        // provider is unavailable (Missing), so no package can claim or rebind that id.
        val result = repository.addChannel(
            ChannelDefinition(
                id = "rebound-journal",
                name = "Rebound Journal",
                implementationId = builtinJournalId,
                enabled = true,
                configSchemaVersion = 1,
                configPayload = OpaqueJsonObject.parse("""{"hijacked":true}""").getOrThrow(),
            ),
        )
        assertTrue(
            "addChannel under builtin:journal must fail while the provider is unavailable",
            result is ChannelRepositoryMutationResult.Failure,
        )

        // Catalogue is unchanged: still one definition, byte-for-byte intact.
        val after = repository.catalogueState.value
        assertEquals(1, after.definitions.size)
        val afterBuiltin = after.definitions.single { it.id == "captains-log" }
        assertEquals("builtin:journal", afterBuiltin.implementationId.value)
        assertEquals(beforePayloadJson, afterBuiltin.configPayload.toJsonString())

        // The package provider is available only under its own id, never the builtin id.
        assertTrue(registry.resolve(packageImplId) is ChannelProviderResolution.Available)
        assertFalse(registry.resolve(builtinJournalId) is ChannelProviderResolution.Available)
    }

    // ──────────────────────────────────────────────────────────────
    //  Helpers
    // ──────────────────────────────────────────────────────────────

    /** Persists a v2 catalogue containing a single persisted `builtin:journal` definition. */
    private fun seedCatalogueFile(): File {
        val snapshot = ChannelCatalogueSnapshot(
            listOf(
                ChannelDefinition(
                    id = "captains-log",
                    name = "Journal",
                    implementationId = builtinJournalId,
                    enabled = true,
                    configSchemaVersion = 1,
                    configPayload = OpaqueJsonObject.parse(builtinPayloadJson).getOrThrow(),
                ),
            ),
            "captains-log",
        )
        val file = tempFile()
        file.writeText(ChannelCatalogueCodec.toJson(snapshot))
        return file
    }

    /**
     * Provider registry simulating an installed external Lua package: the package provider is
     * published under a canonical `github-repository:<id>` identity, while NO built-in provider
     * is registered — so `builtin:journal` resolves Missing (unavailable).
     */
    private fun installedPackageRegistry(): ChannelImplementationProviderRegistry {
        val registry = ChannelImplementationProviderRegistry()
        val digest = ArtifactDigest("a".repeat(64))
        val provider = StubProvider(packageImplId, fingerprintValue = digest.value)
        val result = registry.publishInstalledProviders(
            mapOf(packageImplId to InstalledProviderBinding(packageRepositoryId, digest, provider)),
        )
        assertTrue(
            "Package provider must publish successfully",
            result is InstalledProvidersPublicationResult.Success,
        )
        return registry
    }

    private fun tempFile(): File {
        val file = File.createTempFile("builtin-install-isolation-test", ".json")
        file.delete() // ChannelRepository reads the seeded file we write afterwards.
        file.deleteOnExit()
        return file
    }

    private fun mutablePrefs(): MutableSharedPreferences = MutableSharedPreferences()

    /** Minimal stub provider standing in for an installed external package provider. */
    private class StubProvider(
        private val implId: ChannelImplementationId,
        private val fingerprintValue: String = "stub-fingerprint",
    ) : ChannelImplementationProvider {
        override val descriptor = ChannelImplementationDescriptor(
            implementationId = implId,
            presentation = ChannelPresentationMetadata("Stub", "STUB", "Stub unavailable"),
            configuration = object : ChannelConfigurationProvider {
                override val implementationId = implId
                override val currentSchemaVersion = 1
                override fun defaultPayload(): OpaqueJsonObject =
                    OpaqueJsonObject.parse("""{"default":true}""").getOrThrow()
                override fun validate(schemaVersion: Int, payload: OpaqueJsonObject): ProviderConfigurationResult =
                    ProviderConfigurationResult.Success(ValidatedChannelConfiguration(implId, schemaVersion, payload))
                override fun migrateStep(fromSchemaVersion: Int, payload: OpaqueJsonObject): ChannelConfigurationMigrationStep =
                    ChannelConfigurationMigrationStep.Failure(
                        ChannelProviderError.UnsupportedSchemaVersion(implId, fromSchemaVersion, 1),
                    )
            },
            configurationFields = listOf(ChannelConfigurationField.TextField("mode", "Mode")),
            requiredCapabilities = emptySet(),
            preparationTraits = ChannelPreparationTraits(supportsRecoverablePreparation = false),
        )
        override val fingerprint = ProviderRevisionFingerprint(fingerprintValue)

        override suspend fun constructRuntime(request: ChannelRuntimeConstructionRequest): ChannelRuntimeConstructionResult =
            ChannelRuntimeConstructionResult.Failure(
                ChannelProviderError.RuntimeConstructionFailed(implId, "Stub does not construct runtimes"),
            )
    }

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
