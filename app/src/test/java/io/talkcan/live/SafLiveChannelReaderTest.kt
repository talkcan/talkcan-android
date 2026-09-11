package io.talkcan.live

import android.content.ContentResolver
import io.mockk.every
import io.mockk.mockk
import io.talkcan.dependency.PackageMountAccess
import io.talkcan.dependency.PackageMountDeclaration
import io.talkcan.dependency.PackageMountKind
import io.talkcan.dependency.PackageResourcesDeclaration
import io.talkcan.model.ChannelDefinition
import io.talkcan.model.ChannelImplementationDescriptor
import io.talkcan.model.ChannelImplementationId
import io.talkcan.model.OpaqueJsonObject
import io.talkcan.mount.saf.SafGrantController
import io.talkcan.resource.MountBindingStore
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Gating tests for [SafLiveChannelReader] that never reach SAF I/O.
 * Positive list/read content paths run against the shared VFS core tests and on-device
 * SAF fixtures; here we prove the reader refuses honestly before any provider effect:
 * stale/cancelled sessions, unknown/disabled/unsupported channels, undeclared mounts,
 * and out-of-range pages/reads.
 */
class SafLiveChannelReaderTest {

    private data class Harness(
        var defs: List<ChannelDefinition>,
        var descriptors: Map<String, ChannelImplementationDescriptor?> = emptyMap(),
        var sessionActive: Boolean = true,
    ) {
        val contentResolver: ContentResolver = mockk(relaxed = true)
        val bindings: MountBindingStore = mockk(relaxed = true)
        val grants: SafGrantController = mockk(relaxed = true)

        fun reader(): SafLiveChannelReader = SafLiveChannelReader(
            contentResolver = contentResolver,
            bindings = bindings,
            grants = grants,
            definitions = { defs },
            descriptor = { id -> descriptors[id.value] },
            sessionIsActive = { sessionActive },
        )
    }

    private fun definition(
        id: String = "ch1",
        enabled: Boolean = true,
        implementation: String = "pkg:journal",
    ) = ChannelDefinition(
        id = id,
        name = "Journal $id",
        implementationId = ChannelImplementationId(implementation),
        enabled = enabled,
        configSchemaVersion = 1,
        configPayload = OpaqueJsonObject.fromJsonObject(JSONObject()),
    )

    private fun descriptorWithMount(mountId: String = "notes"): ChannelImplementationDescriptor {
        val mocked = mockk<ChannelImplementationDescriptor>()
        every { mocked.resourceDeclarations } returns PackageResourcesDeclaration(
            listOf(
                PackageMountDeclaration(
                    id = mountId,
                    kind = PackageMountKind.DIRECTORY_TREE,
                    access = PackageMountAccess.READ_WRITE,
                    required = true,
                    label = "Notes",
                    help = null,
                ),
            ),
        )
        return mocked
    }

    private fun code(result: JSONObject): String =
        result.getJSONObject("error").getString("code")

    // -- mounts ----------------------------------------------------------

    @Test
    fun mountsEmptyWhenSessionClosed() = runTest {
        val harness = Harness(
            defs = listOf(definition()),
            descriptors = mapOf("pkg:journal" to descriptorWithMount()),
            sessionActive = false,
        )
        assertEquals(0, harness.reader().mounts("ch1").length())
    }

    @Test
    fun mountsEmptyForUnknownDisabledAndUnsupportedChannels() = runTest {
        val unsupported = definition("ch9", implementation = "pkg:ghost")
        val harness = Harness(
            defs = listOf(definition("ch1"), definition("ch2", enabled = false), unsupported),
            descriptors = mapOf("pkg:journal" to descriptorWithMount()),
        )
        val reader = harness.reader()
        assertEquals(0, reader.mounts("missing").length())
        assertEquals(0, reader.mounts("ch2").length())
        // Unsupported honestly returns empty, never fake content.
        assertEquals(0, reader.mounts("ch9").length())
    }

    @Test
    fun mountsReturnsDeclaredNamesForSupportedChannel() = runTest {
        val harness = Harness(
            defs = listOf(definition()),
            descriptors = mapOf("pkg:journal" to descriptorWithMount("notes")),
        )
        val mounts = harness.reader().mounts("ch1")
        assertEquals(1, mounts.length())
        assertEquals("notes", mounts.getJSONObject(0).getString("mount_id"))
        assertEquals("Notes", mounts.getJSONObject(0).getString("label"))
    }

    // -- list gating (pre-SAF) --------------------------------------------

    @Test
    fun listRefusesStaleSessionUnknownDisabledAndUndeclaredMount() = runTest {
        val harness = Harness(
            defs = listOf(definition("ch1"), definition("ch2", enabled = false)),
            descriptors = mapOf("pkg:journal" to descriptorWithMount("notes")),
        )
        val reader = harness.reader()
        harness.sessionActive = false
        assertEquals(
            "session_closed",
            code(reader.list("ch1", "notes", "", 10, null)),
        )
        harness.sessionActive = true
        assertEquals("unknown_channel", code(reader.list("ghost", "notes", "", 10, null)))
        assertEquals("channel_disabled", code(reader.list("ch2", "notes", "", 10, null)))
        // Undeclared mount and unsupported channel never reach a provider.
        assertEquals("unknown_mount", code(reader.list("ch1", "ghost-mount", "", 10, null)))
        val ghostHarness = Harness(
            defs = listOf(definition("ch9", implementation = "pkg:ghost")),
            descriptors = emptyMap(),
        )
        assertEquals("unknown_mount", code(ghostHarness.reader().list("ch9", "notes", "", 10, null)))
    }

    @Test
    fun listRejectsOutOfRangePageWithoutProviderEffect() = runTest {
        val harness = Harness(
            defs = listOf(definition()),
            descriptors = mapOf("pkg:journal" to descriptorWithMount()),
        )
        val reader = harness.reader()
        assertEquals("invalid_limit", code(reader.list("ch1", "notes", "", 0, null)))
        assertEquals("invalid_limit", code(reader.list("ch1", "notes", "", 51, null)))
        assertEquals("invalid_cursor", code(reader.list("ch1", "notes", "", 10, "")))
    }

    // -- read gating (pre-SAF) --------------------------------------------

    @Test
    fun readRefusesStaleSessionUnknownDisabledAndUndeclaredMount() = runTest {
        val harness = Harness(
            defs = listOf(definition("ch1"), definition("ch2", enabled = false)),
            descriptors = mapOf("pkg:journal" to descriptorWithMount("notes")),
        )
        val reader = harness.reader()
        harness.sessionActive = false
        assertEquals("session_closed", code(reader.read("ch1", "notes", "a.txt", 100)))
        harness.sessionActive = true
        assertEquals("unknown_channel", code(reader.read("ghost", "notes", "a.txt", 100)))
        assertEquals("channel_disabled", code(reader.read("ch2", "notes", "a.txt", 100)))
        assertEquals("unknown_mount", code(reader.read("ch1", "ghost-mount", "a.txt", 100)))
    }

    @Test
    fun readRejectsOutOfRangeBytesAndEmptyPath() = runTest {
        val harness = Harness(
            defs = listOf(definition()),
            descriptors = mapOf("pkg:journal" to descriptorWithMount()),
        )
        val reader = harness.reader()
        assertEquals("invalid_max_bytes", code(reader.read("ch1", "notes", "a.txt", 0)))
        assertEquals("invalid_max_bytes", code(reader.read("ch1", "notes", "a.txt", 32769)))
        assertEquals("invalid_path", code(reader.read("ch1", "notes", "", 100)))
    }

    @Test
    fun closedReaderRefusesNewOperationsEvenWhileSessionRemainsActive() = runTest {
        val harness = Harness(
            defs = listOf(definition()),
            descriptors = mapOf("pkg:journal" to descriptorWithMount()),
        )
        val reader = harness.reader()
        reader.close()
        reader.close()
        assertEquals(0, reader.mounts("ch1").length())
        assertEquals("session_closed", code(reader.list("ch1", "notes", "", 10, null)))
        assertEquals("session_closed", code(reader.read("ch1", "notes", "a.txt", 100)))
    }
}
