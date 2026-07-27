package io.talkcan.model

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Retention contract for persisted built-in channel definitions after removal of the
 * built-in journal / keyboard / openai-agent providers.
 *
 * Persisted `builtin:*` catalogue records must survive the persistence codec byte-for-byte
 * (identity, order, schema version, opaque payload, active pointer), keep the reserved
 * built-in implementation prefix, and resolve through the generic missing-provider path of
 * a provider registry that no longer registers them.
 *
 * The removed providers are addressed only by the literal strings `builtin:journal`,
 * `builtin:keyboard`, and `builtin:openai-agent` — no built-in constants or types are
 * referenced.
 */
class BuiltInCatalogueRetentionContractTest {

    private val journalId = ChannelImplementationId("builtin:journal")
    private val keyboardId = ChannelImplementationId("builtin:keyboard")
    private val openAiAgentId = ChannelImplementationId("builtin:openai-agent")

    private val builtinImplementationIds = listOf(journalId, keyboardId, openAiAgentId)

    @Test
    fun `persisted builtin definitions round-trip byte-for-byte through the catalogue codec`() {
        val original = retainedSnapshot()

        val json = ChannelCatalogueCodec.toJson(original)
        val document = decodeSuccess(json)
        val decoded = document.snapshot

        assertEquals(
            "Round-tripped catalogue must report the current document version",
            ChannelCatalogueCodec.CURRENT_DOCUMENT_VERSION,
            document.sourceDocumentVersion,
        )
        assertEquals(
            "Active channel pointer must round-trip",
            original.activeChannelId,
            decoded.activeChannelId,
        )
        assertEquals(
            "Definition count must round-trip",
            original.definitions.size,
            decoded.definitions.size,
        )
        assertEquals(
            "Snapshot must round-trip with exact definition order and contents",
            original,
            decoded,
        )

        original.definitions.forEachIndexed { index, expected ->
            val actual = decoded.definitions[index]
            assertEquals(
                "Definition id at index $index must keep its persisted order",
                expected.id,
                actual.id,
            )
            assertEquals(
                "Definition name at index $index must round-trip",
                expected.name,
                actual.name,
            )
            assertEquals(
                "Implementation id at index $index must round-trip as the exact literal value",
                expected.implementationId.value,
                actual.implementationId.value,
            )
            assertEquals(
                "Enabled flag at index $index must round-trip",
                expected.enabled,
                actual.enabled,
            )
            assertEquals(
                "Config schema version at index $index must round-trip",
                expected.configSchemaVersion,
                actual.configSchemaVersion,
            )
            assertEquals(
                "Opaque payload at index $index must round-trip byte-for-byte",
                expected.configPayload.toJsonString(),
                actual.configPayload.toJsonString(),
            )
            assertEquals(
                "Opaque payload object at index $index must round-trip without field loss",
                expected.configPayload.toJsonObject().toString(),
                actual.configPayload.toJsonObject().toString(),
            )
        }

        assertEquals(
            "Decoded definitions must keep the fixed built-in identity and order",
            listOf("builtin:journal", "builtin:keyboard", "builtin:openai-agent"),
            decoded.definitions.map { it.implementationId.value },
        )
    }

    @Test
    fun `builtin implementation ids resolve missing from a registry that registers no providers`() {
        val registry = ChannelImplementationProviderRegistry()

        builtinImplementationIds.forEach { implementationId ->
            val resolution = registry.resolve(implementationId)

            assertTrue(
                "Removed built-in $implementationId must fall through to the missing-provider path",
                resolution is ChannelProviderResolution.Missing,
            )
            assertEquals(
                "Missing-provider error must identify $implementationId",
                implementationId,
                (resolution as ChannelProviderResolution.Missing).error.implementationId,
            )
        }
    }

    @Test
    fun `builtin implementation ids stay missing when only unrelated providers are registered`() {
        val registry = ChannelImplementationProviderRegistry()
        val unrelatedProvider = UnrelatedPackageProvider(ChannelImplementationId("package:journal"))
        assertEquals(
            ChannelProviderRegistrationResult.Registered,
            registry.register(unrelatedProvider),
        )

        builtinImplementationIds.forEach { implementationId ->
            val resolution = registry.resolve(implementationId)

            assertTrue(
                "Removed built-in $implementationId must not be rebound, aliased, or substituted",
                resolution is ChannelProviderResolution.Missing,
            )
            assertEquals(
                "Missing-provider error must identify $implementationId",
                implementationId,
                (resolution as ChannelProviderResolution.Missing).error.implementationId,
            )
        }

        val unrelatedResolution = registry.resolve(unrelatedProvider.descriptor.implementationId)
        assertSame(
            "Unrelated registered provider must remain available",
            unrelatedProvider,
            (unrelatedResolution as ChannelProviderResolution.Available).provider,
        )
    }

    @Test
    fun `retained builtin definitions keep the reserved builtin prefix through the codec`() {
        val original = retainedSnapshot()

        val decoded = decodeSuccess(ChannelCatalogueCodec.toJson(original)).snapshot

        decoded.definitions.forEach { definition ->
            assertTrue(
                "Persisted definition ${definition.id} must keep the reserved builtin: prefix",
                definition.implementationId.value.startsWith("builtin:"),
            )
        }
        assertEquals(
            listOf("builtin:journal", "builtin:keyboard", "builtin:openai-agent"),
            decoded.definitions.map { it.implementationId.value },
        )
    }

    private fun retainedSnapshot(): ChannelCatalogueSnapshot = ChannelCatalogueSnapshot(
        definitions = listOf(
            ChannelDefinition(
                id = "captains-log",
                name = "Journal",
                implementationId = journalId,
                enabled = true,
                configSchemaVersion = 1,
                configPayload = OpaqueJsonObject.fromJsonObject(
                    JSONObject().apply {
                        put("baseDirectory", "/storage/emulated/0/journal")
                        put("saveVoice", true)
                        put("saveText", true)
                        put("rendering", JSONObject().apply {
                            put("markdown", true)
                            put("includeTimestamps", false)
                        })
                    },
                ),
            ),
            ChannelDefinition(
                id = "keyboard-channel",
                name = "Keyboard Channel",
                implementationId = keyboardId,
                enabled = true,
                configSchemaVersion = 2,
                configPayload = OpaqueJsonObject.parse(
                    """{"hostProfile":"linux:de","sleepwalker":{"autoConnect":true,"retry":{"maxAttempts":3,"backoffMillis":250}}}""",
                ).getOrThrow(),
            ),
            ChannelDefinition(
                id = "openai-agent",
                name = "OpenAI Agent",
                implementationId = openAiAgentId,
                enabled = false,
                configSchemaVersion = 3,
                configPayload = OpaqueJsonObject.parse(
                    """{"profileId":"profile:primary","model":"gpt-4o","tools":["transcription","synthesis"],"limits":{"maxTokens":512,"streaming":{"enabled":true}}}""",
                ).getOrThrow(),
            ),
        ),
        activeChannelId = "openai-agent",
    )

    private fun decodeSuccess(json: String): DecodedChannelCatalogue {
        val result = ChannelCatalogueCodec.decode(json)
        return (result as? ChannelCatalogueDecodeResult.Success)?.document
            ?: throw AssertionError("Expected successful catalogue decode, got $result")
    }

    /** A non-built-in provider built only on the public provider contract. */
    private class UnrelatedPackageProvider(
        implementationId: ChannelImplementationId,
    ) : ChannelImplementationProvider {
        override val fingerprint = ProviderRevisionFingerprint("test-fingerprint")
        override val descriptor = ChannelImplementationDescriptor(
            implementationId = implementationId,
            presentation = ChannelPresentationMetadata(
                "Package provider",
                "PKG",
                "Package provider unavailable",
            ),
            configuration = StubConfigurationProvider(implementationId),
            configurationFields = listOf(
                ChannelConfigurationField.TextField("required", "Required value"),
            ),
            requiredCapabilities = emptySet(),
            preparationTraits = ChannelPreparationTraits(supportsRecoverablePreparation = false),
        )

        override suspend fun constructRuntime(
            request: ChannelRuntimeConstructionRequest,
        ): ChannelRuntimeConstructionResult = ChannelRuntimeConstructionResult.Failure(
            ChannelProviderError.RuntimeConstructionFailed(
                descriptor.implementationId,
                "Runtime construction is outside retention contract coverage",
            ),
        )
    }

    private class StubConfigurationProvider(
        override val implementationId: ChannelImplementationId,
    ) : ChannelConfigurationProvider {
        override val currentSchemaVersion = 1

        override fun defaultPayload(): OpaqueJsonObject =
            OpaqueJsonObject.parse("""{"required":"ok"}""").getOrThrow()

        override fun validate(
            schemaVersion: Int,
            payload: OpaqueJsonObject,
        ): ProviderConfigurationResult = ProviderConfigurationResult.Success(
            ValidatedChannelConfiguration(implementationId, schemaVersion, payload),
        )

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
}
