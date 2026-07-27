package io.talkcan.mount.saf.vfs

import io.talkcan.model.ChannelImplementationId
import io.talkcan.mount.saf.SafGrantCodec
import io.talkcan.mount.saf.SafGrantController
import io.talkcan.mount.saf.SafGrantStatusMapper
import io.talkcan.mount.saf.SafRequestedAccess
import io.talkcan.resource.MountBindingState
import io.talkcan.resource.MountBindingStatus
import io.talkcan.resource.MountBindingStore
import io.talkcan.resource.PlatformGrantBlob
import io.talkcan.storage.FilesystemError
import io.talkcan.storage.FilesystemErrorCode
import io.talkcan.storage.FilesystemOutcome
import io.talkcan.storage.MountAccessMode
import io.talkcan.storage.MountGrantFingerprint
import io.talkcan.storage.MountLeaseFacts
import io.talkcan.storage.MountLeaseRevalidator
import io.talkcan.storage.MountResolution
import io.talkcan.storage.MountResolver
import io.talkcan.storage.NodeRef
import io.talkcan.storage.ResolvedMount

/**
 * Composition adapter that resolves a SAF document-tree backend from an opaque
 * [PlatformGrantBlob] only after live persisted-permission validation.
 *
 * Decoding the grant, confirming the platform still persists it with the
 * required access, and probing the tree root all happen here, before any
 * [ResolvedMount] is produced. The backend itself is then confined to the
 * validated tree root. No URI, document ID, or grant byte is placed on the
 * resolved mount except the opaque grant fingerprint; failures normalize to the
 * fixed portable [FilesystemErrorCode] vocabulary.
 */
class SafVfsMountFactory(
    private val grants: SafGrantController,
    private val gatewayFactory: (treeUri: String) -> SafDocumentGateway,
) {

    /**
     * Resolves one declared mount to opaque backend access for [generation].
     * Fails closed: an undecodable grant, a grant the platform no longer
     * persists, a missing requested access bit, or an unreachable tree root all
     * yield a normalized [FilesystemOutcome.Failure] and construct no backend.
     */
    fun resolve(
        grant: PlatformGrantBlob,
        declarationId: String,
        generation: Long,
        access: MountAccessMode,
    ): FilesystemOutcome<ResolvedMount> {
        val payload = SafGrantCodec.decode(grant)
            ?: return failure(FilesystemErrorCode.E_MOUNT_UNAVAILABLE, "grant undecodable")

        val persisted = grants.persistedGrants().firstOrNull { it.treeUri == payload.treeUri }
        if (persisted == null || !persisted.readPermission) {
            return failure(FilesystemErrorCode.E_REAUTHORIZATION_REQUIRED, "grant not persisted")
        }

        val gateway = gatewayFactory(payload.treeUri)
        val rootId = when (val result = gateway.treeRootId(payload.treeUri)) {
            is SafGatewayResult.Failed -> return failure(result.failure.resolveCode(), "tree root unresolved")
            is SafGatewayResult.Ok -> result.value
        }
        when (val probe = gateway.queryDocument(rootId)) {
            is SafGatewayResult.Failed -> return failure(probe.failure.resolveCode(), "tree unreachable")
            is SafGatewayResult.Ok -> Unit
        }

        val fingerprint = MountGrantFingerprint.of(grant.toByteArray())
        val backend = SafDocumentTreeBackend(gateway, rootId)
        return FilesystemOutcome.Success(
            ResolvedMount(
                mountToken = fingerprint,
                declarationId = declarationId,
                generation = generation,
                access = access,
                grantFingerprint = fingerprint,
                backend = backend,
                root = NodeRef(rootId),
            ),
        )
    }

    private fun failure(code: FilesystemErrorCode, reason: String): FilesystemOutcome.Failure =
        FilesystemOutcome.Failure(FilesystemError(code, reason))
}

/**
 * Composition [MountResolver] over the persisted [MountBindingStore].
 *
 * Looks up the active binding for the configured instance/implementation and
 * declaration, then resolves it through [SafVfsMountFactory] (which performs
 * the live grant validation). A missing or dormant binding fails closed without
 * constructing a backend.
 */
class SafVfsMountResolver(
    private val store: MountBindingStore,
    private val factory: SafVfsMountFactory,
    private val channelInstanceId: String,
    private val implementationId: ChannelImplementationId,
    private val generationProvider: () -> Long,
) : MountResolver {

    override fun resolve(declarationId: String): MountResolution {
        val binding = store.currentBinding(channelInstanceId, implementationId, declarationId)
            ?: return MountResolution.Failed(
                FilesystemError(FilesystemErrorCode.E_CAPABILITY_UNDECLARED, "no binding"),
            )
        if (binding.state != MountBindingState.ACTIVE) {
            return MountResolution.Failed(
                FilesystemError(FilesystemErrorCode.E_MOUNT_UNAVAILABLE, "binding dormant"),
            )
        }
        return when (
            val resolved = factory.resolve(
                grant = binding.grant,
                declarationId = declarationId,
                generation = generationProvider(),
                access = MountAccessMode.READ_WRITE,
            )
        ) {
            is FilesystemOutcome.Success -> MountResolution.Resolved(resolved.value)
            is FilesystemOutcome.Failure -> MountResolution.Failed(resolved.error)
        }
    }
}

/**
 * Composition [MountLeaseRevalidator] that re-checks the live SAF grant before
 * every operation and before publishing a late success.
 *
 * A changed grant fingerprint is [FilesystemErrorCode.E_STALE]; a vanished grant
 * or unreachable tree is [FilesystemErrorCode.E_REAUTHORIZATION_REQUIRED] /
 * [FilesystemErrorCode.E_MOUNT_UNAVAILABLE]. A grant that is still persisted but
 * has lost write access passes revalidation so reads continue to work; the
 * write operation itself then fails closed at the gateway.
 */
class SafMountLeaseRevalidator(
    private val store: MountBindingStore,
    private val grants: SafGrantController,
    private val implementationId: ChannelImplementationId,
) : MountLeaseRevalidator {

    override fun revalidate(facts: MountLeaseFacts): FilesystemOutcome<Unit> {
        val binding = store.currentBinding(facts.instanceId, implementationId, facts.declarationId)
            ?: return failure(FilesystemErrorCode.E_REAUTHORIZATION_REQUIRED, "binding missing")
        if (binding.state != MountBindingState.ACTIVE) {
            return failure(FilesystemErrorCode.E_MOUNT_UNAVAILABLE, "binding dormant")
        }
        if (MountGrantFingerprint.of(binding.grant.toByteArray()) != facts.grantFingerprint) {
            return failure(FilesystemErrorCode.E_STALE, "grant changed")
        }
        val payload = SafGrantCodec.decode(binding.grant)
            ?: return failure(FilesystemErrorCode.E_MOUNT_UNAVAILABLE, "grant undecodable")
        val persisted = grants.persistedGrants().firstOrNull { it.treeUri == payload.treeUri }
        val probe = if (persisted != null) grants.probeTree(payload.treeUri) else null
        val requested = SafRequestedAccess(read = true, write = true)
        return when (SafGrantStatusMapper.map(requested, persisted, probe)) {
            MountBindingStatus.AVAILABLE -> FilesystemOutcome.Success(Unit)
            // Reads remain authorized; writes fail closed per-operation at the gateway.
            MountBindingStatus.READ_ONLY -> FilesystemOutcome.Success(Unit)
            MountBindingStatus.NEEDS_REAUTHORIZATION ->
                failure(FilesystemErrorCode.E_REAUTHORIZATION_REQUIRED, "grant revoked")
            MountBindingStatus.UNAVAILABLE ->
                failure(FilesystemErrorCode.E_MOUNT_UNAVAILABLE, "tree unreachable")
        }
    }

    private fun failure(code: FilesystemErrorCode, reason: String): FilesystemOutcome<Unit> =
        FilesystemOutcome.Failure(FilesystemError(code, reason))
}

/** Maps a gateway failure observed during resolution onto the portable resolution code. */
private fun SafGatewayFailure.resolveCode(): FilesystemErrorCode = when (this) {
    SafGatewayFailure.REVOKED -> FilesystemErrorCode.E_REAUTHORIZATION_REQUIRED
    SafGatewayFailure.UNAVAILABLE -> FilesystemErrorCode.E_MOUNT_UNAVAILABLE
    SafGatewayFailure.NOT_FOUND -> FilesystemErrorCode.E_MOUNT_UNAVAILABLE
    SafGatewayFailure.UNSUPPORTED -> FilesystemErrorCode.E_UNSUPPORTED
    else -> FilesystemErrorCode.E_MOUNT_UNAVAILABLE
}
