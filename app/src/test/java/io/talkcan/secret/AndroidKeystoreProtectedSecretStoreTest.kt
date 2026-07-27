package io.talkcan.secret

import android.content.SharedPreferences
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests [AndroidKeystoreProtectedSecretStore] encryption, encoding, bounds, concurrency,
 * and transactional behavior using a JVM-generated AES key and in-memory SharedPreferences.
 * Verifies persisted-key compatibility with the legacy SHA-256 derivation and ciphertext format.
 */
class AndroidKeystoreProtectedSecretStoreTest {
    private val testKey: SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    private fun store(
        preferences: InMemorySharedPreferences = InMemorySharedPreferences(),
        maxSecretBytes: Int = AndroidKeystoreProtectedSecretStore.DEFAULT_MAX_SECRET_BYTES,
        maxConcurrentOperations: Int = AndroidKeystoreProtectedSecretStore.DEFAULT_MAX_CONCURRENT_OPERATIONS,
    ): AndroidKeystoreProtectedSecretStore = AndroidKeystoreProtectedSecretStore(
        preferences = preferences,
        keyAlias = "test.alias.v1",
        maxSecretBytes = maxSecretBytes,
        maxConcurrentOperations = maxConcurrentOperations,
        resolveKey = { testKey },
    )

    @Test
    fun aesGcmRoundTripsPlaintext() {
        val store = store()
        val ref = ProtectedSecretReference("round-trip-ref")

        assertEquals(ProtectedSecretResult.Success(Unit), requireMutation(store.prepareCreate(ref, "sk-live-abc123")).commit())
        assertEquals(ProtectedSecretResult.Success("sk-live-abc123"), store.use(ref) { it.toString() })
    }

    @Test
    fun unicodeSecretRoundTripsThroughAesGcm() {
        val store = store()
        val ref = ProtectedSecretReference("unicode-ref")
        val value = "cl\u00e9-\u79d8\u5bc6-\ud83d\udd10"

        requireMutation(store.prepareCreate(ref, value)).commit()
        assertEquals(ProtectedSecretResult.Success(value), store.use(ref) { it.toString() })
    }

    @Test
    fun storedRecordIsCiphertextNotPlaintext() {
        val preferences = InMemorySharedPreferences()
        val store = store(preferences)
        val ref = ProtectedSecretReference("ciphertext-ref")
        val secret = "sk-super-secret-plaintext"

        requireMutation(store.prepareCreate(ref, secret)).commit()

        val storedValues = preferences.allValues()
        assertEquals(1, storedValues.size)
        val storedRecord = storedValues.values.single()
        // The stored record must be Base64 IV + "." + Base64 ciphertext, never plaintext.
        assertFalse(storedRecord.contains(secret))
        assertTrue(storedRecord.contains("."))
        val parts = storedRecord.split(".", limit = 2)
        assertEquals(2, parts.size)
        // Both parts must be valid Base64.
        assertNotNull(Base64.getDecoder().decode(parts[0]))
        assertNotNull(Base64.getDecoder().decode(parts[1]))
    }

    @Test
    fun storedRecordDecryptsManuallyWithSameKey() {
        val preferences = InMemorySharedPreferences()
        val store = store(preferences)
        val ref = ProtectedSecretReference("manual-decrypt-ref")
        val secret = "sk-manual-decrypt"

        requireMutation(store.prepareCreate(ref, secret)).commit()

        val record = preferences.allValues().values.single()
        val parts = record.split(".", limit = 2)
        val iv = Base64.getDecoder().decode(parts[0])
        val ciphertext = Base64.getDecoder().decode(parts[1])
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, testKey, GCMParameterSpec(128, iv))
        val decrypted = String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8)
        assertEquals(secret, decrypted)
    }

    @Test
    fun referenceKeyMatchesLegacySha256Derivation() {
        val store = store()
        val ref = ProtectedSecretReference("openai-bearer-some-uuid")

        val expected = "credential." + MessageDigest.getInstance("SHA-256")
            .digest("openai-bearer-some-uuid".toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

        assertEquals(expected, store.referenceKey(ref))
    }

    @Test
    fun persistedKeyUsesLegacyCredentialPrefix() {
        val preferences = InMemorySharedPreferences()
        val store = store(preferences)
        val ref = ProtectedSecretReference("legacy-compat-ref")

        requireMutation(store.prepareCreate(ref, "token")).commit()

        val key = preferences.allValues().keys.single()
        assertTrue(key.startsWith("credential."))
        assertEquals("credential." + sha256Hex("legacy-compat-ref"), key)
    }

    @Test
    fun tamperedCiphertextFailsAsStorageUnavailableMatchingLegacy() {
        val preferences = InMemorySharedPreferences()
        val store = store(preferences)
        val ref = ProtectedSecretReference("corrupt-ref")
        requireMutation(store.prepareCreate(ref, "valid-token")).commit()

        // Tamper with the ciphertext portion.
        val key = preferences.allValues().keys.single()
        val record = preferences.allValues()[key]!!
        val parts = record.split(".", limit = 2)
        val corruptedCiphertext = Base64.getEncoder().encodeToString(
            Base64.getDecoder().decode(parts[1]).mapIndexed { i, b -> if (i == 0) (b.toInt() xor 0xFF).toByte() else b }.toByteArray(),
        )
        preferences.forcePut(key, parts[0] + "." + corruptedCiphertext)

        val result = store.use(ref) { it.toString() }
        assertTrue(result is ProtectedSecretResult.Failure)
        // GCM tag failure surfaces as a generic exception -> StorageUnavailable, exactly as the
        // legacy bearer store mapped it to ProtectedStorageUnavailable. Structural corruption
        // (malformed record / bad Base64) is the CorruptValue case, covered separately.
        assertEquals(ProtectedSecretError.StorageUnavailable, (result as ProtectedSecretResult.Failure).error)
    }

    @Test
    fun malformedRecordReturnsCorruptValue() {
        val preferences = InMemorySharedPreferences()
        val store = store(preferences)
        val ref = ProtectedSecretReference("malformed-ref")
        val key = store.referenceKey(ref)
        // A record without the "." separator.
        preferences.forcePut(key, "not-a-valid-record")

        val result = store.use(ref) { it.toString() }
        assertTrue(result is ProtectedSecretResult.Failure)
        assertEquals(ProtectedSecretError.CorruptValue, (result as ProtectedSecretResult.Failure).error)
    }

    @Test
    fun invalidBase64RecordReturnsCorruptValue() {
        val preferences = InMemorySharedPreferences()
        val store = store(preferences)
        val ref = ProtectedSecretReference("bad-base64-ref")
        val key = store.referenceKey(ref)
        preferences.forcePut(key, "!!!.@@@")

        val result = store.use(ref) { it.toString() }
        assertTrue(result is ProtectedSecretResult.Failure)
        assertEquals(ProtectedSecretError.CorruptValue, (result as ProtectedSecretResult.Failure).error)
    }

    @Test
    fun nonUtf8DecryptedBytesReturnInvalidUtf8() {
        val preferences = InMemorySharedPreferences()
        val store = store(preferences)
        val ref = ProtectedSecretReference("non-utf8-ref")
        val key = store.referenceKey(ref)

        // Encrypt invalid UTF-8 bytes directly with the test key.
        val invalidUtf8 = byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0x00, 0x80.toByte())
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, testKey)
        val encrypted = cipher.doFinal(invalidUtf8)
        val record = Base64.getEncoder().encodeToString(cipher.iv) + "." + Base64.getEncoder().encodeToString(encrypted)
        preferences.forcePut(key, record)

        val result = store.use(ref) { it.toString() }
        assertTrue(result is ProtectedSecretResult.Failure)
        assertEquals(ProtectedSecretError.InvalidUtf8, (result as ProtectedSecretResult.Failure).error)
    }

    @Test
    fun oversizedDecryptedValueReturnsTooLarge() {
        val preferences = InMemorySharedPreferences()
        val store = store(preferences, maxSecretBytes = 8)
        val ref = ProtectedSecretReference("oversized-read-ref")
        val key = store.referenceKey(ref)

        // Encrypt a value larger than maxSecretBytes directly (bypassing write-time check).
        val bigValue = "a".repeat(16).toByteArray(StandardCharsets.UTF_8)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, testKey)
        val encrypted = cipher.doFinal(bigValue)
        val record = Base64.getEncoder().encodeToString(cipher.iv) + "." + Base64.getEncoder().encodeToString(encrypted)
        preferences.forcePut(key, record)

        val result = store.use(ref) { it.toString() }
        assertTrue(result is ProtectedSecretResult.Failure)
        assertEquals(ProtectedSecretError.TooLarge, (result as ProtectedSecretResult.Failure).error)
    }

    @Test
    fun oversizedWriteIsRejectedWithTooLargeAtPrepare() {
        val store = store(maxSecretBytes = 8)
        val result = store.prepareCreate(ProtectedSecretReference("ref"), "a".repeat(9))

        assertTrue(result is ProtectedSecretResult.Failure)
        assertEquals(ProtectedSecretError.TooLarge, (result as ProtectedSecretResult.Failure).error)
    }

    @Test
    fun multiByteUtf8SizeEnforcedNotCharCount() {
        // 4 two-byte chars = 8 UTF-8 bytes; limit at 8 accepts, limit at 7 rejects.
        val value = "\u00e9\u00e9\u00e9\u00e9" // 8 bytes in UTF-8
        assertEquals(8, value.toByteArray(StandardCharsets.UTF_8).size)

        val acceptingStore = store(maxSecretBytes = 8)
        assertEquals(
            ProtectedSecretResult.Success(Unit),
            requireMutation(acceptingStore.prepareCreate(ProtectedSecretReference("ok"), value)).commit(),
        )

        val rejectingStore = store(maxSecretBytes = 7)
        val result = rejectingStore.prepareCreate(ProtectedSecretReference("no"), value)
        assertTrue(result is ProtectedSecretResult.Failure)
        assertEquals(ProtectedSecretError.TooLarge, (result as ProtectedSecretResult.Failure).error)
    }

    @Test
    fun blankValueRejectedWithInvalidValueAtPrepare() {
        val store = store()
        val result = store.prepareCreate(ProtectedSecretReference("ref"), "")
        assertTrue(result is ProtectedSecretResult.Failure)
        assertEquals(ProtectedSecretError.InvalidValue, (result as ProtectedSecretResult.Failure).error)
    }

    @Test
    fun missingReferenceReturnsNotFound() {
        val store = store()
        val result = store.use(ProtectedSecretReference("absent")) { it.toString() }
        assertTrue(result is ProtectedSecretResult.Failure)
        assertEquals(ProtectedSecretError.NotFound, (result as ProtectedSecretResult.Failure).error)
    }

    @Test
    fun concurrencyLimitRejectsExcessOperations() {
        val store = store(maxConcurrentOperations = 1)
        val ref1 = ProtectedSecretReference("ref-1")
        val ref2 = ProtectedSecretReference("ref-2")
        requireMutation(store.prepareCreate(ref1, "token-1")).commit()
        requireMutation(store.prepareCreate(ref2, "token-2")).commit()

        // Hold one permit inside a use() call, then attempt a second concurrent use().
        var innerResult: ProtectedSecretResult<String>? = null
        val outerResult = store.use(ref1) {
            innerResult = store.use(ref2) { inner -> inner.toString() }
            it.toString()
        }

        assertEquals(ProtectedSecretResult.Success("token-1"), outerResult)
        assertTrue(innerResult is ProtectedSecretResult.Failure)
        assertEquals(
            ProtectedSecretError.TooManyConcurrentOperations,
            (innerResult as ProtectedSecretResult.Failure).error,
        )
    }

    @Test
    fun createRollbackDeletesWrittenRecord() {
        val preferences = InMemorySharedPreferences()
        val store = store(preferences)
        val ref = ProtectedSecretReference("rollback-ref")

        val mutation = requireMutation(store.prepareCreate(ref, "token"))
        assertTrue(store.contains(ref))
        assertEquals(ProtectedSecretResult.Success(Unit), mutation.rollback())
        assertFalse(store.contains(ref))
        assertTrue(preferences.allValues().isEmpty())
    }

    @Test
    fun replaceRollbackRemovesNewAndPreservesOld() {
        val store = store()
        val oldRef = ProtectedSecretReference("old")
        val newRef = ProtectedSecretReference("new")
        requireMutation(store.prepareCreate(oldRef, "old-token")).commit()

        val mutation = requireMutation(store.prepareReplace(oldRef, newRef, "new-token"))
        assertEquals(ProtectedSecretResult.Success(Unit), mutation.rollback())

        assertTrue(store.contains(oldRef))
        assertFalse(store.contains(newRef))
        assertEquals(ProtectedSecretResult.Success("old-token"), store.use(oldRef) { it.toString() })
    }

    @Test
    fun replaceCommitRetiresOldReference() {
        val store = store()
        val oldRef = ProtectedSecretReference("old")
        val newRef = ProtectedSecretReference("new")
        requireMutation(store.prepareCreate(oldRef, "old-token")).commit()

        val mutation = requireMutation(store.prepareReplace(oldRef, newRef, "new-token"))
        assertEquals(ProtectedSecretResult.Success(Unit), mutation.commit())

        assertFalse(store.contains(oldRef))
        assertTrue(store.contains(newRef))
        assertEquals(ProtectedSecretResult.Success("new-token"), store.use(newRef) { it.toString() })
    }

    @Test
    fun clearRollbackRestoresEncryptedValue() {
        val store = store()
        val ref = ProtectedSecretReference("clear-ref")
        requireMutation(store.prepareCreate(ref, "restore-me")).commit()

        val mutation = requireMutation(store.prepareClear(ref))
        assertFalse(store.contains(ref))
        assertEquals(ProtectedSecretResult.Success(Unit), mutation.rollback())
        assertTrue(store.contains(ref))
        assertEquals(ProtectedSecretResult.Success("restore-me"), store.use(ref) { it.toString() })
    }

    @Test
    fun deleteRollbackRestoresEncryptedValue() {
        val store = store()
        val ref = ProtectedSecretReference("delete-ref")
        requireMutation(store.prepareCreate(ref, "restore-me")).commit()

        val mutation = requireMutation(store.prepareDelete(ref))
        assertFalse(store.contains(ref))
        assertEquals(ProtectedSecretResult.Success(Unit), mutation.rollback())
        assertEquals(ProtectedSecretResult.Success("restore-me"), store.use(ref) { it.toString() })
    }

    @Test
    fun useDoesNotRetainPlaintextInStoreState() {
        val preferences = InMemorySharedPreferences()
        val store = store(preferences)
        val ref = ProtectedSecretReference("no-retain-ref")
        val secret = "sk-ephemeral"
        requireMutation(store.prepareCreate(ref, secret)).commit()

        store.use(ref) { it.toString() }

        // After use(), the stored record is still ciphertext, never plaintext.
        for (value in preferences.allValues().values) {
            assertFalse(value.contains(secret))
        }
    }

    @Test
    fun failedWriteSurfacesAtPrepare() {
        val preferences = InMemorySharedPreferences()
        preferences.failCommits = true
        val store = store(preferences)

        val result = store.prepareCreate(ProtectedSecretReference("ref"), "token")
        assertTrue(result is ProtectedSecretResult.Failure)
        assertEquals(ProtectedSecretError.StorageUnavailable, (result as ProtectedSecretResult.Failure).error)
    }

    @Test
    fun deleteFailureReportsStorageUnavailableAtPrepare() {
        val preferences = InMemorySharedPreferences()
        val store = store(preferences)
        val ref = ProtectedSecretReference("del-fail-ref")
        requireMutation(store.prepareCreate(ref, "token")).commit()
        preferences.failCommits = true

        // prepareDelete reads the record (succeeds), then attempts removal (fails) at prepare time.
        val result = store.prepareDelete(ref)
        assertTrue(result is ProtectedSecretResult.Failure)
        assertEquals(ProtectedSecretError.StorageUnavailable, (result as ProtectedSecretResult.Failure).error)
    }

    private fun requireMutation(result: ProtectedSecretResult<PreparedSecretMutation>): PreparedSecretMutation =
        when (result) {
            is ProtectedSecretResult.Success -> result.value
            is ProtectedSecretResult.Failure -> throw AssertionError("Expected prepared mutation, got $result")
        }

    private fun sha256Hex(input: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    /** Minimal in-memory [SharedPreferences] supporting only the operations the store uses. */
    private class InMemorySharedPreferences : SharedPreferences {
        private val data = mutableMapOf<String, String>()
        var failCommits = false

        fun allValues(): Map<String, String> = data.toMap()

        fun forcePut(key: String, value: String) {
            data[key] = value
        }

        override fun getString(key: String, defValue: String?): String? = data[key] ?: defValue
        override fun contains(key: String): Boolean = key in data
        override fun edit(): SharedPreferences.Editor = Editor()

        private inner class Editor : SharedPreferences.Editor {
            private val puts = mutableMapOf<String, String>()
            private val removals = mutableSetOf<String>()

            override fun putString(key: String, value: String?): SharedPreferences.Editor {
                if (value != null) puts[key] = value else removals.add(key)
                return this
            }

            override fun remove(key: String): SharedPreferences.Editor {
                removals.add(key)
                return this
            }

            override fun commit(): Boolean {
                if (failCommits) return false
                removals.forEach { data.remove(it) }
                data.putAll(puts)
                return true
            }

            override fun clear(): SharedPreferences.Editor = throw UnsupportedOperationException()
            override fun putStringSet(key: String, values: MutableSet<String>?): SharedPreferences.Editor = throw UnsupportedOperationException()
            override fun putInt(key: String, value: Int): SharedPreferences.Editor = throw UnsupportedOperationException()
            override fun putLong(key: String, value: Long): SharedPreferences.Editor = throw UnsupportedOperationException()
            override fun putFloat(key: String, value: Float): SharedPreferences.Editor = throw UnsupportedOperationException()
            override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor = throw UnsupportedOperationException()
            override fun apply(): Unit = throw UnsupportedOperationException()
        }

        override fun getAll(): MutableMap<String, *> = throw UnsupportedOperationException()
        override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? = throw UnsupportedOperationException()
        override fun getInt(key: String, defValue: Int): Int = throw UnsupportedOperationException()
        override fun getLong(key: String, defValue: Long): Long = throw UnsupportedOperationException()
        override fun getFloat(key: String, defValue: Float): Float = throw UnsupportedOperationException()
        override fun getBoolean(key: String, defValue: Boolean): Boolean = throw UnsupportedOperationException()
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?): Unit = throw UnsupportedOperationException()
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?): Unit = throw UnsupportedOperationException()
    }
}
