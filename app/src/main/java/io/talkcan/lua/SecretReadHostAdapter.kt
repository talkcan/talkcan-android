package io.talkcan.lua

import io.talkcan.secret.ProtectedSecretError
import io.talkcan.secret.ProtectedSecretResult
import io.talkcan.secret.ProtectedSecretStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Typed actor suspension for `talkcan.secrets.read` (task 8.4).
 *
 * Resolves only an opaque state-local reference token from the current
 * generation's [SecretReferenceTokenRegistry]; a guessed, foreign, or
 * predecessor token resolves to nothing and fails with the same nondisclosing
 * denial as an absent value. Plaintext is read through the protected store's
 * scoped callback, returned once inside the exact resume envelope
 * `{"plaintext": <utf8>}`, and never retained by this adapter. Every failure
 * maps to one normalized `E_*` code with no observability content.
 */
internal class SecretReadHostAdapter(
    private val store: ProtectedSecretStore,
    private val registry: SecretReferenceTokenRegistry,
) : TypedHostOperationAdapter {

    override suspend fun complete(claim: HostOperationClaim.Admitted): TypedHostCompletion {
        if (claim.kind != HostOperationKind.SECRET_READ) {
            return TypedHostCompletion.failure("E_DENIED")
        }
        val binding = registry.resolve(claim.referenceToken)
            ?: return TypedHostCompletion.failure("E_DENIED")
        val result = withContext(Dispatchers.IO) {
            store.use(binding.reference) { plaintext -> plaintext.toString() }
        }
        return when (result) {
            is ProtectedSecretResult.Success -> TypedHostCompletion(
                success = true,
                value = JSONObject().put("plaintext", result.value).toString(),
            )
            is ProtectedSecretResult.Failure ->
                TypedHostCompletion.failure(result.error.normalizedCode())
        }
    }
}

/** Nondisclosing normalization into the SECRET_READ allowlist (default E_STORAGE). */
internal fun ProtectedSecretError.normalizedCode(): String = when (this) {
    ProtectedSecretError.NotFound -> "E_NOT_FOUND"
    ProtectedSecretError.Denied -> "E_DENIED"
    ProtectedSecretError.TooLarge -> "E_TOO_LARGE"
    ProtectedSecretError.InvalidUtf8 -> "E_INVALID_VALUE"
    ProtectedSecretError.InvalidValue -> "E_INVALID_VALUE"
    ProtectedSecretError.StorageUnavailable -> "E_STORAGE"
    ProtectedSecretError.CorruptValue -> "E_STORAGE"
    ProtectedSecretError.TooManyConcurrentOperations -> "E_BUSY"
}
