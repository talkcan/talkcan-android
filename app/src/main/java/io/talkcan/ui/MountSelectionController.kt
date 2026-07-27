package io.talkcan.ui

import io.talkcan.dependency.PackageMountDeclaration
import io.talkcan.model.ChannelImplementationId
import io.talkcan.mount.saf.SafMountAdapter
import io.talkcan.mount.saf.SafSelectionOutcome
import io.talkcan.mount.saf.SafTreePickerOutcome
import io.talkcan.resource.MountBinding

/**
 * 2.7: Generic mount selection request keyed by configuration owner instance,
 * provider implementation, and mount declaration ID. Never keyed by scalar
 * configuration field paths.
 */
data class MountSelectionRequest(
    val ownerInstanceId: String,
    val implementationId: ChannelImplementationId,
    val declarationId: String,
)

/**
 * 2.7: Result of one mount selection round trip.
 */
sealed interface MountSelectionResult {
    /** The picker was cancelled; the prior binding (if any) is fully retained. */
    data class RetainedPrior(val request: MountSelectionRequest) : MountSelectionResult

    /** Validation passed and the new binding was committed atomically. */
    data class Bound(val request: MountSelectionRequest, val declarationId: String) : MountSelectionResult

    /** The selection failed; the prior binding was preserved. */
    data class Failed(
        val request: MountSelectionRequest,
        val reason: MountSelectionFailure,
    ) : MountSelectionResult
}

/**
 * 2.7: Portable reasons a mount selection did not bind.
 */
enum class MountSelectionFailure {
    NO_PENDING_REQUEST,
    PICKER_RESULT_NOT_TREE,
    GRANT_NOT_PERSISTED,
    TREE_UNREACHABLE,
    REQUESTED_ACCESS_NOT_GRANTED,
    STORE_REJECTED,
}

/**
 * 2.7: Generic mount selection controller.
 *
 * Owns pending selection state keyed by owner instance + implementation +
 * declaration ID. Cancellation and validation failure retain the current
 * binding; success triggers the established atomic repository/runtime reconcile
 * path through [onBound].
 *
 * This controller is platform-neutral and JVM-testable. Android Intent creation
 * remains in MainActivity through SafTreePickerBridge.
 */
class MountSelectionController(
    private val adapter: SafMountAdapter,
    private val onBound: (MountSelectionRequest, MountBinding) -> Unit = { _, _ -> },
) {
    private var pending: MountSelectionRequest? = null
    private var pendingDeclaration: PackageMountDeclaration? = null

    val pendingRequest: MountSelectionRequest? get() = pending

    /**
     * 2.7: Begin a generic mount selection. Stores the pending request and
     * returns the declaration for Intent creation by the platform layer.
     */
    fun begin(request: MountSelectionRequest, declaration: PackageMountDeclaration): PackageMountDeclaration {
        require(request.declarationId == declaration.id) {
            "Request declaration ID must match declaration: ${request.declarationId} != ${declaration.id}"
        }
        pending = request
        pendingDeclaration = declaration
        return declaration
    }

    /**
     * 2.7: Complete the picker round trip. Cancellation and failure retain the
     * prior binding; success invokes [onBound] to trigger reconciliation.
     */
    fun complete(outcome: SafTreePickerOutcome): MountSelectionResult {
        val request = pending ?: return MountSelectionResult.Failed(
            MountSelectionRequest("", ChannelImplementationId("placeholder:invalid"), ""),
            MountSelectionFailure.NO_PENDING_REQUEST,
        )
        val declaration = pendingDeclaration ?: return MountSelectionResult.Failed(
            request,
            MountSelectionFailure.NO_PENDING_REQUEST,
        )

        pending = null
        pendingDeclaration = null

        return when (val saf = adapter.completeSelection(
            channelInstanceId = request.ownerInstanceId,
            implementationId = request.implementationId,
            declaration = declaration,
            outcome = outcome,
        )) {
            SafSelectionOutcome.RetainedPrior -> MountSelectionResult.RetainedPrior(request)
            is SafSelectionOutcome.Bound -> {
                onBound(request, saf.binding)
                MountSelectionResult.Bound(request, saf.binding.declarationId)
            }
            is SafSelectionOutcome.Failed -> MountSelectionResult.Failed(
                request,
                when (saf.failure) {
                    io.talkcan.mount.saf.SafSelectionFailure.PICKER_RESULT_NOT_TREE ->
                        MountSelectionFailure.PICKER_RESULT_NOT_TREE
                    io.talkcan.mount.saf.SafSelectionFailure.GRANT_NOT_PERSISTED ->
                        MountSelectionFailure.GRANT_NOT_PERSISTED
                    io.talkcan.mount.saf.SafSelectionFailure.TREE_UNREACHABLE ->
                        MountSelectionFailure.TREE_UNREACHABLE
                    io.talkcan.mount.saf.SafSelectionFailure.REQUESTED_ACCESS_NOT_GRANTED ->
                        MountSelectionFailure.REQUESTED_ACCESS_NOT_GRANTED
                    io.talkcan.mount.saf.SafSelectionFailure.STORE_REJECTED ->
                        MountSelectionFailure.STORE_REJECTED
                },
            )
        }
    }

    /**
     * Cancel the pending selection without touching the binding store.
     */
    fun cancel() {
        pending = null
        pendingDeclaration = null
    }
}
