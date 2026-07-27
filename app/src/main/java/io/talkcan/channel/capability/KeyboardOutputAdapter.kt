package io.talkcan.channel.capability

/**
 * Terminal result of one generation-scoped keyboard-output submission.
 *
 * [Completed] wraps the host's typed non-replay delivery outcome (`delivered`, `rejected`,
 * `failed`, or `indeterminate`). [Busy] proves the operation was never admitted because an
 * applicable bound was exhausted before any compilation, connection, arm, or output; callers
 * surface it as `E_BUSY`. [Closed] means the shared service is shut down. [Revoked] means the
 * owning scope or generation was revoked while the operation was queued, proving no effect began.
 */
sealed interface KeyboardOutputSubmission {
    data class Completed(val outcome: TextDeliveryOutcome) : KeyboardOutputSubmission
    data object Busy : KeyboardOutputSubmission
    data object Closed : KeyboardOutputSubmission
    data object Revoked : KeyboardOutputSubmission
}

/**
 * Generation-scoped keyboard-output adapter seam.
 *
 * The host hands runtimes only this adapter, never the shared Sleepwalker service or a raw
 * transport lease, so shared-service ownership is never transferred to a runtime. Each submission
 * is attributed to the adapter's instance, generation, and execution owner, revalidates the logical
 * profile and request bounds inside the host, and is admitted through the one host-wide bounded FIFO
 * scheduler shared with the built-in Keyboard path. The adapter exposes no connection, keymap, HID,
 * acknowledgement, address, or transport control.
 */
interface KeyboardOutputAdapter {
    val identity: CapabilityScopeIdentity

    suspend fun sendText(request: TextOutputRequest): KeyboardOutputSubmission

    suspend fun sendKey(request: TextKeyRequest): KeyboardOutputSubmission
}
