package io.talkcan.ui

import io.talkcan.dependency.PackageMountAccess
import io.talkcan.dependency.PackageMountDeclaration
import io.talkcan.dependency.PackageMountKind
import io.talkcan.model.ChannelImplementationId
import io.talkcan.resource.MountAvailability
import io.talkcan.resource.MountBinding
import io.talkcan.resource.MountBindingState
import io.talkcan.resource.MountBindingStatus
import io.talkcan.resource.MountUnavailableReason
import io.talkcan.resource.PlatformGrantBlob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2.6: Focused JVM tests for the mount editor projection.
 * Verifies portable status, typed unavailability, and no platform leakage.
 */
class MountEditorProjectionTest {

    private val declaration1 = PackageMountDeclaration(
        id = "output",
        kind = PackageMountKind.DIRECTORY_TREE,
        access = PackageMountAccess.READ_WRITE,
        required = true,
        label = "Output directory",
        help = "Where to write journal files",
    )

    private val declaration2 = PackageMountDeclaration(
        id = "cache",
        kind = PackageMountKind.DIRECTORY_TREE,
        access = PackageMountAccess.READ_WRITE,
        required = true,
        label = "Cache directory",
        help = null,
    )

    private val implementationId = ChannelImplementationId("test:provider")

    private fun binding(
        declarationId: String,
        status: MountBindingStatus,
        state: MountBindingState = MountBindingState.ACTIVE,
    ): MountBinding = MountBinding(
        channelInstanceId = "instance-1",
        implementationId = implementationId,
        declarationId = declarationId,
        kind = PackageMountKind.DIRECTORY_TREE,
        access = PackageMountAccess.READ_WRITE,
        grant = PlatformGrantBlob("test-grant".toByteArray()),
        status = status,
        state = state,
    )

    @Test
    fun `projection maps available binding to available status`() {
        val entries = MountEditorProjection.entries(
            listOf(declaration1),
        ) { declarationId ->
            MountAvailability.Available(binding(declarationId, MountBindingStatus.AVAILABLE))
        }

        assertEquals(1, entries.size)
        val entry = entries[0]
        assertEquals("output", entry.declaration.declarationId)
        assertEquals("Output directory", entry.declaration.label)
        assertEquals("Where to write journal files", entry.declaration.help)
        assertTrue(entry.declaration.required)
        assertEquals(MountBindingStatus.AVAILABLE.portable, entry.statusPortable)
        assertFalse(entry.isBlocking)
    }

    @Test
    fun `projection maps unbound required mount to blocking unavailability`() {
        val entries = MountEditorProjection.entries(
            listOf(declaration1),
        ) { MountAvailability.Unavailable(MountUnavailableReason.Unbound) }

        assertEquals(1, entries.size)
        val entry = entries[0]
        assertEquals("unbound", entry.statusPortable)
        assertTrue(entry.isBlocking)
    }

    @Test
    fun `projection maps needs-reauthorization to portable status`() {
        val entries = MountEditorProjection.entries(
            listOf(declaration1),
        ) { MountAvailability.Unavailable(MountUnavailableReason.NeedsReauthorization) }

        assertEquals(MountBindingStatus.NEEDS_REAUTHORIZATION.portable, entries[0].statusPortable)
        assertTrue(entries[0].isBlocking)
    }

    @Test
    fun `projection maps read-only to portable status`() {
        val entries = MountEditorProjection.entries(
            listOf(declaration1),
        ) { MountAvailability.Unavailable(MountUnavailableReason.ReadOnly) }

        assertEquals(MountBindingStatus.READ_ONLY.portable, entries[0].statusPortable)
        assertTrue(entries[0].isBlocking)
    }

    @Test
    fun `projection maps unavailable grant to portable status`() {
        val entries = MountEditorProjection.entries(
            listOf(declaration1),
        ) { MountAvailability.Unavailable(MountUnavailableReason.GrantUnavailable) }

        assertEquals(MountBindingStatus.UNAVAILABLE.portable, entries[0].statusPortable)
        assertTrue(entries[0].isBlocking)
    }

    @Test
    fun `isBlocking honors the required flag at the presentation level`() {
        val unavailable = MountAvailability.Unavailable(MountUnavailableReason.Unbound)
        val available = MountAvailability.Available(binding("output", MountBindingStatus.AVAILABLE))
        val requiredPresentation = MountDeclarationPresentation("output", "Output", null, required = true)
        val optionalPresentation = MountDeclarationPresentation("cache", "Cache", null, required = false)

        assertTrue(MountEditorEntry(requiredPresentation, unavailable).isBlocking)
        assertFalse("optional declaration is never blocking", MountEditorEntry(optionalPresentation, unavailable).isBlocking)
        assertFalse(MountEditorEntry(requiredPresentation, available).isBlocking)
    }

    @Test
    fun `projection reports each declaration status independently`() {
        val entries = MountEditorProjection.entries(
            listOf(declaration1, declaration2),
        ) { declarationId ->
            when (declarationId) {
                "output" -> MountAvailability.Available(binding(declarationId, MountBindingStatus.AVAILABLE))
                "cache" -> MountAvailability.Unavailable(MountUnavailableReason.Unbound)
                else -> error("unexpected declaration")
            }
        }

        assertEquals(2, entries.size)
        assertEquals("output", entries[0].declaration.declarationId)
        assertFalse(entries[0].isBlocking)
        assertEquals("cache", entries[1].declaration.declarationId)
        assertTrue("unbound required mount is blocking", entries[1].isBlocking)
    }

    @Test
    fun `requiredResourceUnavailability returns null when all required mounts available`() {
        val entries = MountEditorProjection.entries(
            listOf(declaration1, declaration2),
        ) { declarationId ->
            MountAvailability.Available(binding(declarationId, MountBindingStatus.AVAILABLE))
        }

        val unavailability = MountEditorProjection.requiredResourceUnavailability(entries)
        assertNull(unavailability)
    }

    @Test
    fun `requiredResourceUnavailability reports blocking declaration IDs`() {
        val entries = MountEditorProjection.entries(
            listOf(declaration1, declaration2),
        ) { declarationId ->
            when (declarationId) {
                "output" -> MountAvailability.Unavailable(MountUnavailableReason.Unbound)
                "cache" -> MountAvailability.Unavailable(MountUnavailableReason.Unbound)
                else -> error("unexpected declaration")
            }
        }

        val unavailability = MountEditorProjection.requiredResourceUnavailability(entries)
        assertTrue(unavailability != null)
        assertEquals(listOf("output", "cache"), unavailability!!.blockingDeclarationIds)
    }

    @Test
    fun `presentation never exposes platform grant blob or URI`() {
        val entries = MountEditorProjection.entries(
            listOf(declaration1),
        ) { declarationId ->
            MountAvailability.Available(binding(declarationId, MountBindingStatus.AVAILABLE))
        }

        val entry = entries[0]
        // The presentation only carries declaration metadata and portable status.
        // No grant blob, URI, or path is accessible from MountEditorEntry.
        assertEquals("output", entry.declaration.declarationId)
        assertEquals("Output directory", entry.declaration.label)
        assertEquals("Where to write journal files", entry.declaration.help)
        assertTrue(entry.declaration.required)
        assertEquals("available", entry.statusPortable)
        // Type system enforces: MountEditorEntry has no grant/URI/path fields.
    }
}
