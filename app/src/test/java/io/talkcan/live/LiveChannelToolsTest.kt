package io.talkcan.live

import io.talkcan.model.ChannelCatalogueSnapshot
import io.talkcan.model.ChannelDefinition
import io.talkcan.model.ChannelImplementationId
import io.talkcan.model.OpaqueJsonObject
import io.talkcan.service.ChannelExecutionStatus
import io.talkcan.service.ChannelPreparationAvailability
import io.talkcan.service.ChannelPreparationReason
import io.talkcan.service.ChannelRuntimeSnapshot
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Refusal-boundary tests for [LiveChannelTools] with a fake [LiveChannelReader].
 * No Android, SAF, or filesystem I/O is touched here; SAF confinement is covered by
 * `SafLiveChannelReaderTest` and the VFS core tests.
 */
class LiveChannelToolsTest {

    private data class Fixture(
        val catalogue: ChannelCatalogueSnapshot,
        val runtimes: List<ChannelRuntimeSnapshot>,
        var sessionActive: Boolean = true,
        val allowControl: Boolean = true,
        val allowRead: Boolean = true,
        val reader: FakeReader = FakeReader(),
        var selectCalls: Int = 0,
        var selectResult: Boolean = true,
        var onSelect: (suspend () -> Unit)? = null,
    ) {
        fun tools(): LiveChannelTools = LiveChannelTools(
            catalogue = { catalogue },
            snapshots = { runtimes },
            selectChannel = { id ->
                selectCalls++
                onSelect?.invoke()
                selectResult && catalogue.definitions.any { it.id == id }
            },
            reader = reader,
            allowControl = allowControl,
            allowRead = allowRead,
            sessionIsActive = { sessionActive },
        )
    }

    private class FakeReader(
        var mounts: JSONArray = JSONArray().put(JSONObject().put("mount_id", "notes").put("label", "Notes")),
        var listResult: JSONObject = JSONObject()
            .put("entries", JSONArray().put(JSONObject().put("name", "a.txt").put("kind", "file")))
            .put("next_cursor", JSONObject.NULL),
        var readResult: JSONObject = JSONObject().put("text", "hello").put("bytes", 5),
        var onList: (suspend () -> Unit)? = null,
        var onRead: (suspend () -> Unit)? = null,
        var onMounts: (suspend () -> Unit)? = null,
    ) : LiveChannelReader {
        var mountsCalls = 0
        var listCalls = 0
        var readCalls = 0

        override suspend fun mounts(channelId: String): JSONArray {
            mountsCalls++
            onMounts?.invoke()
            return mounts
        }

        override suspend fun list(
            channelId: String,
            mountId: String,
            path: String,
            limit: Int,
            cursor: String?,
        ): JSONObject {
            listCalls++
            onList?.invoke()
            return listResult
        }

        override suspend fun read(
            channelId: String,
            mountId: String,
            path: String,
            maxBytes: Int,
        ): JSONObject {
            readCalls++
            onRead?.invoke()
            return readResult
        }

        override suspend fun close() = Unit
    }

    private fun definition(
        id: String = "ch1",
        name: String = "Journal",
        enabled: Boolean = true,
        implementation: String = "builtin:journal",
    ) = ChannelDefinition(
        id = id,
        name = name,
        implementationId = ChannelImplementationId(implementation),
        enabled = enabled,
        configSchemaVersion = 1,
        configPayload = OpaqueJsonObject.fromJsonObject(JSONObject()),
    )

    private fun runtime(
        id: String = "ch1",
        name: String = "Journal",
        enabled: Boolean = true,
        summary: String? = "today",
        pendingCount: Int = 2,
    ) = ChannelRuntimeSnapshot(
        id = id,
        name = name,
        implementationId = ChannelImplementationId("builtin:journal"),
        enabled = enabled,
        preparation = ChannelPreparationAvailability.Available,
        executionStatus = ChannelExecutionStatus.IDLE,
        summary = summary,
        pendingCount = pendingCount,
    )

    private fun code(result: JSONObject): String =
        result.getJSONObject("error").getString("code")

    // -- definitions ----------------------------------------------------

    @Test
    fun definitionsReturnsFiveBackendCompatibleFunctionTools() {
        val fixture = Fixture(
            catalogue = ChannelCatalogueSnapshot(listOf(definition()), "ch1"),
            runtimes = listOf(runtime()),
        )
        val defs = fixture.tools().definitions()
        assertEquals(5, defs.length())
        val names = (0 until defs.length()).map { defs.getJSONObject(it).getString("name") }.toSet()
        assertEquals(
            setOf("list_channels", "get_channel", "select_channel", "list_channel_files", "read_channel_file"),
            names,
        )
        for (i in 0 until defs.length()) {
            val entry = defs.getJSONObject(i)
            assertEquals("function", entry.getString("type"))
            assertTrue(entry.getString("description").isNotBlank())
            val params = entry.getJSONObject("parameters")
            assertEquals("object", params.getString("type"))
            assertFalse(params.getBoolean("additionalProperties"))
        }
    }

    // -- list_channels --------------------------------------------------

    @Test
    fun listChannelsReturnsBoundedMetadata() = runTest {
        val fixture = Fixture(
            catalogue = ChannelCatalogueSnapshot(
                listOf(definition("ch1", "One"), definition("ch2", "Two", enabled = false)),
                "ch1",
            ),
            runtimes = listOf(runtime("ch1"), runtime("ch2", enabled = false)),
        )
        val result = fixture.tools().execute("list_channels", JSONObject())
        val channels = result.getJSONArray("channels")
        assertEquals(2, channels.length())
        assertEquals("ch1", channels.getJSONObject(0).getString("id"))
        assertEquals(true, channels.getJSONObject(0).getBoolean("active"))
        assertEquals(false, channels.getJSONObject(1).getBoolean("enabled"))
        assertEquals("ch1", result.getString("active_channel_id"))
        // No configuration, secrets, or ledger content may leak through metadata.
        val encoded = result.toString()
        assertFalse(encoded.contains("config", ignoreCase = true))
        assertFalse(encoded.contains("secret", ignoreCase = true))
        assertFalse(encoded.contains("profile", ignoreCase = true))
        assertFalse(encoded.contains("ledger", ignoreCase = true))
    }

    @Test
    fun listChannelsRejectsUnknownArgs() = runTest {
        val fixture = Fixture(
            catalogue = ChannelCatalogueSnapshot(listOf(definition()), "ch1"),
            runtimes = listOf(runtime()),
        )
        assertEquals(
            "invalid_arguments",
            code(fixture.tools().execute("list_channels", JSONObject().put("extra", 1))),
        )
    }

    @Test
    fun listChannelsRefusesStaleSession() = runTest {
        val fixture = Fixture(
            catalogue = ChannelCatalogueSnapshot(listOf(definition()), "ch1"),
            runtimes = listOf(runtime()),
            sessionActive = false,
        )
        assertEquals("session_closed", code(fixture.tools().execute("list_channels", JSONObject())))
    }

    // -- get_channel ----------------------------------------------------

    @Test
    fun getChannelHidesMountsWithoutRead() = runTest {
        val fixture = Fixture(
            catalogue = ChannelCatalogueSnapshot(listOf(definition()), "ch1"),
            runtimes = listOf(runtime()),
            allowRead = false,
        )
        val result = fixture.tools().execute("get_channel", JSONObject().put("channel_id", "ch1"))
        assertEquals("ch1", result.getString("id"))
        assertFalse(result.has("mounts"))
        assertEquals(0, fixture.reader.mountsCalls)
    }

    @Test
    fun getChannelIncludesMountsWithReadAndSafeSnapshotFields() = runTest {
        val fixture = Fixture(
            catalogue = ChannelCatalogueSnapshot(listOf(definition()), "ch1"),
            runtimes = listOf(runtime(summary = "hello", pendingCount = 3)),
        )
        val result = fixture.tools().execute("get_channel", JSONObject().put("channel_id", "ch1"))
        assertEquals("hello", result.getString("summary"))
        assertEquals(3, result.getInt("pending_count"))
        assertTrue(result.has("mounts"))
        assertEquals(1, fixture.reader.mountsCalls)
        val encoded = result.toString()
        assertFalse(encoded.contains("configPayload", ignoreCase = true))
        assertFalse(encoded.contains("secret", ignoreCase = true))
        assertFalse(encoded.contains("ledger", ignoreCase = true))
    }

    @Test
    fun getChannelUnknownChannel() = runTest {
        val fixture = Fixture(
            catalogue = ChannelCatalogueSnapshot(listOf(definition()), "ch1"),
            runtimes = listOf(runtime()),
        )
        assertEquals(
            "unknown_channel",
            code(fixture.tools().execute("get_channel", JSONObject().put("channel_id", "missing"))),
        )
    }

    @Test
    fun getChannelRejectsMissingAndTypeInvalidArgs() = runTest {
        val fixture = Fixture(
            catalogue = ChannelCatalogueSnapshot(listOf(definition()), "ch1"),
            runtimes = listOf(runtime()),
        )
        assertEquals("invalid_arguments", code(fixture.tools().execute("get_channel", JSONObject())))
        assertEquals(
            "invalid_arguments",
            code(fixture.tools().execute("get_channel", JSONObject().put("channel_id", 123))),
        )
        assertEquals(
            "invalid_arguments",
            code(
                fixture.tools().execute(
                    "get_channel",
                    JSONObject().put("channel_id", "ch1").put("extra", true),
                ),
            ),
        )
    }

    @Test
    fun getChannelSuppressesLateSuccessAfterSessionClose() = runTest {
        val fixture = Fixture(
            catalogue = ChannelCatalogueSnapshot(listOf(definition()), "ch1"),
            runtimes = listOf(runtime()),
        )
        fixture.reader.onMounts = { fixture.sessionActive = false }
        assertEquals(
            "session_closed",
            code(fixture.tools().execute("get_channel", JSONObject().put("channel_id", "ch1"))),
        )
    }

    // -- select_channel -------------------------------------------------

    @Test
    fun selectChannelRefusedWithoutControlAndWithoutEffect() = runTest {
        val fixture = Fixture(
            catalogue = ChannelCatalogueSnapshot(listOf(definition()), "ch1"),
            runtimes = listOf(runtime()),
            allowControl = false,
        )
        assertEquals(
            "not_permitted",
            code(fixture.tools().execute("select_channel", JSONObject().put("channel_id", "ch1"))),
        )
        assertEquals(0, fixture.selectCalls)
    }

    @Test
    fun selectChannelRejectsUnknownAndDisabledTargets() = runTest {
        val fixture = Fixture(
            catalogue = ChannelCatalogueSnapshot(
                listOf(definition("ch1"), definition("ch2", enabled = false)),
                "ch1",
            ),
            runtimes = listOf(runtime("ch1")),
        )
        assertEquals(
            "unknown_channel",
            code(fixture.tools().execute("select_channel", JSONObject().put("channel_id", "ghost"))),
        )
        assertEquals(
            "channel_disabled",
            code(fixture.tools().execute("select_channel", JSONObject().put("channel_id", "ch2"))),
        )
        assertEquals(0, fixture.selectCalls)
    }

    @Test
    fun selectChannelSucceedsAndPublishesOnlyWhileLive() = runTest {
        val fixture = Fixture(
            catalogue = ChannelCatalogueSnapshot(listOf(definition("ch1"), definition("ch2")), "ch1"),
            runtimes = listOf(runtime("ch1")),
        )
        val ok = fixture.tools().execute("select_channel", JSONObject().put("channel_id", "ch2"))
        assertEquals(true, ok.getBoolean("ok"))
        assertEquals(1, fixture.selectCalls)
    }

    @Test
    fun selectChannelSuppressedWhenSessionClosesDuringEffect() = runTest {
        val fixture = Fixture(
            catalogue = ChannelCatalogueSnapshot(listOf(definition("ch1"), definition("ch2")), "ch1"),
            runtimes = listOf(runtime("ch1")),
        )
        fixture.onSelect = { fixture.sessionActive = false }
        assertEquals(
            "session_closed",
            code(fixture.tools().execute("select_channel", JSONObject().put("channel_id", "ch2"))),
        )
        // The host effect already ran; the live result is still suppressed as stale.
        assertEquals(1, fixture.selectCalls)
    }

    @Test
    fun selectChannelMapsFailedCommit() = runTest {
        val fixture = Fixture(
            catalogue = ChannelCatalogueSnapshot(listOf(definition("ch1"), definition("ch2")), "ch1"),
            runtimes = listOf(runtime("ch1")),
            selectResult = false,
        )
        assertEquals(
            "selection_failed",
            code(fixture.tools().execute("select_channel", JSONObject().put("channel_id", "ch2"))),
        )
    }

    // -- list_channel_files ---------------------------------------------

    @Test
    fun listFilesRefusedWithoutReadAndWithoutReaderEffect() = runTest {
        val fixture = Fixture(
            catalogue = ChannelCatalogueSnapshot(listOf(definition()), "ch1"),
            runtimes = listOf(runtime()),
            allowRead = false,
        )
        val args = JSONObject().put("channel_id", "ch1").put("mount_id", "notes")
        assertEquals("not_permitted", code(fixture.tools().execute("list_channel_files", args)))
        assertEquals(0, fixture.reader.listCalls)
    }

    @Test
    fun listFilesRejectsTraversalAbsoluteAndBackslash() = runTest {
        val fixture = Fixture(
            catalogue = ChannelCatalogueSnapshot(listOf(definition()), "ch1"),
            runtimes = listOf(runtime()),
        )
        for (bad in listOf("../up", "..", "/abs", "a//b", "a/./b", "a\\b", "dir/")) {
            val args = JSONObject()
                .put("channel_id", "ch1").put("mount_id", "notes").put("path", bad)
            assertEquals("invalid_path for $bad", "invalid_path", code(fixture.tools().execute("list_channel_files", args)))
        }
        assertEquals(0, fixture.reader.listCalls)
    }

    @Test
    fun listFilesRejectsBoundsAndSchema() = runTest {
        val fixture = Fixture(
            catalogue = ChannelCatalogueSnapshot(listOf(definition()), "ch1"),
            runtimes = listOf(runtime()),
        )
        // Empty and overlong ids.
        assertEquals(
            "invalid_channel_id",
            code(
                fixture.tools().execute(
                    "list_channel_files",
                    JSONObject().put("channel_id", "").put("mount_id", "notes"),
                ),
            ),
        )
        assertEquals(
            "invalid_mount_id",
            code(
                fixture.tools().execute(
                    "list_channel_files",
                    JSONObject().put("channel_id", "ch1").put("mount_id", ""),
                ),
            ),
        )
        assertEquals(
            "invalid_mount_id",
            code(
                fixture.tools().execute(
                    "list_channel_files",
                    JSONObject().put("channel_id", "ch1").put("mount_id", "m".repeat(129)),
                ),
            ),
        )
        // Finite page 1..50.
        for (badLimit in listOf(0, 51, -1)) {
            assertEquals(
                "invalid_limit for $badLimit",
                "invalid_limit",
                code(
                    fixture.tools().execute(
                        "list_channel_files",
                        JSONObject().put("channel_id", "ch1").put("mount_id", "notes").put("limit", badLimit),
                    ),
                ),
            )
        }
        // Unknown, missing, and type-invalid.
        assertEquals(
            "invalid_arguments",
            code(
                fixture.tools().execute(
                    "list_channel_files",
                    JSONObject().put("channel_id", "ch1").put("mount_id", "notes").put("bogus", 1),
                ),
            ),
        )
        assertEquals(
            "invalid_arguments",
            code(fixture.tools().execute("list_channel_files", JSONObject().put("channel_id", "ch1"))),
        )
        assertEquals(
            "invalid_arguments",
            code(
                fixture.tools().execute(
                    "list_channel_files",
                    JSONObject().put("channel_id", "ch1").put("mount_id", "notes").put("limit", "20"),
                ),
            ),
        )
        assertEquals(
            "invalid_cursor",
            code(
                fixture.tools().execute(
                    "list_channel_files",
                    JSONObject().put("channel_id", "ch1").put("mount_id", "notes").put("cursor", ""),
                ),
            ),
        )
        assertEquals(0, fixture.reader.listCalls)
    }

    @Test
    fun listFilesForwardsReaderPageAndSuppressesStaleSession() = runTest {
        val fixture = Fixture(
            catalogue = ChannelCatalogueSnapshot(listOf(definition()), "ch1"),
            runtimes = listOf(runtime()),
        )
        val args = JSONObject().put("channel_id", "ch1").put("mount_id", "notes").put("limit", 10)
        val page = fixture.tools().execute("list_channel_files", args)
        assertEquals("a.txt", page.getJSONArray("entries").getJSONObject(0).getString("name"))
        assertEquals(1, fixture.reader.listCalls)

        fixture.reader.onList = { fixture.sessionActive = false }
        assertEquals("session_closed", code(fixture.tools().execute("list_channel_files", args)))
    }

    // -- read_channel_file ----------------------------------------------

    @Test
    fun readFileRejectsBoundsAndTraversal() = runTest {
        val fixture = Fixture(
            catalogue = ChannelCatalogueSnapshot(listOf(definition()), "ch1"),
            runtimes = listOf(runtime()),
        )
        assertEquals(
            "invalid_path",
            code(
                fixture.tools().execute(
                    "read_channel_file",
                    JSONObject().put("channel_id", "ch1").put("mount_id", "notes").put("path", ""),
                ),
            ),
        )
        assertEquals(
            "invalid_path",
            code(
                fixture.tools().execute(
                    "read_channel_file",
                    JSONObject().put("channel_id", "ch1").put("mount_id", "notes").put("path", "../secret"),
                ),
            ),
        )
        assertEquals(
            "invalid_max_bytes",
            code(
                fixture.tools().execute(
                    "read_channel_file",
                    JSONObject().put("channel_id", "ch1").put("mount_id", "notes")
                        .put("path", "a.txt").put("max_bytes", 0),
                ),
            ),
        )
        assertEquals(
            "invalid_max_bytes",
            code(
                fixture.tools().execute(
                    "read_channel_file",
                    JSONObject().put("channel_id", "ch1").put("mount_id", "notes")
                        .put("path", "a.txt").put("max_bytes", 32769),
                ),
            ),
        )
        assertEquals(0, fixture.reader.readCalls)
    }

    @Test
    fun readFileForwardsStoredContentVerbatim() = runTest {
        val fixture = Fixture(
            catalogue = ChannelCatalogueSnapshot(listOf(definition()), "ch1"),
            runtimes = listOf(runtime()),
        )
        fixture.reader.readResult = JSONObject().put("text", "stored notes").put("bytes", 12)
        val result = fixture.tools().execute(
            "read_channel_file",
            JSONObject().put("channel_id", "ch1").put("mount_id", "notes").put("path", "day.md"),
        )
        assertEquals("stored notes", result.getString("text"))
    }

    // -- dispatch -------------------------------------------------------

    @Test
    fun unknownToolThrows() = runTest {
        val fixture = Fixture(
            catalogue = ChannelCatalogueSnapshot(listOf(definition()), "ch1"),
            runtimes = listOf(runtime()),
        )
        try {
            fixture.tools().execute("no_such_tool", JSONObject())
            fail("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("unknown tool"))
        }
    }

    @Test
    fun degradedPreparationStillPublishesSafeStatus() = runTest {
        val degraded = ChannelRuntimeSnapshot(
            id = "ch1",
            name = "Journal",
            implementationId = ChannelImplementationId("builtin:journal"),
            enabled = true,
            preparation = ChannelPreparationAvailability.Recoverable(
                ChannelPreparationReason.RuntimeBusy,
            ),
            executionStatus = ChannelExecutionStatus.IDLE,
            summary = "busy",
            pendingCount = 0,
        )
        val fixture = Fixture(
            catalogue = ChannelCatalogueSnapshot(listOf(definition()), "ch1"),
            runtimes = listOf(degraded),
        )
        val result = fixture.tools().execute("get_channel", JSONObject().put("channel_id", "ch1"))
        assertEquals(false, result.getBoolean("available"))
        assertTrue(result.getString("status").isNotBlank())
    }
}
