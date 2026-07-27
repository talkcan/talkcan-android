package io.talkcan.storage

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * 4.2: Identity of the one context that owns a set of mount leases.
 *
 * A lease is bound to exactly one Lua state ([stateId]), one channel instance
 * ([instanceId]), and one runtime generation ([generation]). The registry is
 * scoped to a single owner; a handle minted for one owner is foreign to every
 * other. Contains no platform value and no grant bytes.
 */
public data class LeaseOwner(
    val stateId: String,
    val instanceId: String,
    val generation: Long,
) {
    init {
        require(stateId.isNotEmpty()) { "stateId must not be empty" }
        require(instanceId.isNotEmpty()) { "instanceId must not be empty" }
    }
}

/**
 * 4.2: Why a lease was revoked. Recorded in the registry entry so the terminal
 * cause is observable without exposing platform detail. Mapped to the portable
 * [FilesystemErrorCode] vocabulary before it reaches a caller.
 */
public enum class MountRevocationSource {
    /** The underlying platform grant was revoked and must be reauthorized. */
    GRANT_REVOKED,

    /** The binding was atomically replaced (reselection / compatible update). */
    BINDING_REPLACED,

    /** The owning generation was closed or replaced. */
    GENERATION_CLOSED,

    /** The owning channel instance was removed. */
    INSTANCE_REMOVED,

    /** The declared access changed beneath the lease. */
    ACCESS_CHANGED,
}

/**
 * 4.2: Portable facts about one live lease, handed to a [MountLeaseRevalidator]
 * for per-operation binding revalidation. Deliberately carries no backend, root,
 * platform object, or grant bytes — only the opaque [grantFingerprint].
 */
public data class MountLeaseFacts(
    val instanceId: String,
    val declarationId: String,
    val generation: Long,
    val access: MountAccessMode,
    val grantFingerprint: String,
)

/**
 * 4.2: Live binding-status revalidation hook invoked before each operation and
 * before publishing a late success.
 *
 * The VFS core stays provider-neutral: it never touches a binding store, grant,
 * or platform object directly. The host mount/binding layer implements this hook
 * to re-check that the declaration is still bound, active, and authorized for
 * the lease's access, returning a normalized failure (`E_REAUTHORIZATION_REQUIRED`,
 * `E_MOUNT_UNAVAILABLE`, `E_READ_ONLY`, `E_STALE`, ...) when it is not. Readiness
 * is never an authorization grant; this hook is the per-operation recheck.
 */
public fun interface MountLeaseRevalidator {
    public fun revalidate(facts: MountLeaseFacts): FilesystemOutcome<Unit>
}

/**
 * 4.2: Opaque grant fingerprint derivation.
 *
 * A fingerprint is a collision-resistant digest of the exact grant bytes; it lets
 * the registry and revalidator detect that the underlying grant changed without
 * ever holding, logging, or comparing the grant bytes themselves. Two bindings
 * with byte-identical grants share a fingerprint; any change yields a new one.
 */
public object MountGrantFingerprint {
    /** Derives an opaque lowercase-hex SHA-256 fingerprint of [grantBytes]. */
    public fun of(grantBytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(grantBytes)
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) {
            sb.append(HEX[(b.toInt() shr 4) and 0x0F]).append(HEX[b.toInt() and 0x0F])
        }
        return sb.toString()
    }

    private val HEX = "0123456789abcdef".toCharArray()
}

/**
 * 4.2: Lifecycle state of one lease entry. Transitions are one-way from [Active]
 * to exactly one terminal state ([Revoked] or [Closed]); a terminal lease never
 * becomes usable again.
 */
internal sealed interface LeaseState {
    data object Active : LeaseState
    data class Revoked(val source: MountRevocationSource) : LeaseState
    data object Closed : LeaseState
}

/**
 * 4.2: One registry entry binding an unforgeable [token] to its owner, declared
 * mount, access, opaque grant fingerprint, resolved backend access, and lifecycle
 * state. Holds no platform object and no grant bytes.
 */
internal class MountLeaseEntry(
    val token: String,
    val stateId: String,
    val instanceId: String,
    val generation: Long,
    val declarationId: String,
    val access: MountAccessMode,
    val grantFingerprint: String,
    val resolved: ResolvedMount,
) {
    val state: AtomicReference<LeaseState> = AtomicReference(LeaseState.Active)

    fun facts(): MountLeaseFacts =
        MountLeaseFacts(instanceId, declarationId, generation, access, grantFingerprint)

    /** One-way transition to revoked; returns false unless this call performed it. */
    fun revoke(source: MountRevocationSource): Boolean =
        state.compareAndSet(LeaseState.Active, LeaseState.Revoked(source))

    /** One-way transition to closed; returns false unless this call performed it. */
    fun close(): Boolean = state.compareAndSet(LeaseState.Active, LeaseState.Closed)
}

/**
 * 4.2: Generation-owned mount-lease registry and lifecycle authority.
 *
 * The registry is the host-internal implementation behind [MountResolver]: it
 * performs the bounded synchronous mount lookup, mints an unforgeable opaque
 * lease token, and binds it to exactly one owner, declaration, access, grant
 * fingerprint, and revocation source. Handles handed to callers carry only the
 * token; every operation resolves and revalidates the lease here before any
 * provider access, rejecting foreign, stale, revoked, and closed handles.
 *
 * The registry never sees a platform path, URI, grant bytes, or provider object;
 * backend access is the portable [ResolvedMount] and binding status is rechecked
 * through an injected [MountLeaseRevalidator]. It is safe for concurrent
 * operation and revocation/close races: lifecycle transitions are exact-once.
 */
public class MountLeaseRegistry(
    private val owner: LeaseOwner,
    private val resolver: MountResolver,
    private val revalidator: MountLeaseRevalidator = MountLeaseRevalidator { FilesystemOutcome.Success(Unit) },
    maxReasonBytes: Int = 256,
) {
    private val sanitizer = ReasonSanitizer(maxReasonBytes)
    private val leases = ConcurrentHashMap<String, MountLeaseEntry>()
    private val currentGeneration = AtomicLong(owner.generation)
    private val closed = AtomicBoolean(false)
    private val tokenEntropy = SecureRandom()

    /** The generation new leases bind to and live leases are revalidated against. */
    public val generation: Long get() = currentGeneration.get()

    /** True once [close] has been called; no lease resolves afterwards. */
    public val isClosed: Boolean get() = closed.get()

    /** Count of leases currently in the registry (any lifecycle state). */
    public val leaseCount: Int get() = leases.size

    /**
     * Bounded synchronous mount lookup. Resolves [declarationId] through the
     * injected resolver, mints an unforgeable lease bound to the current owner
     * and generation, and returns an opaque [MountHandle] carrying only the
     * token. Resolver failures are normalized and sanitized; a thrown resolver
     * failure collapses to [FilesystemErrorCode.E_IO] without leakage.
     */
    public fun open(declarationId: String): FilesystemOutcome<MountHandle> {
        if (closed.get()) {
            return fail(FilesystemErrorCode.E_CLOSED, "mount registry is closed")
        }
        val resolution = try {
            resolver.resolve(declarationId)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            return fail(FilesystemErrorCode.E_IO, "operation failed")
        }
        return when (resolution) {
            is MountResolution.Failed -> FilesystemOutcome.Failure(sanitized(resolution.error))
            is MountResolution.Resolved -> {
                val resolved = resolution.mount
                val token = newToken()
                val entry = MountLeaseEntry(
                    token = token,
                    stateId = owner.stateId,
                    instanceId = owner.instanceId,
                    generation = currentGeneration.get(),
                    declarationId = resolved.declarationId,
                    access = resolved.access,
                    grantFingerprint = resolved.grantFingerprint,
                    resolved = resolved,
                )
                leases[token] = entry
                FilesystemOutcome.Success(MountHandle(token))
            }
        }
    }

    /**
     * Resolves a lease for one operation. Verifies the token is known to this
     * registry (exact live owner), the lease is active, its generation is still
     * current, and the injected revalidator still authorizes the binding.
     * Returns the live [ResolvedMount] or a normalized failure:
     * foreign/unknown → [FilesystemErrorCode.E_STALE]; revoked → the source's
     * mapped code; closed or stale-generation → [FilesystemErrorCode.E_CLOSED];
     * revalidation failure → the revalidator's code.
     */
    public fun resolveForOperation(token: String): FilesystemOutcome<ResolvedMount> {
        val entry = lookupLive(token)
        return when (entry) {
            is FilesystemOutcome.Failure -> entry
            is FilesystemOutcome.Success -> FilesystemOutcome.Success(entry.value.resolved)
        }
    }

    /**
     * Re-checks lease liveness without returning backend access. Used as the
     * publication guard after an operation completes, so a success that finished
     * while the lease was revoked, closed, or generation-replaced is suppressed
     * rather than published to a stale owner.
     */
    public fun publicationCheck(token: String): FilesystemOutcome<Unit> {
        val entry = lookupLive(token)
        return when (entry) {
            is FilesystemOutcome.Failure -> entry
            is FilesystemOutcome.Success -> FilesystemOutcome.Success(Unit)
        }
    }

    private fun lookupLive(token: String): FilesystemOutcome<MountLeaseEntry> {
        if (closed.get()) {
            return fail(FilesystemErrorCode.E_CLOSED, "mount registry is closed")
        }
        val entry = leases[token]
            ?: return fail(FilesystemErrorCode.E_STALE, "mount lease is not valid for this owner")
        // Defense in depth: an entry only ever originates from this registry, so a
        // mismatch means a handle forged against a different owner's registry.
        if (entry.stateId != owner.stateId || entry.instanceId != owner.instanceId) {
            return fail(FilesystemErrorCode.E_STALE, "mount lease belongs to another owner")
        }
        when (val state = entry.state.get()) {
            is LeaseState.Revoked -> return fail(revocationCode(state.source), revocationReason(state.source))
            LeaseState.Closed -> return fail(FilesystemErrorCode.E_CLOSED, "mount lease is closed")
            LeaseState.Active -> Unit
        }
        if (entry.generation != currentGeneration.get()) {
            return fail(FilesystemErrorCode.E_CLOSED, "mount generation is closed")
        }
        val revalidated = try {
            revalidator.revalidate(entry.facts())
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            return fail(FilesystemErrorCode.E_IO, "operation failed")
        }
        return when (revalidated) {
            is FilesystemOutcome.Success -> FilesystemOutcome.Success(entry)
            is FilesystemOutcome.Failure -> FilesystemOutcome.Failure(sanitized(revalidated.error))
        }
    }

    /**
     * Revokes one lease. Exact-once: only the first revocation of an active lease
     * takes effect. Returns true if this call transitioned the lease to revoked.
     */
    public fun revoke(token: String, source: MountRevocationSource): Boolean {
        val entry = leases[token] ?: return false
        return entry.revoke(source)
    }

    /**
     * Revokes every active lease (for example on binding replacement or grant
     * revocation affecting the whole instance). Returns the number of leases this
     * call transitioned to revoked.
     */
    public fun revokeAll(source: MountRevocationSource): Int {
        var count = 0
        for (entry in leases.values) {
            if (entry.revoke(source)) count++
        }
        return count
    }

    /**
     * Advances the current generation. Leases minted under an earlier generation
     * become stale and fail the generation check on their next resolution, while
     * new [open] calls bind to [newGeneration].
     */
    public fun advanceGeneration(newGeneration: Long) {
        currentGeneration.set(newGeneration)
    }

    /**
     * Closes the registry. All active leases are transitioned to closed and no
     * lease resolves afterwards. Idempotent; safe under concurrent operations.
     */
    public fun close() {
        if (!closed.compareAndSet(false, true)) return
        for (entry in leases.values) {
            entry.close()
        }
    }

    private fun revocationCode(source: MountRevocationSource): FilesystemErrorCode = when (source) {
        MountRevocationSource.GRANT_REVOKED -> FilesystemErrorCode.E_REAUTHORIZATION_REQUIRED
        MountRevocationSource.ACCESS_CHANGED -> FilesystemErrorCode.E_READ_ONLY
        MountRevocationSource.BINDING_REPLACED -> FilesystemErrorCode.E_STALE
        MountRevocationSource.GENERATION_CLOSED -> FilesystemErrorCode.E_CLOSED
        MountRevocationSource.INSTANCE_REMOVED -> FilesystemErrorCode.E_CLOSED
    }

    private fun revocationReason(source: MountRevocationSource): String = when (source) {
        MountRevocationSource.GRANT_REVOKED -> "mount grant was revoked"
        MountRevocationSource.ACCESS_CHANGED -> "mount access changed"
        MountRevocationSource.BINDING_REPLACED -> "mount binding was replaced"
        MountRevocationSource.GENERATION_CLOSED -> "mount generation is closed"
        MountRevocationSource.INSTANCE_REMOVED -> "owning instance was removed"
    }

    private fun sanitized(error: FilesystemError): FilesystemError =
        FilesystemError(error.code, sanitizer.sanitize(error.reason))

    private fun fail(code: FilesystemErrorCode, reason: String): FilesystemOutcome<Nothing> =
        FilesystemOutcome.Failure(sanitizer.error(code, reason))

    private fun newToken(): String {
        val bytes = ByteArray(24)
        tokenEntropy.nextBytes(bytes)
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            sb.append(HEX[(b.toInt() shr 4) and 0x0F]).append(HEX[b.toInt() and 0x0F])
        }
        return sb.toString()
    }

    private companion object {
        private val HEX = "0123456789abcdef".toCharArray()
    }
}
