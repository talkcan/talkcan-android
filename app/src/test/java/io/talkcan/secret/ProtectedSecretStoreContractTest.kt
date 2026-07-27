package io.talkcan.secret

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract tests for [ProtectedSecretStore] transactional semantics, error normalization,
 * and privacy invariants. Uses an in-memory implementation to verify the interface contract
 * independent of platform encryption.
 */
class ProtectedSecretStoreContractTest {
    @Test
    fun createCommitRoundTripsPlaintext() {
        val store = InMemoryProtectedSecretStore()
        val ref = ProtectedSecretReference("ref-1")

        val mutation = requireMutation(store.prepareCreate(ref, "sk-secret-value"))
        assertEquals(ProtectedSecretResult.Success(Unit), mutation.commit())

        val result = store.use(ref) { it.toString() }
        assertEquals(ProtectedSecretResult.Success("sk-secret-value"), result)
    }

    @Test
    fun createRollbackRemovesWrittenValue() {
        val store = InMemoryProtectedSecretStore()
        val ref = ProtectedSecretReference("ref-1")

        val mutation = requireMutation(store.prepareCreate(ref, "sk-secret-value"))
        assertTrue(store.contains(ref))
        assertEquals(ProtectedSecretResult.Success(Unit), mutation.rollback())
        assertFalse(store.contains(ref))
    }

    @Test
    fun unicodeRoundTripsExactly() {
        val store = InMemoryProtectedSecretStore()
        val ref = ProtectedSecretReference("ref-unicode")
        val value = "sk-\u00e9\u00e8\u00ea-\u4e16\u754c-\ud83d\udd11"

        requireMutation(store.prepareCreate(ref, value)).commit()
        val result = store.use(ref) { it.toString() }
        assertEquals(ProtectedSecretResult.Success(value), result)
    }

    @Test
    fun replaceCommitRetiresOldReferenceAndKeepsNew() {
        val store = InMemoryProtectedSecretStore()
        val oldRef = ProtectedSecretReference("ref-old")
        val newRef = ProtectedSecretReference("ref-new")
        create(store, oldRef, "old-token")

        val mutation = requireMutation(store.prepareReplace(oldRef, newRef, "new-token"))
        assertTrue(store.contains(oldRef))
        assertTrue(store.contains(newRef))

        assertEquals(ProtectedSecretResult.Success(Unit), mutation.commit())
        assertFalse(store.contains(oldRef))
        assertTrue(store.contains(newRef))
        assertEquals(ProtectedSecretResult.Success("new-token"), store.use(newRef) { it.toString() })
    }

    @Test
    fun replaceRollbackRemovesNewAndPreservesOld() {
        val store = InMemoryProtectedSecretStore()
        val oldRef = ProtectedSecretReference("ref-old")
        val newRef = ProtectedSecretReference("ref-new")
        create(store, oldRef, "old-token")

        val mutation = requireMutation(store.prepareReplace(oldRef, newRef, "new-token"))
        assertEquals(ProtectedSecretResult.Success(Unit), mutation.rollback())

        assertTrue(store.contains(oldRef))
        assertFalse(store.contains(newRef))
        assertEquals(ProtectedSecretResult.Success("old-token"), store.use(oldRef) { it.toString() })
    }

    @Test
    fun replaceCommitReportsCleanupFailureButNewValueRemains() {
        val store = InMemoryProtectedSecretStore()
        val oldRef = ProtectedSecretReference("ref-old")
        val newRef = ProtectedSecretReference("ref-new")
        create(store, oldRef, "old-token")
        store.failDeletes = true

        val mutation = requireMutation(store.prepareReplace(oldRef, newRef, "new-token"))
        val commitResult = mutation.commit()

        assertTrue(commitResult is ProtectedSecretResult.Failure)
        assertEquals(
            ProtectedSecretError.StorageUnavailable,
            (commitResult as ProtectedSecretResult.Failure).error,
        )
        // New value is committed despite old-reference cleanup failure.
        assertEquals(ProtectedSecretResult.Success("new-token"), store.use(newRef) { it.toString() })
    }

    @Test
    fun clearCommitRemovesValue() {
        val store = InMemoryProtectedSecretStore()
        val ref = ProtectedSecretReference("ref-1")
        create(store, ref, "token")

        val mutation = requireMutation(store.prepareClear(ref))
        assertFalse(store.contains(ref))
        assertEquals(ProtectedSecretResult.Success(Unit), mutation.commit())
        assertFalse(store.contains(ref))
    }

    @Test
    fun clearRollbackRestoresValue() {
        val store = InMemoryProtectedSecretStore()
        val ref = ProtectedSecretReference("ref-1")
        create(store, ref, "token")

        val mutation = requireMutation(store.prepareClear(ref))
        assertFalse(store.contains(ref))
        assertEquals(ProtectedSecretResult.Success(Unit), mutation.rollback())
        assertTrue(store.contains(ref))
        assertEquals(ProtectedSecretResult.Success("token"), store.use(ref) { it.toString() })
    }

    @Test
    fun deleteCommitRemovesValue() {
        val store = InMemoryProtectedSecretStore()
        val ref = ProtectedSecretReference("ref-1")
        create(store, ref, "token")

        val mutation = requireMutation(store.prepareDelete(ref))
        assertEquals(ProtectedSecretResult.Success(Unit), mutation.commit())
        assertFalse(store.contains(ref))
    }

    @Test
    fun deleteRollbackRestoresValue() {
        val store = InMemoryProtectedSecretStore()
        val ref = ProtectedSecretReference("ref-1")
        create(store, ref, "token")

        val mutation = requireMutation(store.prepareDelete(ref))
        assertEquals(ProtectedSecretResult.Success(Unit), mutation.rollback())
        assertTrue(store.contains(ref))
        assertEquals(ProtectedSecretResult.Success("token"), store.use(ref) { it.toString() })
    }

    @Test
    fun deleteOnAbsentReferenceSucceedsIdempotently() {
        val store = InMemoryProtectedSecretStore()
        val ref = ProtectedSecretReference("ref-absent")

        val mutation = requireMutation(store.prepareDelete(ref))
        assertEquals(ProtectedSecretResult.Success(Unit), mutation.commit())
        assertFalse(store.contains(ref))
    }

    @Test
    fun useOnAbsentReferenceReturnsNotFound() {
        val store = InMemoryProtectedSecretStore()
        val result = store.use(ProtectedSecretReference("ref-absent")) { it.toString() }

        assertTrue(result is ProtectedSecretResult.Failure)
        assertEquals(ProtectedSecretError.NotFound, (result as ProtectedSecretResult.Failure).error)
    }

    @Test
    fun blankValueIsRejectedWithInvalidValueWithoutTouchingStorage() {
        val store = InMemoryProtectedSecretStore()
        val result = store.prepareCreate(ProtectedSecretReference("ref-1"), "   ")

        assertTrue(result is ProtectedSecretResult.Failure)
        assertEquals(ProtectedSecretError.InvalidValue, (result as ProtectedSecretResult.Failure).error)
        assertFalse(store.contains(ProtectedSecretReference("ref-1")))
    }

    @Test
    fun oversizedValueIsRejectedWithTooLargeWithoutTouchingStorage() {
        val store = InMemoryProtectedSecretStore(maxSecretBytes = 16)
        val result = store.prepareCreate(ProtectedSecretReference("ref-1"), "a".repeat(17))

        assertTrue(result is ProtectedSecretResult.Failure)
        assertEquals(ProtectedSecretError.TooLarge, (result as ProtectedSecretResult.Failure).error)
        assertFalse(store.contains(ProtectedSecretReference("ref-1")))
    }

    @Test
    fun boundarySizedValueIsAccepted() {
        val store = InMemoryProtectedSecretStore(maxSecretBytes = 16)
        val value = "a".repeat(16)

        requireMutation(store.prepareCreate(ProtectedSecretReference("ref-1"), value)).commit()
        assertEquals(ProtectedSecretResult.Success(value), store.use(ProtectedSecretReference("ref-1")) { it.toString() })
    }

    @Test
    fun crossProfileReferenceReturnsNotFoundWithoutDisclosure() {
        val store = InMemoryProtectedSecretStore()
        create(store, ProtectedSecretReference("profile-a/field"), "secret-a")

        // Guessing another profile's reference returns NotFound, not Denied or any existence hint.
        val result = store.use(ProtectedSecretReference("profile-b/field")) { it.toString() }
        assertTrue(result is ProtectedSecretResult.Failure)
        assertEquals(ProtectedSecretError.NotFound, (result as ProtectedSecretResult.Failure).error)
    }

    @Test
    fun referenceTokensCarryNoAliasOrPlatformInformation() {
        val ref = ProtectedSecretReference("host-generated-uuid-token")
        // The token is opaque; toString exposes only the token itself, no alias/path.
        assertEquals("ProtectedSecretReference(token=host-generated-uuid-token)", ref.toString())
        assertFalse(ref.token.contains("AndroidKeyStore"))
        assertFalse(ref.token.contains("talkcan.openai"))
    }

    @Test
    fun referenceRejectsBlankToken() {
        var thrown = false
        try {
            ProtectedSecretReference("  ")
        } catch (_: IllegalArgumentException) {
            thrown = true
        }
        assertTrue(thrown)
    }

    @Test
    fun generationSecretGrantsBindFieldToReferenceWithoutAlias() {
        val grant = SecretFieldGrant(
            packageRepositoryId = 42L,
            profileId = "profile-1",
            fieldId = "api_key",
            profileRevision = 3L,
            reference = ProtectedSecretReference("opaque-token-1"),
        )
        val grants = GenerationSecretGrants(listOf(grant))

        assertEquals(grant, grants.grantFor("api_key"))
        assertNull(grants.grantFor("other_field"))
        assertEquals(setOf("api_key"), grants.fields)
        assertEquals(setOf(ProtectedSecretReference("opaque-token-1")), grants.references)
    }

    @Test
    fun generationSecretGrantsRejectDuplicateFieldIds() {
        val grantA = SecretFieldGrant(1L, "p1", "api_key", 1L, ProtectedSecretReference("ref-a"))
        val grantB = SecretFieldGrant(1L, "p1", "api_key", 1L, ProtectedSecretReference("ref-b"))
        var thrown = false
        try {
            GenerationSecretGrants(listOf(grantA, grantB))
        } catch (_: IllegalArgumentException) {
            thrown = true
        }
        assertTrue(thrown)
    }

    @Test
    fun writeFailureSurfacesAtPrepareBeforeMetadataCommit() {
        val store = InMemoryProtectedSecretStore()
        store.failWrites = true

        val result = store.prepareCreate(ProtectedSecretReference("ref-1"), "token")

        assertTrue(result is ProtectedSecretResult.Failure)
        assertEquals(ProtectedSecretError.StorageUnavailable, (result as ProtectedSecretResult.Failure).error)
        assertFalse(store.contains(ProtectedSecretReference("ref-1")))
    }

    private fun create(store: InMemoryProtectedSecretStore, ref: ProtectedSecretReference, value: String) {
        requireMutation(store.prepareCreate(ref, value)).commit()
    }

    private fun requireMutation(result: ProtectedSecretResult<PreparedSecretMutation>): PreparedSecretMutation =
        when (result) {
            is ProtectedSecretResult.Success -> result.value
            is ProtectedSecretResult.Failure -> throw AssertionError("Expected prepared mutation, got $result")
        }

    /**
     * In-memory [ProtectedSecretStore] for contract verification. Stores plaintext directly
     * (no encryption) to isolate transactional semantics from platform crypto.
     */
    private class InMemoryProtectedSecretStore(
        private val maxSecretBytes: Int = AndroidKeystoreProtectedSecretStore.DEFAULT_MAX_SECRET_BYTES,
    ) : ProtectedSecretStore {
        private val values = mutableMapOf<ProtectedSecretReference, String>()
        var failWrites = false
        var failDeletes = false

        override fun prepareCreate(
            reference: ProtectedSecretReference,
            plaintext: CharSequence,
        ): ProtectedSecretResult<PreparedSecretMutation> {
            val value = plaintext.toString()
            if (value.isBlank()) return ProtectedSecretResult.Failure(ProtectedSecretError.InvalidValue)
            if (value.toByteArray(Charsets.UTF_8).size > maxSecretBytes) return ProtectedSecretResult.Failure(ProtectedSecretError.TooLarge)
            if (failWrites) return ProtectedSecretResult.Failure(ProtectedSecretError.StorageUnavailable)
            values[reference] = value
            return ProtectedSecretResult.Success(CreatedMutation(this, reference))
        }

        override fun prepareReplace(
            oldReference: ProtectedSecretReference,
            newReference: ProtectedSecretReference,
            plaintext: CharSequence,
        ): ProtectedSecretResult<PreparedSecretMutation> {
            val value = plaintext.toString()
            if (value.isBlank()) return ProtectedSecretResult.Failure(ProtectedSecretError.InvalidValue)
            if (value.toByteArray(Charsets.UTF_8).size > maxSecretBytes) return ProtectedSecretResult.Failure(ProtectedSecretError.TooLarge)
            if (failWrites) return ProtectedSecretResult.Failure(ProtectedSecretError.StorageUnavailable)
            values[newReference] = value
            return ProtectedSecretResult.Success(ReplacedMutation(this, oldReference, newReference))
        }

        override fun prepareClear(reference: ProtectedSecretReference): ProtectedSecretResult<PreparedSecretMutation> {
            val retained = values.remove(reference)
            return ProtectedSecretResult.Success(RemovedMutation(this, reference, retained))
        }

        override fun prepareDelete(reference: ProtectedSecretReference): ProtectedSecretResult<PreparedSecretMutation> {
            val retained = values.remove(reference)
            return ProtectedSecretResult.Success(RemovedMutation(this, reference, retained))
        }

        override fun contains(reference: ProtectedSecretReference): Boolean = reference in values

        override fun <T> use(
            reference: ProtectedSecretReference,
            block: (CharSequence) -> T,
        ): ProtectedSecretResult<T> {
            val value = values[reference]
                ?: return ProtectedSecretResult.Failure(ProtectedSecretError.NotFound)
            return ProtectedSecretResult.Success(block(value))
        }

        fun deleteRef(reference: ProtectedSecretReference): ProtectedSecretResult<Unit> {
            if (failDeletes) return ProtectedSecretResult.Failure(ProtectedSecretError.StorageUnavailable)
            values.remove(reference)
            return ProtectedSecretResult.Success(Unit)
        }

        fun restoreRef(reference: ProtectedSecretReference, value: String): ProtectedSecretResult<Unit> {
            values[reference] = value
            return ProtectedSecretResult.Success(Unit)
        }

        private class CreatedMutation(
            private val store: InMemoryProtectedSecretStore,
            private val reference: ProtectedSecretReference,
        ) : PreparedSecretMutation {
            override fun commit(): ProtectedSecretResult<Unit> = ProtectedSecretResult.Success(Unit)
            override fun rollback(): ProtectedSecretResult<Unit> = store.deleteRef(reference)
        }

        private class ReplacedMutation(
            private val store: InMemoryProtectedSecretStore,
            private val oldReference: ProtectedSecretReference,
            private val newReference: ProtectedSecretReference,
        ) : PreparedSecretMutation {
            override fun commit(): ProtectedSecretResult<Unit> = store.deleteRef(oldReference)
            override fun rollback(): ProtectedSecretResult<Unit> = store.deleteRef(newReference)
        }

        private class RemovedMutation(
            private val store: InMemoryProtectedSecretStore,
            private val reference: ProtectedSecretReference,
            private val retained: String?,
        ) : PreparedSecretMutation {
            override fun commit(): ProtectedSecretResult<Unit> = ProtectedSecretResult.Success(Unit)
            override fun rollback(): ProtectedSecretResult<Unit> =
                if (retained != null) store.restoreRef(reference, retained)
                else ProtectedSecretResult.Success(Unit)
        }
    }
}
