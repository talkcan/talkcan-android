package io.talkcan.lua

/**
 * One normalized completion for a typed host operation claimed from the
 * kernel (contract: local://kotlin-bridge-contract.md). [success] selects the
 * resume convention:
 *
 * - `success = true` → [value] is a complete JSON document the native kernel
 *   converts to the Lua result value.
 * - `success = false` → [value] is exactly one normalized `E_*` code string.
 *
 * Adapters produce completions only; the caller owns the exactly-once resume
 * through the existing operation terminal gate, so late completions after
 * close or cancellation are suppressed by the bridge without adapter logic.
 */
internal data class TypedHostCompletion(
    val success: Boolean,
    val value: String,
) {
    init {
        require(value.isNotEmpty()) { "Typed host completion value must not be empty" }
        if (!success) {
            require(value.startsWith("E_")) { "Failure completion must carry one normalized E_* code" }
        }
    }

    companion object {
        fun failure(code: String): TypedHostCompletion = TypedHostCompletion(false, code)
    }
}

/**
 * Host adapter for one family of typed yielded operations. Implementations
 * revalidate the claim payload, perform the authorized host effect through
 * generic stores/transports (never provider-shaped objects), and return one
 * [TypedHostCompletion]. They expose no IDs, paths, aliases, leases, or
 * persistence objects in the completion value beyond the contract envelopes.
 */
internal fun interface TypedHostOperationAdapter {
    suspend fun complete(claim: HostOperationClaim.Admitted): TypedHostCompletion
}
