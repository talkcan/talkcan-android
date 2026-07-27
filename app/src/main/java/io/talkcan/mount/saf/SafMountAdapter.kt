package io.talkcan.mount.saf

import io.talkcan.dependency.PackageMountDeclaration
import io.talkcan.model.ChannelImplementationId
import io.talkcan.resource.MountBinding
import io.talkcan.resource.MountBindingRemovalResult
import io.talkcan.resource.MountBindingStatus
import io.talkcan.resource.MountBindingStore
import io.talkcan.resource.MountBindingWriteResult
import io.talkcan.resource.PlatformGrantBlob
import io.talkcan.resource.mountBindingRemoval

/** Terminal result of one picker round trip through [SafMountAdapter.completeSelection]. */
sealed interface SafSelectionOutcome {
    /** 3.5: the picker was cancelled; the last valid binding (if any) is fully retained. */
    data object RetainedPrior : SafSelectionOutcome

    /** 3.3: validation passed and the new binding was committed atomically. */
    data class Bound(val binding: MountBinding) : SafSelectionOutcome

    /** The selection failed; the prior binding was preserved and no stale grant remains. */
    data class Failed(val failure: SafSelectionFailure) : SafSelectionOutcome
}

/** Portable reasons a SAF selection did not bind. No platform detail. */
enum class SafSelectionFailure {
    /** The activity result was not a `content://` document tree. */
    PICKER_RESULT_NOT_TREE,

    /** The platform refused to persist the requested grant. */
    GRANT_NOT_PERSISTED,

    /** The selected tree root could not be reached through its provider. */
    TREE_UNREACHABLE,

    /** The persisted grant does not satisfy the declaration's requested access. */
    REQUESTED_ACCESS_NOT_GRANTED,

    /** The binding store rejected the replacement; the prior binding remains. */
    STORE_REJECTED,
}

/** Terminal result of an explicit release through [SafMountAdapter]. */
sealed interface SafReleaseOutcome {
    /** No binding existed at the address. */
    data object NoBinding : SafReleaseOutcome

    /** Binding removed, but another binding still references the same grant; nothing released. */
    data object RemovedGrantStillReferenced : SafReleaseOutcome

    /** Binding removed and the now-unreferenced platform grant was released. */
    data object Released : SafReleaseOutcome

    /** Binding removed and unreferenced, but the platform no longer held the grant. */
    data object GrantAlreadyGone : SafReleaseOutcome

    /** The binding store failed the removal; bindings are unchanged. */
    data object StoreFailed : SafReleaseOutcome
}

/**
 * 3.1–3.6: Android SAF adapter for generic declared directory-tree mounts.
 *
 * Orchestrates the system picker contract, `takePersistableUriPermission`,
 * pre-commit tree validation, transactional binding replacement, live portable
 * status mapping, and reference-aware grant release. The adapter never
 * resolves trees to filesystem paths and never depends on the legacy
 * all-files storage permission; authority lives entirely in opaque persisted
 * `content://` grants behind [PlatformGrantBlob].
 *
 * Precondition: the injected [MountBindingStore] must be loaded (composition
 * calls `load()` at startup; a corrupt document is a composition repair
 * decision). Store operations are internally synchronized, so concurrent
 * adapter calls on one store instance are safe.
 */
class SafMountAdapter(
    private val store: MountBindingStore,
    private val grants: SafGrantController,
) {

    /**
     * 3.1: the exact picker intent spec for one declared mount:
     * `ACTION_OPEN_DOCUMENT_TREE` with exactly the requested read/write grant
     * flags plus the persistable grant flag.
     */
    fun selectionIntentSpec(declaration: PackageMountDeclaration): SafTreeIntentSpec {
        require(declaration.kind == io.talkcan.dependency.PackageMountKind.DIRECTORY_TREE) {
            "SAF selection supports only directory-tree mounts: ${declaration.kind.value}"
        }
        return SafTreeSelection.intentSpec(SafRequestedAccess.from(declaration.access))
    }

    /**
     * 3.2/3.3/3.5: complete one picker round trip for one declared mount.
     *
     * Cancellation and every failure path leave the previously committed
     * binding untouched; a freshly taken grant is released on any failure
     * before commit, and the store replacement itself is transactional.
     */
    fun completeSelection(
        channelInstanceId: String,
        implementationId: ChannelImplementationId,
        declaration: PackageMountDeclaration,
        outcome: SafTreePickerOutcome,
    ): SafSelectionOutcome {
        if (outcome is SafTreePickerOutcome.Cancelled) {
            return SafSelectionOutcome.RetainedPrior
        }
        val selected = outcome as SafTreePickerOutcome.Selected
        if (!selected.treeUri.startsWith(CONTENT_SCHEME_PREFIX)) {
            return SafSelectionOutcome.Failed(SafSelectionFailure.PICKER_RESULT_NOT_TREE)
        }
        val requested = SafRequestedAccess.from(declaration.access)

        // 3.2: take the persistable grant and record only what was actually granted.
        val taken = grants.takePersistable(selected.treeUri, requested)
        if (taken is SafTakeResult.Failed) {
            return SafSelectionOutcome.Failed(SafSelectionFailure.GRANT_NOT_PERSISTED)
        }
        taken as SafTakeResult.Taken

        // 3.3: validate reachability and the requested access before any store write.
        when (val probe = grants.probeTree(selected.treeUri)) {
            is SafTreeProbe.Unreachable -> {
                grants.releasePersistable(selected.treeUri)
                return SafSelectionOutcome.Failed(SafSelectionFailure.TREE_UNREACHABLE)
            }
            is SafTreeProbe.Reachable -> {
                val accessSatisfied = (!requested.read || taken.granted.read) &&
                    (!requested.write || (taken.granted.write && probe.directoryCreateSupported))
                if (!accessSatisfied) {
                    grants.releasePersistable(selected.treeUri)
                    return SafSelectionOutcome.Failed(SafSelectionFailure.REQUESTED_ACCESS_NOT_GRANTED)
                }
            }
        }

        val blob = SafGrantCodec.encode(SafGrantPayload(selected.treeUri, taken.granted))
        return when (val result = store.replaceBinding(
            declaration = declaration,
            channelInstanceId = channelInstanceId,
            implementationId = implementationId,
            grant = blob,
            status = MountBindingStatus.AVAILABLE,
        )) {
            is MountBindingWriteResult.Failed -> {
                // The store preserved the prior binding; roll back the fresh grant.
                grants.releasePersistable(selected.treeUri)
                SafSelectionOutcome.Failed(SafSelectionFailure.STORE_REJECTED)
            }
            is MountBindingWriteResult.Committed -> {
                releaseOrphanedGrants(result.replaced, result.snapshot)
                SafSelectionOutcome.Bound(result.binding)
            }
        }
    }

    /**
     * 3.4: live portable status for one declared mount, mapped from the
     * persisted grant and provider reachability to exactly `available`,
     * `read-only`, `needs-reauthorization`, or `unavailable`, without
     * exposing any Android detail.
     */
    fun currentStatus(
        channelInstanceId: String,
        implementationId: ChannelImplementationId,
        declaration: PackageMountDeclaration,
    ): MountBindingStatus {
        val binding = store.currentBinding(channelInstanceId, implementationId, declaration.id)
            ?: return MountBindingStatus.UNAVAILABLE
        val payload = SafGrantCodec.decode(binding.grant)
            ?: return MountBindingStatus.UNAVAILABLE
        val requested = SafRequestedAccess.from(declaration.access)
        val persisted = grants.persistedGrants().firstOrNull { it.treeUri == payload.treeUri }
        val probe = if (persisted != null) grants.probeTree(payload.treeUri) else null
        return SafGrantStatusMapper.map(requested, persisted, probe)
    }

    /**
     * 3.4: compute [currentStatus] and persist it onto the binding through the
     * store's status refresh so readiness/editor projections observe it.
     * A status-write failure never changes the returned portable status.
     */
    fun syncStatus(
        channelInstanceId: String,
        implementationId: ChannelImplementationId,
        declaration: PackageMountDeclaration,
    ): MountBindingStatus {
        val status = currentStatus(channelInstanceId, implementationId, declaration)
        if (store.currentBinding(channelInstanceId, implementationId, declaration.id) != null) {
            store.updateStatus(channelInstanceId, implementationId, declaration.id, status)
        }
        return status
    }

    /**
     * 3.6: remove one binding and release its platform grant only when the
     * store reports the opaque grant unreferenced by any remaining binding.
     */
    fun release(
        channelInstanceId: String,
        implementationId: ChannelImplementationId,
        declarationId: String,
    ): SafReleaseOutcome {
        val result = store.removeBinding(channelInstanceId, implementationId, declarationId)
        return toReleaseOutcome(result)
    }

    /**
     * 3.6: remove every binding of one instance, releasing only grants no
     * remaining binding (including other instances') references.
     */
    fun releaseInstance(channelInstanceId: String): SafReleaseOutcome {
        val result = store.removeInstance(channelInstanceId)
        return toReleaseOutcome(result)
    }

    private fun toReleaseOutcome(result: MountBindingRemovalResult): SafReleaseOutcome =
        when (result) {
            is MountBindingRemovalResult.Unchanged -> SafReleaseOutcome.NoBinding
            is MountBindingRemovalResult.Failed -> SafReleaseOutcome.StoreFailed
            is MountBindingRemovalResult.Committed -> {
                val unreferenced = result.removal.unreferencedGrants
                if (unreferenced.isEmpty()) {
                    SafReleaseOutcome.RemovedGrantStillReferenced
                } else {
                    var anyHeld = false
                    for (blob in unreferenced) {
                        val payload = SafGrantCodec.decode(blob) ?: continue
                        if (grants.releasePersistable(payload.treeUri)) {
                            anyHeld = true
                        }
                    }
                    if (anyHeld) SafReleaseOutcome.Released else SafReleaseOutcome.GrantAlreadyGone
                }
            }
        }

    /**
     * After a committed replacement, release the displaced grant only when the
     * pure reference classification finds no remaining binding (including the
     * new one) referencing that exact opaque blob.
     */
    private fun releaseOrphanedGrants(replaced: MountBinding?, snapshot: List<MountBinding>) {
        if (replaced == null) return
        val removal = mountBindingRemoval(removed = listOf(replaced), remaining = snapshot)
        for (blob in removal.unreferencedGrants) {
            val payload = SafGrantCodec.decode(blob) ?: continue
            grants.releasePersistable(payload.treeUri)
        }
    }

    private companion object {
        const val CONTENT_SCHEME_PREFIX = "content:"
    }
}
