package io.talkcan.secret

import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.security.MessageDigest
import java.util.Arrays
import java.util.Base64
import java.util.concurrent.Semaphore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Android Keystore-backed [ProtectedSecretStore]. The AES key lives in the hardware-backed
 * keystore; [preferences] stores only AES-GCM ciphertext and IV, keyed by a SHA-256-derived
 * opaque reference. Plaintext never enters metadata, logs, or diagnostics.
 *
 * Extracted from the legacy OpenAI bearer credential store. The legacy store delegates here
 * with its original preferences name and key alias, preserving persisted identity exactly.
 */
class AndroidKeystoreProtectedSecretStore(
    private val preferences: SharedPreferences,
    private val keyAlias: String,
    private val maxSecretBytes: Int = DEFAULT_MAX_SECRET_BYTES,
    private val maxConcurrentOperations: Int = DEFAULT_MAX_CONCURRENT_OPERATIONS,
    private val resolveKey: (alias: String) -> SecretKey = ::androidKeystoreKey,
) : ProtectedSecretStore {

    private val operationGate = Semaphore(maxConcurrentOperations)

    override fun prepareCreate(
        reference: ProtectedSecretReference,
        plaintext: CharSequence,
    ): ProtectedSecretResult<PreparedSecretMutation> = when (val result = writeSecret(reference, plaintext)) {
        is ProtectedSecretResult.Failure -> result
        is ProtectedSecretResult.Success -> ProtectedSecretResult.Success(CreatedMutation(this, reference))
    }

    override fun prepareReplace(
        oldReference: ProtectedSecretReference,
        newReference: ProtectedSecretReference,
        plaintext: CharSequence,
    ): ProtectedSecretResult<PreparedSecretMutation> = when (val result = writeSecret(newReference, plaintext)) {
        is ProtectedSecretResult.Failure -> result
        is ProtectedSecretResult.Success -> ProtectedSecretResult.Success(ReplacedMutation(this, oldReference, newReference))
    }

    override fun prepareClear(reference: ProtectedSecretReference): ProtectedSecretResult<PreparedSecretMutation> =
        prepareRemoval(reference)

    override fun prepareDelete(reference: ProtectedSecretReference): ProtectedSecretResult<PreparedSecretMutation> =
        prepareRemoval(reference)

    override fun contains(reference: ProtectedSecretReference): Boolean =
        preferences.contains(referenceKey(reference))

    override fun <T> use(
        reference: ProtectedSecretReference,
        block: (CharSequence) -> T,
    ): ProtectedSecretResult<T> {
        if (!operationGate.tryAcquire()) {
            return ProtectedSecretResult.Failure(ProtectedSecretError.TooManyConcurrentOperations)
        }
        try {
            val stored = preferences.getString(referenceKey(reference), null)
                ?: return ProtectedSecretResult.Failure(ProtectedSecretError.NotFound)
            return try {
                val (iv, ciphertext) = decode(stored)
                var decrypted: ByteArray? = null
                try {
                    val cipher = Cipher.getInstance(TRANSFORMATION)
                    cipher.init(Cipher.DECRYPT_MODE, resolveKey(keyAlias), GCMParameterSpec(GCM_TAG_BITS, iv))
                    decrypted = cipher.doFinal(ciphertext)
                    if (decrypted.size > maxSecretBytes) {
                        return ProtectedSecretResult.Failure(ProtectedSecretError.TooLarge)
                    }
                    val plaintext = decodeUtf8Strict(decrypted)
                        ?: return ProtectedSecretResult.Failure(ProtectedSecretError.InvalidUtf8)
                    ProtectedSecretResult.Success(block(plaintext))
                } finally {
                    decrypted?.let { Arrays.fill(it, 0) }
                }
            } catch (_: IllegalArgumentException) {
                ProtectedSecretResult.Failure(ProtectedSecretError.CorruptValue)
            } catch (_: Exception) {
                ProtectedSecretResult.Failure(ProtectedSecretError.StorageUnavailable)
            }
        } finally {
            operationGate.release()
        }
    }

    private fun prepareRemoval(reference: ProtectedSecretReference): ProtectedSecretResult<PreparedSecretMutation> {
        val key = referenceKey(reference)
        val retained = preferences.getString(key, null)
        if (retained == null) return ProtectedSecretResult.Success(RemovedMutation(this, key, null))
        if (!preferences.edit().remove(key).commit()) {
            return ProtectedSecretResult.Failure(ProtectedSecretError.StorageUnavailable)
        }
        return ProtectedSecretResult.Success(RemovedMutation(this, key, retained))
    }

    private fun writeSecret(
        reference: ProtectedSecretReference,
        plaintext: CharSequence,
    ): ProtectedSecretResult<Unit> {
        val value = plaintext.toString()
        if (value.isBlank()) return ProtectedSecretResult.Failure(ProtectedSecretError.InvalidValue)
        val utf8 = value.toByteArray(StandardCharsets.UTF_8)
        if (utf8.size > maxSecretBytes) {
            Arrays.fill(utf8, 0)
            return ProtectedSecretResult.Failure(ProtectedSecretError.TooLarge)
        }
        if (!operationGate.tryAcquire()) {
            Arrays.fill(utf8, 0)
            return ProtectedSecretResult.Failure(ProtectedSecretError.TooManyConcurrentOperations)
        }
        try {
            return try {
                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(Cipher.ENCRYPT_MODE, resolveKey(keyAlias))
                val encrypted = cipher.doFinal(utf8)
                val record = encode(cipher.iv, encrypted)
                if (!preferences.edit().putString(referenceKey(reference), record).commit()) {
                    ProtectedSecretResult.Failure(ProtectedSecretError.StorageUnavailable)
                } else {
                    ProtectedSecretResult.Success(Unit)
                }
            } catch (_: Exception) {
                ProtectedSecretResult.Failure(ProtectedSecretError.StorageUnavailable)
            }
        } finally {
            Arrays.fill(utf8, 0)
            operationGate.release()
        }
    }

    internal fun deleteRecord(reference: ProtectedSecretReference): ProtectedSecretResult<Unit> = try {
        if (!preferences.edit().remove(referenceKey(reference)).commit()) {
            ProtectedSecretResult.Failure(ProtectedSecretError.StorageUnavailable)
        } else {
            ProtectedSecretResult.Success(Unit)
        }
    } catch (_: Exception) {
        ProtectedSecretResult.Failure(ProtectedSecretError.StorageUnavailable)
    }

    internal fun restoreRecord(key: String, record: String): ProtectedSecretResult<Unit> = try {
        if (!preferences.edit().putString(key, record).commit()) {
            ProtectedSecretResult.Failure(ProtectedSecretError.StorageUnavailable)
        } else {
            ProtectedSecretResult.Success(Unit)
        }
    } catch (_: Exception) {
        ProtectedSecretResult.Failure(ProtectedSecretError.StorageUnavailable)
    }

    internal fun referenceKey(reference: ProtectedSecretReference): String =
        "credential." + MessageDigest.getInstance("SHA-256")
            .digest(reference.token.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun encode(iv: ByteArray, ciphertext: ByteArray): String =
        Base64.getEncoder().encodeToString(iv) + "." + Base64.getEncoder().encodeToString(ciphertext)

    private fun decode(record: String): Pair<ByteArray, ByteArray> {
        val components = record.split('.', limit = 2)
        require(components.size == 2)
        return Base64.getDecoder().decode(components[0]) to Base64.getDecoder().decode(components[1])
    }

    private fun decodeUtf8Strict(bytes: ByteArray): String? = try {
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (_: Exception) {
        null
    }

    private class CreatedMutation(
        private val store: AndroidKeystoreProtectedSecretStore,
        private val reference: ProtectedSecretReference,
    ) : PreparedSecretMutation {
        override fun commit(): ProtectedSecretResult<Unit> = ProtectedSecretResult.Success(Unit)
        override fun rollback(): ProtectedSecretResult<Unit> = store.deleteRecord(reference)
    }

    private class ReplacedMutation(
        private val store: AndroidKeystoreProtectedSecretStore,
        private val oldReference: ProtectedSecretReference,
        private val newReference: ProtectedSecretReference,
    ) : PreparedSecretMutation {
        override fun commit(): ProtectedSecretResult<Unit> = store.deleteRecord(oldReference)
        override fun rollback(): ProtectedSecretResult<Unit> = store.deleteRecord(newReference)
    }

    private class RemovedMutation(
        private val store: AndroidKeystoreProtectedSecretStore,
        private val key: String,
        private val retainedRecord: String?,
    ) : PreparedSecretMutation {
        override fun commit(): ProtectedSecretResult<Unit> = ProtectedSecretResult.Success(Unit)
        override fun rollback(): ProtectedSecretResult<Unit> =
            if (retainedRecord != null) store.restoreRecord(key, retainedRecord)
            else ProtectedSecretResult.Success(Unit)
    }

    companion object {
        const val DEFAULT_MAX_SECRET_BYTES = 65_536
        const val DEFAULT_MAX_CONCURRENT_OPERATIONS = 8
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128

        fun androidKeystoreKey(alias: String): SecretKey {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }
            return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).apply {
                init(
                    KeyGenParameterSpec.Builder(
                        alias,
                        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                    )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setRandomizedEncryptionRequired(true)
                        .build(),
                )
            }.generateKey()
        }
    }
}
