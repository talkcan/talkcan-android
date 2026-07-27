package io.talkcan.secret

/**
 * Provider-neutral protected secret storage. Implementations own encryption, key management,
 * and persistence. No keystore alias, platform object, or encryption detail crosses this boundary.
 *
 * Mutations follow a prepare/commit/rollback protocol so that owning metadata (profile records)
 * never commits against a failed protected mutation:
 *
 * 1. `prepare*` applies the protected-storage change immediately and returns a
 *    [ProtectedSecretResult] wrapping a [PreparedSecretMutation]. A [ProtectedSecretResult.Failure]
 *    means the protected mutation was NOT applied — the caller MUST NOT persist metadata.
 * 2. On prepare success the caller persists its metadata.
 * 3. On metadata success: [PreparedSecretMutation.commit] finalizes (e.g., retires predecessor references).
 * 4. On metadata failure: [PreparedSecretMutation.rollback] undoes the prepared change.
 */
interface ProtectedSecretStore {
    /**
     * Prepares a write of [plaintext] under [reference]. On success the encrypted value is
     * already persisted; rollback removes it. Returns [ProtectedSecretResult.Failure] without
     * touching storage when the value is blank, oversized, or the write fails.
     */
    fun prepareCreate(reference: ProtectedSecretReference, plaintext: CharSequence): ProtectedSecretResult<PreparedSecretMutation>

    /**
     * Prepares a replacement: writes [plaintext] under [newReference] and holds [oldReference]
     * for retirement. Commit retires [oldReference] (cleanup failure is reported but the new
     * value is already committed). Rollback removes [newReference] and preserves [oldReference].
     * Returns [ProtectedSecretResult.Failure] without touching storage when the write fails.
     */
    fun prepareReplace(
        oldReference: ProtectedSecretReference,
        newReference: ProtectedSecretReference,
        plaintext: CharSequence,
    ): ProtectedSecretResult<PreparedSecretMutation>

    /**
     * Prepares clearing the value under [reference]. The ciphertext is retained for rollback.
     * Commit discards the retained ciphertext. Rollback restores it. Clearing an absent
     * reference succeeds idempotently.
     */
    fun prepareClear(reference: ProtectedSecretReference): ProtectedSecretResult<PreparedSecretMutation>

    /**
     * Prepares deleting the value under [reference]. The ciphertext is retained for rollback.
     * Commit discards the retained ciphertext. Rollback restores it. Deleting an absent
     * reference succeeds idempotently.
     */
    fun prepareDelete(reference: ProtectedSecretReference): ProtectedSecretResult<PreparedSecretMutation>

    /** Presence check; does not decrypt or reveal the value. */
    fun contains(reference: ProtectedSecretReference): Boolean

    /**
     * Decrypts the value under [reference] and passes it to [block] inside a scoped callback.
     * The plaintext is not retained after [block] returns.
     */
    fun <T> use(reference: ProtectedSecretReference, block: (CharSequence) -> T): ProtectedSecretResult<T>
}

/**
 * A successfully prepared protected-storage mutation. Exactly one of [commit] or [rollback]
 * MUST be called. Calling neither leaves the prepared change applied (create/replace) or
 * removed (clear/delete) without finalization.
 */
interface PreparedSecretMutation {
    /**
     * Finalizes the mutation. For [ProtectedSecretStore.prepareReplace], this retires the
     * predecessor reference; retirement failure is reported as
     * [ProtectedSecretError.StorageUnavailable] but the new value is already committed.
     * For other operations, commit is a no-op that returns [ProtectedSecretResult.Success].
     */
    fun commit(): ProtectedSecretResult<Unit>

    /**
     * Undoes the prepared mutation. For create: removes the written value. For replace: removes
     * the new value and preserves the old. For clear/delete: restores the retained ciphertext.
     */
    fun rollback(): ProtectedSecretResult<Unit>
}
