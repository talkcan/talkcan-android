package io.talkcan.storage

import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Mount-lease registry and lifecycle tests (task 4.2): unforgeable opaque identity, exact live
 * owner resolution, per-operation revalidation, and foreign/stale/revoked/closed rejection. No
 * platform value or grant bytes may reach a public handle or its toString.
 */
class MountLeaseRegistryTest {

    private fun backend() = InMemoryDocumentTreeBackend()

    private fun registry(
        mount: ResolvedMount? = inMemoryMount(backend()),
        revalidator: MountLeaseRevalidator = MountLeaseRevalidator { FilesystemOutcome.Success(Unit) },
        owner: LeaseOwner = LeaseOwner("state-1", "instance-1", 1),
    ): MountLeaseRegistry = MountLeaseRegistry(owner, MutableResolver(mount), revalidator)

    private fun MountLeaseRegistry.openOk(): MountHandle =
        (open("output") as FilesystemOutcome.Success).value

    private fun code(outcome: FilesystemOutcome<*>): FilesystemErrorCode =
        (outcome as FilesystemOutcome.Failure).error.code

    // -- Opaque, unforgeable identity --------------------------------------

    @Test
    fun openMintsDistinctUnforgeableTokens() {
        val reg = registry()
        val a = reg.openOk()
        val b = reg.openOk()
        assertNotEquals(a.leaseToken, b.leaseToken)
        // 24 random bytes -> 48 hex chars of entropy.
        assertEquals(48, a.leaseToken.length)
        assertTrue(a.leaseToken.all { it in '0'..'9' || it in 'a'..'f' })
    }

    @Test
    fun handleToStringLeaksNoTokenGrantOrPlatformDetail() {
        val resolved = inMemoryMount(backend(), token = "output-token", grantFingerprint = "secret-fingerprint")
        val reg = registry(resolved)
        val handle = reg.openOk()
        val text = handle.toString()
        assertFalse("token leaked: $text", text.contains(handle.leaseToken))
        assertFalse("fingerprint leaked: $text", text.contains("secret-fingerprint"))
        assertFalse("mount token leaked: $text", text.contains("output-token"))
        for (secret in listOf("content://", "/storage", "emulated", "SAF", "document/42")) {
            assertFalse("platform detail leaked <$secret>: $text", text.contains(secret))
        }
    }

    @Test
    fun grantFingerprintIsOpaqueStableAndCollisionResistant() {
        val grant = "content://secret/tree/42".toByteArray(StandardCharsets.UTF_8)
        val fingerprint = MountGrantFingerprint.of(grant)
        // Opaque: not the grant bytes, fixed SHA-256 hex width.
        assertNotEquals("content://secret/tree/42", fingerprint)
        assertEquals(64, fingerprint.length)
        // Stable for identical bytes, distinct for different bytes.
        assertEquals(fingerprint, MountGrantFingerprint.of(grant.copyOf()))
        assertNotEquals(fingerprint, MountGrantFingerprint.of("content://secret/tree/43".toByteArray()))
    }

    // -- Resolution under exact live owner ---------------------------------

    @Test
    fun resolveForOperationReturnsTheLiveResolvedMount() {
        val resolved = inMemoryMount(backend())
        val reg = registry(resolved)
        val handle = reg.openOk()
        val looked = reg.resolveForOperation(handle.leaseToken) as FilesystemOutcome.Success
        assertEquals(resolved.mountToken, looked.value.mountToken)
        assertEquals(resolved.declarationId, looked.value.declarationId)
    }

    @Test
    fun unknownOrForgedTokenIsRejectedStale() {
        val reg = registry()
        reg.openOk()
        assertEquals(FilesystemErrorCode.E_STALE, code(reg.resolveForOperation("forged-token")))
    }

    @Test
    fun foreignOwnerHandleIsRejectedStale() {
        val regA = registry(owner = LeaseOwner("state-A", "instance-A", 1))
        val regB = registry(owner = LeaseOwner("state-B", "instance-B", 1))
        val handleA = regA.openOk()
        // A's token is meaningless to B's registry: foreign handle, no storage access.
        assertEquals(FilesystemErrorCode.E_STALE, code(regB.resolveForOperation(handleA.leaseToken)))
    }

    // -- Revocation --------------------------------------------------------

    @Test
    fun revokedLeasesMapEachSourceToItsPortableCode() {
        val cases = listOf(
            MountRevocationSource.GRANT_REVOKED to FilesystemErrorCode.E_REAUTHORIZATION_REQUIRED,
            MountRevocationSource.ACCESS_CHANGED to FilesystemErrorCode.E_READ_ONLY,
            MountRevocationSource.BINDING_REPLACED to FilesystemErrorCode.E_STALE,
            MountRevocationSource.GENERATION_CLOSED to FilesystemErrorCode.E_CLOSED,
            MountRevocationSource.INSTANCE_REMOVED to FilesystemErrorCode.E_CLOSED,
        )
        for ((source, expected) in cases) {
            val reg = registry()
            val handle = reg.openOk()
            assertTrue(reg.revoke(handle.leaseToken, source))
            assertEquals("$source", expected, code(reg.resolveForOperation(handle.leaseToken)))
        }
    }

    @Test
    fun revocationIsExactOnce() {
        val reg = registry()
        val handle = reg.openOk()
        assertTrue(reg.revoke(handle.leaseToken, MountRevocationSource.GRANT_REVOKED))
        // Second revocation is a no-op: the first source is retained.
        assertFalse(reg.revoke(handle.leaseToken, MountRevocationSource.INSTANCE_REMOVED))
        assertEquals(FilesystemErrorCode.E_REAUTHORIZATION_REQUIRED, code(reg.resolveForOperation(handle.leaseToken)))
    }

    @Test
    fun revokeAllRevokesEveryActiveLease() {
        val reg = registry()
        val a = reg.openOk()
        val b = reg.openOk()
        assertEquals(2, reg.revokeAll(MountRevocationSource.BINDING_REPLACED))
        assertEquals(FilesystemErrorCode.E_STALE, code(reg.resolveForOperation(a.leaseToken)))
        assertEquals(FilesystemErrorCode.E_STALE, code(reg.resolveForOperation(b.leaseToken)))
        // Already-revoked leases are not transitioned again.
        assertEquals(0, reg.revokeAll(MountRevocationSource.BINDING_REPLACED))
    }

    // -- Close -------------------------------------------------------------

    @Test
    fun closedRegistryRejectsResolutionAndNewOpens() {
        val reg = registry()
        val handle = reg.openOk()
        reg.close()
        assertTrue(reg.isClosed)
        assertEquals(FilesystemErrorCode.E_CLOSED, code(reg.resolveForOperation(handle.leaseToken)))
        assertEquals(FilesystemErrorCode.E_CLOSED, code(reg.open("output")))
    }

    @Test
    fun closeIsIdempotent() {
        val reg = registry()
        val handle = reg.openOk()
        reg.close()
        reg.close()
        assertEquals(FilesystemErrorCode.E_CLOSED, code(reg.resolveForOperation(handle.leaseToken)))
    }

    // -- Generation ownership ----------------------------------------------

    @Test
    fun predecessorGenerationLeaseIsRejectedClosed() {
        val reg = registry()
        val predecessor = reg.openOk()
        reg.advanceGeneration(2)
        assertEquals(2L, reg.generation)
        assertEquals(FilesystemErrorCode.E_CLOSED, code(reg.resolveForOperation(predecessor.leaseToken)))
        // A freshly opened lease binds to the new generation and resolves.
        val successor = reg.openOk()
        assertTrue(reg.resolveForOperation(successor.leaseToken) is FilesystemOutcome.Success)
    }

    // -- Per-operation revalidation ----------------------------------------

    @Test
    fun revalidationFailureRejectsTheOperation() {
        val revalidator = MutableRevalidator()
        val reg = registry(revalidator = revalidator)
        val handle = reg.openOk()
        // Opening does not revalidate; the per-operation check does.
        revalidator.failure = FilesystemError(FilesystemErrorCode.E_REAUTHORIZATION_REQUIRED, "grant revoked")
        assertEquals(FilesystemErrorCode.E_REAUTHORIZATION_REQUIRED, code(reg.resolveForOperation(handle.leaseToken)))
        // Restoring live status makes the same lease usable again.
        revalidator.failure = null
        assertTrue(reg.resolveForOperation(handle.leaseToken) is FilesystemOutcome.Success)
        assertTrue(revalidator.calls >= 2)
    }

    @Test
    fun revalidationSeesTheLeaseFacts() {
        var seen: MountLeaseFacts? = null
        val resolved = inMemoryMount(backend(), grantFingerprint = "fp-xyz")
        val reg = registry(
            mount = resolved,
            revalidator = MountLeaseRevalidator { facts -> seen = facts; FilesystemOutcome.Success(Unit) },
        )
        val handle = reg.openOk()
        reg.resolveForOperation(handle.leaseToken)
        val facts = seen!!
        assertEquals("output", facts.declarationId)
        assertEquals("instance-1", facts.instanceId)
        assertEquals(1L, facts.generation)
        assertEquals(MountAccessMode.READ_WRITE, facts.access)
        assertEquals("fp-xyz", facts.grantFingerprint)
    }

    @Test
    fun revalidatorExceptionCollapsesToIo() {
        val reg = registry(
            revalidator = MountLeaseRevalidator { throw RuntimeException("content://secret SAF") },
        )
        val handle = reg.openOk()
        assertEquals(FilesystemErrorCode.E_IO, code(reg.resolveForOperation(handle.leaseToken)))
    }

    @Test
    fun publicationCheckMirrorsLiveness() {
        val reg = registry()
        val handle = reg.openOk()
        assertTrue(reg.publicationCheck(handle.leaseToken) is FilesystemOutcome.Success)
        reg.revoke(handle.leaseToken, MountRevocationSource.GRANT_REVOKED)
        assertEquals(FilesystemErrorCode.E_REAUTHORIZATION_REQUIRED, code(reg.publicationCheck(handle.leaseToken)))
    }

    // -- Resolver normalization at open ------------------------------------

    @Test
    fun resolverFailureNormalizesAtOpen() {
        val reg = registry(mount = null)
        assertEquals(FilesystemErrorCode.E_CAPABILITY_UNDECLARED, code(reg.open("output")))
    }

    @Test
    fun resolverExceptionCollapsesToIoWithoutLeakage() {
        val reg = MountLeaseRegistry(
            LeaseOwner("state-1", "instance-1", 1),
            object : MountResolver {
                override fun resolve(declarationId: String): MountResolution =
                    throw RuntimeException("content://secret/document/42 at /storage/emulated/0 SAF")
            },
        )
        val error = (reg.open("output") as FilesystemOutcome.Failure).error
        assertEquals(FilesystemErrorCode.E_IO, error.code)
        val reason = error.reason ?: ""
        for (secret in listOf("content://", "/storage", "SAF", "document/42")) {
            assertFalse("leaked <$secret>: $reason", reason.contains(secret))
        }
    }
}
