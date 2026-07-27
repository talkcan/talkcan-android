package io.talkcan.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelCatalogueTest {
    private val testImplId = ChannelImplementationId("test:provider")

    @Test
    fun `codec round-trip preserves every definition payload exactly`() {
        val payloadA = opaque("""{"mode":"alpha","version":1}""")
        val payloadB = opaque("""{"mode":"beta","nested":{"enabled":true,"count":42}}""")
        val definitions = listOf(
            ChannelDefinition("a", "Alpha", testImplId, enabled = true, configSchemaVersion = 1, configPayload = payloadA),
            ChannelDefinition("b", "Beta", testImplId, enabled = true, configSchemaVersion = 1, configPayload = payloadB),
        )
        val original = ChannelCatalogueSnapshot(definitions, "a")

        val json = ChannelCatalogueCodec.toJson(original)
        val decoded = (ChannelCatalogueCodec.decode(json) as ChannelCatalogueDecodeResult.Success).document.snapshot

        assertEquals(original.definitions.size, decoded.definitions.size)
        assertEquals(original.activeChannelId, decoded.activeChannelId)
        decoded.definitions.zip(original.definitions).forEach { (decodedDef, originalDef) ->
            assertEquals(originalDef.configPayload.toJsonString(), decodedDef.configPayload.toJsonString())
        }
    }

    @Test
    fun `codec round-trip preserves provider extension objects faithfully`() {
        val payload = opaque(
            """{"mode":"hybrid","providerExtension":{"sub":{"nested":true,"values":[1,2,3]},"retained":42},"futureFlag":false}""",
        )
        val definitions = listOf(
            ChannelDefinition("x", "X", testImplId, enabled = true, configSchemaVersion = 1, configPayload = payload),
        )
        val original = ChannelCatalogueSnapshot(definitions, "x")

        val json = ChannelCatalogueCodec.toJson(original)
        val decoded = (ChannelCatalogueCodec.decode(json) as ChannelCatalogueDecodeResult.Success).document.snapshot
        val decodedPayload = decoded.definitions.single().configPayload

        assertEquals(payload.toJsonString(), decodedPayload.toJsonString())
        val obj = decodedPayload.toJsonObject()
        assertEquals("hybrid", obj.getString("mode"))
        assertTrue(obj.getJSONObject("providerExtension").getJSONObject("sub").getBoolean("nested"))
        assertEquals(42, obj.getJSONObject("providerExtension").getInt("retained"))
        assertFalse(obj.getBoolean("futureFlag"))
    }

    @Test
    fun `selectChannel preserves all definition payloads unchanged`() {
        val payloads = mapOf(
            "a" to opaque("""{"channel":"a"}"""),
            "b" to opaque("""{"channel":"b"}"""),
            "c" to opaque("""{"channel":"c"}"""),
        )
        val definitions = payloads.map { (id, payload) ->
            ChannelDefinition(id, id.uppercase(), testImplId, enabled = true, configSchemaVersion = 1, configPayload = payload)
        }
        val snapshot = ChannelCatalogueSnapshot(definitions, "a")

        val result = snapshot.selectChannel("b")
        val updated = (result as ChannelCatalogueMutationResult.Success).snapshot

        assertEquals("b", updated.activeChannelId)
        updated.definitions.forEach { def ->
            assertEquals(payloads[def.id]?.toJsonString(), def.configPayload.toJsonString())
        }
    }

    @Test
    fun `addChannel preserves existing definition payloads`() {
        val existingDef = ChannelDefinition(
            "existing", "Existing", testImplId, enabled = true, configSchemaVersion = 1,
            configPayload = opaque("""{"original":true}"""),
        )
        val snapshot = ChannelCatalogueSnapshot(listOf(existingDef), "existing")

        val newDef = ChannelDefinition(
            "new", "New", testImplId, enabled = true, configSchemaVersion = 1,
            configPayload = opaque("""{"added":true}"""),
        )
        val result = snapshot.addChannel(newDef)
        val updated = (result as ChannelCatalogueMutationResult.Success).snapshot

        assertEquals(2, updated.definitions.size)
        assertEquals(existingDef.configPayload.toJsonString(), updated.definitions.first().configPayload.toJsonString())
    }

    @Test
    fun `updateChannel preserves sibling definition payloads unchanged`() {
        val payloadAlpha = opaque("""{"mode":"alpha"}""")
        val payloadBeta = opaque("""{"mode":"beta"}""")
        val definitions = listOf(
            ChannelDefinition("a", "Alpha", testImplId, enabled = true, configSchemaVersion = 1, configPayload = payloadAlpha),
            ChannelDefinition("b", "Beta", testImplId, enabled = true, configSchemaVersion = 1, configPayload = payloadBeta),
        )
        val snapshot = ChannelCatalogueSnapshot(definitions, "a")

        val result = snapshot.updateChannel("a") { it.copy(configPayload = opaque("""{"mode":"alpha-updated"}""")) }
        val updated = (result as ChannelCatalogueMutationResult.Success).snapshot

        assertEquals("""{"mode":"alpha-updated"}""", updated.definitions[0].configPayload.toJsonString())
        assertEquals(payloadBeta.toJsonString(), updated.definitions[1].configPayload.toJsonString())
    }

    @Test
    fun `moveChannel preserves all definition payloads`() {
        val payloads = listOf(
            opaque("""{"pos":"first"}"""),
            opaque("""{"pos":"second"}"""),
            opaque("""{"pos":"third"}"""),
        )
        val definitions = listOf(
            ChannelDefinition("a", "A", testImplId, enabled = true, configSchemaVersion = 1, configPayload = payloads[0]),
            ChannelDefinition("b", "B", testImplId, enabled = true, configSchemaVersion = 1, configPayload = payloads[1]),
            ChannelDefinition("c", "C", testImplId, enabled = true, configSchemaVersion = 1, configPayload = payloads[2]),
        )
        val snapshot = ChannelCatalogueSnapshot(definitions, "a")

        val result = snapshot.moveChannel("c", 0)
        val updated = (result as ChannelCatalogueMutationResult.Success).snapshot

        assertEquals(3, updated.definitions.size)
        assertEquals("c", updated.definitions[0].id)
        assertEquals("a", updated.definitions[1].id)
        assertEquals("b", updated.definitions[2].id)
        updated.definitions.forEach { def ->
            val expected = payloads.find { it.toJsonString() == def.configPayload.toJsonString() }
            assertTrue(expected != null)
        }
    }

    @Test
    fun `removeChannel preserves remaining definition payloads`() {
        val payloadBeta = opaque("""{"mode":"beta"}""")
        val definitions = listOf(
            ChannelDefinition("a", "Alpha", testImplId, enabled = true, configSchemaVersion = 1, configPayload = opaque("""{"mode":"alpha"}""")),
            ChannelDefinition("b", "Beta", testImplId, enabled = true, configSchemaVersion = 1, configPayload = payloadBeta),
        )
        val snapshot = ChannelCatalogueSnapshot(definitions, "a")

        val result = snapshot.removeChannel("a")
        val updated = (result as ChannelCatalogueMutationResult.Success).snapshot

        assertEquals(1, updated.definitions.size)
        assertEquals("b", updated.definitions.single().id)
        assertEquals(payloadBeta.toJsonString(), updated.definitions.single().configPayload.toJsonString())
    }

    @Test
    fun `rename preserves payload exactly unchanged`() {
        val payload = opaque("""{"preserved":true,"value":42}""")
        val definition = ChannelDefinition(
            "original-id", "Original Name", testImplId, enabled = true, configSchemaVersion = 1, configPayload = payload,
        )
        val snapshot = ChannelCatalogueSnapshot(listOf(definition), "original-id")

        val result = snapshot.updateChannel("original-id") { it.copy(name = "Renamed") }
        val updated = (result as ChannelCatalogueMutationResult.Success).snapshot

        assertEquals("Renamed", updated.definitions.single().name)
        assertEquals(payload.toJsonString(), updated.definitions.single().configPayload.toJsonString())
    }

    @Test
    fun `two same-provider instances retain isolated payloads after updating sibling`() {
        val payloadFirst = opaque("""{"instance":"first","value":1}""")
        val payloadSecond = opaque("""{"instance":"second","value":2}""")
        val definitions = listOf(
            ChannelDefinition("one", "One", testImplId, enabled = true, configSchemaVersion = 1, configPayload = payloadFirst),
            ChannelDefinition("two", "Two", testImplId, enabled = true, configSchemaVersion = 1, configPayload = payloadSecond),
        )
        val snapshot = ChannelCatalogueSnapshot(definitions, "one")

        val result = snapshot.updateChannel("one") { it.copy(configPayload = opaque("""{"instance":"first","value":100}""")) }
        val updated = (result as ChannelCatalogueMutationResult.Success).snapshot

        assertEquals("""{"instance":"first","value":100}""", updated.definitions[0].configPayload.toJsonString())
        assertEquals(payloadSecond.toJsonString(), updated.definitions[1].configPayload.toJsonString())
    }

    @Test
    fun `malformed json returns typed decode failure`() {
        assertTrue(ChannelCatalogueCodec.decode("{invalid") is ChannelCatalogueDecodeResult.Failure)
        assertTrue(ChannelCatalogueCodec.decode("not-json") is ChannelCatalogueDecodeResult.Failure)
        assertTrue(ChannelCatalogueCodec.decode("""{"version":2}""") is ChannelCatalogueDecodeResult.Failure)
    }

    @Test
    fun `multiple encode decode cycles preserve payload exactly`() {
        val payload = opaque("""{"complex":{"nested":["keep",true],"count":7},"preserved":"exact"}""")
        val definitions = listOf(
            ChannelDefinition("stable", "Stable", testImplId, enabled = true, configSchemaVersion = 1, configPayload = payload),
        )
        val original = ChannelCatalogueSnapshot(definitions, "stable")

        var current = original
        repeat(3) {
            val json = ChannelCatalogueCodec.toJson(current)
            val decoded = (ChannelCatalogueCodec.decode(json) as ChannelCatalogueDecodeResult.Success).document.snapshot
            assertEquals(payload.toJsonString(), decoded.definitions.single().configPayload.toJsonString())
            current = decoded
        }
    }

    @Test
    fun `file store save and load preserves opaque payload across simulated restart`() {
        val payloadAlpha = opaque("""{"mode":"alpha","providerExtension":{"nested":{"deep":true,"values":[1,2,3]},"retained":42},"futureFlag":false}""")
        val payloadBeta = opaque("""{"mode":"beta","opaqueBuffer":"non-utf8-preserved"}""")
        val definitions = listOf(
            ChannelDefinition("a", "Alpha", testImplId, enabled = true, configSchemaVersion = 1, configPayload = payloadAlpha),
            ChannelDefinition("b", "Beta", testImplId, enabled = true, configSchemaVersion = 1, configPayload = payloadBeta),
        )
        val original = ChannelCatalogueSnapshot(definitions, "a")

        val file = java.io.File.createTempFile("catalogue-restart-test", ".json")
        try {
            val firstStore = ChannelCatalogueFileStore(file)
            assertTrue(firstStore.save(original) is ChannelCatalogueFileStoreResult.Success)

            // Simulate process restart: new store instance reads the same file.
            val restartedStore = ChannelCatalogueFileStore(file)
            val loaded = (restartedStore.load() as? ChannelCatalogueLoadResult.Success)?.document?.snapshot
                ?: throw AssertionError("Expected successful load after restart")

            assertEquals(2, loaded.definitions.size)
            assertEquals("a", loaded.activeChannelId)
            assertEquals(payloadAlpha.toJsonString(), loaded.definitions[0].configPayload.toJsonString())
            assertEquals(payloadBeta.toJsonString(), loaded.definitions[1].configPayload.toJsonString())

            // Verify nested provider extension is preserved exactly.
            val loadedAlpha = loaded.definitions[0].configPayload.toJsonObject()
            assertTrue(loadedAlpha.getJSONObject("providerExtension").getJSONObject("nested").getBoolean("deep"))
            assertEquals(42, loadedAlpha.getJSONObject("providerExtension").getInt("retained"))
            assertFalse(loadedAlpha.getBoolean("futureFlag"))
        } finally {
            file.delete()
        }
    }

    private fun opaque(encoded: String): OpaqueJsonObject = OpaqueJsonObject.parse(encoded).getOrThrow()
}
