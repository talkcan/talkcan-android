package io.talkcan.secret

/**
 * Opaque provider-neutral handle to one protected secret held outside profile metadata.
 * The token is host-generated and carries no keystore alias, storage path, or platform object.
 */
@JvmInline
value class ProtectedSecretReference(val token: String) {
    init {
        require(token.isNotBlank()) { "Protected secret reference must not be blank" }
    }
}

/**
 * Normalized protected-secret failures. No variant carries plaintext, keystore aliases,
 * platform objects, or information revealing whether an ungranted reference exists.
 */
sealed interface ProtectedSecretError {
    val message: String

    /** No value is available under the reference. Does not reveal whether the reference exists. */
    data object NotFound : ProtectedSecretError {
        override val message = "Protected secret is unavailable"
    }

    /** Access to the reference is denied by grant or capability policy. */
    data object Denied : ProtectedSecretError {
        override val message = "Protected secret access is denied"
    }

    /** The value exceeds the per-secret byte bound. */
    data object TooLarge : ProtectedSecretError {
        override val message = "Protected secret exceeds the size bound"
    }

    /** The stored or supplied value is not valid UTF-8. */
    data object InvalidUtf8 : ProtectedSecretError {
        override val message = "Protected secret is not valid UTF-8"
    }

    /** The supplied value is blank or otherwise invalid for storage. */
    data object InvalidValue : ProtectedSecretError {
        override val message = "Protected secret value is invalid"
    }

    /** Protected storage is unavailable or a write failed. */
    data object StorageUnavailable : ProtectedSecretError {
        override val message = "Protected credential storage is unavailable"
    }

    /** The stored ciphertext is malformed or fails authentication. */
    data object CorruptValue : ProtectedSecretError {
        override val message = "Stored protected secret is corrupt"
    }

    /** Too many concurrent protected-storage operations. */
    data object TooManyConcurrentOperations : ProtectedSecretError {
        override val message = "Too many concurrent protected-storage operations"
    }
}

sealed interface ProtectedSecretResult<out T> {
    data class Success<T>(val value: T) : ProtectedSecretResult<T>
    data class Failure(val error: ProtectedSecretError) : ProtectedSecretResult<Nothing>
}
